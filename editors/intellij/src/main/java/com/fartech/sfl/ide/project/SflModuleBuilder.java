package com.fartech.sfl.ide.project;

import com.fartech.sfl.ide.SflIcons;
import com.intellij.ide.util.projectWizard.ModuleBuilder;
import com.intellij.ide.util.projectWizard.ModuleWizardStep;
import com.intellij.ide.util.projectWizard.WizardContext;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.module.ModuleType;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.roots.ContentEntry;
import com.intellij.openapi.roots.ModifiableRootModel;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Icon;
import java.io.File;
import java.io.IOException;

/**
 * "New Project" → SFL. Generates the same layout `sfl build init` does and
 * marks src/ as a source root so the tree reads like any other language's.
 */
public final class SflModuleBuilder extends ModuleBuilder {
    String projectVersion = "0.1.0";
    boolean withTests = true;

    @Override
    public ModuleType<?> getModuleType() {
        return SflModuleType.getInstance();
    }

    @Override
    public @NotNull String getPresentableName() {
        return "SFL";
    }

    @Override
    public String getDescription() {
        return "An SFL project: build.sfl, src/ and an optional test suite, ready for 'sfl build'.";
    }

    @Override
    public Icon getNodeIcon() {
        return SflIcons.FILE;
    }

    @Override
    public @Nullable ModuleWizardStep getCustomOptionsStep(WizardContext context, Disposable parentDisposable) {
        return new SflModuleWizardStep(this);
    }

    @Override
    public void setupRootModel(@NotNull ModifiableRootModel model) throws ConfigurationException {
        ContentEntry entry = doAddContentEntry(model);
        if (entry == null) {
            return;
        }
        VirtualFile rootFile = entry.getFile();
        if (rootFile == null) {
            return;
        }
        File root = VfsUtilCore.virtualToIoFile(rootFile);
        try {
            SflScaffold.write(root, model.getModule().getName(), projectVersion, withTests);
        } catch (IOException e) {
            throw new ConfigurationException("Cannot generate the SFL project: " + e.getMessage());
        }
        VfsUtil.markDirtyAndRefresh(true, true, true, rootFile);
        // Deliberately NO source folders: addSourceFolder marks Java source roots,
        // which drafts the module into the JPS build and makes Build Project demand
        // a JDK. Building an SFL module is SflProjectTaskRunner running `sfl build`.
        model.inheritSdk();
    }
}
