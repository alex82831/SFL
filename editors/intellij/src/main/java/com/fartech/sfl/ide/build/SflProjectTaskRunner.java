package com.fartech.sfl.ide.build;

import com.fartech.sfl.ide.project.SflModuleType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleType;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.task.ModuleBuildTask;
import com.intellij.task.ProjectTask;
import com.intellij.task.ProjectTaskContext;
import com.intellij.task.ProjectTaskRunner;
import com.intellij.task.TaskRunnerResults;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.concurrency.AsyncPromise;
import org.jetbrains.concurrency.Promise;

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

    /** Build view output is shared with the tool window's Build All. */
    private static boolean buildOne(Project project, VirtualFile root) {
        return com.fartech.sfl.ide.buildtool.SflBuildOutput.buildInBuildView(
                project, VfsUtilCore.virtualToIoFile(root));
    }
}
