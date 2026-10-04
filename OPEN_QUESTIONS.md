# Open questions

Decisions and findings that are not yet settled. Written down instead of interrupting work.

## Forge / NeoForge launching (resolved)

Status: all three loaders launch. `smokeLaunch` passes for vanilla, Fabric, Forge and NeoForge.

The fix was the classpath, not the module path. FML's `BootstrapLauncher` builds its `MC-BOOTSTRAP`
layer from `-DlegacyClassPath`, so for a Forge or NeoForge version:

- `-cp` must be absent. With one, the layer stays empty and the game dies with
  `Module layer not found: MC-BOOTSTRAP`.
- `-DlegacyClassPath` holds every library from the version JSON plus the FML language jars. None of
  them is listed in the version JSON's `libraries`, so they are discovered from disk by walking
  `library_directory` and matching the names in `-DignoreList` (`fmlcore`, `javafmllanguage`,
  `lowcodelanguage`, `mclanguage`, `forge-`). Forge has several jars per artifact; the `-universal`
  one wins over the client one.
- `modlauncher` is already a normal JSON library and supplies the `java.util.function.Consumer` the
  loader looks up through `ServiceLoader` from its `module-info`, which is why it has to be in the
  layer even though no jar declares `META-INF/services`.
- Mojang's `1.20.1.jar` must be on neither path. On the classpath it overlaps the `_1._20._1` module
  and breaks the layer; on the module path the loader derives the module name from the file name and
  throws `Invalid module name: '1'`. `jarsplitter`'s output already provides the `minecraft` and
  `_1._20._1` modules, so the full jar is not needed.
- The module path itself is left exactly as the version JSON declared it.

`CommandBuilderTest.fmlLaunchesFromLegacyClassPathInsteadOfCp` locks this in.

What is verified:

- The Forge installer runs to completion (`Successfully installed client into launcher.`) and
  produces a complete version: `versions/1.20.1-forge-47.4.23/1.20.1-forge-47.4.23.json` with
  `inheritsFrom=1.20.1`, `mainClass=cpw.mods.bootstraplauncher.BootstrapLauncher`, 29 libraries,
  no `downloads` block.
- A clean-room install produces a byte-for-byte equivalent JSON (only formatting differs), so the
  install is not truncated or corrupted by our own post-processing.
- The installer needs `launcher_profiles.json` to exist even for `--installClient`.
- Forge creates **no** version jar. That is by design, not a broken install: a fresh install in an
  empty directory also produces none.
- The client-side processor is `jarsplitter`, which splits the vanilla client jar into
  `client-1.20.1-20230612.114412-slim.jar` (12.6 MB, the `minecraft` module),
  `client-1.20.1-20230612.114412-extra.jar` (10.4 MB, the `_1._20._1` module) and
  `client-1.20.1-20230612.114412-srg.jar` (18.8 MB), plus the mappings file. All four land in
  `libraries/net/minecraft/client/1.20.1-20230612.114412/`.

## Loader install naming

Installers name the version they write, not the launcher that requested it: Forge writes
`1.20.1-forge-47.4.23` while NeoForge writes `neoforge-20.4.251`. Predicting the path and checking
it after the installer exits rejects successful installs, so `ModLoaderInstaller` now snapshots the
existing version ids first and then launches whatever the installer actually added. `smokeLaunch`
launches the returned id for the same reason.

## Forge installation automation (settled)

The generated Forge version JSON carries a `_comment_` asking that Forge not be downloaded and
installed automatically, and asking that its Patreon be linked. The loader picker now installs any
of Fabric, Forge or NeoForge, and for Forge only: the opt-in checkbox starts unticked and must be
ticked to proceed, and the Patreon link is on screen at the same time. Both are asserted in
`UiCheck`, so the box cannot quietly disappear later.

NeoForge is not held to this. Its version JSON does not carry the request, and inventing an opt-in
for it would be a false claim about where the rule comes from.

## NeoForge version mapping (resolved for 1.20.2+)

The NeoForge maven API returns a flat version list with no Minecraft mapping, so
`ModLoaderCatalog` hardcodes a table. There is deliberately no 1.20.1 entry: the `20.1.x` line was
never published, because NeoForge took over from Forge at 1.20.2. Asking for NeoForge on 1.20.1 now
fails immediately with a clear message instead of chasing a version that does not exist.

`1.20.2` onward is confirmed against a real install, `1.20.4` with `20.4.251`. Every install
verifies `inheritsFrom` against the expected Minecraft version, so a wrong entry fails loudly rather
than launching the wrong game.

## Known gaps

- `LauncherController.deleteAllVersions()` looked like it might delete more than intended. It does
  not: `VersionStore.deleteAll()` walks `installed()`, which only counts a directory that has a
  client jar or is a mod loader profile. Covered by `deleteAllLeavesAnythingElseAlone`.

## Loader identity and version listing

Both were derived from the loader's own naming rather than written out by hand, after reading how
ElyPrismLauncher does it.

- A version JSON is typed by the maven group of the library its installer wrote: `net.minecraftforge`,
  `net.neoforged` or `net.fabricmc`. That group is the only part of an installed profile this launcher
  did not author, so a profile installed by another launcher, or by hand, is recognised on the same
  terms as one we installed. Two loader groups at once reads as unrecognised rather than a guess,
  since a confident wrong answer would put a profile in the build picker that cannot be installed.
  Our own `modloader` marker is still read, second, for profiles written before this was derived.
  See `ModLoaderCatalog.detectFromLibraries`.
- The NeoForge line is derived by dropping the leading `1` from the Minecraft version: `1.20.4` is
  served by `20.4.x`, `1.21` by `21.0.x`. The hand-written table this replaced had to be edited for
  every new Minecraft release or the launcher reported the version as unsupported, and it drifted
  silently in between: it had no entry for `1.21.9` or `1.22`, which would have kept working long
  after NeoForge shipped them. Verified against the live release list, where `1.20.1` correctly finds
  nothing because NeoForge took over from Forge at 1.20.2.
- No published index maps Minecraft versions to NeoForge lines, so the mapping has to be derived
  rather than read. Forge needs no equivalent, as its maven is keyed by Minecraft version already.

## Verified on 2026-10-02

`clean test build installDist` green, 57 unit tests, `uiCheck` 71 passed. Smoke results:

| profile | result |
| --- | --- |
| `1.20.1` vanilla | `PASS: vanilla reached the loading stage` |
| `fabric-1.20.1-0.19.5` | `PASS: fabric reached the loading stage` |
| `1.20.1-forge-47.4.23` | `PASS: forge reached the loading stage` |
| `neoforge-20.4.251` on `1.20.4` | `PASS: neoforge reached the loading stage` |

## Deferred by request

- Redesign of the interface, pending a new specification.
- Multiplayer and chat. Chat is per sender and recipient with an offline queue on the sender's
  side. P2P needs signalling plus STUN/TURN, or UPnP with a relay fallback.
- TLauncher has no confirmed public API.
- Skin upload/editing is deferred. The account pane displays the head from an official Mojang
  profile texture when available; offline and unsupported third-party profiles use the fallback.

## Build metadata

Named loader profiles keep their display name in the profile's own version JSON. The launcher
discovers those profiles from disk instead of maintaining a parallel `builds.json` index, so a
deleted, copied or externally installed profile cannot leave a stale list entry behind.
