import java.util.Base64
import java.util.zip.ZipFile

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/**
 * Copies the player's unsigned release APK into this app's assets as
 * template.apk, after checking it has what the packer needs.
 */
abstract class CopyPlayerTemplate : DefaultTask() {
    @get:InputFile
    abstract val templateApk: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun run() {
        val src = templateApk.get().asFile
        ZipFile(src).use { zip ->
            val names = zip.entries().asSequence().map { it.name }.toList()
            val icons = names.filter { Regex("^res/mipmap-[a-z]+(-v\\d+)?/ic_launcher(_foreground|_background)?\\.png$").matches(it) }
            check(icons.isNotEmpty()) {
                val resEntries = names.filter { it.startsWith("res/") }.sorted()
                "Player template has no res/mipmap-*/ic_launcher*.png entries, so ROM Packer could not " +
                    "swap icons per game. AGP's optimizeReleaseResources shortens resource paths; keep " +
                    "android.enableResourceOptimizations=false in gradle.properties. " +
                    "Resource entries found (${resEntries.size}): " + resEntries.take(40).joinToString(", ")
            }
            check(names.any { it.startsWith("lib/") && it.endsWith("libmgba_libretro_android.so") }) {
                "Player template is missing the mGBA core (lib/*/libmgba_libretro_android.so)."
            }
        }
        val out = outputDir.get().asFile
        out.mkdirs()
        src.copyTo(File(out, "template.apk"), overwrite = true)
    }
}

// The release keystore is stored base64-encoded so the repo stays text-only.
val releaseKeystore: File = layout.buildDirectory.file("rompacker.jks").get().asFile.also { jks ->
    if (!jks.isFile) {
        jks.parentFile.mkdirs()
        jks.writeBytes(Base64.getDecoder().decode(file("rompacker.jks.b64").readText().trim()))
    }
}

val copyPlayerTemplate = tasks.register<CopyPlayerTemplate>("copyPlayerTemplate") {
    dependsOn(":player:assembleRelease")
    templateApk.set(
        rootProject.layout.projectDirectory.file("player/build/outputs/apk/release/player-release-unsigned.apk")
    )
}

android {
    namespace = "com.fixmylife.rompacker"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.fixmylife.rompacker"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        // Fixed key so every CI build of ROM Packer installs over the previous one.
        // Only signs ROM Packer itself; generated games use a key created on your phone.
        create("rompacker") {
            storeFile = releaseKeystore
            storePassword = "rompacker"
            keyAlias = "rompacker"
            keyPassword = "rompacker"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("rompacker")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                "META-INF/BCKEY.DSA", "META-INF/BCKEY.SF",
                "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*",
            )
        }
    }
}

val builderIcons = tasks.register<GenerateLauncherIcons>("generateLauncherIcons") {
    background.set(0xFF7B2FF7.toInt())
    body.set(0xFFF0F0F5.toInt())
    accent.set(0xFF7B2FF7.toInt())
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(copyPlayerTemplate, CopyPlayerTemplate::outputDir)
        variant.sources.res?.addGeneratedSourceDirectory(builderIcons, GenerateLauncherIcons::outputDir)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("com.android.tools.build:apksig:8.5.2")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.78.1")
}
