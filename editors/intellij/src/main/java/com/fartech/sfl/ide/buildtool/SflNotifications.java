package com.fartech.sfl.ide.buildtool;

import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

final class SflNotifications {
    static void warn(@NotNull Project project, @NotNull String title, @NotNull String text) {
        NotificationGroupManager.getInstance()
                .getNotificationGroup("SFL")
                .createNotification(title, text, NotificationType.WARNING)
                .notify(project);
    }

    static void info(@NotNull Project project, @NotNull String title, @NotNull String text) {
        NotificationGroupManager.getInstance()
                .getNotificationGroup("SFL")
                .createNotification(title, text, NotificationType.INFORMATION)
                .notify(project);
    }

    private SflNotifications() {
    }
}
