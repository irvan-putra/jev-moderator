package com.irvanputra.jevmoderator;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;
import java.util.ArrayList;
import java.util.List;

/** Draws numbered boxes over the camera picture, which is letterboxed (fit-center). */
final class OverlayView extends View {
    private List<Person> people = new ArrayList<>();
    private int highlight = -1;
    private int frameW = 1, frameH = 1;
    private final Paint box = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);

    OverlayView(Context c) {
        super(c);
        box.setStyle(Paint.Style.STROKE);
        text.setTextSize(36f);
        text.setFakeBoldText(true);
    }

    void set(List<Person> p, int fw, int fh, int hl) {
        people = p; frameW = Math.max(1, fw); frameH = Math.max(1, fh); highlight = hl;
        postInvalidate();
    }

    @Override protected void onDraw(Canvas c) {
        float vw = getWidth(), vh = getHeight();
        float s = Math.min(vw / frameW, vh / frameH);
        float dw = frameW * s, dh = frameH * s, ox = (vw - dw) / 2f, oy = (vh - dh) / 2f;
        for (int i = 0; i < people.size(); i++) {
            Person p = people.get(i);
            boolean hl = i == highlight;
            int color = hl ? 0xFFFFD400 : (p.raised ? 0xFFED313E : 0xFF73A89A);
            box.setColor(color); text.setColor(color);
            box.setStrokeWidth(hl ? 10f : 3f);
            RectF r = new RectF(ox + p.x0 * dw, oy + p.y0 * dh, ox + p.x1 * dw, oy + p.y1 * dh);
            c.drawRect(r, box);
            c.drawText("#" + (i + 1) + (p.raised ? " hand" : ""), r.left + 4, Math.max(36f, r.top - 6), text);
        }
    }
}
