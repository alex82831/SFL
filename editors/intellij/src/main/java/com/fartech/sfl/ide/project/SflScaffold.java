package com.fartech.sfl.ide.project;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Writes a fresh SFL project — the same files `sfl build init` writes.
 *
 * The wizard runs inside a write action, where launching the binary would stall
 * the EDT, so the templates live here as a mirror. If you change one side,
 * change the other: buildtool/main.sfl, __taskInit.
 */
final class SflScaffold {

    static void write(File root, String rawName, String rawVersion, boolean withTests) throws IOException {
        String name = sanitize(rawName, "app");
        String version = sanitize(rawVersion, "0.1.0");

        File src = new File(root, "src");
        if (!src.isDirectory() && !src.mkdirs()) {
            throw new IOException("cannot create " + src);
        }
        writeText(new File(root, "build.sfl"),
                "project({\n"
                        + "  \"name\": \"" + name + "\",\n"
                        + "  \"version\": \"" + version + "\",\n"
                        + "  \"main\": \"src/main.sfl\"\n"
                        + "})\n"
                        + "\n"
                        + "// Tasks are plain SFL; the function receives the task's arguments.\n"
                        + "// `sfl build greet` runs this one:\n"
                        + "// task(\"greet\", (args) -> println(\"hello from a task\"), \"say hello\")\n");
        writeText(new File(root, "src/main.sfl"),
                "println(\"hello, " + name + "\")\n");
        if (withTests) {
            File tests = new File(root, "tests");
            if (!tests.isDirectory() && !tests.mkdirs()) {
                throw new IOException("cannot create " + tests);
            }
            writeText(new File(root, "tests/smoke.sfl"),
                    "// Every .sfl in this directory runs under `sfl build test`; a non-zero\n"
                            + "// exit fails the suite.\n"
                            + "val two = 1 + 1\n"
                            + "if (two != 2) { eprintln(\"arithmetic is broken\"); exit(1) }\n"
                            + "println(\"smoke: ok\")\n");
        }
        writeText(new File(root, ".gitignore"), "build/\n");
    }

    private static void writeText(File file, String text) throws IOException {
        Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
    }

    /** Keeps a value safe to splice into the build.sfl string literals. */
    private static String sanitize(String s, String fallback) {
        if (s == null) {
            return fallback;
        }
        String cleaned = s.replace("\"", "").replace("\\", "").trim();
        return cleaned.isEmpty() ? fallback : cleaned;
    }

    private SflScaffold() {
    }
}
