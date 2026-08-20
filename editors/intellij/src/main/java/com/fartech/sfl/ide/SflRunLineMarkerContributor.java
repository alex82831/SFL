package com.fartech.sfl.ide;

import com.intellij.execution.lineMarker.ExecutorAction;
import com.intellij.execution.lineMarker.RunLineMarkerContributor;
import com.intellij.icons.AllIcons;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The ▶ in the gutter of line one. A script is its own program, so the file's
 * first token is where "run this" belongs; the actions are the platform's own
 * Run/Debug context actions, which resolve through LSP4IJ's producer.
 */
public final class SflRunLineMarkerContributor extends RunLineMarkerContributor {
    @Override
    public @Nullable Info getInfo(@NotNull PsiElement element) {
        if (element.getFirstChild() != null) {
            return null; // leaves only, so the marker appears exactly once
        }
        if (element.getTextRange() == null || element.getTextRange().getStartOffset() != 0) {
            return null;
        }
        PsiFile file = element.getContainingFile();
        if (file == null || file.getFileType() != SflFileType.INSTANCE) {
            return null;
        }
        return new Info(AllIcons.RunConfigurations.TestState.Run, ExecutorAction.getActions(0),
                psi -> "Run " + file.getName());
    }
}
