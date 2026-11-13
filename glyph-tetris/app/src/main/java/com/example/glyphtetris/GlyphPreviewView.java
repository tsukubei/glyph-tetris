package com.example.glyphtetris;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;

/**
 * Glyph Matrixを模した表示。実機に送るのと同じ明るさ配列(0..255)をそのまま描くので、
 * 画面上の絵と実機の絵は常に一致する。
 */
public final class GlyphPreviewView extends View {

    private final Paint glass = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int n = 25;
    private int[] frame;

    public GlyphPreviewView(Context context) {
        super(context);
        glass.setColor(0xFF000000);
    }

    public void setMatrixLength(int length) {
        n = length;
        frame = null;
        invalidate();
    }

    public void setFrame(int[] f) {
        frame = f;
        invalidate();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int s = Math.min(MeasureSpec.getSize(widthSpec), MeasureSpec.getSize(heightSpec));
        setMeasuredDimension(s, s);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float size = Math.min(getWidth(), getHeight());
        float cell = size / n;
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, glass);

        boolean valid = frame != null && frame.length == n * n;
        float radius = cell * 0.40f;
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                if (!GlyphFrameComposer.isLed(n, x, y)) continue;
                int v = valid ? frame[y * n + x] : 0;
                if (v <= 0) {
                    dot.setColor(0xFF1B1B1F);
                } else {
                    int g = 40 + (v * 215) / 255;
                    dot.setColor(Color.rgb(g, g, g));
                }
                canvas.drawCircle((x + 0.5f) * cell, (y + 0.5f) * cell, radius, dot);
            }
        }
    }
}
