package com.fartech.sfl.ide.debug;

import com.fartech.sfl.ide.SflFileType;
import com.fartech.sfl.ide.settings.SflBinaryLocator;
import com.intellij.execution.configurations.RunConfiguration;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.redhat.devtools.lsp4ij.dap.DebugMode;
import com.redhat.devtools.lsp4ij.dap.DebugServerWaitStrategy;
import com.redhat.devtools.lsp4ij.dap.LaunchConfiguration;
import com.redhat.devtools.lsp4ij.dap.configurations.DAPRunConfiguration;
import com.redhat.devtools.lsp4ij.dap.descriptors.DebugAdapterDescriptorFactory;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.util.List;

/**
 * Wires `sfl dap` into LSP4IJ's debug engine.
 *
 * LSP4IJ owns the run configuration type, the producer behind the Run/Debug
 * buttons on "Current File", and the whole DAP client; this factory only has to
 * say which files are ours, which command starts the adapter, and what the
 * launch request looks like. A script is its own entry point — SFL has no main
 * function — so "debug the current file" is exactly launch(program = ${file}).
 */
public final class SflDebugAdapterDescriptorFactory extends DebugAdapterDescriptorFactory {

    /** Also the fallback the descriptor rebuilds parameters from; keep in sync. */
    static final String LAUNCH_JSON = """
            {
              "type": "sfl",
              "name": "Launch SFL script",
              "request": "launch",
              "program": "${file}",
              "cwd": "${workspaceFolder}"
            }""";

    private static final List<LaunchConfiguration> LAUNCH = List.of(
            new LaunchConfiguration("launch-sfl", "Launch SFL script", LAUNCH_JSON, DebugMode.LAUNCH));

    @Override
    public boolean isDebuggableFile(@NotNull VirtualFile file, @NotNull Project project) {
        return file.getFileType() == SflFileType.INSTANCE;
    }

    @Override
    public com.redhat.devtools.lsp4ij.dap.descriptors.DebugAdapterDescriptor createDebugAdapterDescriptor(
            @NotNull com.intellij.execution.configurations.RunConfigurationOptions options,
            @NotNull com.intellij.execution.runners.ExecutionEnvironment environment) {
        // Our own descriptor: it forces the command and the ready pattern, so a
        // stale temporary run configuration from an older plugin cannot break
        // the transport (see SflDebugAdapterDescriptor).
        return new SflDebugAdapterDescriptor(options, environment, getServerDefinition());
    }

    @Override
    public boolean prepareConfiguration(@NotNull RunConfiguration configuration,
                                        @NotNull VirtualFile file,
                                        @NotNull Project project) {
        boolean ok = super.prepareConfiguration(configuration, file, project);
        if (ok && configuration instanceof DAPRunConfiguration dap) {
            // The producer fills in the file; the adapter command is ours to name.
            File binary = SflBinaryLocator.resolve();
            String command = binary != null ? quoteIfNeeded(binary.getAbsolutePath()) : "sfl";
            // Socket transport, not stdio: the IDE's process handler re-decodes
            // stdio as text, and a binary protocol deserves a binary channel. The
            // adapter prints the line below and the tracker extracts the port.
            dap.setCommand(command + " dap --socket");
            dap.setDebugServerWaitStrategy(DebugServerWaitStrategy.TRACE);
            dap.setDebugServerReadyPattern(SflDebugAdapterDescriptor.READY_PATTERN);
            // Without this the launch request goes out with EMPTY arguments:
            // LaunchUtils reads the launch JSON from the run configuration, and
            // the producer flow fills file/serverId but never this content.
            dap.setLaunchConfiguration(LAUNCH_JSON);
            // Deliberately no server mappings. LSP4IJ's own producer decides that
            // an existing configuration belongs to the current file by asking the
            // configuration whether the file is debuggable — which its mappings
            // answer for every .sfl file alike, so one configuration was reused
            // for all of them and Run ran the wrong script. With no mappings that
            // producer never claims a stale configuration, and
            // SflRunConfigurationProducer does the matching properly, by file.
            // Breakpoints are unaffected: they ask the factory, not the mappings.

            // Two scripts are very often both called main.sfl, so the name says
            // which project this one is, the way a multi-module build would.
            VirtualFile parent = file.getParent();
            String owner = parent == null ? null : parent.getName();
            if ("src".equals(owner) && parent.getParent() != null) {
                owner = parent.getParent().getName();
            }
            dap.setName(owner == null || owner.isBlank()
                    ? file.getName()
                    : file.getName() + " (" + owner + ")");
            String base = project.getBasePath();
            dap.setWorkingDirectory(base != null ? base : parent != null ? parent.getPath() : "");
        }
        return ok;
    }

    @Override
    public @NotNull List<LaunchConfiguration> getLaunchConfigurations() {
        return LAUNCH;
    }

    private static String quoteIfNeeded(String path) {
        return path.contains(" ") ? '"' + path + '"' : path;
    }
}
