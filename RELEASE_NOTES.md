## LightBooks v1.9 — Manga, and a setup you can point a camera at

**Comics read on the phone now, and the four Calibre fields go in from one QR code.**

### Comics

CBZ files and the image EPUBs a scanned volume ships as open in a reader of their own: one page,
full screen, no chrome until you tap for it. **Right to left by default**, because most of what
this is for is manga — long-press a book to flip it to left-to-right, per book, since a shelf
holds both. Tap the outer thirds to turn, the middle for the page count and the way out.
Double-tap zooms in on a panel and drag pans it. The wheel turns pages either way round, three
notches a page, and ignores reading direction entirely — a wheel is not a spatial gesture.

Pages are converted once, at import: downscaled to the panel's 1240px and desaturated, then the
archive is thrown away. A 250 MB volume becomes about 20 MB of pages, and turning one costs a
file read instead of a full-size JPEG decode. It takes a minute or two per volume and the screen
says which page it is on while it works.

The detection is automatic. A `.cbz` is a comic by definition; an EPUB is one if it is mostly
images and almost no text, which is exactly what a scan is and exactly what a novel is not. Text
books are unaffected — same reader, same RSVP, same everything.

**Downloading a comic from Calibre now streams to disk** instead of into memory. A 250 MB volume
read into a `ByteArray` on a phone with a couple of hundred megabytes of heap is not a slow
import, it is a crash — so both import paths, the ADD button and the Calibre library, now hand a
file to one shared importer.

### One QR code instead of four fields

Settings → Calibre → **SCAN QR CODE**. The code carries the server address, username, password
and Kobo sync URL, and fills in all four at once. Typing them meant about 150 characters on a
phone with no keyboard of its own, most of it an unmemorable hex token.

It also reads a plain Kobo sync URL copied straight out of calibre-web — that names its own
server, so one scan fills in the address too — and a plain server URL. A code that is not a
Calibre setup code is refused rather than half-applied, and the screen says which fields changed.

Uses ML Kit's bundled barcode model rather than the Play-services one: LightOS has no Play
Services, so the on-demand variant would wait forever for a model that never downloads. The
camera is used for this and nothing else, and only while that screen is up.

---

## LightBooks v1.8 — Your Calibre library, on the phone

**LIBRARY on the shelf's bottom bar. Browse the Calibre server, tap a book, read it — and the server
finds out where you got to.**

### Browsing

Every Calibre server already publishes its library as OPDS: calibre-web, `calibre-server` and COPS all
answer at `/opds` with the same Atom feed. So there is nothing to install on the server side. Put the
address in Settings -> Calibre, with a username and password if it asks for one, and LIBRARY walks the
catalogue the way the server lays it out — recently added, by author, by series — a list of rows with a
cover thumbnail, the author underneath and the format on the right.

Tap a book and it downloads straight onto the shelf. EPUB is preferred over the Kindle formats because
the EPUB parser keeps chapter structure; a book in a format this app cannot read says so in the row
rather than failing after the download. SEARCH asks the server, not the shelf, and uses the feed's own
OpenSearch template when it advertises one.

Two smaller things fall out of this. **Covers come from Calibre**, which means a book downloaded here
never needs an Open Library lookup — if you fixed the cover in Calibre, that is the cover you get. And
**the title and author come from Calibre too**, in preference to whatever is inside the file, because a
library has been curated and an EPUB's own metadata frequently has not.

A book already on the shelf is marked ON SHELF, so a long catalogue does not turn into guesswork about
what you already have.

### Reading progress

Turned on by default, but it needs one thing on the server: calibre-web's **Kobo sync** (Admin ->
Basic Configuration -> Enable Kobo sync), then paste the sync URL from your user page into Settings ->
Calibre. There is no progress field in OPDS, so this rides the API a real Kobo e-reader uses.

What it does: pushes your position while you read — throttled to at most once a minute, and again when
you leave the app — and when you download a book it picks up where another device left off. Your own
bookmarks carry the exact word index, so a resume is not rounded through a percentage; a bookmark from
some other reader is all a percentage can say, and is treated as such.

It is built to survive being offline, which is most of the day for a phone. Each book remembers what
the server was last told, and anything that has drifted goes out on the next sync — nothing queues,
nothing is lost, and a failed push is never an error on screen. SYNC NOW in Settings if you want to
force it.

### Worth knowing

- The password and the Kobo token are stored unencrypted in the app's own settings, and the token *is*
  the authentication for that endpoint. This is pointed at a LAN server on purpose.
- Cleartext HTTP is now permitted, because nobody puts a certificate on `192.168.x.x` and Android has
  blocked it by default since API 28. Everything else the app talks to is still https.
- The shelf's bottom bar is now at the SDK's hard limit of three text items (ADD / LIBRARY /
  SETTINGS).
- Text entry — the server address, a search — needs a system keyboard, which on LightOS means
  LightKeyboard.

---

## light-common 1.2.1 — the baseline profile arrives

A one-line dependency bump, and the only reason it needs a release of its own is that the last
one did not do what it said.

The previous version added `profileinstaller` on the strength of light-common shipping a baseline
profile in its AAR. It was not in the AAR. The file had been put in `src/main/baselineProfiles/`,
which is the app-module directory; a library ships one as `src/main/baseline-prof.txt`, and AGP
packages nothing and warns about nothing when it is in the wrong place. So `profileinstaller` was
installed, ran, and found no profile.

1.2.1 fixes the packaging, and this build is the first that actually gets it: the wheel and the
crash handler are compiled ahead of time instead of being interpreted on the way to the first
frame. That is the first turn of the wheel after a cold start, and the code that runs in
`onCreate` of every single launch.

Nothing else changed — no code, no keep rules, no behaviour.
