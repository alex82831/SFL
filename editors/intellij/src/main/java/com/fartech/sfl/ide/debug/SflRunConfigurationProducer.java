package com.fartech.sfl.ide.debug;

import com.fartech.sfl.ide.SflFileType;
import com.intellij.execution.actions.ConfigurationContext;
import com.intellij.execution.actions.ConfigurationFromContext;
import com.intellij.execution.actions.LazyRunConfigurationProducer;
import com.intellij.execution.configurations.ConfigurationFactory;
import com.intellij.openapi.util.Ref;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.redhat.devtools.lsp4ij.dap.DAPIJUtils;
import com.redhat.devtools.lsp4ij.dap.DebugAdapterManager;
import com.intellij.execution.configurations.ConfigurationType;
import com.redhat.devtools.lsp4ij.dap.configurations.DAPRunConfiguration;
import org.jetbrains.annotations.NotNull;

/**
 * Decides which run configuration "Run this file" means for an SFL script.
 *
 * LSP4IJ ships a producer of its own, but it recognises an existing
 * configuration by asking only whether the file is debuggable at all — true of
 * every .sfl file — so the first configuration ever created was reused for
 * every later file, and Run kept running the script you opened before this one.
 * This producer compares the file the configuration actually holds, which is
 * what "is this configuration for this file?" means.
 *
 * It shares LSP4IJ's configuration type deliberately: the debugging, the
 * console and the settings editor are all theirs and all correct. Only the
 * matching is ours. Their producer is kept from matching by the configurations
 * carrying no server mappings — see SflDebugAdapterDescriptorFactory.
 */
public final class SflRunConfigurationProducer extends LazyRunConfigurationProducer<DAPRunConfiguration> {

    /** LSP4IJ's configuration type is package-private, so it is found by the id
     *  it registers under rather than by its class. */
    private static final String DAP_TYPE_ID = "DAPConfiguration";

    private volatile ConfigurationFactory factory;

    @Override
    public @NotNull ConfigurationFactory getConfigurationFactory() {
        ConfigurationFactory cached = factory;
        if (cached != null) {
            return cached;
        }
        for (ConfigurationType type : ConfigurationType.CONFIGURATION_TYPE_EP.getExtensionList()) {
            if (DAP_TYPE_ID.equals(type.getId())) {
                cached = type.getConfigurationFactories()[0];
                factory = cached;
                return cached;
            }
        }
        // Unreachable while the plugin declares its dependency on LSP4IJ; if the
        // type ever moves, failing loudly beats producing configurations that
        // silently cannot run.
        throw new IllegalStateException("LSP4IJ's " + DAP_TYPE_ID + " run configuration type is missing");
    }

    private static VirtualFile sflFileOf(@NotNull ConfigurationContext context) {
        var location = context.getLocation();
        if (location == null) {
            return null;
        }
        VirtualFile file = location.getVirtualFile();
        if (file == null || file.isDirectory() || file.getFileType() != SflFileType.INSTANCE) {
            return null;
        }
        return file;
    }

    @Override
    protected boolean setupConfigurationFromContext(@NotNull DAPRunConfiguration configuration,
                                                    @NotNull ConfigurationContext context,
                                                    @NotNull Ref<PsiElement> sourceElement) {
        VirtualFile file = sflFileOf(context);
        if (file == null || context.getProject().isDisposed()) {
            return false;
        }
        // The manager routes to the factory that claims the file, which fills in
        // the command, the launch parameters and the name.
        return DebugAdapterManager.getInstance().prepareConfiguration(configuration, file, context.getProject());
    }

    @Override
    public boolean isConfigurationFromContext(@NotNull DAPRunConfiguration configuration,
                                              @NotNull ConfigurationContext context) {
        VirtualFile file = sflFileOf(context);
        if (file == null) {
            return false;
        }
        String configured = configuration.getFile();
        return configured != null && !configured.isBlank()
                && FileUtil.pathsEqual(configured, DAPIJUtils.getFilePath(file));
    }

    /**
     * When both producers offer a configuration for the same SFL file they offer
     * the same one — both go through the manager — so taking ours keeps the name
     * this plugin gives it and the matching that goes with it.
     */
    @Override
    public boolean shouldReplace(@NotNull ConfigurationFromContext self, @NotNull ConfigurationFromContext other) {
        return other.getConfiguration() instanceof DAPRunConfiguration
                && !other.isProducedBy(SflRunConfigurationProducer.class);
    }

    @Override
    public boolean isPreferredConfiguration(ConfigurationFromContext self, ConfigurationFromContext other) {
        return other == null
                || (other.getConfiguration() instanceof DAPRunConfiguration
                    && !other.isProducedBy(SflRunConfigurationProducer.class));
    }
}
