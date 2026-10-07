package org.bustimes.app;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

/** Builds the arrival alert notification channel and posts proximity alerts. */
final class AlertNotifier {

    static final String CHANNEL_ID = "arrival_alerts";
    /** Route handed to MainActivity so tapping the notification filters the map. */
    static final String EXTRA_ROUTE = "alert_route";

    private AlertNotifier() {
    }

    static void ensureChannel(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null || manager.getNotificationChannel(CHANNEL_ID) != null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Arrival alerts",
                NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription("Bus proximity alerts for routes you are waiting for");
        manager.createNotificationChannel(channel);
    }

    static boolean canPost(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled();
    }

    static void post(Context context, ArrivalAlert alert, String destination, String triggerText) {
        if (!canPost(context)) {
            return;
        }
        ensureChannel(context);

        Intent open = new Intent(context, MainActivity.class);
        open.setAction(Intent.ACTION_MAIN);
        open.addCategory(Intent.CATEGORY_LAUNCHER);
        open.putExtra(EXTRA_ROUTE, alert.route);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        int requestCode = alert.id.hashCode();
        PendingIntent pending = PendingIntent.getActivity(context, requestCode, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String title = "Route " + alert.route + " is getting close";
        String text = triggerText;
        if (destination != null && !destination.isEmpty()) {
            text = text + " \u00B7 to " + destination;
        }

        Notification notification = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification_alert)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setAutoCancel(true)
                .setContentIntent(pending)
                .build();

        try {
            NotificationManagerCompat.from(context).notify(requestCode, notification);
        } catch (SecurityException ignored) {
            // notification permission was revoked between the check and the call
        }
    }
}
