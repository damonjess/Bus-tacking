package org.bustimes.app;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

/**
 * Light "Selected Bus" bottom sheet matching the reference design: route title,
 * live status, next-stop ETA, vehicle/reg line and Favorite Route / Alert Me actions.
 */
final class BusDetailsSheet {

    /** Listener bridge so the sheet can drive the map/activity. */
    interface Callbacks {
        void onNavigateToBus(double latitude, double longitude);

        void onFollowBus(String busId, boolean follow);

        boolean isFollowingBus(String busId);

        void onShowBusOnMap(double latitude, double longitude);

        void onToggleFavorite(String route);

        boolean isFavorite(String route);

        void onToggleAlert(String busId, String route);

        boolean isAlertArmed(String busId, String route);
    }

    private BusDetailsSheet() {
    }

    static void show(Activity activity, BusSnapshot snapshot, Callbacks callbacks) {
        Context context = activity;
        int pad = UiTheme.dp(context, 20);

        final Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCanceledOnTouchOutside(true);

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE);
        float[] radii = {UiTheme.dp(context, 24), UiTheme.dp(context, 24), 0, 0, 0, 0, 0, 0};
        bg.setCornerRadii(radii);
        root.setBackground(bg);
        root.setPadding(pad, dp14(context), pad, pad);

        // ---- grab handle -------------------------------------------------------
        View handle = new View(context);
        GradientDrawable handleBg = new GradientDrawable();
        handleBg.setColor(Color.argb(60, 0, 0, 0));
        handleBg.setCornerRadius(UiTheme.dp(context, 3));
        handle.setBackground(handleBg);
        LinearLayout.LayoutParams handleParams = new LinearLayout.LayoutParams(dp40(context), UiTheme.dp(context, 5));
        handleParams.gravity = Gravity.CENTER_HORIZONTAL;
        handleParams.bottomMargin = dp12(context);
        root.addView(handle, handleParams);

        int ink = Color.rgb(20, 26, 44);
        int dim = Color.argb(180, 90, 100, 124);

        // ---- "Selected Bus" label ----------------------------------------------
        TextView selectedLabel = new TextView(context);
        selectedLabel.setText("Selected bus");
        selectedLabel.setTextColor(dim);
        selectedLabel.setTextSize(13);
        root.addView(selectedLabel);

        // ---- Route title --------------------------------------------------------
        String titleText;
        if (snapshot.lineName.isEmpty()) {
            titleText = snapshot.destinationName;
        } else if (snapshot.destinationName.isEmpty()) {
            titleText = snapshot.lineName;
        } else {
            titleText = snapshot.lineName + " to " + snapshot.destinationName;
        }
        TextView routeTitle = new TextView(context);
        routeTitle.setText(titleText);
        routeTitle.setVisibility(titleText.isEmpty() ? View.GONE : View.VISIBLE);
        routeTitle.setTextColor(ink);
        routeTitle.setTextSize(20);
        routeTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleParams.topMargin = dp8(context);
        root.addView(routeTitle, titleParams);

        // ---- Live status --------------------------------------------------------
        int statusColor = statusColor(snapshot);
        String statusText = statusText(snapshot);
        TextView liveStatus = new TextView(context);
        liveStatus.setText(statusText);
        liveStatus.setVisibility(statusText.isEmpty() ? View.GONE : View.VISIBLE);
        liveStatus.setTextColor(statusColor);
        liveStatus.setTextSize(15);
        liveStatus.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        LinearLayout.LayoutParams liveParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        liveParams.topMargin = dp8(context);
        root.addView(liveStatus, liveParams);

        // ---- Next stop ETA ------------------------------------------------------
        String etaLineValue = etaLineText(snapshot);
        TextView etaLine = new TextView(context);
        etaLine.setText(etaLineValue);
        etaLine.setVisibility(etaLineValue.isEmpty() ? View.GONE : View.VISIBLE);
        etaLine.setTextColor(ink);
        etaLine.setTextSize(17);
        etaLine.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        LinearLayout.LayoutParams etaParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        etaParams.topMargin = dp10(context);
        root.addView(etaLine, etaParams);

        // ---- Vehicle / reg + occupancy ------------------------------------------
        TextView vehicleLine = new TextView(context);
        String vehicle = snapshot.vehicleId == null ? "" : snapshot.vehicleId.trim();
        String vehicleDetail = snapshot.regOverride == null || snapshot.regOverride.isEmpty()
                ? snapshot.operatorName
                : snapshot.regOverride;
        if (vehicleDetail != null && !vehicleDetail.isEmpty()) {
            vehicle = vehicle.isEmpty() ? vehicleDetail : vehicle + " • " + vehicleDetail;
        }
        if (!"Information Unknown".equals(snapshot.occupancy)) {
            vehicle += "  ·  " + occupancyLabel(snapshot.occupancy);
        }
        vehicleLine.setText(vehicle);
        vehicleLine.setVisibility(vehicle.isEmpty() ? View.GONE : View.VISIBLE);
        vehicleLine.setTextColor(dim);
        vehicleLine.setTextSize(13);
        LinearLayout.LayoutParams vehicleParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        vehicleParams.topMargin = dp8(context);
        root.addView(vehicleLine, vehicleParams);

        // ---- Data freshness -----------------------------------------------------
        String updated = snapshot.updatedText();
        if (!updated.isEmpty()) {
            TextView updatedLine = new TextView(context);
            updatedLine.setText(updated);
            updatedLine.setTextColor(dim);
            updatedLine.setTextSize(12);
            LinearLayout.LayoutParams updatedParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            updatedParams.topMargin = dp6(context);
            root.addView(updatedLine, updatedParams);
        }

        // ---- Distance + heading -------------------------------------------------
        if (!snapshot.distanceText.isEmpty() || !Float.isNaN(snapshot.bearing)) {
            TextView extraLine = new TextView(context);
            StringBuilder extra = new StringBuilder();
            if (!snapshot.distanceText.isEmpty()) {
                extra.append(snapshot.distanceText);
            }
            if (!Float.isNaN(snapshot.bearing) && snapshot.bearing >= 0) {
                if (extra.length() > 0) {
                    extra.append("  ·  ");
                }
                extra.append("heading ").append(snapshot.compassBearingText());
            }
            if (snapshot.speedKph > 0.5f) {
                extra.append("  ·  ").append(snapshot.speedText());
            }
            extraLine.setText(extra.toString());
            extraLine.setTextColor(dim);
            extraLine.setTextSize(13);
            LinearLayout.LayoutParams extraParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            extraParams.topMargin = dp8(context);
            root.addView(extraLine, extraParams);
        }

        // ---- Right-side actions: Favorite + Alert -------------------------------
        LinearLayout actions = new LinearLayout(context);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);

        boolean favorite = callbacks != null && callbacks.isFavorite(snapshot.lineName);
        actions.addView(iconAction(context, favorite ? "★" : "☆",
                favorite ? "Favorited" : "Favorite Route", statusColor, () -> {
                    if (callbacks != null) {
                        callbacks.onToggleFavorite(snapshot.lineName);
                        dialog.dismiss();
                    }
                }));

        boolean armed = callbacks != null && callbacks.isAlertArmed(snapshot.busId, snapshot.lineName);
        actions.addView(iconAction(context, "🔔",
                armed ? "Alert on" : "Alert Me", armed ? statusColor : ink, () -> {
                    if (callbacks != null) {
                        callbacks.onToggleAlert(snapshot.busId, snapshot.lineName);
                        dialog.dismiss();
                    }
                }));

        LinearLayout.LayoutParams actionsParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        actionsParams.topMargin = dp10(context);
        root.addView(actions, actionsParams);

        // ---- Bottom action row ---------------------------------------------------
        LinearLayout bottomRow = new LinearLayout(context);
        bottomRow.setOrientation(LinearLayout.HORIZONTAL);

        if (callbacks != null) {
            boolean following = callbacks.isFollowingBus(snapshot.busId);
            bottomRow.addView(textPill(context, following ? "✓ Following" : "◎ Follow bus",
                    following ? UiTheme.BLUE : ink, () -> {
                        callbacks.onFollowBus(snapshot.busId, !following);
                        dialog.dismiss();
                    }));

            if (!Double.isNaN(snapshot.latitude)) {
                bottomRow.addView(textPill(context, "➤ Walk me there (AR)", ink, () -> {
                    callbacks.onNavigateToBus(snapshot.latitude, snapshot.longitude);
                    dialog.dismiss();
                }));

                bottomRow.addView(textPill(context, "⦿ Show on map", ink, () -> {
                    callbacks.onShowBusOnMap(snapshot.latitude, snapshot.longitude);
                    dialog.dismiss();
                }));
            }

            bottomRow.addView(textPill(context, "⇪ Share", ink, () -> {
                String text = String.format(Locale.UK,
                        "Bus %s → %s. %s. %s",
                        snapshot.lineName,
                        snapshot.destinationName,
                        etaLineText(snapshot),
                        snapshot.distanceText.isEmpty() ? "" : "Currently " + snapshot.distanceText + ".");
                Intent send = new Intent(Intent.ACTION_SEND);
                send.setType("text/plain");
                send.putExtra(Intent.EXTRA_TEXT, text);
                context.startActivity(Intent.createChooser(send, "Share bus"));
                dialog.dismiss();
            }));
        }

        HorizontalScrollView scroller = new HorizontalScrollView(context);
        scroller.setHorizontalScrollBarEnabled(false);
        scroller.addView(bottomRow);
        LinearLayout.LayoutParams scrollerParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        scrollerParams.topMargin = dp14(context);
        root.addView(scroller, scrollerParams);

        dialog.setContentView(root);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            window.setGravity(Gravity.BOTTOM);
        }
        dialog.show();

        root.setTranslationY(dp40(context));
        root.animate().translationY(0f).setDuration(220)
                .setInterpolator(new DecelerateInterpolator(1.4f))
                .start();

        // Live countdown while open.
        if (snapshot.expectedEtaMinutes >= 0) {
            final long startElapsed = SystemClock.elapsedRealtime();
            final ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
            animator.setDuration(120_000L);
            animator.addUpdateListener(animation -> {
                if (!dialog.isShowing()) {
                    animation.cancel();
                    return;
                }
                long minutes = snapshot.expectedEtaMinutes - (SystemClock.elapsedRealtime() - startElapsed) / 60000L;
                etaLine.setText(countdownText(snapshot, (int) Math.max(0L, minutes)));
            });
            animator.start();
            dialog.setOnDismissListener(d -> animator.cancel());
        }
    }

    private static int statusColor(BusSnapshot snapshot) {
        if (snapshot.expectedEtaMinutes < 0) {
            return UiTheme.BLUE;
        }
        if (snapshot.expectedEtaMinutes <= 2) {
            return UiTheme.CORAL;
        }
        if (snapshot.expectedEtaMinutes <= 8) {
            return UiTheme.AMBER;
        }
        return UiTheme.GREEN;
    }

    private static String statusText(BusSnapshot snapshot) {
        if (snapshot.expectedEtaMinutes < 0) {
            return "";
        }
        if (snapshot.expectedEtaMinutes <= 2) {
            return "Due now";
        }
        return snapshot.expectedEtaMinutes + " mins away";
    }

    private static String etaLineText(BusSnapshot snapshot) {
        return countdownText(snapshot, snapshot.expectedEtaMinutes);
    }

    /** "5 mins until arrival (est.)", prefixed with the real stop name when we have one. */
    private static String countdownText(BusSnapshot snapshot, int minutes) {
        if (minutes < 0) {
            return "";
        }
        if (!snapshot.arrivalStopName.isEmpty()) {
            return String.format(Locale.UK, "%s - %d min", snapshot.arrivalStopName, minutes);
        }
        if (minutes == 0) {
            return "Due now (est.)";
        }
        return String.format(Locale.UK, "%d mins until arrival (est.)", minutes);
    }

    private static String occupancyLabel(String occupancy) {
        switch (occupancy) {
            case "Full/Crowded":
                return "🔴 crowded";
            case "Standing Room Only":
                return "🟡 busy";
            case "Easy Seating":
                return "🟢 seats free";
            default:
                return "";
        }
    }

    private static LinearLayout iconAction(Context context, String icon, String label, int tint, Runnable action) {
        LinearLayout item = new LinearLayout(context);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER);
        item.setPadding(UiTheme.dp(context, 14), UiTheme.dp(context, 6), UiTheme.dp(context, 14), UiTheme.dp(context, 6));
        item.setOnClickListener(v -> action.run());
        UiTheme.pressScale(item);

        TextView iconView = new TextView(context);
        iconView.setText(icon);
        iconView.setTextSize(22);
        iconView.setTextColor(tint);
        iconView.setGravity(Gravity.CENTER);
        item.addView(iconView);

        TextView labelView = new TextView(context);
        labelView.setText(label);
        labelView.setTextSize(11);
        labelView.setTextColor(Color.argb(200, 60, 70, 96));
        labelView.setGravity(Gravity.CENTER);
        item.addView(labelView);
        return item;
    }

    private static TextView textPill(Context context, String label, int inkColor, Runnable action) {
        TextView pill = new TextView(context);
        pill.setText(label);
        pill.setTextSize(13);
        pill.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        pill.setTextColor(inkColor);
        pill.setGravity(Gravity.CENTER);
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.argb(16, 20, 26, 44));
        background.setCornerRadius(UiTheme.dp(context, 20));
        background.setStroke(UiTheme.dp(context, 1), Color.argb(40, 20, 26, 44));
        pill.setBackground(UiTheme.ripple(background));
        pill.setPadding(UiTheme.dp(context, 14), UiTheme.dp(context, 8), UiTheme.dp(context, 14), UiTheme.dp(context, 8));
        pill.setOnClickListener(v -> action.run());
        UiTheme.pressScale(pill);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.rightMargin = UiTheme.dp(context, 8);
        pill.setLayoutParams(params);
        return pill;
    }

    private static int dp40(Context context) {
        return UiTheme.dp(context, 40);
    }

    private static int dp14(Context context) {
        return UiTheme.dp(context, 14);
    }

    private static int dp12(Context context) {
        return UiTheme.dp(context, 12);
    }

    private static int dp10(Context context) {
        return UiTheme.dp(context, 10);
    }

    private static int dp8(Context context) {
        return UiTheme.dp(context, 8);
    }

    private static int dp6(Context context) {
        return UiTheme.dp(context, 6);
    }
}
