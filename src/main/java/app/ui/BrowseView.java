package app.ui;

import app.api.ContentProvider;
import app.api.ProjectSummary;
import app.api.ProjectType;
import app.api.ProjectVersion;
import app.api.ProjectVersion.DependencyType;
import app.api.ProjectVersion.VersionFile;
import app.api.ProviderException;
import app.api.SearchPage;
import app.api.SearchQuery;
import app.api.SearchSort;
import app.api.TrustedUrls;
import app.configuration.AppContext;
import app.downloads.Checksum;
import app.downloads.DownloadException;
import app.downloads.DownloadRequest;
import app.downloads.FileNames;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.nio.file.Path;
import java.text.NumberFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Search page for one kind of content (mods, modpacks, resource packs or shaders).
 * Network work runs in the background; results from outdated searches are ignored.
 */
public final class BrowseView {

    private static final Logger log = LoggerFactory.getLogger("UI");
    private static final int PAGE_SIZE = 20;
    private static final List<String> LOADER_CHOICES = List.of("Any loader", "Fabric", "Quilt", "Forge", "NeoForge");

    private final AppContext context;
    private final ContentProvider provider;
    private final ProjectType type;
    private final Supplier<List<String>> knownVersions;

    private final VBox root = new VBox(10);
    private final TextField searchField = new TextField();
    private final ComboBox<String> versionBox = new ComboBox<>();
    private final ComboBox<String> loaderBox = new ComboBox<>();
    private final ComboBox<SearchSort> sortBox = new ComboBox<>();
    private final Button searchButton = new Button("Search");
    private final Button prevButton = new Button("Previous");
    private final Button nextButton = new Button("Next");
    private final Label pageLabel = new Label();
    private final Label status = new Label();

    private final ListView<ProjectSummary> results = new ListView<>();
    private final Label detailTitle = new Label("Select a result");
    private final Label detailMeta = new Label();
    private final Label detailDescription = new Label();
    private final Label versionInfo = new Label();
    private final TableView<ProjectVersion> versionTable = new TableView<>();
    private final Button downloadButton = new Button("Download file");
    private final Button installButton = new Button("Install to instance...");
    private final Button pageButton = new Button("Open project page");

    private final Map<String, Image> iconCache = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Image> eldest) {
            return size() > 300;
        }
    };

    private boolean loaded;
    private long searchSeq;
    private long versionSeq;
    private int offset;
    private SearchPage lastPage;

    public BrowseView(AppContext context, ProjectType type, Supplier<List<String>> knownVersions) {
        this.context = context;
        this.provider = context.content();
        this.type = type;
        this.knownVersions = knownVersions;

        Label title = new Label(type.label());
        title.getStyleClass().add("page-title");
        Label source = new Label("Source: " + provider.name());
        source.getStyleClass().add("page-muted");
        status.getStyleClass().add("page-text");
        pageLabel.getStyleClass().add("page-muted");

        searchField.setPromptText("Search " + type.label().toLowerCase(Locale.ROOT) + "...");
        HBox.setHgrow(searchField, Priority.ALWAYS);
        versionBox.setEditable(true);
        versionBox.setPromptText("Any Minecraft version");
        versionBox.setPrefWidth(190);
        loaderBox.getItems().addAll(LOADER_CHOICES);
        loaderBox.setValue(LOADER_CHOICES.get(0));
        loaderBox.setVisible(type.usesLoader());
        loaderBox.setManaged(type.usesLoader());
        sortBox.getItems().addAll(SearchSort.values());
        sortBox.setValue(SearchSort.DOWNLOADS);
        sortBox.setConverter(new StringConverter<>() {
            @Override
            public String toString(SearchSort s) {
                return s == null ? "" : s.label();
            }

            @Override
            public SearchSort fromString(String s) {
                return SearchSort.RELEVANCE;
            }
        });

        // Search only on request (button or Enter): this keeps well under Modrinth's rate limit.
        searchButton.setOnAction(e -> runSearch(0));
        searchField.setOnAction(e -> runSearch(0));
        prevButton.setOnAction(e -> runSearch(Math.max(0, offset - PAGE_SIZE)));
        nextButton.setOnAction(e -> runSearch(offset + PAGE_SIZE));
        prevButton.setDisable(true);
        nextButton.setDisable(true);

        HBox controls = new HBox(8, searchField, versionBox, loaderBox, sortBox, searchButton);

        buildResultList();
        VBox left = new VBox(8, results, new HBox(8, prevButton, nextButton, pageLabel));
        VBox.setVgrow(results, Priority.ALWAYS);

        VBox right = buildDetails();
        SplitPane split = new SplitPane(left, right);
        split.setOrientation(Orientation.HORIZONTAL);
        split.setDividerPositions(0.42);
        VBox.setVgrow(split, Priority.ALWAYS);

        root.getChildren().addAll(title, source, controls, split, status);
        VBox.setVgrow(split, Priority.ALWAYS);
        updateDetailButtons();
    }

    public Node node() {
        return root;
    }

    /** Called every time the page is shown. The first call starts the first search. */
    public void ensureLoaded() {
        refreshVersionChoices();
        if (!loaded) {
            loaded = true;
            runSearch(0);
        }
    }

    // ---- search ----------------------------------------------------------------------

    private void runSearch(int newOffset) {
        long seq = ++searchSeq;
        String loader = type.usesLoader() && loaderBox.getValue() != null
                && !loaderBox.getValue().equals(LOADER_CHOICES.get(0)) ? loaderBox.getValue() : null;
        SearchQuery query = new SearchQuery(searchField.getText(), type, versionText(), loader,
                sortBox.getValue(), newOffset, PAGE_SIZE);

        status.setText("Searching...");
        Task<SearchPage> task = new Task<>() {
            @Override
            protected SearchPage call() {
                return provider.search(query);
            }
        };
        task.setOnSucceeded(e -> {
            if (seq != searchSeq) return; // a newer search was started
            lastPage = task.getValue();
            offset = lastPage.offset();
            results.getItems().setAll(lastPage.hits());
            results.scrollTo(0);
            updatePaging();
            status.setText(lastPage.hits().isEmpty() ? "No results. Try other words or filters." : "");
        });
        task.setOnFailed(e -> {
            if (seq != searchSeq) return;
            status.setText(friendly(task.getException()));
        });
        start(task, "search");
    }

    private void updatePaging() {
        int from = lastPage.hits().isEmpty() ? 0 : offset + 1;
        int to = offset + lastPage.hits().size();
        NumberFormat nf = NumberFormat.getIntegerInstance(Locale.US);
        pageLabel.setText(from + "-" + to + " of " + nf.format(lastPage.totalHits()));
        prevButton.setDisable(offset <= 0);
        nextButton.setDisable(to >= lastPage.totalHits());
    }

    // ---- details and versions ---------------------------------------------------------

    private void showProject(ProjectSummary project) {
        versionTable.getItems().clear();
        versionInfo.setText("");
        updateDetailButtons();
        if (project == null) {
            detailTitle.setText("Select a result");
            detailMeta.setText("");
            detailDescription.setText("");
            return;
        }

        NumberFormat nf = NumberFormat.getIntegerInstance(Locale.US);
        detailTitle.setText(project.title());
        detailMeta.setText("by " + project.author() + "  |  " + nf.format(project.downloads()) + " downloads  |  "
                + (project.license() == null ? "license unknown" : project.license())
                + (project.categories().isEmpty() ? "" : "  |  " + String.join(", ", project.categories())));
        detailDescription.setText(project.description());

        long seq = ++versionSeq;
        String loader = type.usesLoader() && loaderBox.getValue() != null
                && !loaderBox.getValue().equals(LOADER_CHOICES.get(0)) ? loaderBox.getValue() : null;
        String mcVersion = versionText();
        versionInfo.setText("Loading versions...");

        Task<List<ProjectVersion>> task = new Task<>() {
            @Override
            protected List<ProjectVersion> call() {
                return provider.versions(project.id(), mcVersion, loader);
            }
        };
        task.setOnSucceeded(e -> {
            if (seq != versionSeq) return;
            List<ProjectVersion> versions = task.getValue();
            versionTable.getItems().setAll(versions);
            if (versions.isEmpty()) {
                versionInfo.setText(mcVersion != null || loader != null
                        ? "No version matches your filters (" + describeFilters(mcVersion, loader)
                        + "). Clear the filters to see all versions."
                        : "This project has no downloadable versions.");
            } else {
                versionInfo.setText(versions.size() + " version" + (versions.size() == 1 ? "" : "s")
                        + (mcVersion != null || loader != null ? " matching " + describeFilters(mcVersion, loader) : "")
                        + ". Select one.");
                versionTable.getSelectionModel().selectFirst();
            }
        });
        task.setOnFailed(e -> {
            if (seq != versionSeq) return;
            versionInfo.setText(friendly(task.getException()));
        });
        start(task, "versions");
    }

    private void showVersion(ProjectVersion version) {
        updateDetailButtons();
        if (version == null) return;
        long required = version.count(DependencyType.REQUIRED);
        long optional = version.count(DependencyType.OPTIONAL);
        long incompatible = version.count(DependencyType.INCOMPATIBLE);
        StringBuilder sb = new StringBuilder();
        sb.append("Requires ").append(required).append(required == 1 ? " other project" : " other projects");
        if (optional > 0) sb.append(", ").append(optional).append(" optional");
        if (incompatible > 0) sb.append(", incompatible with ").append(incompatible);
        sb.append(". Use \"Install to instance\" to add it with everything it needs.");
        versionInfo.setText(sb.toString());
    }

    private void onDownload() {
        ProjectSummary project = results.getSelectionModel().getSelectedItem();
        ProjectVersion version = versionTable.getSelectionModel().getSelectedItem();
        if (project == null || version == null) return;
        VersionFile file = version.primaryFile();
        if (file == null) {
            status.setText("This version has no file to download.");
            return;
        }
        try {
            Checksum checksum = file.sha512() != null ? Checksum.parse(file.sha512())
                    : file.sha1() != null ? Checksum.parse(file.sha1()) : null;
            String fileName = FileNames.sanitize(file.fileName());
            Path destination = context.paths().downloadsDir().resolve(fileName);
            context.downloads().enqueue(new DownloadRequest(URI.create(file.url()), destination,
                    project.title() + " " + version.versionNumber(), checksum, file.size(), false));
            status.setText("Added \"" + fileName + "\" to Downloads."
                    + (checksum == null ? " (No checksum was provided, so it could not be verified.)" : ""));
        } catch (IllegalArgumentException | DownloadException e) {
            status.setText("Could not start the download: " + e.getMessage());
        }
    }

    // ---- UI construction ---------------------------------------------------------------

    private void buildResultList() {
        results.setPlaceholder(new Label("Nothing to show yet."));
        results.setCellFactory(lv -> new ListCell<>() {
            private final ImageView icon = new ImageView();
            private final Label title = new Label();
            private final Label meta = new Label();
            private final Label description = new Label();
            private final VBox texts = new VBox(2, title, meta, description);
            private final HBox row = new HBox(10, icon, texts);

            {
                icon.setFitWidth(48);
                icon.setFitHeight(48);
                title.getStyleClass().add("result-title");
                meta.getStyleClass().add("page-muted");
                description.getStyleClass().add("page-text");
                description.setWrapText(true);
                description.setMaxHeight(38);
                description.maxWidthProperty().bind(lv.widthProperty().subtract(100));
                row.setPadding(new Insets(4, 0, 4, 0));
            }

            @Override
            protected void updateItem(ProjectSummary item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setGraphic(null);
                    return;
                }
                title.setText(item.title());
                meta.setText("by " + item.author() + "  |  "
                        + NumberFormat.getIntegerInstance(Locale.US).format(item.downloads()) + " downloads");
                description.setText(item.description());
                icon.setImage(iconFor(item));
                setGraphic(row);
            }
        });
        results.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> showProject(b));
    }

    private VBox buildDetails() {
        detailTitle.getStyleClass().add("section-title");
        detailMeta.getStyleClass().add("page-muted");
        detailMeta.setWrapText(true);
        detailDescription.getStyleClass().add("page-text");
        detailDescription.setWrapText(true);
        versionInfo.getStyleClass().add("page-text");
        versionInfo.setWrapText(true);

        versionTable.setPlaceholder(new Label("No versions to show"));
        versionTable.getColumns().add(column("Version", 170, ProjectVersion::versionNumber));
        versionTable.getColumns().add(column("Type", 70, ProjectVersion::versionType));
        versionTable.getColumns().add(column("Minecraft", 120, v -> shorten(v.gameVersions(), 3)));
        versionTable.getColumns().add(column("Loaders", 110, v -> String.join(", ", v.loaders())));
        versionTable.getColumns().add(column("Published", 90, v -> v.datePublished() == null ? ""
                : v.datePublished().substring(0, Math.min(10, v.datePublished().length()))));
        versionTable.getColumns().add(column("Size", 80, v -> v.primaryFile() == null ? "-"
                : Formats.bytes(v.primaryFile().size())));
        versionTable.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> showVersion(b));

        downloadButton.setOnAction(e -> onDownload());
        installButton.setVisible(type == ProjectType.MOD);
        installButton.setManaged(type == ProjectType.MOD);
        installButton.setOnAction(e -> {
            ProjectSummary p = results.getSelectionModel().getSelectedItem();
            ProjectVersion v = versionTable.getSelectionModel().getSelectedItem();
            if (p != null && v != null) new InstallFlow(context, root, status::setText).start(p, v);
        });
        pageButton.setOnAction(e -> {
            ProjectSummary p = results.getSelectionModel().getSelectedItem();
            if (p != null) {
                URI page = TrustedUrls.modrinthPage(p.type(), p.slug() != null ? p.slug() : p.id());
                FolderOpener.browse(page, status::setText);
            }
        });

        VBox box = new VBox(8, detailTitle, detailMeta, detailDescription,
                new Label("Versions"), versionTable, versionInfo, new HBox(8, installButton, downloadButton, pageButton));
        box.getChildren().get(3).getStyleClass().add("section-title");
        box.setPadding(new Insets(0, 0, 0, 10));
        VBox.setVgrow(versionTable, Priority.ALWAYS);
        return box;
    }

    private void updateDetailButtons() {
        boolean noFile = versionTable.getSelectionModel().getSelectedItem() == null
                || versionTable.getSelectionModel().getSelectedItem().primaryFile() == null;
        downloadButton.setDisable(noFile);
        installButton.setDisable(noFile);
        pageButton.setDisable(results.getSelectionModel().getSelectedItem() == null);
    }

    // ---- helpers -----------------------------------------------------------------------

    private void refreshVersionChoices() {
        String typed = versionBox.getEditor().getText();
        versionBox.getItems().setAll(knownVersions.get());
        versionBox.getEditor().setText(typed);
    }

    private String versionText() {
        String text = versionBox.getEditor().getText();
        return text == null || text.isBlank() ? null : text.trim();
    }

    private Image iconFor(ProjectSummary item) {
        String url = item.iconUrl();
        if (!TrustedUrls.isTrustedImage(url)) return null;
        return iconCache.computeIfAbsent(url, u -> new Image(u, 48, 48, true, true, true));
    }

    private static String describeFilters(String mcVersion, String loader) {
        if (mcVersion != null && loader != null) return mcVersion + ", " + loader;
        return mcVersion != null ? mcVersion : loader;
    }

    private static String shorten(List<String> items, int max) {
        if (items.size() <= max) return String.join(", ", items);
        return String.join(", ", items.subList(0, max)) + " +" + (items.size() - max);
    }

    private static String friendly(Throwable error) {
        if (error instanceof ProviderException) return error.getMessage();
        log.error("Unexpected error while browsing", error);
        return "Something went wrong. Details were written to the log file.";
    }

    private static void start(Task<?> task, String name) {
        Thread thread = new Thread(task, "browse-" + name);
        thread.setDaemon(true);
        thread.start();
    }

    private static <T> TableColumn<T, String> column(String title, double width, Function<T, String> getter) {
        TableColumn<T, String> col = new TableColumn<>(title);
        col.setPrefWidth(width);
        col.setCellValueFactory(cell -> new ReadOnlyStringWrapper(getter.apply(cell.getValue())));
        return col;
    }
}
