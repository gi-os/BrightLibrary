## LightBooks v1.11 — A series is one book on the shelf

Eleven volumes of *To Your Eternity* were eleven cells of the same cover. They are now one, drawn as
a stack — the first volume's cover with the edges of the others peeking out behind it — and tapping
it opens a shelf of that series alone, in reading order.

It is the same shelf one level down: same grid, same covers, same long-press for rename, delete or
reading direction. The only difference is the order, because inside a series the volume order is the
only order anyone wants; the main shelf stays newest-first.

**Nothing is stored to make this work.** A series is read out of the titles — "BECK, Vol. 3",
"Bocchi the Rock! v01 (2023) (Digital)", "Love Hina #2" — so a book joins its family the moment it
is named properly and leaves the moment it isn't, with no hidden field to disagree with what you can
see. Renaming a volume is therefore also how you move it.

Details that took a few tries:

- **A title merely ending in a number is not a series.** *Fairy Tail: 100 Years Quest* would
  otherwise become volume 100, and *2001: A Space Odyssey* volume 2001. A volume marker has to be
  explicit: `Vol. 3`, `Volume 3`, `#3`, `Book 3` or the scanners' `v03`.
- **Trailing tags are ignored**, since that is how half the world names a file:
  `(2023) (Digital) (1r0n)`.
- **One volume is not a stack.** A lone *Parasyte, Vol. 6* stays a book, where it can be seen.
- **The same series spelled three ways is one stack** — "WIND BREAKER v1" from a publisher,
  "Wind Breaker, Vol. 2" from Calibre, "wind breaker vol3" off disk. The fullest spelling labels it,
  and on a tie the one that isn't shouting.
- **A stack's progress is weighted by length, not counted in volumes.** Finishing one short volume of
  four does not mean you are a quarter through the series, and a bar that only moves when a volume
  ends would sit still for most of the time you spend reading.

---

## LightBooks v1.10.1 — The volume with an afterword is still a comic

**Downloading certain manga killed the app outright.** The crash report says it in one line:

```
java.lang.OutOfMemoryError: Failed to allocate a 175566328 byte allocation … 128 MB heap
```

175 MB in a single allocation is a whole book being read into memory, which only the *text* import
path does — so a manga volume had been judged prose. It was judged prose because the test asked the
wrong question. Deciding by how much text a book contains works until a scanned volume ships a text
afterword, and *As a Reincarnated Aristocrat* and *I Was Reincarnated as the 7th Prince* carry about
16,000 characters of one. Eleven volumes in the library were affected, every one of them ending in a
dead app rather than a message.

The question is now **how much of the file is pictures**. Measured across 76 real EPUBs, every manga
volume spends 99.9% of its bytes on images and every novel spends under 15% — cover, plates and all.
The threshold sits at 80%, nowhere near either group. The old text-ratio test is still consulted, but
only for archives that report no useful sizes, and it can no longer overrule a low image share.

Two more changes so this class of failure cannot be fatal again:

- **The size is checked before the file is read.** Anything larger than a quarter of the available
  heap is refused with a sentence that says so, and suggests CBZ if it is a comic. An
  `OutOfMemoryError` cannot be handled after the fact — the process is already gone — so the only
  useful place to deal with it is before the allocation.
- `OutOfMemoryError` is caught around the parse anyway, since the parsers build a string of the whole
  text on top of the bytes and the guard is a floor rather than a guarantee.

If any of those eleven volumes are on your shelf as a failed import, delete and re-download them.

---

## LightBooks v1.10 — Downloads are limited by the phone, not by a number I made up

The download cap was 96 MB, which is generous for a novel and meaningless for a comic: a manga
volume is 100–250 MB, so half the library refused to download and said "that file is larger than
96 MB" as though 96 were a fact about the phone.

The limit is now the free space on the device, less a 300 MB reserve so a download can never fill
the disk out from under Android. Two things follow from that:

- **A book that will not fit is refused before the transfer starts.** calibre-web declares the size
  of every download in the catalogue feed, so the check happens the moment you tap, and the message
  names both numbers — "That file is 1.4 GB and the phone has 900 MB free" — instead of a fixed
  ceiling.
- **The LIBRARY rows show the size** next to the format, so the difference between a 4 MB novel and
  a 240 MB volume is visible before you commit to it.

A hard ceiling of 4 GB remains for the case where a link is wrong and a server streams an error page
forever, and the in-memory cap (feeds, covers, text books) went from 96 MB to 256 MB.

---

## LightBooks v1.9.2 — The Calibre feature actually runs on the phone

**A regex the JDK accepts and Android refuses took the whole thing out, silently, from the first
build that had it.**

`CalibreClient` compiled `\{(?:[a-zA-Z]+:)?searchTerms\??}` as a `companion object` property — the
OpenSearch template matcher. That trailing `}` is unescaped. The JDK reads it as a literal brace;
Android's regex engine is ICU-backed and stricter, and rejects it. Because the property is a
companion initialiser, the *first call to any method on the class* threw
`ExceptionInInitializerError` before a single byte reached the network — and every call site wrapped
that in `runCatching`, so what you saw was "That server address is not a URL". The address was fine.
Nothing was wrong with the server, the account or the network; a packet capture on the NAS showed the
phone never opened a socket at all.

Three changes:

- **The template is filled in by hand now, not by a regex.** It reads the `{searchTerms}` parameter
  with a character scan that also understands a namespace prefix (`{atom:searchTerms}`) and the
  optional marker, drops optional parameters it cannot supply, and leaves required unknown ones alone
  so a server that needs them fails loudly. `CalibreClient`'s companion no longer runs any code at
  class-initialisation time.
- **Failures name the thing that failed.** "That server address is not a URL" now carries the
  exception and its message, and the LIBRARY error prints the address it tried. Either line alone
  would have found this in a minute instead of an evening.
- Also in this build, from v1.9.1: a scanned setup code **saves itself** rather than waiting for a
  SAVE tap, and a 401 distinguishes "no account is set" from "the server refused this one".

Worth stating plainly: the unit tests passed throughout, because they run on the JVM, and so did a
standalone compile of the same file. A pure-Kotlin test cannot see an ICU difference. The check that
would have caught this is running the code on the device.

---

## LightBooks v1.9.1 — A scanned code saves itself, and errors say what went wrong

Scanning the setup code filled in the four fields and then, if you left the page without pressing
SAVE, kept none of them. That looks exactly like being set up, so leaving is the natural thing to
do — and the next thing you see is the library failing to load. A scan now writes the settings down
the moment it succeeds and says "Saved."; the fields stay on screen if you want to check them.

Two error messages were also hiding the thing worth knowing. A 401 said "check the username and
password" whether or not a username had been sent at all — it now distinguishes "no account is set"
from "the server refused this one". And a URL the phone could not open said only that it could not
open it, swallowing the platform's own reason: a missing scheme, a refused host and a cleartext
policy rejection all looked identical. The exception and its message now reach the screen, and the
LIBRARY failure prints the address it actually tried.

---

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
