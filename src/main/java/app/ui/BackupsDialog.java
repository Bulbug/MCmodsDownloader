package app.ui;

import app.backup.BackupException;
import app.backup.BackupInfo;
import app.backup.BackupService;
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
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.function.Function;

/** Lists every backup (also those of deleted instances) and restores, exports or deletes them. */
final class BackupsDialog {

    private static final Logger log = LoggerFactory.getLogger("UI");
    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final AppContext context;
    private final Node anchor;
    private final BackupService backups;
    private final FileInstanceRepository repository;
    private final InstanceService instances;

    private final Dialog<Void> dialog = new Dialog<>();
    private final TableView<BackupInfo> table = new TableView<>();
    private final Label status = new Label();
    private final Button restoreButton = new Button("Restore...");
    private final Button restoreNewButton = new Button("Restore as new instance...");
    private final Button exportButton = new Button("Export...");
    private final Button deleteButton = new Button("Delete...");

    BackupsDialog(AppContext context, Node anchor) {
        this.context = context;
        this.anchor = anchor;
        this.backups = new BackupService(context.paths().backupsDir());
        this.repository = new FileInstanceRepository(context.paths().instancesDirFor(context.settings()));
        this.instances = new InstanceService(repository);
    }

    void show() {
        dialog.setTitle("Backups");
        dialog.setHeaderText("All backups, newest first");
        dialog.setResizable(true);
        style(dialog);

        table.setPlaceholder(new Label("No backups yet. Select an instance and click \"Backup...\"."));
        table.getColumns().add(column("Date", 130, b -> DATE.format(Instant.ofEpochMilli(b.createdAt()))));
        table.getColumns().add(column("Instance", 150, BackupInfo::instanceName));
        table.getColumns().add(column("Minecraft", 80, BackupInfo::minecraftVersion));
        table.getColumns().add(column("Loader", 80, BackupInfo::loader));
        table.getColumns().add(column("Contents", 230, BackupInfo::contents));
        table.getColumns().add(column("Size", 80, b -> Formats.bytes(b.sizeBytes())));
        table.getColumns().add(column("Note", 220, BackupInfo::note));
        table.setPrefSize(1000, 340);
        table.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> updateButtons());

        Button refresh = new Button("Refresh");
        Button openFolder = new Button("Open backups folder");
        refresh.setOnAction(e -> load());
        openFolder.setOnAction(e -> FolderOpener.open(context.paths().backupsDir(), status::setText));
        restoreButton.setOnAction(e -> onRestore());
        restoreNewButton.setOnAction(e -> onRestoreAsNew());
        exportButton.setOnAction(e -> onExport());
        deleteButton.setOnAction(e -> onDelete());

        status.getStyleClass().add("page-text");
        Label note = new Label("\"Restore\" puts the backup back into the original instance and first saves a safety copy "
                + "of what it replaces. If the instance was deleted, use \"Restore as new instance\". "
                + "Backups are normal .zip files; Export copies one to a place you choose.");
        note.setWrapText(true);
        note.getStyleClass().add("page-muted");

        VBox content = new VBox(8, new HBox(8, restoreButton, restoreNewButton, exportButton, deleteButton, refresh, openFolder),
                table, note, status);
        content.setPadding(new Insets(6));
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        updateButtons();
        dialog.show();
        load();
    }

    // ---- actions ---------------------------------------------------------------------

    private void load() {
        status.setText("Loading...");
        background(backups::listAll, list -> {
            table.getItems().setAll(list);
            long total = list.stream().mapToLong(BackupInfo::sizeBytes).sum();
            status.setText(list.size() + " backup" + (list.size() == 1 ? "" : "s") + ", " + Formats.bytes(total) + " in total");
            updateButtons();
        });
    }

    private void onRestore() {
        BackupInfo info = table.getSelectionModel().getSelectedItem();
        if (info == null) return;
        background(() -> instances.list().stream().filter(i -> i.id().equals(info.instanceId())).findFirst().orElse(null),
                instance -> {
                    if (instance == null) {
                        status.setText("The original instance no longer exists. Use \"Restore as new instance\".");
                        return;
                    }
                    confirm("Restore backup", "Restore into \"" + instance.name() + "\"?",
                            "These will be REPLACED with the backup's version: " + info.contents() + ".\n\n"
                                    + "Anything you changed in them since " + DATE.format(Instant.ofEpochMilli(info.createdAt()))
                                    + " is lost, but a safety copy of the current state is saved first as an automatic backup.\n\n"
                                    + "Close Minecraft before continuing.", "Restore", () -> {
                                Path dir = repository.directoryOf(instance.id());
                                ProgressRunner.run(anchor, "Restore", "Restoring into \"" + instance.name() + "\"",
                                        (cancelled, progress) -> backups.restoreInto(info, instance, dir, cancelled, progress),
                                        result -> {
                                            status.setText("Restored " + result.unitsRestored() + " item"
                                                    + (result.unitsRestored() == 1 ? "" : "s") + "."
                                                    + (result.safetyBackup() != null ? " A safety copy of the previous state was saved." : ""));
                                            load();
                                        }, this::fail);
                            });
                });
    }

    private void onRestoreAsNew() {
        BackupInfo info = table.getSelectionModel().getSelectedItem();
        if (info == null) return;
        confirm("Restore as new instance", "Create a new instance from this backup?",
                "A new instance \"" + info.instanceName() + " (restored)\" is created with Minecraft "
                        + info.minecraftVersion() + " and " + info.loader() + ". Nothing existing is changed.",
                "Create", () -> ProgressRunner.run(anchor, "Restore", "Creating a new instance",
                        (cancelled, progress) -> backups.restoreAsNew(info, instances, repository, cancelled, progress),
                        (Instance created) -> {
                            status.setText("Created the instance \"" + created.name() + "\". Find it on the Instances page.");
                            load();
                        }, this::fail));
    }

    private void onExport() {
        BackupInfo info = table.getSelectionModel().getSelectedItem();
        if (info == null) return;
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Export backup");
        chooser.setInitialFileName(info.file().getFileName().toString());
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Zip archive", "*.zip"));
        File target = chooser.showSaveDialog(dialog.getDialogPane().getScene().getWindow());
        if (target == null) return;
        status.setText("Exporting...");
        background(() -> {
            backups.export(info, target.toPath());
            return target.getName();
        }, name -> status.setText("Exported to " + name + "."));
    }

    private void onDelete() {
        BackupInfo info = table.getSelectionModel().getSelectedItem();
        if (info == null) return;
        confirm("Delete backup", "Delete this backup?",
                "The backup of \"" + info.instanceName() + "\" from " + DATE.format(Instant.ofEpochMilli(info.createdAt()))
                        + " (" + Formats.bytes(info.sizeBytes()) + ") will be permanently deleted.", "Delete",
                () -> background(() -> {
                    backups.delete(info);
                    return info;
                }, deleted -> {
                    status.setText("Backup deleted.");
                    load();
                }));
    }

    // ---- helpers ---------------------------------------------------------------------

    private void confirm(String title, String header, String text, String action, Runnable onConfirm) {
        ButtonType yes = new ButtonType(action, ButtonBar.ButtonData.OK_DONE);
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, text, yes, ButtonType.CANCEL);
        alert.setTitle(title);
        alert.setHeaderText(header);
        alert.getDialogPane().setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
        style(alert);
        Optional<ButtonType> answer = alert.showAndWait();
        if (answer.isPresent() && answer.get() == yes) onConfirm.run();
    }

    private void fail(String message) {
        status.setText(message);
        Alert alert = new Alert(Alert.AlertType.ERROR, message, ButtonType.CLOSE);
        alert.setHeaderText("That did not work");
        style(alert);
        alert.show();
    }

    private void updateButtons() {
        BackupInfo info = table.getSelectionModel().getSelectedItem();
        boolean valid = info != null && info.valid();
        restoreButton.setDisable(!valid);
        restoreNewButton.setDisable(!valid);
        exportButton.setDisable(info == null);
        deleteButton.setDisable(info == null);
    }

    private <T> void background(Callable<T> work, Consumer<T> onSuccess) {
        Task<T> task = new Task<>() {
            @Override
            protected T call() throws Exception {
                return work.call();
            }
        };
        task.setOnSucceeded(e -> onSuccess.accept(task.getValue()));
        task.setOnFailed(e -> {
            Throwable error = task.getException();
            boolean friendly = error instanceof BackupException || error instanceof InstanceException;
            if (!friendly) log.error("Unexpected error in the backups window", error);
            fail(friendly ? error.getMessage() : "Something went wrong. Details were written to the log file.");
        });
        Thread thread = new Thread(task, "backups");
        thread.setDaemon(true);
        thread.start();
    }

    private void style(Dialog<?> d) {
        if (anchor.getScene() != null) {
            d.getDialogPane().getStylesheets().addAll(anchor.getScene().getStylesheets());
            d.initOwner(anchor.getScene().getWindow());
        }
    }

    private static <T> TableColumn<T, String> column(String title, double width, Function<T, String> getter) {
        TableColumn<T, String> col = new TableColumn<>(title);
        col.setPrefWidth(width);
        col.setCellValueFactory(cell -> new ReadOnlyStringWrapper(getter.apply(cell.getValue())));
        return col;
    }
}
