# ROM Packer

An Android app that turns a Game Boy / Game Boy Color / Game Boy Advance ROM + an icon into its **own installable app**, built right on your phone.

Each generated game gets its own package id, name, launcher icon, and save data, and runs on the mGBA core via [LibretroDroid](https://github.com/Swordfish90/LibretroDroid).

## Download

Grab `RomPacker.apk` from the [latest release](../../releases/latest). Every push to `main` builds a new one.

## Using it

1. Open **ROM Packer** and tap **Choose ROM** (`.gb`, `.gbc`, `.gba`). The system is detected from the cartridge header.
2. Optionally pick an icon (box art works great). If you skip it, a tile with the game's initials is generated.
3. Check the app name and package id. The package id must be unique per game.
4. Tap **Build APK**, then **Install**. The first time, Android asks you to allow ROM Packer to install apps.

To update a game (new icon, patched ROM), rebuild it with the **same package id** and install over it. Saves are kept.

## In the generated game

- On-screen D-pad, A/B, Start/Select, plus L/R for GBA.
- **SAVE / LOAD** buttons for a quick save state.
- In-game saves (SRAM) are written automatically when you leave the app.
- Bluetooth/USB controllers work. The right face button is A and the bottom face button is B.
- Portrait shows the game on top with controls below. Landscape puts the controls on the sides.

## How it works

```
player/   Emulator app built once as an unsigned "template" APK
builder/  ROM Packer app, which bundles the template in assets/template.apk
```

When you tap Build, ROM Packer:

1. **Patches the binary `AndroidManifest.xml`** (`AxmlPatcher.kt`). It rebuilds the string pool, swapping the placeholder package `fixmylife.rompack.template` for your package id (including AndroidX `<pkg>.androidx-startup` / `<pkg>.DYNAMIC_RECEIVER…` entries, so several games can coexist) and the placeholder label for the game name.
2. **Replaces the launcher icons** in `res/mipmap-*/` for the legacy icon and the adaptive foreground/background layers.
3. **Adds** `assets/rom.<ext>` and `assets/game.json`.
4. **Signs** it with apksig (v1+v2+v3) using a key generated on first use and kept in the app's private storage.

If you uninstall ROM Packer, that key is gone. Games built afterwards can't install over old ones; you'd need to uninstall the old game first, which loses its saves.

## Building locally

Open the folder in Android Studio (JDK 17) and run the `builder` configuration. From a terminal with Gradle 8.9 installed:

```
gradle :builder:assembleRelease
```

The repo is text-only. Launcher icons are drawn at build time (`buildSrc/`), and ROM Packer's own signing keystore is stored base64-encoded (`builder/rompacker.jks.b64`). That key only signs ROM Packer itself.

The mGBA core is downloaded automatically at build time. To test in the x86_64 emulator, set `rompack.abis=arm64-v8a,x86_64` in `gradle.properties`.

Only use ROMs you've dumped from cartridges you own.

— fixmylife
