package org.bustimes.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/**
 * Crisp monochrome icons drawn straight onto the canvas (24x24 design grid) so the
 * navigation bar and map controls never fall back to multi-colour emoji glyphs.
 */
final class IconView extends View {

    static final int MAP = 0;
    static final int SEARCH = 1;
    static final int HEART = 2;
    static final int PERSON = 3;
    static final int AR = 4;
    static final int PLUS = 5;
    static final int MINUS = 6;
    static final int TARGET = 7;
    static final int LAYERS = 8;
    static final int SUN = 9;
    static final int MOON = 10;
    static final int REFRESH = 11;
    static final int CLOSE = 12;
    static final int BUS = 13;
    static final int PIN = 14;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint accentPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    private int kind;
    private int color = Color.WHITE;
    private int accent = Color.WHITE;

    IconView(Context context) {
        this(context, MAP);
    }

    IconView(Context context, int kind) {
        super(context);
        this.kind = kind;
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        accentPaint.setStrokeCap(Paint.Cap.ROUND);
        accentPaint.setStrokeJoin(Paint.Join.ROUND);
    }

    void setKind(int kind) {
        if (this.kind != kind) {
            this.kind = kind;
            invalidate();
        }
    }

    void setIconColor(int color) {
        this.color = color;
        invalidate();
    }

    void setAccentColor(int color) {
        this.accent = color;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float size = Math.min(getWidth(), getHeight());
        if (size <= 0f) {
            return;
        }
        float scale = size / 24f;
        canvas.save();
        canvas.translate((getWidth() - size) / 2f, (getHeight() - size) / 2f);
        canvas.scale(scale, scale);
        paint.setColor(color);
        accentPaint.setColor(accent);
        switch (kind) {
            case MAP: drawMap(canvas); break;
            case SEARCH: drawSearch(canvas); break;
            case HEART: drawHeart(canvas); break;
            case PERSON: drawPerson(canvas); break;
            case AR: drawAr(canvas); break;
            case PLUS: drawPlus(canvas); break;
            case MINUS: drawMinus(canvas); break;
            case TARGET: drawTarget(canvas); break;
            case LAYERS: drawLayers(canvas); break;
            case SUN: drawSun(canvas); break;
            case MOON: drawMoon(canvas); break;
            case REFRESH: drawRefresh(canvas); break;
            case CLOSE: drawClose(canvas); break;
            case BUS: drawBus(canvas); break;
            case PIN: drawPin(canvas); break;
            default: break;
        }
        canvas.restore();
    }

    private void stroke(float width) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(width);
    }

    private void fill() {
        paint.setStyle(Paint.Style.FILL);
    }

    private void drawPlus(Canvas canvas) {
        stroke(2.4f);
        canvas.drawLine(12f, 4.6f, 12f, 19.4f, paint);
        canvas.drawLine(4.6f, 12f, 19.4f, 12f, paint);
    }

    private void drawMinus(Canvas canvas) {
        stroke(2.4f);
        canvas.drawLine(4.6f, 12f, 19.4f, 12f, paint);
    }

    private void drawTarget(Canvas canvas) {
        stroke(1.9f);
        canvas.drawCircle(12f, 12f, 6.6f, paint);
        fill();
        canvas.drawCircle(12f, 12f, 2.1f, paint);
        stroke(1.9f);
        canvas.drawLine(12f, 1.8f, 12f, 4.6f, paint);
        canvas.drawLine(12f, 19.4f, 12f, 22.2f, paint);
        canvas.drawLine(1.8f, 12f, 4.6f, 12f, paint);
        canvas.drawLine(19.4f, 12f, 22.2f, 12f, paint);
    }

    private void drawLayers(Canvas canvas) {
        stroke(1.8f);
        path.reset();
        path.moveTo(12f, 3.2f);
        path.lineTo(20.4f, 7.9f);
        path.lineTo(12f, 12.6f);
        path.lineTo(3.6f, 7.9f);
        path.close();
        canvas.drawPath(path, paint);
        path.reset();
        path.moveTo(3.6f, 12.1f);
        path.lineTo(12f, 16.8f);
        path.lineTo(20.4f, 12.1f);
        canvas.drawPath(path, paint);
        path.reset();
        path.moveTo(3.6f, 16.1f);
        path.lineTo(12f, 20.8f);
        path.lineTo(20.4f, 16.1f);
        canvas.drawPath(path, paint);
    }

    private void drawSun(Canvas canvas) {
        fill();
        canvas.drawCircle(12f, 12f, 4.2f, paint);
        stroke(1.9f);
        for (int i = 0; i < 8; i++) {
            double angle = Math.PI * i / 4.0;
            float dx = (float) Math.cos(angle);
            float dy = (float) Math.sin(angle);
            canvas.drawLine(12f + dx * 6.8f, 12f + dy * 6.8f, 12f + dx * 10f, 12f + dy * 10f, paint);
        }
    }

    private void drawMoon(Canvas canvas) {
        path.reset();
        path.addCircle(12f, 12f, 8.3f, Path.Direction.CW);
        Path cut = new Path();
        cut.addCircle(16.6f, 8.6f, 7.1f, Path.Direction.CW);
        path.op(cut, Path.Op.DIFFERENCE);
        fill();
        canvas.drawPath(path, paint);
    }

    private void drawRefresh(Canvas canvas) {
        stroke(2f);
        canvas.drawArc(new RectF(4.8f, 4.8f, 19.2f, 19.2f), -55f, 280f, false, paint);
        fill();
        path.reset();
        path.moveTo(6.2f, 2.9f);
        path.lineTo(11.4f, 4.6f);
        path.lineTo(7.1f, 8.8f);
        path.close();
        canvas.drawPath(path, paint);
    }

    private void drawClose(Canvas canvas) {
        stroke(2.2f);
        canvas.drawLine(7f, 7f, 17f, 17f, paint);
        canvas.drawLine(17f, 7f, 7f, 17f, paint);
    }

    private void drawSearch(Canvas canvas) {
        stroke(2f);
        canvas.drawCircle(10.8f, 10.8f, 6.2f, paint);
        canvas.drawLine(15.5f, 15.5f, 20.4f, 20.4f, paint);
    }

    private void drawHeart(Canvas canvas) {
        fill();
        canvas.drawCircle(9.1f, 9.5f, 4.35f, paint);
        canvas.drawCircle(14.9f, 9.5f, 4.35f, paint);
        path.reset();
        path.moveTo(4.9f, 10.4f);
        path.lineTo(19.1f, 10.4f);
        path.lineTo(12f, 20.6f);
        path.close();
        canvas.drawPath(path, paint);
    }

    private void drawPerson(Canvas canvas) {
        fill();
        canvas.drawCircle(12f, 7.9f, 3.95f, paint);
        canvas.drawArc(new RectF(4.5f, 13.1f, 19.5f, 25.2f), 180f, 180f, true, paint);
    }

    private void drawAr(Canvas canvas) {
        stroke(1.8f);
        canvas.drawRoundRect(new RectF(2.2f, 6.6f, 21.8f, 17.4f), 5.2f, 5.2f, paint);
        fill();
        canvas.drawCircle(8.4f, 12f, 2.5f, paint);
        canvas.drawCircle(15.6f, 12f, 2.5f, paint);
        stroke(1.8f);
        canvas.drawLine(4.8f, 4.2f, 8.4f, 6.6f, paint);
        canvas.drawLine(19.2f, 4.2f, 15.6f, 6.6f, paint);
    }

    private void drawMap(Canvas canvas) {
        fill();
        canvas.drawCircle(12f, 12f, 9.4f, paint);
        path.reset();
        path.moveTo(12f, 3f);
        path.lineTo(5f, 20.6f);
        path.lineTo(12f, 17.6f);
        path.lineTo(19f, 20.6f);
        path.close();
        accentPaint.setStyle(Paint.Style.FILL);
        canvas.save();
        canvas.rotate(32f, 12f, 12f);
        canvas.drawPath(path, accentPaint);
        canvas.restore();
    }

    private void drawPin(Canvas canvas) {
        stroke(2f);
        canvas.drawCircle(12f, 9.8f, 5.6f, paint);
        path.reset();
        path.moveTo(8.2f, 13.9f);
        path.lineTo(12f, 21.6f);
        path.lineTo(15.8f, 13.9f);
        canvas.drawPath(path, paint);
        fill();
        canvas.drawCircle(12f, 9.8f, 1.9f, paint);
    }

    /** 3D perspective double-decker bus pictogram, used for thumbnails and bus markers. */
    private void drawBus(Canvas canvas) {
        // Shadow under bus
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.argb(55, 0, 0, 0));
        canvas.drawOval(new RectF(2.5f, 20.2f, 21.5f, 22.4f), paint);

        // Upper deck body (White / Light Slate)
        paint.setColor(Color.rgb(248, 250, 252));
        path.reset();
        path.moveTo(4.2f, 7.2f);
        path.lineTo(18.5f, 5.8f);
        path.lineTo(20.5f, 10.8f);
        path.lineTo(4.2f, 11.8f);
        path.close();
        canvas.drawPath(path, paint);

        // Roof cap (Blue accent)
        paint.setColor(Color.rgb(2, 136, 209));
        path.reset();
        path.moveTo(4.2f, 7.2f);
        path.lineTo(18.5f, 5.8f);
        path.lineTo(19.0f, 7.0f);
        path.lineTo(4.2f, 8.2f);
        path.close();
        canvas.drawPath(path, paint);

        // Upper windows (Dark blue/slate)
        paint.setColor(Color.rgb(30, 41, 59));
        canvas.drawRoundRect(new RectF(5.5f, 8.4f, 8.5f, 11.0f), 0.8f, 0.8f, paint);
        canvas.drawRoundRect(new RectF(9.5f, 8.0f, 12.5f, 10.6f), 0.8f, 0.8f, paint);
        canvas.drawRoundRect(new RectF(13.5f, 7.6f, 16.5f, 10.2f), 0.8f, 0.8f, paint);
        // Upper windscreen
        path.reset();
        path.moveTo(17.2f, 7.2f);
        path.lineTo(19.8f, 6.9f);
        path.lineTo(20.2f, 9.8f);
        path.lineTo(17.2f, 10.0f);
        path.close();
        canvas.drawPath(path, paint);

        // Inter-deck stripe (Blue)
        paint.setColor(Color.rgb(2, 136, 209));
        path.reset();
        path.moveTo(4.0f, 11.8f);
        path.lineTo(20.5f, 10.8f);
        path.lineTo(20.8f, 12.2f);
        path.lineTo(4.0f, 13.2f);
        path.close();
        canvas.drawPath(path, paint);

        // Lower deck body (Vibrant Red)
        paint.setColor(Color.rgb(211, 47, 47));
        path.reset();
        path.moveTo(4.0f, 13.2f);
        path.lineTo(20.8f, 12.2f);
        path.lineTo(20.2f, 18.5f);
        path.lineTo(3.5f, 19.5f);
        path.close();
        canvas.drawPath(path, paint);

        // Lower windows
        paint.setColor(Color.rgb(30, 41, 59));
        canvas.drawRoundRect(new RectF(5.2f, 14.0f, 8.2f, 16.6f), 0.8f, 0.8f, paint);
        canvas.drawRoundRect(new RectF(9.2f, 13.6f, 12.2f, 16.2f), 0.8f, 0.8f, paint);
        canvas.drawRoundRect(new RectF(13.2f, 13.2f, 16.2f, 15.8f), 0.8f, 0.8f, paint);
        // Lower windscreen
        path.reset();
        path.moveTo(16.8f, 12.8f);
        path.lineTo(20.0f, 12.5f);
        path.lineTo(19.6f, 16.0f);
        path.lineTo(16.8f, 15.3f);
        path.close();
        canvas.drawPath(path, paint);

        // Headlight
        paint.setColor(Color.rgb(255, 235, 59));
        canvas.drawCircle(19.2f, 17.2f, 0.8f, paint);

        // Wheels
        paint.setColor(Color.rgb(17, 24, 39));
        canvas.drawCircle(6.5f, 19.5f, 2.2f, paint);
        canvas.drawCircle(16.2f, 18.8f, 2.2f, paint);

        paint.setColor(Color.rgb(156, 163, 175));
        canvas.drawCircle(6.5f, 19.5f, 0.9f, paint);
        canvas.drawCircle(16.2f, 18.8f, 0.9f, paint);

        paint.setColor(color);
    }

    private static int darker(int color, float factor) {
        return Color.rgb(
                Math.round(Color.red(color) * factor),
                Math.round(Color.green(color) * factor),
                Math.round(Color.blue(color) * factor));
    }
}
