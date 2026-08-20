package com.fartech.sfl.ide.debug;

import com.fartech.sfl.ide.settings.SflBinaryLocator;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.configurations.RunConfigurationOptions;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.fartech.sfl.ide.SflFileType;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.redhat.devtools.lsp4ij.dap.DebugMode;
import com.redhat.devtools.lsp4ij.dap.client.LaunchUtils;
import com.redhat.devtools.lsp4ij.dap.configurations.DAPRunConfigurationOptions;
import com.redhat.devtools.lsp4ij.dap.configurations.options.WorkingDirectoryConfigurable;
import com.redhat.devtools.lsp4ij.dap.definitions.DebugAdapterServerDefinition;
import com.redhat.devtools.lsp4ij.dap.descriptors.DefaultDebugAdapterDescriptor;
import com.redhat.devtools.lsp4ij.dap.descriptors.ServerReadyConfig;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.util.Map;

/**
 * The runtime half of the SFL debug wiring, and deliberately self-sufficient:
 * it resolves the binary and forces the socket command and the ready pattern
 * itself instead of trusting whatever an old run configuration happens to have
 * stored. Temporary configurations outlive plugin upgrades; behaviour here must
 * not depend on their contents being current.
 */
final class SflDebugAdapterDescriptor extends DefaultDebugAdapterDescriptor {

    /** Must match Dap.readyLine in the sfl binary — it is a wire contract. */
    static final String READY_PATTERN = "DAP server listening on port ${port}";

    SflDebugAdapterDescriptor(@NotNull RunConfigurationOptions options,
                              @NotNull ExecutionEnvironment environment,
                              @NotNull DebugAdapterServerDefinition serverDefinition) {
        super(options, environment, serverDefinition);
    }

    @Override
    public @NotNull ProcessHandler startServer() throws ExecutionException {
        File binary = SflBinaryLocator.resolve();
        if (binary == null) {
            throw new ExecutionException(
                    "The sfl binary was not found. Set its path in Settings | Tools | SFL, "
                            + "or export SFL_PATH, or install sfl on PATH.");
        }
        GeneralCommandLine command =
                new GeneralCommandLine(binary.getAbsolutePath(), "dap", "--socket");
        String workingDirectory = null;
        if (options instanceof WorkingDirectoryConfigurable configurable) {
            workingDirectory = configurable.getWorkingDirectory();
        }
        if (workingDirectory == null || workingDirectory.isBlank()) {
            workingDirectory = environment.getProject().getBasePath();
        }
        if (workingDirectory != null && !workingDirectory.isBlank()) {
            command.withWorkDirectory(workingDirectory);
        }
        return startServer(command);
    }

    @Override
    public @NotNull ServerReadyConfig getServerReadyConfig(@NotNull DebugMode debugMode) {
        return new ServerReadyConfig(READY_PATTERN);
    }

    /**
     * The breakpoint handler asks THIS method — not the factory's twin — whether
     * a breakpoint's file belongs to the session (supportsBreakpoint), and the
     * inherited implementation walks the server definition's mappings, which are
     * always empty for extension-registered servers in LSP4IJ 0.19: the
     * extension point has no mapping elements. Without this override every
     * breakpoint is silently dropped and programs run straight through.
     */
    @Override
    public boolean isDebuggableFile(@NotNull VirtualFile file, @NotNull Project project) {
        return file.getFileType() == SflFileType.INSTANCE;
    }

    /**
     * The launch request's arguments. The default reads the launch JSON stored
     * on the run configuration — which the producer flow leaves empty, and an
     * empty launch has no 'program', which the adapter rightly refuses; the
     * refusal then drowns inside the IDE's future chain as a silent dead
     * session. So when the stored parameters are missing the essentials, they
     * are rebuilt here from the plugin's own template and the configuration's
     * file and working directory.
     */
    @Override
    public Map<String, Object> getDapParameters() {
        Map<String, Object> stored = super.getDapParameters();
        if (stored != null && stored.containsKey("program")) {
            return stored;
        }
        if (options instanceof DAPRunConfigurationOptions dapOptions) {
            return LaunchUtils.getDapParameters(
                    SflDebugAdapterDescriptorFactory.LAUNCH_JSON,
                    new LaunchUtils.LaunchContext(
                            dapOptions.getFile(), dapOptions.getWorkingDirectory()));
        }
        return stored;
    }
}
