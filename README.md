# LightFastread

**A fork of [FastRead](https://github.com/fluffyspace/FastRead) retuned for the Light Phone III's black-and-white OLED.**

Everything upstream does, on a screen where every lit pixel is a choice: pure `#000000` on
every surface, a greyscale palette, and layout that assumes a 3.92" near-square panel
instead of a tall glossy one.

Upstream FastRead is unchanged in behaviour here. This fork only touches presentation.

---

## What's different from upstream

| Change | Why |
| ------ | --- |
| New **Light Phone** theme mode, default on first run | Kept separate from `Dark` rather than replacing it, so this fork stays rebaseable on upstream. |
| Every `surface*` role is `#000000`, `surfaceTint` is black | Material 3's dark scheme uses `#1C1B1F`-ish greys plus tonal elevation overlays. On an OLED that's a lit grey slab where there should be nothing. Black `surfaceTint` makes `surfaceColorAtElevation` resolve to black at every elevation. |
| Hairline outlines replace tonal elevation | Once tonal lift is gone, book rows, the top bar, the chapter strip and the bottom sheet are black-on-black with no visible boundary. They get 1dp `outline` edges instead — one row of dim grey rather than a whole grey rectangle. |
| Title and ORP accents flattened to grey | The panel is greyscale, so the orange title and red ORP letter arrive as whatever grey their luminance happens to be. Rec. 709 luma is remapped into `0.62..1.0` so low-luma picks (the default red, luma ≈ 0.36) stay legible instead of sinking into the dimmed context lines. |
| Contrast floors on de-emphasised text | Matte glass diffuses light and costs roughly a stop of perceived contrast. Context lines, the paragraph pilcrow, the progress readout and the zone guides all had alphas tuned for glossy glass. Relative ordering is preserved; the bottom of the range is lifted. |
| `surfaceVariant` and the dialog containers stay faintly grey | These back Material's *tracks* (progress bar, slider rails, switch tracks) and its *ephemeral* containers (AlertDialog, menus). Pure black would erase the tracks entirely, and a scrim over an already-black background tints nothing — a black dialog on a black screen is unanchored text. |
| Black `windowBackground` in `themes.xml` | The launch theme is what Android paints during cold start, before Compose draws. The stock `Material.Light` parent flashed white — a full-brightness strobe on every launch. |
| Book list padded past the FAB; bottom sheets allowed to go taller | The LPIII is roughly **411 × 472 dp** — normal width, about half the usual height. Anything sized as a fraction of screen height needed a second look. `Scaffold` reserves no space for the FAB, so the last book row sat permanently under "Add book". |
| `targetSdk` 34; package fully renamed to `com.lightfastread` | LightOS is Android 14, and the light-sdk emulator profile is API 34; no reason to opt into 35/36 behaviour the device will never see. Both `namespace` and `applicationId` are `com.lightfastread`, so this shares no identifier with upstream FastRead — not the package, the R class, or the permission and provider authorities AndroidX derives from them. It installs cleanly alongside any other FastRead build. |

Part of the [gi-os Light App collection](#the-gi-os-light-app-collection).

---

## Installing on a Light Phone III

Light's own [light-sdk](https://github.com/lightphone/light-sdk) is the sanctioned path for
LightOS "Tools", and community tools will eventually be built and signed by Light. This is
a plain sideloaded APK, not an SDK tool, so for now:

```bash
# Every push to main publishes a build to Releases. Grab the newest debug APK:
adb install -r LightFastread-<version>-debug.apk
```

Use the **debug** APK — it's signed with Android's standard debug key, so it installs
without any extra setup. Nothing needs uninstalling first; the package ID is unique to
this fork. The release APK is unsigned unless the `RELEASE_*` secrets are set.

Two caveats worth knowing before you start, both from Light's own docs as of July 2026:

- There is **no easy distribution path yet**. ADB sideloading works if you're comfortable
  with it, but LightOS builds in the wild aren't ready to play nicely with SDK-built tools.
- LightOS will let you run unsigned/third-party APKs only under the **"Any tools"** setting,
  and Light explicitly warns that you own the install/uninstall lifecycle there.

### Testing without hardware

Light publishes an emulator profile that behaves close to real hardware:

- **1080 × 1240, 3.92" display**
- **Android API 34**
- **No Google Play Services**

That's the configuration this fork was designed against. Note the emulator renders in
colour — the real panel is black and white, which is exactly why the palette here is
greyscale by construction rather than "colours that happen to look fine in dark mode".

---

## Building

`.github/workflows/build.yml` builds, tests and publishes on every push to `main` — no
tagging step needed. Each run:

1. builds debug + release APKs,
2. stamps `versionCode` with the **workflow run number** and `versionName` with a `-bN`
   suffix, so each build is strictly newer than the last,
3. publishes a GitHub Release tagged `build-N`, marked latest, with both APKs attached.

Because `versionCode` always increases, `adb install -r` upgrades in place — no uninstall,
no lost library or reading positions.

`baseVersionName` in `app/build.gradle.kts` is the single source of truth for the version
number; the workflow greps it to name the artifacts. Bump it there when the version changes.

Tagging `v*` additionally cuts a release with generated notes, for when you want a
hand-marked version rather than a rolling build.

For a properly *signed* release APK, set four repository secrets:
`RELEASE_KEYSTORE_BASE64`, `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`,
`RELEASE_KEY_PASSWORD`. Without them the release APK is unsigned and the debug APK is the
one to install.

Locally:

```bash
./gradlew assembleDebug     # requires JDK 17+ (AGP 9.x) and Android SDK 36
```

Local builds get `versionCode = 1`, so if a CI build is already on the device you'll need
`adb install -r -d` to allow the downgrade.

---

## Screenshots

These two are this fork, on a Light Phone III.

| Library | Reader |
| :---: | :---: |
| ![Library](docs/screenshots/library.png) | ![Reader](docs/screenshots/reader.png) |
| One word at a time, three lines of context above it, bionic mode on, zone guides visible. | Imported EPUBs with word counts and progress. Hairline outlines stand in for the tonal elevation an OLED cannot afford. |

The next two come from upstream and still show the original purple and light interface.
They are here because this fork changes no behavior on either screen.

| Settings (upstream) | Quick settings (upstream) |
| :---: | :---: |
| ![Settings](docs/screenshots/settings.png) | ![Quick settings](docs/screenshots/quick-settings.png) |
| Input mode, speed, pauses, fonts, bionic, theme. | In-reader sheet for live font and size changes. |

---

## Why FastRead

Most RSVP apps either bury you in buttons, lock features behind a subscription, or require you to push your library into someone else's cloud. FastRead is the opposite:

- **One-gesture reading.** No play/pause buttons. Hold to read, drag to change speed, release to stop. That's the whole interface.
- **Your library stays yours.** Books are imported from local storage via the system file picker and cached privately inside the app's own files directory. Nothing leaves the device.
- **Native parsers, no bloat.** EPUB and MOBI parsing are written from scratch in Kotlin. No third-party ebook libraries pulled in.
- **100% Kotlin + Jetpack Compose.** Modern Android stack, single-Activity, navigation-driven, easy to fork and hack on.
- **No internet permission requested.** The app cannot phone home because it has nothing to phone home with.

---

## Features

### Reading

- **RSVP one-word-at-a-time presentation** with a large, configurable focal word.
- **Three input modes**, switchable in settings:
  - **Hold zones** — hold the right 2/3 of the screen to advance, hold the left 1/3 to step back or auto-rewind.
  - **Swipe** — swipe right to advance, left to go back; distance per word is configurable.
  - **Zone swipe** — combine zones with swipe direction for fine control.
- **Speed mapped to finger position.** In hold-zones mode, where you press in the forward zone is your WPM (left edge = `minWpm`, right edge = `maxWpm`).
- **Smooth ramp-up** from 0 to target WPM on press, so speed changes never feel jarring.
- **Tap-to-step-back vs. hold-to-rewind** in the backward zone, separated by a configurable threshold.
- **Three-line context window** above the focal word so you don't lose your place when you blink.
- **Smart pauses** at sentence ends and paragraph breaks, scaled to your current reading speed.
- **Per-letter slowdown** for long words — give your eyes a few extra milliseconds on the hard ones.
- **Bionic reading** mode that bolds the leading letters of each word; apply it to the focal word, the context lines, both, or neither.
- **Per-book progress** saved automatically and resumed on the next open.

### Library

- **Import EPUB and MOBI** via the Android Storage Access Framework — works with anything in your local storage, on an SD card, or in a cloud-sync folder you've already mounted.
- **Long-press to delete** with a confirmation dialog.
- **At-a-glance progress** bars on every book in the library list.

### Look & feel

- **Material 3 design**, with a hand-built greyscale scheme for the Light Phone III.
- **System / Light / Dark / Light Phone** theme switching. Light Phone is pure `#000000` greyscale.
- **Live font preview** in settings: size and family (default, serif, sans-serif, monospace).
- **Quick-settings sheet** on the reader screen for tweaking font size and family without leaving your book.

---

## Supported formats

| Format | Notes |
| ------ | ----- |
| `.epub` (EPUB 2 / 3) | ZIP container, OPF spine traversal, HTML stripped to plain text. |
| `.mobi` (PalmDOC compression, type 1 & 2) | Native PDB/PalmDOC decoder. |
| `.mobi` (HUFF/CDIC compression, type 17480) | Not supported — surfaces a friendly error. |

Rich rendering (images, layout, footnotes) is intentionally out of scope. Speed reading is plain words.

---

## Installation

> No Play Store listing yet. FastRead is distributed as source and via GitHub Releases (when tagged).

### Build from source

Requirements:

- Android Studio Hedgehog or newer (or just the Android SDK + Gradle)
- JDK 17
- Android SDK 36 with build-tools

Steps:

```bash
git clone https://github.com/gi-os/LightFastread.git
cd LightFastread
./gradlew assembleDebug
# APK lands in app/build/outputs/apk/debug/
```

Or open the project in Android Studio and hit Run.

Minimum SDK: **24 (Android 7.0 Nougat)**. Target SDK: **36**.

---

## Quick start

1. Tap **Add book** on the home screen and pick an `.epub` or `.mobi` file.
2. Tap the imported book to open the reader.
3. **Hold the right side of the screen** to start reading. Slide your finger left to slow down, right to speed up. Release to stop.
4. **Tap the left side** to step back one word. **Hold the left side** to auto-rewind.
5. **Tap the very top of the screen** to reveal the title bar with a back button and quick-settings shortcut.

---

## Configuration

All settings persist via SharedPreferences (JSON-encoded, no database). They can be tweaked live from the Settings screen:

| Setting | Default | What it does |
| ------- | ------- | ------------ |
| Min WPM | 100 | Reading speed at the left edge of the forward zone. |
| Max WPM | 500 | Reading speed at the right edge of the forward zone. |
| Speed ramp-up | 300 ms | Time to ease from 0 to target WPM after pressing. |
| Backward hold delay | 500 ms | Tap shorter than this = step back; longer = auto-rewind. |
| Pause after sentence | x1.0 | Multiplier applied to the inter-word interval after `.`, `!`, `?`. |
| Pause after paragraph | x2.0 | Same, applied at paragraph breaks. |
| Extra ms per letter | 0 | Adds delay to long words. |
| Letter delay threshold | 5 | Word length above which the per-letter delay kicks in. |
| Font size | 56 sp | Focal word size. |
| Font family | Default | Default / Serif / Sans-serif / Monospace. |
| Bionic mode | Off | Off / Main only / Context only / Both. |
| Theme | Light Phone | System / Light / Dark / Light Phone (true black). |
| Input mode | Hold zones | Hold zones / Swipe / Zone swipe. |
| Swipe distance per word | 10 dp | Only used in swipe-based input modes. |

---

## Architecture

```
app/src/main/java/com/fastread/
├── MainActivity.kt          # NavHost + theme wiring
├── data/                    # Book, Settings, repositories (SharedPreferences + JSON)
├── parser/                  # EPUB, MOBI, HTML strip — all hand-written
└── ui/
    ├── home/                # Library list + SAF importer
    ├── reader/              # Gesture surface + RSVP loop
    ├── settings/            # All configurable values
    └── theme/               # Material 3 theme + dynamic color
```

- **UI:** Jetpack Compose + Navigation Compose. Three destinations: `home`, `reader/{bookId}`, `settings`.
- **Persistence:** SharedPreferences for the book list and settings (kotlinx.serialization JSON). Each imported book's extracted text is cached as a plain `.txt` file in the app's internal `files/books/` directory.
- **Parsers:**
  - `EpubParser` — reads the ZIP, locates the OPF via `META-INF/container.xml`, walks the spine, strips HTML.
  - `MobiParser` — parses the PDB header, decompresses PalmDOC (type 2) or reads uncompressed (type 1), strips HTML.
  - `HtmlStripper` — minimal tag remover with entity decoding; sized for ebook body text.
- **Progress:** `currentWordIndex` per book is debounce-saved on change and on screen dispose, so you never lose your place.

### Dependencies

Compose BOM, Navigation Compose, Material 3 + extended icons, Lifecycle, AndroidX Activity, AndroidX DocumentFile, kotlinx-serialization-json. That's it. No analytics, no crash reporters, no networking libraries, no ebook SDKs.

---

## Privacy

- **No internet permission.** Check `AndroidManifest.xml`.
- **No analytics, no telemetry, no crash reporting.**
- **No accounts, no sign-in.**
- **Book contents never leave the device** — they live in the app's internal files directory and are removed when you uninstall.

---

## Roadmap / non-goals

**Could land later** (PRs welcome):

- ORP (Optimal Recognition Point) focal-letter highlighting.
- More formats: plain `.txt`, `.fb2`, PDF text extraction.
- Library sorting / search / collections.
- Export reading stats (locally).

**Intentionally out of scope:**

- Cloud sync, accounts, library sharing.
- Rich rendering (images, formatting, footnotes).
- HUFF/CDIC-compressed MOBI (rare in practice; surfaces a friendly error).
- DRM-protected ebooks.

---

## Contributing

Bug reports and PRs are welcome. A few notes:

- Keep dependencies lean. New libraries should be justified.
- Match the existing Compose / repository style; no DI framework, no architectural ceremony.
- For reader-loop changes, test at both extremes: very slow (under 100 WPM) and very fast (over 1000 WPM). Smoothness at the edges matters.
- See `CLAUDE.md` for a concise tour of the codebase aimed at newcomers (and AI assistants).

---

## The gi-os Light App collection

Nine tools for the Light Phone III, all open source, all built in one run.

| Tool | What it does | Built on |
| --- | --- | --- |
| [LightPass](https://github.com/gi-os/LightPass) | Photograph a movie ticket, keep the stub | Plain Android |
| [LightQR](https://github.com/gi-os/LightQR) | QR scanner, plus a browser generator | Plain Android |
| [LightRSS](https://github.com/gi-os/LightRSS) | RSS and Atom reader with images and QR subscribe | light-sdk, fork of [zachattack323/LightRSS](https://github.com/zachattack323/LightRSS) |
| [LightNYCSubway](https://github.com/gi-os/LightNYCSubway) | Live MTA subway arrivals | light-sdk fork |
| [chat](https://github.com/gi-os/chat) | iMessage over a self-hosted BlueBubbles server | Fork of [craigeley/chat](https://github.com/craigeley/chat) |
| [LightFog](https://github.com/gi-os/LightFog) | Fog of World companion, GPS recorder and fog map | Fork of [garado/light-topographic](https://github.com/garado/light-topographic) |
| [LightNonogram](https://github.com/gi-os/LightNonogram) | Picross, plus a generator that only ships solvable puzzles | Kotlin generator, light-sdk tool |
| [LightSolitaire](https://github.com/gi-os/LightSolitaire) | Klondike, draw one, unlimited redeals | light-sdk |
| **LightFastread** (this repo) | RSVP speed reader for EPUB and MOBI | Fork of [fluffyspace/FastRead](https://github.com/fluffyspace/FastRead) |

The Light Phone does not sponsor or endorse any of these. This repo is GPL-3.0, unlike the
rest of the collection, because upstream FastRead is.

---

## License

FastRead is released under the **GNU General Public License v3.0** (GPL-3.0). See [LICENSE](LICENSE) for the full text.

In short: you are free to use, study, modify, and redistribute the app, but any distributed derivative work must also be released under GPL-3.0 with source available. This keeps FastRead and its forks open to the community.

---

## Credits

The reading engine, both parsers and the whole gesture design belong to
**[fluffyspace](https://github.com/fluffyspace)**, in
[fluffyspace/FastRead](https://github.com/fluffyspace/FastRead). This fork contributes a
display treatment for one piece of hardware and nothing else. Thank you.

[queueingqt/FastRead](https://github.com/queueingqt/FastRead) is a fork of that original,
not the source. An earlier version of this file credited it by mistake.

RSVP as a reading technique has been around for decades; FastRead's contribution is a tight, gesture-first Android implementation that respects your privacy and your library.
