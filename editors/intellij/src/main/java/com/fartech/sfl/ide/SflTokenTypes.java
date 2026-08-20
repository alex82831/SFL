package com.fartech.sfl.ide;

import com.intellij.psi.tree.IElementType;
import com.intellij.psi.tree.TokenSet;

public final class SflTokenTypes {
    public static final IElementType KEYWORD = new IElementType("SFL_KEYWORD", SflLanguage.INSTANCE);
    public static final IElementType BUILTIN = new IElementType("SFL_BUILTIN", SflLanguage.INSTANCE);
    public static final IElementType NAMESPACE = new IElementType("SFL_NAMESPACE", SflLanguage.INSTANCE);
    public static final IElementType IDENTIFIER = new IElementType("SFL_IDENTIFIER", SflLanguage.INSTANCE);
    public static final IElementType NUMBER = new IElementType("SFL_NUMBER", SflLanguage.INSTANCE);
    public static final IElementType STRING = new IElementType("SFL_STRING", SflLanguage.INSTANCE);
    public static final IElementType LINE_COMMENT = new IElementType("SFL_LINE_COMMENT", SflLanguage.INSTANCE);
    public static final IElementType BLOCK_COMMENT = new IElementType("SFL_BLOCK_COMMENT", SflLanguage.INSTANCE);
    public static final IElementType OPERATOR = new IElementType("SFL_OPERATOR", SflLanguage.INSTANCE);
    public static final IElementType LPAREN = new IElementType("SFL_LPAREN", SflLanguage.INSTANCE);
    public static final IElementType RPAREN = new IElementType("SFL_RPAREN", SflLanguage.INSTANCE);
    public static final IElementType LBRACE = new IElementType("SFL_LBRACE", SflLanguage.INSTANCE);
    public static final IElementType RBRACE = new IElementType("SFL_RBRACE", SflLanguage.INSTANCE);
    public static final IElementType LBRACKET = new IElementType("SFL_LBRACKET", SflLanguage.INSTANCE);
    public static final IElementType RBRACKET = new IElementType("SFL_RBRACKET", SflLanguage.INSTANCE);
    public static final IElementType COMMA = new IElementType("SFL_COMMA", SflLanguage.INSTANCE);
    public static final IElementType SEMICOLON = new IElementType("SFL_SEMICOLON", SflLanguage.INSTANCE);
    public static final IElementType DOT = new IElementType("SFL_DOT", SflLanguage.INSTANCE);

    public static final TokenSet COMMENTS = TokenSet.create(LINE_COMMENT, BLOCK_COMMENT);
    public static final TokenSet STRINGS = TokenSet.create(STRING);

    private SflTokenTypes() {
    }
}
