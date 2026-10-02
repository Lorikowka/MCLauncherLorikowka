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

import com.mymods.mclauncher.GameStarter;
import com.mymods.mclauncher.auth.AuthProviders;
import com.mymods.mclauncher.auth.AuthProviderInfo;
import com.mymods.mclauncher.auth.AuthSession;
import com.mymods.mclauncher.auth.Authenticator;
import com.mymods.mclauncher.auth.LoginRequest;
import com.mymods.mclauncher.auth.OfflineAuthenticator;
import com.mymods.mclauncher.auth.TokenStore;
import com.mymods.mclauncher.config.LauncherConfig;
import com.mymods.mclauncher.mc.GameProcess;
import com.mymods.mclauncher.mc.JavaRuntimes;
import com.mymods.mclauncher.mc.McVersion;
import com.mymods.mclauncher.mc.ModLoaderCatalog;
import com.mymods.mclauncher.mc.ModLoaderInstaller;
import com.mymods.mclauncher.mc.ModLoaderType;
import com.mymods.mclauncher.mc.ModProfile;
import com.mymods.mclauncher.mc.ModProfileStore;
import com.mymods.mclauncher.mc.VersionCatalog;
import com.mymods.mclauncher.mc.VersionStore;
import com.mymods.mclauncher.util.FileLog;
import javafx.application.Platform;

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

    /** Same lines as the window shows, but kept on disk so a failure survives closing it. */
    private final java.util.function.Consumer<String> fileLog;
    private final ConcurrentLinkedQueue<String> pendingUiLogs = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean uiLogFlushScheduled = new AtomicBoolean();

    /**
     * Credentials from the first attempt, so Ely's 2FA retry can resend login and password without
     * making the user type them again. Cleared once the exchange succeeds.
     */
    private final Map<String, String> pendingCredentials = new HashMap<>();

    private AuthSession session;
    private GameProcess running;
    private String providerId;
    private boolean installing;

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

    /**
     * Loads the builds offered for the chosen loader on the chosen Minecraft version.
     *
     * <p>Deliberately not called from the state refresh: that runs on every keystroke in the
     * editable version box, and each load is a network request. It is bound to the two things that
     * actually change the answer, the loader and the version, and the pair already loaded is cached
     * so switching back and forth costs nothing.
     */
    private void loadBuilds() {
        String baseId = selectedVersionId();
        ModLoaderType loader = selectedLoader();
        // A profile cannot be extended by another loader, and an id the catalog has never heard of
        // has no build list to ask for.
        boolean installable = loader.moddable()
            && !baseId.isBlank()
                && selectedProfile() == null
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
        boolean newProfile = selectedLoader().moddable() && selectedProfile() == null;
        view.profileNameField.setVisible(newProfile);
        view.profileNameField.setManaged(newProfile);
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
     * Minecraft version being installed: 1.20.5 and later require Java 21, everything before runs
     * on 17. A loader that needs a different Java than the game would be surprising, and the
     * game's own requirement is what the installer is built against.
     */
    private Path installerJava(String baseVersion) throws IOException, InterruptedException {
        int required = baseVersion != null && baseVersion.startsWith("1.20.5")
                ? 21
                : (baseVersion != null && baseVersion.startsWith("1.21") ? 21 : 17);
        JavaRuntimes.Runtime runtime = JavaRuntimes.resolve(new JavaRuntimes.LauncherSettings(
                config.javaPath, config.installRoot().resolve("runtimes")), required, this::log);
        return runtime.java();
    }

    /** Opens the mods folder of the selected profile, creating it when missing. */
    public void openModsFolder() {
        ModProfile profile = selectedProfile();
        if (profile == null) {
            error("Это не профиль", new IllegalStateException("Выбери профиль с загрузчиком"));
            return;
        }
        try {
            Path dir = new GameStarter(config, catalog).loaderModsDir(profile.id(), profile, session);
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

    /** Puts a profile id into the version picker, keeping vanilla entries in their order. */
    private void addProfileToPicker(String id) {
        var items = view.versionBox.getItems();
        if (!items.contains(id)) {
            items.add(id);
        }
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
        int mods = profiles().modsIn(profile.id()).size();
        view.profileInfo.setText(profile.displayName() + " · модов: " + mods
                + (mods == 0 ? " · положи .jar в папку модов" : ""));
        view.openModsButton.setDisable(false);
        view.openModsButton.setVisible(true);
        view.openModsButton.setManaged(true);
    }

    /** Refreshes the "on disk" line in the settings tab. */
    public void refreshDiskUsage() {
        VersionStore store = store();
        List<String> installed = store.installed();
        long total = 0;
        for (String id : installed) {
            total += store.sizeOf(id);
        }
        view.versionDiskLabel.setText(installed.size() + " версий · "
                + VersionStore.formatSize(total) + " · ассеты "
                + VersionStore.formatSize(store.assetsSize()));
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
        if (gameRunning()) {
            error("Нельзя удалить", new IllegalStateException("Сначала закрой игру"));
            return;
        }
        try {
            store().delete(id);
            // A deleted profile must not linger in the picker as an entry that cannot be launched.
            view.versionBox.getItems().remove(id);
            view.log("Версия " + id + " удалена");
            refreshDiskUsage();
            updateUiState();
        } catch (IOException e) {
            error("Не удалось удалить версию", e);
        }
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
        if (gameRunning()) {
            error("Нельзя удалить", new IllegalStateException("Сначала закрой игру"));
            return;
        }
        try {
            int removed = store().deleteAll();
            for (String id : profiles().ids()) {
                view.versionBox.getItems().remove(id);
            }
            view.log("Удалено версий: " + removed);
            refreshDiskUsage();
            updateUiState();
        } catch (IOException e) {
            error("Не удалось удалить версии", e);
        }
    }

    /** Clears shared assets, which is the largest reclaimable chunk of disk space. */
    public void cleanAssets() {
        if (!confirm("Удалить ассеты?\nОни общие для всех версий и весят несколько гигабайт.")) {
            return;
        }
        if (gameRunning()) {
            error("Нельзя очистить", new IllegalStateException("Сначала закрой игру"));
            return;
        }
        try {
            if (store().deleteAssets()) {
                view.log("Ассеты удалены");
            } else {
                view.log("Ассетов не было");
            }
            refreshDiskUsage();
        } catch (IOException e) {
            error("Не удалось удалить ассеты", e);
        }
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
        this(view, config, dataDir, new VersionCatalog());
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
        view.setVersionLabelProvider(id -> {
            ModProfile profile = profiles().find(id);
            return profile == null ? id : profile.displayName();
        });
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
        view.log("Загружаю список версий с Mojang ...");
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
                    view.log("Доступно версий: " + ids.size()
                            + (liveProfiles.isEmpty() ? "" : " · профилей: " + liveProfiles.size()));
                    updateUiState();
                });

                AuthSession restored = AuthProviders.restoreSilent(config, tokens, providerId,
                        this::log);
                if (restored != null) {
                    session = restored;
                    Platform.runLater(() -> {
                        view.log("Восстановлена сессия: " + restored.username());
                        updateUiState();
                    });
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
                view.log(more.getMessage());
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
        ModProfile profile = selectedProfile();
        ModLoaderType loader = profile == null ? selectedLoader() : ModLoaderType.VANILLA;
        boolean installLoader = profile == null && loader.moddable();
        McVersion version = catalog.find(selectedVersionId());
        if (version == null && profile == null) {
            error("Версия не найдена", new IOException(selectedVersionId()));
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
                }
                GameProcess process = starter.launch(launchVersion, launchProfile, launchSession,
                        new GameStarter.Callbacks() {
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
                                    updateUiState();
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
        if (installed == null) {
            ModProfile requested = new ModProfile(expectedId, baseVersion, loader, loaderVersion,
                    List.of(), null);
            String actualId = new ModLoaderInstaller(config.installRoot(), installerJava(baseVersion),
                    this::log).install(requested);
            installed = store.find(actualId);
            if (installed == null) {
                throw new IOException("Установщик не создал профиль " + actualId);
            }
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
        try {
            Path dir = Path.of(config.gameDir).toAbsolutePath();
            Files.createDirectories(dir);
            if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                error("Не удалось открыть папку", new IOException("система не поддерживает открытие папок"));
                return;
            }
            Desktop.getDesktop().open(dir.toFile());
        } catch (IOException e) {
            error("Не удалось открыть папку", e);
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
        config.javaPath = view.javaPath.getText();
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
            view.playButton.setText("ИГРАТЬ");
            view.playButton.setDisable(!(hasSession && versionOk));
        }

        view.stopButton.setVisible(gameRunning);
        view.stopButton.setManaged(gameRunning);
        view.loginButton.setDisable(installing);
        view.versionBox.setDisable(installing);
        view.stopButton.setDisable(false);

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
            view.setStatus("Готов к запуску");
        }
    }

private void setBusy(boolean busy) {
        view.loginButton.setDisable(busy);
        view.versionBox.setDisable(busy);
        view.loaderPicker.setDisable(busy);
        view.profileNameField.setDisable(busy);
        view.buildPicker.setDisable(busy || selectedLoader() == ModLoaderType.VANILLA);
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
        view.stopButton.setOnAction(e -> stopGame());
view.loaderPicker.getSelectionModel().selectedItemProperty()
                .addListener((obs, was, is) -> {
                    refreshLoaderChoice();
                    loadBuilds();
                });
        view.forgeSupportLink.setOnAction(e -> openForgeSupport());
        view.openModsButton.setOnAction(e -> openModsFolder());
        view.openFolderButton.setOnAction(e -> openGameFolder());
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
                    updateUiState();
                    loadBuilds();
                });
        // The version box is editable, so the typed value also has to re-check readiness.
        view.versionBox.getEditor().textProperty().addListener((obs, old, value) -> updateUiState());
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

    /** True while the game process is running. Used by the UI check. */
    public boolean gameRunning() {
        return running != null && running.isAlive();
    }
}
