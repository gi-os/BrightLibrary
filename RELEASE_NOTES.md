## LightBooks v1.15.0 — The shelf's title scrolls, and its bottom bar is icons

**"Books" was a fixed top bar**, three grid units of screen on every single shelf view, never
moving. It is now the grid's own first row, spanning both columns, so it scrolls away with the
covers exactly like a book scrolls away in a reader -- more of the shelf, less permanently-parked
chrome. The empty shelf keeps the title outside the grid, since there is nothing there to scroll.

**The bottom bar is icons now, not text.** Add, Library and Settings were three text labels --
exactly the SDK's limit once any item is text, so a fourth action would have had nowhere to go.
Icons allow up to five, and Add now sits last, after Settings, rather than first.

---

## LightBooks v1.14.3 — The wheel answers a roll of the thumb faster

**Scrolling a comic page took three notches of the wheel before anything moved.** The sensor fires a
discrete notch every 35-60ms of travel, and the reader banked three of them into one step -- a
threshold meant to stop a single stray notch from moving the page, sized to match the text reader's
page-turn count. For a deliberate scroll it read as a beat of nothing before the page caught up.

Dropped to two notches per step. Still enough to swallow one accidental notch, since a real turn
arrives as a burst rather than a single tick, but the page now answers a normal roll of the thumb
without the lag. Page turns and 4-koma strip stops are untouched -- this is the scroll-within-a-page
threshold only.

---

## LightBooks v1.14.2 — One scroll for a page 4-koma couldn't split

**A page with no gutter was still charged four koma stops.** In 4-koma mode, `stepsFor` returned the
mode's fixed three-scrolls/four-stops count for *every* page once the series was in 4-koma mode — even
one that measured as unsplittable and is shown whole, at fit-width, because crop found no blank band
down the middle. A whole page is short; it should step like any other page, usually one scroll, not
sit behind three empty notches meant for a tall narrow column.

Fixed by checking the page against the same `unsplittable` set the reader already keeps: the fixed
four-stop count now applies only while the current page actually split into two columns. A page shown
whole falls through to the ordinary step count, based on its real measured height.

---

## LightBooks v1.14.1 — A book remembers where you got to

**Reading a book saved your place only when you closed it deliberately.** The page view reported
its position on the way out — the back arrow in the chrome, or the FastRead button — and nowhere
else. But the way you leave an app on this phone is the home key, which stops the activity and, some
minutes later, kills the process. Neither runs the exit path, so everything read since the last
deliberate close was never written down, and the book reopened at that same old place every time.

Two fixes, deliberately overlapping:

- **Every page turn is a saved position.** The settled page — the one the pager landed on, not one
  being dragged past — writes its first word straight to disk. Nothing is reported until the pager
  has been moved to the page the book was left on, or opening a book would save page 1 over the very
  place it was about to restore.
- **Leaving the app saves too**, on the same signal that already tells Calibre where you are.

Page turns are saved without going on the back stack: a turn is reading, not a jump, so the history
arrow still walks back over the places you *jumped* to.

**4-koma cuts have leeway now.** A gutter had to be blank paper — under 0.4% ink — and real scans are
not that pure: a balloon tail, a hand, a sound effect or a page number leaning into the gap made the
page unsplittable, which in 4-koma mode reads as a whole page in the middle of a book of strips. A
column may now carry **up to 10% ink** and still be cut through. A speech bubble stretched across both
strips is around 16% and still blocks the cut, which is right — those two strips really are joined.

---

## LightBooks v1.14.0 — Pages stored at the width they are shown at

**Every page was slightly softer than the screen could show, and 4-koma strips were much worse.**
A page was stored with its **long edge** at 1240 — the panel's height — which puts an ordinary
portrait page at 868 pixels wide. The reader then fits a page to the screen's *width*, 1080, so every
page in every book was being stretched by a quarter on its way to the glass. In 4-koma mode a column
is half a page: **434 pixels blown up to fill 1080**, which is where softness stops being theoretical.

The cap is on the **width** now:

- **1080** for an ordinary volume — the panel's own width, so fit-to-width is pixel for pixel.
- **2160** for a volume that prints two strips to a page, so a *column* lands at 1080.
- A separate pixel ceiling for the pages that are not page-shaped — a double spread, a poster, a strip
  stitched into one tall image — which a width target alone would let become a bitmap no phone can
  decode.
- **Nothing is ever enlarged at import.** The scan is the ceiling: a volume scanned 1600 wide stores
  at 1600, and no amount of storage invents the rest.

**Which volumes are strips is decided by looking at them.** Ten pages spread through the middle of the
book are decoded small and asked whether they have a band of blank paper down the middle — the same
test the reader already uses to decide whether a page can split at all. Seven of ten and it is a
yonkoma: it is stored at the wider size, and **4-koma mode switches itself on for that series**. Only
for the first volume of a series, so a mode you turned off is never switched back on by the next volume
you add. Covers and afterwords are skipped, being the pages least likely to have a gutter in a book
that otherwise has one.

**Three things had to change so a bigger page cannot kill the app.** A 2160-wide page is 27 MB once
decoded, and the reader used to keep six pages at a time — which would have been 160 MB. So the page
cache is budgeted in bytes rather than counted in entries, the decode backs off against the heap so a
phone with less memory gets a smaller page instead of a crash, and the crop box and the gutter are now
measured on a small copy of the page rather than by reading 27 million pixels of the real one.

**Volumes already on the phone keep their old pages** — delete and re-download the ones worth it,
starting with the 4-koma. Expect an ordinary volume to take about half again as much space, and a
strip volume around four times as much; a volume that will not fit is stored at the ordinary width
rather than filling the disk.

---

## LightBooks v1.13.4 — 4-koma only splits a page that has a gutter

**A full page is no longer cut in half.** 4-koma mode used to split every page at the halfway point,
which is right for a yonkoma page and wrong for everything else in the volume — a splash page, a
chapter break, a double-page spread all got sliced through the middle of the drawing.

Each page is now asked, once, when it loads: **is there a band of blank paper down the middle?** If
there is, the page reads as two strips and **the cut is the centre of that band**, not the centre of
the page — a scan's gutter is rarely centred, and cutting at the halfway point leaves a sliver of one
strip at the end of the other. If there isn't, the page is shown whole and the strip that doesn't exist
is stepped over, so the wheel goes straight on to the next page.

What counts as a gutter, measured against real page shapes:

- **Blank, not merely quiet** — under 0.4% ink, far stricter than the 2% a content line is allowed.
- **Near the middle**, within the central third. A gap a fifth of the way in is the space beside a
  panel.
- **At least 1.2% of the page wide.** Four pixels between two panels of one strip is not a gutter.
- **At most a quarter of the page.** Wider than that and the middle is simply empty.
- A speech bubble straddling the gutter blocks the split, which is correct: those two strips are joined.

Nine assertions cover it, including the off-centre case — a gutter at 430–449 on a 900px page cuts at
440, and one at 380–419 cuts at 399 while the page's own middle is 450.

---

## LightBooks v1.13.3 — Three scrolls, four stops

A 4-koma strip now takes **three** scrolls rather than four, which is four resting points down the
strip — one per panel. The first panel is where you start, not somewhere you scroll to, so four moves
was one too many and ended with a stop that had nowhere left to go. The counter in the chrome reads
1/4 through 4/4.

---

## LightBooks v1.13.2 — 4-koma reads the right strip, then the left

I had the cut in the wrong direction. A yonkoma page prints **two strips side by side**, each four
panels tall — so the page divides into *columns*, and cutting it horizontally sliced every strip
through the middle of its panels.

How it reads now, on a page like the *Twilight* example:

```
right strip → 4 scrolls down it → left strip → 4 scrolls down it → next page
```

- **The split is vertical**, into two columns, cut after the crop so the paper margin isn't handed half
  to each. The second column takes the remainder, so rounding can't lose a column of pixels between
  them.
- **The right strip is read first** in a right-to-left book, the left first otherwise. The strip you are
  on is turned into a physical column at the last moment, so nothing else in the reader has to know.
- **Each strip is one image taken in four scrolls** — four panels, four stops. Dividing by screenfuls
  instead would put the stops wherever the screen happened to end rather than between panels.

Still remembered per series, and still nothing stored on the file: the strips are source rectangles over
the one page image.

---

## LightBooks v1.13.1 — Four strips, per series, and a slower turn

- **4-koma mode cuts a page into four strips, not two halves.** A yonkoma page is four panels in one
  column — 4x1, not a 2x2 grid — so each strip is now one screen of reading and a notch moves to the
  next one. The cut still happens after the crop, so the paper margin is not handed a quarter to each
  strip, and the last strip takes the remainder so rounding cannot lose a row of pixels between them.
- **It is remembered per series, not for the whole app.** Whether a book is four strips to a page is a
  fact about the book: *Nichijou* is, *Wind Breaker* is not, and a global switch would have to be
  flipped on the way into and out of every volume. A book with no series remembers against its own
  title. Fit-to-width, cropping and tap-to-turn stay global, since those are preferences.
- **The turn is slower**: the page takes 340ms to slide up rather than 210, and the darkening behind it
  runs over 420ms and linearly, so it reads as a fade rather than as arriving at black and waiting
  there. In-page steps went from 220ms to 260ms.

---

## LightBooks v1.13 — 4-koma mode, and a page turn with nothing between the pages

**The black frame is gone, and it was the pager's fault.** `HorizontalPager` animates its own way —
sideways, with the gap between pages showing through — and it owned the scroll position, which is also
why turning backwards kept landing in the wrong place. The transition is now hand-rolled: the page you
are leaving stays exactly where it was and *darkens* while the new one slides over it in the direction
you were already travelling. There is never a gap, because the outgoing page is only dropped once the
incoming one covers it.

**Scrolling is counted in steps, not measured in pixels.** Each page's overflow is divided into a
whole number of equal steps and the reader moves between step *indices*, so the second notch can no
longer land you half a step from the bottom — the "0.5" it used to pull. The last step always lands
exactly on the bottom, and a drag now re-syncs to the nearest step so the notch after it is a whole
one.

**4-koma mode** splits every page across the middle and reads the halves as two pages, for the
volumes that print two strips to a page. The split happens *after* the crop, so the paper margin isn't
counted as part of either strip. Half a page fills this screen about twice over, so the steps are half
a screen in this mode, and a strip taller than two screens gets at least four of them rather than two
enormous jumps.

**A settings menu inside the reader**, on the bottom bar next to the fit toggle: fit to width, crop
white borders, 4-koma mode, and **tap the edges to turn** — which can now be switched off. It is the
gesture most easily made by accident, and with a wheel to hand it is not the only way to turn a page;
off, a tap anywhere opens the menu instead. Every option writes through to the saved settings, so the
next book opens the way this one ended.

Four bugs found by review before this shipped, each of which would have looked like a different
feature misbehaving:

- The drag-release step re-sync never ran, because the gesture coroutine had captured the step size
  from the first frame — when the viewport was still zero and every page was worth no steps.
- Toggling 4-koma threw away your place: the slot was keyed on the mode, so flipping it reset to
  wherever the book was opened, and the next save made that permanent.
- A page turn between two same-sized pages never re-reported its measurement, so a backwards turn
  landed at the top rather than the bottom.
- A sentinel value used while waiting for that measurement could never be cleared in whole-page mode,
  which silently stopped backwards turns working for the rest of the session.

---

## LightBooks v1.12.1 — Even steps, and a page that arrives from the right direction

Three corrections to the fit-to-width reader, all of them about how the scroll *feels*:

- **Every step down a page is now the same distance.** The page's overflow is divided into equal
  steps of at most a screen, so a page one and a half screens tall is two half-screen steps rather
  than a screen and then a stub — and the last step lands exactly on the bottom.
- **A page turn continues the movement it came from.** While fitted to the width, the next page now
  slides up from the bottom instead of sliding in sideways, and going back brings the previous page
  down from the top, at its bottom edge — where you were reading when you left it. Whole-page mode
  keeps the sideways turn, which is what a page turn looks like when you can see the whole page.
- **Scrolling is animated** — about a fifth of a second, eased. A page that jumps a screen with no
  motion gives you nothing to follow, and finding your place again is most of the work of reading.

Four bugs found while writing it, none of which would have been obvious from using it for a minute:

- Tapping the forward third twice turned one page, twice. The tap handler had captured the page
  number it was composed with, and Compose had no reason to replace it.
- After turning by swipe rather than by tap, the scroll position, zoom and pan of the *previous* page
  were still in force.
- The scroll limit could belong to the page you had just left, so the first notch on a taller page
  turned straight past it.
- A fast drag under-scrolled, because each pointer event started an animation that cancelled the one
  before it and lost its delta.

---

## LightBooks v1.12 — Manga you can actually read on a 3.9" screen

**Pages now fit the width and scroll, rather than being shrunk whole onto the panel.** A manga page
was drawn for paper seven inches across; showing all of it on this screen proves it exists but does
not let you read the lettering. Fitted to the width, the text is the size it was drawn to be and a
page is about two screens tall.

**The wheel follows the page.** One notch takes you a screen down; the notch after the last one turns
to the next page. Going backwards lands you at the *bottom* of the previous page, which is where you
were reading when you left it. A little of the previous screen stays visible on each step, so a line
of dialogue is never cut in half across two screens.

**Pinch to zoom**, up to 6x, about the point between your fingers rather than the middle of the
screen — pinching on a speech bubble keeps that bubble under your fingers. Drag to move around a
zoomed page, double-tap to go back to the fit. A single finger on an unzoomed page scrolls it, and a
horizontal swipe still turns pages.

**White border cropping**, off by default, in the bar with the fit toggle. It measures where the ink
actually is and trims the paper around it; on a typical scan that is a fifth of the width back.

Three decisions in the crop worth naming, since each is a way it could have quietly eaten your art:

- **Solid black is ink, not a border.** An earlier version treated near-black as scanner edge, which
  cost it every black panel and night scene in the book. The price is that a scan framed in black
  does not crop at all — it shows you the whole page, which is the safe way to be wrong.
- **Dust is not content.** A line needs 2% ink before it counts, so specks on the glass and JPEG
  ringing along an edge don't drag the crop out to the paper's corner.
- **It refuses to crop away more than three quarters of a page.** A page that measures as mostly
  margin is a measurement gone wrong — a splash of open sky, a faded scan — and blowing the rest up
  to fill the screen is worse than leaving the margins on.

Both toggles are remembered as the default for the next book. Fit-to-width is on out of the box;
cropping is not, because it is a guess about somebody else's scan.

---

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
