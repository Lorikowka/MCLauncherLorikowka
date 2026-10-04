package com.mymods.mclauncher.ui;

import java.awt.Desktop;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import com.google.gson.JsonObject;
import com.mymods.mclauncher.GameStarter;
import com.mymods.mclauncher.auth.AuthProviders;
import com.mymods.mclauncher.auth.AuthProviderInfo;
import com.mymods.mclauncher.auth.AuthSession;
import com.mymods.mclauncher.auth.Authenticator;
import com.mymods.mclauncher.auth.LoginRequest;
import com.mymods.mclauncher.auth.OfflineAuthenticator;
import com.mymods.mclauncher.auth.SkinService;
import com.mymods.mclauncher.auth.TokenStore;
import com.mymods.mclauncher.config.BannerStore;
import com.mymods.mclauncher.config.LauncherConfig;
import com.mymods.mclauncher.mc.GameProcess;
import com.mymods.mclauncher.mc.JavaRuntimes;
import com.mymods.mclauncher.mc.LaunchPreflight;
import com.mymods.mclauncher.mc.McVersion;
import com.mymods.mclauncher.mc.ModLoaderCatalog;
import com.mymods.mclauncher.mc.ModLoaderInstaller;
import com.mymods.mclauncher.mc.ModLoaderType;
import com.mymods.mclauncher.mc.ModProfile;
import com.mymods.mclauncher.mc.ModProfileStore;
import com.mymods.mclauncher.mc.ServerAddress;
import com.mymods.mclauncher.mc.VersionCatalog;
import com.mymods.mclauncher.mc.VersionResolver;
import com.mymods.mclauncher.mc.VersionStore;
import com.mymods.mclauncher.util.FileLog;
import com.mymods.mclauncher.util.Util;
import javafx.application.Platform;
import javafx.stage.FileChooser;

/**
 * Wires the widgets to the launcher services and keeps all long work off the UI thread.
 *
 * <p>Callbacks from background threads are marshalled back with {@link Platform#runLater}.
 *
 * <p>Every widget state is derived in {@link #updateUiState} from three inputs: whether a session
 * exists, whether something is installing, and whether the game process is alive. Nothing else
 * touches button text or visibility, so the three states cannot drift apart.
 */
public final class LauncherController {

    /**
     * Shown next to the Forge opt-in, because Forge's version JSON asks that its Patreon be linked
     * wherever the launcher offers to install it.
     */
    private static final String FORGE_SUPPORT_URL = "https://www.patreon.com/MinecraftForge";

    /** Shown in the build picker while the real list is still being fetched. */
    private static final String BUILDS_LOADING = "загрузка...";

    /** Loader and version whose build list is already in the picker, to avoid refetching. */
    private String buildsKey;

    private final LauncherView view;
    private final LauncherConfig config;
    private final Path dataDir;
    private final TokenStore tokens;
    private final VersionCatalog catalog;
    private final BannerStore banners;

    /** Same lines as the window shows, but kept on disk so a failure survives closing it. */
    private final java.util.function.Consumer<String> fileLog;
    private final ConcurrentLinkedQueue<String> pendingUiLogs = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean uiLogFlushScheduled = new AtomicBoolean();

    /**
     * Credentials from the first attempt, so Ely's 2FA retry can resend login and password without
     * making the user type them again. Cleared once the exchange succeeds.
     */
    private final Map<String, String> pendingCredentials = new HashMap<>();

    // Login and init workers write these, the UI thread reads them (and stopGame writes running),
    // so each write has to become visible across threads rather than staying in one CPU's cache.
    private volatile AuthSession session;
    private volatile String accountUsername = "";
    private volatile boolean needsRelogin;
    private volatile GameProcess running;
    private volatile String providerId;
    private boolean installing;
    /** Set when the launcher hid itself for a running game, so the exit knows to bring it back. */
    private boolean hiddenForGame;

    /** Last ten-percent step already written to the log, so progress lines are not duplicated. */
    private int loggedBucket = -1;

    private VersionStore store() {
        return new VersionStore(config.versionsDir(), config.assetsDir());
    }

    private ModProfileStore profiles() {
        return new ModProfileStore(config.versionsDir());
    }

    /** The selected entry, whether it is a mod loader profile or a plain Mojang version. */
    private ModProfile selectedProfile() {
        return profiles().find(selectedVersionId());
    }

    public void createLoaderProfile() {
        String baseId = selectedBaseVersionId();
        ModLoaderType loader = selectedLoader();
        if (!loader.moddable()) {
            error("Выбери загрузчик", new IOException("Для Vanilla профиль создавать не нужно"));
            return;
        }
        if (catalog.find(baseId) == null) {
            error("Выбери версию Minecraft", new IOException(baseId));
            return;
        }
        if (loader == ModLoaderType.FORGE && !view.forgeOptIn.isSelected()) {
            view.flashForgeConsent();
            return;
        }
        if (gameRunning() || installing) {
            error("Нельзя создать сборку", new IllegalStateException(
                    "Дождись завершения текущей операции."));
            return;
        }

        String build = selectedBuild();
        String name = view.profileNameField.getText().trim();
        installing = true;
        setBusy(true);
        view.setStatus("Создаю профиль " + loader.displayName() + " ...");
        view.progressBar.setProgress(-1);
        view.progressBar.setVisible(true);
        view.progressBar.setManaged(true);
        view.progressLabel.setText("Устанавливаю loader profile ...");
        updateUiState();

        Thread worker = new Thread(() -> {
            try {
                ModProfile profile = ensureLoaderProfile(baseId, loader, build, name);
                ModProfile created = profile;
                Platform.runLater(() -> {
                    addProfileToPicker(created.id());
                    view.versionBox.getSelectionModel().select(created.id());
                    view.profileNameField.clear();
                    view.profileInfo.setText(created.displayName() + " готова к запуску");
                    view.log("Сборка создана: " + created.displayName());
                    config.lastVersionId = created.id();
                    config.save(dataDir);
                    installing = false;
                    setBusy(false);
                    view.hideProgress();
                    refreshManagedProfileButtons();
                    updateUiState();
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    installing = false;
                    setBusy(false);
                    view.hideProgress();
                    updateUiState();
                    error("Не удалось создать сборку", e);
                });
            }
        }, "launcher-create-profile");
        worker.setDaemon(true);
        worker.start();
    }

    /** The build the sidebar list points at, falling back to the picker when it is not focused. */
    private String selectedBuildId() {
        String id = view.buildList.getSelectionModel().getSelectedItem();
        return id != null ? id : view.versionBox.getValue();
    }

    private void selectManagedProfile() {
        view.showLaunch();
        updateUiState();
    }

    private void showManagedProfileDetails() {
        ModProfile profile = profiles().find(selectedBuildId());
        if (profile == null) {
            return;
        }
        javafx.scene.control.Alert alert = new javafx.scene.control.Alert(
                javafx.scene.control.Alert.AlertType.INFORMATION);
        alert.setTitle("Сведения о сборке");
        alert.setHeaderText(profile.displayName());
        alert.setContentText("Minecraft " + profile.baseVersion() + "\n"
            + profile.loader().displayName() + " " + profile.loaderVersion() + "\n"
            + "ID: " + profile.id());
        alert.showAndWait();
    }

    private void deleteManagedProfile() {
        String id = selectedBuildId();
        ModProfile profile = profiles().find(id);
        if (profile == null) {
            return;
        }
        if (gameRunning() || installing) {
            error("Нельзя удалить сборку", new IllegalStateException("Сначала дождись завершения игры"));
            return;
        }
        if (!confirm("Удалить сборку «" + profile.displayName() + "»?\n"
                + "Её файлы и mods-папка будут удалены; общие библиотеки останутся.")) {
            return;
        }
        boolean wasSelectedForPlay = id.equals(selectedVersionId());
        String base = profile.baseVersion();
        runDiskAction("launcher-delete-profile", "Не удалось удалить сборку",
                () -> store().delete(id),
                () -> {
                    if (wasSelectedForPlay) {
                        view.versionBox.getSelectionModel().select(base);
                    }
                    view.versionBox.getItems().remove(id);
                });
    }

    private void refreshManagedProfileButtons() {
        String id = selectedBuildId();
        boolean selected = id != null && !id.isBlank();
        view.openBuildFolderItem.setDisable(!selected);
        view.buildInfoItem.setDisable(!selected);
        view.deleteBuildItem.setDisable(!selected || installing || gameRunning());
    }

    /**
     * Loads the builds offered for the chosen loader on the chosen Minecraft version.
     *
     * <p>Deliberately not called from the state refresh: that runs on every keystroke in the
     * editable version box, and each load is a network request. It is bound to the two things that
     * actually change the answer, the loader and the version, and the pair already loaded is cached
     * so switching back and forth costs nothing.
     */
    private void loadBuilds() {
        String baseId = selectedBaseVersionId();
        ModLoaderType loader = selectedLoader();
        // Only vanilla catalog versions can be used as a profile base.
        boolean installable = loader.moddable()
                && !baseId.isBlank()
                && catalog.find(baseId) != null;
        if (!installable) {
            buildsKey = null;
            view.buildPicker.getItems().clear();
            view.buildPicker.setDisable(true);
            return;
        }

        String key = loader.name() + "@" + baseId;
        if (key.equals(buildsKey)) {
            return;
        }
        buildsKey = key;
        view.buildPicker.getItems().setAll(BUILDS_LOADING);
        view.buildPicker.getSelectionModel().selectFirst();
        view.buildPicker.setDisable(true);

        Thread worker = new Thread(() -> {
            List<String> builds;
            String failure = null;
            try {
                builds = ModLoaderCatalog.builds(loader, baseId);
            } catch (Exception e) {
                builds = List.of();
                failure = e.getMessage();
            }
            List<String> result = builds;
            String problem = failure;
            Platform.runLater(() -> {
                // The user may have changed the loader or version while this was in flight.
                if (!key.equals(buildsKey)) {
                    return;
                }
                view.buildPicker.getItems().setAll(result);
                if (result.isEmpty()) {
                    view.buildPicker.getItems().setAll(
                            problem == null ? "нет сборок" : "не удалось получить список");
                }
                view.buildPicker.getSelectionModel().selectFirst();
                view.buildPicker.setDisable(false);
                if (problem != null) {
                    view.log(loader.displayName() + ": " + problem);
                }
            });
        }, "loader-builds-" + loader.name().toLowerCase(Locale.ROOT));
        worker.setDaemon(true);
        worker.start();
    }

    /** The build chosen in the picker, or null when the newest should be resolved instead. */
    private String selectedBuild() {
        String value = view.buildPicker.getValue();
        if (value == null || value.isBlank() || BUILDS_LOADING.equals(value)
                || "нет сборок".equals(value)) {
            return null;
        }
        return value;
    }

    /** The loader chosen in the picker, defaulting to plain Minecraft. */
    private ModLoaderType selectedLoader() {
        String name = view.loaderPicker.getValue();
        for (ModLoaderType loader : ModLoaderType.values()) {
            if (loader.displayName().equals(name)) {
                return loader;
            }
        }
        return ModLoaderType.VANILLA;
    }

    /**
     * Keeps the Forge opt-in and its support link in step with the picker.
     *
     * <p>Only Forge asks for this. NeoForge does not carry the request in its version JSON, so
     * showing the box for both would be a lie about where it came from.
     */
    private void refreshLoaderChoice() {
        boolean forge = selectedLoader() == ModLoaderType.FORGE;
        view.createProfileButton.setDisable(!selectedLoader().moddable() || installing);
        view.forgeOptIn.setVisible(forge);
        view.forgeOptIn.setManaged(forge);
        view.forgeSupportLink.setVisible(forge);
        view.forgeSupportLink.setManaged(forge);
        view.loaderNote.setText(forge
                ? "Forge установится официальным установщиком: это заметный трафик и несколько минут."
                : "");
    }

    /**
     * JVM used to run a mod loader's own installer.
     *
     * <p>The installer is a Gradle-built jar, so it needs a JDK and it has to be new enough for the
     * Minecraft version being installed. A loader that needs a different Java than the game would
     * be surprising, and the game's own requirement is what the installer is built against.
     */
    private Path installerJava(String baseVersion) throws IOException, InterruptedException {
        JavaRuntimes.Runtime runtime = JavaRuntimes.resolve(new JavaRuntimes.LauncherSettings(
                config.javaPath, config.installRoot().resolve("runtimes")),
                installerJavaMajor(baseVersion), this::log);
        return runtime.java();
    }

    /**
     * Java the installer runs on: 21 for 1.20.5 and newer, 17 otherwise.
     *
     * <p>Compared numerically rather than by prefix so 1.20.6, 1.21.x and every later minor land
     * on the right side of the boundary. Snapshot ids are not loader targets and get the floor.
     */
    private static int installerJavaMajor(String baseVersion) {
        if (baseVersion == null) {
            return 17;
        }
        String[] parts = baseVersion.split("\\.");
        try {
            if (parts.length >= 2) {
                int minor = Integer.parseInt(parts[1]);
                int patch = parts.length >= 3 ? Integer.parseInt(parts[2]) : 0;
                if (minor > 20 || (minor == 20 && patch >= 5)) {
                    return 21;
                }
            }
        } catch (NumberFormatException notASemver) {
            // Falls through to 17.
        }
        return 17;
    }

    /** Opens the mods folder of the selected profile, creating it when missing. */
    public void openModsFolder() {
        ModProfile profile = selectedProfile();
        if (profile == null) {
            error("Это не профиль", new IllegalStateException("Выбери профиль с загрузчиком"));
            return;
        }
        try {
            Path dir = new GameStarter(config, catalog).loaderModsDir(profile.id(), profile);
            Files.createDirectories(dir);
            if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                error("Не удалось открыть папку",
                        new IOException("система не поддерживает открытие папок"));
                return;
            }
            Desktop.getDesktop().open(dir.toFile());
        } catch (IOException e) {
            error("Не удалось открыть папку модов", e);
        }
    }

    /**
     * Opens Forge's Patreon, which the opt-in box promises.
     *
     * <p>Shown as a plain https link rather than through an in-app browser so it opens in whatever
     * the user already has, and so no page from the internet is rendered inside the launcher's own
     * window.
     */
    private void openForgeSupport() {
        try {
            if (!Desktop.isDesktopSupported()
                    || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                error("Не удалось открыть ссылку",
                        new IOException("система не поддерживает открытие ссылок: "
                                + FORGE_SUPPORT_URL));
                return;
            }
            Desktop.getDesktop().browse(new java.net.URI(FORGE_SUPPORT_URL));
        } catch (Exception e) {
            error("Не удалось открыть ссылку", e);
        }
    }

    /**
     * Puts a profile into the version picker right after the vanilla version it is built on, so
     * "1.19.2-forge-..." sits with "1.19.2" instead of piling up at the bottom of the list.
     */
    private void addProfileToPicker(String id) {
        var items = view.versionBox.getItems();
        if (items.contains(id)) {
            return;
        }
        String base = baseVersionOf(id);
        int insertAt = items.size();
        for (int i = items.size() - 1; i >= 0; i--) {
            if (base.equals(baseVersionOf(items.get(i)))) {
                insertAt = i + 1;
                break;
            }
        }
        items.add(insertAt, id);
    }

    /** A profile's base version; vanilla ids and snapshots are their own base. */
    private String baseVersionOf(String id) {
        ModProfile profile = profiles().find(id);
        return profile == null ? id : profile.baseVersion();
    }

    /** Re-reads the profile list and refreshes the line describing the selected one. */
    private void refreshProfileInfo() {
        ModProfile profile = selectedProfile();
        refreshLoaderChoice();
        if (profile == null) {
            view.profileInfo.setText("");
            view.openModsButton.setDisable(true);
            view.openModsButton.setVisible(false);
            view.openModsButton.setManaged(false);
            return;
        }
        view.profileInfo.setText(profile.displayName() + " · папка модов");
        view.openModsButton.setDisable(false);
        view.openModsButton.setVisible(true);
        view.openModsButton.setManaged(true);
    }

    /** Recomputes the "on disk" line; walks the install, so it belongs off the UI thread. */
    private String diskUsageText() {
        VersionStore store = store();
        List<String> installed = store.installed();
        long total = 0;
        for (String id : installed) {
            total += store.sizeOf(id);
        }
        return installed.size() + " версий · " + VersionStore.formatSize(total)
                + " · ассеты " + VersionStore.formatSize(store.assetsSize());
    }

    /**
     * Refreshes the "on disk" line in the settings tab. The size walk covers the whole install,
     * so it runs off the UI thread; the launch gate is deliberately not raised for it.
     */
    public void refreshDiskUsage() {
        Util.background("launcher-disk-usage", () -> {
            String disk = diskUsageText();
            Platform.runLater(() -> view.versionDiskLabel.setText(disk));
        }, e -> log("Не удалось посчитать занятое место: " + e.getMessage()));
    }

    /**
     * Runs a disk-heavy action off the UI thread under the same busy gate the launch uses: a
     * multi-gigabyte delete would freeze the window otherwise, and the gate keeps a second
     * destructive click from racing the first. {@code uiFinish} runs on the UI thread after the
     * work succeeds, before the gate opens again.
     */
    private void runDiskAction(String label, String failTitle,
            Util.ThrowingRunnable work, Runnable uiFinish) {

        installing = true;
        setBusy(true);
        updateUiState();
        Util.background(label, () -> {
            work.run();
            String disk = diskUsageText();
            Platform.runLater(() -> {
                uiFinish.run();
                view.versionDiskLabel.setText(disk);
                installing = false;
                setBusy(false);
                updateUiState();
            });
        }, e -> Platform.runLater(() -> {
            installing = false;
            setBusy(false);
            updateUiState();
            error(failTitle, e);
        }));
    }

    /** Deletes the version currently selected in the picker, after a confirmation. */
    public void deleteSelectedVersion() {
        String id = selectedVersionId();
        if (!store().isInstalled(id)) {
            view.log("Версия " + id + " не установлена");
            return;
        }
        if (!confirm("Удалить версию " + id + "?\nПапка версии будет удалена, ассеты останутся.")) {
            return;
        }
        if (gameRunning() || installing) {
            error("Нельзя удалить", new IllegalStateException("Сначала закрой игру"));
            return;
        }
        runDiskAction("launcher-delete-version", "Не удалось удалить версию",
                () -> store().delete(id),
                () -> {
                    // A deleted profile must not linger in the picker as an entry that cannot be launched.
                    view.versionBox.getItems().remove(id);
                    view.log("Версия " + id + " удалена");
                });
    }

    /** Deletes every installed version, after a confirmation. */
    public void deleteAllVersions() {
        List<String> installed = store().installed();
        if (installed.isEmpty()) {
            view.log("Установленных версий нет");
            return;
        }
        if (!confirm("Удалить все версии (" + installed.size() + ")?\n"
                + "Они будут скачаны заново при следующем запуске.")) {
            return;
        }
        if (gameRunning() || installing) {
            error("Нельзя удалить", new IllegalStateException("Сначала закрой игру"));
            return;
        }
        int[] removed = {0};
        List<String> profileIds = profiles().ids();
        runDiskAction("launcher-delete-all", "Не удалось удалить версии",
                () -> removed[0] = store().deleteAll(),
                () -> {
                    for (String id : profileIds) {
                        view.versionBox.getItems().remove(id);
                    }
                    view.log("Удалено версий: " + removed[0]);
                });
    }

    /** Clears shared assets, which is the largest reclaimable chunk of disk space. */
    public void cleanAssets() {
        if (!confirm("Удалить ассеты?\nОни общие для всех версий и весят несколько гигабайт.")) {
            return;
        }
        if (gameRunning() || installing) {
            error("Нельзя очистить", new IllegalStateException("Сначала закрой игру"));
            return;
        }
        boolean[] hadAssets = {false};
        runDiskAction("launcher-clean-assets", "Не удалось удалить ассеты",
                () -> hadAssets[0] = store().deleteAssets(),
                () -> view.log(hadAssets[0] ? "Ассеты удалены" : "Ассетов не было"));
    }

    /** True for every destructive settings action, so a stray click cannot wipe the install. */
    private boolean confirm(String message) {
        javafx.scene.control.Alert alert =
                new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.CONFIRMATION, message);
        alert.setHeaderText(null);
        alert.getButtonTypes().setAll(javafx.scene.control.ButtonType.OK,
                javafx.scene.control.ButtonType.CANCEL);
        Optional<javafx.scene.control.ButtonType> answer = alert.showAndWait();
        return answer.isPresent() && answer.get() == javafx.scene.control.ButtonType.OK;
    }

    public LauncherController(LauncherView view, LauncherConfig config, Path dataDir) {
        this(view, config, dataDir, new VersionCatalog(dataDir.resolve("version_manifest_v2.json")));
    }

    /** Seam for the UI check, which supplies a seeded catalog instead of hitting Mojang. */
    public LauncherController(LauncherView view, LauncherConfig config, Path dataDir,
                              VersionCatalog catalog) {
        this.view = view;
        this.config = config;
        this.dataDir = dataDir;
        this.catalog = catalog;
           this.fileLog = FileLog.open(dataDir);
           this.tokens = new TokenStore(config.tokensFile());
           this.banners = BannerStore.load(dataDir);
        view.serverAddress.setText(config.lastServerAddress == null ? "" : config.lastServerAddress);
        view.setVersionLabelProvider(id -> {
            ModProfile profile = profiles().find(id);
            return profile == null ? id : profile.displayName();
        });
        view.bannerData = this::bannerDataFor;
        view.bannerScreenshots = this::bannerScreenshotsFor;
        this.providerId = config.authProvider;
        view.fieldsEdited = this::updateUiState;
    }

    /**
     * Picks the version the picker should open on: the remembered one when it still exists.
     *
     * <p>The remembered id can point at anything, since it is written whenever the game is played
     * or a profile is installed. A profile that was deleted, or an id from another install root,
     * simply is not offered any more, and selecting it would leave the picker on a blank row that
     * cannot be launched.
     */
    public static String restoredVersionId(List<String> vanillaIds, List<String> profileIds,
            String remembered) {

        if (remembered == null || remembered.isBlank()) {
            return null;
        }
        if (vanillaIds.contains(remembered) || profileIds.contains(remembered)) {
            return remembered;
        }
        return null;
    }

    /** Fills the version list and restores a cached session if possible. */
    public void start() {
        // start() itself runs on a pool thread (LauncherApp calls it via runAsync), so the log
        // goes through the queued logger rather than touching the text area directly.
        log("Загружаю список версий с Mojang ...");
        Thread worker = new Thread(() -> {
            try {
                catalog.refresh();
                McVersion preferred = catalog.defaultVersion();
List<String> ids = catalog.releases().stream().map(McVersion::id).toList();

Platform.runLater(() -> {
                    // A profile installed while this network refresh was still running is already
                    // on disk but not in the snapshot taken above, so re-read the profile list
                    // instead of trusting the copy captured before the install finished.
                    List<String> liveProfiles = profiles().ids();
                    view.versionBox.getItems().setAll(ids);
                    liveProfiles.forEach(this::addProfileToPicker);
                    view.profileBaseVersion.getItems().setAll(ids);
                    // A remembered version wins over the default one, so reopening the launcher
                    // lands on the version the user was actually playing. The lookup goes through
                    // the freshly filled list, so a profile created since the snapshot still counts.
                    String restore = restoredVersionId(ids, liveProfiles, config.lastVersionId);
                    if (restore == null && preferred != null) {
                        restore = preferred.id();
                    }
                    if (restore != null) {
                        view.versionBox.getSelectionModel().select(restore);
                    }
                    ModProfile restoredProfile = profiles().find(restore);
                    String base = restoredProfile == null ? restore : restoredProfile.baseVersion();
                    if (base != null && ids.contains(base)) {
                        view.profileBaseVersion.getSelectionModel().select(base);
                    } else if (preferred != null) {
                        view.profileBaseVersion.getSelectionModel().select(preferred.id());
                    }
                    view.log("Доступно версий: " + ids.size()
                            + (liveProfiles.isEmpty() ? "" : " · профилей: " + liveProfiles.size()));
                    updateUiState();
                });

                TokenStore.CachedSession cached = tokens.get(providerId);
                AuthSession restored = AuthProviders.restoreSilent(config, tokens, providerId,
                        this::log);
                if (restored != null) {
                    session = restored;
                    accountUsername = restored.username();
                    needsRelogin = false;
                    loadAccountSkin(restored);
                    Platform.runLater(() -> {
                        view.log("Восстановлена сессия: " + restored.username());
                        updateUiState();
                    });
                } else {
                    accountUsername = cached == null ? config.lastUsername : cached.username();
                    needsRelogin = cached != null;
                    Platform.runLater(this::updateUiState);
                }
            } catch (IOException | InterruptedException e) {
                Platform.runLater(() -> error("Не удалось загрузить список версий", e));
            }
        }, "launcher-init");
        worker.setDaemon(true);
        worker.start();
    }

    /** Shows the login fields for the given provider and runs a login when asked. */
    public void beginLogin(String newProviderId) {
        // Switching provider invalidates any half-entered Ely 2FA state.
        pendingCredentials.clear();
        providerId = newProviderId;
        config.authProvider = newProviderId;
        session = null;
        TokenStore.CachedSession cached = tokens.get(newProviderId);
        accountUsername = cached == null
            ? (AuthProviderInfo.OFFLINE.equals(newProviderId) ? config.lastUsername : "")
            : cached.username();
        needsRelogin = cached != null && !AuthProviderInfo.OFFLINE.equals(newProviderId);
        view.setAccountSkin(null);

        Map<String, String> labels = new LinkedHashMap<>();
        switch (newProviderId) {
            case AuthProviderInfo.ELY -> {
                labels.put("login", "E-mail или ник Ely");
                labels.put("password", "Пароль");
            }
            case AuthProviderInfo.MICROSOFT -> labels.put("clientId", "Azure client id");
            default -> labels.put(OfflineAuthenticator.FIELD_USERNAME, "Никнейм");
        }
        showFields(labels);
        // Offline has no login step, so a remembered nickname is what makes the play button
        // usable right away on the next start.
        if (AuthProviderInfo.OFFLINE.equals(newProviderId) && !config.lastUsername.isBlank()) {
            view.loginFields.get(OfflineAuthenticator.FIELD_USERNAME).setText(config.lastUsername);
        }
        view.log("Режим входа: " + displayName(newProviderId));
        updateUiState();
    }

    /** Performs the login for the selected provider, handling Ely's 2FA step. */
    public void login() {
        runLogin(collectFields());
    }

    private void runLogin(Map<String, String> fields) {
        pendingCredentials.putAll(fields);
        Authenticator authenticator = AuthProviders.create(config, tokens, providerId);
        LoginRequest request = new LoginRequest(providerId, new HashMap<>(pendingCredentials), null);

        setBusy(true);
        Thread worker = new Thread(() -> {
            try {
                AuthSession result = authenticator.login(request, this::log);
                session = result;
                accountUsername = result.username();
                needsRelogin = false;
                loadAccountSkin(result);
                // Do not keep the password around once the exchange succeeded.
                pendingCredentials.clear();
                Platform.runLater(() -> {
                    view.log("Готово: " + result.username() + " (" + result.userType() + ")");
                    view.setLoginButtonVisible(false);
                    updateUiState();
                });
            } catch (LoginRequest.NeedsMoreInput more) {
                // Ely wants a TOTP before it will issue a token: swap the form and keep going.
                Platform.runLater(() -> {
                    showFields(Map.of("totp", "Код 2FA (6 цифр)"));
                    view.loginButton.setText("Подтвердить код");
                });
                log(more.getMessage());
            } catch (LoginRequest.Cancelled cancelled) {
                Platform.runLater(() -> view.log("Вход отменён"));
            } catch (Exception e) {
                Platform.runLater(() -> error("Ошибка входа", e));
            } finally {
                Platform.runLater(() -> {
                    setBusy(false);
                    updateUiState();
                });
            }
        }, "launcher-login");
        worker.setDaemon(true);
        worker.start();
    }

    /** Swaps the credential form, keeping the field listeners attached to the new fields. */
    private void showFields(Map<String, String> labels) {
        view.showFieldsFor(providerId, labels);
    }

    /** Installs the selected version and starts the game. */
    public void play() {
        play(null);
    }

    private void play(String quickPlayAddress) {
        ModProfile profile = selectedProfile();
        ModLoaderType loader = profile == null ? selectedLoader() : ModLoaderType.VANILLA;
        boolean installLoader = profile == null && loader.moddable();
        String targetId = installLoader ? selectedBaseVersionId() : selectedVersionId();
        McVersion version = catalog.find(targetId);
        if (version == null && profile == null) {
            error("Версия не найдена", new IOException(targetId));
            return;
        }
        if (version == null) {
            // A profile is not in Mojang's manifest, so the entry the pipeline needs is built from
            // the profile itself. Its JSON is already on disk, so the URL is never followed.
            version = new McVersion(profile.id(), "release", "", "");
        }
            if (installLoader && loader == ModLoaderType.FORGE && !view.forgeOptIn.isSelected()) {
                error("Forge не установлен", new IllegalStateException(
                    "Отметь согласие на загрузку Forge перед запуском."));
                return;
            }
        if (!ensureSession()) {
            return;
        }

        if (quickPlayAddress != null) {
            try {
                quickPlayAddress = ServerAddress.normalize(quickPlayAddress);
            } catch (IllegalArgumentException e) {
                error("Некорректный адрес", e);
                return;
            }
            config.lastServerAddress = quickPlayAddress;
        }

            saveSettings();
            String targetDescription = installLoader
                ? loader.displayName() + " " + (selectedBuild() == null ? "последняя сборка" : selectedBuild())
                : (profile == null ? "Mojang, без загрузчика"
                    : profile.displayName() + " / " + profile.loader().displayName()
                        + " " + profile.loaderVersion());
            log("Цель запуска: " + version.id() + " (" + targetDescription + ")");
        GameStarter starter = new GameStarter(config, catalog);
        installing = true;
            setBusy(true);
        loggedBucket = -1;
            view.setStatus(installLoader ? "Устанавливаю " + loader.displayName() + " ..."
                : "Готовлю файлы игры ...");
            if (installLoader) {
                view.progressBar.setProgress(-1);
                view.progressBar.setVisible(true);
                view.progressBar.setManaged(true);
                view.progressLabel.setText("Устанавливаю загрузчик ...");
            }
        updateUiState();

        // Captured by the worker below, so these have to be the final values rather than the
        // reassigned locals above.
        final McVersion target = version;
        final ModProfile targetProfile = profile;
        final String loaderBuild = selectedBuild();
        final String packName = view.profileNameField.getText().trim();
        final AuthSession launchSession = session;
        final String launchAddress = quickPlayAddress;

        Thread worker = new Thread(() -> {
            try {
                ModProfile launchProfile = targetProfile;
                McVersion launchVersion = target;
                if (installLoader) {
                    launchProfile = ensureLoaderProfile(target.id(), loader, loaderBuild, packName);
                    launchVersion = new McVersion(launchProfile.id(), "release", "", "");
                    ModProfile installedProfile = launchProfile;
                    Platform.runLater(() -> {
                        addProfileToPicker(installedProfile.id());
                        view.versionBox.getSelectionModel().select(installedProfile.id());
                        view.profileNameField.clear();
                        config.lastVersionId = installedProfile.id();
                        config.save(dataDir);
                        updateUiState();
                    });
                    log("Профиль готов: " + launchProfile.displayName());
                } else if (launchProfile != null) {
                    // An existing profile is launched without reinstalling it, so its loader
                    // files are checked here — the JSON being on disk does not prove they are.
                    ensureLoaderArtifacts(launchProfile);
                }
                GameProcess process = starter.launch(launchVersion, launchProfile, launchSession,
                    launchAddress, new GameStarter.Callbacks() {
                            @Override
                            public void log(String line) {
                                LauncherController.this.log(line);
                            }

                            @Override
                            public void progress(int percent, String what) {
                                Platform.runLater(() -> {
                                    if (percent >= 100) {
                                        view.setStatus("Запускаю игру ...");
                                    } else {
                                        view.showProgress(percent, what);
                                    }
                                    // The details panel is for tracing a slow download, so log every
                                    // tenth of a percent rather than every single file.
                                    int bucket = percent / 10;
                                    if (bucket > loggedBucket) {
                                        loggedBucket = bucket;
                                        view.log(percent + "% — " + what);
                                    }
                                });
                            }

                            @Override
                            public void installed() {
                                Platform.runLater(() -> view.setStatus("Файлы готовы, запускаю ..."));
                            }

                            @Override
                            public void failed(Exception e) {
                                Platform.runLater(() -> error("Не удалось запустить игру", e));
                            }

                            @Override
                            public void exited(int code) {
                                Platform.runLater(() -> {
                                    running = null;
                                    view.hideProgress();
                                    view.log("Игра закрыта, код " + code);
                                    view.refreshBanner();
                                    if (code != 0) {
                                        view.showConsole();
                                    }
                                    updateUiState();
                                    if (hiddenForGame) {
                                        hiddenForGame = false;
                                        stage().show();
                                        stage().setIconified(false);
                                        stage().toFront();
                                    }
                                });
                            }
                        });
                running = process;
                Platform.runLater(() -> {
                    installing = false;
                    setBusy(false);
                    view.hideProgress();
                    view.log("Игра запущена, PID " + process.pid());
                    updateUiState();
                    // The "on close" preference also says what the window does when the game
                    // takes over: hide-to-tray hides it, minimise iconifies it, plain close
                    // leaves the launcher where it is.
                    if ("hide".equals(config.closeAction) || "minimize".equals(config.closeAction)) {
                        hiddenForGame = true;
                        if ("hide".equals(config.closeAction)) {
                            stage().hide();
                        } else {
                            stage().setIconified(true);
                        }
                    }
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    installing = false;
                    setBusy(false);
                    view.hideProgress();
                    updateUiState();
                    error("Не удалось запустить игру", e);
                });
            }
        }, "launcher-play");
        worker.setDaemon(true);
        worker.start();
    }

    private ModProfile ensureLoaderProfile(String baseVersion, ModLoaderType loader, String build,
                                           String displayName)
            throws IOException, InterruptedException {
        String loaderVersion = build == null
                ? ModLoaderCatalog.latestBuild(loader, baseVersion)
                : build;
        ModProfileStore store = profiles();
        String expectedId = ModProfile.buildId(loader, baseVersion, loaderVersion);
        ModProfile installed = store.find(expectedId);
        if (installed != null) {
            // A profile whose loader files never finished downloading still reads as installed:
            // the version JSON exists because the installer writes it before its downloads.
            ensureLoaderArtifacts(installed);
        } else {
            ModProfile requested = new ModProfile(expectedId, baseVersion, loader, loaderVersion,
                    List.of(), null);
            String actualId = new ModLoaderInstaller(config.installRoot(), installerJava(baseVersion),
                    this::log).install(requested);
            installed = store.find(actualId);
            if (installed == null) {
                throw new IOException("Установщик не создал профиль " + actualId);
            }
            ensureLoaderArtifacts(installed);
        }
        if (displayName == null || displayName.isBlank()
                || installed.displayName().equals(displayName.trim())) {
            return installed;
        }
        String requestedName = displayName.trim();
        for (ModProfile candidate : store.all()) {
            if (candidate.baseVersion().equals(baseVersion)
                    && candidate.loader() == loader
                    && java.util.Objects.equals(candidate.loaderVersion(), loaderVersion)
                    && candidate.displayName().equalsIgnoreCase(requestedName)) {
                return candidate;
            }
        }
        return store.createNamedAlias(installed.id(), requestedName);
    }

    /**
     * Re-runs the loader's installer when the jars FML resolves at launch are missing.
     *
     * <p>The installer writes its version JSON before it downloads anything, so an interrupted
     * install leaves a profile that looks complete and dies at launch inside FML's own locator,
     * with a stack trace the user cannot act on. Running the installer again is what actually
     * produces the missing files: it skips what is already on disk and downloads the rest.
     *
     * <p>Only installer-backed loaders are checked. Fabric and vanilla have their libraries
     * re-downloaded by the regular pipeline on every launch, and NeoForge's layout is deliberately
     * not verified (see {@link LaunchPreflight}), so for those this is a no-op.
     */
    private void ensureLoaderArtifacts(ModProfile profile)
            throws IOException, InterruptedException {

        if (!profile.loader().needsInstaller()) {
            return;
        }
        List<Path> missing = loaderArtifactsMissing(profile);
        if (missing.isEmpty()) {
            return;
        }
        log("Неполная установка " + profile.id() + ": не хватает " + missing.size()
                + " файлов, переустанавливаю " + profile.loader().displayName() + " ...");
        new ModLoaderInstaller(config.installRoot(), installerJava(profile.baseVersion()), this::log)
                .install(profile);
        List<Path> stillMissing = loaderArtifactsMissing(profile);
        if (!stillMissing.isEmpty()) {
            throw new IOException(LaunchPreflight.describe(stillMissing));
        }
        log("Недостающие файлы загрузчика докачаны");
    }

    /** The loader jars this profile's launch arguments name but that are absent from disk. */
    private List<Path> loaderArtifactsMissing(ModProfile profile) {
        try {
            JsonObject json = new VersionResolver(config.versionsDir()).load(profile.id());
            return LaunchPreflight.missingLoaderArtifacts(json, config.librariesDir());
        } catch (IOException unreadable) {
            // An unreadable version JSON is reported with its exact path by the launch itself.
            return List.of();
        }
    }

    public void stopGame() {
        if (running == null || !running.isAlive()) {
            running = null;
            updateUiState();
            return;
        }
        view.log("Останавливаю игру ...");
        running.stop();
        running = null;
        view.hideProgress();
        updateUiState();
    }

    /** Opens the game folder in the system file manager, creating it when missing. */
    public void openGameFolder() {
        openFolder(Path.of(config.gameDir).toAbsolutePath());
    }

    /** Opens the folder the selected build was installed into. */
    private void openBuildFolder() {
        String id = selectedBuildId();
        if (id == null || id.isBlank()) {
            return;
        }
        openFolder(config.versionsDir().resolve(id));
    }

    /** Opens a folder of the selected build's instance, which is where its data lives. */
    private void openSubFolder(String name) {
        Path base = new GameStarter(config, catalog).gameDirFor(selectedProfile());
        openFolder(name.isEmpty() ? base : base.resolve(name));
    }

    private void openFolder(Path dir) {
        try {
            Files.createDirectories(dir);
            if (!Desktop.isDesktopSupported()
                    || !Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                error("Не удалось открыть папку",
                        new IOException("система не поддерживает открытие папок"));
                return;
            }
            Desktop.getDesktop().open(dir.toFile());
        } catch (IOException e) {
            error("Не удалось открыть папку", e);
        }
    }

    /** Copies the newest crash report, or the launcher's own log when the game never wrote one. */
    private void copyCrashLog() {
        try {
            Path instance = new GameStarter(config, catalog).gameDirFor(selectedProfile());
            Path newest = newestCrashReport(instance);
            if (newest == null && !instance.equals(Path.of(config.gameDir).toAbsolutePath())) {
                newest = newestCrashReport(Path.of(config.gameDir).toAbsolutePath());
            }
            String text;
            String note;
            if (newest != null) {
                text = Files.readString(newest);
                note = "Краш-лог скопирован: " + newest.getFileName();
            } else {
                text = view.logArea.getText();
                note = "Отчётов о падении нет, скопирован вывод лаунчера";
            }
            javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
            content.putString(text);
            javafx.scene.input.Clipboard.getSystemClipboard().setContent(content);
            view.log(note);
        } catch (Exception e) {
            error("Не удалось скопировать краш-лог", e);
        }
    }

    private Path newestCrashReport(Path gameDir) throws IOException {
        Path dir = gameDir.resolve("crash-reports");
        if (!Files.isDirectory(dir)) {
            return null;
        }
        try (var stream = Files.list(dir)) {
            return stream
                    .filter(path -> path.getFileName().toString().endsWith(".txt"))
                    .max(java.util.Comparator.comparingLong(path -> path.toFile().lastModified()))
                    .orElse(null);
        }
    }

    /** The banner choice saved for a build, falling back to the mockup's defaults. */
    private BannerData bannerDataFor(String id) {
        BannerStore.Entry entry = banners.forId(id);
        Path image = entry.image == null || entry.image.isBlank() ? null : Path.of(entry.image);
        return new BannerData(entry.mode, entry.preset, image, entry.auto);
    }

    /** Screenshots of the build's instance, oldest first so the carousel reads chronologically. */
    private List<Path> bannerScreenshotsFor(String id) {
        ModProfile profile = id == null || id.isBlank() ? null : profiles().find(id);
        Path dir = new GameStarter(config, catalog).gameDirFor(profile).resolve("screenshots");
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (var stream = Files.list(dir)) {
            return stream.filter(path -> {
                String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                return name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg");
            }).sorted(java.util.Comparator.comparingLong(path -> path.toFile().lastModified()))
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private void updateBannerChoice(java.util.function.Consumer<BannerStore.Entry> change) {
        change.accept(banners.forId(selectedBuildId()));
        banners.save();
        view.syncAppearanceControls();
        view.refreshBanner();
    }

    /** Copies a chosen picture into the data folder so moving the original keeps the cover. */
    private void chooseBannerImage() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Выбрать изображение баннера");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(
                "Изображения", "*.png", "*.jpg", "*.jpeg", "*.bmp", "*.gif"));
        java.io.File selected = chooser.showOpenDialog(view.rootNode().getScene().getWindow());
        if (selected == null) {
            return;
        }
        Path source = selected.toPath().toAbsolutePath().normalize();
        String id = selectedBuildId();
        String key = id == null || id.isBlank() ? "vanilla"
                : id.replaceAll("[^A-Za-z0-9._-]", "_");
        String fileName = source.getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        String extension = dot < 0 ? ".img" : fileName.substring(dot).toLowerCase(Locale.ROOT);
        Path target = dataDir.resolve("banners").resolve(key + extension).toAbsolutePath().normalize();
        try {
            Files.createDirectories(target.getParent());
            if (!source.equals(target)) {
                Files.copy(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            updateBannerChoice(entry -> {
                entry.mode = BannerData.MODE_CUSTOM;
                entry.image = target.toString();
            });
        } catch (IOException e) {
            error("Не удалось выбрать изображение", e);
        }
    }

    private void chooseWallpaper() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Выбрать обои основного экрана");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(
                "Изображения", "*.png", "*.jpg", "*.jpeg", "*.bmp", "*.gif"));
        java.io.File selected = chooser.showOpenDialog(view.rootNode().getScene().getWindow());
        if (selected == null) {
            return;
        }
        Path source = selected.toPath().toAbsolutePath().normalize();
        String fileName = source.getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        String extension = dot < 0 ? ".img" : fileName.substring(dot).toLowerCase(Locale.ROOT);
        Path target = dataDir.resolve("main-menu-wallpaper" + extension).toAbsolutePath().normalize();
        Path previous = config.mainMenuWallpaper == null || config.mainMenuWallpaper.isBlank()
            ? null : Path.of(config.mainMenuWallpaper);
        try {
            if (!source.equals(target)) {
                Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
                Files.copy(source, temporary, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                Files.move(temporary, target,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            view.setWallpaper(target);
            config.mainMenuWallpaper = target.toString();
            config.save(dataDir);
            removeManagedWallpaper(previous, target);
        } catch (IOException e) {
            error("Не удалось установить обои", e);
        }
    }

    private void clearWallpaper() {
        Path previous = config.mainMenuWallpaper == null || config.mainMenuWallpaper.isBlank()
                ? null : Path.of(config.mainMenuWallpaper);
        try {
            view.setWallpaper(null);
            config.mainMenuWallpaper = "";
            config.save(dataDir);
            removeManagedWallpaper(previous, null);
        } catch (IOException e) {
            error("Не удалось сбросить обои", e);
        }
    }

    private void removeManagedWallpaper(Path previous, Path keep) throws IOException {
        if (previous == null || (keep != null && previous.equals(keep))) {
            return;
        }
        Path managedDir = dataDir.toAbsolutePath().normalize();
        Path candidate = previous.toAbsolutePath().normalize();
        if (candidate.startsWith(managedDir) && Files.isRegularFile(candidate)) {
            Files.delete(candidate);
        }
    }

    /**
     * Makes sure there is a session to launch with.
     *
     * <p>Offline mode has no login step, so the nickname is turned into a session here. That is why
     * the play button can already be active with the login button hidden.
     *
     * @return false when a session could not be produced, with a message shown to the user
     */
    private boolean ensureSession() {
        if (session != null) {
            return true;
        }
        if (!AuthProviderInfo.OFFLINE.equals(providerId)) {
            error("Сначала войдите", new IllegalStateException("Нет активной сессии"));
            return false;
        }
        String nickname = view.offlineNickname();
        if (!OfflineAuthenticator.isValidNickname(nickname)) {
            view.setStatus("Введите ник: 1-16 символов, латиница, цифры и _");
            return false;
        }
        try {
            session = new OfflineAuthenticator().login(
                    OfflineAuthenticator.request(nickname), this::log);
            updateUiState();
            return true;
        } catch (Exception e) {
            error("Не удалось запустить игру", e);
            return false;
        }
    }

    private void saveSettings() {
        config.minMemoryMb = view.minMemory.getValue();
        config.maxMemoryMb = (int) Math.round(view.maxMemorySlider.getValue());
        config.extraJvmArgs = view.extraArgs.getText();
        config.javaPath = view.javaPathValue();
        config.authServerUrl = view.authServer.getText();
        config.microsoftClientId = view.microsoftClientId.getText();
        config.isolateGameDir = view.toggles.isolateGameDir.isSelected();
        config.useAuthlibInjector = view.toggles.authlibInjector.isSelected();
        config.appearanceTheme = "Светлая".equals(view.appearanceTheme.getValue()) ? "light" : "dark";
        config.appearanceAccent = view.accentHex();
        config.interfaceFontSize = (int) Math.round(view.interfaceFontSize.getValue());
        config.authProvider = providerId;
        config.lastVersionId = selectedVersionId();
        config.lastUsername = rememberUsername();
        config.save(dataDir);
    }

    /** Only a free-form nickname is worth remembering; real logins keep their own token store. */
    private String rememberUsername() {
        if (AuthProviderInfo.OFFLINE.equals(providerId)) {
            return view.offlineNickname();
        }
        return session == null ? null : session.username();
    }

private String selectedVersionId() {
        // The box is editable, so the text is what the user sees and what they may have typed. The
        // selected row alone would ignore a pasted id, and the row alone also goes stale while the
        // text is being edited.
        String typed = view.versionBox.getEditor() == null
                ? null
                : view.versionBox.getEditor().getText();
        if (typed != null && !typed.isBlank()) {
            String normalized = typed.trim();
            for (String id : view.versionBox.getItems()) {
                if (view.versionLabel(id).equalsIgnoreCase(normalized)) {
                    return id;
                }
            }
            return normalized;
        }
        String value = view.versionBox.getValue();
        return value == null ? "" : value.trim();
    }

    private String selectedBaseVersionId() {
        String selected = view.profileBaseVersion.getValue();
        return selected == null || selected.isBlank() ? selectedVersionId() : selected.trim();
    }

    /**
     * Single source of truth for button text, enabled state and visibility.
     *
     * <p>Play is bright only when the game could actually start: a usable session (or a valid
     * offline nickname) and a version that exists. While installing it reads as a greyed
     * "Загрузка…", and while the game runs the stop button appears.
     */
    private void updateUiState() {
        boolean offline = AuthProviderInfo.OFFLINE.equals(providerId);
        boolean gameRunning = running != null && running.isAlive();
        view.setAccount(session == null ? accountUsername : session.username(),
            displayName(providerId), needsRelogin);
        refreshProfileInfo();
        // A mod loader profile is a valid target even though Mojang's manifest has never heard of it.
        boolean versionOk = catalog.find(selectedVersionId()) != null || selectedProfile() != null;
        boolean hasSession = session != null
                || (offline && OfflineAuthenticator.isValidNickname(view.offlineNickname()));

        if (gameRunning) {
            view.playButton.setText("Игра запущена");
            view.playButton.setDisable(true);
            view.setStatus("Игра запущена. Открой подробности, если что-то пойдёт не так.");
        } else if (installing) {
            view.playButton.setText("Загрузка…");
            view.playButton.setDisable(true);
        } else {
            view.playButton.setText("Играть");
            view.playButton.setDisable(!(hasSession && versionOk));
        }

        view.stopButton.setVisible(gameRunning);
        view.stopButton.setManaged(gameRunning);
        view.loginButton.setDisable(installing);
        view.versionBox.setDisable(installing);
        view.stopButton.setDisable(false);
        view.connectButton.setDisable(installing || gameRunning || !hasSession || !versionOk);

        if (gameRunning || installing) {
            return;
        }
        if (!versionOk && !selectedVersionId().isEmpty()) {
            view.setStatus("Такой версии нет в списке Mojang.");
        } else if (!versionOk) {
            view.setStatus("Выбери версию Minecraft.");
        } else if (selectedProfile() != null) {
            view.setStatus("Профиль выбран: " + selectedProfile().displayName());
        } else if (offline && !OfflineAuthenticator.isValidNickname(view.offlineNickname())) {
            view.setStatus("Введите ник: 1-16 символов, латиница, цифры и _");
        } else if (!hasSession) {
            view.setStatus("Войдите, чтобы играть.");
        } else {
            view.setStatus(LauncherView.BASE_STATUS);
        }
    }

    private void loadAccountSkin(AuthSession account) {
        if (AuthProviderInfo.OFFLINE.equals(providerId)) {
            Platform.runLater(() -> {
                if (session == account) {
                    view.setAccountSkin(null);
                }
            });
            return;
        }
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {
                String url = SkinService.findTextureUrl(account.uuid());
                if (url != null) {
                    javafx.scene.image.Image skin = new javafx.scene.image.Image(url, true);
                    Platform.runLater(() -> {
                        if (session == account) {
                            view.setAccountSkin(skin);
                        }
                    });
                }
            } catch (IOException | InterruptedException ignored) {
                if (ignored instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
            }
        });
    }

private void setBusy(boolean busy) {
        view.loginButton.setDisable(busy);
        view.versionBox.setDisable(busy);
        view.profileBaseVersion.setDisable(busy);
        view.loaderPicker.setDisable(busy);
        view.createProfileButton.setDisable(busy);
        view.buildList.setDisable(busy);
        view.profileNameField.setDisable(busy);
        view.buildPicker.setDisable(busy || selectedLoader() == ModLoaderType.VANILLA);
        refreshManagedProfileButtons();
    }

private void log(String line) {
        fileLog.accept(line);
        pendingUiLogs.add(line);
        scheduleUiLogFlush();
    }

    private void scheduleUiLogFlush() {
        if (uiLogFlushScheduled.compareAndSet(false, true)) {
            Platform.runLater(this::flushUiLogs);
        }
    }

    private void flushUiLogs() {
        List<String> batch = new java.util.ArrayList<>(200);
        for (int count = 0; count < 200; count++) {
            String line = pendingUiLogs.poll();
            if (line == null) {
                break;
            }
            batch.add(line);
        }
        view.logBatch(batch);
        uiLogFlushScheduled.set(false);
        if (!pendingUiLogs.isEmpty()) {
            scheduleUiLogFlush();
        }
    }

    private static String displayName(String id) {
        for (AuthProviderInfo info : AuthProviderInfo.all()) {
            if (info.id().equals(id)) {
                return info.displayName();
            }
        }
        return id;
    }

    private void error(String title, Exception e) {
        String message = e.getMessage() == null ? e.toString() : e.getMessage();
        view.log("[ошибка] " + message);
        showError(title, message);
    }

    /** Alert creation is separated so the UI check can stub it out. */
    private void showError(String title, String message) {
        javafx.scene.control.Alert alert =
                new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.ERROR, message);
        alert.setHeaderText(title);
        alert.showAndWait();
    }

    /** Wire-up for buttons that live in the view. */
    public void bindActions() {
        view.loginButton.setOnAction(e -> {
            if ("Подтвердить код".equals(view.loginButton.getText())) {
                runLogin(collectFields());
                return;
            }
            login();
        });
        view.playButton.setOnAction(e -> play());
        view.connectButton.setOnAction(e -> play(view.serverAddress.getText()));
        view.stopButton.setOnAction(e -> stopGame());
        view.createProfileButton.setOnAction(e -> createLoaderProfile());
        view.buildList.getSelectionModel().selectedItemProperty()
                .addListener((obs, old, value) -> refreshManagedProfileButtons());
        view.buildList.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) {
                selectManagedProfile();
            }
        });
        view.buildInfoItem.setOnAction(e -> showManagedProfileDetails());
        view.deleteBuildItem.setOnAction(e -> deleteManagedProfile());
        view.openBuildFolderItem.setOnAction(e -> openBuildFolder());
        view.addBuildButton.setOnAction(e -> view.openWizard());
        view.accountButton.setOnAction(e -> view.openAccounts());
        view.manageAccountsButton.setOnAction(e -> view.openAccounts());
        view.copyCrashButton.setOnAction(e -> copyCrashLog());
        view.profileBaseVersion.valueProperty().addListener((obs, old, value) -> {
            if (value != null) {
                if (selectedProfile() == null) {
                    view.versionBox.getSelectionModel().select(value);
                }
                loadBuilds();
            }
        });
view.loaderPicker.getSelectionModel().selectedItemProperty()
                .addListener((obs, was, is) -> {
                    refreshLoaderChoice();
                    loadBuilds();
                });
        view.forgeSupportLink.setOnAction(e -> openForgeSupport());
        view.openModsButton.setOnAction(e -> openModsFolder());
        view.gameFolderButton.setOnAction(e -> openGameFolder());
        view.folderRootItem.setOnAction(e -> openSubFolder(""));
        view.folderConfigItem.setOnAction(e -> openSubFolder("config"));
        view.folderSavesItem.setOnAction(e -> openSubFolder("saves"));
        view.folderLogsItem.setOnAction(e -> openSubFolder("logs"));
        view.folderScreensItem.setOnAction(e -> openSubFolder("screenshots"));
        view.chooseWallpaperButton.setOnAction(e -> chooseWallpaper());
        view.clearWallpaperButton.setOnAction(e -> clearWallpaper());
        view.bannerModeChosen = mode -> updateBannerChoice(entry -> entry.mode = mode);
        view.bannerPresetChosen = index -> updateBannerChoice(entry -> {
            entry.mode = BannerData.MODE_PRESET;
            entry.preset = index;
        });
        view.bannerAutoChosen = auto -> updateBannerChoice(entry -> entry.auto = auto);
        view.bannerPickImage = this::chooseBannerImage;
        view.bannerResetImage = () -> updateBannerChoice(entry -> {
            entry.mode = BannerData.MODE_SHOTS;
            entry.image = "";
        });
        view.deleteVersionButton.setOnAction(e -> deleteSelectedVersion());
        view.deleteAllVersionsButton.setOnAction(e -> deleteAllVersions());
        view.cleanAssetsButton.setOnAction(e -> cleanAssets());
        view.closeAction.valueProperty().addListener((obs, old, value) -> {
            if (value != null) {
                config.closeAction = closeActionFor(value);
                config.save(dataDir);
            }
        });
        view.appearanceTheme.valueProperty().addListener((obs, old, value) -> saveAppearance());
        view.appearanceAccent.valueProperty().addListener((obs, old, value) -> saveAppearance());
        view.interfaceFontSize.valueProperty().addListener((obs, old, value) -> saveAppearance());
view.versionBox.getSelectionModel().selectedItemProperty()
                .addListener((obs, old, value) -> {
                    // Selecting a row programmatically, as a finished loader install does, left
                    // the editor showing the previous id, so the field still read as the vanilla
                    // version right after a profile had been created.
                    String label = view.versionLabel(value);
                    if (value != null && !label.equals(view.versionBox.getEditor().getText())) {
                        view.versionBox.getEditor().setText(label);
                    }
                    ModProfile profile = profiles().find(value);
                    String base = profile == null ? value : profile.baseVersion();
                    if (base != null && view.profileBaseVersion.getItems().contains(base)
                            && !base.equals(view.profileBaseVersion.getValue())) {
                        view.profileBaseVersion.getSelectionModel().select(base);
                    }
                    updateUiState();
                });
        // The version box is editable, so the typed value also has to re-check readiness.
        view.versionBox.getEditor().textProperty().addListener((obs, old, value) -> updateUiState());
    }

    public void restoreWallpaper() {
        if (config.mainMenuWallpaper == null || config.mainMenuWallpaper.isBlank()) {
            return;
        }
        try {
            view.setWallpaper(Path.of(config.mainMenuWallpaper));
        } catch (IOException | RuntimeException e) {
            log("Не удалось восстановить обои основного экрана: " + e.getMessage());
        }
    }

    private void saveAppearance() {
        config.appearanceTheme = "Светлая".equals(view.appearanceTheme.getValue()) ? "light" : "dark";
        config.appearanceAccent = view.accentHex();
        config.interfaceFontSize = (int) Math.round(view.interfaceFontSize.getValue());
        view.applyAppearance(config.appearanceTheme, config.appearanceAccent,
                config.interfaceFontSize);
        config.save(dataDir);
    }

    /** Maps the Russian label shown in the dropdown onto the stored value. */
    public static String closeActionFor(String label) {
        if (label == null) {
            return "close";
        }
        return switch (label) {
            case "Скрывать в трей" -> "hide";
            case "Сворачивать в трей" -> "minimize";
            default -> "close";
        };
    }

    /** The dropdown label for a stored value. */
    public static String closeActionLabel(String value) {
        return switch (value == null ? "close" : value) {
            case "hide" -> "Скрывать в трей";
            case "minimize" -> "Сворачивать в трей";
            default -> "Закрывать лаунчер";
        };
    }

    private Map<String, String> collectFields() {
        Map<String, String> fields = new HashMap<>();
        view.loginFields.forEach((key, field) -> fields.put(key, field.getText()));
        return fields;
    }

    /** Current session, or null when nobody is logged in. Used by the UI check. */
    public AuthSession currentSession() {
        return session;
    }

    /** Forces a state refresh; used by the UI check after poking the widgets directly. */
    public void refreshState() {
        updateUiState();
    }

    /** The window itself; only touches are allowed on the UI thread. */
    private javafx.stage.Stage stage() {
        return (javafx.stage.Stage) view.rootNode().getScene().getWindow();
    }

    /** True while the game process is running. Used by the UI check. */
    public boolean gameRunning() {
        return running != null && running.isAlive();
    }
}
