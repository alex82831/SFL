package com.fartech.sfl.ide.settings;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The environment every sfl child process gets — the language server, the debug
 * adapter, the build runner. A GUI-launched IDE does not carry the terminal's
 * exports, so SFL_HOME configured in a shell profile never reaches those
 * processes on its own; this is where the settings page hands it over.
 */
public final class SflEnvironment {

    public static Map<String, String> forChildProcesses() {
        Map<String, String> env = new LinkedHashMap<>();
        SflSettings settings = SflSettings.getInstance();
        String home = expand(settings.getHome());
        if (!home.isBlank() && new File(home).isDirectory()) {
            env.put("SFL_HOME", home);
        }
        if (!settings.isUndefinedCheck()) {
            env.put("SFL_LSP_UNDEFINED", "off");
        }
        return env;
    }

    private static String expand(String p) {
        if (p == null) {
            return "";
        }
        String t = p.trim();
        if (t.startsWith("~" + File.separator) || t.equals("~")) {
            return System.getProperty("user.home") + t.substring(1);
        }
        return t;
    }

    private SflEnvironment() {
    }
}
