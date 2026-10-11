# RecipeDealer capture app

Android app (Kotlin + Jetpack Compose, minSdk 34) that receives shares (link, text, images, PDF), extracts the
recipe on the device and sends a **capture** (structured recipe + all raw material) to a backend over HTTPS / REST.
The full task description is `final-app.md` (sections referenced below as §N).

## Rules
- Conversation with the user in Polish; code, comments, docs and commit messages in English. App UI in Polish.
- Commit per milestone step; never commit secrets (backend token lives only in app settings).
- Keep the data model minimal. The contract is `schema/capture-v1.json` (`schemaVersion` 1): an incompatible
  change means a new version + new schema file, never an edit.
- Do not tune the heading heuristics to a single AI-chat answer; the backend handles the rest.
- When a decision or assumption in `final-app.md` turns out wrong: tell the user, update the docs.

## Decisions (§3, agreed with the user 2026-10-11)
D1 Kotlin + Compose · D2 Polish UI · D3 bearer token in settings (pluggable) · D4 one multipart PUT per capture ·
D5 manual entry form → `MANUAL` capture.

Own assumption: captures are **not** sent automatically by default – the user reviews and taps "Wyślij" (preview
or notification action). Setting "Wysyłaj automatycznie" sends right after extraction.

## Layout
- `model/Capture.kt` – contract data classes (kotlinx.serialization; property order = JSON key order).
- `share/` – `ShareActivity` (translucent trampoline, copies shared files into app storage), `ShareRouter`.
- `capture/` – `CaptureService` (shortService FGS), `CaptureProcessor`, `CaptureBuilder`, extractors per source.
- `store/CaptureStore.kt` – file-based store: `files/captures/<id>/{capture.json,state.json,job.json,files/}`.
- `upload/` – `CaptureSender` (local / HTTP), upload runner.
- `ui/` – Compose screens.
- Seed assets (§10, do not rewrite): `app/src/main/assets/{extractor.js,Readability.js,stability-observer.js}`,
  fixtures in `app/src/androidTest/assets/fixtures/`, `tools/generate-extractor-fixtures.js`,
  `app/src/test/resources/expected-recipe/` (PoC merge output – starting point only), `recipe-photos/`, `poc-runs/`.

## Build & test
- `./gradlew :app:testDebugUnitTest` – JVM tests (contract, routing, merge, goldens, uploader).
  `-PupdateGolden` rewrites golden files; review the git diff.
- `cd tools && npm install && node generate-extractor-fixtures.js` – real extractor in jsdom over the fixtures →
  `app/src/test/resources/extractor-output/` (rerun after every extractor.js change).
- `./gradlew :app:connectedDebugAndroidTest` – instrumented tests (real WebView). **Uninstalls the app** –
  reinstall (`installDebug`) before manual tests.
- adb lives in `~/Library/Android/sdk/platform-tools/adb`; emulator AVD `Pixel_9` (Android 37).

## Gotchas
- `am start` with a share while the app is on screen is delivered to the running instance – force-stop first.
- adb shell cannot grant MediaStore read access: feed test files via `run-as … cp` into the app's files dir and
  share through the app's FileProvider. Long texts: push a file and use `--es android.intent.extra.TEXT "$(cat …)"`.
  `\n` inside an adb `--es` string arrives literally, not as a newline.
- The PoC used the same package name; `files/captures/` on the emulator may hold loose PoC test files (ignored).
- Page `lang` is not the content language (AI-chat share pages say `en` for Polish answers).

## State
- M1 skeleton: done (share target + routing, capture model + schema + contract tests, local sender, capture list).
