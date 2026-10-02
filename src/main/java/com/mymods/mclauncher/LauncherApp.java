package com.mymods.mclauncher;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

import com.mymods.mclauncher.auth.AuthProviderInfo;
import com.mymods.mclauncher.config.LauncherConfig;
import com.mymods.mclauncher.ui.LauncherController;
import com.mymods.mclauncher.ui.LauncherView;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseEvent;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

/**
 * JavaFX entry point.
 *
 * <p>The window is undecorated so the whole frame is dark: the Windows title bar cannot be
 * recoloured from CSS, so it is replaced by the launcher's own title bar with drag and buttons.
 */
public final class LauncherApp extends Application {

    private static final double DEFAULT_WIDTH = 900;
    private static final double DEFAULT_HEIGHT = 720;
    private static final double MIN_WIDTH = 760;
    private static final double MIN_HEIGHT = 600;

    private LauncherConfig config;
    private Path dataDir;
    private LauncherController controller;
    private double restoreWidth;
    private double restoreHeight;
    private double dragAnchorX;
    private double dragAnchorY;
    private double stageAnchorX;
    private double stageAnchorY;

    @Override
    public void init() {
        dataDir = Path.of(System.getProperty("user.home"), ".mclauncher");
        config = LauncherConfig.load(dataDir);
    }

    @Override
    public void start(Stage stage) {
        LauncherView view = new LauncherView();
        controller = new LauncherController(view, config, dataDir);

        view.versionBox.getItems().add("Загрузка...");
        // The version itself is restored by LauncherController once the Mojang manifest is in.
        // Spinners only accept committed values, so set them through the factory.
        view.minMemory.getValueFactory().setValue(clamp(config.minMemoryMb, 0, 65536));
        view.maxMemorySlider.setValue(clamp(config.maxMemoryMb, 512, 16384));
        view.extraArgs.setText(config.extraJvmArgs);
        view.javaPath.setText(config.javaPath);
        view.authServer.setText(config.authServerUrl);
        view.microsoftClientId.setText(config.microsoftClientId);
        view.toggles.isolateGameDir.setSelected(config.isolateGameDir);
        view.toggles.authlibInjector.setSelected(config.useAuthlibInjector);
        view.appearanceTheme.getSelectionModel().select(
            "light".equals(config.appearanceTheme) ? "Светлая" : "Тёмная");
        try {
            view.appearanceAccent.setValue(Color.web(config.appearanceAccent));
        } catch (IllegalArgumentException ignored) {
            view.appearanceAccent.setValue(Color.web("#63A86F"));
        }
        view.interfaceFontSize.setValue(clamp(config.interfaceFontSize, 12, 17));
        view.applyAppearance(config.appearanceTheme, config.appearanceAccent,
            config.interfaceFontSize);
        view.closeAction.getSelectionModel().select(LauncherController.closeActionLabel(config.closeAction));

        AuthProviderInfo[] providers = AuthProviderInfo.all();
        view.providerPicker.getSelectionModel().select(providerIndex(config.authProvider));
        view.providerPicker.setOnAction(e -> {
            int index = view.providerPicker.getSelectionModel().getSelectedIndex();
            if (index >= 0) {
                controller.beginLogin(providers[index].id());
            }
        });

        controller.bindActions();
        controller.beginLogin(config.authProvider);

        Scene scene = new Scene(view.build(), DEFAULT_WIDTH, DEFAULT_HEIGHT);
        scene.getStylesheets().add(LauncherApp.class.getResource("/launcher.css").toExternalForm());
        controller.restoreWallpaper();

        stage.initStyle(StageStyle.UNDECORATED);
        stage.setTitle("MCLauncher");
        stage.setScene(scene);
        stage.setMinWidth(MIN_WIDTH);
        stage.setMinHeight(MIN_HEIGHT);
        wireTitleBar(stage, view);
        wireCloseBehaviour(stage, view);
        stage.show();

        controller.refreshDiskUsage();
        CompletableFuture.runAsync(controller::start);
    }

    /**
     * Honours the "on close" setting: exit, hide to tray, or minimise.
     *
     * <p>A running game always keeps the process alive regardless, so closing the window mid-game
     * must not kill the session it is serving.
     */
    private void wireCloseBehaviour(Stage stage, LauncherView view) {
        installTrayIcon(stage);
        stage.setOnCloseRequest(event -> {
            boolean running = controller.gameRunning();
            switch (config.closeAction) {
                case "hide" -> {
                    // With a game running, hiding entirely would strand the user with no window
                    // to come back to, so fall back to minimising.
                    if (running) {
                        event.consume();
                        stage.setIconified(true);
                        return;
                    }
                    event.consume();
                    stage.hide();
                }
                case "minimize" -> {
                    event.consume();
                    stage.setIconified(true);
                }
                default -> {
                    if (running) {
                        event.consume();
                        controller.stopGame();
                        stage.close();
                    }
                }
            }
        });
    }

    /** Tray entry that reopens the window, so "hide" is reversible. */
    private void installTrayIcon(Stage stage) {
        if (!java.awt.SystemTray.isSupported()) {
            return;
        }
        try {
            java.awt.TrayIcon trayIcon = new java.awt.TrayIcon(trayImage());
            trayIcon.setToolTip("MCLauncher");
            trayIcon.setImageAutoSize(true);
            // AWT TrayIcon has no context menu, so a left click is the way back to the window.
            trayIcon.addActionListener(e -> javafx.application.Platform.runLater(() -> {
                stage.show();
                stage.setIconified(false);
                stage.toFront();
            }));
            java.awt.SystemTray.getSystemTray().add(trayIcon);
        } catch (Exception e) {
            // A missing tray is not worth failing startup over; the setting still works for
            // minimise and close.
            System.err.println("Tray icon unavailable: " + e.getMessage());
        }
    }

    /** A small grass-block style square, drawn here so no image file has to ship. */
    private static java.awt.Image trayImage() {
        java.awt.image.BufferedImage image =
                new java.awt.image.BufferedImage(16, 16, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = image.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new java.awt.Color(0x5B, 0x8C, 0x3E));
        g.fillRect(0, 0, 16, 5);
        g.setColor(new java.awt.Color(0x79, 0x5A, 0x3C));
        g.fillRect(0, 5, 16, 11);
        g.setColor(new java.awt.Color(0x2B, 0x2B, 0x2B));
        g.drawRect(0, 0, 15, 15);
        g.dispose();
        return image;
    }

    /** Drag, minimise, maximise and close, since the system frame is gone. */
    private void wireTitleBar(Stage stage, LauncherView view) {
        // Stage has no beginDrag(), so the title bar moves the window from mouse deltas.
        view.titleBar.setOnMousePressed(event -> {
            if (isButtonClick(event)) {
                return;
            }
            if (event.getClickCount() == 2) {
                toggleMaximize(stage, view);
                return;
            }
            dragAnchorX = event.getScreenX();
            dragAnchorY = event.getScreenY();
            stageAnchorX = stage.getX();
            stageAnchorY = stage.getY();
        });
        view.titleBar.setOnMouseDragged(event -> {
            if (isButtonClick(event)) {
                return;
            }
            stage.setX(stageAnchorX + (event.getScreenX() - dragAnchorX));
            stage.setY(stageAnchorY + (event.getScreenY() - dragAnchorY));
        });

        view.windowMinimize.setOnAction(e -> stage.setIconified(true));
        view.windowMaximize.setOnAction(e -> toggleMaximize(stage, view));
        view.windowClose.setOnAction(e -> stage.close());

        // A native frame is gone, so the usual close shortcut has to be provided.
        view.tabs.setOnKeyPressed(e -> {
            if (e.isControlDown() && e.getCode() == KeyCode.Q) {
                stage.close();
            }
        });
    }

    /** Clicking the title bar next to a button still drags, so only the buttons themselves count. */
    private static boolean isButtonClick(MouseEvent event) {
        return event.getTarget() instanceof javafx.scene.control.Button;
    }

    private void toggleMaximize(Stage stage, LauncherView view) {
        if (stage.isMaximized()) {
            stage.setMaximized(false);
            stage.setWidth(restoreWidth > 0 ? restoreWidth : DEFAULT_WIDTH);
            stage.setHeight(restoreHeight > 0 ? restoreHeight : DEFAULT_HEIGHT);
            view.windowMaximize.setText("\u25A1");
            return;
        }
        restoreWidth = stage.getWidth();
        restoreHeight = stage.getHeight();
        stage.setMaximized(true);
        view.windowMaximize.setText("\u25AD");
    }

    private static int providerIndex(String providerId) {
        AuthProviderInfo[] all = AuthProviderInfo.all();
        for (int i = 0; i < all.length; i++) {
            if (all[i].id().equals(providerId)) {
                return i;
            }
        }
        return 0;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    @Override
    public void stop() {
        controller.stopGame();
        config.save(dataDir);
    }
}
