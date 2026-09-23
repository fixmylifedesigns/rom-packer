package com.fixmylife.rompacker

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

class MainActivity : ComponentActivity() {

    private lateinit var romInfoText: TextView
    private lateinit var romMetaText: TextView
    private lateinit var iconCrop: IconCropView
    private lateinit var gameCode: EditText
    private lateinit var appName: EditText
    private lateinit var packageId: EditText
    private lateinit var buildBtn: Button
    private lateinit var installBtn: Button
    private lateinit var saveBtn: Button
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView

    private var romBytes: ByteArray? = null
    private var romInfo: RomInfo? = null
    private var builtApk: File? = null
    private var iconSource: Bitmap? = null
    private var userPickedIcon = false
    private var packageEdited = false

    private val pickRom = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(::loadRom)
    }

    private val pickIcon = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri ?: return@registerForActivityResult
        lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) { runCatching { IconMaker.loadSquare(contentResolver, uri) }.getOrNull() }
            if (bmp == null) {
                toast("Couldn't read that image")
            } else {
                userPickedIcon = true
                setIconSource(bmp)
            }
        }
    }

    private val saveApk = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.android.package-archive")
    ) { uri ->
        val apk = builtApk ?: return@registerForActivityResult
        uri ?: return@registerForActivityResult
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    contentResolver.openOutputStream(uri)!!.use { out -> apk.inputStream().use { it.copyTo(out) } }
                }.isSuccess
            }
            toast(if (ok) "Saved" else "Save failed")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        romInfoText = findViewById(R.id.romInfo)
        romMetaText = findViewById(R.id.romMeta)
        iconCrop = findViewById(R.id.iconCrop)
        gameCode = findViewById(R.id.gameCode)
        appName = findViewById(R.id.appName)
        packageId = findViewById(R.id.packageId)
        buildBtn = findViewById(R.id.build)
        installBtn = findViewById(R.id.install)
        saveBtn = findViewById(R.id.save)
        progress = findViewById(R.id.progress)
        status = findViewById(R.id.status)

        findViewById<Button>(R.id.pickRom).setOnClickListener { pickRom.launch(arrayOf("*/*")) }
        findViewById<Button>(R.id.pickIcon).setOnClickListener {
            pickIcon.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        findViewById<Button>(R.id.rotateIcon).setOnClickListener { iconCrop.rotate() }
        findViewById<Button>(R.id.resetIcon).setOnClickListener {
            iconCrop.resetAdjustments()
            listOf(R.id.brightness, R.id.contrast, R.id.saturation).forEach {
                findViewById<SeekBar>(it).progress = 50
            }
            iconCrop.setBitmap(iconSource)
        }
        findViewById<Button>(R.id.findCover).setOnClickListener { findCover() }
        findViewById<TextView>(R.id.version).text = "build ${Updater.installedVersion(this)}"
        findViewById<Button>(R.id.checkUpdate).setOnClickListener { checkForUpdate(manual = true) }

        slider(R.id.brightness, R.id.brightnessValue) {
            iconCrop.brightness = (it - 50) / 50f
            "${it - 50}"
        }
        // 0 -> 0.5x, 50 -> neutral, 100 -> 2.5x
        slider(R.id.contrast, R.id.contrastValue) {
            val value = if (it <= 50) 0.5f + it / 100f else 1f + (it - 50) / 33f
            iconCrop.contrast = value
            "${(value * 100).toInt()}%"
        }
        slider(R.id.saturation, R.id.saturationValue) {
            iconCrop.saturation = it / 50f
            "${it * 2}%"
        }

        buildBtn.setOnClickListener { build() }
        installBtn.setOnClickListener { install() }
        saveBtn.setOnClickListener { builtApk?.let { saveApk.launch(it.name) } }

        appName.doAfterTextChanged {
            if (!packageEdited && appName.hasFocus()) {
                packageId.setText(ManifestRewriter.suggestPackage(it.toString()))
            }
        }
        packageId.doAfterTextChanged { if (packageId.hasFocus()) packageEdited = true }

        if (Updater.shouldAutoCheck(this)) checkForUpdate(manual = false)

        handleIncoming(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncoming(intent)
    }

    /** A ROM opened from a file manager, the Downloads list, or the share sheet. */
    private fun handleIncoming(incoming: Intent?) {
        val source = incoming ?: return
        val uri = when (source.action) {
            Intent.ACTION_VIEW -> source.data
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(source, Intent.EXTRA_STREAM, Uri::class.java)
            else -> null
        } ?: return
        // Clear it so a later recreate doesn't reload the same file over the user's work.
        source.action = null
        loadRom(uri)
    }

    /** Compares the installed build number against the newest GitHub release. */
    private fun checkForUpdate(manual: Boolean) {
        if (manual) toast("Checking\u2026")
        lifecycleScope.launch {
            val release = withContext(Dispatchers.IO) { Updater.latest() }
            Updater.markChecked(this@MainActivity)
            val installed = Updater.installedVersion(this@MainActivity)
            when {
                release == null -> if (manual) toast("Couldn't reach GitHub")
                release.versionCode <= installed ->
                    if (manual) toast("You're on the latest build ($installed)")
                else -> AlertDialog.Builder(this@MainActivity)
                    .setTitle("Update available")
                    .setMessage(
                        "You have build $installed. Build ${release.versionCode} is out " +
                            "(${release.size / 1024 / 1024} MB). Download and install it?"
                    )
                    .setPositiveButton("Update") { _, _ -> downloadUpdate(release) }
                    .setNegativeButton("Later", null)
                    .show()
            }
        }
    }

    private fun downloadUpdate(release: Updater.Release) {
        setBusy(true, "Downloading ${release.tag}\u2026")
        lifecycleScope.launch {
            val apk = withContext(Dispatchers.IO) {
                Updater.download(this@MainActivity, release) { pct ->
                    lifecycleScope.launch { status.text = "Downloading ${release.tag}\u2026 $pct%" }
                }
            }
            if (apk == null) {
                setBusy(false, "Update download failed")
            } else {
                setBusy(false, "Ready to install ${release.tag}")
                launchInstaller(apk)
            }
        }
    }

    private fun loadRom(uri: Uri) {
        val fileName = displayName(uri)
        lifecycleScope.launch {
            val bytes = withContext(Dispatchers.IO) {
                runCatching { contentResolver.openInputStream(uri)!!.use { it.readBytes() } }.getOrNull()
            }
            val info = bytes?.let { RomInfo.detect(it, fileName) }
            if (bytes == null || info == null) {
                toast("That doesn't look like a GB / GBC / GBA ROM")
                return@launch
            }
            if (bytes.size > 64 * 1024 * 1024) {
                toast("ROM is larger than 64 MB, that's not a GB/GBA game")
                return@launch
            }
            romBytes = bytes
            romInfo = info
            romInfoText.text = fileName ?: "ROM"
            romMetaText.text = buildString {
                append(info.system.label).append(" \u00b7 ").append(bytes.size / 1024).append(" KB")
                if (info.headerTitle.isNotBlank()) append(" \u00b7 ").append(info.headerTitle)
            }
            appName.setText(info.suggestedName)
            if (!packageEdited) packageId.setText(ManifestRewriter.suggestPackage(info.suggestedName))
            if (iconSource == null) setIconSource(IconMaker.placeholder(info.suggestedName, info.system))
            gameCode.setText(CoverArt.headerSerial(bytes, info.system).orEmpty())
            buildBtn.isEnabled = true
            autoFetchCover(bytes, info)
        }
    }

    private fun build() {
        val rom = romBytes ?: return
        val info = romInfo ?: return
        val label = appName.text.toString().trim().ifEmpty { info.suggestedName }
        val pkg = packageId.text.toString().trim()
        if (!ManifestRewriter.isValidPackage(pkg)) {
            packageId.error = "Use letters/digits separated by dots, e.g. com.fixmylife.rom.zelda"
            return
        }
        if (pkg == packageName) {
            packageId.error = "That's this app's own id"
            return
        }

        val art = iconCrop.export(512) ?: IconMaker.placeholder(label, info.system)
        setBusy(true, "Building $label\u2026")
        lifecycleScope.launch {
            val result = withContext(Dispatchers.Default) {
                runCatching { buildApk(rom, info, label, pkg, art) }
            }
            result.onSuccess { apk ->
                builtApk = apk
                setBusy(false, "Done: ${apk.name} (${apk.length() / 1024} KB)\nPackage: $pkg")
                installBtn.isEnabled = true
                saveBtn.isEnabled = true
            }.onFailure { e ->
                builtApk = null
                setBusy(false, "Build failed: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    private fun buildApk(rom: ByteArray, info: RomInfo, label: String, pkg: String, art: Bitmap): File {
        val work = File(cacheDir, "build").apply { mkdirs() }
        work.listFiles()?.forEach { it.delete() }

        val template = templateApk()
        val unsigned = File(work, "unsigned.apk")
        val romName = "rom.${info.system.ext}"
        val gameJson = JSONObject()
            .put("title", label)
            .put("rom", romName)
            .put("system", info.system.ext)
            .toString()

        ApkRepacker.repack(
            template = template,
            output = unsigned,
            newPackage = pkg,
            label = label,
            extraFiles = mapOf(
                "assets/game.json" to gameJson.toByteArray(),
                "assets/$romName" to rom,
            ),
        ) { layer, px -> IconMaker.render(art, layer, px) }

        val slug = pkg.substringAfterLast('.')
        val signed = File(work, "$slug.apk")
        Signer(this).sign(unsigned, signed)
        unsigned.delete()
        return signed
    }

    /** Copies the bundled player template out of assets (refreshed when this app updates). */
    private fun templateApk(): File {
        val file = File(filesDir, "template.apk")
        val updated = packageManager.getPackageInfo(packageName, 0).lastUpdateTime
        if (!file.isFile || file.lastModified() < updated) {
            assets.open("template.apk").use { input -> file.outputStream().use { input.copyTo(it) } }
        }
        return file
    }

    private fun install() {
        launchInstaller(builtApk ?: return)
    }

    private fun launchInstaller(apk: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            toast("Allow ROM Packer to install apps, then try again")
            startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
            )
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.files", apk)
        startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    private fun setBusy(busy: Boolean, message: String) {
        progress.visibility = if (busy) View.VISIBLE else View.GONE
        buildBtn.isEnabled = !busy && romBytes != null
        if (busy) {
            installBtn.isEnabled = false
            saveBtn.isEnabled = false
        }
        status.text = message
    }

    private fun displayName(uri: Uri): String? =
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    /** Wires a slider and keeps its readout in sync; the lambda returns the label. */
    private fun slider(id: Int, labelId: Int, apply: (Int) -> String) {
        val label = findViewById<TextView>(labelId)
        findViewById<SeekBar>(id).setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                label.text = apply(value)
            }

            override fun onStartTrackingTouch(bar: SeekBar) = Unit
            override fun onStopTrackingTouch(bar: SeekBar) = Unit
        })
    }

    private fun setIconSource(bitmap: Bitmap) {
        iconSource = bitmap
        iconCrop.setBitmap(bitmap)
    }

    /** Silently tries the ROM's own checksum, which works for all three systems. */
    private fun autoFetchCover(rom: ByteArray, info: RomInfo) {
        lifecycleScope.launch {
            val art = withContext(Dispatchers.IO) {
                runCatching {
                    val match = CoverArt.byChecksum(this@MainActivity, info.system, rom)
                        ?: return@runCatching null
                    CoverArt.fetchBoxart(info.system, match.name)
                }.getOrNull()
            }
            if (art != null && !userPickedIcon) {
                setIconSource(art)
                status.text = "Cover art found for this ROM"
            }
        }
    }

    private fun findCover() {
        val info = romInfo ?: return toast("Choose a ROM first")
        val query = gameCode.text.toString().trim()
        if (query.isEmpty()) return toast("Enter a game code or title")
        setBusy(true, "Looking up $query\u2026")
        lifecycleScope.launch {
            val matches = withContext(Dispatchers.IO) {
                runCatching { CoverArt.search(this@MainActivity, info.system, query, romBytes) }.getOrDefault(emptyList())
            }
            setBusy(false, "")
            when {
                matches.isEmpty() -> toast("No match. Try the game's title instead.")
                matches.size == 1 -> loadCover(info, matches.first())
                else -> AlertDialog.Builder(this@MainActivity)
                    .setTitle("Which one?")
                    .setItems(matches.map { it.name }.toTypedArray()) { _, i -> loadCover(info, matches[i]) }
                    .show()
            }
        }
    }

    private fun loadCover(info: RomInfo, match: CoverArt.Match) {
        setBusy(true, "Downloading cover\u2026")
        lifecycleScope.launch {
            val art = withContext(Dispatchers.IO) {
                runCatching { CoverArt.fetchBoxart(info.system, match.name) }.getOrNull()
            }
            if (art == null) {
                setBusy(false, "No box art published for ${match.name}")
            } else {
                userPickedIcon = true
                setIconSource(art)
                setBusy(false, match.name)
            }
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
