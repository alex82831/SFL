package com.fartech.sfl.ide.lsp;

import com.fartech.sfl.ide.settings.SflBinaryLocator;
import com.fartech.sfl.ide.settings.SflSettings;
import com.fartech.sfl.ide.settings.SflSettingsConfigurable;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.project.Project;
import com.redhat.devtools.lsp4ij.LanguageServerEnablementSupport;
import com.redhat.devtools.lsp4ij.LanguageServerFactory;
import com.redhat.devtools.lsp4ij.server.StreamConnectionProvider;
import org.jetbrains.annotations.NotNull;

public final class SflLanguageServerFactory
        implements LanguageServerFactory, LanguageServerEnablementSupport {

    private static volatile boolean warned = false;

    @Override
    public @NotNull StreamConnectionProvider createConnectionProvider(@NotNull Project project) {
        return new SflLanguageServer();
    }

    /**
     * The server only claims to be enabled when the binary actually resolves.
     * A missing binary therefore never becomes a process-start crash; it becomes
     * one balloon explaining where to point the plugin.
     */
    @Override
    public boolean isEnabled(@NotNull Project project) {
        if (!SflSettings.getInstance().isServerEnabled()) {
            return false;
        }
        if (SflBinaryLocator.resolve() != null) {
            return true;
        }
        warnOnce(project);
        return false;
    }

    @Override
    public void setEnabled(boolean enabled, @NotNull Project project) {
        SflSettings.getInstance().setServerEnabled(enabled);
    }

    private static void warnOnce(Project project) {
        if (warned) {
            return;
        }
        warned = true;
        Notification n = NotificationGroupManager.getInstance()
                .getNotificationGroup("SFL")
                .createNotification(
                        "SFL language server not started",
                        "The sfl binary was not found on PATH, in SFL_PATH, or in the usual "
                                + "install directories. Highlighting still works; diagnostics and "
                                + "completion need the binary.",
                        NotificationType.WARNING);
        n.addAction(new AnAction("Set Path…") {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                ShowSettingsUtil.getInstance()
                        .showSettingsDialog(project, SflSettingsConfigurable.class);
                warned = false; // a fixed path deserves a fresh verdict next time
            }
        });
        n.notify(project);
    }
}
