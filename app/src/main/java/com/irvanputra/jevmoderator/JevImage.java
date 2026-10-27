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

    static final int MAX_N = 30;

    private static JSONObject countQuestion(String text) throws Exception {
        JSONObject q = new JSONObject(); q.put("type", "choice"); q.put("instructions", text);
        JSONObject crit = new JSONObject();
        for (int i = 0; i <= MAX_N; i++) crit.put("n" + i, i == 1 ? "Exactly 1" : "Exactly " + i);
        crit.put("n" + (MAX_N + 1) + "_plus", "More than " + MAX_N);
        crit.put("unclear", "Too dark, blurry or crowded to count");
        q.put("criteria", crit); return q;
    }

    static JSONObject buildRequest(String ref, JSONObject asset) throws Exception {
        JSONObject assets = new JSONObject(); assets.put(ref, asset);
        JSONObject state = new JSONObject(); state.put("photo", ref);
        JSONObject qs = new JSONObject();
        qs.put("people_count", countQuestion("How many separate people are clearly visible in `photo`? Count each person once, including partly visible ones. Do not try to identify anyone."));
        qs.put("raised_hand_count", countQuestion("How many people in `photo` have a hand clearly raised above their shoulder or head? Count people, not hands. Do not try to identify anyone."));
        JSONObject body = new JSONObject();
        body.put("model", "jev-latest"); body.put("state", state); body.put("assets", assets); body.put("questions", qs);
        return body;
    }

    /** Jev's estimate. people/hands = -1 when Jev said unclear; MAX_N+1 means "more than MAX_N". */
    static final class Result { int people, hands; double pPeople, pHands; }

    static Result ask(String apiKey, byte[] jpeg) throws Exception { return ask(JevClient.URL, apiKey, buildRequest(jpeg)); }

    static Result ask(String endpoint, String apiKey, JSONObject body) throws Exception {
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

    private static int parseN(String choice) {
        if (choice.equals("unclear")) return -1;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^n(\\d+)(_plus)?$").matcher(choice);
        if (!m.matches()) throw new IllegalArgumentException("unexpected answer");
        return Integer.parseInt(m.group(1));
    }

    static Result summarize(String json) throws Exception {
        JSONObject a = new JSONObject(json).getJSONObject("answers");
        Result r = new Result();
        JSONObject p = a.getJSONObject("people_count"), h = a.getJSONObject("raised_hand_count");
        r.people = parseN(p.getString("choice")); r.hands = parseN(h.getString("choice"));
        r.pPeople = p.getJSONObject("probabilities").optDouble(p.getString("choice"), Double.NaN);
        r.pHands = h.getJSONObject("probabilities").optDouble(h.getString("choice"), Double.NaN);
        return r;
    }

    static String show(int n) { return n < 0 ? "?" : (n > MAX_N ? ">" + MAX_N : String.valueOf(n)); }
}
