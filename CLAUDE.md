# RecipeDealer capture app

Android app (Kotlin + Jetpack Compose, minSdk 34) that receives shares (link, text, images, PDF), extracts the
recipe on the device and sends a **capture** (structured recipe + all raw material) to a backend over HTTPS / REST.
The full task description is `final-app.md` (sections referenced below as §N). Backend: `../RecipeDealerBE-PoC`
(separate repo). Shared rules (language, contract, deployment order, VPS, permission pitfalls): `../CLAUDE.md`.

## Rules
- Commit per milestone step; never commit secrets (backend token lives only in app settings).
- Keep the data model minimal; the contract `schema/capture-v1.json` follows the rules in `../CLAUDE.md`.
- Do not tune the heading heuristics to a single AI-chat answer; the backend handles the rest.
- When a decision or assumption in `final-app.md` turns out wrong: tell the user, update the docs.

## Decisions (§3, agreed with the user 2026-10-11)
D1 Kotlin + Compose · D2 Polish UI · D3 bearer token in settings (pluggable; since 2026-10-27 from login) · D4 one multipart PUT per capture ·
D5 manual entry form → `MANUAL` capture.

Deviation from §6.5 / §7.2 (told the user): a `screenshot` attachment (long screenshot: page URL shared along, or
height > 2.5 × width) keeps its width (≤ 1600 px) and full height (≤ 20 000 px) instead of a 1600 px long edge –
a 1080×11513 page scaled to 1600 px would be ~150 px wide and unreadable for vision.

Own assumption: captures are **not** sent automatically by default – the user reviews and taps "Wyślij" (preview
or notification action). Setting "Wysyłaj automatycznie" sends right after extraction.

## Layout
- `model/Capture.kt` – contract data classes (kotlinx.serialization; property order = JSON key order).
- `share/` – `ShareActivity` (translucent trampoline, copies shared files into app storage), `ShareRouter`.
- `capture/` – `CaptureService` (shortService FGS), `CaptureProcessor`, `CaptureBuilder`, extractors per source.
- `store/CaptureStore.kt` – file-based store: `files/captures/<id>/{capture.json,state.json,job.json,files/}`.
- `upload/` – `CaptureSender` (local / HTTP), upload runner.
- `api/` – `BackendClient` (everything after the upload: inbox, decisions, sync feed, recipes, revisions, search,
  blobs, edit lock) → `ApiResult`; `ApiModels.kt` response DTOs. Backend `recipe` objects stay `JsonObject`;
  `RecipeJson.merge` writes form edits over them so keys the app does not model survive a save.
- `inbox/Inbox.kt` – captures waiting for the user (`GET /v1/captures`, all phones of the household) and the
  NEW / UPDATE decisions; every CaptureView seen updates the local `state.json` (`backendStatus`, `candidates`, …).
- `recipes/` – `RecipeCache` (offline list, one JSON file `files/recipes/recipes.json` with the sync cursor and
  merge redirects), `RecipeSync` (`GET /v1/recipes` paging; no / malformed cursor → full sync that drops recipes
  the feed no longer lists), `SyncWorker` (hourly + "now"; also refreshes the inbox), `ImageCache` (blobs with the
  token, forever; `recipe.images[0]` URLs without the token).
- `ui/` – Compose screens; bottom tabs in `MainActivity` (Przepisy · Do decyzji · Udostępnienia), decision screen
  per capture, recipe list / detail from the cache.
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
- The PoC used the same package name: its leftover `shared_prefs/settings.xml` (e.g. `backgroundMode=false`) is
  read by this app. `adb shell pm clear com.gatar.recipedealer` (+ re-grant POST_NOTIFICATIONS) for a clean state.
- Pages with Trusted Types (Gemini) reject `innerHTML` and `DOMParser` – the extractor reports a readability error
  and scans the live body; that error in `diagnostics.errors` is expected.
- Loading the same Gemini share link many times in a row gets throttled (answer stops rendering) – not a bug.
- `WebView.enableSlowWholeDocumentDraw()` is called in `RecipeDealerApp.onCreate` (must precede any WebView).
- Chrome's link share (`text/plain`, URL in EXTRA_TEXT) carries a ~126×126 page-preview PNG in **ClipData** (no
  EXTRA_STREAM). It must not count as a shared file (it turned link shares into 0 % IMAGE captures):
  `ShareRouter.clipDataIsContent`. Shares via `adb am start` never have ClipData – test real-app shapes in
  `ShareFlowTest`.
- Page `lang` is not the content language (AI-chat share pages say `en` for Polish answers).

## Web capture (§6.3)
`web/WebCapture.kt` (`WebCaptureRunner`): off-screen WebView → 5 s DOM quiet or 15 s hard timeout → extractor →
Cloudflare wait (≤30 s, never OCR the challenge) → consent-wall check → late-content retries every 5 s until 30 s →
OCR fallback (whole-page snapshot, tiled ML Kit, OCR text through `loadText`, fills only missing parts, provenance
`ocr`). `ExtractorMerge` merges JSON-LD → microdata → plugins → heuristic with the group rule. `recipe.language`
comes from the content (`LanguageGuess`), page `lang` only breaks ties. `completeness.bestStage` = strongest stage
that gave ingredients or steps. `BrowserActivity` is the visible WebView (failure notification, background mode off).

## Text, images, PDF (§6.4–6.6)
- TEXT: `web/TextSourceExtractor` – `loadText` + `run()` in an off-screen WebView with an empty page (~0.5 s).
- IMAGE: `capture/FileExtractors.kt` – EXIF rotation, ML Kit lines (not blocks), tiles 4096/512 for tall images
  (`ocr/Tiling.kt`, a line belongs to the tile holding its vertical center), wide images subsampled to ≤ 3000 px for
  OCR, JPEG attachments. No on-device structuring (recipe stays empty; backend does it). OCR text of several
  images / pages is joined with `\f`; `lines[].page` is the image / page index.
- PDF: text layer (`PdfRenderer.Page.getTextContents()`, API 35+ or ext S ≥ 13) + OCR of each page at 1600 px on
  white, ≤ 10 pages, original attached as `document-N.pdf`.
- `am start` has no array-of-URI extra: SEND_MULTIPLE is covered by `ShareFlowTest` (instrumented).

## Backend decisions (RecipeDealerBE)
A processed capture ends in `needs_decision` when there is a real choice: the user picks, per extracted candidate,
"Nowy przepis" or "Aktualizuj: <match>" (`GET /v1/recipes/candidates`, URL matches preselected). Since backend
2026-10-27 a candidate that matches no recipe becomes a new recipe without asking (capture goes straight to `done`);
`StatusWorker` syncs on every `done`, so such a recipe shows up right away.
`StatusWorker` polls while `accepted` / `processing`, then notifies on `needs_decision` / `needs_attention`
(tap → decision screen). `recipe_gone` / `already_resolved` reload the capture. Server messages come in English.

## Recipes: sync, search, editing (RecipeDealerBE)
- The recipe list reads only `RecipeCache` (works offline). Search: `GET /v1/recipes/search`, offline / while
  loading `LocalSearch` (words in title or ingredients, diacritics ignored, no typo tolerance).
- Editing is online only: `RecipeEditSession` (GET head → POST lock → heartbeat every 2 min → POST revision with
  `Lock-Id` + `If-Match`, or DELETE recipe, or release). `RecipeEditViewModel.onCleared` releases the lock on back /
  cancel; a killed app lets the 5 min lease expire (until then the server reports the phone itself as the editor).
  412 / lost lock keep the form text. Applying a revision / machine proposal = editor prefilled with it.
- "Zatwierdzam bez zmian" (editor, `RecipeDraft.sameContentAs`) sends the normal save. Since backend 4857305
  (2026-10-25) content equal to a head that `needsReview` gives 201 with a new USER head without `needsReview`; equal
  content otherwise gives 200 (no revision). Older backends answered 200 and kept the flag.
- Tablet view (`ui/TabletRecipeView.kt`): a setting (`tabletView`, also the phone/tablet icon on the recipe detail),
  not chosen by screen size. White page, ecru rounded cards: title 1/3 + photo 2/3, then ingredients, steps, notes.
  Fixed light colors also in the dark theme. Samsung's eye comfort filter tints screenshots (white looks cream).
- ETag is built from `headRevisionId` (`etagOf`), never taken from the header: Caddy's `encode gzip` rewrites it to
  `"<id>-gzip"` and the backend then answers 412 to `If-Match`.

## Tags (agreed with the user 2026-10-25)
- Two levels `category:tag`; each level has a `name` (stable readable id, `[a-z0-9-]`, e.g. `meal:breakfast`) and
  a `title` (shown, "Posiłek" / "Śniadanie"). Order everywhere = the tag manager's order. No ingredient tags.
- Assigned manually only: recipe detail (label icon → `TagPickerDialog` → `PUT /v1/recipes/{id}/tags`, no edit lock,
  no revision) and with "Nowy przepis" on the decision screen (`Resolution.tags`). Not with UPDATE.
- `recipes/Tags.kt`: `TagCatalogStore` (`files/recipes/tags.json`, refreshed at the start of every `RecipeSync`; a 404
  = backend without tags = empty catalog), `TagFilter` (column content, AND across categories / OR inside one, slug).
  Tag changes against such a backend: a 404 without the backend's `not_found` code = missing route (`isMissingRoute`)
  → "Serwer nie obsługuje jeszcze tagów". `Resolution.tags` is omitted when empty (old body for old backends).
- Recipe list: tag column on the right (1/4 width) with only the tags some cached recipe carries, as centered pills
  (fixed muted sage colors, light / dark: tag lighter than its category, selected tag stronger); category pills fold,
  only the first two start unfolded; no counts shown; the label icon in the top bar hides it (`tagColumn` setting; a hidden column does not
  filter). `ui/TagScreens.kt`: column, chips, picker, `TagManagerScreen` (online; up/down arrows instead of drag;
  every change answers with the whole catalog, then a sync picks up renamed / moved / deleted tag ids).
- Entry to the tag manager: sliders icon (`Tune`) in the tag column header, and Settings.

## Waiting for backend changes (postponed, backend not to be touched for now – user, 2026-10-24)
- Photo from a file on the device: decided (a) – a backend upload endpoint (e.g. `POST /v1/blobs` → blob id the
  recipe then references). Rejected (b): a `data:` URI JPEG inside `recipe.images` (bloats every sync).
  Until then the photo can only be replaced by URL (editor field `imageUrl` = `images[0]`).

## Accounts (backend 2026-10-27, `docs/api.md` Accounts)
- `ui/AccountScreens.kt`: `LoginScreen` (login / "Załóż konto" with the household registration code; server URL,
  user, password, device name = `Settings.Global.DEVICE_NAME` or the model, editable and stored so a re-login keeps
  the server's device) and `PasswordScreen` (`PUT /v1/me/password`; current password optional for a device the admin
  enrolled). `AppContainer.signIn` / `signOut` (forgets the token locally only).
- The token is not shown anywhere (kept in `settings`). Settings → "Konto": logged in as / enrolled by the admin
  ("Ustaw hasło", no logout until the user can log in) / logged out.
- A dead token: every `ApiResult.Unauthorized` (BackendClient's `onUnauthorized`, upload, workers) →
  `UploadQueue.pauseForAuth` sets `authRejected` (+ one notification, tap → login). `AppSettings.needsLogin`
  (no token or rejected) opens the login screen; back = offline use of the cache.
- `BackendClient`: 401 / 403 / 429 with an endpoint-specific code (`invalid_credentials`, `registration_disabled`,
  `too_many_requests`, …) are `Failed`, not `Unauthorized` / `Retry`; Polish texts in `ApiTexts` without the
  server's English details.

## Preview, editing, manual entry (§6.7, D5)
- `capture/RecipeEdits.kt`: `RecipeDraft` (form strings ↔ Recipe, items one per line), `applyEdit` (changed fields →
  provenance `user`, raw untouched, completeness recomputed), `manualCapture` (`MANUAL`, provenance `manual`).
- Editing only while DRAFT / FAILED: a sent capture cannot change (idempotent PUT answers 200 and keeps the old one).
- Compose UI tests need espresso ≥ 3.7 / test runner ≥ 1.7 on Android 37 (older espresso calls the removed
  `InputManager.getInstance`).

## State
- M1 skeleton: done (share target + routing, capture model + schema + contract tests, local sender, capture list).
- M2 web capture: done. Verified on the emulator: kwestiasmaku (timeout trigger, heuristic groups), recipetineats
  (WPRM groups + JSON-LD), allrecipes, aniagotuje (microdata), ChatGPT / Gemini / Claude share links, 6 shares at
  once; visible WebView auto-extract. Claude "tort" share gives only assembly steps (known §9 case → backend).
  `ExtractorWebViewTest` (instrumented) matches all 15 goldens.
- Backend API (2026-10-22): client, decisions, recipe list / sync / detail, search, editing – done. Verified on the
  emulator against https://2.31.66.200: share → needs_decision notification → "Nowy przepis" → done → listed after
  sync; search with a typo; edit with lock + save + history; lock released on back. Not verified on the real server:
  recipe delete (verified by the user 2026-10-27: deleted on the tablet, gone on the emulator after sync).
  Since backend 2026-10-27 web recipes get `thumbnailBlob` too (server copy of `images[0]`, original bytes): the
  app shows the blob first, the `images[0]` URL only as a fallback (`recipeImage`); verified on the emulator.
- M3 text / images / PDF: done. Verified: Gemini cake text selection (0.46 s, same as golden), rotated typewritten
  photo (EXIF 90°), synthetic 1080×11513 screenshot (186/186 lines, no duplicates, in order, 1.2 s OCR), the three
  household PDFs (text layer + OCR, 0.4–0.6 s each).
