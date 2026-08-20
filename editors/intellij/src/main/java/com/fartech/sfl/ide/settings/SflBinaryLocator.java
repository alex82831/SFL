package com.fartech.sfl.ide.settings;

import com.intellij.openapi.util.SystemInfo;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Finds the sfl binary and always hands back an absolute path.
 *
 * The IDE, launched from the Dock or a desktop shell, does not inherit the
 * terminal's PATH — the single most common reason a language server "is not
 * installed" on a machine where it plainly is. So after the explicit setting
 * and the environment, PATH is searched by hand, and then the places people
 * actually install things: Homebrew's prefix, /usr/local/bin, ~/.local/bin.
 */
public final class SflBinaryLocator {

    /** The resolved binary, or null when nothing plausible exists. */
    public static File resolve() {
        String name = SystemInfo.isWindows ? "sfl.exe" : "sfl";

        String configured = SflSettings.getInstance().getPath();
        File fromSetting = fromUserPath(configured);
        if (fromSetting != null) {
            return fromSetting;
        }

        File fromEnv = fromUserPath(System.getenv("SFL_PATH"));
        if (fromEnv != null) {
            return fromEnv;
        }

        String pathVar = System.getenv("PATH");
        if (pathVar != null) {
            for (String dir : pathVar.split(File.pathSeparator)) {
                File candidate = new File(dir, name);
                if (candidate.isFile() && candidate.canExecute()) {
                    return candidate.getAbsoluteFile();
                }
            }
        }

        for (String dir : wellKnownDirs()) {
            File candidate = new File(dir, name);
            if (candidate.isFile() && candidate.canExecute()) {
                return candidate.getAbsoluteFile();
            }
        }
        return null;
    }

    private static File fromUserPath(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String p = raw.trim();
        if (p.startsWith("~" + File.separator) || p.equals("~")) {
            p = System.getProperty("user.home") + p.substring(1);
        }
        File f = new File(p);
        return f.isFile() && f.canExecute() ? f.getAbsoluteFile() : null;
    }

    private static List<String> wellKnownDirs() {
        String home = System.getProperty("user.home");
        List<String> dirs = new ArrayList<>();
        if (SystemInfo.isWindows) {
            dirs.add(home + "\\bin");
            dirs.add(home + "\\.local\\bin");
        } else {
            dirs.add("/opt/homebrew/bin");
            dirs.add("/usr/local/bin");
            dirs.add(home + "/.local/bin");
            dirs.add(home + "/bin");
        }
        return dirs;
    }

    private SflBinaryLocator() {
    }
}
