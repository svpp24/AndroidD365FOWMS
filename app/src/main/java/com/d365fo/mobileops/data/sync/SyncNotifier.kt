package com.d365fo.mobileops.data.sync

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.ForegroundInfo
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.d365fo.mobileops.R
import com.d365fo.mobileops.data.local.entity.SyncOperation
import com.d365fo.mobileops.presentation.MainActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Posts a real Android notification when a posting is dead-lettered.
 *
 * Everything else in this app's failure handling assumes someone is looking at the screen -
 * a busy indicator, an error banner, a badge in the top bar. A dead-lettered posting almost
 * never happens while anyone is looking: the worker captured a receipt, tapped Post, walked
 * away to the next pallet, and the failure surfaces minutes later in the background. Without a
 * system notification that failure sits silently in the failed-sync list until someone happens
 * to open the app and check - which, on a device that spends most of a shift in someone's
 * holster, could be hours. This is the one thing in the app that genuinely needs the
 * notification shade rather than an in-app indicator, because it is the one thing that can
 * happen while the app is not the thing on screen.
 *
 * Deliberately narrow: only dead letters notify, not every retry. A retry is the normal,
 * expected shape of posting on warehouse wifi - see RetryAfterInterceptor and the queue's own
 * backoff ladder - and a notification for each one would train everyone to swipe it away
 * without reading it, which defeats the one that actually matters.
 */
@Singleton
class SyncNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    fun createChannel() {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Posting failures",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "A captured receipt or transfer could not be posted to D365FO"
            },
        )

        // Low, not default: this one is a convenience for a worker who has put the device down
        // mid-pallet, not an alert - it should sit quietly in the shade rather than heads-up
        // over whatever else is on screen the way a posting failure does.
        manager.createNotificationChannel(
            NotificationChannel(
                PROGRESS_CHANNEL_ID,
                "Receiving progress",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "A line was added to a receipt, or a receipt or transfer finished"
            },
        )

        manager.createNotificationChannel(
            NotificationChannel(
                COUNT_CHANNEL_ID,
                "Count submission",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Counted quantities being sent to D365FO"
            },
        )
    }

    /** A new TRIGGERED count for this warehouse - high priority, it jumps the queue. */
    fun notifyTriggeredCount(journalId: String, description: String?) {
        if (!hasNotificationPermission()) return
        val openApp = PendingIntent.getActivity(
            context,
            journalId.hashCode(),
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("New count: $journalId")
            .setContentText(description?.takeIf { it.isNotBlank() } ?: "A triggered count is waiting in Inventory count.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openApp)
            .build()
        safeNotify(journalId.hashCode(), notification)
    }

    /** The ongoing notification an End count job shows while it runs in the foreground. */
    fun countSubmitForegroundInfo(journalId: String): ForegroundInfo {
        val notification = NotificationCompat.Builder(context, COUNT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Submitting count")
            .setContentText(journalId.ifBlank { "Sending counted quantities to D365FO" })
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
        val id = COUNT_NOTIFICATION_BASE + (journalId.hashCode() and 0xFFF)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(id, notification)
        }
    }

    /**
     * @param aggregateId The purchase order or transfer order number, shown so the notification
     * says which document needs attention rather than just "something failed".
     */
    fun notifyDeadLetter(aggregateId: String, operation: SyncOperationLabel) {
        // Never crashes on a missing grant - API 33+ requires POST_NOTIFICATIONS at runtime,
        // and a worker who denied it (or has not been asked yet) should get a silently skipped
        // notification, not a SecurityException that takes the background worker down with it.
        if (!hasNotificationPermission()) return

        val openApp = PendingIntent.getActivity(
            context,
            aggregateId.hashCode(),
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Could not post $aggregateId")
            .setContentText("${operation.label} failed - tap to review.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openApp)
            .build()

        safeNotify(aggregateId.hashCode(), notification)
    }

    /**
     * A line was banked into the receipt or transfer in hand - not yet posted, just captured.
     *
     * Confirms the scan actually landed. On a device an operator is not staring at between
     * items, the beep already does that; this is the same confirmation for whoever glances at
     * the notification shade instead, or is reading the screen while gloved and half-turned
     * away.
     */
    fun notifyLineAdded(itemName: String) {
        if (!hasNotificationPermission()) return

        val notification = NotificationCompat.Builder(context, PROGRESS_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentText("Added $itemName")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .build()

        safeNotify(LINE_ADDED_ID, notification)
    }

    /**
     * The whole receipt or transfer was captured, and the flow's own live attempt to hand it to
     * D365FO has now been made.
     *
     * "Hand to", not "post" - landing is a fast, cheap INSERT now (HSMobileOpsPostQueue, staged
     * at Pending); the real registration work happens later, off a supervisor's or operator's
     * own trigger on the Activity screen, not here. So [postedLive] true means only "D365FO has
     * this now and it is Pending" - the wording says so rather than claiming more than that. A
     * Pending row that later fails to register gets its own signal too - see the status list on
     * the Activity screen, which is where that outcome actually surfaces, not this notification.
     * False means the live attempt itself failed - no signal, a timeout, whatever - and the
     * capture has fallen back to the same durable sync queue as always, to be retried without the
     * operator waiting on it. Either way nothing is lost. A capture that the sync queue itself
     * eventually gives up retrying still gets its own, separate, high-priority notification -
     * see [notifyDeadLetter].
     */
    fun notifyWorkCompleted(aggregateId: String, postedLive: Boolean) {
        if (!hasNotificationPermission()) return

        val openApp = PendingIntent.getActivity(
            context,
            aggregateId.hashCode(),
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification = NotificationCompat.Builder(context, PROGRESS_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (postedLive) "$aggregateId received" else "$aggregateId captured")
            .setContentText(
                if (postedLive) {
                    "Received - posting shortly."
                } else {
                    "Saved - retrying automatically."
                },
            )
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .setContentIntent(openApp)
            .build()

        safeNotify(aggregateId.hashCode(), notification)
    }

    /** See the identical API-33+ grant check inlined in [notifyDeadLetter]; shared here too. */
    /**
     * Posting a notification throws SecurityException without POST_NOTIFICATIONS (API 33+, a
     * grant the worker can refuse). A notification is never worth a crash - logged and dropped.
     */
    private fun safeNotify(id: Int, notification: android.app.Notification) {
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (e: SecurityException) {
            timber.log.Timber.w(e, "notification not shown: permission refused")
        }
    }

    private fun hasNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private companion object {
        const val CHANNEL_ID = "posting_failures"
        const val PROGRESS_CHANNEL_ID = "receiving_progress"
        const val COUNT_CHANNEL_ID = "count_submission"
        const val COUNT_NOTIFICATION_BASE = 20000

        // Fixed rather than hashed per line: a second "line added" notification is meant to
        // replace the first in the shade, not stack up one per item on a fifty-line order.
        const val LINE_ADDED_ID = 9000
    }
}

/** The human label for a dead-lettered operation, kept separate from SyncOperation's own name. */
enum class SyncOperationLabel(val label: String) {
    PRODUCT_RECEIPT("Product receipt"),
    TRANSFER_SHIPMENT("Transfer shipment"),
    TRANSFER_RECEIPT("Transfer receipt"),
    PURCHASE_ORDER("New purchase order"),
    TRANSFER_ORDER("New transfer order"),
    PURCHASE_CONFIRMATION("PO confirmation"),
    FEEDBACK("Feedback"),
    PURCHASE_RETURN("Return to vendor"),
    OTHER("Posting");

    companion object {
        fun of(operation: SyncOperation): SyncOperationLabel = when (operation) {
            SyncOperation.POST_PRODUCT_RECEIPT -> PRODUCT_RECEIPT
            SyncOperation.TRANSFER_ORDER_SHIP -> TRANSFER_SHIPMENT
            SyncOperation.TRANSFER_ORDER_RECEIVE -> TRANSFER_RECEIPT
            SyncOperation.CREATE_PURCHASE_ORDER -> PURCHASE_ORDER
            SyncOperation.CREATE_TRANSFER_ORDER -> TRANSFER_ORDER
            SyncOperation.CONFIRM_PURCHASE_ORDER -> PURCHASE_CONFIRMATION
            SyncOperation.SEND_FEEDBACK -> FEEDBACK
            SyncOperation.CREATE_PURCHASE_RETURN -> PURCHASE_RETURN
            else -> OTHER
        }
    }
}
