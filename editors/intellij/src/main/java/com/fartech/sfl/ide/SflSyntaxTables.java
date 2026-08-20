package com.fartech.sfl.ide;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * The keyword, builtin and namespace tables, read from resources that
 * {@code tools/gen-editor-syntax.sfl} regenerates from {@code sfl syntax}.
 * Nothing in this plugin hand-maintains a word list: when the language grows a
 * keyword, regenerating the resources is the whole update.
 */
public final class SflSyntaxTables {
    public static final Set<String> KEYWORDS = load("/syntax/sfl-keywords.txt");
    public static final Set<String> BUILTINS = load("/syntax/sfl-builtins.txt");
    /**
     * The namespaces that are there without an import: {@code std}, which holds
     * every builtin, and one per builtin group. A program's own namespaces come
     * from its imports and are recognised by shape instead — see
     * {@link SflLexer}.
     */
    public static final Set<String> NAMESPACES = load("/syntax/sfl-namespaces.txt");

    private static Set<String> load(String resource) {
        Set<String> out = new HashSet<>();
        InputStream in = SflSyntaxTables.class.getResourceAsStream(resource);
        if (in == null) {
            return Collections.emptySet();
        }
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                String word = line.trim();
                if (!word.isEmpty()) {
                    out.add(word);
                }
            }
        } catch (IOException ignored) {
            // A missing table degrades to plain-identifier colouring, not a crash.
        }
        return Collections.unmodifiableSet(out);
    }

    private SflSyntaxTables() {
    }
}
