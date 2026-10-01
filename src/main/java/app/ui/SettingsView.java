package app.ui;

import app.configuration.AppContext;
import app.configuration.AppSettings;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/** Settings page. Values are validated before they are saved. */
public final class SettingsView {

    private static final Logger log = LoggerFactory.getLogger("Settings");

    private final AppContext context;
    private final VBox root = new VBox(14);

    private final TextField minecraftDir = new TextField();
    private final TextField instanceDir = new TextField();
    private final Spinner<Integer> maxDownloads;
    private final ComboBox<String> language = new ComboBox<>();
    private final Spinner<Integer> cacheMinutes;
    private final TextField contact = new TextField();
    private final Label message = new Label();

    public SettingsView(AppContext context) {
        this.context = context;
        AppSettings s = context.settings();

        Label title = new Label("Settings");
        title.getStyleClass().add("page-title");

        minecraftDir.setText(s.minecraftDirectory == null ? "" : s.minecraftDirectory);
        minecraftDir.setPromptText("Leave empty to auto-detect");
        instanceDir.setText(s.instanceDirectory == null ? "" : s.instanceDirectory);
        instanceDir.setPromptText("Leave empty to use the default folder");

        maxDownloads = new Spinner<>(1, 10, s.maxConcurrentDownloads);
        cacheMinutes = new Spinner<>(1, 1440, s.cacheMinutes);
        contact.setText(s.apiContact == null ? "" : s.apiContact);
        contact.setPromptText("Optional: email or GitHub name");
        language.getItems().addAll("en", "fil");
        language.setValue(s.language);

        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(12);
        int row = 0;
        addRow(grid, row++, "Minecraft folder", folderField(minecraftDir));
        addRow(grid, row++, "Instances folder", folderField(instanceDir));
        addRow(grid, row++, "Simultaneous downloads", maxDownloads);
        addRow(grid, row++, "Search cache (minutes)", cacheMinutes);
        addRow(grid, row++, "Contact for Modrinth", contact);
        addRow(grid, row, "Language", language);

        Label note = new Label("The download limit applies right away. "
                + "Changing the Minecraft folder takes effect when you press Rescan on Home.");
        note.getStyleClass().add("page-muted");
        note.setWrapText(true);

        Button save = new Button("Save");
        save.setOnAction(e -> save());
        message.getStyleClass().add("page-text");

        root.getChildren().addAll(title, grid, note, new HBox(12, save, message));
        root.setPadding(new Insets(0, 8, 0, 0));
    }

    public Node node() {
        return root;
    }

    private static void addRow(GridPane grid, int row, String label, Node field) {
        Label l = new Label(label);
        l.getStyleClass().add("page-text");
        grid.add(l, 0, row);
        grid.add(field, 1, row);
        GridPane.setHgrow(field, Priority.ALWAYS);
    }

    private Node folderField(TextField field) {
        Button browse = new Button("Browse...");
        browse.setOnAction(e -> {
            DirectoryChooser chooser = new DirectoryChooser();
            File current = new File(field.getText().trim());
            if (current.isDirectory()) chooser.setInitialDirectory(current);
            File chosen = chooser.showDialog(browse.getScene().getWindow());
            if (chosen != null) field.setText(chosen.getAbsolutePath());
        });
        field.setPrefColumnCount(38);
        HBox box = new HBox(8, field, browse);
        HBox.setHgrow(field, Priority.ALWAYS);
        return box;
    }

    private void save() {
        String mc = minecraftDir.getText().trim();
        String inst = instanceDir.getText().trim();

        if (!mc.isEmpty()) {
            Path p = parse(mc);
            if (p == null || !Files.isDirectory(p)) {
                error("The Minecraft folder does not exist.");
                return;
            }
        }
        if (!inst.isEmpty() && parse(inst) == null) {
            error("The Instances folder is not a valid path.");
            return;
        }

        AppSettings s = context.settings();
        s.minecraftDirectory = mc.isEmpty() ? null : mc;
        s.instanceDirectory = inst.isEmpty() ? null : inst;
        s.maxConcurrentDownloads = maxDownloads.getValue();
        s.language = language.getValue();
        s.cacheMinutes = cacheMinutes.getValue();
        String contactText = contact.getText().trim();
        s.apiContact = contactText.isEmpty() ? null : contactText;

        try {
            context.settingsService().save(s);
            context.downloads().setMaxConcurrent(s.maxConcurrentDownloads);
            message.setText("Saved.");
        } catch (UncheckedIOException ex) {
            log.error("Could not save settings", ex);
            error("Could not save settings. See the log for details.");
        }
    }

    private void error(String text) {
        message.setText(text);
    }

    private static Path parse(String raw) {
        try {
            return Path.of(raw);
        } catch (InvalidPathException e) {
            return null;
        }
    }
}
