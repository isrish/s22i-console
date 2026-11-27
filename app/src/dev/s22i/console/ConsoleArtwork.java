package dev.s22i.console;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

final class ConsoleArtwork extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();

    ConsoleArtwork(Context context) {
        super(context);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(context.getResources().getDisplayMetrics().density);
        paint.setColor(Color.argb(23, 204, 255, 63));
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float unit = getResources().getDisplayMetrics().density;
        float centerX = getWidth() + 10 * unit;
        float centerY = -150 * unit;
        for (int lane = 0; lane < 5; lane++) {
            float radius = (280 + lane * 20) * unit;
            oval.set(centerX - radius, centerY - radius, centerX + radius, centerY + radius);
            canvas.drawOval(oval, paint);
        }
    }
}
