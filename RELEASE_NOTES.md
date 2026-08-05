## LightBooks v1.7 — Your place in every book, backed up

**Reading positions, bookmarks and the shelf itself now go to LightSync, so a wiped or replaced
phone gets your books back where you left them. Under that, the wheel and shake-to-report have
moved out of this app and into the shared Light library, and release builds are shrunk.**

### Your place in a book survives the phone

LightBooks now answers LightSync. Back it up and you get the shelf — every book's title, author,
chapter table, bookmarks, and the exact word you stopped on — plus your reading settings, as two
separate things you can restore independently.

What does *not* go is the books themselves. The extracted text of a novel is megabytes, and you
still have the EPUB you imported it from; the covers are all derived, read out of the book file
or fetched from Open Library or drawn from the title. So a restore puts your shelf back with
every position intact, and you re-add the files. That is the honest trade: backing up nightly
copies of files already sitting in your downloads folder is not worth the space on the server.

Nothing here runs on its own. LightSync has to ask, and it is the only thing that can — the
provider checks the caller's package *and* its signing certificate on every single call.

### The wheel and shake-to-report now come from one place

Both used to be files pasted from app to app, which is how they drifted: a fix to the wheel's
idle window landed in three apps and not in this one. They live in `light-common` now, along
with the crash handler and the report sheet, so a fix reaches every Light app at once.

You should not be able to tell. The one place there was a real risk is page turning: the shared
wheel banks notches and paces them out, which is right for moving a selection down a list and
wrong for an ereader that already counts its own notches per page. It is explicitly turned off
here, so a turn of the wheel still turns a page exactly as fast as it did before. If page turns
feel laggy or a deliberate turn skips two pages, that is the thing to report — shake the phone.

### Release builds are shrunk now

R8 minification and full mode are on for the first time. Smaller download, and a faster cold
start on a phone that is slow at it.

This is the change most likely to break something, and it breaks things in a specific way: code
that is reached by name rather than by a call can be removed or renamed without the compiler
noticing. The shelf and the settings are stored as JSON keyed by name, so those have explicit
rules and the enums inside them are pinned by constant name — a mistake there would show up as
settings resetting to defaults or a book losing its position after an update, not as a crash.
Both parsers are regex-based and need nothing.

Crash traces stay readable: line numbers and file names are kept on purpose, because a shaken
report with an obfuscated stack is not a report.

### Android 10 or newer

The minimum Android version goes from 5.0 to 10. The shared library is built against 10 and
uses APIs that do not exist below it, so pretending otherwise would only move the failure from
build time to a crash on launch. The Light Phone III runs Android 14, so this changes nothing
on the phone; it only drops an old spare device you might have been sideloading onto.

### If it does go wrong

`applicationId` and the signing key are unchanged, so this updates in place over v1.6 and your
books and positions come with it. Downgrading to the v1.6 build in Releases is a normal install
if this one misbehaves.
