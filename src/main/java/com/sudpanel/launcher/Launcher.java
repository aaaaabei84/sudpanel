package com.sudpanel.launcher;

import org.springframework.boot.SpringApplication;
import com.sudpanel.SudPanelApplication;

/** Source distribution launcher. Configuration is supplied by the user.
 * No database, device binary or local configuration is copied from the installation.
 */
public final class Launcher {
    private Launcher() { }

    public static void main(String[] args) {
        SpringApplication.run(SudPanelApplication.class, args);
    }
}
