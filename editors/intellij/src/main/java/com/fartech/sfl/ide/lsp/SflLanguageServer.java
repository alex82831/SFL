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
    public SflLanguageServer() {
        File binary = SflBinaryLocator.resolve();
        if (binary == null) {
            throw new CannotStartProcessException(
                    "The sfl binary was not found. Set its path in Settings | Tools | SFL, "
                            + "or export SFL_PATH, or install sfl on PATH.");
        }
        super.setCommands(List.of(binary.getAbsolutePath(), "lsp"));
    }
}
