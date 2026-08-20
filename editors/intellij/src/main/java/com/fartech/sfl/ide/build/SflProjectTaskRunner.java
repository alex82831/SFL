package com.fartech.sfl.ide.build;

import com.fartech.sfl.ide.project.SflModuleType;
import com.fartech.sfl.ide.settings.SflBinaryLocator;
import com.intellij.build.BuildViewManager;
import com.intellij.build.DefaultBuildDescriptor;
import com.intellij.build.events.impl.FailureResultImpl;
import com.intellij.build.events.impl.FinishBuildEventImpl;
import com.intellij.build.events.impl.OutputBuildEventImpl;
import com.intellij.build.events.impl.StartBuildEventImpl;
import com.intellij.build.events.impl.SuccessResultImpl;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.process.OSProcessHandler;
import com.intellij.execution.process.ProcessEvent;
import com.intellij.execution.process.ProcessListener;
import com.intellij.execution.process.ProcessOutputTypes;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleType;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.task.ModuleBuildTask;
import com.intellij.task.ProjectTask;
import com.intellij.task.ProjectTaskContext;
import com.intellij.task.ProjectTaskRunner;
import com.intellij.task.TaskRunnerResults;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.concurrency.AsyncPromise;
import org.jetbrains.concurrency.Promise;

import java.io.File;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Makes Build Project mean something for SFL: modules of our type are claimed
 * away from the JPS pipeline — which would otherwise demand a JDK it has no use
 * for — and built by `sfl build` in each module's content root, with the output
 * streamed into the Build tool window like any other language's build.
 */
public final class SflProjectTaskRunner extends ProjectTaskRunner {

    @Override
    public boolean canRun(@NotNull ProjectTask projectTask) {
        return projectTask instanceof ModuleBuildTask task
                && ModuleType.get(task.getModule()) instanceof SflModuleType;
    }

    @Override
    public Promise<Result> run(@NotNull Project project,
                               @NotNull ProjectTaskContext context,
                               ProjectTask @NotNull ... tasks) {
        Set<VirtualFile> roots = new LinkedHashSet<>();
        for (ProjectTask task : tasks) {
            if (task instanceof ModuleBuildTask moduleTask) {
                Module module = moduleTask.getModule();
                for (VirtualFile root : ModuleRootManager.getInstance(module).getContentRoots()) {
                    if (root.findChild("build.sfl") != null || roots.isEmpty()) {
                        roots.add(root);
                    }
                }
            }
        }
        AsyncPromise<Result> promise = new AsyncPromise<>();
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            boolean failed = false;
            for (VirtualFile root : roots) {
                failed |= buildOne(project, root);
            }
            promise.setResult(failed ? TaskRunnerResults.FAILURE : TaskRunnerResults.SUCCESS);
        });
        return promise;
    }

    /** Runs `sfl build` in `root`, reporting into the Build view; true on failure. */
    private static boolean buildOne(Project project, VirtualFile root) {
        BuildViewManager buildView = project.getService(BuildViewManager.class);
        Object buildId = new Object();
        DefaultBuildDescriptor descriptor = new DefaultBuildDescriptor(
                buildId, "sfl build " + root.getName(), root.getPath(), System.currentTimeMillis());
        buildView.onEvent(buildId, new StartBuildEventImpl(descriptor, "running sfl build…"));

        File binary = SflBinaryLocator.resolve();
        if (binary == null) {
            buildView.onEvent(buildId, new FinishBuildEventImpl(
                    buildId, null, System.currentTimeMillis(), "sfl not found",
                    new FailureResultImpl("The sfl binary was not found. "
                            + "Set its path in Settings | Tools | SFL.")));
            return true;
        }
        try {
            GeneralCommandLine command = new GeneralCommandLine(binary.getAbsolutePath(), "build")
                    .withWorkDirectory(root.getPath())
                    .withEnvironment(
                            com.fartech.sfl.ide.settings.SflEnvironment.forChildProcesses());
            OSProcessHandler handler = new OSProcessHandler(command);
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
            // The compiled binary lands under build/; let the tree see it.
            VfsUtil.markDirtyAndRefresh(true, true, true, root);
            return !ok;
        } catch (ExecutionException e) {
            buildView.onEvent(buildId, new FinishBuildEventImpl(
                    buildId, null, System.currentTimeMillis(), "failed",
                    new FailureResultImpl(e.getMessage())));
            return true;
        }
    }
}
