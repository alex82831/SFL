package com.fartech.sfl.ide.navigation;

import com.intellij.psi.PsiElement;
import com.intellij.psi.search.searches.DefinitionsScopedSearch;
import com.intellij.util.Processor;
import com.intellij.util.QueryExecutor;
import org.jetbrains.annotations.NotNull;

/**
 * Go to Implementation(s) (⌥⌘B) on an SFL symbol: the platform asks every
 * DefinitionsScopedSearch executor for implementations of the target element;
 * this one answers with textDocument/implementation. The search already runs
 * under a progress indicator, so waiting on the server here is fine.
 */
public final class SflImplementationSearcher
        implements QueryExecutor<PsiElement, DefinitionsScopedSearch.SearchParameters> {

    @Override
    public boolean execute(@NotNull DefinitionsScopedSearch.SearchParameters parameters,
                           @NotNull Processor<? super PsiElement> consumer) {
        PsiElement element = parameters.getElement();
        if (!SflLspNavigation.isSflElement(element)) {
            return true;
        }
        for (PsiElement target : SflLspNavigation.implementations(element)) {
            if (!consumer.process(target)) {
                return false;
            }
        }
        return true;
    }
}
