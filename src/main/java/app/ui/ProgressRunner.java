package app.ui;

import app.backup.BackupException;
import app.backup.ProgressListener;
import app.instance.InstanceException;
import app.mods.InstallException;
import javafx.concurrent.Task;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.VBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Runs long work in the background behind a small window with a progress bar and a Cancel button. */
final class ProgressRunner {

    private static final Logger log = LoggerFactory.getLogger("UI");

    interface Work<T> {
        T run(BooleanSupplier cancelled, ProgressListener progress) throws Exception;
    }

    private ProgressRunner() { }

    /**
     * @param onFailure receives a message that is safe to show to the user
     */
    static <T> void run(Node anchor, String title, String header, Work<T> work,
                        Consumer<T> onSuccess, Consumer<String> onFailure) {
        AtomicBoolean cancelled = new AtomicBoolean(false);

        Dialog<Boolean> dialog = new Dialog<>();
        dialog.setTitle(title);
        dialog.setHeaderText(header);
        if (anchor.getScene() != null) {
            dialog.getDialogPane().getStylesheets().addAll(anchor.getScene().getStylesheets());
            dialog.initOwner(anchor.getScene().getWindow());
        }
        ProgressBar bar = new ProgressBar(ProgressBar.INDETERMINATE_PROGRESS);
        bar.setPrefWidth(380);
        Label line = new Label("Working...");
        VBox box = new VBox(10, bar, line);
        box.setPadding(new Insets(12));
        dialog.getDialogPane().setContent(box);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CANCEL);
        Button cancel = (Button) dialog.getDialogPane().lookupButton(ButtonType.CANCEL);
        // Keep the window open until the work has really stopped and undone itself.
        cancel.addEventFilter(ActionEvent.ACTION, e -> {
            cancelled.set(true);
            cancel.setDisable(true);
            line.setText("Cancelling...");
            e.consume();
        });

        Task<T> task = new Task<>() {
            @Override
            protected T call() throws Exception {
                return work.run(cancelled::get, (done, total) -> {
                    if (total > 0) updateProgress(done, total);
                    else updateProgress(-1, -1);
                });
            }
        };
        bar.progressProperty().bind(task.progressProperty());
        task.progressProperty().addListener((o, a, b) -> {
            if (!cancelled.get() && b.doubleValue() >= 0) line.setText(Math.round(b.doubleValue() * 100) + "%");
        });

        task.setOnSucceeded(e -> {
            dialog.setResult(Boolean.TRUE);
            onSuccess.accept(task.getValue());
        });
        task.setOnFailed(e -> {
            dialog.setResult(Boolean.FALSE);
            Throwable error = task.getException();
            boolean friendly = error instanceof BackupException || error instanceof InstanceException
                    || error instanceof InstallException;
            if (!friendly) log.error("Background work failed", error);
            onFailure.accept(friendly ? error.getMessage()
                    : "Something went wrong. Details were written to the log file.");
        });

        Thread thread = new Thread(task, "progress-work");
        thread.setDaemon(true);
        thread.start();
        dialog.show();
    }
}
