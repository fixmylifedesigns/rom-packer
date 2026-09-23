package com.fixmylife.romplayer

import android.app.AlertDialog
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.swordfish.libretrodroid.GLRetroView
import com.swordfish.libretrodroid.GLRetroViewData
import com.swordfish.libretrodroid.ShaderConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import kotlin.coroutines.resume
import kotlin.math.abs

class GameActivity : ComponentActivity() {

    private var retroView: GLRetroView? = null
    private var gameReady = false
    private var lastPhysicalDpad = 0 to 0

    private val saves by lazy { SaveManager(this) }
    private val quickStateFile by lazy { File(filesDir, "quick.state") }
    private var gameTitle = "game"
    private var pendingExportSlot: SaveManager.Slot? = null
    private lateinit var menu: GameMenu

    // ---- storage access framework plumbing ----

    private val importBatteryLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        val bytes = readUri(uri) ?: return@registerForActivityResult toast("Couldn't read that file")
        if (bytes.isEmpty()) return@registerForActivityResult toast("That file is empty")
        // Battery saves are applied at load time, so restart the emulator with it in place.
        if (saves.writeAtomic(saves.batteryFile, bytes)) {
            toast("Battery save imported, restarting")
            recreate()
        } else {
            toast("Import failed")
        }
    }

    private val exportBatteryLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        uri ?: return@registerForActivityResult
        val view = retroView
        val bytes = if (view != null && gameReady) {
            runCatching { view.serializeSRAM() }.getOrNull() ?: saves.readBattery()
        } else {
            saves.readBattery()
        }
        if (bytes == null || bytes.isEmpty()) return@registerForActivityResult toast("This game has no battery save")
        toast(if (writeUri(uri, bytes)) "Exported" else "Export failed")
    }

    private val importStateLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        val bytes = readUri(uri) ?: return@registerForActivityResult toast("Couldn't read that file")
        val view = retroView ?: return@registerForActivityResult
        val ok = runCatching { view.unserializeState(bytes) }.getOrDefault(false)
        toast(if (ok) "State loaded" else "That state isn't for this game")
    }

    private val exportStateLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val slot = pendingExportSlot
        pendingExportSlot = null
        uri ?: return@registerForActivityResult
        val bytes = slot?.file?.takeIf { it.isFile }?.readBytes()
            ?: return@registerForActivityResult toast("That slot is empty")
        toast(if (writeUri(uri, bytes)) "Exported" else "Export failed")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        goImmersive()

        val config = runCatching {
            JSONObject(assets.open("game.json").bufferedReader().use { it.readText() })
        }.getOrNull()

        if (config == null) {
            showMessage("No game packaged.\n\nThis is the ROM Packer player template. Use ROM Packer to build a game APK.")
            return
        }

        val romFile = runCatching { extractRom(config.getString("rom")) }.getOrElse {
            showMessage("Couldn't unpack the ROM: ${it.message}")
            return
        }
        val isGba = config.optString("system") == "gba"
        gameTitle = config.optString("title").ifBlank { "game" }

        val data = GLRetroViewData(this).apply {
            coreFilePath = "libmgba_libretro_android.so"
            gameFilePath = romFile.absolutePath
            systemDirectory = filesDir.absolutePath
            savesDirectory = filesDir.absolutePath
            saveRAMState = saves.readBattery()
            shader = ShaderConfig.Sharp
            rumbleEventsEnabled = false
            preferLowLatencyAudio = true
        }

        val view = GLRetroView(this, data)
        retroView = view
        lifecycle.addObserver(view)

        val pad = VirtualPad(this, showShoulders = isGba, listener = object : VirtualPad.Listener {
            override fun onButton(keyCode: Int, pressed: Boolean) {
                view.sendKeyEvent(if (pressed) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP, keyCode)
            }
            override fun onDpad(x: Int, y: Int) {
                view.sendMotionEvent(GLRetroView.MOTION_SOURCE_DPAD, x.toFloat(), y.toFloat())
            }
            override fun onQuickSave() = quickSave()
            override fun onQuickLoad() = quickLoad()
            override fun onMenu() = menu.show()
        })

        menu = GameMenu(this, saves, menuActions)

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        root.addView(view, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(pad, FrameLayout.LayoutParams(MATCH, MATCH))
        setContentView(root)

        lifecycleScope.launch {
            view.getGLRetroEvents().collect { event ->
                if (event is GLRetroView.GLRetroEvents.FrameRendered && !gameReady) {
                    gameReady = true
                    view.viewport = viewportFor(resources.configuration)
                    offerAutoResume()
                }
            }
        }
        lifecycleScope.launch {
            view.getGLRetroErrors().collect { code ->
                val msg = when (code) {
                    GLRetroView.ERROR_LOAD_LIBRARY -> "Couldn't load the emulator core on this device."
                    GLRetroView.ERROR_LOAD_GAME -> "The emulator couldn't load this ROM."
                    else -> "Emulator error ($code)."
                }
                Toast.makeText(this@GameActivity, msg, Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    /** Copies the ROM out of the APK once per install/update (the core needs a real file). */
    private fun extractRom(name: String): File {
        val out = File(filesDir, name)
        val installed = packageManager.getPackageInfo(packageName, 0).lastUpdateTime
        if (!out.isFile || out.lastModified() < installed) {
            val tmp = File(filesDir, "$name.part")
            assets.open(name).use { input -> tmp.outputStream().use { input.copyTo(it) } }
            tmp.renameTo(out)
        }
        return out
    }

    override fun onPause() {
        super.onPause()
        // Lifecycle observers (incl. the emulator) are already paused here,
        // so read SRAM directly instead of queueing onto the GL thread.
        val view = retroView ?: return
        if (!gameReady) return
        runCatching {
            val sram = view.serializeSRAM(false)
            if (sram.isNotEmpty()) saves.writeAtomic(saves.batteryFile, sram)
        }.onFailure { Log.w(TAG, "SRAM save failed", it) }
        runCatching {
            val state = view.serializeState(false)
            if (state.isNotEmpty()) saves.writeAtomic(saves.autoStateFile, state)
        }.onFailure { Log.w(TAG, "Auto state save failed", it) }
    }

    private fun quickSave() {
        val view = retroView ?: return
        if (!gameReady) return
        lifecycleScope.launch {
            val bytes = runCatching { view.serializeState() }.getOrNull()
            val ok = bytes != null && withContext(Dispatchers.IO) { saves.writeAtomic(quickStateFile, bytes) }
            toast(if (ok) "Quick state saved" else "Save failed")
        }
    }

    private fun quickLoad() {
        val view = retroView ?: return
        if (!gameReady) return
        if (!quickStateFile.isFile) {
            toast("No quick state yet \u2014 tap SAVE first")
            return
        }
        lifecycleScope.launch {
            val bytes = withContext(Dispatchers.IO) { runCatching { quickStateFile.readBytes() }.getOrNull() }
            val ok = bytes != null && runCatching { view.unserializeState(bytes) }.getOrDefault(false)
            toast(if (ok) "Quick state loaded" else "Load failed")
        }
    }

    // ---- menu actions ----

    private val menuActions = object : GameMenu.Actions {
        override fun saveToSlot(slot: SaveManager.Slot) {
            val view = retroView ?: return
            if (!gameReady) return
            lifecycleScope.launch {
                val bytes = runCatching { view.serializeState() }.getOrNull()
                if (bytes == null) return@launch toast("Save failed")
                val thumb = captureThumbnail(view)
                val ok = withContext(Dispatchers.IO) {
                    val wrote = saves.writeAtomic(slot.file, bytes)
                    if (wrote && thumb != null) {
                        runCatching {
                            slot.thumb.outputStream().use { thumb.compress(Bitmap.CompressFormat.PNG, 90, it) }
                        }
                    }
                    wrote
                }
                toast(if (ok) "Saved to slot ${slot.index}" else "Save failed")
            }
        }

        override fun loadFromSlot(slot: SaveManager.Slot) {
            val view = retroView ?: return
            lifecycleScope.launch {
                val bytes = withContext(Dispatchers.IO) { runCatching { slot.file.readBytes() }.getOrNull() }
                val ok = bytes != null && runCatching { view.unserializeState(bytes) }.getOrDefault(false)
                toast(if (ok) "Loaded slot ${slot.index}" else "Load failed")
            }
        }

        override fun importBatterySave() = importBatteryLauncher.launch(arrayOf("*/*"))

        override fun exportBatterySave() = exportBatteryLauncher.launch(saves.exportName(gameTitle, "sav"))

        override fun importState() = importStateLauncher.launch(arrayOf("*/*"))

        override fun exportState(slot: SaveManager.Slot) {
            pendingExportSlot = slot
            exportStateLauncher.launch(saves.exportName("$gameTitle slot ${slot.index}", "state"))
        }

        override fun resetGame() {
            retroView?.reset()
        }

        override fun setFastForward(speed: Int) {
            retroView?.frameSpeed = speed
            toast(if (speed == 1) "Normal speed" else "Fast-forward ${speed}x")
        }

        override fun setShader(index: Int) {
            retroView?.shader = when (index) {
                1 -> ShaderConfig.Default
                2 -> ShaderConfig.LCD
                3 -> ShaderConfig.CRT
                else -> ShaderConfig.Sharp
            }
            toast("Filter: ${GameMenu.SHADERS[index]}")
        }

        override fun setMuted(muted: Boolean) {
            retroView?.audioEnabled = !muted
        }
    }

    /** Grabs the current frame for a slot thumbnail. Returns null if the copy fails. */
    private suspend fun captureThumbnail(view: GLRetroView): Bitmap? =
        suspendCancellableCoroutine { cont ->
            runCatching {
                val width = 256
                val height = (width * view.height / view.width.coerceAtLeast(1)).coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                PixelCopy.request(view, bitmap, { result ->
                    cont.resume(if (result == PixelCopy.SUCCESS) bitmap else null)
                }, Handler(Looper.getMainLooper()))
            }.onFailure { cont.resume(null) }
        }

    /** Offers the state written when the game was last closed. */
    private fun offerAutoResume() {
        val view = retroView ?: return
        val auto = saves.autoStateFile
        if (!auto.isFile || auto.length() == 0L) return
        AlertDialog.Builder(this)
            .setTitle("Resume?")
            .setMessage("Pick up exactly where you left off, or start from the game's own save.")
            .setPositiveButton("Resume") { _, _ ->
                val ok = runCatching { view.unserializeState(auto.readBytes()) }.getOrDefault(false)
                if (!ok) toast("Couldn't resume")
            }
            .setNegativeButton("Start fresh", null)
            .show()
    }

    private fun readUri(uri: Uri): ByteArray? =
        runCatching { contentResolver.openInputStream(uri)!!.use { it.readBytes() } }.getOrNull()

    private fun writeUri(uri: Uri, bytes: ByteArray): Boolean =
        runCatching { contentResolver.openOutputStream(uri)!!.use { it.write(bytes) } }.isSuccess

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (gameReady) retroView?.viewport = viewportFor(newConfig)
        goImmersive()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) goImmersive()
    }

    // ---- physical controllers ----

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val view = retroView
        val mapped = GAMEPAD_KEYS[event.keyCode]
        if (view != null && mapped != null && event.repeatCount == 0 &&
            (event.action == KeyEvent.ACTION_DOWN || event.action == KeyEvent.ACTION_UP)
        ) {
            view.sendKeyEvent(event.action, mapped)
            return true
        }
        if (mapped != null) return true // swallow repeats
        return super.dispatchKeyEvent(event)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        val view = retroView
        if (view != null && event.isFromSource(InputDevice.SOURCE_JOYSTICK) &&
            event.action == MotionEvent.ACTION_MOVE
        ) {
            fun axis(hat: Int, stick: Int): Int {
                val h = event.getAxisValue(hat)
                if (abs(h) > 0.5f) return if (h > 0) 1 else -1
                val s = event.getAxisValue(stick)
                return if (s > 0.5f) 1 else if (s < -0.5f) -1 else 0
            }
            val dir = axis(MotionEvent.AXIS_HAT_X, MotionEvent.AXIS_X) to axis(MotionEvent.AXIS_HAT_Y, MotionEvent.AXIS_Y)
            if (dir != lastPhysicalDpad) {
                lastPhysicalDpad = dir
                view.sendMotionEvent(GLRetroView.MOTION_SOURCE_DPAD, dir.first.toFloat(), dir.second.toFloat())
            }
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    // ---- helpers ----

    /** Portrait: game in the top half, controls below. Landscape: full screen. */
    private fun viewportFor(config: Configuration) =
        if (config.orientation == Configuration.ORIENTATION_PORTRAIT) RectF(0f, 0f, 1f, 0.52f) else RectF(0f, 0f, 1f, 1f)

    private fun goImmersive() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun showMessage(msg: String) {
        setContentView(TextView(this).apply {
            text = msg
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.BLACK)
            gravity = Gravity.CENTER
            textSize = 18f
            setPadding(48, 48, 48, 48)
        })
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        private const val TAG = "RomPlayer"
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT

        /**
         * Physical controller \u2192 what we send to the core. Android's A/B (Xbox layout)
         * are swapped relative to RetroPad, same as LibretroDroid does internally:
         * bottom face button = GB "B", right face button = GB "A".
         */
        private val GAMEPAD_KEYS = mapOf(
            KeyEvent.KEYCODE_BUTTON_A to KeyEvent.KEYCODE_BUTTON_B,
            KeyEvent.KEYCODE_BUTTON_B to KeyEvent.KEYCODE_BUTTON_A,
            KeyEvent.KEYCODE_BUTTON_X to KeyEvent.KEYCODE_BUTTON_B,
            KeyEvent.KEYCODE_BUTTON_Y to KeyEvent.KEYCODE_BUTTON_A,
            KeyEvent.KEYCODE_BUTTON_L1 to KeyEvent.KEYCODE_BUTTON_L1,
            KeyEvent.KEYCODE_BUTTON_R1 to KeyEvent.KEYCODE_BUTTON_R1,
            KeyEvent.KEYCODE_BUTTON_L2 to KeyEvent.KEYCODE_BUTTON_L1,
            KeyEvent.KEYCODE_BUTTON_R2 to KeyEvent.KEYCODE_BUTTON_R1,
            KeyEvent.KEYCODE_BUTTON_START to KeyEvent.KEYCODE_BUTTON_START,
            KeyEvent.KEYCODE_BUTTON_SELECT to KeyEvent.KEYCODE_BUTTON_SELECT,
            KeyEvent.KEYCODE_DPAD_UP to KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN to KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT to KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT to KeyEvent.KEYCODE_DPAD_RIGHT,
        )
    }
}
