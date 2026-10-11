# RecipeDealer capture app – build prompt

> **For the human starting the new project**
> 1. Create an empty repository for the capture app.
> 2. Copy the **seed assets** listed in section 10 from the PoC repository into it (paths given there).
> 3. Start the coding agent in the new repository and give it this file as the task. Do not give it the PoC
>    repository, its `CLAUDE.md` or its `README.md` – everything worth knowing is distilled below.
>
> Everything below this box is addressed to the coding agent.

---

## 1. Your task

Build the **RecipeDealer capture app**: an Android app that receives whatever the user shares (a link, text,
images, a PDF), extracts the recipe on the device as far as it can, and sends a **capture** – the structured
recipe plus all raw material – to a backend over **HTTPS / REST**.

A proof of concept (PoC) explored exactly this app; its findings are written down here as requirements,
proven techniques and pitfalls. You build the real app **from scratch**; the only code you take over are the
seed assets in section 10.

**Scope:** the Android capture app only. The backend and a kitchen-tablet app will exist later but are **not**
part of this task and are not designed yet – do not build them, do not design their internals. What is fixed is
the **data contract** (section 7) and an **example REST API** (section 8) that the app talks to. Until a backend
exists, the app must work against a mock server and a local "save / export JSON" sender.

Work in this order: read the whole prompt → resolve the open decisions in section 3 with the user → propose a
short plan (milestones from section 12) → build milestone by milestone, each one runnable and tested.

## 2. Context and constraints

- **Private use only.** One household, a few Android phones on **Android 14+**. No store release, no onboarding
  for strangers, no older Android versions, no OEM battery-killer workarounds. A wider release may be considered
  only after it proves itself at home – do not design for it now, just do not make it impossible.
- **The user (developer) speaks Polish; recipes are mostly Polish, some English.** Code, identifiers, comments,
  docs and commit messages are in English.
- **Capture time is not critical.** A recipe is shared minutes before cooking. Waiting 20–30 s for a complete
  result is fine; prefer completeness over speed.
- **Do as much as possible on the device.** The backend should stay thin; everything the phone can do well
  (web extraction, OCR, PDF text, text structuring) happens on the phone. What the phone cannot do (vision on
  handwriting, splitting several recipes, final structuring of messy text) is left to the backend – so the
  phone must always send the raw material it worked from.
- **Keep the data model minimal.** Add fields only when a real need shows up (tags were left out on purpose).

## 3. Decisions to make with the user before coding

Ask these up front (one short message, recommendation first). Do not silently pick.

| # | Decision | Recommendation |
|---|---|---|
| D1 | Language / UI toolkit | Kotlin + Jetpack Compose (the PoC was Java only for speed) |
| D2 | App UI language | Polish |
| D3 | Backend authentication in the app | A bearer token entered once in the app settings (per household or per person) – the backend does not exist yet, so keep it pluggable |
| D4 | Attachments transport | One multipart request per capture (section 8), as in the example API |
| D5 | Manual recipe entry in this app | Yes: a form that produces a `MANUAL` capture (handwritten family recipes will be typed in) |

## 4. App overview

```
share ─► route ─► on-device extraction ─► preview / notification ─► capture (JSON + files) ─► HTTPS REST ─► backend
          WEB / TEXT / IMAGE / PDF          (WebView, ML Kit, PDF text)                     offline queue, retries
```

- Every share produces exactly one capture with a client-generated id; captures are queued locally and uploaded
  when online (idempotent upload, section 8).
- The user sees the result (notification, preview) and can correct the recipe before it is sent; the raw
  material is sent unchanged.

## 5. Recipe DTO (our own – not schema.org)

| Field | Content |
|---|---|
| `title` | string |
| `language` | primary language subtag (`pl`, `en`) or absent; see the pitfall about page `lang` in section 11 |
| `servings` | `{text, count}` – `text` as written by the source, `count` only when the text is clearly a number of servings |
| `times` | `{prepMinutes, cookMinutes, totalMinutes}` as integers; **never computed from each other** (sources are inconsistent) |
| `ingredientGroups` | `[{name, items}]`; `items` are **plain text lines** (no quantity / unit split); `name` is the sub-heading or absent |
| `stepGroups` | `[{name, items}]` – same shape |
| `notes` | tips the source keeps apart ("Wskazówki", "Notes", plugin notes) |
| `images` | distinct image URLs (dedupe ignoring the query string) |

## 6. Capture techniques (proven in the PoC)

### 6.1 Share entry and routing

- Share target for `ACTION_SEND` / `ACTION_SEND_MULTIPLE` with `text/plain`, `image/*`, `application/pdf`.
- Route: text that is, after trim, a single http(s) URL → **WEB**; other text → **TEXT** (embedded URLs kept);
  images → **IMAGE**; PDF → **PDF**. Manual entry → **MANUAL**.
- Record what arrived (action, MIME type, extras summary, referrer package) in the capture.
- What real apps send (measured):
  - Chrome / Brave / Opera: the bare URL in `EXTRA_TEXT` (+ title in `EXTRA_SUBJECT` / `EXTRA_TITLE`); Opera sends
    no referrer.
  - Chrome / Brave long screenshot: `image/png` via their FileProvider **plus the page URL in `EXTRA_TEXT`** →
    keep it as `source.url`.
  - ChatGPT, Gemini, Claude share buttons: a **public link** to the conversation / message (`chatgpt.com/s/…`,
    `share.gemini.google/…` redirecting to `gemini.google.com/share/…`, `claude.ai/share/…` – Claude needs "Anyone
    with the link"). They open without login → WEB route.
  - Text selection → Share (any app): plain text, **flattened** (no markdown, no bullets, headings as plain
    lines), often no referrer and no title → TEXT route.

### 6.2 Background capture (default UX)

- Share → a translucent trampoline activity starts a `shortService` foreground service and finishes at once; the
  user stays in the source app and sees a toast.
- The service runs an **off-screen WebView** (no window, sized with `measure` / `layout` to the screen size). It
  reports `visibilityState = "visible"` and gave identical results to a visible WebView on all tested pages, with
  several shares at once and with the screen off.
- Result as a notification: "Saved · <title>" → opens the preview; failure → tapping it opens the **visible**
  WebView for the same URL (interactive bot check, consent wall, page that needs interaction) with an "Extract
  now" action.
- When web extraction fails, ask the user (notification text) for a **long screenshot** of the page, or a few
  regular screenshots (Chrome's long-screenshot sometimes does not start).

### 6.3 Web pages

1. **Load and stabilize.** Inject a `MutationObserver`; extract after **5 s of DOM quiet**, hard timeout **15 s**
   (ad-heavy pages never go quiet – extracting on the timeout works).
2. **Late content.** If an automatic extraction has no ingredients or no steps, extract again every 5 s up to
   30 s after the load, then fall back to OCR.
3. **Cloudflare "Just a moment…".** The WebView passes it by itself in 2–7 s. Detect it (`window._cf_chl_opt` or
   the title), discard that result, wait for the real page (max 30 s); never OCR the challenge page.
4. **Consent walls.** Most cookie dialogs do not block DOM extraction. Google's "Before you continue to Google"
   (Gemini share pages, EU) blocks the content completely; once the user answers it in the visible WebView the
   cookie persists for later background captures. The visible WebView must **not** auto-extract while such a
   dialog is shown.
5. **Run the seed `extractor.js`** (section 10) – all stages always, merged field by field with provenance:
   JSON-LD (`@graph`, arrays, `@type` arrays, `{"@id"}` references resolved against all nodes – Yoast writes
   author / image that way) → microdata (some sites have the Recipe only there) → plugin selectors (WP Recipe
   Maker incl. ingredient / instruction groups and notes, Tasty, Mediavine) → heading heuristic (Readability +
   PL/EN section keywords, sub-groups, checklist / step-title clean-ups, title from the nearest higher-ranked
   heading before the ingredients). Merge in that priority. Group rule: a flat winning list is replaced by a lower
   stage's named groups with the same item count (JSON-LD ingredients are always flat, WPRM markup has groups).
   Clean-ups: `▢` marks, " ," → ",", trailing colons in group names, image URLs deduplicated ignoring the query.
6. **OCR fallback** when ingredients or steps are still missing: whole-page snapshot of the WebView
   (`WebView.enableSlowWholeDocumentDraw()`) → ML Kit, tiled like tall images (6.5).
7. Keep the page's readable text (`readableText`) in the capture whatever the result.

### 6.4 Text

- Clean the text (zero-width characters incl. a leading U+200B, NBSP, CRLF, runs of blank lines).
- Structure it with the seed `RecipeExtractor.loadText(text)` (short section-keyword lines → h2, numbered short
  lines not followed by another numbered line → h2, labels ending with ":" or "Krok N" → h3, first title-like line
  → h1, other lines → list items) followed by the normal run, in an off-screen WebView with an empty page
  (~0.5 s). Readability is skipped for text.
- Keep the cleaned text as `raw.text`.

### 6.5 Images

- `SEND` and `SEND_MULTIPLE`; apply EXIF orientation.
- ML Kit text recognition (Latin). Use **lines, not blocks** (ML Kit can merge a whole tile into one block); sort
  top-to-bottom, then left-to-right.
- **Tall images** (long screenshots, e.g. 1080×11513): decode in tiles of 4096 px with 512 px overlap
  (`BitmapRegionDecoder`); a line belongs to the tile that holds its vertical center – nothing lost, nothing
  duplicated. Cap the number of tiles.
- Always attach a **downscaled JPEG (long edge 1600 px)** of each image – the backend's vision step needs it
  (1600 px proved enough for handwriting, see 9).
- Measured on real household photos: clean print very good (typical error "ł" read as "t"); photos of a monitor
  good (missing spaces); stained typewriting readable with many errors; **handwriting fails**; **two-column pages
  get interleaved** (ML Kit merges lines across columns – cannot be fixed on the device). On-device structuring of
  OCR text is not required; the backend does it.
- The user crops photos to one recipe before sharing. Optional later: an in-app "photograph a recipe" action with
  the ML Kit Document Scanner (ready-made UI with edge detection and perspective fix) – no own auto-cropping.

### 6.6 PDF

- Per page: the PDF **text layer** via `PdfRenderer.Page.getTextContents()` (Android 15+, or SDK extension S ≥ 13
  on Android 14 – check `SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 13`) **and** ML Kit OCR of the
  page rendered at 1600 px width on a white background. Cap the page count (10).
- Text layer: exact characters (Polish letters, ½ ¾). Reading order is fine for Google Docs exports, but for a
  Chrome "Save as PDF" of a web page it comes in content-stream order (headings first, labels away from values) –
  one blob per page without bounds, not fixable on the device. OCR keeps the visual order. **Send both**, plus the
  original PDF. ~1–1.5 s per file.
- A PDF can hold several recipes – the backend splits them.

### 6.7 Screens

- Capture preview: the extracted recipe (editable), what was used (source, completeness), send status.
- Capture list: the user's captures with upload status (queued, sent, failed – retry).
- Manual entry (decision D5): a form for title, servings, times, ingredient / step groups, notes → `MANUAL`
  capture.
- Settings: backend base URL, token (D3), background mode on/off.

## 7. Data contract

### 7.1 Capture (JSON, UTF-8)

Every capture is self-contained: the backend must be able to (re)process it later from what it contains.

| Field | Type | Content |
|---|---|---|
| `schemaVersion` | int | `1` for this contract |
| `captureId` | string (UUID) | generated on the phone; the idempotency key |
| `capturedAt` | string (RFC 3339, UTC) | when the share happened |
| `client` | object | `{appVersion, androidSdk, deviceModel}` |
| `share` | object | `{action, mimeType, sourceApp, subject, title}` – what arrived (nulls allowed) |
| `source` | object | `{type: WEB/TEXT/IMAGE/PDF/MANUAL, url, siteName, author, files}` – `url`: canonical → final → shared; `files`: display names of shared files |
| `recipe` | object | the Recipe DTO (section 5); may be partial or empty (IMAGE / PDF) |
| `completeness` | object | `{score 0..1, hasTitle, hasIngredients, hasSteps, bestStage}` |
| `provenance` | object | recipe field → stage: `jsonLd`, `microdata`, `pluginHits`, `heuristic`, `ocr`, `page`, `manual`, `user` (edited in the preview) |
| `raw` | object | raw material, see 7.2 |
| `attachments` | array | `[{name, role, mimeType, width, height, bytes}]` – metadata; the files travel as multipart parts with the same `name` |
| `diagnostics` | object | `{timingsMs: {stage: ms}, errors: [string], notes: [string]}` – free-form, for debugging only |

### 7.2 Raw material (`raw`)

| Field | When | Content |
|---|---|---|
| `text` | TEXT | the cleaned shared text |
| `web` | WEB | `{finalUrl, canonicalUrl, title, lang, siteName, ogImage, jsonLdRecipeId, botChallenge, stabilized, renderTimeMs, readableText, extractor}` – `extractor` is the seed extractor's JSON output as is (JSON-LD, microdata, plugin hits, heuristic result) |
| `pdfText` | PDF | text layer, pages separated by a form feed (`\f`) |
| `ocr` | IMAGE, PDF, WEB fallback | `{source: IMAGE/PDF_RENDER/WEBVIEW_SNAPSHOT, text, lines: [{text, page, bbox: [l, t, r, b]}], truncated}` |

Attachment roles: `image` (downscaled shared image, JPEG, long edge 1600 px), `pdf` (the original PDF),
`screenshot` (a long screenshot when it is the source).

### 7.3 JSON Schema (normative for the fields the backend relies on)

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "https://recipedealer.example/schemas/capture-v1.json",
  "type": "object",
  "required": ["schemaVersion", "captureId", "capturedAt", "source", "recipe", "raw", "attachments"],
  "properties": {
    "schemaVersion": {"const": 1},
    "captureId": {"type": "string", "format": "uuid"},
    "capturedAt": {"type": "string", "format": "date-time"},
    "client": {"type": "object"},
    "share": {"type": "object"},
    "source": {
      "type": "object",
      "required": ["type"],
      "properties": {
        "type": {"enum": ["WEB", "TEXT", "IMAGE", "PDF", "MANUAL"]},
        "url": {"type": ["string", "null"]},
        "siteName": {"type": ["string", "null"]},
        "author": {"type": ["string", "null"]},
        "files": {"type": "array", "items": {"type": "string"}}
      }
    },
    "recipe": {"$ref": "#/$defs/recipe"},
    "completeness": {"type": "object"},
    "provenance": {"type": "object", "additionalProperties": {"type": "string"}},
    "raw": {"type": "object"},
    "attachments": {
      "type": "array",
      "items": {
        "type": "object",
        "required": ["name", "role", "mimeType"],
        "properties": {
          "name": {"type": "string"},
          "role": {"enum": ["image", "pdf", "screenshot"]},
          "mimeType": {"type": "string"},
          "width": {"type": "integer"},
          "height": {"type": "integer"},
          "bytes": {"type": "integer"}
        }
      }
    },
    "diagnostics": {"type": "object"}
  },
  "$defs": {
    "group": {
      "type": "object",
      "required": ["items"],
      "properties": {
        "name": {"type": ["string", "null"]},
        "items": {"type": "array", "items": {"type": "string"}}
      }
    },
    "recipe": {
      "type": "object",
      "properties": {
        "title": {"type": ["string", "null"]},
        "language": {"type": ["string", "null"]},
        "servings": {
          "type": ["object", "null"],
          "properties": {"text": {"type": "string"}, "count": {"type": ["integer", "null"]}}
        },
        "times": {
          "type": "object",
          "properties": {
            "prepMinutes": {"type": ["integer", "null"]},
            "cookMinutes": {"type": ["integer", "null"]},
            "totalMinutes": {"type": ["integer", "null"]}
          }
        },
        "ingredientGroups": {"type": "array", "items": {"$ref": "#/$defs/group"}},
        "stepGroups": {"type": "array", "items": {"$ref": "#/$defs/group"}},
        "notes": {"type": "array", "items": {"type": "string"}},
        "images": {"type": "array", "items": {"type": "string"}}
      }
    }
  }
}
```

Ship this schema in the repo, validate every outgoing capture against it in tests, and keep it versioned
(`schemaVersion`); an incompatible change means a new version, not an edit.

### 7.4 Example capture (WEB, shortened)

```json
{
  "schemaVersion": 1,
  "captureId": "3f6c1a52-8a0e-4c5e-9a43-2a1f0d6b7c11",
  "capturedAt": "2026-10-09T17:42:05Z",
  "client": {"appVersion": "1.0.0", "androidSdk": 36, "deviceModel": "Pixel 9"},
  "share": {"action": "android.intent.action.SEND", "mimeType": "text/plain", "sourceApp": "com.android.chrome",
            "subject": "Szarlotka – Kwestia Smaku", "title": null},
  "source": {"type": "WEB", "url": "https://www.kwestiasmaku.com/przepis/szarlotka", "siteName": "Kwestia Smaku",
             "author": null, "files": []},
  "recipe": {
    "title": "Szarlotka",
    "language": "pl",
    "servings": {"text": "12 porcji", "count": 12},
    "times": {},
    "ingredientGroups": [
      {"name": "Jabłka", "items": ["1,5 kg jabłek", "5 łyżek cukru", "1/2 łyżeczki cynamonu"]},
      {"name": "Ciasto", "items": ["320 g mąki (2 szklanki)", "250 g zimnego masła*", "1 jajko"]}
    ],
    "stepGroups": [
      {"name": "Jabłka", "items": ["Jabłka obrać, pokroić na ćwiartki i wyciąć gniazda nasienne."]},
      {"name": "Pieczenie", "items": ["Piekarnik nagrzać do 180 stopni C.", "Piec przez ok. 50 minut."]}
    ],
    "notes": ["* masło można zastąpić margaryną."],
    "images": ["https://www.kwestiasmaku.com/sites/v123.kwestiasmaku.com/files/szarlotka-00_2.jpg"]
  },
  "completeness": {"score": 1.0, "hasTitle": true, "hasIngredients": true, "hasSteps": true,
                   "bestStage": "heuristic"},
  "provenance": {"title": "heuristic", "servings": "heuristic", "ingredients": "heuristic", "steps": "heuristic",
                 "notes": "heuristic", "images": "page"},
  "raw": {
    "web": {"finalUrl": "https://www.kwestiasmaku.com/przepis/szarlotka",
            "canonicalUrl": "https://www.kwestiasmaku.com/przepis/szarlotka",
            "title": "Szarlotka – Kwestia Smaku", "lang": "pl", "siteName": "Kwestia Smaku",
            "ogImage": "https://www.kwestiasmaku.com/…/szarlotka-00_2.jpg", "jsonLdRecipeId": null,
            "botChallenge": null, "stabilized": false, "renderTimeMs": 15000,
            "readableText": "Szarlotka … Składniki … Przygotowanie …", "extractor": {"…": "seed extractor output"}}
  },
  "attachments": [],
  "diagnostics": {"timingsMs": {"stabilize": 15000, "merge": 2}, "errors": [], "notes": ["trigger=timeout"]}
}
```

## 8. Example REST API (HTTPS)

The backend does not exist yet; this is the API the app is written against. Keep the HTTP client behind an
interface so the real API can differ in details. All requests over HTTPS, JSON in UTF-8,
`Authorization: Bearer <token>` (D3).

### `PUT /v1/captures/{captureId}` – upload a capture (idempotent)

`multipart/form-data` with:
- part `capture`: `application/json`, the capture (section 7);
- one part per entry in `attachments`, part name = `attachments[].name`, with its `mimeType`.

Responses:
- `201 Created` – stored; `{"captureId": "…", "status": "accepted"}`
- `200 OK` – a capture with this id already exists (retry after a lost response); same body
- `400 Bad Request` – schema validation failed; `{"error": "invalid_capture", "details": ["…"]}` → do not retry,
  keep the capture as failed and show it
- `401 Unauthorized` / `403 Forbidden` → stop the queue, ask for the token
- `413 Payload Too Large` → keep failed, report (attachments are capped on the device)
- `429`, `5xx`, network errors → retry with exponential backoff (WorkManager), same `captureId`

### `GET /v1/captures/{captureId}` – processing status (optional)

`{"captureId": "…", "status": "accepted | processing | done | needs_attention", "recipes": [{"recipeId": "…",
"title": "…"}], "message": null}` – lets the app show the backend's final title or a "needs your help" note. The
app must work without it.

### `GET /v1/health`

`200 {"status": "ok"}` – used by the settings screen to test the URL and token.

Until a backend exists: a local sender (save the capture + files to app storage, "Share JSON" / export) and a
mock server in tests (e.g. OkHttp `MockWebServer`) covering every response above.

## 9. What the backend will need (informative – not your task)

So the contract stays sufficient later:
- **Recipe identity / de-duplication**: two people sharing the same recipe, or a re-share after the page
  changed, must end up as one recipe (a new version). The backend keys web captures on the canonical URL
  (normalized: scheme, `www.`, trailing slash, `utm_*` and similar, AMP variants) and on `raw.web.jsonLdRecipeId`;
  image shares carrying a page URL (long screenshot) use that URL; TEXT / IMAGE / PDF without a URL are matched
  on content and confirmed by the user. `captureId` is per capture; `capturedAt` orders versions.
- **LLM / vision step**: it will run on IMAGE / PDF captures and on captures where ingredients or steps come only
  from the heading heuristic or OCR, using `raw` and the attachments. Completeness is not correctness – a partial
  heuristic result (only the assembly steps of a cake) scored 0.6 and looked like a success – so the raw text must
  always be there.
- **Several recipes per capture** (a magazine page with two columns, a typewritten sheet with 5 recipes, a PDF
  with two recipes, an AI chat with a revised version) – the backend splits them; the app sends one capture.
- Evidence for vision (in-session check on the 1600 px attachments): handwriting fully readable where ML Kit
  returned garbage; the two-column page split into its two recipes; the typewritten sheet read completely; only
  handwritten margin edits stayed uncertain.

## 10. Seed assets (copied from the PoC – take over, do not rewrite)

Copy these from the PoC repository (`/Users/bartlomiejgatarski/Projekty/RecipeDealerPoC`) into the new project:

| PoC path | What it is | How to use it |
|---|---|---|
| `app/src/main/assets/extractor.js` | The page / text extractor: JSON-LD, microdata, plugin selectors, heading heuristic, `loadText`, bot-challenge detection. Returns one JSON object (`jsonLd`, `microdata`, `pluginHits`, `heuristic`, `readableText`, page metadata, timings, errors) | Inject into the WebView as is (after Readability); the merge into the DTO is yours to write. Keep its output contract; when you change it, regenerate the fixture outputs |
| `app/src/main/assets/Readability.js` | Mozilla Readability | Injected before `extractor.js` |
| `app/src/main/assets/stability-observer.js` | MutationObserver quiet-window reporter | Reference for 6.3 step 1 |
| `app/src/androidTest/assets/fixtures/*.html`, `*.txt` | 15 fixtures modeled on real pages and shares (Polish recipe sites, WPRM + Yoast, microdata-only, Cloudflare page, ChatGPT / Claude share pages, multi-stage cake, text selection) | Regression set for the extractor |
| `tools/generate-extractor-fixtures.js` (+ `tools/package.json`) | Runs the real extractor in jsdom over the fixtures and writes their JSON outputs | Keep the "real JS in jsdom → unit tests" chain (section 11) |
| `app/src/test/resources/expected-recipe/*.json` | Expected recipe per fixture (from the PoC merge) | Starting point for your golden tests; review differences, do not blindly match |
| `recipe-photos/` | Real household photos (print, clippings, monitor photos, typewriting with 5 recipes, a two-column page, handwriting) and 3 PDFs | Test set for OCR, tiling and PDF |
| `poc-runs/2026-10-*.json` (a few) | Real PoC payloads (older envelope, `schemaVersion` 2 of the PoC – not the contract above) | Examples of raw material per source type |

Do **not** copy the PoC Java code, `CLAUDE.md` or `README.md`.

## 11. Testing strategy and pitfalls

What paid off in the PoC:
- HTML fixtures modeled on real pages (inspect the live DOM first – see below), the **real extractor in jsdom**
  producing JSON that unit tests consume, **golden tests** of the whole resulting recipe per fixture (with an
  explicit "update golden" switch and a reviewed diff), and **re-capturing live pages** and diffing against
  earlier captures after every extractor change.
- Contract tests: every capture the app builds validates against the JSON Schema; the uploader is tested against
  a mock server for every response in section 8.
- Inspecting a live page inside the app's WebView: debug builds allow WebView remote debugging –
  `adb forward tcp:9222 localabstract:webview_devtools_remote_<pid>` and the Chrome DevTools protocol; this is how
  the AI-chat DOM structures were found.

Pitfalls:
- `./gradlew connectedAndroidTest` uninstalls the app – reinstall before manual tests.
- If the app is on screen, `am start` with a share is delivered to the running instance and ignored – force-stop
  first.
- adb shell cannot grant read access to MediaStore images – feed test images / PDFs through the app's own
  FileProvider (`run-as … cp` into the app's files dir, share the `content://` URI). Long texts via adb: push a file
  and use `adb shell 'am start … --es android.intent.extra.TEXT "$(cat /data/local/tmp/t.txt)" …'`.
- Chrome's Long Screenshot editor cannot be driven through adb – ask the user.
- Pages with **Trusted Types** (Google, e.g. Gemini) reject `innerHTML` **and** `DOMParser.parseFromString`; any
  code that parses HTML strings needs a fallback (the extractor scans the live body instead).
- Readability drops sections it considers unimportant (a short "Notes" section, headings of tiny documents) –
  look up missing parts on the whole body.
- AI chat answers are **not deterministic**: the same app answers the same kind of question in different
  structures (numbered H2 sections with H3 components; step-title paragraphs; a widget with one `<div>` per step;
  plain markdown with no section headings at all). The heuristics cover the common shapes; the rest is for the
  backend – never tune the heuristics to a single answer.
- Page `lang` is not the content language (all AI-chat share pages say `en` for Polish answers).
- Loading the same Gemini share link dozens of times in a row makes it stop rendering the answer for a while
  (throttling) – don't misread that as a WebView bug.
- If you serialize with reflection on Android, fields come back alphabetically – fix the key order explicitly or
  use generated serializers (kotlinx.serialization).

Known gaps (accepted for now): cake "Dekoracja" / "Wykończenie" and "Z czym podawać?" sections are not captured as
steps / notes; heuristic notes can run into reader reviews (allrecipes); a first ingredient may carry an inline
category label ("Mięso: 400 g …").

## 12. Milestones

1. **Skeleton**: project per D1–D2, share target and routing, capture model + JSON Schema + contract tests, local
   sender, capture list.
2. **Web capture**: background service, off-screen WebView, stabilization, Cloudflare / consent handling, seed
   extractor, merge, OCR fallback, notifications, visible WebView on failure. Fixtures + golden tests.
3. **Text, images, PDF**: `loadText`, OCR with tiling, attachments, PDF text layer + OCR.
4. **Upload**: settings (URL, token), multipart upload, offline queue with WorkManager, idempotent retries,
   mock-server tests, optional status polling.
5. **Preview, editing, manual entry** (D5).

Each milestone ends runnable on the emulator / a phone, with tests passing and a short note of what was verified.

## 13. Working agreements for the new repository

- Create a `CLAUDE.md` in the new repository early and keep it current: project rules, current state, gotchas
  (copy the relevant pitfalls from section 11 there as you hit them).
- Conversation with the user in Polish; everything in the repository in English.
- Commit per milestone step with clear messages; never commit secrets.
- When a decision from section 3 or an assumption in this prompt turns out wrong, tell the user and update the
  docs instead of working around it silently.
