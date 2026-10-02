package com.mymods.mclauncher;

import javafx.application.Application;

/**
 * Program entry point.
 *
 * <p>Kept separate from {@link LauncherApp} on purpose: JavaFX refuses to start when the class
 * holding {@code main} extends {@link Application}, because the toolkit launcher has to be the
 * outermost frame. Calling {@link Application#launch} from a plain class avoids that.
 */
public final class Main {

    private Main() {}

    public static void main(String[] args) {
        Application.launch(LauncherApp.class, args);
    }
}
