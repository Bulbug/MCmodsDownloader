package app.ui;

import app.configuration.AppContext;
import app.downloads.Checksum;
import app.downloads.DownloadException;
import app.downloads.DownloadManager;
import app.downloads.DownloadRequest;
import app.downloads.DownloadSnapshot;
import app.downloads.DownloadState;
import app.downloads.FileNames;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.cell.ProgressBarTableCell;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.function.Function;

/** Downloads page: queue with progress, speed, and pause/resume/cancel/retry. */
public final class DownloadsView {

    private final AppContext context;
    private final DownloadManager manager;
    private final VBox box = new VBox(10);
    private final TableView<DownloadSnapshot> table = new TableView<>();
    private final Label status = new Label();

    private final Button addButton = new Button("Add download");
    private final Button pauseButton = new Button("Pause");
    private final Button resumeButton = new Button("Resume");
    private final Button cancelButton = new Button("Cancel");
    private final Button retryButton = new Button("Retry");
    private final Button clearButton = new Button("Clear finished");
    private final Button folderButton = new Button("Open downloads folder");

    public DownloadsView(AppContext context) {
        this.context = context;
        this.manager = context.downloads();

        Label title = new Label("Downloads");
        title.getStyleClass().add("page-title");
        Label where = new Label("Saved to: " + context.paths().downloadsDir());
        where.getStyleClass().add("page-muted");
        status.getStyleClass().add("page-text");

        table.setPlaceholder(new Label("No downloads yet."));
        table.getColumns().add(column("Name", 230, DownloadSnapshot::name));
        table.getColumns().add(column("Status", 95, s -> s.state().label()));

        TableColumn<DownloadSnapshot, Double> progress = new TableColumn<>("Progress");
        progress.setPrefWidth(150);
        progress.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().fraction()));
        progress.setCellFactory(ProgressBarTableCell.forTableColumn());
        table.getColumns().add(progress);

        table.getColumns().add(column("Size", 140, s -> s.totalBytes() > 0
                ? Formats.bytes(s.downloadedBytes()) + " / " + Formats.bytes(s.totalBytes())
                : Formats.bytes(s.downloadedBytes())));
        table.getColumns().add(column("Speed", 90, s -> s.state() == DownloadState.DOWNLOADING
                ? Formats.speed(s.bytesPerSecond()) : "-"));
        table.getColumns().add(column("Time left", 80, s -> Formats.eta(s.etaSeconds())));
        table.getColumns().add(column("Info", 320, DownloadSnapshot::message));
        table.setPrefHeight(360);

        HBox buttons = new HBox(8, addButton, pauseButton, resumeButton, cancelButton, retryButton,
                clearButton, folderButton);

        addButton.setOnAction(e -> onAdd());
        pauseButton.setOnAction(e -> act(id -> manager.pause(id)));
        resumeButton.setOnAction(e -> act(id -> manager.resume(id)));
        cancelButton.setOnAction(e -> act(id -> manager.cancel(id)));
        retryButton.setOnAction(e -> act(id -> manager.retry(id)));
        clearButton.setOnAction(e -> {
            manager.clearFinished();
            refresh();
        });
        folderButton.setOnAction(e -> FolderOpener.open(context.paths().downloadsDir(), status::setText));
        table.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> updateButtons());

        box.getChildren().addAll(title, where, buttons, table, status);

        // Cheap timer: re-reads the snapshots twice a second. Downloads run on worker threads.
        Timeline timer = new Timeline(new KeyFrame(Duration.millis(500), e -> refresh()));
        timer.setCycleCount(Timeline.INDEFINITE);
        timer.play();
        refresh();
    }

    public Node node() {
        return box;
    }

    private void onAdd() {
        Dialog<String[]> dialog = new Dialog<>();
        dialog.setTitle("Add download");
        dialog.setHeaderText("Download a file from a secure (https) address");
        if (box.getScene() != null) {
            dialog.getDialogPane().getStylesheets().addAll(box.getScene().getStylesheets());
            dialog.initOwner(box.getScene().getWindow());
        }

        TextField url = new TextField();
        url.setPromptText("https://...");
        url.setPrefColumnCount(40);
        TextField name = new TextField();
        name.setPromptText("Optional. Taken from the address if empty");
        TextField checksum = new TextField();
        checksum.setPromptText("Optional SHA-1, SHA-256 or SHA-512");

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(10));
        grid.addRow(0, new Label("Address"), url);
        grid.addRow(1, new Label("File name"), name);
        grid.addRow(2, new Label("Checksum"), checksum);
        dialog.getDialogPane().setContent(grid);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        dialog.setResultConverter(b -> b == ButtonType.OK
                ? new String[]{url.getText(), name.getText(), checksum.getText()} : null);

        dialog.showAndWait().ifPresent(r -> {
            try {
                URI uri = new URI(r[0].trim());
                String fileName = r[1].isBlank() ? FileNames.fromUrl(uri) : FileNames.sanitize(r[1].trim());
                Checksum sum = r[2].isBlank() ? null : Checksum.parse(r[2]);
                Path destination = context.paths().downloadsDir().resolve(fileName);
                manager.enqueue(DownloadRequest.of(uri, destination, sum));
                status.setText("Added \"" + fileName + "\".");
                refresh();
            } catch (URISyntaxException e) {
                error("The web address is not valid.");
            } catch (IllegalArgumentException | DownloadException e) {
                error(e.getMessage());
            }
        });
    }

    private void act(java.util.function.LongConsumer action) {
        DownloadSnapshot selected = table.getSelectionModel().getSelectedItem();
        if (selected == null) return;
        action.accept(selected.id());
        refresh();
    }

    private void refresh() {
        DownloadSnapshot selected = table.getSelectionModel().getSelectedItem();
        long selectedId = selected == null ? -1 : selected.id();

        table.getItems().setAll(manager.snapshots());
        if (selectedId >= 0) {
            for (DownloadSnapshot s : table.getItems()) {
                if (s.id() == selectedId) {
                    table.getSelectionModel().select(s);
                    break;
                }
            }
        }
        updateButtons();
    }

    private void updateButtons() {
        DownloadSnapshot s = table.getSelectionModel().getSelectedItem();
        DownloadState st = s == null ? null : s.state();
        pauseButton.setDisable(st != DownloadState.DOWNLOADING && st != DownloadState.QUEUED);
        resumeButton.setDisable(st != DownloadState.PAUSED);
        cancelButton.setDisable(st == null || st == DownloadState.COMPLETED
                || st == DownloadState.CANCELLED || st == DownloadState.VERIFYING);
        retryButton.setDisable(st != DownloadState.FAILED && st != DownloadState.CANCELLED);
    }

    private void error(String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR, message, ButtonType.CLOSE);
        alert.setTitle("Downloads");
        alert.setHeaderText("That did not work");
        if (box.getScene() != null) {
            alert.getDialogPane().getStylesheets().addAll(box.getScene().getStylesheets());
            alert.initOwner(box.getScene().getWindow());
        }
        alert.show();
    }

    private static <T> TableColumn<T, String> column(String title, double width, Function<T, String> getter) {
        TableColumn<T, String> col = new TableColumn<>(title);
        col.setPrefWidth(width);
        col.setCellValueFactory(cell -> new ReadOnlyStringWrapper(getter.apply(cell.getValue())));
        return col;
    }
}
