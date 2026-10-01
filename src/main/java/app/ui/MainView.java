package app.ui;

import app.configuration.AppContext;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.EnumMap;
import java.util.Map;

/** Main window layout: sidebar navigation on the left, page content on the right. */
public final class MainView {

    private final BorderPane root = new BorderPane();
    private final StackPane content = new StackPane();
    private final AppContext context;
    /** Kept so the scan results survive switching pages. */
    private final HomeView home;
    private final DownloadsView downloads;
    private final Map<Page, BrowseView> browsers = new EnumMap<>(Page.class);

    public MainView(AppContext context) {
        this.context = context;
        this.home = new HomeView(context);
        this.downloads = new DownloadsView(context);

        VBox sidebar = new VBox(4);
        sidebar.getStyleClass().add("sidebar");
        sidebar.setPadding(new Insets(16, 12, 16, 12));

        Label title = new Label("Minecraft Manager");
        title.getStyleClass().add("app-title");
        sidebar.getChildren().add(title);

        ToggleGroup group = new ToggleGroup();
        ToggleButton first = null;
        for (Page page : Page.values()) {
            ToggleButton button = new ToggleButton(page.title());
            button.setToggleGroup(group);
            button.setUserData(page);
            button.setMaxWidth(Double.MAX_VALUE);
            button.getStyleClass().add("nav-button");
            sidebar.getChildren().add(button);
            if (first == null) first = button;
        }

        group.selectedToggleProperty().addListener((obs, previous, selected) -> {
            if (selected == null) {
                if (previous != null) previous.setSelected(true);
            } else {
                show((Page) selected.getUserData());
            }
        });

        root.setLeft(sidebar);
        root.setCenter(content);
        content.getStyleClass().add("content");
        content.setPadding(new Insets(24));

        group.selectToggle(first);
    }

    public BorderPane root() {
        return root;
    }

    private void show(Page page) {
        Node node = switch (page) {
            case HOME -> home.node();
            case INSTANCES -> new InstancesView(context, home::knownVersionIds).node();
            case MODS -> browse(page, app.api.ProjectType.MOD);
            case MODPACKS -> browse(page, app.api.ProjectType.MODPACK);
            case RESOURCE_PACKS -> browse(page, app.api.ProjectType.RESOURCE_PACK);
            case SHADERS -> browse(page, app.api.ProjectType.SHADER);
            case DOWNLOADS -> downloads.node();
            case SETTINGS -> new SettingsView(context).node();
            default -> placeholder(page);
        };
        content.getChildren().setAll(node);
    }

    /** Browse pages are created on first visit, so the app makes no network calls at startup. */
    private Node browse(Page page, app.api.ProjectType type) {
        BrowseView view = browsers.computeIfAbsent(page,
                p -> new BrowseView(context, type, home::knownVersionIds));
        view.ensureLoaded();
        return view.node();
    }

    private Node placeholder(Page page) {
        VBox box = new VBox(8);
        Label heading = new Label(page.title());
        heading.getStyleClass().add("page-title");
        Label description = new Label(page.description());
        description.getStyleClass().add("page-text");
        Label planned = new Label("Not available yet. Planned for Phase " + page.phase() + ".");
        planned.getStyleClass().add("page-muted");
        box.getChildren().addAll(heading, description, planned);
        StackPane.setAlignment(box, Pos.TOP_LEFT);
        return box;
    }
}
