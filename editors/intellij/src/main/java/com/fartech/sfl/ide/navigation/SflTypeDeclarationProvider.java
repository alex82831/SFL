package com.fartech.sfl.ide.navigation;

import com.intellij.codeInsight.navigation.actions.TypeDeclarationProvider;
import com.intellij.psi.PsiElement;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** Go to Type Declaration (⇧⌘B) on an SFL symbol: textDocument/typeDefinition. */
public final class SflTypeDeclarationProvider implements TypeDeclarationProvider {
    @Override
    public PsiElement @Nullable [] getSymbolTypeDeclarations(@NotNull PsiElement symbol) {
        if (!SflLspNavigation.isSflElement(symbol)) {
            return null;
        }
        List<PsiElement> targets = SflLspNavigation.typeDefinitions(symbol);
        return targets.isEmpty() ? null : targets.toArray(PsiElement.EMPTY_ARRAY);
    }
}
