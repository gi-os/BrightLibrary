## LightBooks v1.6 — Covers that actually get looked for, and a name you can fix

**Two fixes to the same problem: a cover search that gave up quietly, and no way to tell it what the
book is called.**

### The searching

A cover lookup only ever happened once, during the import, in a coroutine belonging to the shelf. So
if you imported a book with no signal, or left the shelf before the request came back, that was the
only attempt the book ever got — and a search that came back empty left no trace, so nothing knew to
try again. That is the whole of "the covers sometimes never search".

Now the shelf sweeps for books that still have no cover every time it opens, one at a time, skipping
anything it asked about in the last 12 hours. Every attempt is recorded whether it succeeds or not,
which is what makes the back-off possible. Books already on your shelf without covers get picked up
on the first open after this update.

The search itself was also too literal. Open Library's `title=` is matched closely, and ebook
metadata is not catalogue metadata: titles arrive as `dune_messiah.epub`, or with `(Z-Library)` glued
on, or carrying a marketing subtitle; authors arrive as `Herbert, Frank` or as three names separated
by semicolons. Any of those returns an empty result, which reads exactly like a search that never
ran. So the query is cleaned first — filename underscores, bracketed suffixes, surname-first authors,
contributor lists — and then widened rather than abandoned: full title with author, title without its
subtitle, title alone, and finally a fuzzy search. A failed request is no longer the end of the
whole lookup either, and if a cover has no large size uploaded it falls back to the medium one.

### Fix the name

Long-press a book: **FIX NAME**, under FIND COVER because it is what to try when that keeps failing.
Correct the title and the author, and saving searches again immediately with what you typed. The
title comes up selected, since the usual edit is replacing the whole thing.

That also fixes the shelf caption for anything that imported with a filename where a title should be.

Text entry needs a keyboard: LightOS's own is an in-app component rather than a system input method,
so this is a field LightKeyboard fills.

---

## LightBooks v1.5 — It's a bookshelf now

**The app is renamed, rebuilt on the real Light Phone SDK design language, and reorganised around
reading pages. FastRead is still here; it is a button rather than the whole app.**

### Renamed to LightBooks

The launcher says **Books**, the release titles say **LightBooks**, and shake-to-report signs its
issues LightBooks. Deliberately nothing else: the repo is still `gi-os/LightFastread`, the published
APK is still `LightFastread-<version>.apk`, `applicationId` is still `com.lightfastread`, and the
signing key is unchanged. So this updates in place over your existing build with your books and your
place in them intact — no uninstall, and your Obtainium source keeps working exactly as it is.

The name had stopped being accurate. Fast reading is one mode of an ebook reader now, not the point
of the app.

### It looks like LightOS, not like Android

Every screen has been rebuilt on a port of `lightphone/light-sdk` (MIT, credited in
`LICENSE-light-sdk`) rather than on Material 3 with a black palette painted over it:

- **The 27 x 31 grid.** Bar heights, insets and icon sizes are fractions of the screen, the way
  LightOS keeps its proportions on a 3.92" panel. No fixed dp anywhere in the new chrome.
- **The named type scale**, scaled against a 600px baseline. Text is chosen by what it *is* —
  Copy, Detail, Button, Superfine — rather than by how big it should look.
- **The real icons**, from the SDK's own set. The back chevron is the phone's back chevron.
- **No ripples.** A tap is a 45ms buzz and no visual splash, which is what LightOS does.
- **Three colours**, with selection shown by `[ brackets ]` rather than a change of shade: on a
  matte greyscale panel a shade change does not read at arm's length.

Gone with it: the floating action button, the tonal-elevation cards, Material's switches, radio
buttons, filter chips, dividers, alert dialogs and spinning progress circles. Sliders survived —
LightOS has no slider, and a numeric stepper for words-per-minute would be worse — restyled to a
white thumb on a hairline track.

### The library is a shelf

Two covers to a row, each about 180dp on the LP3, with the title and author under it and your
progress as a hairline directly beneath the cover.

Covers come from the book itself first: LightBooks now reads the cover image out of an EPUB (all
three ways an EPUB can name one) and out of a MOBI's EXTH records. If the file carries no art, it
asks Open Library by title and author. If that misses too, the book gets a typographic cover — the
title set large over a rule with the author beneath — which on a monochrome shelf says far more than
a grid of identical book glyphs would. Long-press a book for **FIND COVER** if you want it to look
again, or **DELETE**.

Add and Settings moved into a LightOS action bar along the bottom of the shelf.

### Covers in colour

The Light Phone III's panel is a full-colour AMOLED — the black and white is Android's daltonizer
pinned to monochromacy — so the shelf lifts it while it is on screen and drops it the moment you open
a book. Cover art in colour, reading in black and white, and nothing else on the phone touched.

One grant, once:

```
adb shell pm grant com.lightfastread android.permission.WRITE_SECURE_SETTINGS
```

Without it the covers simply stay grey; nothing breaks. There is a **Colour covers** toggle in
Settings if you would rather the phone stayed mono, and the grant command is printed there too.

### A book opens to its pages

Tapping a book used to land you in the RSVP word reader, with the page view hidden behind a tap on
the context preview. That is the wrong way round for an ereader, so it is inverted:

- Opening a book goes **straight to the page view**, full width, with no chrome over the text.
- Tap the screen and a top bar appears for four seconds: **back** on the left to the shelf,
  **FASTREAD** on the right to hand the book to the word reader at the page you are on.
- Back out of the word reader returns you to the pages, not to the shelf. The pages are the book's
  home screen now.
- The page counter at the bottom stays, and the wheel still turns pages.

Pagination is measured and cached exactly as before, and now reports progress as a filling rule and
a percentage instead of a Material arc.

---

Everything else is where it was: the gesture model, both parsers, the chapter list, bookmarks,
shake-to-report, and every setting including the ones the greyscale panel makes moot.
