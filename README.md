# MCLauncher

A Minecraft launcher for Windows, macOS and Linux. Written in Java 17 with JavaFX.

It downloads Mojang versions, installs Fabric, Forge and NeoForge when you press Play, and launches
the game with an offline or online account. Name a loader profile to keep multiple modpacks for the
same Minecraft and loader versions; each profile has its own game and mods folder.

## What works

| Feature | Status |
| --- | --- |
| Vanilla versions from Mojang's API | working |
| Fabric | working |
| Forge | working |
| NeoForge | working, Minecraft 1.20.2 and newer |
| Offline play | working |
| Ely.by / authlib-injector, Microsoft accounts | implemented, not verified against live services |
| Mods | a profile's `mods` folder is opened and counted; no in-app list yet |
| Forge / NeoForge in the window | working, with Forge's required opt-in and Patreon link |
| Picking an exact loader build | working, lists every published build for that Minecraft version |
| Creating named modpack profiles | working, choose a loader/build/name and press Play |
| Download performance | libraries and assets download concurrently, with duplicate assets shared |
| Launcher log on disk | working, `~/.mclauncher/launcher.log` |
| Multiplayer, chat, P2P, skins | not started |

Verified on 2026-10-02: 70 unit tests, `uiCheck` 94 passed, and `smokeLaunch` reaching the loading
stage for vanilla, Fabric, Forge and NeoForge. See `OPEN_QUESTIONS.md` for the details behind the
loader support and for what is deliberately not done yet.

NeoForge does not exist for Minecraft 1.20.1. It took over from Forge at 1.20.2, so on 1.20.1 the
loader to use is Forge.

## Building and running

Requires a JDK 17 or newer. JavaFX and Gson are resolved by Gradle, so there is nothing else to
install.

```sh
./gradlew run          # Windows: gradlew.bat run
./gradlew build        # compiles, tests and assembles
./gradlew installDist  # produces build/install/MCLauncher
./gradlew packageApp   # self-contained Windows app with bundled Java
./gradlew packagePortableZip  # build/distributions/MCLauncher-0.1.0-windows-x64.zip
./gradlew packageWindowsMsi  # build/distributions/MCLauncher-0.1.0.msi
./gradlew packageWindowsExe  # build/distributions/MCLauncher-0.1.0.exe
```

The portable ZIP contains `MCLauncher.exe`, its own Java runtime, and a per-user PowerShell installer.
Extract the ZIP, then run `Install-MCLauncher.ps1`; it installs under `%LOCALAPPDATA%\Programs`,
creates Start menu and desktop shortcuts, and needs no admin rights. `Uninstall-MCLauncher.ps1` removes
the application but preserves settings, wallpaper and logs in `%USERPROFILE%\.mclauncher`.

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\Install-MCLauncher.ps1
```

This installs the launcher, not Minecraft game files: the launcher downloads Minecraft and the chosen
loader from their official sources when needed. The MSI and EXE tasks download the official portable
WiX 3.14.1 bundle, verify its SHA-256, and keep it under ignored `build/tools`; no system-wide WiX
installation is needed.

Windows installer outputs are:

- `build/distributions/MCLauncher-0.1.0.msi`
- `build/distributions/MCLauncher-0.1.0.exe`
- `build/distributions/MCLauncher-0.1.0-windows-x64.zip` for the portable copy

Both native installers are configured for the current user and add Start menu and desktop shortcuts.

## Verification

Every one of these runs against the real thing: real downloads, the real JavaFX window, the real
game process. They are not mocked.

```sh
./gradlew test
./gradlew uiCheck         # builds the real window and asserts what the user would see
./gradlew probeServices   # checks the third-party endpoints the launcher depends on
./gradlew headlessCheck "-PmcVersion=1.20.1"
```

`smokeLaunch` installs the profile if needed, starts the game, watches its log and exits once the
loading stage is reached:

```sh
./gradlew smokeLaunch "-PmcVersion=1.20.1"
./gradlew smokeLaunch "-PmcVersion=1.20.1" "-Ploader=fabric"
./gradlew smokeLaunch "-PmcVersion=1.20.1" "-Ploader=forge" "-PloaderVersion=47.4.23"
./gradlew smokeLaunch "-PmcVersion=1.20.4" "-Ploader=neoforge"
```

Omit `-PloaderVersion` to get the newest build for that Minecraft version. The run asserts that the
requested loader specifically came up, so a Fabric profile cannot pass by quietly falling back to
vanilla.

These default to a scratch directory so a run never touches the real install. To reproduce a profile
in the real one, point them at it:

```sh
./gradlew smokeLaunch "-PmcVersion=1.20.1" "-Ploader=forge" "-PgameDir=$HOME/MCLauncher"
```

## How it is put together

- `Main`, `LauncherApp` — startup.
- `ui/LauncherView`, `ui/LauncherController` — the window and its behaviour, built in code rather
  than FXML so there is no layout file to keep in sync.
- `mc/` — version metadata, download, inheritance flattening, library resolution, native
  extraction, runtime install, and the launch command.
- `mc/CommandBuilder` — turns a version plus an account into a JVM command line. Forge and NeoForge
  need special handling: FML builds its module layer from `-DlegacyClassPath`, so they must not use
  `-cp`. `CommandBuilderTest.fmlLaunchesFromLegacyClassPathInsteadOfCp` guards that.
- `mc/ModLoader*`, `mc/InstallerJarRunner` — loader metadata, profiles, and running the official
  installers.
- `auth/` — offline, Ely.by and Microsoft sign-in, plus authlib-injector.
- `config/LauncherConfig` — settings, kept in `~/.mclauncher` so they survive moving the game folder.
- `util/FileLog` — mirrors the window's log into `~/.mclauncher/launcher.log`.

Versions, libraries, assets and Java runtimes all live under the install root, laid out the way the
official launcher does, so the two agree about paths. Settings and the log sit outside it on purpose:
they describe the installation rather than belonging to it.

## Notes for contributors

`OPEN_QUESTIONS.md` records decisions that were made under uncertainty, and why. It is the first
thing to read before changing how a loader is launched.