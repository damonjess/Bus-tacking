package org.bustimes.app;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.view.View;
import android.widget.TextView;

/**
 * Shared design system for Bus Times Live: a modern glass-and-neon palette,
 * gradient circular buttons, pill chips and press animations.
 */
final class UiTheme {
    static final int INK = Color.rgb(9, 15, 34);            // deep space navy
    static final int INK_LIGHT = Color.rgb(16, 26, 56);     // panel navy
    static final int BLUE = Color.rgb(46, 124, 246);        // electric blue
    static final int BLUE_DEEP = Color.rgb(23, 74, 186);    // gradient bottom
    static final int CYAN = Color.rgb(0, 229, 255);         // neon cyan accent
    static final int TEAL = Color.rgb(40, 224, 200);
    static final int AMBER = Color.rgb(255, 179, 0);
    static final int CORAL = Color.rgb(255, 90, 95);
    static final int GREEN = Color.rgb(42, 211, 126);
    static final int VIOLET = Color.rgb(154, 106, 255);
    static final int WHITE = Color.WHITE;
    static final int TEXT_DIM = Color.argb(210, 226, 234, 255);

    private UiTheme() {
    }

    static int dp(Context context, float value) {
        return (int) (value * context.getResources().getDisplayMetrics().density + 0.5f);
    }

    /** Circular vertical gradient used for floating action buttons. */
    static GradientDrawable circleGradient(Context context, int topColor, int bottomColor) {
        GradientDrawable drawable = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[] { topColor, bottomColor });
        drawable.setShape(GradientDrawable.OVAL);
        return drawable;
    }

    /** Rounded "pill" background, optionally with a stroke. */
    static GradientDrawable pill(Context context, int fillColor, int strokeColor, float strokeWidthDp, float radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setColor(fillColor);
        drawable.setCornerRadius(dp(context, radiusDp));
        if (strokeColor != 0) {
            drawable.setStroke(Math.max(1, dp(context, strokeWidthDp)), strokeColor);
        }
        return drawable;
    }

    static RippleDrawable ripple(GradientDrawable content) {
        return new RippleDrawable(ColorStateList.valueOf(Color.argb(70, 255, 255, 255)), content, content);
    }

    /** Adds a satisfying press-down scale animation while keeping normal click behaviour. */
    static void pressScale(View view) {
        view.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    v.animate().scaleX(0.92f).scaleY(0.92f).setDuration(90).start();
                    break;
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    v.animate().scaleX(1f).scaleY(1f).setDuration(120).start();
                    break;
                default:
                    break;
            }
            return false;
        });
    }

    static TextView pillText(Context context, String text, int textColor, int fillColor, int strokeColor) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextColor(textColor);
        view.setTextSize(12);
        view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        view.setBackground(ripple(pill(context, fillColor, strokeColor, 1f, 20f)));
        view.setPadding(dp(context, 14), dp(context, 7), dp(context, 14), dp(context, 7));
        view.setClickable(true);
        view.setFocusable(true);
        return view;
    }

    /** Draws the small solid triangle chevron used by AR edge indicators. */
    static void drawChevron(Canvas canvas, float x, float y, float sizeDegreesDp, boolean pointRight, Paint paint) {
        Path path = new Path();
        float size = sizeDegreesDp;
        if (pointRight) {
            path.moveTo(size, 0);
            path.lineTo(-size * 0.7f, -size * 0.8f);
            path.lineTo(-size * 0.7f, size * 0.8f);
        } else {
            path.moveTo(-size, 0);
            path.lineTo(size * 0.7f, -size * 0.8f);
            path.lineTo(size * 0.7f, size * 0.8f);
        }
        path.close();
        canvas.save();
        canvas.translate(x, y);
        canvas.drawPath(path, paint);
        canvas.restore();
    }

    /** Soft glow paint factory for canvas overlays. */
    static Paint glowPaint(Context context, int color, float strokeWidthDp, float blurDp) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(color);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStrokeWidth(dp(context, strokeWidthDp));
        paint.setMaskFilter(new BlurMaskFilter(dp(context, blurDp), BlurMaskFilter.Blur.NORMAL));
        return paint;
    }

    static int occupancyColor(String occupancy) {
        if (occupancy == null) {
            return BLUE;
        }
        switch (occupancy) {
            case "Full/Crowded":
                return CORAL;
            case "Standing Room Only":
                return AMBER;
            case "Easy Seating":
                return GREEN;
            default:
                return BLUE;
        }
    }

    static int withAlpha(int color, int alpha) {
        return (alpha << 24) | (color & 0x00FFFFFF);
    }
}
