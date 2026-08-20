package com.fartech.sfl.ide.project;

import com.fartech.sfl.ide.SflIcons;
import com.intellij.openapi.module.ModuleType;
import com.intellij.openapi.module.ModuleTypeManager;
import org.jetbrains.annotations.NotNull;

import javax.swing.Icon;

public final class SflModuleType extends ModuleType<SflModuleBuilder> {
    public static final String ID = "SFL_MODULE";

    public SflModuleType() {
        super(ID);
    }

    public static SflModuleType getInstance() {
        return (SflModuleType) ModuleTypeManager.getInstance().findByID(ID);
    }

    @Override
    public @NotNull SflModuleBuilder createModuleBuilder() {
        return new SflModuleBuilder();
    }

    @Override
    public @NotNull String getName() {
        return "SFL";
    }

    @Override
    public @NotNull String getDescription() {
        return "An SFL project: build.sfl, src/ and an optional test suite, ready for 'sfl build'.";
    }

    @Override
    public @NotNull Icon getNodeIcon(boolean isOpened) {
        return SflIcons.FILE;
    }
}
