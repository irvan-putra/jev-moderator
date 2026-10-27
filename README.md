# Audience Moderator (Android)

A small helper for an event moderator.

Features
- People counter: counts people the camera can see (on-device MediaPipe pose model, Lite or Full).
- Hands-up counter: a raised hand is a wrist clearly above the nose.
- Question rounds: peak hands over 5/8/12 seconds, logged per question.
- Random audience pick: separate buttons for "everyone seen" and "raised hands". The frame freezes with a yellow box on the picked person.
- Manual +/- fallback and a no-camera random number picker.

Privacy: counting, hands and the random pick run on the phone. Camera pictures are never saved or sent, and there is no face recognition or identity matching (boxes and positions only, never names). The app has the INTERNET permission only for the optional, off-by-default Round insight: when you switch it on and save your own TypeSafe API key, each round sends only the question text and the hand/people counts to TypeSafe's Jev model at https://api.typesafe.ai/v1/systemone. The key is stored encrypted with an Android Keystore key, is not in this repository and is never logged.

Limits: people who are hidden, tiny, far away or in the dark are missed; a packed room will undercount. Wide-room scan splits the picture into tiles to see farther people but updates more slowly.

Build: `gradle assembleDebug` (JDK 17, Android SDK 34). Debug APK, arm64 only. Models: Google MediaPipe pose_landmarker lite/full (Apache-2.0).

## Engine and licence
Detection uses YOLOv8n-pose (Ultralytics, AGPL-3.0) exported to ONNX (from Hugging Face Xenova/yolov8n-pose) and run with ONNX Runtime (MIT). Because of the YOLOv8 weights this repository is licensed AGPL-3.0 (see LICENSE). Source is public.
A round result is the most hands seen in a single camera frame during the window, not a count of unique voters.
Tests: `PoseMath` (box mapping, raised-hand rule, tile merge) is plain Java and was checked on a JVM; the APK itself has not been run on a device at time of writing.

Round insight uses the official TypeSafe API (docs.typesafe.ai): POST /v1/systemone with Authorization: Bearer <key>, model jev-latest, one Choice and one Noul question. It was tested against a local mock server only, not the live service.

## v0.4 changes (false-positive repair)
An empty furnished room was counted as 5 people on a Galaxy A24 (v0.3). v0.4 adds an evidence check (clear score, at least 5 body points, a shoulder plus a head or hip point, no tile-sized boxes), a two-frame confirmation before a person is counted, and shows each box's score. The wide-room scan is now off by default (about 4 s per update on the A24). Counts are still estimates: use the manual +/- buttons to correct them. The app screen now lists the models used.

## v0.4.1 (crash fix)
v0.4 crashed whenever Start camera was tapped: the "Models used" panel was missing from the screen code while the camera start still tried to update it (a null field). v0.4.1 adds the panel for real, guards the update, and keeps a local-only crash note (class names and code lines, no messages) that is shown on the next launch. Not yet tested on a phone.

Icon: the launcher icon is a sticker image supplied by the app owner; its original source and license are not known to the author.
