package app.ui;

import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.function.Consumer;

/** Opens a folder in the system file manager (Explorer on Windows). */
final class FolderOpener {

    private static final Logger log = LoggerFactory.getLogger("UI");

    private FolderOpener() { }

    static void open(Path folder, Consumer<String> showMessage) {
        if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
            showMessage.accept("Folder: " + folder);
            return;
        }
        Thread thread = new Thread(() -> {
            try {
                Desktop.getDesktop().open(folder.toFile());
            } catch (IOException | RuntimeException e) {
                log.warn("Could not open folder {}", folder, e);
                Platform.runLater(() -> showMessage.accept("Could not open the folder. It is at: " + folder));
            }
        }, "open-folder");
        thread.setDaemon(true);
        thread.start();
    }

    /** Opens a web page in the default browser. */
    static void browse(URI uri, Consumer<String> showMessage) {
        if (uri == null || !"https".equalsIgnoreCase(uri.getScheme())) return;
        if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            showMessage.accept("Open this address in your browser: " + uri);
            return;
        }
        Thread thread = new Thread(() -> {
            try {
                Desktop.getDesktop().browse(uri);
            } catch (IOException | RuntimeException e) {
                log.warn("Could not open {}", uri, e);
                Platform.runLater(() -> showMessage.accept("Could not open the browser. Address: " + uri));
            }
        }, "open-browser");
        thread.setDaemon(true);
        thread.start();
    }
}
