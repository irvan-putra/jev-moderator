package com.irvanputra.jevmoderator;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Optional call to TypeSafe's Jev model (official endpoint https://api.typesafe.ai/v1/systemone).
 * Sends ONLY text and numbers: the question, hand and people counts. Never camera frames.
 * The API key is passed in by the caller and is never logged.
 */
final class JevClient {
    static final String URL = "https://api.typesafe.ai/v1/systemone";

    static final class Round { String question; int hands, people; boolean manual; }

    static JSONObject buildRequest(Round now, List<Round> before) throws Exception {
        JSONObject state = new JSONObject();
        state.put("moderator_question", now.question.isEmpty() ? "(not entered)" : now.question);
        state.put("hands_raised_at_once", now.hands);
        state.put("people_counted", now.people);
        state.put("counts_source", now.manual ? "typed by the moderator" : "phone camera estimate; may undercount hidden, far or small people");
        JSONArray prev = new JSONArray();
        for (Round r : before) {
            JSONObject o = new JSONObject();
            o.put("question", r.question.isEmpty() ? "(not entered)" : r.question);
            o.put("hands", r.hands); o.put("people", r.people);
            prev.put(o);
        }
        state.put("earlier_rounds", prev);

        JSONObject reaction = new JSONObject();
        reaction.put("type", "choice");
        reaction.put("instructions", "How did the audience respond to the moderator's question, based on hands raised versus people counted?");
        JSONObject crit = new JSONObject();
        crit.put("strong", "A large share of the counted people raised a hand");
        crit.put("mixed", "A moderate share raised a hand");
        crit.put("low", "Few or no hands were raised");
        crit.put("unclear", "The numbers are too small, inconsistent or unreliable to tell");
        reaction.put("criteria", crit);

        JSONObject follow = new JSONObject();
        follow.put("type", "noul");
        follow.put("instructions", "Would a follow-up question help the moderator learn more from this audience, given these results?");

        JSONObject qs = new JSONObject();
        qs.put("audience_reaction", reaction);
        qs.put("follow_up_worth_it", follow);

        JSONObject body = new JSONObject();
        body.put("model", "jev-latest");
        body.put("state", state);
        body.put("questions", qs);
        return body;
    }

    /** Runs on a background thread. Returns a one-line human summary or throws with a safe message. */
    static String ask(String apiKey, Round now, List<Round> before) throws Exception { return ask(URL, apiKey, now, before); }

    static String ask(String endpoint, String apiKey, Round now, List<Round> before) throws Exception {
        byte[] payload = buildRequest(now, before).toString().getBytes(StandardCharsets.UTF_8);
        HttpURLConnection c = (HttpURLConnection) new java.net.URL(endpoint).openConnection();
        c.setConnectTimeout(8000); c.setReadTimeout(15000); c.setRequestMethod("POST"); c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("Authorization", "Bearer " + apiKey.trim());
        try (OutputStream os = c.getOutputStream()) { os.write(payload); }
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        String text = "";
        if (in != null) { ByteArrayOutputStream bo = new ByteArrayOutputStream(); byte[] b = new byte[4096]; int n; while ((n = in.read(b)) > 0) bo.write(b, 0, n); text = bo.toString("UTF-8"); }
        if (code == 401 || code == 403) throw new Exception("Jev rejected the key (HTTP " + code + ").");
        if (code == 429 || code == 529) throw new Exception("Jev is busy or rate limited (HTTP " + code + "). Try again shortly.");
        if (code >= 400) throw new Exception("Jev error HTTP " + code + ".");
        return summarize(text);
    }

    static String summarize(String json) throws Exception {
        JSONObject a = new JSONObject(json).getJSONObject("answers");
        JSONObject r = a.getJSONObject("audience_reaction");
        String choice = r.getString("choice");
        double pr = r.getJSONObject("probabilities").optDouble(choice, Double.NaN);
        double conf = r.optDouble("confidence", Double.NaN);
        double fu = a.getJSONObject("follow_up_worth_it").getDouble("noul");
        return String.format(java.util.Locale.US, "Jev: reaction %s (probability %.2f, confidence %.2f); follow-up worth it %.2f", choice, pr, conf, fu);
    }
}
