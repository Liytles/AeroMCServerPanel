package com.aerogroup.mcpanel;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.Parent;
import javafx.scene.image.Image;
import javafx.stage.Stage;

public final class MainApp extends Application {
    private MainController controller;
    @Override public void start(Stage stage) {
        controller = new MainController(getHostServices());
        Parent root = controller.buildView();
        String savedLanguage = LanguageManager.load();
        if ("en".equals(savedLanguage)) LanguageManager.apply(root, savedLanguage);
        Scene scene = new Scene(root, 1120, 720);
        scene.getStylesheets().add(getClass().getResource("/style.css").toExternalForm());
        try (var icon = getClass().getResourceAsStream("/icons/aeromc.png")) { if (icon != null) stage.getIcons().add(new Image(icon)); } catch (Exception ignored) { }
        stage.setTitle("AeroMC Server Panel • " + BuildInfo.displayVersion()); stage.setMinWidth(920); stage.setMinHeight(620); stage.setScene(scene); stage.show();
        Platform.runLater(() -> controller.showFeatureTourIfNeeded(stage));
        stage.setOnCloseRequest(event -> controller.shutdown());
    }
    public static void main(String[] args) { AppDiagnostics.install(); launch(args); }
}
