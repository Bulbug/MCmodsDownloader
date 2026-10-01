package app.ui;

import app.configuration.AppContext;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

public final class MainApp extends Application {

    private static final Logger log = LoggerFactory.getLogger("UI");
    private static AppContext context;

    /** JavaFX instantiates Application itself, so the context is handed over statically. */
    public static void launchApp(AppContext appContext, String[] args) {
        context = Objects.requireNonNull(appContext);
        Application.launch(MainApp.class, args);
    }

    @Override
    public void start(Stage stage) {
        MainView view = new MainView(context);
        Scene scene = new Scene(view.root(), 1100, 700);
        scene.getStylesheets().add(
                Objects.requireNonNull(getClass().getResource("/styles/dark.css")).toExternalForm());

        stage.setTitle("Minecraft Manager");
        stage.setMinWidth(800);
        stage.setMinHeight(500);
        stage.setScene(scene);
        stage.show();
        log.info("Main window shown");
    }

    @Override
    public void stop() {
        context.downloads().shutdown();
        context.settingsService().save(context.settings());
        log.info("Application closed");
    }
}
