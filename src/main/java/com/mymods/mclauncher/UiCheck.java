package com.mymods.mclauncher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.mymods.mclauncher.auth.OfflineAuthenticator;
import com.mymods.mclauncher.auth.OfflineSession;
import com.mymods.mclauncher.config.LauncherConfig;
import com.mymods.mclauncher.mc.McVersion;
import com.mymods.mclauncher.mc.VersionCatalog;
import com.mymods.mclauncher.ui.LauncherController;
import com.mymods.mclauncher.ui.LauncherView;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.layout.Region;
import javafx.stage.Stage;

/**
 * Drives the real UI on the JavaFX thread and asserts what the user would see, so layout and
 * wiring problems surface without clicking by hand.
 *
 * <p>Run with {@code gradlew uiCheck}. Prints a PASS/FAIL line per check and exits non-zero on
 * any failure.
 */
public final class UiCheck {

    private static final List<String> failures = new ArrayList<>();
    private static int passed;

    public static void main(String[] args) throws Exception {
        CountDownLatch ready = new CountDownLatch(1);
        AtomicReference<Throwable> startupError = new AtomicReference<>();

        Platform.startup(() -> {
            try {
                runChecks();
            } catch (Throwable t) {
                startupError.set(t);
            } finally {
                ready.countDown();
            }
        });

        if (!ready.await(3, TimeUnit.MINUTES)) {
            System.out.println("FAIL: таймаут запуска JavaFX");
            System.exit(1);
        }
        Throwable error = startupError.get();
        if (error != null) {
            error.printStackTrace();
            System.exit(1);
        }

        System.out.println();
        System.out.println("=== RESULT ===");
        System.out.println(passed + " passed, " + failures.size() + " failed");
        if (!failures.isEmpty()) {
            failures.forEach(f -> System.out.println("  FAIL " + f));
            System.exit(1);
        }
        System.out.println("PASS");
        Platform.exit();
    }

    private static void runChecks() throws IOException {
        Path dataDir = createDataDir();
        LauncherConfig config = LauncherConfig.load(dataDir);
        config.gameDir = dataDir.resolve("game").toString();

        LauncherView view = new LauncherView();
        Region root = view.build();

        LauncherView restoredAppearanceView = new LauncherView();
        restoredAppearanceView.appearanceTheme.getSelectionModel().select("Светлая");
        restoredAppearanceView.appearanceAccent.setValue(
            javafx.scene.paint.Color.web("#D35F42"));
        restoredAppearanceView.interfaceFontSize.setValue(16);
        Region restoredAppearanceRoot = restoredAppearanceView.build();
        check("appearance preferences survive window creation",
            restoredAppearanceRoot.getStyleClass().contains("theme-light")
                && restoredAppearanceRoot.getStyle().contains("#D35F42")
                && restoredAppearanceRoot.getStyle().contains("16px"));

        Stage stage = new Stage();
        javafx.scene.Scene scene = new javafx.scene.Scene(root);
        scene.getStylesheets().add(UiCheck.class.getResource("/launcher.css").toExternalForm());
        stage.setScene(scene);
        root.applyCss();

        // A seeded catalog keeps the check offline while still exercising "a version is picked".
        VersionCatalog catalog = new VersionCatalog();
        catalog.addVersions(List.of(new McVersion("1.20.1", "release", "", "")));

        LauncherController controller = new LauncherController(view, config, dataDir, catalog);
        controller.bindActions();
        view.versionBox.getItems().setAll("1.20.1");
        view.versionBox.getSelectionModel().select("1.20.1");

        // 1a. A profile on disk is a launchable target even though Mojang never listed it.
        // A session is set up first, so a disabled play button can only be about the version.
        controller.beginLogin("offline");
        view.loginFields.get(OfflineAuthenticator.FIELD_USERNAME).setText("Tester");
        controller.refreshState();
        String profileId = writeProfile(config);
        view.versionBox.getItems().add(profileId);
        view.versionBox.getSelectionModel().select(profileId);
        controller.refreshState();
        check("profile is a valid version", !view.playButton.isDisable());
        check("profile info names the loader",
                view.profileInfo.getText().contains("Fabric"));
        check("profile info counts mods",
                view.profileInfo.getText().contains("модов: 1"));
        check("profile manager keeps pack name field available",
            view.profileNameField.isManaged());
        check("profile shows its mods folder", view.openModsButton.isManaged());
        check("profile offers its mods folder", !view.openModsButton.isDisable());

        // Switching back to vanilla exposes loader choices without a separate install button.
        view.versionBox.getSelectionModel().select("1.20.1");
        controller.refreshState();
        check("loader manager defaults to Vanilla", "Ванильный".equals(
            new LauncherView().loaderPicker.getValue()));
        view.loaderPicker.getSelectionModel().select("Fabric");
        check("vanilla can create a loader profile on launch", view.profileNameField.isManaged());
        check("vanilla clears profile info", view.profileInfo.getText().isEmpty());

        // The loader picker is what makes Forge and NeoForge reachable at all.
        check("loader picker offers every mod loader",
                view.loaderPicker.getItems().containsAll(
                    List.of("Ванильный", "Fabric", "Forge", "NeoForge")));
        check("separate profiles tab exists", view.tabs.getTabs().stream()
            .anyMatch(tab -> "Сборки".equals(tab.getText())));
        check("profile list in manager tab", inScene(view.profilesList, root));
        check("create profile action in manager tab", inScene(view.createProfileButton, root));
        javafx.scene.control.Tab playTab = view.tabs.getTabs().stream()
            .filter(tab -> "Запуск".equals(tab.getText())).findFirst().orElseThrow();
        check("loader selection is kept off the Play tab",
            !inScene(view.loaderPicker, playTab.getContent()));
        check("profile name field in scene", inScene(view.profileNameField, root));
        check("loader picker in scene", inScene(view.loaderPicker, root));
        // A pack often names one exact build, so the newest cannot be the only choice.
        check("build picker in scene", inScene(view.buildPicker, root));

        // The manager's build picker is independent of the currently selected Play profile.
        view.buildPicker.getItems().setAll("0.19.5-test");
        view.versionBox.getSelectionModel().select("1.20.1-fabric-1.20.1-0.19.5");
        controller.refreshState();
        check("build picker remains independent of Play profile",
            view.buildPicker.getItems().contains("0.19.5-test"));
        view.versionBox.getSelectionModel().select("1.20.1");
        controller.refreshState();

        // Forge's own version JSON asks for an explicit opt-in and a visible Patreon link.
        // Showing those only for Forge, and only while Forge is picked, is the whole point.
        check("forge opt-in hidden for the default loader", !view.forgeOptIn.isManaged());
        check("forge link hidden for the default loader", !view.forgeSupportLink.isManaged());

        view.loaderPicker.getSelectionModel().select("Forge");
        controller.refreshState();
        check("forge opt-in shown for forge", view.forgeOptIn.isManaged());
        check("forge opt-in visible for forge", view.forgeOptIn.isVisible());
        check("forge opt-in in scene", inScene(view.forgeOptIn, root));
        check("forge support link in scene", inScene(view.forgeSupportLink, root));
        check("forge support link names the link", view.forgeSupportLink.getText().contains("Patreon"));
        check("forge install is not silently allowed", !view.forgeOptIn.isSelected());

        // Regression: a profile installed while the startup version refresh was still in flight
        // used to be thrown away again. start() refilled the picker and re-selected the remembered
        // id, which still pointed at the vanilla version played before the install, so Play then
        // launched vanilla even though Forge had installed successfully. The remembered id is now
        // written as soon as an install finishes, so restoring it has to keep the profile selected.
        String restored = LauncherController.restoredVersionId(
                List.of("1.20.1"), List.of(profileId), profileId);
        check("restored version keeps a freshly installed profile selected",
                profileId.equals(restored));
        check("restored version is never an unknown id",
                "1.20.1".equals(LauncherController.restoredVersionId(
                        List.of("1.20.1"), List.of(), "1.20.1")));
        // A remembered id that is no longer offered yields nothing, so the caller falls back to the
        // default release instead of leaving the picker on a row that cannot be launched.
        check("restored version yields nothing for a deleted id",
                LauncherController.restoredVersionId(
                        List.of("1.20.1"), List.of(profileId), "9.9.9") == null);
        check("restored version yields nothing when blank",
                LauncherController.restoredVersionId(List.of("1.20.1"), List.of(), "  ") == null);

        view.loaderPicker.getSelectionModel().select("NeoForge");
        controller.refreshState();
        check("forge opt-in not forced on neoforge", !view.forgeOptIn.isManaged());

        view.loaderPicker.getSelectionModel().select("Fabric");
        controller.refreshState();

        // A finished installer profile is selected and remains a launchable target.
        String forgeId = writeForgeProfile(config);
        view.versionBox.getItems().add(forgeId);
        view.versionBox.getSelectionModel().select(forgeId);
        check("selecting a profile makes getValue() report it",
                forgeId.equals(view.versionBox.getValue()));
        check("selecting a profile shows its readable editor label",
            view.versionBox.getEditor().getText().contains("Forge"));
        check("readable profile label resolves to its version id",
            forgeId.equals(view.versionBox.getConverter()
                .fromString(view.versionBox.getEditor().getText())));
        controller.refreshState();
        check("play still enabled with a profile selected", !view.playButton.isDisable());
        check("profile info names Forge", view.profileInfo.getText().contains("Forge"));
        // The box is editable, so a typed id has to be able to move the target as well. Right now
        // only getValue() is consulted and the editor text is decoration.
        view.versionBox.getEditor().setText("1.20.1");
        controller.refreshState();
        check("a typed id is honoured as the launch target",
            view.profileNameField.isManaged());
        // Switching the picker back to vanilla is what the picker does when the list is refilled.
        view.versionBox.getSelectionModel().select("1.20.1");
        controller.refreshState();
        check("vanilla is selectable again", "1.20.1".equals(view.versionBox.getValue()));

        // 1. The provider picker must be reachable: it is what selects Ely/Microsoft at all.
        check("provider picker present", view.providerBox.getChildren().contains(view.providerPicker));
        check("provider picker in scene", inScene(view.providerPicker, root));

        // 2. Offline asks for a nickname only, and hides the pointless login button.
        controller.beginLogin("offline");
        check("offline shows a nickname field", view.loginFields.containsKey("username"));
        check("offline password field hidden", !view.loginFields.containsKey("password"));
        check("offline hides login button", !view.loginButton.isVisible());
        check("offline login button not managed", !view.loginButton.isManaged());
        check("login button in scene", inScene(view.loginButton, root));

        // 3. A valid nickname is enough to play, without pressing any login button.
        view.loginFields.get("username").setText("Steve");
        controller.refreshState();
        check("offline nickname enables play", !view.playButton.isDisable());
        check("play label stays ИГРАТЬ", view.playButton.getText().equals("ИГРАТЬ"));

        // 4. An invalid nickname must not be playable.
        view.loginFields.get("username").setText("плохой ник");
        controller.refreshState();
        check("invalid nickname disables play", view.playButton.isDisable());

        // 5. Switching to Ely must not wipe the picker: that was the original regression.
        int pickersBefore = view.providerBox.getChildren().size();
        controller.beginLogin("ely");
        check("ely shows login field", view.loginFields.containsKey("login"));
        check("ely shows password field", view.loginFields.containsKey("password"));
        check("ely hides totp before request", !view.loginFields.containsKey("totp"));
        check("ely shows login button", view.loginButton.isVisible());
        check("provider picker survives provider switch",
                view.providerBox.getChildren().size() == pickersBefore);
        check("login button survives provider switch", inScene(view.loginButton, root));

        // 6. Ely 2FA swaps the form to a single TOTP field.
        controller.beginLogin("ely");
        view.showFieldsFor("ely", java.util.Map.of("totp", "Код 2FA (6 цифр)"));
        check("2FA swaps to totp only", view.loginFields.size() == 1
                && view.loginFields.containsKey("totp"));

        // 7. Microsoft asks for a client id, not a password.
        controller.beginLogin("microsoft");
        check("microsoft asks for clientId", view.loginFields.containsKey("clientId"));
        check("microsoft hides password", !view.loginFields.containsKey("password"));

        // 8. Switching provider must not leave a previous session ready to play.
        controller.beginLogin("offline");
        check("no session before login", controller.currentSession() == null);

        // 9. The real offline authenticator produces a usable session.
        var offline = new OfflineAuthenticator();
        try {
            var session = offline.login(OfflineAuthenticator.request("UiTester"), line -> { });
            check("offline login returns session", session instanceof OfflineSession);
            check("offline uuid has no dashes",
                    session.uuid().length() == 32 && !session.uuid().contains("-"));
            check("offline uses legacy user type", session.userType().equals("legacy"));
        } catch (Exception e) {
            check("offline login returns session", false);
        }

        try {
            offline.login(OfflineAuthenticator.request("плохой ник"), line -> { });
            check("offline rejects bad nickname", false);
        } catch (Exception e) {
            check("offline rejects bad nickname", true);
        }
        check("nickname rule shared with UI",
                OfflineAuthenticator.isValidNickname("Steve_1")
                        && !OfflineAuthenticator.isValidNickname("плохой ник"));

        // 10. The log starts collapsed and the toggle reveals it.
        check("log hidden by default", !view.logArea.isVisible());
        view.detailsButton.setSelected(true);
        check("details toggle shows log", view.logArea.isVisible());
        view.detailsButton.setSelected(false);
        check("details toggle hides log", !view.logArea.isVisible());
        view.logBatch(java.util.Collections.nCopies(2400, "Forge patch progress line"));
        check("visible log buffer is bounded", view.logArea.getLength() <= 80000);

        // 11. Progress uses a real bar with a plain-language caption.
        check("progress bar hidden when idle", !view.progressBar.isVisible());
        view.showProgress(42, "ассеты: something");
        check("progress bar visible while loading", view.progressBar.isVisible());
        check("progress caption has percent", view.progressLabel.getText().contains("42%"));
        view.hideProgress();
        check("progress bar hidden after load", !view.progressBar.isVisible());

        // 12. Stop is only offered while the game runs.
        check("stop button hidden while idle", !view.stopButton.isVisible());

        // 13. Play stays disabled until both a session and a valid version exist.
        view.loginFields.get("username").setText("");
        controller.refreshState();
        check("play disabled without a nickname", view.playButton.isDisable());

        // 14. Layout rules: one column width, no dead space, custom title bar.
        check("all fields share a width", fieldsAligned(view));
        check("play button in footer", inScene(view.playButton, root));
        check("title bar in scene", inScene(view.titleBar, root));
        check("open folder button in scene", inScene(view.openFolderButton, root));
        // Title bar holds the name, a filler and the three window buttons only: no folder
        // shortcut, and no second copy of the settings tab.
        check("no settings duplicate in header", view.titleBar.getChildren().size() == 5);
        check("folder shortcut not in header", !inScene(view.openFolderButton, view.titleBar));
        check("folder shortcut has tooltip", view.openFolderButton.getTooltip() != null);

        // 15. Version management and window behaviour are wired to real settings.
        check("delete selected version button", inScene(view.deleteVersionButton, root));
        check("delete all versions button", inScene(view.deleteAllVersionsButton, root));
        check("clean assets button", inScene(view.cleanAssetsButton, root));
        check("close action picker", inScene(view.closeAction, root));
        check("wallpaper picker in settings", inScene(view.chooseWallpaperButton, root));
        check("wallpaper reset in settings", inScene(view.clearWallpaperButton, root));
        Path wallpaper = dataDir.resolve("ui-check-wallpaper.png");
        java.awt.image.BufferedImage wallpaperPixels = new java.awt.image.BufferedImage(
            4, 2, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        javax.imageio.ImageIO.write(wallpaperPixels, "png", wallpaper.toFile());
        view.setWallpaper(wallpaper);
        check("custom wallpaper is loaded", view.wallpaperStatus.getText().equals(wallpaper.getFileName().toString()));
        view.setWallpaper(null);
        check("wallpaper reset restores default state", "Обои не выбраны".equals(view.wallpaperStatus.getText()));
        check("theme picker in scene", inScene(view.appearanceTheme, root));
        check("accent picker in scene", inScene(view.appearanceAccent, root));
        check("text size control in scene", inScene(view.interfaceFontSize, root));
        view.appearanceTheme.getSelectionModel().select("Светлая");
        check("theme change is applied and saved",
            config.appearanceTheme.equals("light") && root.getStyleClass().contains("theme-light"));
        root.applyCss();
        check("light theme changes the rendered background",
            root.getBackground() != null
                && root.getBackground().getFills().get(0).getFill()
                    .equals(javafx.scene.paint.Color.web("#f0f3ef")));
        view.appearanceAccent.setValue(javafx.scene.paint.Color.web("#D35F42"));
        check("accent color change is applied and saved",
            config.appearanceAccent.equals("#D35F42") && root.getStyle().contains("#D35F42"));
        view.interfaceFontSize.setValue(16);
        check("text size change is saved", config.interfaceFontSize == 16);
        check("close action maps hide", LauncherController
                .closeActionFor("Скрывать в трей").equals("hide"));
        check("close action maps minimize", LauncherController
                .closeActionFor("Сворачивать в трей").equals("minimize"));
        check("close action defaults to close",
                LauncherController.closeActionFor(null).equals("close"));
        check("close action label round trips", LauncherController
                .closeActionLabel("hide").equals("Скрывать в трей"));

        // 15. Every text node is readable, i.e. the file was not saved with broken encoding.
        long cyrillic = countCyrillic(root);
        check("UI text is intact UTF-8 (" + cyrillic + " cyrillic chars)", cyrillic > 20);

        stage.close();
    }

    /** All credential fields and the version picker must line up in one column. */
    private static boolean fieldsAligned(LauncherView view) {
        for (javafx.scene.control.TextField field : view.loginFields.values()) {
            if (Math.abs(field.getPrefWidth() - LauncherView.FIELD_WIDTH) > 0.5) {
                return false;
            }
        }
        return Math.abs(view.maxMemorySlider.getPrefWidth() - LauncherView.COLUMN_WIDTH) < 0.5;
    }

    /**
     * Walks the scene graph looking for a node.
     *
     * <p>Two containers hide their children from a plain recursive walk: a {@link Tab} is not a
     * {@link Node} and {@code TabPane} reports no children at all, while {@code ScrollPane} keeps
     * its content in a viewport instead of the children list. Both have to be entered explicitly,
     * or every widget inside a tab form looks missing.
     */
    private static boolean inScene(Node target, Node node) {
        if (node == target) {
            return true;
        }
        if (node instanceof TabPane pane) {
            for (Tab tab : pane.getTabs()) {
                if (tab.getContent() != null && inScene(target, tab.getContent())) {
                    return true;
                }
            }
            return false;
        }
        if (node instanceof javafx.scene.control.ScrollPane scroll) {
            return scroll.getContent() != null && inScene(target, scroll.getContent());
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                if (inScene(target, child)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static long countCyrillic(Node node) {
        long count = 0;
        if (node instanceof javafx.scene.control.Labeled labeled && labeled.getText() != null) {
            count += labeled.getText().chars().filter(c -> c >= 0x400 && c <= 0x4FF).count();
        }
        if (node instanceof javafx.scene.control.TextInputControl input
                && input.getPromptText() != null) {
            count += input.getPromptText().chars().filter(c -> c >= 0x400 && c <= 0x4FF).count();
        }
        if (node instanceof javafx.scene.text.Text text && text.getText() != null) {
            count += text.getText().chars().filter(c -> c >= 0x400 && c <= 0x4FF).count();
        }
        if (node instanceof TabPane pane) {
            for (Tab tab : pane.getTabs()) {
                if (tab.getContent() != null) {
                    count += countCyrillic(tab.getContent());
                }
            }
            return count;
        }
        if (node instanceof javafx.scene.control.ScrollPane scroll) {
            // Without this the count would miss every form field label.
            return scroll.getContent() == null ? 0 : countCyrillic(scroll.getContent());
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                count += countCyrillic(child);
            }
        }
        return count;
    }

    private static void check(String name, boolean condition) {
        if (condition) {
            passed++;
            System.out.println("PASS " + name);
        } else {
            failures.add(name);
            System.out.println("FAIL " + name);
        }
    }

    /** Scratch data dir, kept out of the real launcher folder so the check is repeatable. */
    private static Path createDataDir() {
        Path dir = Path.of(System.getProperty("user.home"), ".mclauncher-uicheck").toAbsolutePath();
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return dir;
    }

    /**
     * Writes a Fabric profile and one mod, standing in for a real installation.
     *
     * <p>Only the files the launcher reads are produced, so the check stays offline and does not
     * depend on what the network is doing.
     */
    private static String writeProfile(LauncherConfig config) throws IOException {
        String id = "fabric-1.20.1-0.19.5";
        Path dir = config.versionsDir().resolve(id);
        Files.createDirectories(dir.resolve("mods"));
        Files.writeString(dir.resolve("mods").resolve("examplemod-1.0.jar"), "not a real jar");
        Files.writeString(dir.resolve(id + ".json"), """
                {"id":"%s","inheritsFrom":"1.20.1","type":"release",
                 "mainClass":"net.fabricmc.loader.impl.launch.knot.KnotClient",
                 "libraries":[],
                 "modloader":{"type":"fabric","version":"0.19.5","base":"1.20.1"}}
                """.formatted(id));
        return id;
    }

    /**
     * Writes a Forge profile, standing in for a real Forge install.
     *
     * <p>Forge keeps its mods in the game directory rather than next to the version, so this one
     * deliberately has no mods folder under the version.
     */
    private static String writeForgeProfile(LauncherConfig config) throws IOException {
        String id = "1.20.1-forge-47.4.23";
        Path dir = config.versionsDir().resolve(id);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(id + ".json"), """
                {"id":"%s","inheritsFrom":"1.20.1","type":"release",
                 "mainClass":"cpw.mods.bootstraplauncher.BootstrapLauncher",
                 "libraries":[],
                 "modloader":{"type":"forge","version":"47.4.23","base":"1.20.1"}}
                """.formatted(id));
        return id;
    }
}
