package app.ui;

import app.api.ProjectVersion;
import app.backup.BackupPart;
import app.backup.BackupService;
import app.configuration.AppContext;
import app.instance.Instance;
import app.mods.DependencyResolver;
import app.mods.InstallException;
import app.mods.InstallPlan;
import app.mods.InstalledContentStore;
import app.mods.ModInstaller;
import app.mods.UpdateChecker;
import app.mods.InstalledMod;
import app.mods.ManagedMod;
import app.mods.ModManager;
import app.mods.RemovalPlan;
import app.mods.RemovalResult;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ChoiceDialog;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.function.Function;

/** "Installed mods" window for one instance: see what is installed and remove mods safely. */
final class InstalledModsDialog {

    private static final Logger log = LoggerFactory.getLogger("UI");

    private final AppContext context;
    private final Instance instance;
    private final Path instanceDir;
    private final Node anchor;
    private final ModManager manager = new ModManager();

    private final TableView<ManagedMod> table = new TableView<>();
    private final Label status = new Label();
    private final Button removeButton = new Button("Remove...");
    private final Button checkButton = new Button("Check for updates");
    private final Button updateButton = new Button("Update...");
    private final Button versionButton = new Button("Change version...");
    private final CheckBox preRelease = new CheckBox("Include beta and alpha");
    private final Map<String, UpdateChecker.Candidate> updates = new HashMap<>();
    private boolean busy;
    private final Dialog<Void> dialog = new Dialog<>();

    InstalledModsDialog(AppContext context, Instance instance, Path instanceDir, Node anchor) {
        this.context = context;
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
        table.getColumns().add(column("File", 200, ManagedMod::fileName));
        table.getColumns().add(column("Update", 130, m -> {
            UpdateChecker.Candidate c = m.projectId() == null ? null : updates.get(m.projectId());
            return c == null ? "" : "-> " + c.latest().versionNumber();
        }));
        table.getColumns().add(column("Size", 80, m -> m.fileExists() ? Formats.bytes(m.sizeBytes()) : "-"));
        table.setPrefSize(940, 340);
        table.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> updateButtons());
        updateButtons();

        Button refresh = new Button("Refresh");
        Button openMods = new Button("Open mods folder");
        Button openRemoved = new Button("Open removed files");
        removeButton.setOnAction(e -> onRemove());
        checkButton.setOnAction(e -> onCheckUpdates());
        updateButton.setOnAction(e -> onUpdate());
        versionButton.setOnAction(e -> onChangeVersion());
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

        VBox content = new VBox(8, new HBox(8, removeButton, refresh, openMods, openRemoved),
                new HBox(8, checkButton, preRelease, updateButton, versionButton), table, note, status);
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

    private void updateButtons() {
        ManagedMod m = table.getSelectionModel().getSelectedItem();
        boolean tracked = m != null && m.tracked() && m.fileExists();
        removeButton.setDisable(busy || m == null);
        checkButton.setDisable(busy);
        updateButton.setDisable(busy || !tracked || m.projectId() == null || !updates.containsKey(m.projectId()));
        versionButton.setDisable(busy || !tracked);
    }

    private void setBusy(boolean value) {
        busy = value;
        updateButtons();
    }

    private void onCheckUpdates() {
        status.setText("Checking for updates...");
        setBusy(true);
        background(() -> new UpdateChecker(context.content()).check(instanceDir, instance, preRelease.isSelected()),
                report -> {
                    updates.clear();
                    for (UpdateChecker.Candidate c : report.updates()) updates.put(c.installed().projectId(), c);
                    table.refresh();
                    status.setText(report.updates().isEmpty()
                            ? "Everything checked is up to date."
                            : report.updates().size() + " update" + (report.updates().size() == 1 ? "" : "s")
                            + " available. Select a mod and click Update...");
                    if (!report.notChecked().isEmpty()) {
                        status.setText(status.getText() + "  (" + report.notChecked().size()
                                + " could not be checked)");
                    }
                    setBusy(false);
                });
    }

    private void onUpdate() {
        ManagedMod selected = table.getSelectionModel().getSelectedItem();
        if (selected == null || selected.projectId() == null) return;
        UpdateChecker.Candidate candidate = updates.get(selected.projectId());
        if (candidate != null) planChange(candidate.installed(), candidate.latest(), preRelease.isSelected());
    }

    /** Lets the user pick any compatible version: newer, or older to roll back. */
    private void onChangeVersion() {
        ManagedMod selected = table.getSelectionModel().getSelectedItem();
        if (selected == null || selected.projectId() == null) return;
        InstalledMod current = new InstalledContentStore(instanceDir).load().stream()
                .filter(m -> m.projectId().equals(selected.projectId())).findFirst().orElse(null);
        if (current == null) return;

        status.setText("Loading versions...");
        setBusy(true);
        background(() -> context.content().versions(current.projectId(), instance.minecraftVersion(),
                instance.loader().toLowerCase()), versions -> {
            setBusy(false);
            status.setText("");
            List<ProjectVersion> choices = versions.stream().filter(v -> !v.id().equals(current.versionId())).toList();
            if (choices.isEmpty()) {
                status.setText("There is no other version of " + current.title() + " for Minecraft "
                        + instance.minecraftVersion() + " with " + instance.loader() + ".");
                return;
            }
            ChoiceDialog<ProjectVersion> chooser = new ChoiceDialog<>(choices.get(0), choices);
            chooser.setTitle("Change version");
            chooser.setHeaderText("Installed: " + current.title() + " " + current.versionNumber()
                    + "\nChoose the version to switch to (newest first):");
            chooser.setContentText("Version:");
            chooser.getDialogPane().getStylesheets().addAll(anchor.getScene().getStylesheets());
            chooser.initOwner(dialog.getDialogPane().getScene().getWindow());
            // Show "2.0.1 (release, 2026-05-01)" instead of the raw record text.
            @SuppressWarnings("unchecked")
            javafx.scene.control.ComboBox<ProjectVersion> combo =
                    (javafx.scene.control.ComboBox<ProjectVersion>) chooser.getDialogPane().lookup(".combo-box");
            if (combo != null) {
                javafx.util.StringConverter<ProjectVersion> converter = new javafx.util.StringConverter<>() {
                    @Override
                    public String toString(ProjectVersion v) {
                        return v == null ? "" : v.versionNumber() + "  (" + v.versionType() + ", "
                                + (v.datePublished() == null ? "?" : v.datePublished().substring(0, Math.min(10, v.datePublished().length())))
                                + ")";
                    }

                    @Override
                    public ProjectVersion fromString(String s) {
                        return null;
                    }
                };
                combo.setConverter(converter);
            }
            chooser.showAndWait().ifPresent(v -> planChange(current, v, true));
        });
    }

    private void planChange(InstalledMod current, ProjectVersion target, boolean allowPre) {
        status.setText("Checking compatibility and dependencies...");
        setBusy(true);
        background(() -> new DependencyResolver(context.content()).resolveUpdate(instance,
                        instanceDir.resolve("game").resolve("mods"), new InstalledContentStore(instanceDir).load(),
                        current, current.title(), target, allowPre),
                plan -> {
                    setBusy(false);
                    status.setText("");
                    confirmChange(plan, current, target);
                });
    }

    private void confirmChange(InstallPlan plan, InstalledMod current, ProjectVersion target) {
        Dialog<ButtonType> confirm = new Dialog<>();
        confirm.setTitle("Change version");
        confirm.setHeaderText(plan.canInstall()
                ? "Replace " + current.title() + " " + current.versionNumber() + " with " + target.versionNumber() + "?"
                : "Cannot change the version of " + current.title());
        confirm.getDialogPane().getStylesheets().addAll(anchor.getScene().getStylesheets());
        confirm.initOwner(dialog.getDialogPane().getScene().getWindow());
        String older = target.datePublished() != null && current.publishedAt() != null
                && target.datePublished().compareTo(current.publishedAt()) < 0
                ? "This is an OLDER version than the one you have.\n\n" : "";
        TextArea text = new TextArea(older + InstallFlow.describe(plan)
                + (plan.canInstall() ? "\n\nThe current file is moved to the .removed folder, so you can restore it by hand." : ""));
        text.setEditable(false);
        text.setWrapText(true);
        text.setPrefSize(560, 280);
        CheckBox backupFirst = new CheckBox("Back up this instance's mods and config first");
        backupFirst.setSelected(true);
        VBox confirmContent = new VBox(10, text);
        if (plan.canInstall()) confirmContent.getChildren().add(backupFirst);
        confirm.getDialogPane().setContent(confirmContent);
        ButtonType apply = new ButtonType("Replace", ButtonBar.ButtonData.OK_DONE);
        if (plan.canInstall()) confirm.getDialogPane().getButtonTypes().add(apply);
        confirm.getDialogPane().getButtonTypes().add(plan.canInstall() ? ButtonType.CANCEL : ButtonType.CLOSE);

        Optional<ButtonType> answer = confirm.showAndWait();
        if (answer.isEmpty() || answer.get() != apply) return;

        boolean makeBackup = backupFirst.isSelected();
        setBusy(true);
        Task<Integer> task = new Task<>() {
            @Override
            protected Integer call() {
                if (makeBackup) {
                    Platform.runLater(() -> status.setText("Backing up first..."));
                    new BackupService(context.paths().backupsDir()).create(instance, instanceDir,
                            java.util.EnumSet.of(BackupPart.MODS, BackupPart.CONFIG), false,
                            "Before changing " + current.title(), () -> false, (done, total) -> { });
                }
                return new ModInstaller(context.downloads()).update(plan, current, instanceDir, () -> false,
                        msg -> Platform.runLater(() -> status.setText(msg))).size();
            }
        };
        task.setOnSucceeded(e -> {
            updates.remove(current.projectId());
            status.setText("Updated " + current.title() + " to " + target.versionNumber() + ".");
            setBusy(false);
            load();
        });
        task.setOnFailed(e -> {
            setBusy(false);
            Throwable error = task.getException();
            String message = error instanceof InstallException || error instanceof app.backup.BackupException ? error.getMessage()
                    : "Something went wrong. Details were written to the log file.";
            if (!(error instanceof InstallException || error instanceof app.backup.BackupException)) log.error("Update failed", error);
            status.setText(message);
            Alert alert = new Alert(Alert.AlertType.ERROR, message, ButtonType.CLOSE);
            alert.setHeaderText("The version was not changed");
            alert.getDialogPane().getStylesheets().addAll(anchor.getScene().getStylesheets());
            alert.show();
        });
        Thread thread = new Thread(task, "mod-update");
        thread.setDaemon(true);
        thread.start();
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
                    || error instanceof app.instance.InstanceException
                    || error instanceof app.api.ProviderException;
            if (!friendly) log.error("Unexpected error in installed mods window", error);
            String message = friendly || error instanceof app.api.ProviderException ? error.getMessage()
                    : "Something went wrong. Details were written to the log file.";
            setBusy(false);
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
