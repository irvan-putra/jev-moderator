package com.irvanputra.jevmoderator;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Size;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import com.google.common.util.concurrent.ListenableFuture;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {
    private static final int REQ_CAM = 7, REQ_PICK = 8;
    private final SecureRandom rng = new SecureRandom();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private PreviewView preview; private ImageView frozenView; private OverlayView overlay;
    private TextView tvModels, tvPeople, tvHands, tvPeak, tvStatus, tvPick, tvLog, tvSrc;
    private CheckBox cbWide, cbManual; private Spinner spDur; private EditText etQ;
    private Button btnCam, btnRound;

    private volatile PoseCounter counter; private volatile String counterModel = "";
    private volatile boolean running = false, frozen = false, busy = false;
    private volatile List<Person> poses = new ArrayList<>();
    private volatile Bitmap lastFrame;
    private final List<List<Person>> history = new ArrayList<>();   // last raw scans, for the 2-frame check
    private int people, hands, manualP, manualH, peak, roundPeople;
    private long roundUntil = 0;
    private final List<String> log = new ArrayList<>();
    private ProcessCameraProvider provider;
    private final List<JevClient.Round> rounds = new ArrayList<>();
    private CheckBox cbJev; private EditText etKey; private TextView tvJev, tvPhoto; private KeyVault vault;

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
    private TextView label(String t, int sp, boolean bold) {
        TextView v = new TextView(this); v.setText(t); v.setTextSize(sp); v.setTextColor(0xFF251F21);
        if (bold) v.setTypeface(null, android.graphics.Typeface.BOLD); return v;
    }
    private Button btn(String t, int color, View.OnClickListener l) {
        Button b = new Button(this); b.setText(t); b.setAllCaps(false); b.setOnClickListener(l);
        b.setBackgroundColor(color); b.setTextColor(color == 0xFFFFFFFF ? 0xFF4E7F72 : Color.WHITE); return b;
    }
    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this); r.setOrientation(LinearLayout.HORIZONTAL); r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, dp(4), 0, dp(4)); return r;
    }
    private TextView metric(LinearLayout parent, String cap) {
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setGravity(Gravity.CENTER);
        TextView n = label("0", 32, true); n.setGravity(Gravity.CENTER);
        TextView c = label(cap, 12, false); c.setGravity(Gravity.CENTER);
        box.addView(n); box.addView(c);
        parent.addView(box, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return n;
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        ScrollView sv = new ScrollView(this); sv.setBackgroundColor(0xFFF4EFEC);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(dp(14), dp(14), dp(14), dp(24));
        sv.addView(root);
        final android.content.SharedPreferences cp = getSharedPreferences("crash", MODE_PRIVATE);
        final Thread.UncaughtExceptionHandler prevH = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((th, ex) -> {
            try { cp.edit().putString("last", safeTrace(ex)).commit(); } catch (Throwable ignored) { }   // never blocks the normal crash path
            if (prevH != null) prevH.uncaughtException(th, ex);
        });
        root.addView(label("Audience Moderator", 22, true));
        String lastCrash = cp.getString("last", null);
        if (lastCrash != null) {
            TextView ct = label("The app closed unexpectedly last time. Details (stay on this phone; screenshot them if you want help):\n" + lastCrash.substring(0, Math.min(900, lastCrash.length())), 11, false);
            ct.setBackgroundColor(0xFFFBE9E9); ct.setPadding(dp(8), dp(8), dp(8), dp(8)); root.addView(ct);
            root.addView(btn("Hide this message", 0xFFFFFFFF, v -> { cp.edit().remove("last").apply(); ct.setVisibility(View.GONE); v.setVisibility(View.GONE); }));
        }
        TextView sub = label("Counts people the camera sees, counts raised hands per question, and picks a random person. Camera pictures are processed on this phone only: no uploads, no recording, no names, no face recognition. The optional Jev round insight (off by default) sends question text and counts only. The separate photo question sends a picture only when you pick it, preview it and tap Send.", 12, false);
        root.addView(sub);

        LinearLayout m = row(); tvPeople = metric(m, "people counted"); tvHands = metric(m, "hands up now"); tvPeak = metric(m, "most hands at once (this round)");
        root.addView(m);
        tvSrc = label("source: camera", 12, false); root.addView(tvSrc);

        FrameLayout stage = new FrameLayout(this); stage.setBackgroundColor(Color.BLACK);
        preview = new PreviewView(this); preview.setScaleType(PreviewView.ScaleType.FIT_CENTER);
        preview.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);
        frozenView = new ImageView(this); frozenView.setScaleType(ImageView.ScaleType.FIT_CENTER); frozenView.setVisibility(View.GONE); frozenView.setBackgroundColor(Color.BLACK);
        overlay = new OverlayView(this);
        stage.addView(preview, new FrameLayout.LayoutParams(-1, -1)); stage.addView(frozenView, new FrameLayout.LayoutParams(-1, -1)); stage.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        root.addView(stage, new LinearLayout.LayoutParams(-1, dp(360)));

        LinearLayout r1 = row();
        btnCam = btn("Start camera", 0xFF73A89A, v -> toggleCamera()); r1.addView(btnCam);
        root.addView(r1);
        cbWide = new CheckBox(this); cbWide.setText("Wide-room scan (7 passes per update; slow, about 4 s on a Galaxy A24; helps a little with farther people)"); cbWide.setChecked(false); root.addView(cbWide);
        tvStatus = label("Camera is off.", 13, false); root.addView(tvStatus);
        TextView note = label("Limits: it only counts people the camera can see. Hidden, tiny, far or dark people are missed and a packed room will undercount. A raised hand is a wrist clearly above the nose; a half-raised hand may not count. Use manual +/- to correct by eye.", 12, false);
        note.setBackgroundColor(0xFFFAF6E8); note.setPadding(dp(8), dp(8), dp(8), dp(8)); root.addView(note);
        root.addView(label("Models used", 15, true));
        tvModels = label("", 12, false); tvModels.setBackgroundColor(0xFFF0F3F1); tvModels.setPadding(dp(8), dp(8), dp(8), dp(8)); root.addView(tvModels);
        updateModels();


        root.addView(label("Manual correction", 15, true));
        LinearLayout mr = row();
        mr.addView(label("People ", 15, false)); mr.addView(btn("-", 0xFFFFFFFF, v -> adj(0, -1))); mr.addView(label(" ", 15, false)); mr.addView(btn("+", 0xFFFFFFFF, v -> adj(0, 1)));
        mr.addView(label("  Hands ", 15, false)); mr.addView(btn("-", 0xFFFFFFFF, v -> adj(1, -1))); mr.addView(btn("+", 0xFFFFFFFF, v -> adj(1, 1)));
        root.addView(mr);
        cbManual = new CheckBox(this); cbManual.setText("Use manual numbers"); cbManual.setOnCheckedChangeListener((c, on) -> { if (on) { manualP = people; manualH = hands; } render(); }); root.addView(cbManual);

        root.addView(label("Question round", 15, true));
        etQ = new EditText(this); etQ.setHint("Question (optional)"); etQ.setSingleLine(); root.addView(etQ);
        LinearLayout rr = row();
        spDur = new Spinner(this); spDur.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, new String[]{"5 s", "8 s", "12 s"})); spDur.setSelection(2);
        rr.addView(spDur); btnRound = btn("Ask: count hands", 0xFF73A89A, v -> startRound()); rr.addView(btnRound);
        rr.addView(btn("Clear log", 0xFFFFFFFF, v -> { log.clear(); showLog(); }));
        root.addView(rr);
        tvLog = label("(no rounds yet)", 14, false); root.addView(tvLog);

        root.addView(label("Round insight (optional, uses Jev online)", 15, true));
        root.addView(label("Off by default. When switched on, after each round ONLY the question text and the hand/people counts are sent to TypeSafe's Jev model (api.typesafe.ai). No pictures are sent by this option. You need your own TypeSafe API key. Everything else works without it.", 12, false));
        vault = new KeyVault(this);
        cbJev = new CheckBox(this); cbJev.setText("Send round numbers to Jev for an insight"); root.addView(cbJev); cbJev.setOnCheckedChangeListener((c, on) -> updateModels());
        etKey = new EditText(this); etKey.setHint(vault.has() ? "API key saved (hidden). Paste a new one to replace." : "Paste your TypeSafe API key");
        etKey.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD); etKey.setSingleLine(); root.addView(etKey);
        LinearLayout jr = row();
        jr.addView(btn("Save key", 0xFF73A89A, v -> saveKey()));
        jr.addView(btn("Forget key", 0xFFFFFFFF, v -> { vault.clear(); etKey.setText(""); etKey.setHint("Paste your TypeSafe API key"); cbJev.setChecked(false); tvJev.setText("Key removed."); }));
        jr.addView(btn("Send a test", 0xFFFFFFFF, v -> sendTest()));
        root.addView(jr);
        tvJev = label("Jev is off.", 13, false); root.addView(tvJev);

        root.addView(label("Photo question to Jev (experimental, you choose each photo)", 15, true));
        root.addView(label("Jev's image feature is marked experimental by TypeSafe. Nothing is sent unless you tap one of these buttons, check the photo in the preview and tap Send. Only that one photo goes to TypeSafe (api.typesafe.ai). It may show people's faces. It asks whether hands are raised and never identifies anyone. The answer is a second opinion and never changes the counts. Needs your key above.", 12, false));
        LinearLayout phr = row();
        phr.addView(btn("Pick a photo", 0xFFFFFFFF, v -> pickPhoto()));
        phr.addView(btn("Use current camera frame", 0xFFFFFFFF, v -> frameToJev()));
        root.addView(phr);
        tvPhoto = label("No photo sent.", 13, false); root.addView(tvPhoto);

        root.addView(label("Random audience pick", 15, true));
        root.addView(label("Choose the pool each time. The picture freezes on the picked person (yellow box). It cannot know names.", 12, false));
        LinearLayout pr = row();
        pr.addView(btn("Everyone seen", 0xFF73A89A, v -> pick(false))); pr.addView(btn("Raised hands", 0xFFED313E, v -> pick(true)));
        pr.addView(btn("Resume", 0xFFFFFFFF, v -> unfreeze()));
        root.addView(pr);
        tvPick = label("-", 22, true); tvPick.setGravity(Gravity.CENTER); root.addView(tvPick);
        root.addView(btn("Random number 1..N (no camera)", 0xFFFFFFFF, v -> pickNumber()));
        setContentView(sv);
        render();
    }

    private void adj(int which, int d) {
        if (which == 0) manualP = Math.max(0, manualP + d); else manualH = Math.max(0, manualH + d);
        cbManual.setChecked(true); render();
    }
    private int effP() { return cbManual.isChecked() ? manualP : people; }
    private int effH() { return cbManual.isChecked() ? manualH : hands; }
    private void render() {
        tvPeople.setText(String.valueOf(effP())); tvHands.setText(String.valueOf(effH())); tvPeak.setText(String.valueOf(peak));
        tvSrc.setText("source: " + (cbManual.isChecked() ? "manual numbers (people " + manualP + ", hands " + manualH + ")" : "camera"));
    }
    private void status(String s) { runOnUiThread(() -> tvStatus.setText(s)); }



    /** Crash note kept on the phone only: class names and code locations. No exception messages (they can hold typed text or keys). */
    static String safeTrace(Throwable ex) {
        StringBuilder sb = new StringBuilder(); int depth = 0;
        for (Throwable t = ex; t != null && depth < 3; t = t.getCause(), depth++) {
            sb.append(depth == 0 ? "" : "caused by ").append(t.getClass().getName()).append('\n');
            StackTraceElement[] st = t.getStackTrace();
            for (int i = 0; i < Math.min(10, st.length); i++) sb.append("  at ").append(st[i].getClassName()).append('.').append(st[i].getMethodName()).append(':').append(st[i].getLineNumber()).append('\n');
        }
        return sb.toString();
    }

    /** Describes what is actually inside this build and its live state. */
    private void updateModels() {
        if (tvModels == null) return;
        String size = "?";
        try (android.content.res.AssetFileDescriptor fd = getAssets().openFd("yolov8n-pose.onnx")) { size = String.format(java.util.Locale.US, "%.1f MB", fd.getLength() / 1048576.0); } catch (Exception ignored) { }
        String ort = "?"; try { ort = ai.onnxruntime.OrtEnvironment.getEnvironment().getVersion(); } catch (Throwable ignored) { }
        boolean jevOn = cbJev != null && cbJev.isChecked();
        tvModels.setText(
            "1. People and hands (on this phone, no internet): YOLOv8n-pose by Ultralytics (AGPL-3.0), ONNX export from Hugging Face Xenova/yolov8n-pose, file yolov8n-pose.onnx, " + size + ", 640x640 input, run by ONNX Runtime " + ort + " on the CPU. Status: " + (counter != null ? "loaded" : "not loaded yet (loads when you start the camera)") + ". It finds body boxes and 17 body points per person; a hand counts as raised when the wrist is above the nose or shoulders. It does not know who anyone is.\n"
          + "2. Round insight (online, optional): Jev (model jev-latest) by TypeSafe through api.typesafe.ai. Status: " + (jevOn ? "switched on" : "off") + (vault != null && vault.has() ? ", key saved" : ", no key saved") + ". Sends text and numbers only. Not yet tried against the live service by the author.\n"
          + "3. Photo question (online, optional, experimental): the same Jev model through api.typesafe.ai with TypeSafe's experimental images flag. Sends one photo only after you pick it, preview it and tap Send. Not yet tried against the live service by the author.\n"
          + "Random pick is a plain random choice among the detected boxes, not a model.");
    }

    // ---- optional photo question to Jev: only after the user picks a photo, sees it, and taps Send ----
    private void pickPhoto() {
        if (!vault.has()) { tvPhoto.setText("Save a TypeSafe key first. Nothing was sent."); return; }
        android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_GET_CONTENT);
        i.setType("image/*"); i.addCategory(android.content.Intent.CATEGORY_OPENABLE);
        startActivityForResult(android.content.Intent.createChooser(i, "Pick a photo for Jev"), REQ_PICK);
    }
    @Override protected void onActivityResult(int req, int res, android.content.Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_PICK || res != RESULT_OK || data == null || data.getData() == null) return;
        final android.net.Uri uri = data.getData();
        worker.execute(() -> {
            try {
                Bitmap b = loadPicked(uri);
                if (b == null) { ui.post(() -> tvPhoto.setText("Could not read that photo. Nothing was sent.")); return; }
                final Bitmap fb = b; ui.post(() -> confirmSend(fb));
            } catch (Throwable t) { ui.post(() -> tvPhoto.setText("Could not read that photo. Nothing was sent.")); }
        });
    }
    private Bitmap loadPicked(android.net.Uri uri) throws Exception {
        android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options(); o.inJustDecodeBounds = true;
        try (java.io.InputStream in = getContentResolver().openInputStream(uri)) { android.graphics.BitmapFactory.decodeStream(in, null, o); }
        int sample = 1; while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= 1568) sample *= 2;
        android.graphics.BitmapFactory.Options o2 = new android.graphics.BitmapFactory.Options(); o2.inSampleSize = sample;
        Bitmap b; try (java.io.InputStream in = getContentResolver().openInputStream(uri)) { b = android.graphics.BitmapFactory.decodeStream(in, null, o2); }
        if (b == null) return null;
        int rot = 0;
        try (java.io.InputStream in = getContentResolver().openInputStream(uri)) {
            int ori = new android.media.ExifInterface(in).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1);
            if (ori == 6) rot = 90; else if (ori == 3) rot = 180; else if (ori == 8) rot = 270;
        } catch (Throwable ignored) { }
        if (rot != 0) { Matrix mx = new Matrix(); mx.postRotate(rot); b = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), mx, true); }
        return b;
    }
    private void frameToJev() {
        if (!vault.has()) { tvPhoto.setText("Save a TypeSafe key first. Nothing was sent."); return; }
        Bitmap f = lastFrame;
        if (f == null) { tvPhoto.setText("Start the camera first. Nothing was sent."); return; }
        confirmSend(f.copy(Bitmap.Config.ARGB_8888, false));
    }
    private void confirmSend(final Bitmap src) {
        final int[] t = JevImage.targetSize(src.getWidth(), src.getHeight());
        if (t == null) { tvPhoto.setText("That photo's shape is outside Jev's image limits. Nothing was sent."); return; }
        final Bitmap out = (t[0] == src.getWidth() && t[1] == src.getHeight()) ? src : Bitmap.createScaledBitmap(src, t[0], t[1], true);
        ImageView iv = new ImageView(this); iv.setImageBitmap(out); iv.setAdjustViewBounds(true); iv.setPadding(dp(8), dp(8), dp(8), dp(8));
        new androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Send this photo to TypeSafe's Jev?")
            .setMessage("Only this one photo is sent (" + t[0] + "x" + t[1] + "). It may show people's faces. Jev is asked whether hands are raised and is told not to identify anyone. Image support is experimental.")
            .setView(iv)
            .setPositiveButton("Send", (d, w) -> sendPhoto(out))
            .setNegativeButton("Cancel", (d, w) -> tvPhoto.setText("Cancelled. Nothing was sent."))
            .setOnCancelListener(d -> tvPhoto.setText("Cancelled. Nothing was sent."))
            .show();
    }
    private void sendPhoto(final Bitmap out) {
        tvPhoto.setText("Sending the photo you confirmed to Jev...");
        new Thread(() -> {
            String res;
            try {
                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                out.compress(Bitmap.CompressFormat.JPEG, 88, bo);
                res = JevImage.ask(vault.load(), bo.toByteArray());
            } catch (Throwable e) { res = "Jev unavailable: " + e.getMessage(); }
            final String r = res; runOnUiThread(() -> tvPhoto.setText(r));
        }).start();
    }

    // ---- camera ----
    private void toggleCamera() {
        if (running) { stopCamera(); return; }
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) { requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAM); return; }
        startCamera();
    }
    @Override public void onRequestPermissionsResult(int rc, String[] p, int[] g) {
        super.onRequestPermissionsResult(rc, p, g);
        if (rc == REQ_CAM && g.length > 0 && g[0] == PackageManager.PERMISSION_GRANTED) startCamera();
        else status("Camera permission denied. Manual +/- and the number picker still work.");
    }
    private void startCamera() {
        btnCam.setEnabled(false);
        final String model = "yolov8n-pose.onnx";
        status("Loading model...");
        worker.execute(() -> {
            try {
                if (counter == null || !counterModel.equals(model)) { if (counter != null) counter.close(); counter = new PoseCounter(this, model); counterModel = model; }
            } catch (Throwable t) { status("Model failed: " + t.getMessage() + ". Manual mode still works."); runOnUiThread(() -> btnCam.setEnabled(true)); return; }
            runOnUiThread(() -> { updateModels(); bindCamera(); });
        });
    }
    private void bindCamera() {
        ListenableFuture<ProcessCameraProvider> f = ProcessCameraProvider.getInstance(this);
        f.addListener(() -> {
            try {
                provider = f.get(); provider.unbindAll();
                Preview pv = new Preview.Builder().build(); pv.setSurfaceProvider(preview.getSurfaceProvider());
                ImageAnalysis ia = new ImageAnalysis.Builder().setTargetResolution(new Size(960, 1280))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888).build();
                ia.setAnalyzer(worker, this::analyze);
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, pv, ia);
                running = true; frozen = false; btnCam.setText("Stop camera"); btnCam.setEnabled(true);
                status("Running (YOLOv8n-pose). Counts only people the camera can see.");
            } catch (Throwable t) { status("Camera error: " + t.getMessage()); btnCam.setEnabled(true); }
        }, ContextCompat.getMainExecutor(this));
    }
    private void stopCamera() {
        running = false; history.clear(); if (provider != null) provider.unbindAll();
        btnCam.setText("Start camera"); status("Camera off."); overlay.set(new ArrayList<>(), 1, 1, -1);
    }

    private void analyze(ImageProxy ip) {
        try {
            if (!running || frozen || busy) return;
            busy = true;
            Bitmap bmp = ip.toBitmap();
            int rot = ip.getImageInfo().getRotationDegrees();
            if (rot != 0) { Matrix mx = new Matrix(); mx.postRotate(rot); bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), mx, true); }
            long t0 = System.currentTimeMillis();
            List<Person> raw = counter.count(bmp, cbWide.isChecked());
            List<Person> list = PoseMath.confirm(raw, history);
            history.add(0, raw); while (history.size() > 2) history.remove(history.size() - 1);
            long ms = System.currentTimeMillis() - t0;
            if (frozen) return;
            lastFrame = bmp; poses = list;
            final int fw = bmp.getWidth(), fh = bmp.getHeight();
            int h = 0; for (Person p : list) if (p.raised) h++;
            final int hh = h;
            ui.post(() -> {
                people = list.size(); hands = hh;
                if (roundUntil > 0) {
                    peak = Math.max(peak, effH()); roundPeople = Math.max(roundPeople, effP());
                    long left = (roundUntil - System.currentTimeMillis() + 999) / 1000;
                    if (left <= 0) endRound(); else tvStatus.setText("Raise your hands now! " + left + " s");
                }
                overlay.set(list, fw, fh, -1); render();
            });
            if (roundUntil == 0) status("Running. Last update " + ms + " ms.");
        } catch (Throwable t) { status("Scan error: " + t.getMessage()); }
        finally { busy = false; ip.close(); }
    }

    // ---- rounds ----
    private void startRound() {
        if (!running && !cbManual.isChecked()) { status("Start the camera first, or tick manual numbers."); return; }
        if (!running) { log.add(logLine(etQ.getText().toString(), manualH, manualP)); showLog(); insight(etQ.getText().toString(), manualH, manualP, true, false); return; }
        peak = 0; roundPeople = 0;
        int[] secs = {5, 8, 12};
        roundUntil = System.currentTimeMillis() + secs[spDur.getSelectedItemPosition()] * 1000L;
        btnRound.setEnabled(false);
    }
    private void saveKey() {
        String k = etKey.getText().toString().trim();
        if (k.isEmpty()) { tvJev.setText("Paste a key first."); return; }
        try { vault.save(k); etKey.setText(""); etKey.setHint("API key saved (hidden). Paste a new one to replace."); tvJev.setText("Key saved on this phone (encrypted). Tick the box to use it."); updateModels(); }
        catch (Throwable t) { tvJev.setText("Could not store the key safely: " + t.getMessage()); }
    }
    private void insight(String q, int h, int p, boolean manual, boolean isTest) {
        if (!isTest && !cbJev.isChecked()) return;
        if (!vault.has()) { tvJev.setText("No key saved. Round insight skipped."); return; }
        final JevClient.Round now = new JevClient.Round(); now.question = q.trim(); now.hands = h; now.people = p; now.manual = manual;
        final List<JevClient.Round> before = new ArrayList<>(rounds);
        if (!isTest) rounds.add(now);
        tvJev.setText("Asking Jev (sending question text and counts only)...");
        new Thread(() -> {
            String out;
            try { out = (isTest ? "TEST with sample numbers: " : "") + JevClient.ask(vault.load(), now, before); }
            catch (Throwable t) { out = "Jev unavailable: " + t.getMessage(); }
            final String o = out;
            runOnUiThread(() -> { tvJev.setText(o); if (!isTest) { log.add("   " + o); showLog(); } });
        }).start();
    }
    private void sendTest() {
        if (!vault.has()) { tvJev.setText("Save a key first."); return; }
        insight("Test question", 7, 20, true, true);
    }
    private void endRound() {
        roundUntil = 0; btnRound.setEnabled(true);
        log.add(logLine(etQ.getText().toString(), peak, roundPeople)); showLog();
        insight(etQ.getText().toString(), peak, roundPeople, cbManual.isChecked(), false);
        status("Round done: peak " + peak + " hands of " + roundPeople + " people.");
    }
    private String logLine(String q, int h, int p) {
        String lbl = q.trim().isEmpty() ? "-" : q.trim();
        return "Q" + (log.size() + 1) + " " + lbl + ": " + h + " hands / " + p + " people" + (p > 0 ? " (" + Math.round(100f * h / p) + "%)" : "");
    }
    private void showLog() { tvLog.setText(log.isEmpty() ? "(no rounds yet)" : android.text.TextUtils.join("\n", log)); }

    // ---- random pick ----
    private void pick(boolean handsOnly) {
        if (!running && lastFrame == null) { tvPick.setText("Start the camera first."); return; }
        List<Person> snap = new ArrayList<>(poses);
        List<Integer> idx = new ArrayList<>();
        for (int i = 0; i < snap.size(); i++) if (!handsOnly || snap.get(i).raised) idx.add(i);
        if (idx.isEmpty()) { tvPick.setText(handsOnly ? "No raised hands right now." : "Nobody visible."); return; }
        int choice = idx.get(rng.nextInt(idx.size()));
        frozen = true;
        Bitmap f = lastFrame;
        if (f != null) { frozenView.setImageBitmap(f); frozenView.setVisibility(View.VISIBLE); overlay.set(snap, f.getWidth(), f.getHeight(), choice); }
        tvPick.setText("Picked #" + (choice + 1) + " of " + idx.size() + " (" + (handsOnly ? "raised hands" : "everyone seen") + ") - yellow box");
    }
    private void unfreeze() { frozen = false; frozenView.setVisibility(View.GONE); tvPick.setText("-"); }
    private void pickNumber() {
        int n = effP(); if (n < 1) { tvPick.setText("Set a people count first (manual +)."); return; }
        tvPick.setText("Number " + (rng.nextInt(n) + 1) + " of " + n + " (count off from the left/front)");
    }

    @Override protected void onStop() { super.onStop(); if (running) stopCamera(); }
    @Override protected void onDestroy() { super.onDestroy(); worker.shutdown(); }
}
