package com.fixmylife.romplayer

import android.content.res.Configuration
import android.graphics.Color
import android.graphics.RectF
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.swordfish.libretrodroid.GLRetroView
import com.swordfish.libretrodroid.GLRetroViewData
import com.swordfish.libretrodroid.ShaderConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import kotlin.math.abs

class GameActivity : ComponentActivity() {

    private var retroView: GLRetroView? = null
    private var gameReady = false
    private var lastPhysicalDpad = 0 to 0

    private val sramFile by lazy { File(filesDir, "game.srm") }
    private val stateFile by lazy { File(filesDir, "quick.state") }

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

        val data = GLRetroViewData(this).apply {
            coreFilePath = "libmgba_libretro_android.so"
            gameFilePath = romFile.absolutePath
            systemDirectory = filesDir.absolutePath
            savesDirectory = filesDir.absolutePath
            saveRAMState = sramFile.takeIf { it.isFile }?.readBytes()
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
        })

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        root.addView(view, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(pad, FrameLayout.LayoutParams(MATCH, MATCH))
        setContentView(root)

        lifecycleScope.launch {
            view.getGLRetroEvents().collect { event ->
                if (event is GLRetroView.GLRetroEvents.FrameRendered && !gameReady) {
                    gameReady = true
                    view.viewport = viewportFor(resources.configuration)
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
            if (sram.isNotEmpty()) {
                val tmp = File(filesDir, "game.srm.part")
                tmp.writeBytes(sram)
                tmp.renameTo(sramFile)
            }
        }.onFailure { Log.w(TAG, "SRAM save failed", it) }
    }

    private fun quickSave() {
        val view = retroView ?: return
        if (!gameReady) return
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { stateFile.writeBytes(view.serializeState()) }.isSuccess
            }
            toast(if (ok) "State saved" else "Save failed")
        }
    }

    private fun quickLoad() {
        val view = retroView ?: return
        if (!gameReady) return
        if (!stateFile.isFile) {
            toast("No saved state yet")
            return
        }
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { view.unserializeState(stateFile.readBytes()) }.getOrDefault(false)
            }
            toast(if (ok) "State loaded" else "Load failed")
        }
    }

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
         * Physical controller → what we send to the core. Android's A/B (Xbox layout)
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
