package com.ykb.review.ui

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.ykb.review.config.ConfigurationError
import com.ykb.review.config.ReviewSettingsConfigurable

object ReviewNotifier {
    private const val GROUP = "YKB AI Review Notifications"

    /** Bildirimde gösterilecek hızlı aksiyon (tıklanınca bildirim kapanır). */
    data class Action(val text: String, val run: () -> Unit)

    fun notify(
        project: Project?,
        content: String,
        type: NotificationType = NotificationType.INFORMATION,
        title: String = "YKB AI Review",
        actions: List<Action> = emptyList(),
    ) {
        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup(GROUP)
            .createNotification(title, content, type)
        actions.forEach { action -> notification.addAction(NotificationAction.createSimpleExpiring(action.text) { action.run() }) }
        notification.notify(project)
    }

    /** Hata bildirimi; yapılandırma hatalarında "Ayarları Aç" aksiyonu ekler (VS Code runSafely karşılığı). */
    fun error(project: Project?, error: Throwable, prefix: String? = null, retry: (() -> Unit)? = null) {
        val message = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup(GROUP)
            .createNotification("YKB AI Review", if (prefix != null) "$prefix: $message" else message, NotificationType.ERROR)
        if (error is ConfigurationError) {
            notification.addAction(NotificationAction.createSimpleExpiring("Ayarları Aç") {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, ReviewSettingsConfigurable::class.java)
            })
        } else if (retry != null) {
            notification.addAction(NotificationAction.createSimpleExpiring("Tekrar Dene") { retry() })
        }
        notification.notify(project)
    }
}
