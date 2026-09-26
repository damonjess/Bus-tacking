package org.bustimes.app;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
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
 * A modern dark-glass bottom sheet with live bus details, shown whenever a bus
 * marker on the map or in AR is tapped.
 */
final class BusDetailsSheet {

    /** Listener bridge so the sheet can drive the map/activity. */
    interface Callbacks {
        void onNavigateToBus(double latitude, double longitude);

        void onFollowBus(String busId, boolean follow);

        boolean isFollowingBus(String busId);

        void onShowBusOnMap(double latitude, double longitude);
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
        root.setBackground(rounded(context, UiTheme.INK_LIGHT, dp24(context)));
        root.setPadding(pad, pad, pad, dp12(context));

        // ---- grab handle -------------------------------------------------------
        View handle = new View(context);
        GradientDrawable handleBg = new GradientDrawable();
        handleBg.setColor(Color.argb(90, 255, 255, 255));
        handleBg.setCornerRadius(UiTheme.dp(context, 3));
        handle.setBackground(handleBg);
        LinearLayout.LayoutParams handleParams = new LinearLayout.LayoutParams(dp40(context), UiTheme.dp(context, 5));
        handleParams.gravity = Gravity.CENTER_HORIZONTAL;
        handleParams.bottomMargin = dp12(context);
        root.addView(handle, handleParams);

        // ---- header: badge + route + destination -------------------------------
        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        View badge = busBadge(context, snapshot.lineName, UiTheme.occupancyColor(snapshot.occupancy));
        header.addView(badge, new LinearLayout.LayoutParams(dp64(context), dp64(context)));

        LinearLayout titles = new LinearLayout(context);
        titles.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        titleParams.leftMargin = dp14(context);
        header.addView(titles, titleParams);

        TextView routeTitle = new TextView(context);
        routeTitle.setText("Route " + snapshot.lineName);
        routeTitle.setTextColor(Color.WHITE);
        routeTitle.setTextSize(22);
        routeTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        titles.addView(routeTitle);

        TextView destination = new TextView(context);
        destination.setText("→ " + snapshot.destinationName);
        destination.setTextColor(UiTheme.TEXT_DIM);
        destination.setTextSize(14);
        destination.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        titles.addView(destination);

        View livePill = UiTheme.pillText(context, "● LIVE", UiTheme.GREEN,
                UiTheme.withAlpha(UiTheme.GREEN, 26), UiTheme.withAlpha(UiTheme.GREEN, 90));
        LinearLayout.LayoutParams liveParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        liveParams.gravity = Gravity.TOP;
        header.addView(livePill, liveParams);
        root.addView(header);

        // ---- hero ETA card ------------------------------------------------------
        LinearLayout hero = heroCard(context, snapshot);
        LinearLayout.LayoutParams heroParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        heroParams.topMargin = dp14(context);
        root.addView(hero, heroParams);

        // ---- occupancy meter ----------------------------------------------------
        LinearLayout occupancyBlock = occupancyMeter(context, snapshot);
        LinearLayout.LayoutParams occupancyParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        occupancyParams.topMargin = dp14(context);
        root.addView(occupancyBlock, occupancyParams);

        // ---- facts grid ----------------------------------------------------------
        LinearLayout grid = factsGrid(context, snapshot);
        LinearLayout.LayoutParams gridParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        gridParams.topMargin = dp14(context);
        root.addView(grid, gridParams);

        // ---- operator / source line ---------------------------------------------
        if (!snapshot.operatorName.trim().isEmpty()) {
            TextView operator = new TextView(context);
            String operatorText = snapshot.operatorName;
            if (!snapshot.lastSeen.trim().isEmpty()) {
                operatorText += "  ·  updated " + snapshot.lastSeen;
            }
            operator.setText(operatorText);
            operator.setTextColor(Color.argb(160, 226, 234, 255));
            operator.setTextSize(12);
            LinearLayout.LayoutParams operatorParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            operatorParams.topMargin = dp12(context);
            root.addView(operator, operatorParams);
        }

        // ---- action row ----------------------------------------------------------
        root.addView(actionRow(context, snapshot, callbacks, dialog),
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout footerHint = new LinearLayout(context);
        TextView hint = new TextView(context);
        hint.setText("Live data from BODS · refreshes every 15s while the map is open");
        hint.setTextColor(Color.argb(120, 226, 234, 255));
        hint.setTextSize(11);
        hint.setGravity(Gravity.CENTER);
        footerHint.addView(hint);
        LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hintParams.topMargin = dp10(context);
        root.addView(footerHint, hintParams);

        dialog.setContentView(root);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            window.setGravity(Gravity.BOTTOM);
            window.setWindowAnimations(android.R.style.Animation_InputMethod);
        }
        dialog.show();

        // Slide-up entrance.
        root.setTranslationY(dp40(context));
        root.animate().translationY(0f).setDuration(220)
                .setInterpolator(new DecelerateInterpolator(1.4f))
                .start();

        // Live ETA countdown while the sheet is open.
        if (snapshot.expectedEtaMinutes >= 0) {
            final TextView etaValueView = hero.findViewWithTag("eta_value");
            final TextView etaUnitView = hero.findViewWithTag("eta_unit");
            final long startElapsed = android.os.SystemClock.elapsedRealtime();
            final long startWallClock = System.currentTimeMillis();
            final ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
            animator.setDuration(120_000L);
            animator.addUpdateListener(animation -> {
                if (!dialog.isShowing()) {
                    animation.cancel();
                    return;
                }
                long elapsedSeconds = (android.os.SystemClock.elapsedRealtime() - startElapsed) / 1000L;
                long minutes = snapshot.expectedEtaMinutes - elapsedSeconds / 60L;
                if (etaValueView != null) {
                    etaValueView.setText(String.format(Locale.UK, "%d", Math.max(0, minutes)));
                }
                if (etaUnitView != null) {
                    etaUnitView.setText(minutes <= 1 ? "min" : "min");
                }
            });
            animator.start();
            dialog.setOnDismissListener(d -> animator.cancel());
        }
    }

    private static LinearLayout heroCard(Context context, BusSnapshot snapshot) {
        LinearLayout hero = new LinearLayout(context);
        hero.setOrientation(LinearLayout.HORIZONTAL);
        hero.setGravity(Gravity.CENTER_VERTICAL);
        hero.setBackground(rounded(context, UiTheme.withAlpha(UiTheme.INK, 110), dp20(context)));
        int pad = dp14(context);
        hero.setPadding(pad, pad, pad, pad);

        LinearLayout etaBlock = new LinearLayout(context);
        etaBlock.setOrientation(LinearLayout.VERTICAL);

        TextView etaValue = new TextView(context);
        etaValue.setTag("eta_value");
        etaValue.setText(snapshot.expectedEtaMinutes >= 0
                ? String.format(Locale.UK, "%d", snapshot.expectedEtaMinutes)
                : "—");
        etaValue.setTextColor(Color.WHITE);
        etaValue.setTextSize(46);
        etaValue.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        etaBlock.addView(etaValue);

        TextView etaUnit = new TextView(context);
        etaUnit.setTag("eta_unit");
        etaUnit.setText(snapshot.expectedEtaMinutes >= 0 ? "min to " + snapshot.arrivalStopName : "ETA not published");
        etaUnit.setTextColor(Color.argb(200, 226, 234, 255));
        etaUnit.setTextSize(12);
        etaBlock.addView(etaUnit);

        hero.addView(etaBlock, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // Status ring: now / soon / later.
        LinearLayout statusBlock = new LinearLayout(context);
        statusBlock.setOrientation(LinearLayout.VERTICAL);
        statusBlock.setGravity(Gravity.CENTER);
        int statusColor;
        String statusText;
        if (snapshot.expectedEtaMinutes < 0) {
            statusColor = UiTheme.BLUE;
            statusText = "NO DATA";
        } else if (snapshot.expectedEtaMinutes <= 2) {
            statusColor = UiTheme.CORAL;
            statusText = "DUE NOW";
        } else if (snapshot.expectedEtaMinutes <= 8) {
            statusColor = UiTheme.AMBER;
            statusText = "DUE SOON";
        } else {
            statusColor = UiTheme.GREEN;
            statusText = "ON TIME";
        }
        View ring = new View(context);
        GradientDrawable ringBg = new GradientDrawable();
        ringBg.setShape(GradientDrawable.OVAL);
        ringBg.setColor(UiTheme.withAlpha(statusColor, 34));
        ringBg.setStroke(UiTheme.dp(context, 3), statusColor);
        ring.setBackground(ringBg);
        statusBlock.addView(ring, new LinearLayout.LayoutParams(dp56(context), dp56(context)));

        TextView statusLabel = new TextView(context);
        statusLabel.setText(statusText);
        statusLabel.setTextColor(statusColor);
        statusLabel.setTextSize(11);
        statusLabel.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        statusLabel.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        statusParams.topMargin = UiTheme.dp(context, 6);
        statusBlock.addView(statusLabel, statusParams);

        hero.addView(statusBlock, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return hero;
    }

    private static LinearLayout occupancyMeter(Context context, BusSnapshot snapshot) {
        LinearLayout block = new LinearLayout(context);
        block.setOrientation(LinearLayout.VERTICAL);

        LinearLayout labelRow = new LinearLayout(context);
        labelRow.setOrientation(LinearLayout.HORIZONTAL);

        TextView label = new TextView(context);
        label.setText("Occupancy");
        label.setTextColor(UiTheme.TEXT_DIM);
        label.setTextSize(12);
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        labelRow.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView value = new TextView(context);
        value.setText(snapshot.occupancy);
        value.setTextColor(UiTheme.occupancyColor(snapshot.occupancy));
        value.setTextSize(12);
        value.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        labelRow.addView(value, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        block.addView(labelRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Track.
        LinearLayout meter = new LinearLayout(context);
        meter.setOrientation(LinearLayout.HORIZONTAL);
        meter.setBackground(rounded(context, UiTheme.withAlpha(Color.BLACK, 90), UiTheme.dp(context, 8)));
        meter.setPadding(UiTheme.dp(context, 2), UiTheme.dp(context, 2), UiTheme.dp(context, 2), UiTheme.dp(context, 2));

        View filled = new View(context);
        GradientDrawable fillBg = new GradientDrawable();
        fillBg.setCornerRadius(UiTheme.dp(context, 6));
        fillBg.setColor(UiTheme.occupancyColor(snapshot.occupancy));
        filled.setBackground(fillBg);
        LinearLayout.LayoutParams fillParams = new LinearLayout.LayoutParams(0, UiTheme.dp(context, 10), occupancyFraction(snapshot));
        meter.addView(filled, fillParams);

        View rest = new View(context);
        LinearLayout.LayoutParams restParams = new LinearLayout.LayoutParams(0, UiTheme.dp(context, 10), 1f - occupancyFraction(snapshot));
        meter.addView(rest, restParams);

        LinearLayout.LayoutParams meterParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        LinearLayout blockSpacing = new LinearLayout(context);
        LinearLayout spacerWrap = new LinearLayout(context);
        spacerWrap.setOrientation(LinearLayout.VERTICAL);
        spacerWrap.setPadding(0, UiTheme.dp(context, 8), 0, 0);
        spacerWrap.addView(meter, meterParams);
        block.addView(spacerWrap, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return block;
    }

    private static float occupancyFraction(BusSnapshot snapshot) {
        switch (snapshot.occupancy) {
            case "Full/Crowded":
                return 0.95f;
            case "Standing Room Only":
                return 0.55f;
            case "Easy Seating":
                return 0.2f;
            default:
                return 0.5f;
        }
    }

    private static LinearLayout factsGrid(Context context, BusSnapshot snapshot) {
        LinearLayout grid = new LinearLayout(context);
        grid.setOrientation(LinearLayout.VERTICAL);

        LinearLayout rowOne = new LinearLayout(context);
        rowOne.addView(factCell(context, "Speed", snapshot.speedText()),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        rowOne.addView(spacer(context));
        rowOne.addView(factCell(context, "Heading", snapshot.compassBearingText()),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        grid.addView(rowOne, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout rowTwo = new LinearLayout(context);
        rowTwo.addView(factCell(context, "Distance", snapshot.distanceText),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        rowTwo.addView(spacer(context));
        rowTwo.addView(factCell(context, "Vehicle", snapshot.vehicleId),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        grid.addView(rowTwo, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return grid;
    }

    private static View spacer(Context context) {
        View spacer = new View(context);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(UiTheme.dp(context, 8), 1);
        spacer.setLayoutParams(params);
        return spacer;
    }

    private static LinearLayout factCell(Context context, String label, String value) {
        LinearLayout cell = new LinearLayout(context);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setBackground(rounded(context, UiTheme.withAlpha(UiTheme.INK, 110), dp16(context)));
        int pad = dp12(context);
        cell.setPadding(pad, pad, pad, pad);

        TextView valueView = new TextView(context);
        valueView.setText(value.isEmpty() ? "—" : value);
        valueView.setTextColor(Color.WHITE);
        valueView.setTextSize(15);
        valueView.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        valueView.setSingleLine(true);
        valueView.setGravity(Gravity.CENTER);
        cell.addView(valueView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView labelView = new TextView(context);
        labelView.setText(label.toUpperCase(Locale.UK));
        labelView.setTextColor(Color.argb(130, 226, 234, 255));
        labelView.setTextSize(10);
        labelView.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        labelView.setGravity(Gravity.CENTER);
        cell.addView(labelView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return cell;
    }

    private static View actionRow(Context context, BusSnapshot snapshot, Callbacks callbacks, Dialog dialog) {
        HorizontalScrollView scroller = new HorizontalScrollView(context);
        scroller.setHorizontalScrollBarEnabled(false);

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);

        if (callbacks != null) {
            boolean following = callbacks.isFollowingBus(snapshot.busId);
            TextView follow = UiTheme.pillText(context, following ? "✓ Following" : "◎ Follow this bus",
                    following ? UiTheme.CYAN : Color.WHITE,
                    UiTheme.withAlpha(following ? UiTheme.CYAN : UiTheme.BLUE, 40),
                    UiTheme.withAlpha(following ? UiTheme.CYAN : UiTheme.BLUE, 140));
            follow.setOnClickListener(v -> {
                callbacks.onFollowBus(snapshot.busId, !following);
                dialog.dismiss();
            });
            row.addView(follow);

            TextView navigate = UiTheme.pillText(context, "➤ Walk me there (AR)", Color.WHITE,
                    UiTheme.withAlpha(UiTheme.BLUE, 40), UiTheme.withAlpha(UiTheme.BLUE, 140));
            navigate.setOnClickListener(v -> {
                callbacks.onNavigateToBus(snapshot.latitude, snapshot.longitude);
                dialog.dismiss();
            });
            row.addView(navigate);

            TextView showOnMap = UiTheme.pillText(context, "⦿ Show on map", Color.WHITE,
                    UiTheme.withAlpha(UiTheme.INK, 120), UiTheme.withAlpha(Color.WHITE, 60));
            showOnMap.setOnClickListener(v -> {
                callbacks.onShowBusOnMap(snapshot.latitude, snapshot.longitude);
                dialog.dismiss();
            });
            row.addView(showOnMap);
        }

        TextView share = UiTheme.pillText(context, "⇪ Share", Color.WHITE,
                UiTheme.withAlpha(UiTheme.INK, 120), UiTheme.withAlpha(Color.WHITE, 60));
        share.setOnClickListener(v -> {
            String text = String.format(Locale.UK,
                    "Bus %s → %s%s. %s. Occupancy: %s.%s",
                    snapshot.lineName,
                    snapshot.destinationName,
                    snapshot.expectedEtaMinutes >= 0 ? " due in " + snapshot.expectedEtaMinutes + " min" : "",
                    "Speed " + snapshot.speedText(),
                    snapshot.occupancy,
                    snapshot.distanceText.isEmpty() ? "" : " Currently " + snapshot.distanceText + " from me.");
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("text/plain");
            send.putExtra(Intent.EXTRA_TEXT, text);
            context.startActivity(Intent.createChooser(send, "Share bus"));
            dialog.dismiss();
        });
        row.addView(share);

        int rowPad = UiTheme.dp(context, 6);
        row.setPadding(0, rowPad, 0, rowPad);
        scroller.addView(row);
        return scroller;
    }

    /** Circular route badge like a modern transit chip. */
    private static View busBadge(Context context, String lineName, int color) {
        FrameLayout badge = new FrameLayout(context);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[] { UiTheme.withAlpha(color, 255), UiTheme.withAlpha(color, 170) });
        bg.setShape(GradientDrawable.OVAL);
        badge.setBackground(bg);

        TextView label = new TextView(context);
        label.setText(lineName);
        label.setTextColor(Color.WHITE);
        label.setTextSize(24);
        label.setTypeface(Typeface.create("sans-serif-black", Typeface.NORMAL));
        label.setGravity(Gravity.CENTER);
        badge.addView(label, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return badge;
    }

    private static GradientDrawable rounded(Context context, int color, float radiusPx) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radiusPx);
        return drawable;
    }

    private static int dp64(Context context) {
        return UiTheme.dp(context, 64);
    }

    private static int dp56(Context context) {
        return UiTheme.dp(context, 56);
    }

    private static int dp40(Context context) {
        return UiTheme.dp(context, 40);
    }

    private static int dp24(Context context) {
        return UiTheme.dp(context, 24);
    }

    private static int dp20(Context context) {
        return UiTheme.dp(context, 20);
    }

    private static int dp16(Context context) {
        return UiTheme.dp(context, 16);
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
}
