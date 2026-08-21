package com.fartech.sfl.ide.buildtool;

import com.fartech.sfl.ide.settings.SflBinaryLocator;
import com.fartech.sfl.ide.settings.SflEnvironment;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.process.ProcessOutput;
import com.intellij.execution.util.ExecUtil;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The set of SFL projects linked into this IDE project — the SFL counterpart of
 * Gradle's "linked projects". Roots persist per IDE project; their models come
 * from `sfl build describe` and are refreshed on demand. Discovery walks the
 * content roots for build.sfl files so a checkout with several SFL projects
 * shows all of them without anyone clicking Link.
 */
@Service(Service.Level.PROJECT)
@State(name = "SflBuildTool", storages = @Storage("sflBuildTool.xml"))
public final class SflProjectsService implements PersistentStateComponent<SflProjectsService.State> {

    public static final class State {
        public List<String> roots = new ArrayList<>();
        /** Roots the user unlinked; discovery must not resurrect them. */
        public List<String> ignored = new ArrayList<>();
        public boolean autoDetect = true;
    }

    public interface Listener {
        void projectsChanged();
    }

    private static final Set<String> SKIPPED_DIRS = Set.of(
            ".idea", ".git", "build", "node_modules", "sfl_packages", "packages",
            "target", "out", ".gradle", ".intellijPlatform");

    private final Project project;
    private State state = new State();
    private final Map<String, SflProjectModel> models = new LinkedHashMap<>();
    private final Map<String, String> errors = new LinkedHashMap<>();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private volatile boolean reloading = false;

    public SflProjectsService(@NotNull Project project) {
        this.project = project;
    }

    public static SflProjectsService getInstance(@NotNull Project project) {
        return project.getService(SflProjectsService.class);
    }

    @Override
    public @Nullable State getState() {
        return state;
    }

    @Override
    public void loadState(@NotNull State state) {
        this.state = state;
    }

    // ---- the linked set ------------------------------------------------------

    public synchronized List<String> roots() {
        return Collections.unmodifiableList(new ArrayList<>(state.roots));
    }

    public boolean isAutoDetect() {
        return state.autoDetect;
    }

    public void setAutoDetect(boolean on) {
        state.autoDetect = on;
    }

    public boolean isReloading() {
        return reloading;
    }

    /** Links a directory; true when it was new. Refuses directories without build.sfl. */
    public synchronized boolean link(@NotNull File dir) {
        if (!new File(dir, "build.sfl").isFile()) {
            return false;
        }
        String path = dir.getAbsolutePath();
        state.ignored.remove(path);
        if (state.roots.contains(path)) {
            return false;
        }
        state.roots.add(path);
        return true;
    }

    public synchronized void unlink(@NotNull String path) {
        state.roots.remove(path);
        if (!state.ignored.contains(path)) {
            state.ignored.add(path);
        }
        models.remove(path);
        errors.remove(path);
        fire();
    }

    public synchronized @Nullable SflProjectModel model(@NotNull String path) {
        return models.get(path);
    }

    public synchronized @Nullable String error(@NotNull String path) {
        return errors.get(path);
    }

    // ---- discovery -----------------------------------------------------------

    /** Walks the content roots (three levels deep) for build.sfl; returns how many were new. */
    public int discover() {
        if (!state.autoDetect) {
            return 0;
        }
        Set<File> candidates = new LinkedHashSet<>();
        String base = project.getBasePath();
        if (base != null) {
            candidates.add(new File(base));
        }
        for (VirtualFile vf : ProjectRootManager.getInstance(project).getContentRoots()) {
            candidates.add(VfsUtilCore.virtualToIoFile(vf));
        }
        int added = 0;
        for (File start : candidates) {
            for (File dir : findProjectDirs(start, 3)) {
                String path = dir.getAbsolutePath();
                synchronized (this) {
                    if (state.ignored.contains(path) || state.roots.contains(path)) {
                        continue;
                    }
                    state.roots.add(path);
                }
                added++;
            }
        }
        return added;
    }

    private static List<File> findProjectDirs(File dir, int depth) {
        List<File> out = new ArrayList<>();
        if (dir == null || !dir.isDirectory()) {
            return out;
        }
        if (new File(dir, "build.sfl").isFile()) {
            out.add(dir);
        }
        if (depth == 0) {
            return out;
        }
        File[] kids = dir.listFiles();
        if (kids == null) {
            return out;
        }
        for (File kid : kids) {
            if (kid.isDirectory() && !SKIPPED_DIRS.contains(kid.getName()) && !kid.getName().startsWith(".")) {
                out.addAll(findProjectDirs(kid, depth - 1));
            }
        }
        return out;
    }

    // ---- describe ------------------------------------------------------------

    /** Re-describes every linked root off the EDT, then notifies listeners on it. */
    public void reload() {
        if (reloading) {
            return;
        }
        reloading = true;
        fire();
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                discover();
                File binary = SflBinaryLocator.resolve();
                List<String> snapshot = roots();
                Map<String, SflProjectModel> fresh = new LinkedHashMap<>();
                Map<String, String> freshErrors = new LinkedHashMap<>();
                for (String path : snapshot) {
                    File root = new File(path);
                    if (!new File(root, "build.sfl").isFile()) {
                        freshErrors.put(path, "build.sfl is gone");
                        continue;
                    }
                    if (binary == null) {
                        freshErrors.put(path, "sfl binary not found — Settings | Tools | SFL");
                        continue;
                    }
                    try {
                        GeneralCommandLine cmd = new GeneralCommandLine(binary.getAbsolutePath(), "build", "describe")
                                .withWorkDirectory(root)
                                .withEnvironment(SflEnvironment.forChildProcesses());
                        ProcessOutput out = ExecUtil.execAndGetOutput(cmd, 15000);
                        if (out.getExitCode() != 0) {
                            String err = (out.getStderr() + "\n" + out.getStdout()).trim();
                            freshErrors.put(path, err.isEmpty() ? "describe failed" : firstLine(err));
                        } else {
                            fresh.put(path, SflProjectModel.parse(root, out.getStdout()));
                        }
                    } catch (ExecutionException | RuntimeException e) {
                        freshErrors.put(path, String.valueOf(e.getMessage()));
                    }
                }
                synchronized (this) {
                    models.clear();
                    models.putAll(fresh);
                    errors.clear();
                    errors.putAll(freshErrors);
                }
            } finally {
                reloading = false;
                fire();
            }
        });
    }

    private static String firstLine(String s) {
        int nl = s.indexOf('\n');
        return nl < 0 ? s : s.substring(0, nl);
    }

    // ---- listeners -----------------------------------------------------------

    public void addListener(@NotNull Listener l) {
        listeners.add(l);
    }

    public void removeListener(@NotNull Listener l) {
        listeners.remove(l);
    }

    private void fire() {
        ApplicationManager.getApplication().invokeLater(() -> {
            if (project.isDisposed()) {
                return;
            }
            for (Listener l : listeners) l.projectsChanged();
        });
    }
}
