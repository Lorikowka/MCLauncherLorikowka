package com.mymods.mclauncher.ui;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

import com.mymods.mclauncher.auth.AuthProviderInfo;
import com.mymods.mclauncher.mc.ModLoaderType;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ColorPicker;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.Slider;
import javafx.scene.control.Spinner;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.util.StringConverter;

/**
 * The launcher window, built in code rather than FXML so the whole layout reads in one place.
 *
 * <p>This class only constructs and exposes widgets; {@link LauncherController} wires behaviour.
 * Colours and sizes live in {@code launcher.css} so the window stays visually consistent.
 *
 * <p>Layout follows three rules the feedback asked for: one aligned column for all inputs, the
 * main action in a fixed footer instead of floating in empty space, and the raw log hidden behind a
 * toggle so file names do not crowd the window.
 */
public final class LauncherView {

    private static final int MAX_VISIBLE_LOG_CHARS = 80000;

    /** Width shared by every credential field, so the form reads as a single column. */
    public static final double FIELD_WIDTH = 320;

    /** Width of the whole form column, a little wider than the fields it contains. */
    public static final double COLUMN_WIDTH = 380;

    /** Folder pictogram for the icon-only button; the tooltip carries the wording. */
    private static final String FOLDER_GLYPH = "\uD83D\uDCC1";

    /** Version picker: editable so a user can type an id that is not in the list. */
    public final ComboBox<String> versionBox = new ComboBox<>();

    /** Offline / Ely / Microsoft selector. Built here so it shares the column width. */
    public final ComboBox<String> providerPicker = new ComboBox<>();

    public final TextArea logArea = new TextArea();

    /** Field per auth provider, showing only the ones the selected provider needs. */
    public final Map<String, TextField> loginFields = new LinkedHashMap<>();

    /**
     * Credential rows for the current provider. Only this container is rebuilt on a provider
     * switch, which is what keeps the picker in {@link #providerBox} from disappearing.
     */
    public final VBox providerBox = new VBox(8);

    /** Rebuilt by {@link #showFieldsFor} for every provider change. */
    public final VBox loginFieldsBox = new VBox(10);

    public final Button loginButton = new Button("Войти");
    public final Button playButton = new Button("ИГРАТЬ");
    public final Button stopButton = new Button("Остановить");

    /** Icon-only shortcut to the launcher data folder, shown in the footer. */
    public final Button openFolderButton = new Button(FOLDER_GLYPH);

    /** Collapses the raw log, which is noise unless something went wrong. */
    public final ToggleButton detailsButton = new ToggleButton("Показать подробности");

    public final ProgressBar progressBar = new ProgressBar();
    public final Label progressLabel = new Label();
    public final Label statusLabel = new Label();

    public final Slider maxMemorySlider = new Slider(512, 16384, 4096);
    public final Spinner<Integer> minMemory = new Spinner<>(0, 65536, 512, 256);
    public final TextField extraArgs = new TextField();
    public final TextField javaPath = new TextField();
    public final TextField authServer = new TextField();
    public final TextField microsoftClientId = new TextField();
    public final CheckBoxToggles toggles = new CheckBoxToggles();

    public final ComboBox<String> appearanceTheme = new ComboBox<>();
    public final ColorPicker appearanceAccent = new ColorPicker(Color.web("#63A86F"));
    public final Slider interfaceFontSize = new Slider(12, 17, 13);
    public final Label interfaceFontSizeValue = new Label("13 px");

// Mod loader profiles.
    public final ComboBox<String> loaderPicker = new ComboBox<>();

    /**
     * Builds offered for the chosen loader on the chosen Minecraft version, newest first.
     *
     * <p>A pack often names one exact build, so the newest cannot be the only choice. Forge alone
     * publishes well over a hundred builds for 1.20.1, which is why this is a picker rather than a
     * text field.
     */
    public final ComboBox<String> buildPicker = new ComboBox<>();
    public final TextField profileNameField = new TextField();
    public final Button openModsButton = new Button("Папка модов");
    public final Label profileInfo = new Label();

    /**
     * Forge's generated version JSON asks that it not be downloaded without an explicit opt-in and
     * that its Patreon be linked, so both are shown before an install rather than after.
     */
    public final CheckBox forgeOptIn = new CheckBox("Разрешаю загрузку Forge");
    public final Hyperlink forgeSupportLink = new Hyperlink("Поддержать Forge на Patreon");
    public final Label loaderNote = new Label();

    // Version management.
    public final Button deleteVersionButton = new Button("Удалить выбранную");
    public final Button deleteAllVersionsButton = new Button("Удалить все версии");
    public final Button cleanAssetsButton = new Button("Очистить ассеты");
    public final Label versionDiskLabel = new Label();

    // Window behaviour on close.
    public final ComboBox<String> closeAction = new ComboBox<>();

    /** Custom dark title bar, so the window is not framed by a light system border. */
    public final HBox titleBar = new HBox(8);
    public final Button windowMinimize = new Button("\u2013");
    public final Button windowMaximize = new Button("\u25A1");
    public final Button windowClose = new Button("\u2715");

    public final TabPane tabs = new TabPane();
    private final Tab playTab = new Tab("Запуск");
    private final Tab settingsTab = new Tab("Настройки");

    /** Called when the user edits a credential, so the controller can refresh the play button. */
    public Runnable fieldsEdited = () -> { };

    private Function<String, String> versionLabelProvider = Function.identity();

    private BorderPane root;

    public LauncherView() {
        for (ComboBox<String> box : List.of(versionBox, providerPicker, loaderPicker)) {
            box.setMaxWidth(Double.MAX_VALUE);
        }
        versionBox.setEditable(true);
        versionBox.setConverter(new StringConverter<>() {
            @Override
            public String toString(String id) {
                return versionLabel(id);
            }

            @Override
            public String fromString(String label) {
                if (label == null) {
                    return null;
                }
                for (String id : versionBox.getItems()) {
                    if (versionLabel(id).equalsIgnoreCase(label.trim())) {
                        return id;
                    }
                }
                return label.trim();
            }
        });
        versionBox.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(String id, boolean empty) {
                super.updateItem(id, empty);
                setText(empty ? null : versionLabel(id));
            }
        });
        providerPicker.getItems().addAll(
                Arrays.stream(AuthProviderInfo.all()).map(AuthProviderInfo::displayName).toList());
        // The picker belongs to the provider box, which survives every field swap.
        providerBox.getChildren().addAll(sectionLabel("ВХОД"), providerPicker);

        logArea.setEditable(false);
        logArea.setVisible(false);
        logArea.setManaged(false);
        VBox.setVgrow(logArea, Priority.ALWAYS);

        minMemory.setEditable(true);
        minMemory.setPrefWidth(140);

        for (TextField field : List.of(extraArgs, javaPath, authServer, microsoftClientId)) {
            field.setMaxWidth(Double.MAX_VALUE);
        }

        maxMemorySlider.setPrefWidth(COLUMN_WIDTH);
        maxMemorySlider.setShowTickLabels(true);
        maxMemorySlider.setMajorTickUnit(2048);

        for (Button manage : List.of(deleteVersionButton, deleteAllVersionsButton, cleanAssetsButton)) {
            manage.getStyleClass().add("secondary");
            manage.setMaxWidth(Double.MAX_VALUE);
        }
        deleteAllVersionsButton.getStyleClass().add("danger-outline");
        cleanAssetsButton.getStyleClass().add("danger-outline");
        versionDiskLabel.getStyleClass().add("faint");

        closeAction.getItems().addAll("Закрывать лаунчер", "Скрывать в трей", "Сворачивать в трей");
        closeAction.setMaxWidth(Double.MAX_VALUE);

        appearanceTheme.getItems().addAll("Тёмная", "Светлая");
        appearanceTheme.getSelectionModel().selectFirst();
        appearanceTheme.setMaxWidth(Double.MAX_VALUE);
        appearanceAccent.setMaxWidth(110);
        interfaceFontSize.setShowTickLabels(true);
        interfaceFontSize.setMajorTickUnit(1);
        interfaceFontSize.setMinorTickCount(0);
        interfaceFontSize.setSnapToTicks(true);
        interfaceFontSize.setBlockIncrement(1);
        interfaceFontSize.valueProperty().addListener((obs, old, value) ->
            interfaceFontSizeValue.setText((int) Math.round(value.doubleValue()) + " px"));

        loginButton.getStyleClass().addAll("secondary");
        playButton.getStyleClass().add("primary");
        stopButton.getStyleClass().add("danger");
        openFolderButton.getStyleClass().addAll("icon-button");
        openFolderButton.setTooltip(new Tooltip("Открыть папку лаунчера"));
        detailsButton.getStyleClass().add("link");
        for (Button windowButton : List.of(windowMinimize, windowMaximize, windowClose)) {
            windowButton.getStyleClass().add("window-button");
        }
        windowClose.getStyleClass().add("window-button-close");

        // "Остановить" only means something while the game is running.
        stopButton.setVisible(false);
        stopButton.setManaged(false);

        detailsButton.selectedProperty().addListener((obs, was, isSelected) -> {
            logArea.setVisible(isSelected);
            logArea.setManaged(isSelected);
        });

        playTab.setClosable(false);
        settingsTab.setClosable(false);
        playTab.setContent(buildPlayTab());
        settingsTab.setContent(buildSettingsTab());
        tabs.getTabs().addAll(playTab, settingsTab);
    }

    public BorderPane build() {
        root = new BorderPane();
        root.getStyleClass().add("root");
        root.setTop(buildTitleBar());
        root.setCenter(tabs);
        root.setBottom(buildFooter());
        applyAppearance("Светлая".equals(appearanceTheme.getValue()) ? "light" : "dark",
            accentHex(), (int) Math.round(interfaceFontSize.getValue()));
        return root;
    }

    public void setVersionLabelProvider(Function<String, String> provider) {
        versionLabelProvider = provider == null ? Function.identity() : provider;
    }

    public String versionLabel(String id) {
        return id == null ? "" : versionLabelProvider.apply(id);
    }

    public void applyAppearance(String theme, String accentHex, int fontSize) {
        if (root == null) {
            return;
        }
        Color accent = parseAccent(accentHex);
        boolean light = "light".equals(theme);
        Color hover = accent.interpolate(light ? Color.BLACK : Color.WHITE, 0.16);
        Color soft = accent.interpolate(light ? Color.WHITE : Color.web("#171B18"), 0.86);
        String accentText = luminance(accent) > 0.58 ? "#182019" : "#FFFFFF";

        root.getStyleClass().removeAll("theme-dark", "theme-light");
        root.getStyleClass().add(light ? "theme-light" : "theme-dark");
        root.setStyle("-fx-font-size: " + Math.max(12, Math.min(17, fontSize)) + "px;"
                + " -fx-accent: " + colorHex(accent) + ";"
                + " -fx-accent-hover: " + colorHex(hover) + ";"
                + " -fx-accent-soft: " + colorHex(soft) + ";"
                + " -fx-accent-text: " + accentText + ";"
                + " -fx-focus-color: " + colorHex(accent) + ";");
    }

    public String accentHex() {
        return colorHex(appearanceAccent.getValue());
    }

    private static Color parseAccent(String value) {
        try {
            return Color.web(value == null ? "#63A86F" : value);
        } catch (IllegalArgumentException e) {
            return Color.web("#63A86F");
        }
    }

    private static String colorHex(Color color) {
        return "#%02X%02X%02X".formatted(
                Math.round((float) color.getRed() * 255),
                Math.round((float) color.getGreen() * 255),
                Math.round((float) color.getBlue() * 255));
    }

    private static double luminance(Color color) {
        return 0.2126 * color.getRed() + 0.7152 * color.getGreen()
                + 0.0722 * color.getBlue();
    }

    /** Header row: app name and the window buttons. */
    private Region buildTitleBar() {
        Label title = new Label("MCLauncher");
        title.getStyleClass().add("title");

        HBox spacer = new HBox();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        titleBar.setAlignment(Pos.CENTER_LEFT);
        titleBar.setPadding(new Insets(10, 8, 10, 18));
        titleBar.getStyleClass().add("titlebar");
        // The drag region is everything except the buttons themselves.
        titleBar.getChildren().addAll(title, spacer,
                windowMinimize, windowMaximize, windowClose);
        return titleBar;
    }

    private Region buildPlayTab() {
        VBox form = new VBox(14);
        form.setPadding(new Insets(20, 20, 20, 20));
        form.setMaxWidth(COLUMN_WIDTH);
        form.setAlignment(Pos.TOP_LEFT);
        profileInfo.getStyleClass().add("dim");
        profileInfo.setWrapText(true);
        loaderNote.getStyleClass().add("dim");
        loaderNote.setWrapText(true);

        loaderPicker.getItems().addAll(Arrays.stream(ModLoaderType.values())
            .map(ModLoaderType::displayName).toList());
        loaderPicker.getSelectionModel().select(ModLoaderType.VANILLA.displayName());
        buildPicker.setPrefWidth(140);
        buildPicker.setDisable(true);
        buildPicker.getStyleClass().add("dim");
        profileNameField.setPromptText("Название сборки (необязательно)");
        profileNameField.setMaxWidth(Double.MAX_VALUE);
        forgeSupportLink.setText("Поддержать Forge на Patreon");
        forgeSupportLink.setVisible(false);
        forgeSupportLink.setManaged(false);
        forgeOptIn.setVisible(false);
        forgeOptIn.setManaged(false);

        HBox modRow = new HBox(8, loaderPicker, buildPicker);
        modRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(loaderPicker, Priority.ALWAYS);
        HBox profileRow = new HBox(8, profileNameField, openModsButton);
        profileRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(profileNameField, Priority.ALWAYS);

        form.getChildren().addAll(
                sectionLabel("ВЕРСИЯ MINECRAFT"),
                versionBox,
                modRow,
                profileRow,
                loaderNote,
                forgeOptIn,
                forgeSupportLink,
                profileInfo,
                providerBox,
                loginFieldsBox,
                loginButton);

        ScrollPane scroll = new ScrollPane(form);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().add("form-scroll");

        // Centre the column instead of leaving a wide empty gutter on the left.
        VBox column = new VBox(scroll);
        column.setMaxWidth(Double.MAX_VALUE);
        column.setAlignment(Pos.TOP_CENTER);
        return column;
    }

    private Region buildSettingsTab() {
        VBox box = new VBox(12);
        box.setPadding(new Insets(20));
        box.setMaxWidth(COLUMN_WIDTH);
        box.setAlignment(Pos.TOP_LEFT);

        Label memoryValue = new Label();
        memoryValue.getStyleClass().add("dim");
        maxMemorySlider.valueProperty().addListener((obs, old, value) ->
                memoryValue.setText((int) Math.round(value.doubleValue()) + " МБ"));

        HBox memoryHead = new HBox(8, sectionLabel("ПАМЯТЬ ДЛЯ ИГРЫ"), memoryValue);
        memoryHead.setAlignment(Pos.CENTER_LEFT);

        box.getChildren().addAll(
            sectionLabel("ВНЕШНИЙ ВИД"),
            appearanceTheme,
            new HBox(10, sectionLabel("АКЦЕНТНЫЙ ЦВЕТ"), appearanceAccent),
            sectionLabel("РАЗМЕР ТЕКСТА"),
            new HBox(10, interfaceFontSize, interfaceFontSizeValue),
            new Separator(),
                memoryHead,
                maxMemorySlider,
                hint("Больше памяти помогает, только если хватает её самой системе."),
                sectionLabel("МИНИМУМ, МБ"),
                minMemory,
                sectionLabel("ДОПОЛНИТЕЛЬНЫЕ ПАРАМЕТРЫ JAVA"),
                extraArgs,
                hint("Например: -XX:+UseG1GC"),
                sectionLabel("ПУТЬ К JAVA (ПУСТО = ИСКАТЬ САМОСТРОЯТЕЛЬНО)"),
                javaPath,
                sectionLabel("ELY / YGGDRASIL API"),
                authServer,
                sectionLabel("AZURE CLIENT ID ДЛЯ MICROSOFT"),
                microsoftClientId,
                toggles.isolateGameDir,
                toggles.authlibInjector,
                new Separator(),
                sectionLabel("ПАМЯТЬ НА ДИСКЕ"),
                versionDiskLabel,
                deleteVersionButton,
                deleteAllVersionsButton,
                cleanAssetsButton,
                hint("Ассеты общие для всех версий. Удаление освобождает место, но их придётся качать заново."),
                new Separator(),
                sectionLabel("ПРИ ЗАКРЫТИИ ОКНА"),
                closeAction,
                hint("«Скрывать» оставляет лаунчер в трее, чтобы он не занимал место."));

        ScrollPane scroll = new ScrollPane(box);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().add("form-scroll");

        VBox column = new VBox(scroll);
        column.setMaxWidth(Double.MAX_VALUE);
        column.setAlignment(Pos.TOP_CENTER);
        return column;
    }

    /**
     * Progress, the play button and the collapsed log. Keeping the primary action here means it
     * sits at the same place in every tab and never leaves a gap where the log used to be.
     */
    private Region buildFooter() {
        VBox footer = new VBox(10);
        footer.setPadding(new Insets(12, 20, 16, 20));
        footer.getStyleClass().add("footer");

        progressLabel.getStyleClass().add("status");
        progressLabel.setText("Готов к запуску");
        progressBar.setMaxWidth(Double.MAX_VALUE);
        progressBar.setVisible(false);
        progressBar.setManaged(false);
        HBox.setHgrow(progressBar, Priority.ALWAYS);

        footer.getChildren().add(progressBar);
        footer.getChildren().add(progressLabel);

        statusLabel.getStyleClass().add("status");
        statusLabel.setAlignment(Pos.CENTER);
        footer.getChildren().add(statusLabel);

        HBox actions = new HBox(10, spacer(), playButton, stopButton, openFolderButton, spacer());
        actions.setAlignment(Pos.CENTER);
        footer.getChildren().add(actions);

        HBox logHeader = new HBox(detailsButton);
        logHeader.setAlignment(Pos.CENTER_LEFT);
        footer.getChildren().addAll(logHeader, logArea);
        return footer;
    }

    /**
     * Registers a login field and returns it, so the controller can read its value later.
     *
     * <p>Editing any field notifies {@link #fieldsEdited} because in offline mode the nickname
     * alone decides whether the game can start.
     */
    public TextField addLoginField(String key, String label, boolean password, String prompt) {
        TextField field = password ? new PasswordField() : new TextField();
        field.setPromptText(prompt);
        field.setPrefWidth(FIELD_WIDTH);
        field.setMaxWidth(FIELD_WIDTH);
        field.textProperty().addListener((obs, old, value) -> fieldsEdited.run());
        loginFields.put(key, field);

        VBox row = new VBox(4, sectionLabel(label.toUpperCase(Locale.ROOT)), field);
        loginFieldsBox.getChildren().add(row);
        return field;
    }

    /** Swaps the visible login rows to match the selected provider. */
    public void showFieldsFor(String providerId, Map<String, String> requiredFields) {
        loginFieldsBox.getChildren().clear();
        loginFields.clear();
        loginButton.setText("Войти");

        if (providerId != null && requiredFields != null) {
            for (Map.Entry<String, String> entry : requiredFields.entrySet()) {
                addLoginField(entry.getKey(), entry.getValue(), isSecret(entry.getKey()),
                        entry.getValue());
            }
        }

        // The 2FA step has no use for the provider explainer, it only wants a code.
        boolean totpStep = loginFields.containsKey("totp");
        if (AuthProviderInfo.OFFLINE.equals(providerId)) {
            loginFieldsBox.getChildren().add(hint("Твоё имя в игре, входить не нужно."));
        } else if (AuthProviderInfo.ELY.equals(providerId) && !totpStep) {
            loginFieldsBox.getChildren().add(hint("Пароль не сохраняется."));
        } else if (AuthProviderInfo.MICROSOFT.equals(providerId)) {
            loginFieldsBox.getChildren().add(hint("Покажем код и ссылку для входа."));
        }

        // Offline needs no password step, so a "Войти" button would only add a pointless click.
        setLoginButtonVisible(!AuthProviderInfo.OFFLINE.equals(providerId));
    }

    /** Ely's 2FA step reuses the same button with a different label. */
    public void setLoginButtonVisible(boolean visible) {
        loginButton.setVisible(visible);
        loginButton.setManaged(visible);
    }

    private static boolean isSecret(String field) {
        return field.equals("password") || field.equals("totp");
    }

    /** Nickname typed in offline mode, or null when the field is absent or empty. */
    public String offlineNickname() {
        TextField field = loginFields.get("username");
        if (field == null) {
            return null;
        }
        String value = field.getText().trim();
        return value.isEmpty() ? null : value;
    }

    public void log(String line) {
        logBatch(List.of(line));
    }

    public void logBatch(List<String> lines) {
        if (lines.isEmpty()) {
            return;
        }
        StringBuilder text = new StringBuilder();
        for (String line : lines) {
            text.append(line).append(System.lineSeparator());
        }
        logArea.appendText(text.toString());
        int excess = logArea.getLength() - MAX_VISIBLE_LOG_CHARS;
        if (excess > 0) {
            String current = logArea.getText();
            int nextLine = current.indexOf('\n', excess);
            logArea.deleteText(0, nextLine < 0 ? excess : nextLine + 1);
        }
    }

    /** Shows download progress as a bar plus a plain-language caption. */
    public void showProgress(int percent, String what) {
        progressBar.setProgress(percent / 100.0);
        progressBar.setVisible(true);
        progressBar.setManaged(true);
        progressLabel.setText("Загрузка файлов игры… " + percent + "%");
    }

    public void hideProgress() {
        progressBar.setVisible(false);
        progressBar.setManaged(false);
        progressBar.setProgress(0);
    }

    public void setStatus(String text) {
        statusLabel.setText(text);
    }

    public static Label sectionLabel(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("section");
        return label;
    }

    public static Label hint(String text) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.setMaxWidth(FIELD_WIDTH);
        label.getStyleClass().add("faint");
        return label;
    }

    /** Horizontal filler that absorbs the space to one side of a centred row. */
    private static Region spacer() {
        Region region = new Region();
        HBox.setHgrow(region, Priority.ALWAYS);
        return region;
    }

    /** Group of independent boolean settings. */
    public static final class CheckBoxToggles {
        public final CheckBox isolateGameDir = new CheckBox("Отдельная папка мира для каждого ника");
        public final CheckBox authlibInjector = new CheckBox("Использовать authlib-injector для Ely");

        public CheckBoxToggles() {
            for (CheckBox box : List.of(isolateGameDir, authlibInjector)) {
                box.setMaxWidth(Double.MAX_VALUE);
            }
        }
    }
}
