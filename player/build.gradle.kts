import java.net.URI

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ABIs to ship. arm64-v8a covers basically every phone from the last ~8 years.
// Add x86_64 (for the Android Studio emulator) in gradle.properties: rompack.abis=arm64-v8a,x86_64
val coreAbis = (findProperty("rompack.abis") as String? ?: "arm64-v8a")
    .split(",").map { it.trim() }.filter { it.isNotEmpty() }

/** Downloads the mGBA libretro core (handles GB, GBC and GBA) built for LibretroDroid. */
abstract class DownloadCore : DefaultTask() {
    @get:Input abstract val abis: ListProperty<String>
    @get:Input abstract val baseUrl: Property<String>
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun download() {
        abis.get().forEach { abi ->
            val dest = outputDir.file("$abi/libmgba_libretro_android.so").get().asFile
            if (dest.isFile && dest.length() > 0) return@forEach
            dest.parentFile.mkdirs()
            val url = "${baseUrl.get()}/$abi/libmgba_libretro_android.so"
            logger.lifecycle("Downloading mGBA core: $url")
            val tmp = File(dest.path + ".part")
            URI(url).toURL().openStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
            check(tmp.length() > 100_000) { "Core download looks wrong: $url" }
            tmp.renameTo(dest)
        }
    }
}

val downloadCore = tasks.register<DownloadCore>("downloadMgbaCore") {
    abis.set(coreAbis)
    // Same core builds Lemuroid ships with (matches LibretroDroid 0.13.x).
    baseUrl.set("https://github.com/Swordfish90/LemuroidCores/raw/1.17.0/lemuroid_core_mgba/src/main/jniLibs")
}

android {
    namespace = "com.fixmylife.romplayer"
    compileSdk = 35

    defaultConfig {
        // Placeholder. ROM Packer rewrites this per game. Must NOT share a prefix with `namespace`.
        applicationId = "fixmylife.rompack.template"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        ndk { abiFilters += coreAbis }
    }

    buildTypes {
        release {
            // Keep resource paths readable (res/mipmap-*/ic_launcher.png) so the packer can swap icons.
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = null // unsigned; ROM Packer signs each generated game
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    packaging {
        // Compressed + extracted at install: no page-alignment requirements when repacking.
        jniLibs { useLegacyPackaging = true }
    }

    lint {
        // The literal label is intentional (it's a placeholder the packer replaces).
        disable += setOf("HardcodedText", "MissingTranslation")
        checkReleaseBuilds = false
    }
}

// Placeholder launcher icons. ROM Packer replaces these per game.
val playerIcons = tasks.register<GenerateLauncherIcons>("generateLauncherIcons") {
    background.set(0xFF4A4458.toInt())
    backgroundEnd.set(0xFF26222E.toInt())
    body.set(0xFFC8C8D2.toInt())
    accent.set(0xFF3A3644.toInt())
}

androidComponents {
    onVariants { variant ->
        variant.sources.jniLibs?.addGeneratedSourceDirectory(downloadCore, DownloadCore::outputDir)
        variant.sources.res?.addGeneratedSourceDirectory(playerIcons, GenerateLauncherIcons::outputDir)
    }
}

dependencies {
    implementation("com.github.Swordfish90:LibretroDroid:0.13.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
