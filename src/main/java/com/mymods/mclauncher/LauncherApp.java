package com.mymods.mclauncher;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.mymods.mclauncher.auth.AuthProviderInfo;
import com.mymods.mclauncher.config.LauncherConfig;
import com.mymods.mclauncher.mc.JavaRuntimes;
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
    private static final double RESIZE_MARGIN = 7;
    private static final int EDGE_LEFT = 1;
    private static final int EDGE_RIGHT = 2;
    private static final int EDGE_TOP = 4;
    private static final int EDGE_BOTTOM = 8;

    private LauncherConfig config;
    private Path dataDir;
    private LauncherController controller;
    private double restoreWidth;
    private double restoreHeight;
    private double restoreX;
    private double restoreY;
    private boolean maximized;
    private int resizeEdges;
    private double resizeAnchorX;
    private double resizeAnchorY;
    private double resizeStartX;
    private double resizeStartY;
    private double resizeStartWidth;
    private double resizeStartHeight;
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
        loadBundledFont();
        LauncherView view = new LauncherView();
        controller = new LauncherController(view, config, dataDir);

        view.versionBox.getItems().add("Загрузка...");
        // The version itself is restored by LauncherController once the Mojang manifest is in.
        // Spinners only accept committed values, so set them through the factory.
        view.minMemory.getValueFactory().setValue(clamp(config.minMemoryMb, 0, 65536));
        view.maxMemorySlider.setValue(clamp(config.maxMemoryMb, 512, 16384));
        view.setMaxMemoryCeiling(totalMemoryMb() - 2048);
        view.extraArgs.setText(config.extraJvmArgs);
        view.setJavaPathValue(config.javaPath);
        CompletableFuture.supplyAsync(() -> JavaRuntimes.scan(
                config.installRoot().resolve("runtimes")))
            .thenAccept(runtimes -> javafx.application.Platform.runLater(() -> {
                view.setJavaChoices(runtimes.stream().map(runtime -> runtime.java().toString())
                    .toList());
                view.setJavaPathValue(config.javaPath);
                view.javaChoice.valueProperty().addListener((obs, old, value) ->
                    updateJavaStatus(view, runtimes, view.javaPathValue()));
                updateJavaStatus(view, runtimes, view.javaPathValue());
            }));
        view.authServer.setText(config.authServerUrl);
        view.microsoftClientId.setText(config.microsoftClientId);
        view.toggles.isolateGameDir.setSelected(config.isolateGameDir);
        view.toggles.authlibInjector.setSelected(config.useAuthlibInjector);
        view.appearanceTheme.getSelectionModel().select(
            "light".equals(config.appearanceTheme) ? "Светлая" : "Тёмная");
        try {
            view.appearanceAccent.setValue(Color.web(config.appearanceAccent));
        } catch (IllegalArgumentException ignored) {
            view.appearanceAccent.setValue(Color.web("#FF7A59"));
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

    /** Tray entry: left click or the menu reopen the window, the menu can also close for real. */
    private void installTrayIcon(Stage stage) {
        if (!java.awt.SystemTray.isSupported()) {
            return;
        }
        try {
            java.awt.TrayIcon trayIcon = new java.awt.TrayIcon(trayImage());
            trayIcon.setToolTip("MCLauncher");
            trayIcon.setImageAutoSize(true);
            trayIcon.addActionListener(e -> javafx.application.Platform.runLater(() -> show(stage)));

            java.awt.PopupMenu menu = new java.awt.PopupMenu();
            java.awt.MenuItem showItem = new java.awt.MenuItem("Показать лаунчер");
            showItem.addActionListener(e -> javafx.application.Platform.runLater(() -> show(stage)));
            java.awt.MenuItem closeItem = new java.awt.MenuItem("Закрыть лаунчер");
            closeItem.addActionListener(e -> quitFromTray(stage));
            menu.add(showItem);
            menu.addSeparator();
            menu.add(closeItem);
            trayIcon.setPopupMenu(menu);

            java.awt.SystemTray.getSystemTray().add(trayIcon);
        } catch (Exception e) {
            // A missing tray is not worth failing startup over; the setting still works for
            // minimise and close.
            System.err.println("Tray icon unavailable: " + e.getMessage());
        }
    }

    private static void show(Stage stage) {
        stage.show();
        stage.setIconified(false);
        stage.toFront();
    }

    /**
     * A real exit from the tray, deliberately ignoring the "hide to tray" close setting: a running
     * Minecraft goes down first (the graceful stop can take up to 20 s), then the window, then the
     * JVM — AWT's event thread is not a daemon and would otherwise keep the process alive.
     */
    private void quitFromTray(Stage stage) {
        java.util.concurrent.CompletableFuture<Void> stopped = new java.util.concurrent.CompletableFuture<>();
        javafx.application.Platform.runLater(() -> {
            try {
                controller.stopGame();
                stopped.complete(null);
            } catch (RuntimeException e) {
                stopped.completeExceptionally(e);
            }
        });
        stopped.whenComplete((ok, err) -> javafx.application.Platform.runLater(() -> {
            stage.hide();
            config.save(dataDir);
            javafx.application.Platform.exit();
            new Thread(() -> {
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                System.exit(0);
            }, "tray-force-exit").start();
        }));
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
        javafx.scene.Node root = view.rootNode();
        root.addEventFilter(MouseEvent.MOUSE_MOVED, event -> {
            if (resizeEdges == 0) {
                root.setCursor(maximized ? javafx.scene.Cursor.DEFAULT
                        : resizeCursor(resizeEdgesAt(event.getSceneX(), event.getSceneY(), root)));
            }
        });
        root.addEventFilter(MouseEvent.MOUSE_PRESSED, event -> {
            if (maximized || isButtonClick(event)) {
                return;
            }
            resizeEdges = resizeEdgesAt(event.getSceneX(), event.getSceneY(), root);
            if (resizeEdges != 0) {
                resizeAnchorX = event.getScreenX();
                resizeAnchorY = event.getScreenY();
                resizeStartX = stage.getX();
                resizeStartY = stage.getY();
                resizeStartWidth = stage.getWidth();
                resizeStartHeight = stage.getHeight();
                event.consume();
            }
        });
        root.addEventFilter(MouseEvent.MOUSE_DRAGGED, event -> {
            if (resizeEdges == 0) {
                return;
            }
            double deltaX = event.getScreenX() - resizeAnchorX;
            double deltaY = event.getScreenY() - resizeAnchorY;
            double width = resizeStartWidth;
            double height = resizeStartHeight;
            double x = resizeStartX;
            double y = resizeStartY;
            if ((resizeEdges & EDGE_LEFT) != 0) {
                width = Math.max(MIN_WIDTH, resizeStartWidth - deltaX);
                x = resizeStartX + resizeStartWidth - width;
            } else if ((resizeEdges & EDGE_RIGHT) != 0) {
                width = Math.max(MIN_WIDTH, resizeStartWidth + deltaX);
            }
            if ((resizeEdges & EDGE_TOP) != 0) {
                height = Math.max(MIN_HEIGHT, resizeStartHeight - deltaY);
                y = resizeStartY + resizeStartHeight - height;
            } else if ((resizeEdges & EDGE_BOTTOM) != 0) {
                height = Math.max(MIN_HEIGHT, resizeStartHeight + deltaY);
            }
            stage.setX(x);
            stage.setY(y);
            stage.setWidth(width);
            stage.setHeight(height);
            event.consume();
        });
        root.addEventFilter(MouseEvent.MOUSE_RELEASED, event -> {
            resizeEdges = 0;
            root.setCursor(javafx.scene.Cursor.DEFAULT);
        });

        // Stage has no beginDrag(), so the window moves from mouse deltas captured on the top bar
        // and the sidebar title; anything under a button is a click rather than a drag start.
        for (javafx.scene.Node dragSurface : java.util.List.of(view.navBar, view.brand)) {
            dragSurface.setOnMousePressed(event -> {
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
            dragSurface.setOnMouseDragged(event -> {
                if (isButtonClick(event)) {
                    return;
                }
                stage.setX(stageAnchorX + (event.getScreenX() - dragAnchorX));
                stage.setY(stageAnchorY + (event.getScreenY() - dragAnchorY));
            });
        }

        view.windowMinimize.setOnAction(e -> stage.setIconified(true));
        view.windowMaximize.setOnAction(e -> toggleMaximize(stage, view));
        view.windowClose.setOnAction(e -> stage.close());

        // A native frame is gone, so the usual close shortcut has to be provided, and Escape
        // dismisses whatever popup the window currently has.
        javafx.scene.Scene scene = view.rootNode().getScene();
        if (scene != null) {
            scene.addEventHandler(javafx.scene.input.KeyEvent.KEY_PRESSED, event -> {
                if (event.isControlDown() && event.getCode() == KeyCode.Q) {
                    stage.close();
                } else if (event.getCode() == KeyCode.ESCAPE) {
                    view.closeTransient();
                }
            });
        }
    }

    /** Clicking the frame next to a button still drags, so only the buttons themselves count. */
    private static boolean isButtonClick(MouseEvent event) {
        javafx.scene.Node node = (javafx.scene.Node) event.getTarget();
        while (node != null) {
            if (node instanceof javafx.scene.control.ButtonBase) {
                return true;
            }
            node = node.getParent();
        }
        return false;
    }

    private static int resizeEdgesAt(double x, double y, javafx.scene.Node root) {
        int edges = 0;
        if (x <= RESIZE_MARGIN) edges |= EDGE_LEFT;
        if (x >= root.getScene().getWidth() - RESIZE_MARGIN) edges |= EDGE_RIGHT;
        if (y <= RESIZE_MARGIN) edges |= EDGE_TOP;
        if (y >= root.getScene().getHeight() - RESIZE_MARGIN) edges |= EDGE_BOTTOM;
        return edges;
    }

    private static javafx.scene.Cursor resizeCursor(int edges) {
        return switch (edges) {
            case EDGE_LEFT, EDGE_RIGHT -> javafx.scene.Cursor.H_RESIZE;
            case EDGE_TOP, EDGE_BOTTOM -> javafx.scene.Cursor.V_RESIZE;
            case EDGE_TOP | EDGE_LEFT, EDGE_BOTTOM | EDGE_RIGHT -> javafx.scene.Cursor.NW_RESIZE;
            case EDGE_TOP | EDGE_RIGHT, EDGE_BOTTOM | EDGE_LEFT -> javafx.scene.Cursor.NE_RESIZE;
            default -> javafx.scene.Cursor.DEFAULT;
        };
    }

    /** Physical memory, used to cap the "max heap" slider two gigabytes below what the PC has. */
    private static void loadBundledFont() {
        try (var font = LauncherApp.class.getResourceAsStream("/fonts/Rubik-Variable.ttf")) {
            if (font == null || javafx.scene.text.Font.loadFont(font, 13) == null) {
                throw new IllegalStateException("Bundled Rubik font could not be loaded");
            }
        } catch (IOException e) {
            throw new IllegalStateException("Bundled Rubik font could not be loaded", e);
        }
    }

    private static void updateJavaStatus(LauncherView view,
            List<JavaRuntimes.Runtime> runtimes, String selectedPath) {
        boolean automatic = selectedPath == null || selectedPath.isBlank()
                || "Автоматически".equals(selectedPath);
        JavaRuntimes.Runtime selected = null;
        if (automatic) {
            Path currentHome = Path.of(System.getProperty("java.home")).toAbsolutePath().normalize();
            selected = runtimes.stream()
                    .filter(runtime -> runtime.home().toAbsolutePath().normalize().equals(currentHome))
                    .findFirst().orElse(null);
            if (selected == null && !runtimes.isEmpty()) {
                selected = runtimes.get(0);
            }
        } else {
            String requested = Path.of(selectedPath).toAbsolutePath().normalize().toString();
            selected = runtimes.stream()
                    .filter(runtime -> runtime.java().toAbsolutePath().normalize().toString()
                            .equalsIgnoreCase(requested))
                    .findFirst().orElse(null);
        }
        if (selected == null) {
            view.setStatsJava("Не найдена", automatic ? "Java не обнаружена" : "Проверьте путь");
        } else {
            view.setStatsJava("Java " + selected.majorVersion(),
                    (automatic ? "Авто · " : "") + selected.home());
        }
    }

    private static int totalMemoryMb() {
        try {
            java.lang.management.OperatingSystemMXBean os =
                    java.lang.management.ManagementFactory.getOperatingSystemMXBean();
            if (os instanceof com.sun.management.OperatingSystemMXBean sun) {
                long total = sun.getTotalPhysicalMemorySize();
                if (total > 0) {
                    return (int) Math.min(Integer.MAX_VALUE, total / (1024 * 1024));
                }
            }
        } catch (RuntimeException ignored) {
            // An unreadable memory size is not worth failing startup over.
        }
        return 16384;
    }

    private void toggleMaximize(Stage stage, LauncherView view) {
        if (maximized) {
            maximized = false;
            stage.setWidth(restoreWidth > 0 ? restoreWidth : DEFAULT_WIDTH);
            stage.setHeight(restoreHeight > 0 ? restoreHeight : DEFAULT_HEIGHT);
            stage.setX(restoreX);
            stage.setY(restoreY);
            view.windowMaximize.setText("\u25A1");
            return;
        }
        maximized = true;
        restoreX = stage.getX();
        restoreY = stage.getY();
        restoreWidth = stage.getWidth();
        restoreHeight = stage.getHeight();
        javafx.geometry.Rectangle2D bounds = javafx.stage.Screen
                .getScreensForRectangle(stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight())
                .stream().findFirst().orElse(javafx.stage.Screen.getPrimary()).getVisualBounds();
        stage.setX(bounds.getMinX());
        stage.setY(bounds.getMinY());
        stage.setWidth(bounds.getWidth());
        stage.setHeight(bounds.getHeight());
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
