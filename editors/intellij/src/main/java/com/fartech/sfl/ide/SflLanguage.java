package com.fartech.sfl.ide;

import com.intellij.lang.Language;

public final class SflLanguage extends Language {
    public static final SflLanguage INSTANCE = new SflLanguage();

    private SflLanguage() {
        super("SFL");
    }
}
