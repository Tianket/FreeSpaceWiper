package app.freespacewiper;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

/**
 * Circular progress ring. Determinate progress glides to its new value; the indeterminate state
 * is a rotating arc. Drawn with plain Canvas calls, so it looks the same on every Android version.
 */
public final class RingProgressView extends View {

    private static final float SWEEP_DEGREES = 80f;
    private static final float SPIN_DEGREES_PER_SECOND = 220f;

    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();
    private final int activeColor;
    private final int mutedColor;

    private float progress;
    private float shown;
    private boolean indeterminate;
    private float spin;
    private long lastFrame;

    public RingProgressView(Context context) {
        this(context, null);
    }

    public RingProgressView(Context context, AttributeSet attrs) {
        super(context, attrs);
        float stroke = 10f * getResources().getDisplayMetrics().density;
        activeColor = context.getColor(R.color.accent);
        mutedColor = context.getColor(R.color.disabled);
        track.setStyle(Paint.Style.STROKE);
        track.setStrokeWidth(stroke);
        track.setColor(context.getColor(R.color.track));
        arc.setStyle(Paint.Style.STROKE);
        arc.setStrokeWidth(stroke);
        arc.setStrokeCap(Paint.Cap.ROUND);
        arc.setColor(activeColor);
    }

    /** @param value progress from 0 to 1 */
    public void setProgress(float value) {
        float v = Math.max(0f, Math.min(1f, value));
        if (v != progress || indeterminate) {
            progress = v;
            indeterminate = false;
            invalidate();
        }
    }

    public void setIndeterminate() {
        if (!indeterminate) {
            indeterminate = true;
            invalidate();
        }
    }

    /** Greyed out while the job is paused. */
    public void setMuted(boolean muted) {
        int color = muted ? mutedColor : activeColor;
        if (arc.getColor() != color) {
            arc.setColor(color);
            invalidate();
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        float half = arc.getStrokeWidth() / 2f;
        float size = Math.min(w - getPaddingLeft() - getPaddingRight(), h - getPaddingTop() - getPaddingBottom());
        float left = getPaddingLeft() + (w - getPaddingLeft() - getPaddingRight() - size) / 2f;
        float top = getPaddingTop() + (h - getPaddingTop() - getPaddingBottom() - size) / 2f;
        oval.set(left + half, top + half, left + size - half, top + size - half);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        long now = SystemClock.uptimeMillis();
        float dt = lastFrame == 0 ? 0f : Math.min(0.1f, (now - lastFrame) / 1000f);
        lastFrame = now;

        canvas.drawArc(oval, 0f, 360f, false, track);
        if (indeterminate) {
            spin = (spin + dt * SPIN_DEGREES_PER_SECOND) % 360f;
            canvas.drawArc(oval, spin - 90f, SWEEP_DEGREES, false, arc);
            postInvalidateOnAnimation();
            return;
        }
        float diff = progress - shown;
        if (Math.abs(diff) > 0.0005f) {
            shown += diff * Math.min(1f, dt * 6f + 0.02f);
            postInvalidateOnAnimation();
        } else {
            shown = progress;
        }
        if (shown > 0f) {
            canvas.drawArc(oval, -90f, 360f * shown, false, arc);
        }
    }
}
