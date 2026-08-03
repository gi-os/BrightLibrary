## LightFastread v1.4 — Shake to report, and the word loop runs on frames

**Two changes: the app can file its own bug reports, and the RSVP loop stops doing twice the work
the screen can use.**

### The word loop was ticking at 125Hz on a 60Hz panel

`runWordLoop` advanced on a fixed `delay(8)`. The Light Phone III's panel is 60Hz, so that woke
the coroutine roughly twice per frame, and every wakeup published a live WPM and could advance
the word — both of which write Compose state that nothing can draw until the next frame arrives.
Half of it was thrown away before it reached the screen.

It runs on `withFrameNanos` now: the same arithmetic, once per frame. The second effect matters
more than the first — a frame callback is not scheduled at all when the reader is not producing
frames, so a session left running in the background stops burning CPU on words nobody is reading.
`delay(8)` kept going regardless.

Reading speed is unchanged. Word timing was already derived from the measured interval rather
than assumed from the tick, so it follows the frame clock without drifting, and the ramp still
reads the wall clock because it is a ramp in seconds since you pressed. Punctuation pauses
re-baseline on the frame after the pause, so waiting does not come back as a burst of skipped
words.

### Shake the phone to report a bug

Shake twice — there and back, twice — and a sheet comes up. Pick what happened from five chips
and add a note in your own words. The note is optional but it is the part that carries anything,
and what you type becomes the title of the issue. The report brings the screen you were on, app
and firmware versions, free space, heap, and the stack trace if the app died last run.

Reports queue on disk before anything is sent, so a report survives the crash that prompted it.
