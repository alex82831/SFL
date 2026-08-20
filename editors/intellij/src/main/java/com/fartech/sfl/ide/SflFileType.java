package com.fartech.sfl.ide;

import com.intellij.openapi.fileTypes.LanguageFileType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Icon;

public final class SflFileType extends LanguageFileType {
    public static final SflFileType INSTANCE = new SflFileType();

    private SflFileType() {
        super(SflLanguage.INSTANCE);
    }

    @Override
    public @NotNull String getName() {
        return "SFL";
    }

    @Override
    public @NotNull String getDescription() {
        return "SFL script";
    }

    @Override
    public @NotNull String getDefaultExtension() {
        return "sfl";
    }

    @Override
    public @Nullable Icon getIcon() {
        return SflIcons.FILE;
    }
}
