package com.razukenk.peugeot307vanmonitor;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

public class CarDoorView extends View {
    public static final int BOOT = 0x08;
    public static final int REAR_LEFT = 0x10;
    public static final int REAR_RIGHT = 0x20;
    public static final int FRONT_LEFT = 0x40;
    public static final int FRONT_RIGHT = 0x80;

    private int doorMask = 0;

    private final Paint body = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint closed = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint open = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glass = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);

    public CarDoorView(Context context) {
        super(context);
        init();
    }

    public CarDoorView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        body.setStyle(Paint.Style.STROKE);
        body.setStrokeWidth(5f);
        body.setColor(Color.rgb(210, 215, 225));

        closed.setStyle(Paint.Style.STROKE);
        closed.setStrokeWidth(7f);
        closed.setStrokeCap(Paint.Cap.ROUND);
        closed.setColor(Color.rgb(90, 105, 115));

        open.setStyle(Paint.Style.STROKE);
        open.setStrokeWidth(9f);
        open.setStrokeCap(Paint.Cap.ROUND);
        open.setColor(Color.rgb(255, 70, 70));

        glass.setStyle(Paint.Style.STROKE);
        glass.setStrokeWidth(4f);
        glass.setColor(Color.rgb(90, 170, 220));

        text.setColor(Color.WHITE);
        text.setTextSize(24f);
        text.setTextAlign(Paint.Align.CENTER);

        setMinimumHeight(330);
        setBackgroundColor(Color.rgb(24, 26, 30));
    }

    public void setDoorMask(int mask) {
        mask &= 0xF8;
        if (doorMask != mask) {
            doorMask = mask;
            invalidate();
        }
    }

    public int getDoorMask() {
        return doorMask;
    }

    private boolean isOpen(int bit) {
        return (doorMask & bit) != 0;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        float w = getWidth();
        float h = getHeight();
        float cx = w / 2f;

        float carW = Math.min(w * 0.42f, 250f);
        float left = cx - carW / 2f;
        float right = cx + carW / 2f;
        float top = 42f;
        float bottom = h - 48f;

        RectF shell = new RectF(left, top, right, bottom);
        canvas.drawRoundRect(shell, carW * 0.22f, carW * 0.22f, body);

        float frontWindY = top + (bottom - top) * 0.22f;
        float rearWindY = top + (bottom - top) * 0.72f;
        canvas.drawLine(left + 22, frontWindY, right - 22, frontWindY, glass);
        canvas.drawLine(left + 22, rearWindY, right - 22, rearWindY, glass);

        float midY = top + (bottom - top) * 0.49f;
        canvas.drawLine(left, midY, right, midY, body);

        drawDoor(canvas, FRONT_LEFT, left, top + 45, left - 72, top + 105, "ЛП");
        drawDoor(canvas, FRONT_RIGHT, right, top + 45, right + 72, top + 105, "ПП");
        drawDoor(canvas, REAR_LEFT, left, midY + 8, left - 72, midY + 70, "ЛЗ");
        drawDoor(canvas, REAR_RIGHT, right, midY + 8, right + 72, midY + 70, "ПЗ");

        Paint bootPaint = isOpen(BOOT) ? open : closed;
        if (isOpen(BOOT)) {
            canvas.drawLine(left + 15, bottom, cx, bottom + 45, bootPaint);
            canvas.drawLine(cx, bottom + 45, right - 15, bottom, bootPaint);
        } else {
            canvas.drawLine(left + 18, bottom - 2, right - 18, bottom - 2, bootPaint);
        }

        text.setTextSize(22f);
        text.setColor(isOpen(BOOT) ? Color.rgb(255, 100, 100) : Color.LTGRAY);
        canvas.drawText(isOpen(BOOT) ? "БАГАЖНИК ОТКРЫТ" : "багажник", cx, h - 12, text);

        text.setTextSize(20f);
        text.setColor(Color.rgb(150, 170, 185));
        canvas.drawText("ПЕРЕД", cx, 28f, text);
    }

    private void drawDoor(Canvas canvas, int bit, float hingeX, float hingeY,
                          float openX, float openY, String label) {
        boolean opened = isOpen(bit);
        Paint p = opened ? open : closed;

        if (opened) {
            canvas.drawLine(hingeX, hingeY, openX, openY, p);
        } else {
            float sign = hingeX < getWidth() / 2f ? -1f : 1f;
            canvas.drawLine(hingeX, hingeY, hingeX, openY, p);
            canvas.drawLine(hingeX, openY, hingeX + sign * 8f, openY, p);
        }

        text.setTextSize(19f);
        text.setColor(opened ? Color.rgb(255, 100, 100) : Color.rgb(150, 155, 165));
        float tx = opened ? openX : hingeX + (hingeX < getWidth()/2f ? -28f : 28f);
        canvas.drawText(label, tx, openY + 23f, text);
    }
}
