package com.irvanputra.jevmoderator;

import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Optional, experimental photo question to TypeSafe's Jev (images are a "danger zone" feature, see docs.typesafe.ai/go/images).
 * Sends ONE photo that the moderator explicitly chose and confirmed. Nothing else, never automatically.
 * Result is a second opinion only and never changes the counts.
 */
final class JevImage {
    static final int MAX_SIDE = 1568, MIN_SIDE = 280;

    /** Returns {w,h} within the API limits (longest side <= 1568, both sides >= 280), or null if the shape cannot fit. */
    static int[] targetSize(int w, int h) {
        if (w <= 0 || h <= 0) return null;
        double k = 1.0; int longest = Math.max(w, h), shortest = Math.min(w, h);
        if (longest > MAX_SIDE) k = (double) MAX_SIDE / longest;
        if (shortest * k < MIN_SIDE) k = (double) MIN_SIDE / shortest;
        int nw = (int) Math.round(w * k), nh = (int) Math.round(h * k);
        if (Math.max(nw, nh) > MAX_SIDE || Math.min(nw, nh) < MIN_SIDE) return null;
        return new int[] { nw, nh };
    }

    static JSONObject buildRequest(byte[] jpeg) throws Exception {
        String ref = UUID.randomUUID().toString();
        JSONObject asset = new JSONObject();
        asset.put("type", "image"); asset.put("format", "jpg");
        asset.put("data_base64", android.util.Base64.encodeToString(jpeg, android.util.Base64.NO_WRAP));
        return buildRequest(ref, asset);
    }

    static JSONObject buildRequest(String ref, JSONObject asset) throws Exception {
        JSONObject assets = new JSONObject(); assets.put(ref, asset);
        JSONObject state = new JSONObject(); state.put("photo", ref);
        JSONObject any = new JSONObject();
        any.put("type", "noul");
        any.put("instructions", "Is at least one person in `photo` clearly raising a hand above their shoulder or head? Do not try to identify anyone.");
        JSONObject cnt = new JSONObject();
        cnt.put("type", "choice");
        cnt.put("instructions", "How many people in `photo` have a hand clearly raised above their shoulder or head? Count only clearly visible raised hands.");
        JSONObject crit = new JSONObject();
        crit.put("none", "No raised hand is clearly visible");
        crit.put("one", "Exactly one raised hand");
        crit.put("two_to_four", "Two to four raised hands");
        crit.put("five_or_more", "Five or more raised hands");
        crit.put("unclear", "The photo is too dark, blurry or crowded to tell");
        cnt.put("criteria", crit);
        JSONObject qs = new JSONObject(); qs.put("any_hand_raised", any); qs.put("raised_hand_count", cnt);
        JSONObject body = new JSONObject();
        body.put("model", "jev-latest"); body.put("state", state); body.put("assets", assets); body.put("questions", qs);
        return body;
    }

    static String ask(String apiKey, byte[] jpeg) throws Exception { return ask(JevClient.URL, apiKey, buildRequest(jpeg)); }

    static String ask(String endpoint, String apiKey, JSONObject body) throws Exception {
        byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
        HttpURLConnection c = (HttpURLConnection) new java.net.URL(endpoint).openConnection();
        c.setConnectTimeout(8000); c.setReadTimeout(30000); c.setRequestMethod("POST"); c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("Authorization", "Bearer " + apiKey.trim());
        c.setRequestProperty("TypeSafe-Danger-Zone-Images", "true");
        try (OutputStream os = c.getOutputStream()) { os.write(payload); }
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        String text = "";
        if (in != null) { ByteArrayOutputStream bo = new ByteArrayOutputStream(); byte[] b = new byte[4096]; int n; while ((n = in.read(b)) > 0) bo.write(b, 0, n); text = bo.toString("UTF-8"); }
        if (code == 401 || code == 403) throw new Exception("Jev rejected the key (HTTP " + code + ").");
        if (code == 429 || code == 529) throw new Exception("Jev is busy or rate limited (HTTP " + code + "). Try again shortly.");
        if (code == 400) throw new Exception("Jev refused the photo request (HTTP 400). Images are experimental; try a different photo.");
        if (code == 413) throw new Exception("Photo too large for Jev (HTTP 413).");
        if (code >= 400) throw new Exception("Jev error HTTP " + code + ".");
        return summarize(text);
    }

    static String summarize(String json) throws Exception {
        JSONObject a = new JSONObject(json).getJSONObject("answers");
        JSONObject c = a.getJSONObject("raised_hand_count");
        String choice = c.getString("choice");
        double pr = c.getJSONObject("probabilities").optDouble(choice, Double.NaN);
        double conf = c.optDouble("confidence", Double.NaN);
        double any = a.getJSONObject("any_hand_raised").getDouble("noul");
        return String.format(java.util.Locale.US, "Jev photo opinion (experimental, does not change the counts): raised hands %s (probability %.2f, confidence %.2f); any hand raised %.2f", choice, pr, conf, any);
    }
}
