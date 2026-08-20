package com.fartech.sfl.ide.lsp;

import com.fartech.sfl.ide.settings.SflBinaryLocator;
import com.redhat.devtools.lsp4ij.server.CannotStartProcessException;
import com.redhat.devtools.lsp4ij.server.ProcessStreamConnectionProvider;

import java.io.File;
import java.util.List;

/**
 * Starts `sfl lsp`. The locator hands back an absolute path — never a bare
 * "sfl" left for exec-time PATH resolution, because a Dock-launched IDE does
 * not have the terminal's PATH and the resulting IOException is useless to a
 * reader. When nothing resolves, the factory has already declined to start
 * the server and pointed at Settings; this throw is only the backstop for a
 * binary deleted in between.
 */
public final class SflLanguageServer extends ProcessStreamConnectionProvider {
    public SflLanguageServer(String projectBasePath) {
        File binary = SflBinaryLocator.resolve();
        if (binary == null) {
            throw new CannotStartProcessException(
                    "The sfl binary was not found. Set its path in Settings | Tools | SFL, "
                            + "or export SFL_PATH, or install sfl on PATH.");
        }
        super.setCommands(List.of(binary.getAbsolutePath(), "lsp"));
        // Settings ride in as environment: SFL_HOME so imports resolve installed
        // packages, SFL_LSP_UNDEFINED to switch the semantic pass.
        super.setUserEnvironmentVariables(
                com.fartech.sfl.ide.settings.SflEnvironment.forChildProcesses());
        super.setIncludeSystemEnvironmentVariables(true);
        // The working directory decides what ./sfl_packages and ./packages mean:
        // the importer's package roots are cwd-relative, exactly as on the
        // command line. Without this the server resolves them against wherever
        // the IDE happened to start, and project-local packages go red.
        if (projectBasePath != null && !projectBasePath.isBlank()) {
            super.setWorkingDirectory(projectBasePath);
        }
    }
}
