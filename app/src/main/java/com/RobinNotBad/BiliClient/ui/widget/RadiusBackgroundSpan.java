package com.RobinNotBad.BiliClient.ui.widget;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.TextPaint;
import android.text.style.ReplacementSpan;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public class RadiusBackgroundSpan extends ReplacementSpan {
    private final int horizontalPadding;
    private final int verticalPadding;
    private final int radius;
    private final int textColor;
    private final int bgColor;
    private final int maxHeight;
    private final float textSizeScale;
    private final float textScaleX;
    private final boolean fakeBoldText;

    public RadiusBackgroundSpan(int margin, int radius, int textColor, int bgColor) {
        this(margin, margin, radius, textColor, bgColor, Integer.MAX_VALUE, 1f, 1f, false);
    }

    public RadiusBackgroundSpan(int margin, int radius, int textColor, int bgColor, int maxHeight) {
        this(margin, margin, radius, textColor, bgColor, maxHeight, 1f, 1f, false);
    }

    public RadiusBackgroundSpan(int horizontalPadding, int verticalPadding, int radius, int textColor, int bgColor,
                                int maxHeight) {
        this(horizontalPadding, verticalPadding, radius, textColor, bgColor, maxHeight, 1f, 1f, false);
    }

    public RadiusBackgroundSpan(int horizontalPadding, int verticalPadding, int radius, int textColor, int bgColor,
                                int maxHeight, float textSizeScale, float textScaleX, boolean fakeBoldText) {
        this.horizontalPadding = horizontalPadding;
        this.verticalPadding = verticalPadding;
        this.radius = radius;
        this.textColor = textColor;
        this.bgColor = bgColor;
        this.maxHeight = maxHeight;
        this.textSizeScale = textSizeScale > 0 ? textSizeScale : 1f;
        this.textScaleX = textScaleX > 0 ? textScaleX : 1f;
        this.fakeBoldText = fakeBoldText;
    }

    @Override
    public int getSize(@NonNull Paint paint, CharSequence text, int start, int end, @Nullable Paint.FontMetricsInt fm) {
        Paint newPaint = getCustomTextPaint(paint);
        return Math.round(newPaint.measureText(text, start, end)) + horizontalPadding * 2;
    }

    @Override
    public void draw(@NonNull Canvas canvas, CharSequence text, int start, int end, float x, int top, int y, int
            bottom, @NonNull Paint paint) {
        Paint newPaint = getCustomTextPaint(paint);
        Paint.FontMetrics fontMetrics = newPaint.getFontMetrics();

        float textWidth = newPaint.measureText(text, start, end);
        float textHeight = fontMetrics.descent - fontMetrics.ascent;
        float badgeHeight = Math.min(textHeight + verticalPadding * 2f, maxHeight);
        float textCenterY = y + (fontMetrics.ascent + fontMetrics.descent) / 2f;

        RectF rect = new RectF();
        rect.top = textCenterY - badgeHeight / 2f;
        rect.bottom = textCenterY + badgeHeight / 2f;
        rect.left = x;
        rect.right = x + textWidth + horizontalPadding * 2f;

        int oldColor = paint.getColor();
        paint.setColor(bgColor);
        canvas.drawRoundRect(rect, radius, radius, paint);
        paint.setColor(oldColor);

        newPaint.setColor(textColor);
        float textX = rect.left + horizontalPadding;
        canvas.drawText(text, start, end, textX, y, newPaint);
    }

    private TextPaint getCustomTextPaint(Paint srcPaint) {
        TextPaint textPaint = new TextPaint(srcPaint);
        textPaint.setTextSize(textPaint.getTextSize() * textSizeScale);
        textPaint.setTextScaleX(textPaint.getTextScaleX() * textScaleX);
        textPaint.setFakeBoldText(fakeBoldText || textPaint.isFakeBoldText());
        return textPaint;
    }
}
