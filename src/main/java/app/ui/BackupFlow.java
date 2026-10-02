package app.ui;

import app.backup.BackupInfo;
import app.backup.BackupPart;
import app.backup.BackupService;
import app.configuration.AppContext;
import app.instance.Instance;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;

import java.nio.file.Path;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/** Backup dialogs that start from an instance: "Backup..." and "Backup and delete". */
final class BackupFlow {

    private record Choice(Set<BackupPart> parts, boolean entire, String note) { }

    private final AppContext context;
    private final Node anchor;
    private final Consumer<String> status;
    private final BackupService backups;

    BackupFlow(AppContext context, Node anchor, Consumer<String> status) {
        this.context = context;
        this.anchor = anchor;
        this.status = status;
        this.backups = new BackupService(context.paths().backupsDir());
    }

    /** Asks what to include, then creates the backup with a progress window. */
    void createBackup(Instance instance, Path instanceDir) {
        Dialog<Choice> dialog = new Dialog<>();
        dialog.setTitle("Back up instance");
        dialog.setHeaderText("Back up \"" + instance.name() + "\"");
        style(dialog);

        Map<BackupPart, CheckBox> boxes = new EnumMap<>(BackupPart.class);
        VBox parts = new VBox(6);
        for (BackupPart part : BackupPart.values()) {
            CheckBox box = new CheckBox(part.label());
            box.setSelected(true);
            boxes.put(part, box);
            parts.getChildren().add(box);
        }
        CheckBox entire = new CheckBox("Entire instance (everything, including logs and screenshots)");
        entire.selectedProperty().addListener((o, a, selected) -> boxes.values().forEach(b -> b.setDisable(selected)));
        TextField note = new TextField();
        note.setPromptText("Optional note, for example \"before updating mods\"");

        Label hint = new Label("Backups are saved outside the instance, so they survive deleting it. "
                + "Worlds can be large and may take a while.");
        hint.setWrapText(true);
        hint.setMaxWidth(420);
        hint.getStyleClass().add("page-muted");
        VBox content = new VBox(10, parts, entire, note, hint);
        content.setPadding(new Insets(10));
        dialog.getDialogPane().setContent(content);

        ButtonType create = new ButtonType("Create backup", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(create, ButtonType.CANCEL);
        dialog.setResultConverter(b -> {
            if (b != create) return null;
            Set<BackupPart> chosen = EnumSet.noneOf(BackupPart.class);
            boxes.forEach((part, box) -> {
                if (box.isSelected()) chosen.add(part);
            });
            return new Choice(chosen, entire.isSelected(), note.getText());
        });

        Optional<Choice> choice = dialog.showAndWait();
        if (choice.isEmpty()) return;
        Choice c = choice.get();
        if (!c.entire() && c.parts().isEmpty()) {
            status.accept("Choose at least one thing to back up.");
            return;
        }
        ProgressRunner.run(anchor, "Backup", "Backing up \"" + instance.name() + "\"",
                (cancelled, progress) -> backups.create(instance, instanceDir, c.parts(), c.entire(), c.note(), cancelled, progress),
                info -> status.accept("Backup created (" + Formats.bytes(info.sizeBytes()) + ")."),
                message -> status.accept(message));
    }

    /**
     * Backs up the whole instance and, only if that worked, runs {@code afterBackup}
     * (the caller deletes the instance there). A failed backup means nothing is deleted.
     */
    void backupThen(Instance instance, Path instanceDir, Runnable afterBackup) {
        ProgressRunner.run(anchor, "Backup", "Backing up \"" + instance.name() + "\" before deleting",
                (cancelled, progress) -> backups.create(instance, instanceDir, EnumSet.noneOf(BackupPart.class), true,
                        "Automatic backup before deleting", cancelled, progress),
                (BackupInfo info) -> {
                    status.accept("Backup saved (" + Formats.bytes(info.sizeBytes()) + "). Deleting the instance...");
                    afterBackup.run();
                },
                message -> {
                    status.accept(message);
                    Alert alert = new Alert(Alert.AlertType.ERROR,
                            message + "\n\nThe instance was NOT deleted.", ButtonType.CLOSE);
                    alert.setHeaderText("The backup failed");
                    style(alert);
                    alert.show();
                });
    }

    private void style(Dialog<?> dialog) {
        if (anchor.getScene() != null) {
            dialog.getDialogPane().getStylesheets().addAll(anchor.getScene().getStylesheets());
            dialog.initOwner(anchor.getScene().getWindow());
        }
    }
}
