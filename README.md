# PriceFighter

An Android app that adds a **“price check” skill to Gemini**. Ask Gemini to price
check an item (by voice, text, or a photo) and it calls into PriceFighter, which
scrapes eBay’s public **sold** and **active** search pages and returns a report:
price range, average, median, 30-day sell-through velocity, current active-listing
count, lowest current price, and a deeplink to the sold results.

It also ships a fully **in-app on-device agent** so you don't need Gemini at all: on the
History (landing) tab, speak or type a request and **Gemini Nano** understands it, drives
the same eBay lookup, discards poor matches, and saves a report — all on-device, no cloud,
and it degrades to offline heuristics where Nano isn't available. (The Gemini-callable app
functions are kept in place for when that integration opens up more widely.)
**All data stays on the device.**

---

## How it works

PriceFighter exposes its capability to Gemini using the **Android App Functions**
framework (`androidx.appfunctions`) — the current, agent-oriented way to make app
capabilities callable by Gemini (it supersedes the older App Actions / Built-In
Intents approach). Functions are annotated Kotlin methods; the OS indexes them and an
agent like Gemini discovers and invokes them, passing structured arguments and reading
structured results.

The skill is intentionally **agentic** — it’s a small toolbox Gemini drives, exactly
as described in the task:

| Tool (`@AppFunction`) | What Gemini uses it for |
|---|---|
| `searchSoldListings(searchTerm, page)` | Fetch one page of **sold** listings (title, price, sold date, URL). Gemini reads the titles and keeps only the ones that genuinely match the item/model. Can be called for multiple pages. |
| `searchActiveListings(searchTerm)` | Fetch **active** listings sorted lowest-price-first → total active count + lowest current price. |
| `buildPriceReport(searchTerm, soldListings, activeListings, lowestActivePrice)` | Turn the **filtered** sold listings + active figures into the final report (range, average, median, velocity, deeplink) and **save it to local history**. |
| `priceCheck(item, model)` | One-shot convenience that runs the whole flow in a single call (fetch sold + active, filter by token overlap, build & save). |

A typical Gemini run:

1. User says/types/shows an item. Gemini resolves it to **item + model** (a photo is
   turned into a model number by Gemini’s own multimodal reasoning — the app doesn’t
   do image recognition).
2. Gemini calls `searchSoldListings` for one or more pages and **filters** the
   listings to true matches.
3. Gemini calls `searchActiveListings` once for the live count and lowest price.
4. Gemini calls `buildPriceReport` with the matched sold listings.
5. Gemini presents the returned report; it also appears in PriceFighter’s history.

The fetch + HTML parsing happens off the main thread (`Dispatchers.IO`), i.e. in the
background, headlessly — no eBay account, no API key. It loads the same HTML a browser
would and parses it with Jsoup. The HTTP transport is pluggable via a `PageFetcher`:

- **On-device the app uses `CronetPageFetcher`** — Cronet is Chromium's network stack, so
  requests carry **Chrome's TLS/HTTP-2 fingerprint**. eBay's anti-bot (Akamai) 403s plain
  OkHttp on Android but lets Chrome-fingerprinted traffic through (verified: Chrome loads
  the page on the same emulator that 403s OkHttp).
- It also **warms a session** first — loads the homepage to pick up eBay's cookies, then
  replays them (plus a `Referer`) on the search — because eBay 403s a cold request even
  from a browser fingerprint. Chrome fingerprint **+** session cookies = 200.
- `OkHttpPageFetcher` is the other implementation, used by the JVM live test (the host's
  fingerprint isn't blocked) and for non-network use.

The parser targets eBay's current `s-card` SRP markup (with `s-item` fallbacks). This
pipeline is validated against **live eBay** both from the host (`EbayLiveIntegrationTest`)
and **on the emulator end-to-end** — `priceCheck` via Cronet returns a real report (see
"Auto-test").

### eBay URLs used

- **Sold/completed:** `https://www.ebay.com/sch/i.html?_nkw=<term>&LH_Sold=1&LH_Complete=1&LH_ItemCondition=1000|1500|1750|2000|2010|2020|2030|2500|3000&_sop=13&_ipg=60&_pgn=<page>`
- **Active (lowest first):** same without `LH_Sold/LH_Complete`, with `_sop=15`

Every search carries the **item-condition filter** `LH_ItemCondition` set to all sellable
conditions — New, Open Box, New-other, every refurbished grade, and Used — which excludes
the **"For parts or not working" (7000)** condition, so parts-only listings never enter the
sample. (This filters by eBay *condition*. Cheap *accessories* — ear pads, cables, cases —
that match the search term but are complete items are a separate relevance concern, handled
by the agent's title matching in the multi-tool flow.)

**Sold sort & the 30-day window.** Sold searches are sorted by **sold/ended date, most
recent first** (`_sop=13`), so the recent window sits at the top of page 1 and continues
across eBay pages. The default sample is the **last 30 days of sales**: `SoldWindow.collect`
pages through eBay pages (at 240 items/page, so a few requests cover the window), keeping
each page's in-window sales and stopping once a page is **majority-older** than the cutoff.

The majority rule matters: eBay injects sponsored/promoted listings with arbitrary (often
old) sold dates onto every page, so stopping at the *first* old date would halt paging on
page 1. Velocity (number sold in 30 days) is the size of the assembled window; a 5×240
safety cap bounds the work, so an item selling faster than ~1,200/30 days yields a lower
bound. The agentic flow can still page explicitly via `searchSoldListings(term, page)`
(which returns 60/page to keep agent payloads small).

---

## Project layout

```
app/src/main/java/com/pricefighter/
├─ PriceFighterApp.kt           Application + AppFunctionConfiguration.Provider
├─ ServiceLocator.kt            Process-wide handle to the repository (functions are no-arg)
├─ appfunctions/
│  └─ PriceCheckFunctions.kt    The Gemini-callable @AppFunctions (the "skill")
├─ data/
│  ├─ model/Models.kt           @AppFunctionSerializable DTOs (agent contract)
│  ├─ agent/PriceCheckAgent.kt  On-device agent: understand → look up → filter → report
│  ├─ agent/NanoText.kt         Text-only Gemini Nano wrapper (fails soft to null)
│  ├─ ebay/EbayUrls.kt          Builds the public search URLs
│  ├─ ebay/EbayParser.kt        Jsoup HTML → listings (defensive, with selector fallbacks)
│  ├─ ebay/EbayClient.kt        Fetch (via a PageFetcher) → parsed results
│  ├─ ebay/PageFetcher.kt       PageFetcher interface + OkHttpPageFetcher (JVM/tests)
│  ├─ ebay/CronetPageFetcher.kt On-device fetch via Cronet (Chrome fingerprint) + cookies
│  ├─ ebay/SoldWindow.kt        Pages the last-30-days sold window (majority-older stop)
│  ├─ ebay/MatchHeuristics.kt   Offline token-overlap match filter (agent's Nano fallback)
│  ├─ vision/ProductIdentifier.kt  On-device barcode → OCR → Gemini Nano identification
│  ├─ stats/PriceStats.kt       Range / average / median / velocity
│  ├─ db/History.kt             Room entity + DAO + database (local-only)
│  └─ repo/PriceCheckRepository.kt  Single source of truth (UI + functions)
└─ ui/                          Jetpack Compose UI
   ├─ MainScreen.kt             Bottom tab bar: History / How to / Camera
   ├─ MainViewModel.kt          History stream + on-device agent state machine
   ├─ HistoryTab.kt             Voice/text prompt bar + accordion history (one card open)
   ├─ HowToScreen.kt            Usage directions (voice / text / photo)
   ├─ CameraScreen.kt           CameraX capture, Single/Continuous modes, results UI
   └─ CameraViewModel.kt        Single-shot state machine + continuous-session results
app/src/test/java/com/pricefighter/   JVM unit tests (parser + stats + URL + window)
```

## App UI

Three tabs (bottom navigation):

- **History** (default) — a **voice/text prompt bar** on top of past price checks. Speak
  (mic → system speech recognizer) or type a request and the **on-device agent** runs it,
  reporting progress inline and dropping the saved report at the top of the list. **One card
  is expanded at a time**: the most recent is open by default with full details; the rest
  collapse to a one-line summary (item + price range). Tapping a card opens it and closes the
  previously open one.
- **How to** — directions for asking Gemini by voice, text, or photo.
- **Camera** — a live CameraX preview with a shutter. Snapping a photo identifies the item
  **on-device** and prices it without leaving the app (a loading indicator runs meanwhile).
  The identifier is a fall-through chain:
  1. **Barcode** (ML Kit) → a UPC/EAN, which eBay can search directly.
  2. **Label OCR** (ML Kit) → a model-number token read off the item.
  3. **Gemini Nano** (ML Kit GenAI Prompt API) → on-device multimodal identification on
     supported devices. If the model isn't downloaded yet (`checkStatus() == DOWNLOADABLE`),
     the app starts the **one-time model download** (kicked off proactively when the camera
     opens) and uses Nano once it's `AVAILABLE`; until then it falls through. On a Galaxy
     Z Fold 7 the download is ~12 MB / a couple seconds (the base model ships with AICore).
  4. **Fallback** — if nothing on-device can identify it, hand the photo to the **Gemini app**
     (`ACTION_SEND` image + prompt, falling back to the system chooser).

  When a tier identifies the item, the app runs its **own** `priceCheck()` (no Gemini round-trip
  needed) and the report lands in History. Note: Gemini Nano (tier 3) is gated to recent
  flagship devices and an experimental beta SDK, runs only while the app is foreground
  (`BACKGROUND_USE_BLOCKED` otherwise), and is not available on the emulator.

  A **Single | Continuous** selector switches capture modes:
  - **Single** — snap one item; the shot **freezes on screen** while it's identified and
    priced. The loader can be **cancelled** (abort the lookup) or sent to the **background**
    (the lookup keeps running and still saves its report to History while you snap the next).
    On success, a result card over the frozen shot (*Snap another* / *See in History*).
  - **Continuous** — keep snapping; each photo's lookup runs in the background while you shoot
    the next. A counter tracks progress; **Done** opens the session's results list (each row is
    the item + average price, or "scanning…" / "couldn't identify"). Unidentifiable photos are
    flagged rather than interrupting the flow with the Gemini hand-off.

### On-device price-check agent (History tab)

The prompt bar runs `PriceCheckAgent` — the in-app stand-in for what we hoped consumer Gemini
would do via the app functions (which is EAP-gated). Given a spoken or typed request it:

1. **Understands** — Gemini Nano (text) distils the request into a clean eBay search term
   (`"price check my old macbook pro m1"` → `"MacBook Pro M1"`).
2. **Looks up** — the repository scrapes a page of recent **sold** listings plus **active**
   listings (the same scraper the camera and app functions use).
3. **Discards poor matches** — Nano judges each distinct title, dropping accessories, parts,
   cases, and wrong models — far sharper than token overlap.
4. **Reports** — the surviving sold listings become a saved report in History.

Every Nano step **fails soft** to an offline heuristic (`MatchHeuristics.byTokenOverlap` /
filler-word stripping), so the agent still works — just less precisely — on devices without
Nano or while the model is mid-download, and on the emulator. The result banner shows which
path ran (*matches judged by Gemini Nano* vs *matched by keywords*). Voice input uses the
system speech recognizer (`RecognizerIntent`), so it needs no `RECORD_AUDIO` permission; the
mic is hidden when no recognizer is available.

---

## Requirements

`androidx.appfunctions:1.0.0-alpha09` pins a fairly specific toolchain. This project is
built and verified against:

| Tool | Version | Why |
|---|---|---|
| Android Gradle Plugin | **9.1.0** | required by appfunctions alpha09 |
| Gradle | **9.4** | AGP 9.1.x needs Gradle ≥ 9.3.1 |
| compileSdk / `platforms;android-37` | **37** (Android 17) | required by appfunctions alpha09 |
| Kotlin | **2.2.10** (AGP 9 **built-in Kotlin**) | `kotlin-android` is *not* applied |
| KSP | **2.3.6** | the auto-selected `2.2.10-2.0.x` fails under built-in Kotlin |
| Room | **2.8.4** | older Room chokes on KSP2 |
| JDK | **17+** | AGP 9 minimum |
| `minSdk` | **36** | App Functions run on Android 16+ devices |

Notes that bit during bring-up and are worth knowing:

- **AGP 9 uses built-in Kotlin.** Do **not** apply `org.jetbrains.kotlin.android` — AGP
  registers the `kotlin {}` extension itself. The Compose compiler plugin
  (`org.jetbrains.kotlin.plugin.compose`) and KSP are still applied.
- The appfunctions `-compiler` (KSP) **auto-generates** the function index
  (`assets/app_functions.xml`) and merges the platform `AppFunctionService` into the
  manifest — there are **no manual `<service>` entries**. The KSP arg
  `appfunctions:aggregateAppFunctions=true` is required.
- App-function parameters that are optional (have a default) must be **nullable** types.

---

## Build & run

This repo includes the Gradle wrapper *config* but not the wrapper JAR (a binary). Use
one of:

```bash
# Easiest: open the folder in Android Studio and let it sync, then Run ▶.

# Or from the CLI, generate the wrapper once with a local Gradle 9.3.1+:
gradle wrapper --gradle-version 9.4
./gradlew :app:assembleDebug            # builds app-debug.apk  (verified passing)
./gradlew :app:testDebugUnitTest        # runs the parser/stats unit tests (6 tests, verified)
```

You’ll also need a `local.properties` pointing at your SDK (Android Studio writes this
automatically), and the `platforms;android-37` SDK package installed:

```properties
sdk.dir=/Users/<you>/Library/Android/sdk
```

Install on an API 36+ device/emulator:

```bash
./gradlew :app:installDebug
```

> Build status: this project has been compiled and assembled (`assembleDebug`) and its
> unit tests run green against the toolchain in the table above. The build emits one
> advisory warning that AGP 9.1.0 was "tested up to compile SDK 36.1" — expected, since
> alpha09 forces compileSdk 37; it is silenced via `android.suppressUnsupportedCompileSdk`.

---

## Using it through Gemini

Once installed on an API 36+ device, ask Gemini, e.g.:

- **Voice:** “Hey Google, **price check** a Sony WH-1000XM5.”
- **Text:** type to Gemini: “**price check** DJI Mini 4 Pro.”
- **Photo:** share a photo of an item and ask Gemini to price check it — Gemini reads the
  model number, then calls the skill.

> **Status / gating (important):** Implementing App Functions works today, but
> **end-to-end invocation by the public Gemini app is in private preview / Early Access
> as of mid-2026.** Building and indexing the functions does not by itself guarantee the
> consumer Gemini app will route to them on every device. The framework is **alpha** and
> the API may change between releases. Until the preview opens up, verify the skill with
> the system agent over `adb` (below).

### Test the functions without Gemini (adb)

Android ships a system tool for invoking app functions directly:

```bash
# List the functions this app registered:
adb shell cmd app_function list-app-functions | grep -F com.pricefighter

# One-shot price check (function IDs are <fully-qualified-class>#<method>):
adb shell "cmd app_function execute-app-function \
  --package com.pricefighter \
  --function \"com.pricefighter.appfunctions.PriceCheckFunctions#priceCheck\" \
  --parameters '{\"item\":[\"Sony WH-1000XM5\"]}'"
```

The four registered function IDs (confirmed in the generated `assets/app_functions.xml`):

- `com.pricefighter.appfunctions.PriceCheckFunctions#searchSoldListings`
- `com.pricefighter.appfunctions.PriceCheckFunctions#searchActiveListings`
- `com.pricefighter.appfunctions.PriceCheckFunctions#buildPriceReport`
- `com.pricefighter.appfunctions.PriceCheckFunctions#priceCheck`

After a successful call, the report shows up in the app’s history.

---

## Privacy

- No analytics, no accounts, no remote backend.
- The only network calls are GET requests to `www.ebay.com` to load public search pages.
- History is stored in a local Room database (`pricefighter.db`) on the device only.

---

## Limitations & notes

- **Web scraping is inherently fragile.** eBay can change its HTML, rate-limit, or serve
  an interstitial. `EbayParser` uses multiple selector fallbacks and parses each card
  defensively, but selectors may need updating over time. The anti-bot is handled with a
  Chrome-fingerprinted Cronet client + cookie warm-up (see above); if eBay escalates to a
  JS/captcha challenge from a given IP, even that won't help. For production durability,
  consider eBay’s official Browse / Marketplace Insights APIs (note: the sold-data
  Marketplace Insights API is approval-gated).

### Auto-test

`EbayLiveIntegrationTest` drives the real fetch → parse → report pipeline (the exact code
the Gemini tools call) against live eBay. It's skipped by default; run it explicitly:

```bash
./gradlew :app:testDebugUnitTest --tests '*EbayLiveIntegrationTest*' \
  -Dpricefighter.live=true -Dpricefighter.term='Nintendo Switch OLED'
```

Verified output on 2026-06-23: 6,200 sold results → 60 parsed; 4,600 active, lowest
$12.10; report avg $205.50, median $199.99, range $12.99–$387.99. The deterministic
suite (parser + stats, including a current-`s-card`-markup case) runs with a plain
`./gradlew :app:testDebugUnitTest`.

**On-device, end-to-end:** invoking the `priceCheck` app function on an API-37 emulator
runs the full Cronet pipeline against live eBay and returns a real report (e.g. Sony
WH-1000XM5 → 26 sold, avg $117.99, median $127.50, 1,600 active) which then appears in
the history UI:

```bash
adb shell 'cmd app_function execute-app-function --package com.pricefighter \
  --function "com.pricefighter.appfunctions.PriceCheckFunctions#priceCheck" \
  --parameters "{\"item\":[\"Sony WH-1000XM5\"]}" --timeout-duration 90 --brief-yaml'
```
- **Velocity is a lower bound** when an item sells more than ~60 times in 30 days and only
  one sold page is fetched — fetch more pages (the agentic flow supports it) for accuracy.
- **App Functions is alpha** and **Gemini end-to-end is preview/EAP-gated** (see above).
- Photo → model-number recognition is performed by **Gemini**, not the app; the app
  receives an already-resolved item/model string.

## License

[MIT](LICENSE) © Yakrware. Open source — contributions welcome via pull request.
