package com.fartech.sfl.ide.navigation;

import com.fartech.sfl.ide.SflTokenTypes;
import com.intellij.codeInsight.TargetElementEvaluatorEx2;
import com.intellij.psi.PsiElement;
import com.intellij.psi.tree.IElementType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Tells the platform what the "symbol under the caret" is in an SFL file. The
 * PSI is a flat list of tokens with no references, so without this every
 * native navigation action (Go to Implementation, Go to Type Declaration) found
 * nothing to act on and silently did nothing. An identifier token is the
 * symbol; the language server decides what it means.
 */
public final class SflTargetElementEvaluator extends TargetElementEvaluatorEx2 {

    private static boolean isSymbolToken(@Nullable PsiElement element) {
        if (element == null) {
            return false;
        }
        IElementType type = element.getNode() == null ? null : element.getNode().getElementType();
        return type == SflTokenTypes.IDENTIFIER
                || type == SflTokenTypes.BUILTIN
                || type == SflTokenTypes.NAMESPACE;
    }

    @Override
    public @Nullable PsiElement getNamedElement(@NotNull PsiElement element) {
        return isSymbolToken(element) ? element : null;
    }
}
