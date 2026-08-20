package com.fartech.sfl.ide;

import com.intellij.extapi.psi.PsiFileBase;
import com.intellij.lang.ASTNode;
import com.intellij.lang.ParserDefinition;
import com.intellij.lang.PsiBuilder;
import com.intellij.lang.PsiParser;
import com.intellij.lexer.Lexer;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.project.Project;
import com.intellij.psi.FileViewProvider;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.impl.source.tree.LeafPsiElement;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.tree.IFileElementType;
import com.intellij.psi.tree.TokenSet;
import org.jetbrains.annotations.NotNull;

/**
 * A deliberately flat PSI: one file node, every token a leaf. Structure — scopes,
 * definitions, references — belongs to the language server, not to a second
 * hand-maintained grammar; this exists so that the platform features that want a
 * PSI (commenting, brace matching, TODO highlighting) have one to stand on.
 */
public final class SflParserDefinition implements ParserDefinition {
    public static final IFileElementType FILE = new IFileElementType(SflLanguage.INSTANCE);

    @Override
    public @NotNull Lexer createLexer(Project project) {
        return new SflLexer();
    }

    @Override
    public @NotNull PsiParser createParser(Project project) {
        return (root, builder) -> {
            PsiBuilder.Marker mark = builder.mark();
            while (!builder.eof()) {
                builder.advanceLexer();
            }
            mark.done(root);
            return builder.getTreeBuilt();
        };
    }

    @Override
    public @NotNull IFileElementType getFileNodeType() {
        return FILE;
    }

    @Override
    public @NotNull TokenSet getCommentTokens() {
        return SflTokenTypes.COMMENTS;
    }

    @Override
    public @NotNull TokenSet getStringLiteralElements() {
        return SflTokenTypes.STRINGS;
    }

    @Override
    public @NotNull PsiElement createElement(ASTNode node) {
        return new LeafPsiElement(node.getElementType(), node.getText());
    }

    @Override
    public @NotNull PsiFile createFile(@NotNull FileViewProvider viewProvider) {
        return new SflFile(viewProvider);
    }

    public static final class SflFile extends PsiFileBase {
        SflFile(@NotNull FileViewProvider viewProvider) {
            super(viewProvider, SflLanguage.INSTANCE);
        }

        @Override
        public @NotNull FileType getFileType() {
            return SflFileType.INSTANCE;
        }
    }
}
