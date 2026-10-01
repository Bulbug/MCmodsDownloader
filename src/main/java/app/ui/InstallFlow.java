package app.ui;

import app.api.ProjectSummary;
import app.api.ProjectVersion;
import app.api.ProviderException;
import app.configuration.AppContext;
import app.instance.FileInstanceRepository;
import app.instance.Instance;
import app.instance.InstanceException;
import app.instance.InstanceService;
import app.mods.DependencyResolver;
import app.mods.InstallException;
import app.mods.InstallPlan;
import app.mods.InstalledContentStore;
import app.mods.ModInstaller;
import app.mods.PlannedMod;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * The "Install to instance" steps: choose instance, review the plan, install with progress.
 * Nothing is written to disk until the user confirms the plan.
 */
final class InstallFlow {

    private static final Logger log = LoggerFactory.getLogger("UI");

    private final AppContext context;
    private final Node anchor;
    private final Consumer<String> status;
    private final FileInstanceRepository repository;
    private final InstanceService instances;

    InstallFlow(AppContext context, Node anchor, Consumer<String> status) {
        this.context = context;
        this.anchor = anchor;
        this.status = status;
        this.repository = new FileInstanceRepository(context.paths().instancesDirFor(context.settings()));
        this.instances = new InstanceService(repository);
    }

    void start(ProjectSummary project, ProjectVersion version) {
        status.accept("Loading your instances...");
        background(instances::list, list -> {
            status.accept("");
            if (list.isEmpty()) {
                info("No instances yet", "Create an instance on the Instances page first, then come back.");
                return;
            }
            chooseInstance(project, version, list);
        });
    }

    // ---- step 1: choose instance ---------------------------------------------------

    private void chooseInstance(ProjectSummary project, ProjectVersion version, List<Instance> list) {
        Dialog<Instance> dialog = new Dialog<>();
        dialog.setTitle("Install " + project.title());
        dialog.setHeaderText("Install " + project.title() + " " + version.versionNumber() + " into which instance?");
        style(dialog);

        ComboBox<Instance> combo = new ComboBox<>();
        combo.getItems().addAll(list);
        combo.setConverter(new StringConverter<>() {
            @Override
            public String toString(Instance i) {
                return i == null ? "" : i.name() + "  (Minecraft " + i.minecraftVersion() + ", " + i.loader() + ")";
            }

            @Override
            public Instance fromString(String s) {
                return null;
            }
        });
        // Pre-select an instance that matches the version if there is one.
        list.stream().filter(i -> version.gameVersions().contains(i.minecraftVersion())
                        && version.loaders().stream().anyMatch(l -> l.equalsIgnoreCase(i.loader())))
                .findFirst().ifPresentOrElse(combo::setValue, () -> combo.setValue(list.get(0)));
        combo.setPrefWidth(420);

        CheckBox preRelease = new CheckBox("Allow beta and alpha versions of required mods");
        Label note = new Label("You will see exactly what will be installed before anything changes.");
        note.getStyleClass().add("page-muted");
        VBox content = new VBox(10, combo, preRelease, note);
        content.setPadding(new Insets(10));
        dialog.getDialogPane().setContent(content);

        ButtonType check = new ButtonType("Check", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(check, ButtonType.CANCEL);
        dialog.setResultConverter(b -> b == check ? combo.getValue() : null);

        Optional<Instance> chosen = dialog.showAndWait();
        if (chosen.isEmpty() || chosen.get() == null) return;
        resolve(project, version, chosen.get(), preRelease.isSelected());
    }

    // ---- step 2: resolve and show the plan -------------------------------------------

    private void resolve(ProjectSummary project, ProjectVersion version, Instance instance, boolean allowPre) {
        status.accept("Checking compatibility and dependencies...");
        Path instanceDir = repository.directoryOf(instance.id());
        background(() -> new DependencyResolver(context.content()).resolve(instance,
                        instanceDir.resolve("game").resolve("mods"),
                        new InstalledContentStore(instanceDir).load(),
                        project.title(), version, allowPre),
                plan -> {
                    status.accept("");
                    showPlan(plan, instanceDir);
                });
    }

    private void showPlan(InstallPlan plan, Path instanceDir) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Install plan");
        dialog.setHeaderText(plan.canInstall() ? "Review before installing into \"" + plan.instance().name() + "\""
                : "This cannot be installed into \"" + plan.instance().name() + "\"");
        style(dialog);

        TextArea text = new TextArea(describe(plan));
        text.setEditable(false);
        text.setWrapText(true);
        text.setPrefSize(560, 320);
        dialog.getDialogPane().setContent(text);

        ButtonType install = new ButtonType("Install", ButtonBar.ButtonData.OK_DONE);
        if (plan.canInstall()) dialog.getDialogPane().getButtonTypes().add(install);
        dialog.getDialogPane().getButtonTypes().add(plan.canInstall() ? ButtonType.CANCEL : ButtonType.CLOSE);

        Optional<ButtonType> answer = dialog.showAndWait();
        if (answer.isPresent() && answer.get() == install) runInstall(plan, instanceDir);
    }

    static String describe(InstallPlan plan) {
        StringBuilder sb = new StringBuilder();
        if (!plan.problems().isEmpty()) {
            sb.append("Problems:\n");
            plan.problems().forEach(p -> sb.append("  - ").append(p).append('\n'));
            sb.append('\n');
        }
        if (!plan.toInstall().isEmpty() && plan.problems().isEmpty()) {
            sb.append("Will be installed:\n");
            for (PlannedMod m : plan.toInstall()) {
                sb.append("  + ").append(m.title()).append(' ').append(m.version().versionNumber())
                        .append(m.chosen() ? "" : "   (required by " + m.neededBy() + ")")
                        .append("\n      file: ").append(m.targetFileName()).append('\n');
            }
            sb.append('\n');
        }
        if (!plan.alreadyInstalled().isEmpty()) {
            sb.append("Already installed (nothing to do):\n");
            plan.alreadyInstalled().forEach(a -> sb.append("  = ").append(a).append('\n'));
            sb.append('\n');
        }
        if (!plan.optional().isEmpty()) {
            sb.append("Optional extras (not installed):\n");
            plan.optional().forEach(o -> sb.append("  ? ").append(o).append('\n'));
            sb.append('\n');
        }
        if (!plan.warnings().isEmpty()) {
            sb.append("Warnings:\n");
            plan.warnings().forEach(w -> sb.append("  ! ").append(w).append('\n'));
        }
        return sb.toString().stripTrailing();
    }

    // ---- step 3: install with progress ---------------------------------------------

    private void runInstall(InstallPlan plan, Path instanceDir) {
        AtomicBoolean cancelled = new AtomicBoolean(false);

        Dialog<Boolean> progress = new Dialog<>();
        progress.setTitle("Installing");
        progress.setHeaderText("Installing into \"" + plan.instance().name() + "\"");
        style(progress);
        Label line = new Label("Starting...");
        ProgressIndicator spinner = new ProgressIndicator();
        spinner.setPrefSize(28, 28);
        HBox box = new HBox(12, spinner, line);
        box.setPadding(new Insets(12));
        progress.getDialogPane().setContent(box);
        progress.getDialogPane().getButtonTypes().add(ButtonType.CANCEL);
        Button cancel = (Button) progress.getDialogPane().lookupButton(ButtonType.CANCEL);
        // Keep the dialog open until the worker has really stopped and undone everything.
        cancel.addEventFilter(ActionEvent.ACTION, e -> {
            cancelled.set(true);
            cancel.setDisable(true);
            line.setText("Cancelling...");
            e.consume();
        });

        Task<Integer> task = new Task<>() {
            @Override
            protected Integer call() {
                return new ModInstaller(context.downloads()).install(plan, instanceDir, cancelled::get,
                        text -> Platform.runLater(() -> {
                            if (!cancelled.get()) line.setText(text);
                        })).size();
            }
        };
        task.setOnSucceeded(e -> {
            progress.setResult(Boolean.TRUE);
            status.accept("Installed " + task.getValue() + " mod" + (task.getValue() == 1 ? "" : "s")
                    + " into \"" + plan.instance().name() + "\".");
        });
        task.setOnFailed(e -> {
            progress.setResult(Boolean.FALSE);
            Throwable error = task.getException();
            String message = error instanceof InstallException ? error.getMessage()
                    : "Something went wrong. Details were written to the log file.";
            if (!(error instanceof InstallException)) log.error("Install failed", error);
            status.accept(message);
            error("Installation did not complete", message);
        });
        Thread thread = new Thread(task, "mod-install");
        thread.setDaemon(true);
        thread.start();
        progress.show();
    }

    // ---- helpers ---------------------------------------------------------------------

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
            boolean friendly = error instanceof ProviderException || error instanceof InstanceException;
            if (!friendly) log.error("Unexpected error while preparing the install", error);
            status.accept("");
            error("That did not work", friendly ? error.getMessage()
                    : "Something went wrong. Details were written to the log file.");
        });
        Thread thread = new Thread(task, "install-prepare");
        thread.setDaemon(true);
        thread.start();
    }

    private void info(String header, String text) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION, text, ButtonType.OK);
        alert.setHeaderText(header);
        style(alert);
        alert.show();
    }

    private void error(String header, String text) {
        Alert alert = new Alert(Alert.AlertType.ERROR, text, ButtonType.CLOSE);
        alert.setHeaderText(header);
        style(alert);
        alert.show();
    }

    private void style(Dialog<?> dialog) {
        if (anchor.getScene() != null) {
            dialog.getDialogPane().getStylesheets().addAll(anchor.getScene().getStylesheets());
            dialog.initOwner(anchor.getScene().getWindow());
        }
    }
}
