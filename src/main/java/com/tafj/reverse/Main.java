package com.tafj.reverse;

import com.tafj.reverse.gui.TafjReverseGUI;
import javafx.application.Application;
import javafx.stage.Stage;

public class Main extends Application {

    @Override
    public void start(Stage primaryStage) {
        TafjReverseGUI gui = new TafjReverseGUI();
        gui.start(primaryStage);
    }

    public static void main(String[] args) {
        launch(args);
    }
}