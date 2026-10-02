package app.ui;

import app.instance.Instance;
import app.mods.InstallException;
import app.mods.InstalledMod;
import app.mods.ManagedMod;
import app.mods.ModManager;
import app.mods.RemovalPlan;
import app.mods.RemovalResult;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.function.Function;

/** "Installed mods" window for one instance: see what is installed and remove mods safely. */
final class InstalledModsDialog {

    private static final Logger log = LoggerFactory.getLogger("UI");

    private final Instance instance;
    private final Path instanceDir;
    private final Node anchor;
    private final ModManager manager = new ModManager();

    private final TableView<ManagedMod> table = new TableView<>();
    private final Label status = new Label();
    private final Button removeButton = new Button("Remove...");
    private final Dialog<Void> dialog = new Dialog<>();

    InstalledModsDialog(Instance instance, Path instanceDir, Node anchor) {
        this.instance = instance;
        this.instanceDir = instanceDir;
        this.anchor = anchor;
    }

    void show() {
        dialog.setTitle("Installed mods");
        dialog.setHeaderText(instance.name() + "  (Minecraft " + instance.minecraftVersion() + ", " + instance.loader() + ")");
        dialog.setResizable(true);
        if (anchor.getScene() != null) {
            dialog.getDialogPane().getStylesheets().addAll(anchor.getScene().getStylesheets());
            dialog.initOwner(anchor.getScene().getWindow());
        }

        table.setPlaceholder(new Label("No mods in this instance yet."));
        table.getColumns().add(column("Name", 200, ManagedMod::title));
        table.getColumns().add(column("Version", 110, ManagedMod::versionNumber));
        table.getColumns().add(column("Source", 170, InstalledModsDialog::source));
        table.getColumns().add(column("File", 220, ManagedMod::fileName));
        table.getColumns().add(column("Size", 80, m -> m.fileExists() ? Formats.bytes(m.sizeBytes()) : "-"));
        table.setPrefSize(820, 340);
        table.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> removeButton.setDisable(b == null));
        removeButton.setDisable(true);

        Button refresh = new Button("Refresh");
        Button openMods = new Button("Open mods folder");
        Button openRemoved = new Button("Open removed files");
        removeButton.setOnAction(e -> onRemove());
        refresh.setOnAction(e -> load());
        openMods.setOnAction(e -> FolderOpener.open(instanceDir.resolve("game").resolve("mods"), status::setText));
        openRemoved.setOnAction(e -> {
            Path removed = instanceDir.resolve(ModManager.REMOVED_FOLDER);
            if (Files.isDirectory(removed)) FolderOpener.open(removed, status::setText);
            else status.setText("Nothing has been removed from this instance yet.");
        });

        status.getStyleClass().add("page-text");
        Label note = new Label("Removed mods are moved to a \".removed\" folder, not deleted. "
                + "To restore one, move the file back into the mods folder.");
        note.getStyleClass().add("page-muted");
        note.setWrapText(true);

        VBox content = new VBox(8, new HBox(8, removeButton, refresh, openMods, openRemoved), table, note, status);
        content.setPadding(new Insets(6));
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        dialog.show();
        load();
    }

    private static String source(ManagedMod m) {
        if (!m.tracked()) return "Not installed by this app";
        if (!m.fileExists()) return "File missing";
        return m.explicit() ? "Chosen by you" : "Dependency";
    }

    private void load() {
        status.setText("Loading...");
        background(() -> manager.list(instanceDir), mods -> {
            table.getItems().setAll(mods);
            long missing = mods.stream().filter(m -> m.tracked() && !m.fileExists()).count();
            status.setText(mods.size() + " mod" + (mods.size() == 1 ? "" : "s")
                    + (missing > 0 ? "  |  " + missing + " listed but missing from the folder" : ""));
        });
    }

    private void onRemove() {
        ManagedMod selected = table.getSelectionModel().getSelectedItem();
        if (selected == null) return;
        background(() -> manager.planRemoval(instanceDir, selected), plan -> confirmAndRemove(plan));
    }

    private void confirmAndRemove(RemovalPlan plan) {
        ManagedMod target = plan.target();
        StringBuilder sb = new StringBuilder();
        if (!target.fileExists()) {
            sb.append("The file is already gone from the mods folder. Only the entry in this list will be cleared.\n");
        } else {
            sb.append("\"").append(target.fileName()).append("\" will be moved to the instance's .removed folder.\n");
        }
        if (!target.tracked()) sb.append("\nThis file was not installed by this app.\n");
        if (plan.hasDependents()) {
            sb.append("\nWARNING - these mods need ").append(target.title())
                    .append(" and may stop working or crash the game without it:\n");
            for (InstalledMod m : plan.dependents()) sb.append("  - ").append(m.title()).append('\n');
        }

        Dialog<ButtonType> confirm = new Dialog<>();
        confirm.setTitle("Remove mod");
        confirm.setHeaderText("Remove " + target.title() + "?");
        if (anchor.getScene() != null) {
            confirm.getDialogPane().getStylesheets().addAll(anchor.getScene().getStylesheets());
            confirm.initOwner(dialog.getDialogPane().getScene().getWindow());
        }
        TextArea text = new TextArea(sb.toString().stripTrailing());
        text.setEditable(false);
        text.setWrapText(true);
        text.setPrefSize(520, 180);
        VBox box = new VBox(10, text);

        CheckBox alsoUnused = new CheckBox();
        if (!plan.unusedDependencies().isEmpty()) {
            alsoUnused.setText("Also remove " + plan.unusedDependencies().size() + " dependency mod"
                    + (plan.unusedDependencies().size() == 1 ? "" : "s") + " that nothing else needs: "
                    + String.join(", ", plan.unusedDependencies().stream().map(InstalledMod::title).toList()));
            alsoUnused.setWrapText(true);
            alsoUnused.setMaxWidth(520);
            box.getChildren().add(alsoUnused);
        }
        confirm.getDialogPane().setContent(box);
        ButtonType remove = new ButtonType(plan.hasDependents() ? "Remove anyway" : "Remove", ButtonBar.ButtonData.OK_DONE);
        confirm.getDialogPane().getButtonTypes().addAll(remove, ButtonType.CANCEL);

        Optional<ButtonType> answer = confirm.showAndWait();
        if (answer.isEmpty() || answer.get() != remove) return;

        boolean includeUnused = alsoUnused.isSelected();
        status.setText("Removing...");
        background(() -> manager.remove(instanceDir, plan, includeUnused), (RemovalResult result) -> {
            status.setText("Removed " + result.movedFiles().size() + " file"
                    + (result.movedFiles().size() == 1 ? "" : "s") + ".");
            load();
        });
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
            boolean friendly = error instanceof InstallException
                    || error instanceof app.instance.InstanceException;
            if (!friendly) log.error("Unexpected error in installed mods window", error);
            String message = friendly ? error.getMessage()
                    : "Something went wrong. Details were written to the log file.";
            status.setText(message);
            Alert alert = new Alert(Alert.AlertType.ERROR, message, ButtonType.CLOSE);
            alert.setHeaderText("That did not work");
            if (anchor.getScene() != null) alert.getDialogPane().getStylesheets().addAll(anchor.getScene().getStylesheets());
            alert.show();
        });
        Thread thread = new Thread(task, "installed-mods");
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
