package com.fartech.sfl.ide;

import com.intellij.lexer.Lexer;
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors;
import com.intellij.openapi.editor.HighlighterColors;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase;
import com.intellij.psi.TokenType;
import com.intellij.psi.tree.IElementType;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;

import static com.intellij.openapi.editor.colors.TextAttributesKey.createTextAttributesKey;

public final class SflSyntaxHighlighter extends SyntaxHighlighterBase {
    private static final Map<IElementType, TextAttributesKey> KEYS = new HashMap<>();

    private static void map(IElementType type, String externalName, TextAttributesKey fallback) {
        KEYS.put(type, createTextAttributesKey(externalName, fallback));
    }

    static {
        map(SflTokenTypes.KEYWORD, "SFL_KEYWORD", DefaultLanguageHighlighterColors.KEYWORD);
        map(SflTokenTypes.BUILTIN, "SFL_BUILTIN", DefaultLanguageHighlighterColors.PREDEFINED_SYMBOL);
        map(SflTokenTypes.IDENTIFIER, "SFL_IDENTIFIER", DefaultLanguageHighlighterColors.IDENTIFIER);
        map(SflTokenTypes.NUMBER, "SFL_NUMBER", DefaultLanguageHighlighterColors.NUMBER);
        map(SflTokenTypes.STRING, "SFL_STRING", DefaultLanguageHighlighterColors.STRING);
        map(SflTokenTypes.LINE_COMMENT, "SFL_LINE_COMMENT", DefaultLanguageHighlighterColors.LINE_COMMENT);
        map(SflTokenTypes.BLOCK_COMMENT, "SFL_BLOCK_COMMENT", DefaultLanguageHighlighterColors.BLOCK_COMMENT);
        map(SflTokenTypes.OPERATOR, "SFL_OPERATOR", DefaultLanguageHighlighterColors.OPERATION_SIGN);
        map(SflTokenTypes.LPAREN, "SFL_PARENS", DefaultLanguageHighlighterColors.PARENTHESES);
        map(SflTokenTypes.RPAREN, "SFL_PARENS2", DefaultLanguageHighlighterColors.PARENTHESES);
        map(SflTokenTypes.LBRACE, "SFL_BRACES", DefaultLanguageHighlighterColors.BRACES);
        map(SflTokenTypes.RBRACE, "SFL_BRACES2", DefaultLanguageHighlighterColors.BRACES);
        map(SflTokenTypes.LBRACKET, "SFL_BRACKETS", DefaultLanguageHighlighterColors.BRACKETS);
        map(SflTokenTypes.RBRACKET, "SFL_BRACKETS2", DefaultLanguageHighlighterColors.BRACKETS);
        map(SflTokenTypes.COMMA, "SFL_COMMA", DefaultLanguageHighlighterColors.COMMA);
        map(SflTokenTypes.SEMICOLON, "SFL_SEMICOLON", DefaultLanguageHighlighterColors.SEMICOLON);
        map(SflTokenTypes.DOT, "SFL_DOT", DefaultLanguageHighlighterColors.DOT);
        map(TokenType.BAD_CHARACTER, "SFL_BAD_CHARACTER", HighlighterColors.BAD_CHARACTER);
    }

    @Override
    public @NotNull Lexer getHighlightingLexer() {
        return new SflLexer();
    }

    @Override
    public TextAttributesKey @NotNull [] getTokenHighlights(IElementType tokenType) {
        TextAttributesKey key = KEYS.get(tokenType);
        return key == null ? TextAttributesKey.EMPTY_ARRAY : new TextAttributesKey[]{key};
    }
}
