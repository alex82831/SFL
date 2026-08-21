package com.fartech.sfl.ide.buildtool;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One linked SFL project as `sfl build describe` reports it. The plugin never
 * parses build.sfl itself — describe is the contract the build tool offers IDEs,
 * so a project file that uses every feature of the language still shows up here
 * exactly as the tool will treat it.
 */
public final class SflProjectModel {
    public final File root;
    public final String name;
    public final String version;
    public final String main;
    public final String tests;
    public final String output;
    public final List<String> sourceFiles;
    public final List<String> tasks;
    public final Map<String, String> deps;

    private SflProjectModel(File root, String name, String version, String main, String tests,
                            String output, List<String> sourceFiles, List<String> tasks,
                            Map<String, String> deps) {
        this.root = root;
        this.name = name;
        this.version = version;
        this.main = main;
        this.tests = tests;
        this.output = output;
        this.sourceFiles = Collections.unmodifiableList(sourceFiles);
        this.tasks = Collections.unmodifiableList(tasks);
        this.deps = Collections.unmodifiableMap(deps);
    }

    /** The tasks every project has, in the order `sfl build tasks` lists them. */
    public static final List<String> BUILTIN_TASKS =
            List.of("build", "run", "test", "clean", "pkg", "describe", "tasks", "init");

    public static SflProjectModel parse(File root, String describeJson) {
        JsonObject o = JsonParser.parseString(describeJson).getAsJsonObject();
        List<String> sources = new ArrayList<>();
        if (o.has("sourceFiles") && o.get("sourceFiles").isJsonArray()) {
            for (JsonElement e : o.getAsJsonArray("sourceFiles")) sources.add(e.getAsString());
        }
        List<String> tasks = new ArrayList<>();
        if (o.has("tasks") && o.get("tasks").isJsonArray()) {
            JsonArray arr = o.getAsJsonArray("tasks");
            for (JsonElement e : arr) tasks.add(e.getAsString());
        }
        Map<String, String> deps = new LinkedHashMap<>();
        if (o.has("deps") && o.get("deps").isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("deps").entrySet()) {
                deps.put(e.getKey(), e.getValue().isJsonPrimitive() ? e.getValue().getAsString() : "*");
            }
        }
        return new SflProjectModel(
                root,
                str(o, "name", root.getName()),
                str(o, "version", ""),
                str(o, "main", null),
                str(o, "tests", "tests"),
                str(o, "output", "build"),
                sources,
                tasks,
                deps);
    }

    private static String str(JsonObject o, String key, String fallback) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : fallback;
    }

    /** Tasks defined in build.sfl, i.e. everything that is not built in. */
    public List<String> userTasks() {
        List<String> out = new ArrayList<>();
        for (String t : tasks) if (!BUILTIN_TASKS.contains(t)) out.add(t);
        return out;
    }

    public String displayName() {
        return version == null || version.isBlank() ? name : name + " " + version;
    }
}
