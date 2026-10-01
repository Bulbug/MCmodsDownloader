package app.ui;

import app.configuration.AppContext;
import app.minecraft.EnvironmentScanner;
import app.minecraft.EnvironmentScanner.ScanResult;
import app.minecraft.JavaInstall;
import app.minecraft.MinecraftInstallation;
import app.minecraft.MinecraftVersionInfo;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.concurrent.Task;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.VBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Function;

/** Home page: shows what was detected on this computer. Scanning runs in the background. */
public final class HomeView {

    private static final Logger log = LoggerFactory.getLogger("Detection");

    private final AppContext context;
    private final ScrollPane root = new ScrollPane();
    private final Label status = new Label("Scanning...");
    private final Label minecraftLine = new Label();
    private final Label hint = new Label();
    private final Button rescan = new Button("Rescan");
    private final TableView<MinecraftVersionInfo> versionTable = new TableView<>();
    private final TableView<JavaInstall> javaTable = new TableView<>();

    private ScanResult lastResult;

    public HomeView(AppContext context) {
        this.context = context;

        VBox box = new VBox(10);
        Label title = new Label("Home");
        title.getStyleClass().add("page-title");

        Label dataDir = new Label("Data directory: " + context.paths().root());
        dataDir.getStyleClass().add("page-text");

        status.getStyleClass().add("page-muted");
        minecraftLine.getStyleClass().add("page-text");
        hint.getStyleClass().add("page-muted");
        hint.setWrapText(true);

        Label javaTitle = new Label("Java installations");
        javaTitle.getStyleClass().add("section-title");
        Label versionsTitle = new Label("Minecraft versions found");
        versionsTitle.getStyleClass().add("section-title");

        buildJavaTable();
        buildVersionTable();
        rescan.setOnAction(e -> startScan());

        box.getChildren().addAll(title, dataDir, rescan, status, minecraftLine,
                versionsTitle, versionTable, javaTitle, javaTable, hint);

        root.setContent(box);
        root.setFitToWidth(true);
        root.getStyleClass().add("transparent-scroll");
        box.setPadding(new javafx.geometry.Insets(0, 8, 0, 0));

        startScan();
    }

    public Node node() {
        return root;
    }

    /** Plain (non-modded) Minecraft version ids found by the last scan. Call on the UI thread. */
    public java.util.List<String> knownVersionIds() {
        if (lastResult == null) return java.util.List.of();
        return lastResult.minecraft().versions().stream()
                .filter(v -> "Vanilla".equals(v.loader()))
                .map(MinecraftVersionInfo::id)
                .toList();
    }

    private void startScan() {
        rescan.setDisable(true);
        status.setText("Scanning...");
        String override = context.settings().minecraftDirectory;

        Task<ScanResult> task = new Task<>() {
            @Override
            protected ScanResult call() {
                return EnvironmentScanner.scan(override);
            }
        };
        task.setOnSucceeded(e -> {
            lastResult = task.getValue();
            render(lastResult);
            rescan.setDisable(false);
        });
        task.setOnFailed(e -> {
            log.error("Environment scan failed", task.getException());
            status.setText("Scan failed: " + task.getException().getMessage());
            rescan.setDisable(false);
        });
        Thread thread = new Thread(task, "environment-scan");
        thread.setDaemon(true);
        thread.start();
    }

    private void render(ScanResult result) {
        MinecraftInstallation mc = result.minecraft();
        log.info("Scan finished: minecraftFound={}, versions={}, javaInstalls={}",
                mc.exists(), mc.versions().size(), result.javaInstalls().size());

        status.setText("Scan complete.");
        if (!mc.exists()) {
            minecraftLine.setText("Minecraft folder not found: " + mc.directory());
            hint.setText("If your launcher uses a different folder, set it in Settings and press Rescan.");
        } else {
            minecraftLine.setText("Minecraft folder: " + mc.directory()
                    + (mc.launcherProfilesFound() ? "  (launcher profiles found)" : "  (no launcher profiles file)"));
            hint.setText("\"Java available\" only checks that some installed Java is at least the minimum version. "
                    + "Very old versions and some Forge builds need exactly Java 8, so check the mod loader's notes.");
        }
        versionTable.getItems().setAll(mc.versions());
        javaTable.getItems().setAll(result.javaInstalls());
    }

    private void buildVersionTable() {
        versionTable.setPrefHeight(230);
        versionTable.setPlaceholder(new Label("No versions found"));
        versionTable.getColumns().add(column("Version", 260, MinecraftVersionInfo::id));
        versionTable.getColumns().add(column("Type", 90, MinecraftVersionInfo::type));
        versionTable.getColumns().add(column("Loader", 90, MinecraftVersionInfo::loader));
        versionTable.getColumns().add(column("Minimum Java", 110,
                v -> v.requiredJava() == 0 ? "unknown" : "Java " + v.requiredJava()));
        versionTable.getColumns().add(column("Java available", 120, this::javaAvailable));
    }

    private void buildJavaTable() {
        javaTable.setPrefHeight(170);
        javaTable.setPlaceholder(new Label("No Java found"));
        javaTable.getColumns().add(column("Java", 70, j -> j.major() == 0 ? "?" : String.valueOf(j.major())));
        javaTable.getColumns().add(column("Version", 110, JavaInstall::version));
        javaTable.getColumns().add(column("Vendor", 150, JavaInstall::vendor));
        javaTable.getColumns().add(column("Found via", 90, JavaInstall::source));
        javaTable.getColumns().add(column("Location", 420, j -> j.home().toString()));
    }

    private String javaAvailable(MinecraftVersionInfo v) {
        if (v.requiredJava() == 0) return "unknown";
        if (lastResult == null) return "...";
        boolean ok = lastResult.javaInstalls().stream().anyMatch(j -> j.major() >= v.requiredJava());
        return ok ? "Yes" : "No - install Java " + v.requiredJava();
    }

    private static <T> TableColumn<T, String> column(String title, double width, Function<T, String> getter) {
        TableColumn<T, String> col = new TableColumn<>(title);
        col.setPrefWidth(width);
        col.setCellValueFactory(cell -> new ReadOnlyStringWrapper(getter.apply(cell.getValue())));
        return col;
    }
}
