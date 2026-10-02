package app.ui;

import app.configuration.AppContext;
import app.instance.FileInstanceRepository;
import app.instance.Instance;
import app.instance.InstanceException;
import app.instance.InstanceService;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/** Instances page: list, create, rename, duplicate, open folder, delete. */
public final class InstancesView {

    private static final Logger log = LoggerFactory.getLogger("UI");
    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final AppContext context;
    private final InstanceService service;
    private final Path root;
    private final Supplier<List<String>> knownVersions;

    private final VBox box = new VBox(10);
    private final TableView<Instance> table = new TableView<>();
    private final Label status = new Label();
    private final Button newButton = new Button("New instance");
    private final Button renameButton = new Button("Rename");
    private final Button duplicateButton = new Button("Duplicate");
    private final Button modsButton = new Button("Installed mods");
    private final Button backupButton = new Button("Backup...");
    private final Button backupsButton = new Button("Backups");
    private final Button openButton = new Button("Open folder");
    private final Button deleteButton = new Button("Delete");
    private final Button refreshButton = new Button("Refresh");

    public InstancesView(AppContext context, Supplier<List<String>> knownVersions) {
        this.context = context;
        this.knownVersions = knownVersions;
        this.root = context.paths().instancesDirFor(context.settings());
        this.service = new InstanceService(new FileInstanceRepository(root));

        Label title = new Label("Instances");
        title.getStyleClass().add("page-title");
        Label where = new Label("Stored in: " + root);
        where.getStyleClass().add("page-muted");
        status.getStyleClass().add("page-text");

        table.setPlaceholder(new Label("No instances yet. Click \"New instance\"."));
        table.getColumns().add(column("Name", 220, Instance::name));
        table.getColumns().add(column("Minecraft", 110, Instance::minecraftVersion));
        table.getColumns().add(column("Loader", 150, i -> i.loaderVersion() == null
                ? i.loader() : i.loader() + " " + i.loaderVersion()));
        table.getColumns().add(column("Created", 140, i -> DATE.format(Instant.ofEpochMilli(i.createdAt()))));
        table.getColumns().add(column("Last played", 140, i -> i.lastPlayed() == 0
                ? "never" : DATE.format(Instant.ofEpochMilli(i.lastPlayed()))));
        table.setPrefHeight(360);

        HBox buttons = new HBox(8, newButton, renameButton, duplicateButton, modsButton, backupButton, backupsButton,
                openButton, deleteButton, refreshButton);

        newButton.setOnAction(e -> onCreate());
        renameButton.setOnAction(e -> onRename());
        duplicateButton.setOnAction(e -> onDuplicate());
        modsButton.setOnAction(e -> onMods());
        backupButton.setOnAction(e -> onBackup());
        backupsButton.setOnAction(e -> new BackupsDialog(context, box).show());
        openButton.setOnAction(e -> onOpenFolder());
        deleteButton.setOnAction(e -> onDelete());
        refreshButton.setOnAction(e -> refresh());
        table.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> updateButtons());
        updateButtons();

        box.getChildren().addAll(title, where, buttons, table, status);
        refresh();
    }

    public Node node() {
        return box;
    }

    // ---- actions -----------------------------------------------------------------

    private void onCreate() {
        Dialog<String[]> dialog = new Dialog<>();
        dialog.setTitle("New instance");
        dialog.setHeaderText("Create an isolated Minecraft instance");
        style(dialog);

        TextField name = new TextField();
        name.setPromptText("For example: Fabric 1.21.8");
        ComboBox<String> version = new ComboBox<>();
        version.setEditable(true);
        version.getItems().addAll(knownVersions.get());
        version.setPromptText("For example: 1.21.8");
        ComboBox<String> loader = new ComboBox<>();
        loader.getItems().addAll(InstanceService.LOADERS);
        loader.setValue("Vanilla");
        TextField loaderVersion = new TextField();
        loaderVersion.setPromptText("Optional");

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(10));
        grid.addRow(0, new Label("Name"), name);
        grid.addRow(1, new Label("Minecraft version"), version);
        grid.addRow(2, new Label("Mod loader"), loader);
        grid.addRow(3, new Label("Loader version"), loaderVersion);
        Label note = new Label("This creates the instance folder only. Installing the loader itself comes in a later phase.");
        note.setWrapText(true);
        note.getStyleClass().add("page-muted");
        note.setMaxWidth(380);
        grid.add(note, 0, 4, 2, 1);

        dialog.getDialogPane().setContent(grid);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        dialog.setResultConverter(button -> button == ButtonType.OK
                ? new String[]{name.getText(), version.getEditor().getText(), loader.getValue(), loaderVersion.getText()}
                : null);

        dialog.showAndWait().ifPresent(r -> runAsync(
                () -> service.create(r[0], r[1], r[2], r[3]),
                created -> {
                    status.setText("Created \"" + created.name() + "\".");
                    refresh();
                }));
    }

    private void onRename() {
        Instance selected = selected();
        if (selected == null) return;
        TextInputDialog dialog = new TextInputDialog(selected.name());
        dialog.setTitle("Rename instance");
        dialog.setHeaderText("New name for \"" + selected.name() + "\"");
        style(dialog);
        dialog.showAndWait().ifPresent(newName -> runAsync(
                () -> service.rename(selected.id(), newName),
                renamed -> {
                    status.setText("Renamed to \"" + renamed.name() + "\".");
                    refresh();
                }));
    }

    private void onDuplicate() {
        Instance selected = selected();
        if (selected == null) return;
        status.setText("Copying \"" + selected.name() + "\"...");
        runAsync(() -> service.duplicate(selected.id()), copy -> {
            status.setText("Created \"" + copy.name() + "\".");
            refresh();
        });
    }

    private void onBackup() {
        Instance selected = selected();
        if (selected == null) return;
        runAsync(() -> service.folderOf(selected.id()),
                folder -> new BackupFlow(context, box, status::setText).createBackup(selected, folder));
    }

    private void onMods() {
        Instance selected = selected();
        if (selected == null) return;
        runAsync(() -> service.folderOf(selected.id()),
                folder -> new InstalledModsDialog(context, selected, folder, box).show());
    }

    private void onOpenFolder() {
        Instance selected = selected();
        if (selected == null) return;
        runAsync(() -> service.folderOf(selected.id()), folder -> FolderOpener.open(folder, status::setText));
    }

    private void onDelete() {
        Instance selected = selected();
        if (selected == null) return;

        // Count worlds first (background), then ask for confirmation.
        runAsync(() -> service.countWorlds(selected.id()), worlds -> {
            String text = worlds > 0
                    ? "This instance contains " + worlds + " world" + (worlds == 1 ? "" : "s")
                    + ".\nDeleting the instance will permanently delete "
                    + (worlds == 1 ? "it" : "them") + " too.\n\nCreate a backup first?"
                    : "This will permanently delete the instance folder and everything in it.\n\nCreate a backup first?";
            ButtonType backupAndDelete = new ButtonType("Backup and delete", ButtonBar.ButtonData.LEFT);
            ButtonType deleteOnly = new ButtonType("Delete", ButtonBar.ButtonData.OK_DONE);
            Alert alert = new Alert(Alert.AlertType.CONFIRMATION, text, backupAndDelete, deleteOnly, ButtonType.CANCEL);
            alert.setTitle("Delete instance");
            alert.setHeaderText("Delete \"" + selected.name() + "\"?");
            alert.getDialogPane().setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
            style(alert);

            Optional<ButtonType> answer = alert.showAndWait();
            if (answer.isEmpty() || answer.get() == ButtonType.CANCEL) return;

            Runnable delete = () -> runAsync(() -> {
                service.delete(selected.id());
                return selected.name();
            }, name -> {
                status.setText("Deleted \"" + name + "\".");
                refresh();
            });

            if (answer.get() == backupAndDelete) {
                // The instance is only deleted if the backup succeeded.
                runAsync(() -> service.folderOf(selected.id()), folder ->
                        new BackupFlow(context, box, status::setText).backupThen(selected, folder, delete));
            } else {
                delete.run();
            }
        });
    }

    // ---- helpers -----------------------------------------------------------------

    private void refresh() {
        runAsync(service::list, list -> {
            table.getItems().setAll(list);
            updateButtons();
        });
    }

    /** Runs work off the UI thread; shows a friendly message if it fails. */
    private <T> void runAsync(Callable<T> work, Consumer<T> onSuccess) {
        Task<T> task = new Task<>() {
            @Override
            protected T call() throws Exception {
                return work.call();
            }
        };
        task.setOnSucceeded(e -> onSuccess.accept(task.getValue()));
        task.setOnFailed(e -> showError(task.getException()));
        Thread thread = new Thread(task, "instances-worker");
        thread.setDaemon(true);
        thread.start();
    }

    private void showError(Throwable error) {
        log.error("Instance operation failed", error);
        String message = error instanceof InstanceException
                ? error.getMessage()
                : "Something went wrong. Details were written to the log file.";
        status.setText("");
        Alert alert = new Alert(Alert.AlertType.ERROR, message, ButtonType.CLOSE);
        alert.setTitle("Instances");
        alert.setHeaderText("That did not work");
        style(alert);
        alert.show();
    }

    private void style(Dialog<?> dialog) {
        if (box.getScene() != null) {
            dialog.getDialogPane().getStylesheets().addAll(box.getScene().getStylesheets());
            dialog.initOwner(box.getScene().getWindow());
        }
    }

    private Instance selected() {
        return table.getSelectionModel().getSelectedItem();
    }

    private void updateButtons() {
        boolean none = selected() == null;
        renameButton.setDisable(none);
        duplicateButton.setDisable(none);
        modsButton.setDisable(none);
        backupButton.setDisable(none);
        openButton.setDisable(none);
        deleteButton.setDisable(none);
    }

    private static <T> TableColumn<T, String> column(String title, double width, Function<T, String> getter) {
        TableColumn<T, String> col = new TableColumn<>(title);
        col.setPrefWidth(width);
        col.setCellValueFactory(cell -> new ReadOnlyStringWrapper(getter.apply(cell.getValue())));
        return col;
    }
}
