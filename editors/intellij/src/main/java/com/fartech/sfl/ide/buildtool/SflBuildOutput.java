package com.fartech.sfl.ide.buildtool;

import com.fartech.sfl.ide.settings.SflBinaryLocator;
import com.fartech.sfl.ide.settings.SflEnvironment;
import com.intellij.build.BuildViewManager;
import com.intellij.build.DefaultBuildDescriptor;
import com.intellij.build.events.impl.FailureResultImpl;
import com.intellij.build.events.impl.FinishBuildEventImpl;
import com.intellij.build.events.impl.OutputBuildEventImpl;
import com.intellij.build.events.impl.StartBuildEventImpl;
import com.intellij.build.events.impl.SuccessResultImpl;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.process.KillableColoredProcessHandler;
import com.intellij.execution.process.ProcessEvent;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.process.ProcessListener;
import com.intellij.execution.process.ProcessOutputTypes;
import com.intellij.execution.RunContentExecutor;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The two ways `sfl build` output reaches the user: the Build tool window for
 * builds (what Build Project and Build All use), and a Run console for tasks
 * (run, test, anything build.sfl defines) — a console can be interactive, and a
 * program under `sfl build run` may well read stdin.
 */
public final class SflBuildOutput {

    private SflBuildOutput() {
    }

    private static GeneralCommandLine command(File binary, File root, String... args) {
        List<String> argv = new ArrayList<>();
        argv.add(binary.getAbsolutePath());
        argv.add("build");
        argv.addAll(Arrays.asList(args));
        return new GeneralCommandLine(argv)
                .withWorkDirectory(root)
                .withEnvironment(SflEnvironment.forChildProcesses());
    }

    /** Runs `sfl build <args>` in `root`, streaming into the Build view; true on failure. */
    public static boolean buildInBuildView(@NotNull Project project, @NotNull File root, String... args) {
        BuildViewManager buildView = project.getService(BuildViewManager.class);
        Object buildId = new Object();
        String label = "sfl build" + (args.length == 0 ? "" : " " + String.join(" ", args)) + " · " + root.getName();
        buildView.onEvent(buildId, new StartBuildEventImpl(
                new DefaultBuildDescriptor(buildId, label, root.getPath(), System.currentTimeMillis()),
                "running…"));

        File binary = SflBinaryLocator.resolve();
        if (binary == null) {
            buildView.onEvent(buildId, new FinishBuildEventImpl(
                    buildId, null, System.currentTimeMillis(), "sfl not found",
                    new FailureResultImpl("The sfl binary was not found. Set its path in Settings | Tools | SFL.")));
            return true;
        }
        try {
            ProcessHandler handler = new KillableColoredProcessHandler(command(binary, root, args));
            handler.addProcessListener(new ProcessListener() {
                @Override
                public void onTextAvailable(@NotNull ProcessEvent event, @NotNull Key outputType) {
                    buildView.onEvent(buildId, new OutputBuildEventImpl(
                            buildId, event.getText(), !ProcessOutputTypes.STDERR.equals(outputType)));
                }
            });
            handler.startNotify();
            handler.waitFor();
            Integer code = handler.getExitCode();
            boolean ok = code != null && code == 0;
            buildView.onEvent(buildId, new FinishBuildEventImpl(
                    buildId, null, System.currentTimeMillis(),
                    ok ? "finished" : "failed",
                    ok ? new SuccessResultImpl() : new FailureResultImpl()));
            refresh(root);
            return !ok;
        } catch (ExecutionException e) {
            buildView.onEvent(buildId, new FinishBuildEventImpl(
                    buildId, null, System.currentTimeMillis(), "failed",
                    new FailureResultImpl(e.getMessage())));
            return true;
        }
    }

    /** Runs `sfl build <args>` in `root` inside a Run console; returns at once. */
    public static void runInConsole(@NotNull Project project, @NotNull File root, String... args) {
        File binary = SflBinaryLocator.resolve();
        String title = "sfl build " + String.join(" ", args) + " · " + root.getName();
        if (binary == null) {
            SflNotifications.warn(project, "SFL build tool",
                    "The sfl binary was not found. Set its path in Settings | Tools | SFL.");
            return;
        }
        ApplicationManager.getApplication().invokeLater(() -> {
            try {
                KillableColoredProcessHandler handler = new KillableColoredProcessHandler(command(binary, root, args));
                new RunContentExecutor(project, handler)
                        .withTitle(title)
                        .withActivateToolWindow(true)
                        .withStop(handler::destroyProcess, () -> !handler.isProcessTerminated())
                        .withAfterCompletion(() -> refresh(root))
                        .run();
            } catch (ExecutionException e) {
                SflNotifications.warn(project, "SFL build tool", "Cannot start sfl: " + e.getMessage());
            }
        });
    }

    /** The build output lands on disk; let the project tree see it. */
    private static void refresh(File root) {
        VirtualFile vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(root);
        if (vf != null) {
            VfsUtil.markDirtyAndRefresh(true, true, true, vf);
        }
    }
}
