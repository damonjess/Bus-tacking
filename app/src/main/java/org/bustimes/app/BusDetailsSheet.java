package org.bustimes.app;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;

import java.util.Locale;

final class BusDetailsSheet {

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

    private BusDetailsSheet() {}

    static void show(Activity activity, BusSnapshot snapshot, Callbacks callbacks) {
        Context context = activity;
        BottomSheetDialog dialog = new BottomSheetDialog(context);
        View root = activity.getLayoutInflater().inflate(R.layout.sheet_bus_details, null);
        dialog.setContentView(root);

        TextView routeBadge = root.findViewById(R.id.text_route_badge);
        TextView destText = root.findViewById(R.id.text_destination);
        TextView opText = root.findViewById(R.id.text_operator);
        TextView etaText = root.findViewById(R.id.text_eta_countdown);
        TextView stopText = root.findViewById(R.id.text_next_stop);
        TextView occText = root.findViewById(R.id.text_occupancy);
        TextView speedText = root.findViewById(R.id.text_speed);
        TextView headingText = root.findViewById(R.id.text_heading);
        TextView distText = root.findViewById(R.id.text_distance);
        ImageView btnFav = root.findViewById(R.id.btn_favorite);
        MaterialButton btnFollow = root.findViewById(R.id.btn_follow);
        MaterialButton btnWalkAr = root.findViewById(R.id.btn_walk_ar);
        MaterialButton btnShare = root.findViewById(R.id.btn_share);

        // Route & destination setup
        routeBadge.setText(snapshot.lineName.isEmpty() ? "Bus" : snapshot.lineName);
        destText.setText(snapshot.destinationName.isEmpty() ? "In Service" : "to " + snapshot.destinationName);
        String sub = snapshot.operatorName;
        if (!snapshot.vehicleId.isEmpty()) {
            sub += " • #" + snapshot.vehicleId;
        }
        opText.setText(sub);

        // Occupancy styling
        if ("Information Unknown".equalsIgnoreCase(snapshot.occupancy)) {
            occText.setVisibility(View.GONE);
        } else {
            occText.setVisibility(View.VISIBLE);
            int occColor = UiTheme.occupancyColor(snapshot.occupancy);
            GradientDrawable occBadge = new GradientDrawable();
            occBadge.setCornerRadius(UiTheme.dp(context, 12));
            occBadge.setColor(UiTheme.withAlpha(occColor, 40));
            occBadge.setStroke(UiTheme.dp(context, 1), occColor);
            occText.setBackground(occBadge);
            occText.setTextColor(occColor);
            occText.setText(snapshot.occupancy);
        }

        // ETA mapping
        if (snapshot.expectedEtaMinutes >= 0) {
            etaText.setText(snapshot.expectedEtaMinutes == 0 ? "Due now (est.)" 
                    : String.format(Locale.UK, "%d mins", snapshot.expectedEtaMinutes));
            etaText.setVisibility(View.VISIBLE);
            stopText.setText(snapshot.arrivalStopName.isEmpty() ? "Next stop estimated" : snapshot.arrivalStopName);
            stopText.setVisibility(View.VISIBLE);
        } else {
            String updated = snapshot.updatedText();
            if (!updated.isEmpty()) {
                etaText.setText(updated);
                etaText.setTextColor(Color.parseColor("#8E9CB8")); // slightly dimmer for 'updated'
                etaText.setTextSize(14f);
                etaText.setVisibility(View.VISIBLE);
                stopText.setVisibility(View.GONE);
            } else {
                etaText.setVisibility(View.GONE);
                stopText.setVisibility(View.GONE);
            }
        }

        // Telemetry details
        String spd = snapshot.speedText();
        String hdg = snapshot.compassBearingText();
        String dst = snapshot.distanceText;
        if (spd.isEmpty() && hdg.isEmpty() && dst.isEmpty()) {
            ((View) speedText.getParent()).setVisibility(View.GONE);
        } else {
            ((View) speedText.getParent()).setVisibility(View.VISIBLE);
            speedText.setText(spd.isEmpty() ? "--" : spd);
            headingText.setText(hdg.isEmpty() ? "--" : hdg);
            distText.setText(dst.isEmpty() ? "--" : dst);
        }
        // Favorite action
        boolean isFav = callbacks != null && callbacks.isFavorite(snapshot.lineName);
        btnFav.setColorFilter(isFav ? Color.rgb(255, 179, 0) : Color.rgb(142, 156, 184));
        btnFav.setOnClickListener(v -> {
            if (callbacks != null) {
                callbacks.onToggleFavorite(snapshot.lineName);
                boolean favNow = callbacks.isFavorite(snapshot.lineName);
                btnFav.setColorFilter(favNow ? Color.rgb(255, 179, 0) : Color.rgb(142, 156, 184));
            }
        });

        // Following action
        boolean following = callbacks != null && callbacks.isFollowingBus(snapshot.busId);
        btnFollow.setText(following ? "Following" : "Follow");
        btnFollow.setOnClickListener(v -> {
            if (callbacks != null) {
                callbacks.onFollowBus(snapshot.busId, !following);
                dialog.dismiss();
            }
        });

        btnWalkAr.setOnClickListener(v -> {
            if (callbacks != null) {
                callbacks.onNavigateToBus(snapshot.latitude, snapshot.longitude);
                dialog.dismiss();
            }
        });

        btnShare.setOnClickListener(v -> {
            String text = String.format(Locale.UK, "Bus %s to %s. %s",
                    snapshot.lineName, snapshot.destinationName, snapshot.distanceText);
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("text/plain");
            send.putExtra(Intent.EXTRA_TEXT, text);
            context.startActivity(Intent.createChooser(send, "Share bus"));
            dialog.dismiss();
        });

        // Live countdown animator
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
                long finalMin = Math.max(0L, minutes);
                etaText.setText(finalMin == 0 ? "Due now" : finalMin + " min away");
            });
            animator.start();
            dialog.setOnDismissListener(d -> animator.cancel());
        } else {
            String updated = snapshot.updatedText();
            if (!updated.isEmpty()) {
                etaText.setText(updated);
            } else {
                etaText.setText("Live tracking");
            }
        }

        dialog.show();
    }
}
