package com.fixmylife.rompacker

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
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

class MainActivity : ComponentActivity() {

    private lateinit var romInfoText: TextView
    private lateinit var iconPreview: ImageView
    private lateinit var appName: EditText
    private lateinit var packageId: EditText
    private lateinit var buildBtn: Button
    private lateinit var installBtn: Button
    private lateinit var saveBtn: Button
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView

    private var romBytes: ByteArray? = null
    private var romInfo: RomInfo? = null
    private var icon: Bitmap? = null
    private var builtApk: File? = null
    private var packageEdited = false

    private val pickRom = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(::loadRom)
    }

    private val pickIcon = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri ?: return@registerForActivityResult
        lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) { runCatching { IconMaker.loadSquare(contentResolver, uri) }.getOrNull() }
            if (bmp == null) toast("Couldn't read that image") else {
                icon = bmp
                iconPreview.setImageBitmap(bmp)
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
        iconPreview = findViewById(R.id.iconPreview)
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
        buildBtn.setOnClickListener { build() }
        installBtn.setOnClickListener { install() }
        saveBtn.setOnClickListener { builtApk?.let { saveApk.launch(it.name) } }

        appName.doAfterTextChanged {
            if (!packageEdited && appName.hasFocus()) {
                packageId.setText(ManifestRewriter.suggestPackage(it.toString()))
            }
        }
        packageId.doAfterTextChanged { if (packageId.hasFocus()) packageEdited = true }
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
            romInfoText.text = buildString {
                append(fileName ?: "ROM").append('\n')
                append(info.system.label).append(" · ").append(bytes.size / 1024).append(" KB")
                if (info.headerTitle.isNotBlank()) append(" · header: ").append(info.headerTitle)
            }
            appName.setText(info.suggestedName)
            if (!packageEdited) packageId.setText(ManifestRewriter.suggestPackage(info.suggestedName))
            if (icon == null) iconPreview.setImageBitmap(IconMaker.placeholder(info.suggestedName, info.system))
            buildBtn.isEnabled = true
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

        setBusy(true, "Building $label…")
        lifecycleScope.launch {
            val result = withContext(Dispatchers.Default) {
                runCatching {
                    val art = icon ?: IconMaker.placeholder(label, info.system)
                    buildApk(rom, info, label, pkg, art)
                }
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
        val apk = builtApk ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            toast("Allow ROM Packer to install apps, then tap Install again")
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

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
