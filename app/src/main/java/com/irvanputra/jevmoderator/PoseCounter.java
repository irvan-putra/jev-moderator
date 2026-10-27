package com.irvanputra.jevmoderator;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import android.content.Context;
import android.graphics.Bitmap;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * On-device people + pose detection with YOLOv8n-pose (Ultralytics, AGPL-3.0) through ONNX Runtime.
 * Frames stay in memory; nothing is saved or sent. Output is boxes and keypoints only: no identity.
 */
final class PoseCounter {
    private static final int S = PoseMath.S;
    private final OrtEnvironment env = OrtEnvironment.getEnvironment();
    private final OrtSession session;

    PoseCounter(Context ctx, String asset) throws Exception {
        byte[] model;
        try (InputStream in = ctx.getAssets().open(asset)) { model = in.readAllBytes(); }
        OrtSession.SessionOptions o = new OrtSession.SessionOptions();
        o.setIntraOpNumThreads(4);
        session = env.createSession(model, o);
    }

    void close() { try { session.close(); } catch (Exception ignored) { } }

    /** One inference on a bitmap; returns people in that bitmap's normalized coordinates. */
    private List<PoseMath.Det> infer(Bitmap bmp) throws Exception {
        int W = bmp.getWidth(), H = bmp.getHeight();
        float sc = (float) S / Math.max(W, H);
        int nw = Math.round(W * sc), nh = Math.round(H * sc);
        Bitmap r = Bitmap.createScaledBitmap(bmp, nw, nh, true);
        int[] px = new int[nw * nh]; r.getPixels(px, 0, nw, 0, 0, nw, nh);
        float[] in = new float[3 * S * S];
        java.util.Arrays.fill(in, 114f / 255f);
        for (int y = 0; y < nh; y++) for (int x = 0; x < nw; x++) {
            int p = px[y * nw + x];
            in[y * S + x] = ((p >> 16) & 255) / 255f;
            in[S * S + y * S + x] = ((p >> 8) & 255) / 255f;
            in[2 * S * S + y * S + x] = (p & 255) / 255f;
        }
        float[][] raw;
        Map<String, OnnxTensor> inputs = new HashMap<>();
        try (OnnxTensor t = OnnxTensor.createTensor(env, FloatBuffer.wrap(in), new long[]{1, 3, S, S})) {
            inputs.put(session.getInputNames().iterator().next(), t);
            try (OrtSession.Result res = session.run(inputs)) { raw = ((float[][][]) res.get(0).getValue())[0]; }
        }
        return PoseMath.decode(raw, nw, nh);
    }

    /** wide=false: whole frame. wide=true: whole frame plus 6 overlapping tiles (sees smaller people, slower). */
    List<Person> count(Bitmap frame, boolean wide) throws Exception {
        List<Person> all = new ArrayList<>();
        for (PoseMath.Det d : infer(frame)) all.add(PoseMath.toPerson(d, 0, 0, 1, 1));
        if (wide) {
            int W = frame.getWidth(), H = frame.getHeight();
            float[] xs = {0f, 0.25f, 0.5f}, ys = {0f, 0.4f};
            for (float x : xs) for (float y : ys) {
                int cx = Math.round(x * W), cy = Math.round(y * H), cw = Math.round(0.5f * W), ch = Math.round(0.6f * H);
                Bitmap crop = Bitmap.createBitmap(frame, cx, cy, Math.min(cw, W - cx), Math.min(ch, H - cy));
                List<Person> add = new ArrayList<>();
                for (PoseMath.Det d : infer(crop)) add.add(PoseMath.toPerson(d, x, y, 0.5f, 0.6f));
                PoseMath.merge(all, add);
            }
        }
        Collections.sort(all, (a, b) -> Float.compare(a.x0, b.x0));
        return all;
    }

}
