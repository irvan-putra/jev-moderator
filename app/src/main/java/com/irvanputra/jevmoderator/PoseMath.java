package com.irvanputra.jevmoderator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Pure-Java post-processing (no Android classes) so it can be unit-tested on a normal JVM. */
final class PoseMath {
    static final float CONF = 0.30f, KP = 0.40f, NMS_IOU = 0.5f;
    static final float ACCEPT_FULL = 0.40f, ACCEPT_TILE = 0.50f, EV = 0.5f;
    static final int MIN_KP = 5;
    static final int S = 640;
    static final class Det { float cx, cy, w, h, conf; float[] kp = new float[51]; }


    /** Decode a YOLOv8-pose output [56][8400] for an image resized to nw x nh and letterboxed top-left into 640x640. */
    static List<Det> decode(float[][] o, int nw, int nh) {
        int n = o[0].length;
        List<Det> dets = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (o[4][i] < CONF) continue;
            Det d = new Det(); d.cx = o[0][i]; d.cy = o[1][i]; d.w = o[2][i]; d.h = o[3][i]; d.conf = o[4][i];
            for (int k = 0; k < 51; k++) d.kp[k] = o[5 + k][i];
            dets.add(d);
        }
        Collections.sort(dets, (a, b) -> Float.compare(b.conf, a.conf));
        List<Det> keep = new ArrayList<>();
        for (Det d : dets) { boolean ok = true; for (Det k : keep) if (iou(d, k) > NMS_IOU) { ok = false; break; } if (ok) keep.add(d); }
        float fx = S / (float) nw, fy = S / (float) nh;
        for (Det d : keep) {
            d.cx = d.cx / S * fx; d.w = d.w / S * fx; d.cy = d.cy / S * fy; d.h = d.h / S * fy;
            for (int k = 0; k < 17; k++) { d.kp[k * 3] = d.kp[k * 3] / S * fx; d.kp[k * 3 + 1] = d.kp[k * 3 + 1] / S * fy; }
        }
        return keep;
    }

    static float iou(Det a, Det b) {
        float ax0 = a.cx - a.w / 2, ay0 = a.cy - a.h / 2, ax1 = a.cx + a.w / 2, ay1 = a.cy + a.h / 2;
        float bx0 = b.cx - b.w / 2, by0 = b.cy - b.h / 2, bx1 = b.cx + b.w / 2, by1 = b.cy + b.h / 2;
        float iw = Math.max(0, Math.min(ax1, bx1) - Math.max(ax0, bx0)), ih = Math.max(0, Math.min(ay1, by1) - Math.max(ay0, by0));
        float i = iw * ih; return i / (a.w * a.h + b.w * b.h - i + 1e-9f);
    }

    static Person toPerson(Det d, float tx, float ty, float tw, float th) {
        Person p = new Person();
        p.x0 = tx + Math.max(0, d.cx - d.w / 2) * tw; p.x1 = tx + Math.min(1, d.cx + d.w / 2) * tw;
        p.y0 = ty + Math.max(0, d.cy - d.h / 2) * th; p.y1 = ty + Math.min(1, d.cy + d.h / 2) * th;
        float[] k = d.kp;
        boolean noseOk = k[2] >= KP;
        p.nx = tx + (noseOk ? k[0] : d.cx) * tw; p.ny = ty + (noseOk ? k[1] : d.cy - d.h / 2) * th;
        // Raised hand: wrist clearly above the nose (face visible), else above both shoulders (seen from behind).
        float margin = 0.06f * d.h;
        boolean r = false;
        for (int w : new int[]{9, 10}) {
            float wy = k[w * 3 + 1], wc = k[w * 3 + 2];
            if (wc < KP) continue;
            if (noseOk) { if (wy < k[1] - margin) r = true; }
            else if (k[5 * 3 + 2] >= KP && k[6 * 3 + 2] >= KP) { if (wy < Math.min(k[5 * 3 + 1], k[6 * 3 + 1]) - margin) r = true; }
        }
        p.raised = r; p.conf = d.conf;
        return p;
    }

    /** A person seen in two tiles is the same person when the boxes overlap a lot. */
    static void merge(List<Person> all, List<Person> add) {
        for (Person n : add) {
            Person dup = null;
            for (Person o : all) if (boxIou(o, n) > 0.4f || Math.hypot(o.nx - n.nx, o.ny - n.ny) < 0.02f) { dup = o; break; }
            if (dup != null) { if (n.raised) dup.raised = true; } else all.add(n);
        }
    }
    static float boxIou(Person a, Person b) {
        float iw = Math.max(0, Math.min(a.x1, b.x1) - Math.max(a.x0, b.x0)), ih = Math.max(0, Math.min(a.y1, b.y1) - Math.max(a.y0, b.y0));
        float i = iw * ih, ua = (a.x1 - a.x0) * (a.y1 - a.y0) + (b.x1 - b.x0) * (b.y1 - b.y0) - i;
        return ua <= 0 ? 0 : i / ua;
    }

    static int visKp(Det d) { int n = 0; for (int k = 0; k < 17; k++) if (d.kp[k * 3 + 2] >= EV) n++; return n; }

    /**
     * Evidence gate, applied after NMS. A furniture "person" usually has a weak score, a few or scattered keypoints, or a box
     * as big as the whole tile. Needs a clear score, enough body points, a shoulder, and a head or hip point.
     * Tiles use a stricter score because cropped, enlarged views produce more false boxes.
     */
    static boolean accept(Det d, boolean full) {
        if (d.conf < (full ? ACCEPT_FULL : ACCEPT_TILE)) return false;
        if (visKp(d) < MIN_KP) return false;
        float[] k = d.kp;
        boolean shoulder = k[5 * 3 + 2] >= EV || k[6 * 3 + 2] >= EV;
        boolean head = false; for (int i = 0; i < 5; i++) if (k[i * 3 + 2] >= EV) head = true;
        boolean hip = k[11 * 3 + 2] >= EV || k[12 * 3 + 2] >= EV;
        if (!(shoulder && (head || hip))) return false;
        if (!full && d.w > 0.9f && d.h > 0.9f) return false;   // a box as big as the whole tile is not one person
        return d.w * d.h >= 0.002f;
    }

    /**
     * Temporal check: a person counts only if a matching box was also seen in at least one of the previous frames.
     * One-frame false boxes (furniture, shadows) are dropped. Position only; no identity is kept.
     */
    static List<Person> confirm(List<Person> cur, List<List<Person>> prev) {
        List<Person> out = new ArrayList<>();
        for (Person c : cur) {
            boolean seen = false;
            for (List<Person> frame : prev) { for (Person o : frame) if (boxIou(o, c) > 0.3f) { seen = true; break; } if (seen) break; }
            if (seen) out.add(c);
        }
        return out;
    }
}
