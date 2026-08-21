package com.fartech.sfl.ide.buildtool;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.ProjectActivity;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * On project open: find the build.sfl files under the content roots and describe
 * them, so the SFL tool window is populated before anyone looks at it — the way
 * Gradle's linked projects are there when the IDE comes up.
 */
public final class SflProjectsStartup implements ProjectActivity {
    @Override
    public @Nullable Object execute(@NotNull Project project,
                                    @NotNull Continuation<? super Unit> continuation) {
        SflProjectsService service = SflProjectsService.getInstance(project);
        if (service.isAutoDetect()) {
            service.reload();
        }
        return Unit.INSTANCE;
    }
}
