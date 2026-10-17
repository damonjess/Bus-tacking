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

import java.util.List;

/** Builds the arrival alert notification channels and posts proximity and background-watch notifications. */
final class AlertNotifier {

    static final String CHANNEL_ID = "arrival_alerts";
    static final String WATCH_CHANNEL_ID = "arrival_watch";
    static final int WATCH_NOTIFICATION_ID = 9001;

    /** Route handed to MainActivity so tapping the notification filters the map. */
    static final String EXTRA_ROUTE = "alert_route";

    private AlertNotifier() {
    }

    static void ensureChannel(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Arrival alerts",
                    NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription("Bus proximity alerts for routes you are waiting for");
            manager.createNotificationChannel(channel);
        }
        if (manager.getNotificationChannel(WATCH_CHANNEL_ID) == null) {
            NotificationChannel watchChannel = new NotificationChannel(WATCH_CHANNEL_ID,
                    "Active alert monitor", NotificationManager.IMPORTANCE_LOW);
            watchChannel.setDescription("Shown while Bus Times Live is watching for an approaching bus");
            watchChannel.setShowBadge(false);
            manager.createNotificationChannel(watchChannel);
        }
    }

    static boolean canPost(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled();
    }

    /** Builds the silent ongoing notification used when polling for armed alerts in the background. */
    static Notification buildWatchNotification(Context context, List<ArrivalAlert> alerts) {
        ensureChannel(context);

        Intent open = new Intent(context, MainActivity.class);
        open.setAction(Intent.ACTION_MAIN);
        open.addCategory(Intent.CATEGORY_LAUNCHER);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(context, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent disarm = new Intent(context, BusTrackingService.class);
        disarm.setAction(BusTrackingService.ACTION_DISARM_ALL_ALERTS);
        PendingIntent disarmIntent = PendingIntent.getService(context, 1, disarm,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String title;
        String body;
        if (alerts.size() == 1) {
            ArrivalAlert a = alerts.get(0);
            title = "Watching for route " + a.route;
            body = "Alerting within " + a.minutes + " min / " + a.radiusMeters + " m of "
                    + (a.placeLabel.isEmpty() ? "your area" : a.placeLabel);
        } else {
            StringBuilder routes = new StringBuilder();
            for (int i = 0; i < alerts.size(); i++) {
                if (i > 0) routes.append(", ");
                routes.append(alerts.get(i).route);
            }
            title = "Watching " + alerts.size() + " bus routes";
            body = "Routes " + routes;
        }

        return new NotificationCompat.Builder(context, WATCH_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification_alert)
                .setContentTitle(title)
                .setContentText(body)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(contentIntent)
                .addAction(0, "Disarm all", disarmIntent)
                .build();
    }

    static void post(Context context, ArrivalAlert alert, String destination, String triggerText) {
        // Once an alert triggers, automatically disarm it so background polling stops cleanly
        ArrivalAlertStore.remove(context, alert.id);

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
