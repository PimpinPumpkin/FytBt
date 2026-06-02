package com.fytbt.media

import android.service.notification.NotificationListenerService

/**
 * Stub. We don't process notifications; we only need the user to grant Notification Access
 * so MediaSessionManager.getActiveSessions becomes usable.
 */
class FytNotificationListener : NotificationListenerService()
