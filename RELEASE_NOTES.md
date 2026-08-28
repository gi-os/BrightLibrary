## LightBooks v1.18 — Volume down turns forward

**The volume keys turn the other way now.** Down is forward, up is back. v1.17 matched them to the
wheel, which means forward when it is rolled up; a book asked for the opposite in the hand. The lower
key is the one under the thumb at rest, forward is the press you make a few hundred times a book, and
every other e-reader with volume page turns — KOReader among them — already puts forward on volume
down. Agreeing with the wheel bought less than reading the way a book actually gets read.

Nothing else about the feature changed. It is still **Settings → Input → "Volume keys turn pages"**,
still off out of the box, still claimed only while a reader is on screen so the keys are volume keys
again the moment you are back on the shelf. One press is still one page — the key going down, first
press only, so leaning on the button does not autorepeat through a chapter. In a comic, forward is
still the next step down the page before the next page.

Both readers flip together: the direction lives in one function that the text reader's borrowed dialog
callback and the comic reader's Activity key stream both call, so there is no way for the two to
disagree.

**Still unverified on the device**, exactly as in v1.17: Light patched five scancodes into the keymap
for the wheel, its click, focus and camera, and volume is not among them. If the toggle is on and
pages do not turn either way, that is a keymap answer rather than a reader one.

---

## LightBooks v1.17 — The volume keys turn pages, if you ask them to

**Settings → Input → "Volume keys turn pages".** Off out of the box, on in both readers the moment
you switch it on: one press, one page. It is the way a page turns on every e-reader with buttons on
its case, and the way KOReader does it on a phone that has no wheel.

**It is opt-in because a key that turns a page is a key the system never sees.** Turning a page with
volume up means consuming the press — and a consumed press produces no volume slider and no change in
volume, for as long as a book is open. That is exactly what somebody who asked for this wants and a
phone that appears to have lost its volume buttons to somebody who did not, so the app takes them only
while the setting is on and a reader is actually on screen. The claim is dropped on the way out of the
book; back on the shelf the keys are volume keys again.

**Up went forward in this release, which v1.18 reversed** — see the v1.18 notes above; forward is
volume *down* from that build on. In a comic a press does what one settled turn of the wheel does: the
next step down the page, then the next page once the steps run out.

**The interesting part is that the two readers cannot catch the key the same way.** The Activity sees
every hardware key before the view hierarchy does — that is how the wheel has always worked — but the
text reader is wrapped in a `Dialog`, and a dialog is its own window. Nothing dispatched to that window
ever reaches the Activity's `dispatchKeyEvent`, which is why the wheel needed a `WheelInDialog` of its
own in the first place. So the page view borrows the dialog window's `Window.Callback` for as long as it
is composed, answers volume up and down itself, delegates every other key to the callback it replaced,
and hands it back on the way out. The comic reader, being a plain screen in the Activity's own window,
just claims the Activity's key stream instead. One composable decides which of the two it is by walking
its own view's parents, so both readers ask for this the same way.

A press is counted once per press: only the key going down, and only the first one, so leaning on the
button does not autorepeat through the chapter. Nothing goes near the wheel's notch accumulator — four
notches make a page turn there, which would have swallowed three presses out of every four.

**Volume keycodes on this phone are the one thing that could not be checked before shipping.** Light
patched five scancodes into the keymap for the wheel, its click, focus and camera; volume is not one of
them, and whether LightOS delivers `KEYCODE_VOLUME_UP`/`DOWN` to a focused app at all is only knowable
on the device. If the toggle is on and pages do not turn, that is the answer, and it is a keymap problem
rather than a reader problem.

Fixes [light-reports#55] and [light-reports#32] — volume keys can turn pages, like KOReader.
