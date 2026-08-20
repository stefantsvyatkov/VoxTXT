# Vox TXT — notes for whoever works on this next

A TTS reader for blind and screen-reader users, aimed at Google Play. The user documentation is in
`docs/manual-bg.txt` (behaviour) and `docs/features-bg.txt` (list). This file holds only what those two do
not say: decisions that look arbitrary from the code, and traps that have already been fallen into once.

The user writes and expects replies in Bulgarian.

**He uses Jieshuo, not TalkBack.** TalkBack comes out only for the occasional test, so advice that assumes it,
or fixes aimed at its particular quirks, are aimed at the wrong reader.

**He has some sight, and what it does and does not reach decides most of the visual design.** Colours, large
shapes and the main shades come through. Text does not: it arrives as broken lines, so every word in this app
reaches him through the screen reader. The drawing inside an icon does not either - Play is a triangle and
that is as far as it goes; the rest of the player row he knows by position and by what is read out.

Three things follow, and they are worth holding on to:

- **Colour, size, fill and position carry information to him. Text and iconography do not.** The open tab is
  filled rather than merely bold for this reason, and replacing a written label with a neat icon would be a
  straight loss.
- **He is the one who finds the visual faults**, because nobody else is testing. Rows that had turned white on
  white, a Play button flickering through a seek, a slider whose filled half could not be told from its track
  - all of those were reported from these eyes and none of them could be seen from the code or heard through
  the screen reader. Screenshots are worth asking for.
- **Contrast is not a preference here.** Anything done to make the app look better has to leave it where it is
  or raise it, and the numbers are worth checking rather than guessing at.

## Shape of the app

Two Java classes carry almost everything: `MainActivity` (all UI, built in code) and `ReaderService`
(playback). `ArticleReader`, `DocumentText` and `HiddenPageLoader` were added for web pages and book formats.

**There are no XML layouts and no Compose.** Every screen is assembled by hand. This is deliberate and is why
the APK is under a megabyte; a comparable Compose app in the same field ships eleven. Do not "modernise" the
UI layer without the user asking for it. Measured, in this project: adding Material Components alone takes the
APK from 937 KB to 4806 KB without a single line of the app changing.

The light theme is pure black on white and the dark one pure white on black, on purpose. That is the highest
contrast available, which is what a reader with partial sight actually needs; the tonal greys that modern
Material themes hand out by default are a step down for him. Anything done to make the app look better has to
keep the contrast where it is.

**Dependencies are jsoup and Readability4J, and that is all.** Adding a library is a real decision here.
Already weighed and rejected: Crux for metadata (drags in OkHttp, coroutines and a JSON library), Apache POI
for legacy .doc (about 5 MB plus an XML-parser workaround), PdfBox-Android for PDF.

## Do not resurrect

Features that existed, were built, and were removed after the user tried them. Re-proposing them is a step
backwards, not an idea.

- **Chapter detection.** Written, tested against four real Bulgarian books, removed: front matter and
  "Recognition and editing" lines were indistinguishable from chapters.
- **Estimated elapsed and remaining time.** The estimate moved by minutes every few sentences. Without an
  audio stream there is nothing stable to measure.
- **Start and end sounds.** Removed with their option. The end of a book is spoken instead.
- **PDF and legacy .doc.** PDF was dropped because ML Kit has no Cyrillic at all, so a scanned Bulgarian book
  would need Tesseract with downloaded language data. Note for the future: Android itself gained PDF text
  extraction in API 35, reaching back to API 30 through `PdfRendererPreV` — if PDF ever returns, that is the
  route, not a bundled library.
- **Fade approaches for the sleep timer.** DynamicsProcessing and Equalizer were both tried and both sounded
  wrong to the user. The linear device-volume fade is the one that works; leave it alone.
- **Switching voices inside Neural Speechlab.** Proven impossible by decompiling that engine. Not our bug.

## Settings keys — the sharpest trap

Settings live in `reader_settings`. **The document profile uses unprefixed keys and the web profile prefixes
them with `web_`.** The unprefixed names are the same ones Beta 3 shipped, which is why upgrading needs no
migration. Renaming any of them silently resets everyone's voice and speed.

**Never change the stored type of a key. Use a new name instead.** `keep_screen_on` was a boolean for one
build and then became a word; reading a string from a boolean throws, and the app crashed on opening Options
for anyone who had the earlier build. It now stores under `keep_screen`, and `MainActivity.onCreate` holds a
cleanup of that key and of `player_armed`, to be deleted in Beta 6 - a cleanup has to ship in the release
that meets the phones carrying the old key, and only then can it go.

A settings file never tidies itself, and `reader_settings` is included in the Android backup, so an orphan key
travels to every future phone. `reader_documents`, `book_positions` and `sleep_rewind_state` are excluded from
the backup on purpose: the permissions to open those files are not restored, so a restored list opens nothing.

## Playback rules that were paid for in bugs

- **Two handlers in `ReaderService`, and the difference matters.** `speechHandler` carries what the app has
  scheduled (the next sentence, the settle after a seek, a retry) and may be cleared at will. `handler`
  carries what the engine has reported. Clearing the second one wholesale is what made a finished sentence
  get lost and spoken again. The rule: clear what you ordered, never what you were told.
- **`move()` must not report its own momentary stop.** It restores `playing` before `notifyState()`. Reporting
  the stop turned the Play button and the notification into a flashing light on every seek step.
- **An `onStop` arriving within a few hundred milliseconds of a sentence starting is our own stop**, not the
  engine failing, and must not trigger a retry. That mistake spoke the same sentence twice.
- **Fast seek pauses the reading deliberately** — otherwise every step starts a sentence it will cut off. The
  Play button is told separately to keep saying Pause throughout, because from outside it is one operation.
  Fast seek must stop at either end of the text, on a rebuilt screen, on destroy, and after a minute as a
  backstop; leaving it running once turned a book into one repeated word.
- **A step of the File progress slider must change the sentence.** In a twelve-sentence article one percent is
  worth less than one sentence, so rounding returned the same sentence and the slider looked stuck.
- **The engine belongs to whoever asked last, and losing it is not a failure.** A screen reader on the same
  engine takes the sentence away, and it arrives in three shapes: a stop, a refusal from `speak()`, or - the
  nasty one - the sentence being accepted and then never spoken, reported as nothing at all. All three go to
  `waitForEngine`, which waits and says the sentence again, patiently enough to outlast a long announcement.
  Treating any of them as a broken engine is what made the book stop dead with an error about the voice.
- **Nothing may leave `playing` true with no sentence speaking and nothing scheduled.** That state is silent,
  shows Pause over the silence, and only closing the book escapes it. It is the reason `speakCurrent` arms a
  timer on every sentence and `onInit` pauses on failure. When something in playback is changed, the question
  to ask of every path out is what is scheduled after it.
- **Leaving a subpage never questions what is open.** `closeRecent` used to ask whether the open document was
  still in the recent list, which threw away web pages and then shared text. Removing a book from the list
  already closes it if it is the one being read; nothing else needs asking.

## Accessibility rules the user cares about

- **What is written on a control is what the screen reader says.** No hidden content descriptions that differ
  from the visible label. This was corrected once and is not negotiable.
- **Bulgarian control labels use the nominal style** — Отваряне, Премахване, Прилагане, not Отвори, Премахни,
  Приложи. Whole sentences addressed to the user stay in the imperative.
- Tabs carry their state with `setSelected`, so the screen reader says "selected" by itself. Picking a tab
  leaves the focus on that tab, never back on the page heading.
- Fast seek announces nothing on release; slow seek announces the sentence only while the book is paused.
- The app never accepts a cookie or consent banner on the user's behalf. It refuses to load the consent
  tools and removes what the page has pinned over itself, and that is the whole of it.

## Testing without a device

Most of the risky logic is plain Java and can be run on the desktop. That is how the formats and the web
extraction were verified: compile the class against the jars in the Gradle cache and run it against real
files. Real Bulgarian books in every supported format can be fetched from chitanka.info for this; note that
its `/text/...` download paths refuse the request while the `/book/...` ones answer, and that it hands out
TXT and FB2 wrapped as `.txt.zip` and `.fb2.zip`.

Anything involving the hidden WebView, the tabs, the clipboard or the file pickers cannot be checked this way.
Say so plainly rather than implying it was tested.

## Build

`assembleRelease` names the APK from the version suffix (`1.0-beta4` gives `VoxTXT-beta4.apk`). There is no
gradlew on PATH; the wrapper is invoked through its jar. Minification is off, and the language split is
disabled in the bundle because the in-app language setting needs every language present in the base module.
