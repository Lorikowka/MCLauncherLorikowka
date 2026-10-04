package com.mymods.mclauncher.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntConsumer;

import com.mymods.mclauncher.auth.AuthProviderInfo;
import com.mymods.mclauncher.mc.ModLoaderType;
import javafx.animation.PauseTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.geometry.Side;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ColorPicker;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Slider;
import javafx.scene.control.Spinner;
import javafx.scene.control.SplitMenuButton;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.Background;
import javafx.scene.layout.BackgroundImage;
import javafx.scene.layout.BackgroundPosition;
import javafx.scene.layout.BackgroundRepeat;
import javafx.scene.layout.BackgroundSize;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;
import javafx.util.StringConverter;

/**
 * The launcher window, built in code rather than FXML so the whole layout reads in one place.
 *
 * <p>This class only constructs and exposes widgets; {@link LauncherController} wires behaviour.
 * Colours and sizes live in {@code launcher.css} so the window stays visually consistent.
 */
public final class LauncherView {

    private static final int MAX_VISIBLE_LOG_CHARS = 80000;

    /** Width shared by every credential field, so the form reads as a single column. */
    public static final double FIELD_WIDTH = 320;

    /** Width of the whole form column, a little wider than the fields it contains. */
    public static final double COLUMN_WIDTH = 380;

    /** The resting status text: what the caption reads when nothing is going on. */
    public static final String BASE_STATUS = "Готов к запуску";

    /** Sidebar width, fixed like the mockup's 260px column. */
    private static final double SIDEBAR_WIDTH = 260;

    /** Version picker: the model behind the sidebar list; never shown as a widget itself. */
    public final ComboBox<String> versionBox = new ComboBox<>();

    /** Offline / Ely / Microsoft selector. Lives in the account dialog. */
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
    public final Button playButton = new Button("Играть");
    public final TextField serverAddress = new TextField();
    public final Button connectButton = new Button("Подключиться");
    public final Button stopButton = new Button("Остановить");

    /** Opens the console window with the raw log. */
    public final ToggleButton detailsButton = new ToggleButton("Подробности");

    public final ProgressBar progressBar = new ProgressBar();
    public final Label progressLabel = new Label(BASE_STATUS);
    public final Label statusLabel = new Label(BASE_STATUS);

    public final Slider maxMemorySlider = new Slider(512, 16384, 4096);
    public final Spinner<Integer> minMemory = new Spinner<>(0, 65536, 512, 256);
    public final TextField extraArgs = new TextField();
    public final ComboBox<String> javaChoice = new ComboBox<>();
    public final TextField authServer = new TextField();
    public final TextField microsoftClientId = new TextField();
    public final CheckBoxToggles toggles = new CheckBoxToggles();

    public final ComboBox<String> appearanceTheme = new ComboBox<>();
    public final ColorPicker appearanceAccent = new ColorPicker(Color.web("#FF7A59"));
    public final Slider interfaceFontSize = new Slider(12, 17, 13);
    public final Label interfaceFontSizeValue = new Label("13 px");
    public final Button chooseWallpaperButton = new Button("Выбрать изображение");
    public final Button clearWallpaperButton = new Button("Сбросить");
    public final Label wallpaperStatus = new Label("Обои не выбраны");

    public final ComboBox<String> loaderPicker = new ComboBox<>();
    public final ComboBox<String> profileBaseVersion = new ComboBox<>();

    /**
     * Builds offered for the chosen loader on the chosen Minecraft version, newest first.
     *
     * <p>A pack often names one exact build, so the newest cannot be the only choice.
     */
    public final ComboBox<String> buildPicker = new ComboBox<>();
    public final TextField profileNameField = new TextField();
    public final Button createProfileButton = new Button("Создать сборку");

    public final Label profileInfo = new Label();

    /**
     * Forge's generated version JSON asks that it not be downloaded without an explicit opt-in and
     * that its Patreon be linked, so both are shown before an install rather than after.
     */
    public final CheckBox forgeOptIn = new CheckBox("Разрешаю загрузку Forge");
    public final Hyperlink forgeSupportLink = new Hyperlink("Поддержать Forge на Patreon");
    public final Label loaderNote = new Label();
    public final Label forgeConsentError = new Label();

    /** Sidebar list of every version and build, sharing the model with {@link #versionBox}. */
    public final ListView<String> buildList = new ListView<>();
    public final Button addBuildButton = new Button("+ Новая сборка");

    public final Button accountButton = new Button();
    public final Region accountFace = new Region();
    public final ImageView accountSkin = new ImageView();
    public final Label accountBadge = new Label("relogin");
    public final Label accountName = new Label("Не вошёл");
    public final Label accountProvider = new Label();

    public final SplitMenuButton openModsButton = new SplitMenuButton();
    public final MenuItem folderRootItem = new MenuItem("Корень игры");
    public final MenuItem folderConfigItem = new MenuItem("Конфиги");
    public final MenuItem folderSavesItem = new MenuItem("Миры");
    public final MenuItem folderLogsItem = new MenuItem("Логи");
    public final MenuItem folderScreensItem = new MenuItem("Скриншоты");
    public final Button gameFolderButton = new Button("Папка");

    public final Button editBannerButton = new Button("Оформление");
    public final Button moreButton = new Button("⋯");
    public final ContextMenu moreMenu = new ContextMenu();
    public final MenuItem renameBuildItem = new MenuItem("Переименовать");
    public final MenuItem openBuildFolderItem = new MenuItem("Открыть папку сборки");
    public final MenuItem buildInfoItem = new MenuItem("Сведения о сборке");
    public final MenuItem deleteBuildItem = new MenuItem("Удалить сборку");

    public final Region bannerBg = new Region();
    public final Label bannerTitle = new Label();

    /** Banner appearance hooks; the controller fills them from the saved per-build choice. */
    public Function<String, BannerData> bannerData =
            id -> new BannerData(BannerData.MODE_SHOTS, 0, null, true);
    public Function<String, List<Path>> bannerScreenshots = id -> List.of();
    public Consumer<String> bannerModeChosen = mode -> { };
    public IntConsumer bannerPresetChosen = index -> { };
    public Consumer<Boolean> bannerAutoChosen = auto -> { };
    public Runnable bannerPickImage = () -> { };
    public Runnable bannerResetImage = () -> { };
    public final Label appearanceTitle = new Label("Оформление");
    public final StackPane appearanceOverlay;

    private final Region bannerBgNext = new Region();
    private final Button bannerPrev = new Button("‹");
    private final Button bannerNext = new Button("›");
    private final Label bannerCaption = new Label();
    private final HBox bannerDots = new HBox(5);
    private final HBox bannerCar = new HBox(8);
    private final ToggleButton bannerShotsButton = new ToggleButton("Скриншоты");
    private final ToggleButton bannerPresetButton = new ToggleButton("Заготовки");
    private final ToggleButton bannerCustomButton = new ToggleButton("Своё");
    private final CheckBox bannerAutoCheck = new CheckBox("Менять сами каждые 5 секунд");
    private final Button bannerPickButton = new Button("Выбрать изображение");
    private final Button bannerResetButton = new Button("Сбросить");
    private final Label bannerCustomHint = new Label();
    private VBox appearanceShotsPanel;
    private VBox appearancePresetPanel;
    private VBox appearanceCustomPanel;
    private FlowPane appearanceGrid;
    private String appearancePanelMode;
    private StackPane bannerPane;
    private Image bannerImage;
    private String bannerImageKey;
    private Image bannerPendingImage;
    private String bannerPendingKey;
    private int bannerSlide;
    private final PauseTransition bannerFade = new PauseTransition(Duration.millis(700));
    private final PauseTransition bannerTimer = new PauseTransition(Duration.seconds(5));

    public final VBox launchPane;
    public final HBox settingsPane;

    public final ToggleButton navLaunchButton = new ToggleButton("Запуск");
    public final ToggleButton navSettingsButton = new ToggleButton("Настройки");
    public final HBox navBar;
    public final Button windowMinimize = new Button("\u2013");
    public final Button windowMaximize = new Button("\u25A1");
    public final Button windowClose = new Button("\u2715");

    public final Label brand = new Label("MCLauncher");

    public final ToggleButton settingsAppearanceButton = new ToggleButton("Внешний вид");
    public final ToggleButton settingsGameButton = new ToggleButton("Игра");
    public final ToggleButton settingsAccountsButton = new ToggleButton("Аккаунты");
    public final ToggleButton settingsDiskButton = new ToggleButton("Диск");
    public final ToggleButton settingsWindowButton = new ToggleButton("Окно");
    public final VBox settingsAppearancePage;
    public final VBox settingsGamePage;
    public final VBox settingsAccountsPage;
    public final VBox settingsDiskPage;
    public final VBox settingsWindowPage;
    public final Button manageAccountsButton = new Button("Управление");

    public final Button deleteVersionButton = new Button("Удалить выбранную");
    public final Button deleteAllVersionsButton = new Button("Удалить все версии");
    public final Button cleanAssetsButton = new Button("Очистить ассеты");
    public final Label versionDiskLabel = new Label();
    public final ComboBox<String> closeAction = new ComboBox<>();

    public final Region flexZone = new Region();
    public final Label statsMemoryValue = new Label("4096 МБ");
    public final Label statsJavaValue = new Label("Авто");
    public final Label statsJavaLabel = new Label("Java");
    public final Label memoryValueLabel = new Label("4096 МБ");

    public final StackPane wizardOverlay;
    public final Button wizardCloseButton = new Button("\u2715");
    public final StackPane accountsOverlay;
    public final Button accountsCloseButton = new Button("\u2715");

    public final VBox consoleContent;
    public final Button copyCrashButton = new Button("Скопировать краш-лог");
    public final Button clearLogButton = new Button("Очистить");

    /** Called when the user edits a credential, so the controller can refresh the play button. */
    public Runnable fieldsEdited = () -> { };

    private Function<String, String> versionLabelProvider = Function.identity();
    private Function<String, String> buildSubtitleProvider = id -> "Ванильный";

    private final VBox sidebar;
    public final StackPane contentHost;

    private StackPane root;
    private javafx.stage.Stage consoleStage;
    private final ImageView wallpaperView = new ImageView();

    public LauncherView() {
        for (ComboBox<String> box : List.of(versionBox, providerPicker, loaderPicker,
                profileBaseVersion, buildPicker, javaChoice, appearanceTheme, closeAction)) {
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
        providerBox.getStyleClass().add("auth-block");
        providerBox.getChildren().addAll(sectionLabel("ВХОД"), providerPicker);

        logArea.setEditable(false);
        logArea.setVisible(false);
        logArea.setManaged(false);

        minMemory.setEditable(true);
        minMemory.setPrefWidth(140);

        for (TextField field : List.of(extraArgs, authServer, microsoftClientId)) {
            field.setMaxWidth(Double.MAX_VALUE);
        }
        javaChoice.getItems().add(javaAuto());
        javaChoice.setValue(javaAuto());

        maxMemorySlider.setPrefWidth(COLUMN_WIDTH);
        maxMemorySlider.setShowTickLabels(true);
        maxMemorySlider.setMajorTickUnit(2048);

        for (Button manage : List.of(deleteVersionButton, deleteAllVersionsButton,
                cleanAssetsButton)) {
            manage.getStyleClass().add("secondary");
            manage.setMaxWidth(Double.MAX_VALUE);
        }
        deleteAllVersionsButton.getStyleClass().add("danger-outline");
        cleanAssetsButton.getStyleClass().add("danger-outline");
        versionDiskLabel.getStyleClass().addAll("faint", "row-note");
        versionDiskLabel.setWrapText(true);

        closeAction.getItems().addAll("Закрывать лаунчер", "Скрывать в трей",
                "Сворачивать в трей");
        closeAction.setMaxWidth(220);

        appearanceTheme.getItems().addAll("Тёмная", "Светлая");
        appearanceTheme.getSelectionModel().selectFirst();
        appearanceTheme.setMaxWidth(170);
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
        serverAddress.setPromptText("Адрес друга, например, play.example.net:25565");
        serverAddress.setTooltip(new Tooltip(
            "Хост должен открыть мир для сети и сообщить адрес с портом. "
                + "Для подключения через интернет может потребоваться проброс порта."));
        connectButton.getStyleClass().add("secondary");
        stopButton.getStyleClass().add("danger");
        detailsButton.getStyleClass().add("secondary");
        gameFolderButton.setTooltip(new Tooltip("Открыть папку игры"));
        for (Button windowButton : List.of(windowMinimize, windowMaximize, windowClose)) {
            windowButton.getStyleClass().add("window-button");
        }
        windowClose.getStyleClass().add("window-button-close");

        stopButton.setVisible(false);
        stopButton.setManaged(false);

        detailsButton.selectedProperty().addListener((obs, was, isSelected) -> {
            logArea.setVisible(isSelected);
            logArea.setManaged(isSelected);
            if (isSelected) {
                openConsoleWindow();
            } else {
                closeConsoleWindow();
            }
        });

        loaderPicker.getItems().setAll(Arrays.stream(ModLoaderType.values())
            .map(ModLoaderType::displayName).toList());
        loaderPicker.getSelectionModel().select(ModLoaderType.VANILLA.displayName());
        buildPicker.setDisable(true);
        buildPicker.getStyleClass().add("dim");
        forgeSupportLink.setVisible(false);
        forgeSupportLink.setManaged(false);
        forgeOptIn.setVisible(false);
        forgeOptIn.setManaged(false);
        forgeOptIn.setMaxWidth(Double.MAX_VALUE);
        forgeConsentError.getStyleClass().add("field-error");
        forgeConsentError.setWrapText(true);
        forgeConsentError.setVisible(false);
        forgeConsentError.setManaged(false);
        loaderNote.setWrapText(true);
        profileInfo.setWrapText(true);
        profileNameField.setPromptText("Название сборки, например SkyFactory");
        profileNameField.setMaxWidth(Double.MAX_VALUE);
        profileBaseVersion.setMaxWidth(Double.MAX_VALUE);
        loaderPicker.setMaxWidth(Double.MAX_VALUE);
        buildPicker.setMaxWidth(Double.MAX_VALUE);
        createProfileButton.getStyleClass().add("primary");

        buildList.setPlaceholder(hint("Сборок пока нет"));
        buildList.getStyleClass().add("build-list");
        buildList.setMaxWidth(Double.MAX_VALUE);
        buildList.setCellFactory(list -> new ListCell<>() {
            private final Region cover = new Region();
            private final Label title = new Label();
            private final Label subtitle = new Label();
            private final HBox row = new HBox(10, cover, new VBox(title, subtitle));

            @Override
            protected void updateItem(String id, boolean empty) {
                super.updateItem(id, empty);
                if (empty || id == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                cover.getStyleClass().setAll("cover");
                cover.setStyle(coverGradient(id));
                cover.setMinSize(40, 40);
                cover.setPrefSize(40, 40);
                cover.setMaxSize(40, 40);
                title.setText(versionLabel(id));
                title.getStyleClass().setAll("cell-title");
                title.setTextOverrun(OverrunStyle.ELLIPSIS);
                subtitle.setText(buildSubtitleProvider.apply(id));
                subtitle.getStyleClass().setAll("cell-sub");
                row.setAlignment(Pos.CENTER_LEFT);
                setGraphic(row);
            }
        });
        buildList.setItems(versionBox.getItems());

        addBuildButton.getStyleClass().add("add-build");
        addBuildButton.setMaxWidth(Double.MAX_VALUE);

        accountButton.getStyleClass().add("account-button");
        accountButton.setMaxWidth(Double.MAX_VALUE);
        accountFace.getStyleClass().add("avatar");
        accountFace.setMinSize(28, 28);
        accountFace.setPrefSize(28, 28);
        accountFace.setMaxSize(28, 28);
        accountSkin.setFitWidth(28);
        accountSkin.setFitHeight(28);
        accountSkin.setPreserveRatio(false);
        accountSkin.setSmooth(false);
        accountSkin.setMouseTransparent(true);
        accountSkin.setVisible(false);
        accountSkin.setManaged(false);
        Rectangle faceClip = new Rectangle(28, 28);
        faceClip.setArcWidth(4);
        faceClip.setArcHeight(4);
        accountSkin.setClip(faceClip);
        accountBadge.getStyleClass().add("account-badge");
        accountBadge.setVisible(false);
        accountBadge.setManaged(false);
        accountName.getStyleClass().add("account-name");
        accountName.setTextOverrun(OverrunStyle.ELLIPSIS);
        accountProvider.getStyleClass().add("account-provider");
        Label accountChevron = new Label("\u21C5");
        accountChevron.getStyleClass().add("account-chevron");
        StackPane faceHost = new StackPane(accountFace, accountSkin, accountBadge);
        VBox accountTexts = new VBox(accountName, accountProvider);
        accountTexts.setMaxWidth(Double.MAX_VALUE);
        HBox accountRow = new HBox(10, faceHost, accountTexts, accountChevron);
        accountRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(accountTexts, Priority.ALWAYS);
        accountButton.setGraphic(accountRow);
        accountButton.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);

        openModsButton.setText("Папка модов");
        openModsButton.getStyleClass().add("secondary");
        openModsButton.getItems().addAll(folderRootItem, folderConfigItem, folderSavesItem,
                folderLogsItem, folderScreensItem);
        gameFolderButton.getStyleClass().add("secondary");
        editBannerButton.getStyleClass().add("banner-button");
        moreButton.getStyleClass().add("banner-button");
        moreMenu.getItems().addAll(renameBuildItem, openBuildFolderItem, buildInfoItem,
                deleteBuildItem);
        deleteBuildItem.getStyleClass().add("danger");
        renameBuildItem.setDisable(true);
        editBannerButton.setOnAction(e -> openAppearance());
        moreButton.setOnAction(e -> moreMenu.show(moreButton, Side.BOTTOM, 0, 0));

        bannerPrev.getStyleClass().add("car-button");
        bannerNext.getStyleClass().add("car-button");
        bannerPrev.setOnAction(e -> stepBanner(-1));
        bannerNext.setOnAction(e -> stepBanner(1));
        bannerCaption.getStyleClass().add("car-caption");
        bannerDots.getStyleClass().add("car-dots");
        bannerCar.getStyleClass().add("car");
        bannerCar.setAlignment(Pos.CENTER_RIGHT);
        bannerCar.setPickOnBounds(false);
        bannerCar.getChildren().addAll(bannerPrev, bannerCaption, bannerDots, bannerNext);
        bannerCar.setVisible(false);
        bannerCar.setManaged(false);
        bannerFade.setOnFinished(e ->
                commitBanner(bannerPendingImage, bannerPendingKey, bannerBgNext.getBackground()));
        bannerTimer.setOnFinished(e -> {
            bannerSlide++;
            refreshBanner();
        });

        bannerTitle.getStyleClass().add("banner-title");
        profileInfo.getStyleClass().add("banner-meta");

        navLaunchButton.getStyleClass().add("nav-button");
        navSettingsButton.getStyleClass().add("nav-button");
        navBar = buildNavBar();

        settingsAppearanceButton.getStyleClass().add("settings-nav-button");
        settingsGameButton.getStyleClass().add("settings-nav-button");
        settingsAccountsButton.getStyleClass().add("settings-nav-button");
        settingsDiskButton.getStyleClass().add("settings-nav-button");
        settingsWindowButton.getStyleClass().add("settings-nav-button");
        manageAccountsButton.getStyleClass().add("secondary");
        settingsAppearancePage = buildAppearancePage();
        settingsGamePage = buildGamePage();
        settingsAccountsPage = buildAccountsPage();
        settingsDiskPage = buildDiskPage();
        settingsWindowPage = buildWindowPage();

        launchPane = buildLaunchPane();
        settingsPane = buildSettingsPane();
        contentHost = buildContentHost();

        sidebar = buildSidebar();
        wizardOverlay = buildWizardOverlay();
        accountsOverlay = buildAccountsOverlay();
        appearanceOverlay = buildAppearanceOverlay();
        consoleContent = buildConsoleContent();

        wireSelectionSync();
        wireNavigation();

        maxMemorySlider.valueProperty().addListener((obs, old, value) -> {
            String text = (int) Math.round(value.doubleValue()) + " МБ";
            statsMemoryValue.setText(text);
            memoryValueLabel.setText(text);
        });

        versionBox.getSelectionModel().selectedItemProperty()
                .addListener((obs, old, id) -> {
                    if (id == null) {
                        buildList.getSelectionModel().clearSelection();
                    } else if (!id.equals(buildList.getSelectionModel().getSelectedItem())) {
                        buildList.getSelectionModel().select(id);
                    }
                    bannerTitle.setText(versionLabel(id));
                    bannerSlide = 0;
                    refreshBanner();
                });
    }

    public StackPane build() {
        root = new StackPane();
        root.getStyleClass().add("root");
        wallpaperView.setPreserveRatio(false);
        wallpaperView.setOpacity(0.28);
        wallpaperView.setMouseTransparent(true);
        wallpaperView.setManaged(false);
        wallpaperView.fitWidthProperty().bind(root.widthProperty());
        wallpaperView.fitHeightProperty().bind(root.heightProperty());
        root.widthProperty().addListener((obs, old, value) -> updateWallpaperViewport());
        root.heightProperty().addListener((obs, old, value) -> updateWallpaperViewport());

        VBox mainColumn = new VBox(navBar, contentHost);
        VBox.setVgrow(contentHost, Priority.ALWAYS);
        HBox shell = new HBox(sidebar, mainColumn);
        HBox.setHgrow(mainColumn, Priority.ALWAYS);
        shell.setMaxWidth(Double.MAX_VALUE);
        shell.setMaxHeight(Double.MAX_VALUE);
        root.getChildren().addAll(wallpaperView, shell, wizardOverlay, accountsOverlay,
                appearanceOverlay);
        applyAppearance("Светлая".equals(appearanceTheme.getValue()) ? "light" : "dark",
            accentHex(), (int) Math.round(interfaceFontSize.getValue()));
        return root;
    }

    /** The window root, for reaching the scene before a widget hosts one itself. */
    public Node rootNode() {
        return root;
    }

    public void setVersionLabelProvider(Function<String, String> provider) {
        versionLabelProvider = provider == null ? Function.identity() : provider;
        buildList.refresh();
        bannerTitle.setText(versionLabel(versionBox.getValue()));
    }

    public String versionLabel(String id) {
        return id == null ? "" : versionLabelProvider.apply(id);
    }

    public void setBuildSubtitleProvider(Function<String, String> provider) {
        buildSubtitleProvider = provider == null ? id -> "Ванильный" : provider;
        buildList.refresh();
    }

    public void setWallpaper(Path path) throws IOException {
        if (root == null) {
            return;
        }
        if (path == null) {
            wallpaperView.setImage(null);
            wallpaperView.setViewport(null);
            wallpaperView.setVisible(false);
            wallpaperStatus.setText("Обои не выбраны");
            return;
        }
        Image image = new Image(path.toUri().toString(), false);
        if (image.isError() || image.getWidth() <= 0 || image.getHeight() <= 0) {
            throw new IOException("Не удалось открыть изображение: " + path.getFileName(),
                    image.getException());
        }
        wallpaperView.setImage(image);
        wallpaperView.setVisible(true);
        wallpaperStatus.setText(path.getFileName().toString());
        updateWallpaperViewport();
    }

    private void updateWallpaperViewport() {
        Image image = wallpaperView.getImage();
        double width = root == null ? 0 : root.getWidth();
        double height = root == null ? 0 : root.getHeight();
        if (image == null || width <= 0 || height <= 0) {
            return;
        }
        double imageRatio = image.getWidth() / image.getHeight();
        double viewRatio = width / height;
        if (imageRatio > viewRatio) {
            double cropWidth = image.getHeight() * viewRatio;
            wallpaperView.setViewport(new Rectangle2D(
                    (image.getWidth() - cropWidth) / 2, 0, cropWidth, image.getHeight()));
        } else {
            double cropHeight = image.getWidth() / viewRatio;
            wallpaperView.setViewport(new Rectangle2D(
                    0, (image.getHeight() - cropHeight) / 2, image.getWidth(), cropHeight));
        }
    }

    public void applyAppearance(String theme, String accentHex, int fontSize) {
        if (root == null) {
            return;
        }
        Color accent = parseAccent(accentHex);
        boolean light = "light".equals(theme);
        Color hover = accent.interpolate(light ? Color.BLACK : Color.WHITE, 0.16);
        Color soft = accent.interpolate(light ? Color.WHITE : Color.web("#14152A"), 0.86);
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
            return Color.web(value == null ? "#FF7A59" : value);
        } catch (IllegalArgumentException e) {
            return Color.web("#FF7A59");
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

    public void setJavaChoices(List<String> choices) {
        String current = javaPathValue();
        javaChoice.getItems().setAll(javaAuto());
        for (String choice : choices) {
            if (choice != null && !choice.isBlank() && !javaChoice.getItems().contains(choice)) {
                javaChoice.getItems().add(choice);
            }
        }
        setJavaPathValue(current);
    }

    public void setJavaPathValue(String path) {
        if (path == null || path.isBlank()) {
            javaChoice.setValue(javaAuto());
            return;
        }
        if (!javaChoice.getItems().contains(path)) {
            javaChoice.getItems().add(path);
        }
        javaChoice.setValue(path);
    }

    public String javaPathValue() {
        String value = javaChoice.getValue();
        return value == null || javaAuto().equals(value) ? "" : value;
    }

    private static String javaAuto() {
        return "Автоматически";
    }

    public void setMaxMemoryCeiling(int maxMb) {
        maxMemorySlider.setMax(Math.max(1024, maxMb));
    }

    public void setAccount(String name, String provider, boolean needsRelogin) {
        accountName.setText(name == null || name.isBlank() ? "Не вошёл" : name);
        accountProvider.setText(provider == null ? "" : provider);
        accountBadge.setVisible(needsRelogin);
        accountBadge.setManaged(needsRelogin);
    }

    public void setAccountSkin(Image skin) {
        if (skin == null) {
            accountSkin.setImage(null);
            accountSkin.setViewport(null);
            accountSkin.setVisible(false);
            accountSkin.setManaged(false);
            return;
        }
        accountSkin.setImage(skin);
        accountSkin.setViewport(new Rectangle2D(8, 8, 8, 8));
        accountSkin.setVisible(true);
        accountSkin.setManaged(true);
        skin.errorProperty().addListener((obs, old, failed) -> {
            if (failed && accountSkin.getImage() == skin) {
                setAccountSkin(null);
            }
        });
    }

    public void setStatsJava(String value, String note) {
        statsJavaValue.setText(value);
        statsJavaLabel.setText(note);
    }

    public void showLaunch() {
        hideAppearance();
        navLaunchButton.setSelected(true);
    }

    public void showSettings() {
        hideAppearance();
        navSettingsButton.setSelected(true);
    }

    public void openWizard() {
        wizardOverlay.setVisible(true);
    }

    public void closeWizard() {
        wizardOverlay.setVisible(false);
    }

    public void openAccounts() {
        accountsOverlay.setVisible(true);
    }

    public void closeAccounts() {
        accountsOverlay.setVisible(false);
    }

    /** Closes every popup and dialog that Escape is allowed to dismiss. */
    public void closeTransient() {
        moreMenu.hide();
        hideAppearance();
        closeWizard();
        closeAccounts();
    }

    /** Repaints the banner for the selected build: screenshots, a preset scene or a custom image. */
    public void refreshBanner() {
        String id = versionBox.getValue();
        BannerData data = bannerData.apply(id == null ? "" : id);
        if (data == null) {
            data = new BannerData(BannerData.MODE_SHOTS, 0, null, true);
        }
        List<Path> shots = BannerData.MODE_SHOTS.equals(data.mode())
                ? bannerScreenshots.apply(id == null ? "" : id) : List.of();
        int seed = id == null ? 0 : id.hashCode();
        Image image = null;
        String key = null;
        if (BannerData.MODE_PRESET.equals(data.mode())) {
            image = BannerArt.image(data.preset(), seed);
            key = "preset|" + data.preset() + "|" + seed;
        } else if (BannerData.MODE_CUSTOM.equals(data.mode())
                && data.image() != null && Files.isRegularFile(data.image())) {
            image = loadCoverImage(data.image());
            key = "custom|" + data.image();
        } else if (!shots.isEmpty()) {
            Path shot = shots.get(Math.floorMod(bannerSlide, shots.size()));
            image = loadCoverImage(shot);
            key = "shots|" + shot;
        }
        if (image == null) {
            // The mockup's answer to "no screenshots yet" (and to a missing file): plains.
            image = BannerArt.image(0, seed);
            key = "fallback|" + seed;
        }
        showBannerImage(image, key);
        updateBannerCarousel(shots, data);
        updateBannerTimer(shots, data);
    }

    /** Shows {@code image} under the banner text, crossfading from the previous cover. */
    private void showBannerImage(Image image, String key) {
        bannerBg.setStyle("");
        double width = bannerPane == null ? 0 : bannerPane.getWidth();
        double height = bannerPane == null ? 0 : bannerPane.getHeight();
        if (key.equals(bannerImageKey)) {
            bannerBg.setBackground(coverBackground(image, width, height));
            return;
        }
        if (key.equals(bannerPendingKey)) {
            return;
        }
        bannerFade.stop();
        if (width <= 1 || height <= 1) {
            // Before the first layout pass there is nothing to cover yet; the size listener
            // re-crops the art once the banner has real bounds.
            commitBanner(image, key, coverBackground(image, width, height));
            return;
        }
        bannerPendingImage = image;
        bannerPendingKey = key;
        bannerBgNext.setBackground(coverBackground(image, width, height));
        bannerBgNext.setOpacity(0);
        bannerFade.playFromStart();
    }

    private void commitBanner(Image image, String key, Background background) {
        bannerImage = image;
        bannerImageKey = key;
        bannerBg.setBackground(background);
        bannerBgNext.setOpacity(0);
        bannerBgNext.setBackground(null);
        bannerPendingImage = null;
        bannerPendingKey = null;
    }

    private void applyBannerSizes() {
        if (bannerPane == null) {
            return;
        }
        double width = bannerPane.getWidth();
        double height = bannerPane.getHeight();
        if (bannerImage != null) {
            bannerBg.setBackground(coverBackground(bannerImage, width, height));
        }
        if (bannerPendingImage != null) {
            bannerBgNext.setBackground(coverBackground(bannerPendingImage, width, height));
        }
    }

    private static Background coverBackground(Image image, double width, double height) {
        if (image == null || image.isError()) {
            return null;
        }
        BackgroundSize size = width > 1 && height > 1
                ? coverSize(image, width, height)
                : new BackgroundSize(Math.max(1, image.getWidth()),
                    Math.max(1, image.getHeight()), false, false, false, false);
        return new Background(new BackgroundImage(image, BackgroundRepeat.NO_REPEAT,
                BackgroundRepeat.NO_REPEAT, BackgroundPosition.CENTER, size));
    }

    /** Scales the image up until it fills the box, cropping the overflow: CSS cover. */
    private static BackgroundSize coverSize(Image image, double width, double height) {
        double imageWidth = Math.max(1, image.getWidth());
        double imageHeight = Math.max(1, image.getHeight());
        double scale = Math.max(width / imageWidth, height / imageHeight);
        return new BackgroundSize(imageWidth * scale, imageHeight * scale,
            false, false, false, false);
    }

    private static Image loadCoverImage(Path file) {
        try {
            Image image = new Image(file.toUri().toString(), 1600, 900, true, true, false);
            return image.isError() ? null : image;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void stepBanner(int delta) {
        bannerSlide += delta;
        refreshBanner();
    }

    private void updateBannerCarousel(List<Path> shots, BannerData data) {
        boolean show = BannerData.MODE_SHOTS.equals(data.mode()) && !shots.isEmpty();
        bannerCar.setVisible(show);
        bannerCar.setManaged(show);
        if (!show) {
            return;
        }
        int index = Math.floorMod(bannerSlide, shots.size());
        bannerCaption.setText(shots.get(index).getFileName().toString());
        bannerDots.getChildren().clear();
        if (shots.size() <= 12) {
            for (int i = 0; i < shots.size(); i++) {
                Label dot = new Label();
                dot.getStyleClass().add("dot");
                if (i == index) {
                    dot.getStyleClass().add("on");
                }
                int target = i;
                dot.setOnMouseClicked(e -> {
                    bannerSlide = target;
                    refreshBanner();
                });
                bannerDots.getChildren().add(dot);
            }
        }
    }

    private void updateBannerTimer(List<Path> shots, BannerData data) {
        boolean rotate = BannerData.MODE_SHOTS.equals(data.mode()) && data.auto()
                && shots.size() > 1 && !isAppearanceOpen();
        bannerTimer.stop();
        if (rotate) {
            bannerTimer.playFromStart();
        }
    }

    /** Toggles the banner appearance pop the mockup anchors under the cover button. */
    public void openAppearance() {
        if (isAppearanceOpen()) {
            hideAppearance();
            return;
        }
        appearancePanelMode = null;
        syncAppearanceControls();
        appearanceOverlay.setVisible(true);
        bannerTimer.stop();
    }

    public void hideAppearance() {
        if (isAppearanceOpen()) {
            appearanceOverlay.setVisible(false);
            appearancePanelMode = null;
            refreshBanner();
        }
    }

    public boolean isAppearanceOpen() {
        return appearanceOverlay.isVisible();
    }

    /** True once the cover has art, even while the crossfade is still running. */
    public boolean bannerPainted() {
        return bannerBg.getBackground() != null || bannerBgNext.getBackground() != null;
    }

    /** Brings the pop in line with the saved choice for the selected build. */
    public void syncAppearanceControls() {
        String id = versionBox.getValue();
        BannerData data = bannerData.apply(id == null ? "" : id);
        if (data == null || appearanceGrid == null) {
            return;
        }
        String mode = data.mode() == null ? BannerData.MODE_SHOTS : data.mode();
        String shown = appearancePanelMode == null ? mode : appearancePanelMode;
        boolean shots = BannerData.MODE_SHOTS.equals(shown);
        boolean preset = BannerData.MODE_PRESET.equals(shown);
        boolean custom = BannerData.MODE_CUSTOM.equals(shown);
        bannerShotsButton.setSelected(shots);
        bannerPresetButton.setSelected(preset);
        bannerCustomButton.setSelected(custom);
        bannerAutoCheck.setSelected(data.auto());
        bannerAutoCheck.setDisable(!BannerData.MODE_SHOTS.equals(mode));
        appearanceTitle.setText("Оформление: " + versionLabel(id));
        appearanceShotsPanel.setVisible(shots);
        appearanceShotsPanel.setManaged(shots);
        appearancePresetPanel.setVisible(preset);
        appearancePresetPanel.setManaged(preset);
        appearanceCustomPanel.setVisible(custom);
        appearanceCustomPanel.setManaged(custom);
        int chosen = Math.floorMod(data.preset(), BannerArt.NAMES.length);
        for (int i = 0; i < appearanceGrid.getChildren().size(); i++) {
            boolean on = preset && i == chosen;
            List<String> classes = appearanceGrid.getChildren().get(i).getStyleClass();
            if (on && !classes.contains("on")) {
                classes.add("on");
            } else if (!on) {
                classes.remove("on");
            }
        }
        bannerCustomHint.setText(data.image() == null
                ? "Изображение не выбрано. Лучше от 1600×500."
                : "Своё изображение выбрано.");
    }

    /**
     * Saves a segment click. "Своё" only switches the stored mode once an image exists, as in the
     * mockup: until then the panel opens but the cover keeps cycling screenshots.
     */
    private void chooseBannerMode(String mode) {
        bannerSlide = 0;
        String id = versionBox.getValue();
        BannerData data = bannerData.apply(id == null ? "" : id);
        boolean hasImage = data != null && data.image() != null;
        if (BannerData.MODE_CUSTOM.equals(mode) && !hasImage) {
            appearancePanelMode = BannerData.MODE_CUSTOM;
            syncAppearanceControls();
            return;
        }
        appearancePanelMode = null;
        bannerModeChosen.accept(mode);
        syncAppearanceControls();
    }

    /** Brings the console window up, if this run is allowed to open one. */
    public void showConsole() {
        detailsButton.setSelected(true);
        if (detailsButton.isSelected()) {
            openConsoleWindow();
        }
    }

    /**
     * Flash the Forge consent box instead of blocking creation with a modal: the checkbox in the
     * wizard is where the user has to act, so the message belongs next to it.
     */
    public void flashForgeConsent() {
        if (!forgeOptIn.getStyleClass().contains("invalid")) {
            forgeOptIn.getStyleClass().add("invalid");
        }
        forgeConsentError.setText("Требуется подтверждение лицензии загрузчика");
        forgeConsentError.setVisible(true);
        forgeConsentError.setManaged(true);
        PauseTransition reset = new PauseTransition(Duration.seconds(4));
        reset.setOnFinished(e -> {
            forgeOptIn.getStyleClass().remove("invalid");
            forgeConsentError.setVisible(false);
            forgeConsentError.setManaged(false);
            forgeConsentError.setText("");
        });
        reset.play();
    }

    private void openConsoleWindow() {
        if (Boolean.getBoolean("mclauncher.uicheck")) {
            return;
        }
        if (consoleStage == null) {
            consoleStage = new javafx.stage.Stage();
            consoleStage.setTitle("Консоль MCLauncher");
            javafx.scene.Scene consoleScene =
                    new javafx.scene.Scene(consoleContent, 820, 540);
            java.net.URL css = LauncherView.class.getResource("/launcher.css");
            if (css != null) {
                consoleScene.getStylesheets().add(css.toExternalForm());
            }
            consoleStage.setScene(consoleScene);
            consoleStage.setOnCloseRequest(e -> detailsButton.setSelected(false));
        }
        consoleStage.show();
        consoleStage.toFront();
    }

    private void closeConsoleWindow() {
        if (consoleStage != null) {
            consoleStage.hide();
        }
    }

    private void wireSelectionSync() {
        buildList.getSelectionModel().selectedItemProperty()
                .addListener((obs, old, id) -> {
                    if (id != null && !id.equals(versionBox.getValue())) {
                        versionBox.getSelectionModel().select(id);
                    }
                });
    }

    private void wireNavigation() {
        ToggleGroup navGroup = new ToggleGroup();
        navLaunchButton.setToggleGroup(navGroup);
        navSettingsButton.setToggleGroup(navGroup);
        navGroup.selectedToggleProperty().addListener((obs, old, toggle) -> {
            // The mockup closes the appearance pop whenever the nav switches tabs.
            hideAppearance();
            boolean settings = toggle == navSettingsButton;
            launchPane.setVisible(!settings);
            launchPane.setManaged(!settings);
            settingsPane.setVisible(settings);
            settingsPane.setManaged(settings);
        });

        ToggleGroup settingsGroup = new ToggleGroup();
        List<ToggleButton> navButtons = List.of(settingsAppearanceButton, settingsGameButton,
                settingsAccountsButton, settingsDiskButton, settingsWindowButton);
        List<VBox> pages = List.of(settingsAppearancePage, settingsGamePage,
                settingsAccountsPage, settingsDiskPage, settingsWindowPage);
        for (ToggleButton button : navButtons) {
            button.setToggleGroup(settingsGroup);
        }
        settingsGroup.selectedToggleProperty().addListener((obs, old, toggle) -> {
            for (int i = 0; i < navButtons.size(); i++) {
                boolean on = toggle == navButtons.get(i);
                pages.get(i).setVisible(on);
                pages.get(i).setManaged(on);
            }
        });

        navLaunchButton.setSelected(true);
        settingsAppearanceButton.setSelected(true);
    }

    private HBox buildNavBar() {
        statusLabel.getStyleClass().add("status");
        HBox navButtons = new HBox(4, navLaunchButton, navSettingsButton);
        navButtons.getStyleClass().add("nav");
        HBox windowButtons = new HBox(0, windowMinimize, windowMaximize, windowClose);
        windowButtons.getStyleClass().add("window-controls");
        HBox bar = new HBox(0, statusLabel, spacer(), navButtons, spacer(), windowButtons);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(8, 8, 8, 22));
        bar.setPrefHeight(44);
        bar.setMaxHeight(44);
        bar.getStyleClass().add("nav-bar");
        return bar;
    }

    private VBox buildSidebar() {
        brand.getStyleClass().add("brand");
        VBox listBox = new VBox(buildList);
        VBox.setVgrow(buildList, Priority.ALWAYS);
        listBox.setFillWidth(true);
        listBox.getStyleClass().add("build-list-box");

        HBox brandBar = new HBox(12, brand);
        brandBar.setPadding(new Insets(14, 16, 10, 16));

        VBox side = new VBox(0, brandBar, listBox, addBuildButton, accountButton);
        VBox.setVgrow(listBox, Priority.ALWAYS);
        side.setPrefWidth(SIDEBAR_WIDTH);
        side.setMinWidth(SIDEBAR_WIDTH);
        side.setMaxWidth(SIDEBAR_WIDTH);
        side.getStyleClass().add("sidebar");
        return side;
    }

    private StackPane buildContentHost() {
        launchPane.setMaxWidth(Double.MAX_VALUE);
        launchPane.setMaxHeight(Double.MAX_VALUE);
        settingsPane.setMaxWidth(Double.MAX_VALUE);
        settingsPane.setMaxHeight(Double.MAX_VALUE);
        StackPane host = new StackPane(launchPane, settingsPane);
        StackPane.setAlignment(launchPane, Pos.TOP_LEFT);
        StackPane.setAlignment(settingsPane, Pos.TOP_LEFT);
        settingsPane.setVisible(false);
        settingsPane.setManaged(false);
        return host;
    }

    private VBox buildLaunchPane() {
        VBox pane = new VBox(0);
        pane.setPadding(new Insets(10, 22, 0, 22));
        pane.getStyleClass().add("launch-pane");

        StackPane banner = new StackPane();
        bannerPane = banner;
        banner.setPrefHeight(290);
        banner.setMaxHeight(320);
        banner.setMaxWidth(Double.MAX_VALUE);
        banner.getStyleClass().add("banner");
        bannerBg.getStyleClass().add("banner-bg");
        bannerBgNext.getStyleClass().add("banner-bg");
        Region shade = new Region();
        shade.getStyleClass().add("banner-shade");
        VBox bannerText = new VBox(4, bannerTitle, profileInfo);
        bannerText.getStyleClass().add("banner-text");
        StackPane.setAlignment(shade, Pos.CENTER);
        StackPane.setAlignment(bannerText, Pos.BOTTOM_LEFT);
        StackPane.setMargin(bannerText, new Insets(22));
        StackPane.setAlignment(editBannerButton, Pos.TOP_RIGHT);
        StackPane.setMargin(editBannerButton, new Insets(12, 56, 0, 0));
        StackPane.setAlignment(moreButton, Pos.TOP_RIGHT);
        StackPane.setMargin(moreButton, new Insets(12, 12, 0, 0));
        StackPane.setAlignment(bannerCar, Pos.BOTTOM_RIGHT);
        StackPane.setMargin(bannerCar, new Insets(0, 14, 14, 0));
        banner.getChildren().addAll(bannerBg, bannerBgNext, shade, bannerText, bannerCar,
                editBannerButton, moreButton);
        banner.widthProperty().addListener((obs, old, value) -> applyBannerSizes());
        banner.heightProperty().addListener((obs, old, value) -> applyBannerSizes());

        HBox actions = new HBox(10, playButton, stopButton, openModsButton, gameFolderButton,
                detailsButton);
        actions.setAlignment(Pos.CENTER_LEFT);
        actions.setPadding(new Insets(18, 0, 0, 0));
        actions.setMaxWidth(Double.MAX_VALUE);
        actions.getStyleClass().add("act-row");

        HBox connectRow = new HBox(8, serverAddress, connectButton);
        connectRow.setAlignment(Pos.CENTER_LEFT);
        connectRow.setPadding(new Insets(12, 0, 0, 0));
        connectRow.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(serverAddress, Priority.ALWAYS);
        connectRow.getStyleClass().add("connect-row");

        VBox progressBlock = new VBox(6, progressBar, progressLabel);
        progressBlock.setPadding(new Insets(14, 0, 0, 0));
        progressBlock.setMaxWidth(Double.MAX_VALUE);
        progressBlock.getStyleClass().add("progress-block");
        progressBar.setMaxWidth(Double.MAX_VALUE);
        progressBar.setVisible(false);
        progressBar.setManaged(false);
        progressLabel.getStyleClass().add("status");
        progressLabel.setVisible(false);
        progressLabel.setManaged(false);

        HBox stats = new HBox(30, stat(statsMemoryValue, "память"),
            stat(statsJavaValue, statsJavaLabel));
        stats.setAlignment(Pos.CENTER_LEFT);
        stats.setPadding(new Insets(20, 0, 20, 0));
        stats.setMaxWidth(Double.MAX_VALUE);
        stats.getStyleClass().add("stats");

        flexZone.setMaxWidth(Double.MAX_VALUE);
        VBox.setVgrow(flexZone, Priority.ALWAYS);

        pane.getChildren().addAll(banner, actions, connectRow, progressBlock, flexZone, stats);
        return pane;
    }

    private static VBox stat(Label value, String label) {
        Label caption = new Label(label);
        return statBox(value, caption);
    }

    private static VBox stat(Label value, Label caption) {
        return statBox(value, caption);
    }

    private static VBox statBox(Label value, Label caption) {
        value.getStyleClass().add("stat-value");
        caption.getStyleClass().add("stat-label");
        VBox box = new VBox(2, value, caption);
        box.getStyleClass().add("stat");
        return box;
    }

    private HBox buildSettingsPane() {
        VBox nav = new VBox(4, settingsAppearanceButton, settingsGameButton,
                settingsAccountsButton, settingsDiskButton, settingsWindowButton);
        nav.setPadding(new Insets(14, 8, 14, 8));
        nav.setPrefWidth(170);
        nav.setMinWidth(170);
        nav.setMaxWidth(170);
        nav.getStyleClass().add("settings-nav");

        StackPane host = new StackPane(settingsAppearancePage, settingsGamePage,
                settingsAccountsPage, settingsDiskPage, settingsWindowPage);
        for (VBox page : List.of(settingsAppearancePage, settingsGamePage, settingsAccountsPage,
                settingsDiskPage, settingsWindowPage)) {
            StackPane.setAlignment(page, Pos.TOP_LEFT);
            page.setMaxWidth(Double.MAX_VALUE);
        }

        ScrollPane scroll = new ScrollPane(host);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().add("form-scroll");
        HBox.setHgrow(scroll, Priority.ALWAYS);

        HBox pane = new HBox(0, nav, scroll);
        pane.setMaxWidth(Double.MAX_VALUE);
        pane.getStyleClass().add("settings-pane");
        return pane;
    }

    private VBox buildAppearancePage() {
        VBox page = page();
        page.getChildren().addAll(
                settingRow("Тема", (String) null, appearanceTheme),
                settingRow("Акцентный цвет", "Кнопки и выделение", appearanceAccent),
                settingRow("Размер текста", (String) null,
                        new HBox(10, interfaceFontSize, interfaceFontSizeValue)),
                settingColumn("Обои основного экрана",
                        "Подложка за списком и баннером. Баннеры сборок не меняются.",
                        new HBox(8, chooseWallpaperButton, clearWallpaperButton),
                        wallpaperStatus));
        return page;
    }

    private VBox buildGamePage() {
        HBox memoryControl = new HBox(10, maxMemorySlider, memoryValueLabel);
        memoryControl.setAlignment(Pos.CENTER_LEFT);
        toggles.isolateGameDir.getStyleClass().add("check-row");
        toggles.isolateGameDir.setMaxWidth(Double.MAX_VALUE);
        VBox page = page();
        page.getChildren().addAll(
                settingColumn("Память для игры",
                        "Больше помогает, только если хватает её самой системе.", memoryControl),
                settingRow("Минимум памяти", "МБ, 0 без ограничения", minMemory),
                settingColumn("Дополнительные параметры Java", "Например: -XX:+UseG1GC", extraArgs),
                settingColumn("Путь к Java", "Пусто: искать самостоятельно", javaChoice),
                toggles.isolateGameDir);
        return page;
    }

    private VBox buildAccountsPage() {
        toggles.authlibInjector.getStyleClass().add("check-row");
        toggles.authlibInjector.setMaxWidth(Double.MAX_VALUE);
        VBox page = page();
        page.getChildren().addAll(
                settingRow("Аккаунты", "Добавить, выбрать или убрать", manageAccountsButton),
                settingColumn("Ely / Yggdrasil API", (String) null, authServer),
                settingColumn("Azure Client ID для Microsoft", (String) null, microsoftClientId),
                toggles.authlibInjector);
        return page;
    }

    private VBox buildDiskPage() {
        HBox versionControls = new HBox(8, deleteVersionButton, deleteAllVersionsButton);
        VBox page = page();
        page.getChildren().addAll(
                settingRow("Версии игры", versionDiskLabel, versionControls),
                settingRow("Ассеты",
                        "Общие для всех версий. После очистки их придётся качать заново.",
                        cleanAssetsButton));
        return page;
    }

    private VBox buildWindowPage() {
        VBox page = page();
        page.getChildren().add(
                settingRow("При закрытии окна",
                        "«Скрывать» оставляет лаунчер в трее, чтобы он не занимал место.",
                        closeAction));
        return page;
    }

    private static VBox page() {
        VBox page = new VBox(0);
        page.setPadding(new Insets(0, 26, 20, 26));
        page.getStyleClass().add("settings-page");
        return page;
    }

    private static HBox settingRow(String title, String note, Node control) {
        Label noteLabel = null;
        if (note != null) {
            noteLabel = new Label(note);
            noteLabel.getStyleClass().add("row-note");
            noteLabel.setWrapText(true);
        }
        return settingRow(title, noteLabel, control);
    }

    private static HBox settingRow(String title, Node note, Node control) {
        Label heading = new Label(title);
        heading.getStyleClass().add("row-title");
        VBox text = new VBox(2, heading);
        if (note != null) {
            text.getChildren().add(note);
        }
        text.setFillWidth(true);
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        HBox row = new HBox(16, text, gap, control);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setMaxWidth(Double.MAX_VALUE);
        row.getStyleClass().add("setting-row");
        return row;
    }

    private static VBox settingColumn(String title, String note, Node... children) {
        Label heading = new Label(title);
        heading.getStyleClass().add("row-title");
        VBox column = new VBox(8, heading);
        if (note != null) {
            Label noteLabel = new Label(note);
            noteLabel.getStyleClass().add("row-note");
            noteLabel.setWrapText(true);
            column.getChildren().add(noteLabel);
        }
        column.getChildren().addAll(children);
        column.setFillWidth(true);
        column.setMaxWidth(Double.MAX_VALUE);
        column.getStyleClass().add("setting-row");
        return column;
    }

    /**
     * The banner appearance pop: a card anchored top-right under the cover button, closed by a
     * click anywhere outside it, by its ✕, by Escape and by a nav switch.
     */
    private StackPane buildAppearanceOverlay() {
        StackPane overlay = new StackPane();
        overlay.getStyleClass().add("pop-overlay");
        overlay.setVisible(false);

        Region catcher = new Region();
        catcher.setMaxWidth(Double.MAX_VALUE);
        catcher.setMaxHeight(Double.MAX_VALUE);
        catcher.setOnMouseClicked(e -> {
            if (e.getTarget() == catcher || e.getTarget() == overlay) {
                hideAppearance();
            }
        });

        appearanceTitle.getStyleClass().add("pop-title");
        Button close = new Button("\u2715");
        close.getStyleClass().add("window-button");
        close.setOnAction(e -> hideAppearance());
        HBox head = new HBox(appearanceTitle, spacer(), close);
        head.setAlignment(Pos.CENTER_LEFT);
        head.getStyleClass().add("pop-head");

        ToggleGroup modeGroup = new ToggleGroup();
        bannerShotsButton.setToggleGroup(modeGroup);
        bannerPresetButton.setToggleGroup(modeGroup);
        bannerCustomButton.setToggleGroup(modeGroup);
        for (ToggleButton button : List.of(bannerShotsButton, bannerPresetButton,
                bannerCustomButton)) {
            button.getStyleClass().add("seg-button");
            button.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(button, Priority.ALWAYS);
        }
        bannerShotsButton.setOnAction(e -> chooseBannerMode(BannerData.MODE_SHOTS));
        bannerPresetButton.setOnAction(e -> chooseBannerMode(BannerData.MODE_PRESET));
        bannerCustomButton.setOnAction(e -> chooseBannerMode(BannerData.MODE_CUSTOM));
        HBox seg = new HBox(bannerShotsButton, bannerPresetButton, bannerCustomButton);
        seg.getStyleClass().add("seg");

        Label shotsHint = new Label("Листаем скриншоты из папки сборки. Если их нет, "
                + "показываем заготовку «Равнины».");
        shotsHint.setWrapText(true);
        shotsHint.getStyleClass().add("hint-text");
        bannerAutoCheck.getStyleClass().add("check-row");
        bannerAutoCheck.setOnAction(e -> {
            appearancePanelMode = null;
            bannerAutoChosen.accept(bannerAutoCheck.isSelected());
            syncAppearanceControls();
        });
        appearanceShotsPanel = new VBox(8, shotsHint, bannerAutoCheck);
        appearanceShotsPanel.getStyleClass().add("pop-panel");

        appearanceGrid = new FlowPane(8, 8);
        appearanceGrid.getStyleClass().add("preset-grid");
        for (int i = 0; i < BannerArt.NAMES.length; i++) {
            int index = i;
            Image thumbArt = BannerArt.image(i, 2);
            Region art = new Region();
            art.getStyleClass().add("preset-art");
            art.setPrefSize(104, 54);
            art.setMaxSize(104, 54);
            art.setBackground(new Background(new BackgroundImage(thumbArt,
                    BackgroundRepeat.NO_REPEAT, BackgroundRepeat.NO_REPEAT,
                    BackgroundPosition.CENTER, coverSize(thumbArt, 104, 54))));
            Label name = new Label(BannerArt.NAMES[index]);
            name.getStyleClass().add("preset-name");
            VBox cell = new VBox(art, name);
            cell.setMaxWidth(Double.MAX_VALUE);
            Button thumb = new Button();
            thumb.setGraphic(cell);
            thumb.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
            thumb.getStyleClass().add("banner-thumb");
            thumb.setMaxWidth(Double.MAX_VALUE);
            thumb.setOnAction(e -> {
                appearancePanelMode = null;
                bannerPresetChosen.accept(index);
                syncAppearanceControls();
            });
            appearanceGrid.getChildren().add(thumb);
        }
        appearancePresetPanel = new VBox(appearanceGrid);
        appearancePresetPanel.getStyleClass().add("pop-panel");

        bannerPickButton.getStyleClass().add("secondary");
        bannerResetButton.getStyleClass().add("secondary");
        bannerPickButton.setOnAction(e -> {
            appearancePanelMode = null;
            bannerPickImage.run();
            syncAppearanceControls();
        });
        bannerResetButton.setOnAction(e -> {
            appearancePanelMode = null;
            bannerResetImage.run();
            syncAppearanceControls();
        });
        HBox customRow = new HBox(8, bannerPickButton, bannerResetButton);
        bannerCustomHint.setWrapText(true);
        bannerCustomHint.getStyleClass().add("hint-text");
        appearanceCustomPanel = new VBox(8, customRow, bannerCustomHint);
        appearanceCustomPanel.getStyleClass().add("pop-panel");

        VBox card = new VBox(12, head, seg, appearanceShotsPanel, appearancePresetPanel,
                appearanceCustomPanel);
        card.getStyleClass().add("banner-pop");
        card.setPrefWidth(370);
        card.setMaxWidth(370);
        StackPane.setAlignment(card, Pos.TOP_RIGHT);
        StackPane.setMargin(card, new Insets(104, 34, 0, 0));

        overlay.getChildren().addAll(catcher, card);
        return overlay;
    }

    private StackPane buildWizardOverlay() {
        StackPane overlay = new StackPane();
        overlay.getStyleClass().add("overlay");
        overlay.setVisible(false);

        Label title = new Label("Новая сборка");
        title.getStyleClass().add("dialog-title");
        wizardCloseButton.getStyleClass().add("dialog-close");
        wizardCloseButton.setOnAction(e -> closeWizard());
        HBox header = new HBox(10, title, spacer(), wizardCloseButton);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("dialog-header");

        VBox body = new VBox(12);
        body.getChildren().addAll(
                sectionLabel("ЗАГРУЗЧИК"),
                loaderPicker,
                sectionLabel("ВЕРСИЯ MINECRAFT"),
                profileBaseVersion,
                sectionLabel("СБОРКА ЗАГРУЗЧИКА"),
                buildPicker,
                sectionLabel("НАЗВАНИЕ СБОРКИ"),
                profileNameField,
                loaderNote,
                forgeConsentError,
                forgeOptIn,
                forgeSupportLink,
                createProfileButton);
        body.setFillWidth(true);
        body.getStyleClass().add("dialog-body");

        VBox dialog = new VBox(0, header, body);
        dialog.getStyleClass().add("dialog");
        dialog.setPrefWidth(520);
        dialog.setMaxWidth(520);
        dialog.setMaxHeight(Region.USE_PREF_SIZE);
        overlay.getChildren().add(dialog);
        return overlay;
    }

    private StackPane buildAccountsOverlay() {
        StackPane overlay = new StackPane();
        overlay.getStyleClass().add("overlay");
        overlay.setVisible(false);

        Label accountsTitle = new Label("Аккаунты");
        accountsTitle.getStyleClass().add("dialog-title");
        accountsCloseButton.getStyleClass().add("dialog-close");
        accountsCloseButton.setOnAction(e -> closeAccounts());
        HBox header = new HBox(10, accountsTitle, spacer(), accountsCloseButton);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("dialog-header");

        VBox body = new VBox(12, providerBox, loginFieldsBox, loginButton);
        body.setFillWidth(true);
        body.getStyleClass().add("dialog-body");

        VBox dialog = new VBox(0, header, body);
        dialog.getStyleClass().add("dialog");
        dialog.setPrefWidth(520);
        dialog.setMaxWidth(520);
        dialog.setMaxHeight(Region.USE_PREF_SIZE);
        overlay.getChildren().add(dialog);
        return overlay;
    }

    private VBox buildConsoleContent() {
        copyCrashButton.getStyleClass().add("secondary");
        clearLogButton.getStyleClass().add("secondary");
        clearLogButton.setOnAction(e -> logArea.clear());
        HBox buttons = new HBox(8, copyCrashButton, clearLogButton);
        buttons.setAlignment(Pos.CENTER_RIGHT);
        buttons.setMaxWidth(Double.MAX_VALUE);
        VBox content = new VBox(8, buttons, logArea);
        content.setPadding(new Insets(10));
        content.setMaxWidth(Double.MAX_VALUE);
        content.setMaxHeight(Double.MAX_VALUE);
        content.getStyleClass().add("console");
        VBox.setVgrow(logArea, Priority.ALWAYS);
        return content;
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
        field.setMaxWidth(Double.MAX_VALUE);
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
        progressLabel.setVisible(true);
        progressLabel.setManaged(true);
        progressLabel.setText("Загрузка файлов игры… " + percent + "%");
    }

    public void hideProgress() {
        progressBar.setVisible(false);
        progressBar.setManaged(false);
        progressBar.setProgress(0);
        progressLabel.setVisible(false);
        progressLabel.setManaged(false);
        progressLabel.setText(BASE_STATUS);
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

    /** Gradient cover shared by the sidebar cells and the banner background. */
    private static String coverGradient(String id) {
        int hue = id == null ? 24 : Math.floorMod(id.hashCode(), 360);
        int second = Math.floorMod(hue + 40, 360);
        return "-fx-background-color: linear-gradient(to bottom right, hsb(" + hue
                + ", 55%, 45%), hsb(" + second + ", 60%, 24%));";
    }

    /** Group of independent boolean settings. */
    public static final class CheckBoxToggles {
        public final CheckBox isolateGameDir = new CheckBox("Отдельная папка мира для каждой сборки");
        public final CheckBox authlibInjector = new CheckBox("Использовать authlib-injector для Ely");

        public CheckBoxToggles() {
            for (CheckBox box : List.of(isolateGameDir, authlibInjector)) {
                box.setMaxWidth(Double.MAX_VALUE);
            }
        }
    }
}
