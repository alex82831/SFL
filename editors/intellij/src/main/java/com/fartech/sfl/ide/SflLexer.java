package com.fartech.sfl.ide;

import com.intellij.lexer.LexerBase;
import com.intellij.psi.TokenType;
import com.intellij.psi.tree.IElementType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Highlighting lexer for SFL: restartable, and tolerant where the compiler's own
 * lexer is strict, because an editor lexes half-typed text all day. Unterminated
 * strings run to end of line, unterminated block comments and triple-quoted
 * strings carry their state across lines — that is what {@code getState()} is for.
 *
 * Word classification (keyword? builtin?) comes from {@link SflSyntaxTables},
 * which is generated from the compiler's own tables. This file knows the token
 * shapes; it deliberately knows no word lists.
 */
public final class SflLexer extends LexerBase {
    /** Between tokens, at the top level. */
    private static final int STATE_DEFAULT = 0;
    /** Inside a block comment whose closing *&#47; has not appeared yet. */
    private static final int STATE_BLOCK_COMMENT = 1;
    /** Inside a triple-quoted string whose closing quotes have not appeared yet. */
    private static final int STATE_TRIPLE_STRING = 2;

    private CharSequence buffer;
    private int bufferEnd;
    private int tokenStart;
    private int tokenEnd;
    private int state;
    private IElementType tokenType;

    @Override
    public void start(@NotNull CharSequence buf, int startOffset, int endOffset, int initialState) {
        buffer = buf;
        bufferEnd = endOffset;
        tokenStart = startOffset;
        tokenEnd = startOffset;
        state = initialState;
        advance();
    }

    @Override
    public int getState() {
        return state;
    }

    @Override
    public @Nullable IElementType getTokenType() {
        return tokenType;
    }

    @Override
    public int getTokenStart() {
        return tokenStart;
    }

    @Override
    public int getTokenEnd() {
        return tokenEnd;
    }

    @Override
    public @NotNull CharSequence getBufferSequence() {
        return buffer;
    }

    @Override
    public int getBufferEnd() {
        return bufferEnd;
    }

    @Override
    public void advance() {
        tokenStart = tokenEnd;
        if (tokenStart >= bufferEnd) {
            tokenType = null;
            return;
        }
        int i = tokenStart;
        char c = buffer.charAt(i);

        if (state == STATE_BLOCK_COMMENT) {
            tokenEnd = scanBlockCommentRest(i);
            tokenType = SflTokenTypes.BLOCK_COMMENT;
            return;
        }
        if (state == STATE_TRIPLE_STRING) {
            tokenEnd = scanTripleRest(i);
            tokenType = SflTokenTypes.STRING;
            return;
        }

        if (Character.isWhitespace(c)) {
            int j = i;
            while (j < bufferEnd && Character.isWhitespace(buffer.charAt(j))) j++;
            tokenEnd = j;
            tokenType = TokenType.WHITE_SPACE;
            return;
        }

        if (c == '/' && i + 1 < bufferEnd && buffer.charAt(i + 1) == '/') {
            int j = i;
            while (j < bufferEnd && buffer.charAt(j) != '\n') j++;
            tokenEnd = j;
            tokenType = SflTokenTypes.LINE_COMMENT;
            return;
        }

        if (c == '/' && i + 1 < bufferEnd && buffer.charAt(i + 1) == '*') {
            state = STATE_BLOCK_COMMENT;
            tokenEnd = scanBlockCommentRest(i + 2);
            tokenType = SflTokenTypes.BLOCK_COMMENT;
            return;
        }

        // r"..." / r'...': raw, no escapes.
        if (c == 'r' && i + 1 < bufferEnd
                && (buffer.charAt(i + 1) == '"' || buffer.charAt(i + 1) == '\'')) {
            char quote = buffer.charAt(i + 1);
            int j = i + 2;
            while (j < bufferEnd && buffer.charAt(j) != quote && buffer.charAt(j) != '\n') j++;
            tokenEnd = j < bufferEnd && buffer.charAt(j) == quote ? j + 1 : j;
            tokenType = SflTokenTypes.STRING;
            return;
        }

        if (c == '"' && i + 2 < bufferEnd
                && buffer.charAt(i + 1) == '"' && buffer.charAt(i + 2) == '"') {
            state = STATE_TRIPLE_STRING;
            tokenEnd = scanTripleRest(i + 3);
            tokenType = SflTokenTypes.STRING;
            return;
        }

        if (c == '"' || c == '\'') {
            int j = i + 1;
            while (j < bufferEnd && buffer.charAt(j) != c && buffer.charAt(j) != '\n') {
                if (buffer.charAt(j) == '\\' && j + 1 < bufferEnd) j++;
                j++;
            }
            tokenEnd = j < bufferEnd && buffer.charAt(j) == c ? j + 1 : j;
            tokenType = SflTokenTypes.STRING;
            return;
        }

        if (Character.isDigit(c) || (c == '.' && i + 1 < bufferEnd && Character.isDigit(buffer.charAt(i + 1)))) {
            tokenEnd = scanNumber(i);
            tokenType = SflTokenTypes.NUMBER;
            return;
        }

        if (isIdentStart(c)) {
            int j = i + 1;
            while (j < bufferEnd && isIdentPart(buffer.charAt(j))) j++;
            String word = buffer.subSequence(i, j).toString();
            tokenEnd = j;
            if (SflSyntaxTables.KEYWORDS.contains(word)) tokenType = SflTokenTypes.KEYWORD;
            else if (SflSyntaxTables.BUILTINS.contains(word)) tokenType = SflTokenTypes.BUILTIN;
            else tokenType = SflTokenTypes.IDENTIFIER;
            return;
        }

        switch (c) {
            case '(': tokenEnd = i + 1; tokenType = SflTokenTypes.LPAREN; return;
            case ')': tokenEnd = i + 1; tokenType = SflTokenTypes.RPAREN; return;
            case '{': tokenEnd = i + 1; tokenType = SflTokenTypes.LBRACE; return;
            case '}': tokenEnd = i + 1; tokenType = SflTokenTypes.RBRACE; return;
            case '[': tokenEnd = i + 1; tokenType = SflTokenTypes.LBRACKET; return;
            case ']': tokenEnd = i + 1; tokenType = SflTokenTypes.RBRACKET; return;
            case ',': tokenEnd = i + 1; tokenType = SflTokenTypes.COMMA; return;
            case ';': tokenEnd = i + 1; tokenType = SflTokenTypes.SEMICOLON; return;
            case '.': tokenEnd = i + 1; tokenType = SflTokenTypes.DOT; return;
            default: break;
        }

        if (isOperatorChar(c)) {
            // Two-character operators first; every second char of one is itself
            // an operator char, so the greedy scan cannot split them wrongly.
            if (i + 1 < bufferEnd && isTwoCharOperator(c, buffer.charAt(i + 1))) tokenEnd = i + 2;
            else tokenEnd = i + 1;
            tokenType = SflTokenTypes.OPERATOR;
            return;
        }

        tokenEnd = i + 1;
        tokenType = TokenType.BAD_CHARACTER;
    }

    private int scanBlockCommentRest(int from) {
        int j = from;
        while (j < bufferEnd) {
            if (buffer.charAt(j) == '*' && j + 1 < bufferEnd && buffer.charAt(j + 1) == '/') {
                state = STATE_DEFAULT;
                return j + 2;
            }
            j++;
        }
        return bufferEnd; // still open; state stays BLOCK_COMMENT for the next segment
    }

    private int scanTripleRest(int from) {
        int j = from;
        while (j < bufferEnd) {
            if (buffer.charAt(j) == '"' && j + 2 < bufferEnd
                    && buffer.charAt(j + 1) == '"' && buffer.charAt(j + 2) == '"') {
                state = STATE_DEFAULT;
                return j + 3;
            }
            j++;
        }
        return bufferEnd; // still open
    }

    private int scanNumber(int from) {
        int j = from;
        if (buffer.charAt(j) == '0' && j + 1 < bufferEnd) {
            char p = buffer.charAt(j + 1);
            if (p == 'x' || p == 'X' || p == 'b' || p == 'B' || p == 'o' || p == 'O') {
                j += 2;
                while (j < bufferEnd && (Character.isLetterOrDigit(buffer.charAt(j)) || buffer.charAt(j) == '_')) j++;
                return j;
            }
        }
        while (j < bufferEnd && (Character.isDigit(buffer.charAt(j)) || buffer.charAt(j) == '_')) j++;
        if (j < bufferEnd && buffer.charAt(j) == '.' && j + 1 < bufferEnd && Character.isDigit(buffer.charAt(j + 1))) {
            j++;
            while (j < bufferEnd && (Character.isDigit(buffer.charAt(j)) || buffer.charAt(j) == '_')) j++;
        }
        if (j < bufferEnd && (buffer.charAt(j) == 'e' || buffer.charAt(j) == 'E')) {
            int save = j;
            j++;
            if (j < bufferEnd && (buffer.charAt(j) == '+' || buffer.charAt(j) == '-')) j++;
            if (j < bufferEnd && Character.isDigit(buffer.charAt(j))) {
                while (j < bufferEnd && (Character.isDigit(buffer.charAt(j)) || buffer.charAt(j) == '_')) j++;
            } else {
                j = save;
            }
        }
        return j;
    }

    private static boolean isIdentStart(char c) {
        return c == '_' || c == '$' || Character.isLetter(c);
    }

    private static boolean isIdentPart(char c) {
        return c == '_' || c == '$' || Character.isLetterOrDigit(c);
    }

    private static boolean isOperatorChar(char c) {
        return "+-*/%=<>!&|?@".indexOf(c) >= 0;
    }

    private static boolean isTwoCharOperator(char a, char b) {
        switch ("" + a + b) {
            case "==": case "!=": case "<=": case ">=": case "&&": case "||":
            case "+=": case "-=": case "*=": case "/=": case "%=":
            case "|>": case "->": case "?.": case "??":
                return true;
            default:
                return false;
        }
    }
}
