# Vox TXT — notes for whoever works on this next

A TTS reader for documents and web pages, aimed at Google Play. The user documentation is in
`docs/manual-bg.txt` (behaviour) and `docs/features-bg.txt` (list). This file holds only what those two do
not say: decisions that look arbitrary from the code, and traps that have already been fallen into once.

**Accessibility is built in, but this is not an app only for blind people.** It is for anyone who wants a
clean, light and capable reader of documents and web pages, and it is described that way in the README. What
follows about screen readers, contrast and announcements is how that is achieved and who tests it - not who
it is for. A change that would make the app worse for a sighted reader is not excused by being good for a
screen reader, and the reverse holds just as firmly.

The user writes and expects replies in Bulgarian.

**Never write "ѝ" - the i with a grave accent. Write "й" instead.** Neural Speechlab reads it as an
unknown character, so it reaches him as noise in the middle of a sentence. This holds everywhere: the app's
Bulgarian strings, the two documents in `docs/`, and anything written to him in Bulgarian.

**Never answer him with a table.** A grid of columns arrives through a screen reader as a stream of
disconnected cells and he has to rebuild the shape in his head. Say it as a list, or as several short lists
under headings of their own - one list per case rather than one table across all the cases.

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

## Writing things down, briefly

**Commit messages and changelog entries are short.** A commit body is at most five sentences; a changelog
entry is one sentence, two at the outside, and the published Beta 3 notes are the measure of it. Reasoning,
history and what was weighed against what belong in this file and in the two documents above - that is what
they are for. He reads every line through a screen reader, and an essay where he expected a sentence is not
thoroughness but noise; he has asked for this twice.

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

## The visual system

**A colour is written in colors.xml and nowhere else.** The app has a theme setting of its own, and Android
resolves resources by the phone's setting rather than by ours, so the colours were once kept a second time
as literal numbers in `appColor`. A colour changed in one place then reached only the users whose theme was
left to the system. `appColor` asks a context told which mode is in force instead. The three colours in
AppThemeLight and AppThemeDark stay written out, because the window is dressed before any code runs; they
are plain white and black and have never moved.

**A colour borrowed from elsewhere in the app is measured against the page it will land on, first.** Twice
now a colour that works perfectly where it lives has failed where it was moved to. `highlight` is #FFD54F in
both themes and is a background with black ink on it; written as text on the light theme's white page it is
1.41 to 1, which is nothing at all. `button_bg` is a fill with white on it; the dark theme's #125AAD written
on black is 3.09 to 1. `select_action` is what the marking mode uses instead: #002171 for light, the fill
colour itself at 14.42 to 1, and #1E88E5 for dark at 5.71 to 1. On black, deeper means dimmer -
contrast there is luminance and nothing else - so the two themes are asked for the opposite thing by the same
words, and #00154D and #1E88E5 are where that lands.

**Asking for accessibility focus is not the same as getting it.** A view that has not been laid out accepts
`ACTION_ACCESSIBILITY_FOCUS` and does nothing with it, and the reader then announces a control it is not
standing on - which is worse than not moving, because nothing about it looks wrong. `focusHeading` checks
`isAccessibilityFocused` afterwards and asks again if it did not take, up to three times, and stops the moment
it has taken so that a reader who has moved on by hand is never pulled back.

**A disabled Button needs a `ColorStateList`, not a colour.** `button()` was given one flat colour for the
fill and one for the lettering, so `setEnabled(false)` changed nothing that could be seen. The lettering fades
and the fill stays: greying the fill as well was built, shown to the user and taken out again - a row of grey
slabs stops reading as a row of buttons.

**The app does not take the focus back after a dropdown, and should not be made to again.** It did, on a
delay, and the delay was the problem: too short and it cut across what was being said, too long and the reader
had moved on. Varying the wait and retrying was built, tried on the phone and taken out - it made the
behaviour less predictable rather than more, and sometimes said everything twice. `focusHeading` is now one
wait and one attempt, used only where a page was rebuilt under the reader's feet, and it never asks for the
control the reader is already standing on: that is not a move, but the reader reads the control out again
for it.

**A sleep timer counts listening, not time.** `sleepHeldMillis` is what is left of one that is not counting
because the reading is not running; `pause()` holds it and `play()` arms it again, at the end of `play()`
rather than the start, so a Play that could not get the sound does not start it counting over silence.

**A list that can delete one entry can delete many, and all three do it the same way.** Bookmarks, recent
documents and recent pages share `selectionModeButton`, `markRow` and `selectionActions`. What is marked lives
in `markedItems`, keyed by what already identifies the entry, because these pages are rebuilt from storage on
every change and a tick kept in a checkbox would be thrown away with it. `leaveSelection` is called on the way
out of a page and on a change of tab; a mode left armed would meet the next list with somebody else's ticks.

**Ask the platform before writing a number down.** `systemDimension` and `systemTextSize` in `MainActivity`
read the theme, and four measures come from there: the height of a list row and of a menu row, the side
padding of a list row, the padding inside a dialog, and the size of text that names a control. The values
Android returns are the ones this app already used, so nothing moved when they were converted - the point is
that they now follow the phone rather than a note made here once.

The same goes for behaviour: dialogs are the platform's `AlertDialog` with its own background and its own
buttons, the More menu is `setItems`, and the touch highlight on a row is `selectableItemBackground`. Two of
these were questioned and the user chose the platform both times - the grey dialog background and the small
capitalised CLOSE stay as Android draws them.

**Three departures, each deliberate.** Every button is 56dp where the platform says 48; the player row is
80dp with 40dp symbols, which the platform has no opinion about; and the text runs on five steps - 28 for the
app name, 24 for a heading, 20 for a file name or a field, the platform's medium for anything that names a
control, and one step below it for a button sharing its row - where the platform offers three. All three
exist because the system measures are drawn for someone who can read the screen.

**Spacing is 4, 8, 16 and 24 and nothing else**, which is the Material scale. Space is given at the top of a
control and never at the bottom: giving it at both ends is what left one pair of controls six points apart
and another twenty-two. Two buttons standing one on the other touch - the gap belongs between different
kinds of thing, not between two of the same thing.

**A label and its control are built by `labelled` and `labelledWithValue`, never by hand.** Every control
keeps some width to itself - a slider needs room at both ends for its thumb, a button's background is drawn
with empty space inside its edges - and those amounts differ, so the edges that can be seen did not line up.
`contentInset` asks a slider what it keeps and `field` moves everything else in by the same, in margins, so
nothing's own inside is touched. Two numbers are deliberately off the scale and were found by looking, not by
arithmetic: 10dp between the two menu buttons where the rows stand 12 apart, because a tall narrow gap reads
wider than a long thin one. Both times the numbers were evened up instead, the screen looked worse.

**A button has the same air above and below its lettering whether that is one line or two.** `breathe` works
it out rather than taking a number: it is what the 56dp minimum already leaves around a single line, so a
one-line button is unchanged and a wrapped one is the same button grown by a line. Two earlier tries both
failed the same way. Zero vertical padding, which `compactButton` had, was invisible on one line and put the
letters against the edges on two. A share of the text size was no better in the end: on one line the 56dp
minimum did the deciding and left 18 points of air, on two the text filled that 56dp by itself and only the
padding was left, which came to 8. Any helper that sets a button's padding afterwards must call `breathe`
again, because it reads the font metrics and `compactButton` changes the text size.

List rows are not buttons in this sense - `listRowButton` keeps its own dp(14) and the platform's row height -
and they lose air the same way when a long file name wraps, 21 points down to 14. Left alone deliberately:
matching them would add twenty points to every wrapped row and a list of long names is long enough already.

**Two buttons in a row take the height of the taller; a label and a button in a row do not halve it.** Both
are fixed rules with nothing measured, and both are there for a reason that survives the sizes being fixed:
"Recent files" needs two lines in Bulgarian and would otherwise stand twenty points taller than "More", and a
long sentence count needs more than half a row while "Navigation" needs less. `addSideBySide` is for two
buttons and `addLabelAndButton` for the other case - two plain methods rather than one that asks what it has
been given.

**There is no text size setting, and putting one back means putting the measuring back with it.** Two sliders
existed - the interface as a percentage and the reading in points - and they were taken out in 1.1 along with
everything that had grown up around them: `uiSize`, the live preview that rescaled every view, and four
helpers that measured a row and decided whether it still fitted. At their far ends the first screen rearranged
itself, a heading was squeezed into a column three words wide and a button stood at twice the height of its
neighbour, and each of those wanted a rule of its own. `DOCUMENT_TEXT_SIZE` is 24 and every other size in the
app is the platform's. The keys they wrote, `interface_scale` and `font_size`, are cleaned up in `onCreate`.

Anyone bringing them back should know what they cost: not the slider, but every row in the app having to work
out where it goes.

**A slider is drawn at the thickness it is given.** `thicken` pins the height of both layers, because a
progress drawable is otherwise stretched to fill its row, and the same two lines of code produced a fatter
bar on the player than in Options.

**The reading scrolls in whole lines.** Backing off a fixed number of points left a fraction of a line
showing along the top edge; `LINES_ABOVE_SENTENCE` counts lines instead.

**Nothing is drawn until the book is in place.** `holdFirstFrame` refuses the first frame, which is what keeps
Android's starting icon on the screen, and `markReady` lets it through. Ready is the last line of `finishLoad`
and not the first: called first, it queued the frame ahead of the scroll onto the sentence, and the reading
was seen sliding up after the icon had gone. The first scroll is a jump rather than a glide, the layout
transitions are attached only after that frame, and a book that will not open releases the screen anyway
after `FIRST_FRAME_LIMIT_MS`.

## Do not resurrect

Features that existed, were built, and were removed after the user tried them. Re-proposing them is a step
backwards, not an idea.

- **Chapter detection.** Written, tested against four real Bulgarian books, removed: front matter and
  "Recognition and editing" lines were indistinguishable from chapters.
- **Estimated elapsed and remaining time, learned from listening.** Tried twice. The first attempt averaged
  the sentences it had heard and the estimate moved by minutes every few sentences. The second was worked out
  properly on the real books and turned down on the numbers: a document of an hour or two lands on the right
  ten-minute step almost always, but a book of fifteen hours lands on it about half the time and one of sixty
  hours hardly ever, and the more unevenly the engine speaks the more often the number wants to correct itself
  in front of the reader. Two things the deliberate measurement has cannot be had this way - its two hundred
  sentences are spread through the whole book rather than taken from the opening, and it measures the size of
  a written file rather than the timing of real speech, so it has no jitter to fight. Fitting a line to heard
  sentences also needs the odd ones set aside, and setting them aside pulls the answer low, because a stumble
  makes a sentence slower and never faster.
- **Speed tables per voice, in words or characters a minute.** The engine-independent form of the same wish.
  Every engine answers a change of rate in its own way, so a table would have to hold an entry for each engine,
  each voice and each speed, and the app has to work with voices nobody here has heard. What the deliberate
  measurement fits is that same number, learned from the voice in front of it instead of looked up.
- **Start and end sounds.** Removed with their option. The end of a book is spoken instead.
- **PDF and legacy .doc.** PDF was dropped because ML Kit has no Cyrillic at all, so a scanned Bulgarian book
  would need Tesseract with downloaded language data. Note for the future: Android itself gained PDF text
  extraction in API 35, reaching back to API 30 through `PdfRendererPreV` — if PDF ever returns, that is the
  route, not a bundled library.
- **Fade approaches for the sleep timer.** DynamicsProcessing and Equalizer were both tried and both sounded
  wrong to the user. The linear device-volume fade is the one that works; leave it alone.
- **Showing the voice sliders as a multiplier instead of a percent.** Weighed and turned down. The ranges
  already match what Android uses for its own settings - rate 0.1 to 6, pitch 0.25 to 4 - so the whole
  range is there, and the user finds a percent of the slider easier to hear than "1.3 times". That normal
  speed falls at 15 per cent of the travel is how the platform slider behaves too, and is not a fault.
- **Switching voices inside Neural Speechlab.** Proven impossible by decompiling that engine. Not our bug.

## Reading a document, and what it may cost

**The reading is shown through a window, not all at once.** `WINDOW_REACH` characters each side of the
sentence, cut at a line break, moved when the reading comes within `WINDOW_EDGE` of an edge. Android lays out
every character it is given, and a three-million-character book took seconds to lay out - once on opening and
again on every rebuild of the screen, which is what froze the app on returning from a page. The window keeps
the text view at sixty thousand characters whatever the book. The highlight and the scroll work in
window-relative positions; everything else - search, bookmarks, contents, navigation - still works on the
whole text. The one visible loss is that scrolling by hand reaches the end of the window, not the end of the
book.

**Two refusals, and only two.** Over fifty megabytes on the storage: the file is too big. Over `MAX_TEXT`
characters of reading: the document is too long. `MAX_UNPACKED` is a stop against a made-up archive, not a
limit on books, and is never explained to the reader. Do not add a third measure - the size of a file says
almost nothing about what it costs, which is the whole reason there were once three.

**An archive gives up only what is read.** `worthKeeping` is why a forty-megabyte manual of photographs opens
in a tenth of a second and why an EPUB carrying recorded narration costs nothing: pictures, fonts, sound, film
and stylesheets are counted past without being held.

**Running out of memory is an Error, not an Exception.** It passed a plain `catch` by and took the app down.
Both document-loading paths catch it and say the document is too long.

## Contents, and how a heading is recognised

**Only what a document declares is a heading.** FB2 nests its sections, EPUB carries a table of its own, DOCX
says it in the style. Nothing is guessed at from bold text or capitals, and eleven of the forty real manuals
correctly have no contents at all despite looking full of headings.

**In DOCX the style's own declaration is the answer, never the name it is filed under and never what it is
built on.** Real manuals file heading styles as "1", "21", "Style37"; "Heading10" and "Heading11" are what
Word writes for a renamed Heading 1, not levels ten and eleven; an outline level of nine means body text and
is how a table-of-contents heading keeps itself out. Following `basedOn` looked right and was wrong: RUBY 10
sets its front matter in a style built on Heading 3, and inheriting turned a page of copyright text into three
entries of the contents.

**Failing to read the contents must never stop a document from opening.** The words are the point; the list of
parts is a convenience on top of them.

**Do not ask jsoup for an element whose name carries a colon.** `selectFirst("> w\\:pPr")` works on the
desktop and failed on the phone, and every DOCX stopped opening. The children are walked by hand instead.

**A press in the contents both chooses and opens, and each announced itself.** On a row that opens something
only the opening is said; a row that opens nothing says "selected" in the platform's own words. The triangle
is drawn for the eye and its changing is never read out. The page is built once and only added to and taken
from - rebuilding it was what made the screen blink and threw away the row the reader was standing on.

## Opening a file, and keeping the right to

**The picker intent must carry `FLAG_GRANT_READ_URI_PERMISSION | FLAG_GRANT_PERSISTABLE_URI_PERMISSION`.**
Without them the grant lives only as long as the task, `takePersistableUriPermission` is refused, and a book
opens once from the picker and then says "unsupported content" from Recent files ever after. The refusal was
swallowed, so nothing said what had happened. A permission that cannot be read is `file_unavailable`, not
`unsupported_content`. `forgetBook` gives the grant back, because Android keeps only so many.

**A shared file arrives as `EXTRA_STREAM`, never as `EXTRA_TEXT`.** Share was written for a browser sending
an address, so it read the text and nothing else, and a book shared from a cloud drive was answered with "no
web address was shared" - the message was honest and the code was looking in the wrong place. A file now wins
over text when both are sent, and goes to the same `loadUri` that Open with uses. The other half of that bug
was in the manifest: the SEND filter declared `text/plain` alone, so the app was not even offered for a DOCX
or an EPUB. Its types are now the ones the VIEW filters accept, and must be kept in step with them.
`SEND_MULTIPLE` is deliberately not declared.

**Open with can match a file by its name; Share can only match it by its type.** An `ACTION_VIEW` intent
carries the file as its data, so the second VIEW filter matches on `pathPattern` and rescues a file whose type
the sender got wrong or did not know. An `ACTION_SEND` intent carries the file in `EXTRA_STREAM`, which an
intent filter cannot look at, so the declared MIME types are the whole of it. That asymmetry, not a missing
type, is why a format can arrive through Open with and be absent from the share sheet.

Checked against the tables `MimeTypeMap` is actually built from - AOSP `external/mime-support/mime.types` and
`frameworks/base/mime/java-res/android.mime.types`. They give `text/plain`, `application/epub+zip`,
`application/zip` and the OOXML wordprocessingml type, all four declared here. **FB2 is in neither table**, so
`getMimeTypeFromExtension("fb2")` is null and a sender falls back to `application/octet-stream`, which is why
that one is declared and must stay. `application/x-zip-compressed` is declared because a downloaded file keeps
the type its server sent and many servers send that; Android itself never produces it.

**`application/txt` is declared because Samsung and Xiaomi hand it out for a shared .txt.** It is not a real
type and is in none of Android's tables, and because it is not under `text/`, `text/*` never caught it. TXT
was the one format missing from the share sheet on those phones while the other four were there - the others
either use a standard type or fall back to `application/octet-stream`. Open with was never affected, which is
the asymmetry above at work: it can fall back to matching the file name.

**Falling off the end of the list means the same as being removed by hand.** `addRecent` calls `forgetBook`
on whatever it pushes past `RECENT_LIMIT`, so there is one description of being rid of a document rather than
two that drift apart. Before that, an entry that simply aged out left behind its position, its bookmarks, its
measured timings and - the one that mattered - the persisted permission to open its file. Android allows an
app 128 of those on Android 10 and older and 512 after; a book that cannot take one opens from the picker and
then reports itself unavailable ever after, and the refusal is swallowed.

**What was read out of a document is kept for every document in the list**, in `doccache/`, and the
rule is the same as for the page cache: the file is the truth, the copy stands in for it. Size and modified
time decide whether the copy is still the file; only an unreachable file is read from the copy regardless.
Kept because a document handed over by another app has an address good for one moment, and because opening a
known book again should not mean unpacking and parsing it a second time.

## Settings keys — the sharpest trap

Settings live in `reader_settings`. **The document profile uses unprefixed keys and the web profile prefixes
them with `web_`.** The unprefixed names are the same ones Beta 3 shipped, which is why upgrading needs no
migration. Renaming any of them silently resets everyone's voice and speed.

**Never change the stored type of a key. Use a new name instead.** `keep_screen_on` was a boolean for one
build and then became a word; reading a string from a boolean throws, and the app crashed on opening Options
for anyone who had the earlier build. It now stores under `keep_screen`, and `MainActivity.onCreate` holds a
cleanup of that key and of `player_armed`, to be deleted after 1.0 - a cleanup has to ship in the release
that meets the phones carrying the old key, and only then can it go.

`nav_unit_plain` holds the last unit chosen that every document can offer, so a book read by paragraphs is
still read by paragraphs after a book read by sections. `nav_unit` alone could not say it.

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

## The sleep timer, and what reaches the reading from outside

**The timer never cuts a sentence and never touches the volume.** It marks that the time is up and the stop
happens where the sentence ends. The ten-second fade of the device volume that 1.0 shipped was removed in
1.1: it cut a sentence in half and had to step back a sentence to put it back, it borrowed the volume of the
whole phone and had to give it back even if the process was killed mid-fade, and Do Not Disturb could refuse
it the change halfway through. `restoreVolumeAfterCrash` and `setMusicVolume` are all that is left of it, for
the phones that carry a stored `fade_volume` from an earlier build; delete both after 1.1.

**The button under the player belongs to the timer and only changes what it says.** The timer runs out
several seconds before the reading stops, because the sentence is allowed to finish. A row that emptied at the
first of those moments and filled at the second sent the player down and back up in front of the reader.
`isStoppingAtSentenceEnd` is what lets the row hold still through the gap, and `shownTimerMinutes` is what
lets it go on saying the same thing if the screen is rebuilt inside it. The only movement left is the return
button arriving underneath.

**Where the timer will return to is taken when it is set, not only at the next Play.** `captureSleepStartOnPlay`
alone meant a timer set over a book already reading had no starting point, and "Return by X minutes" never
appeared when it ran out.

**`PENDING_CATEGORY` is not a note about a rebuild. It is which settings page is open.** Written when one
opens, cleared when one closes, and true for exactly as long as the page is on the screen - which is what
makes a rebuild reliable rather than lucky. It survives one rebuild, two rebuilds, or a restart by any route,
and needs no timer and no guess at how long a rebuild takes. The three attempts before this all failed in the
same way: they tried to describe the rebuild instead of describing where the reader was. It lives in the
settings and not in the instance-state bundle because changing the language below Android 13 restarts by a
route that hands no bundle on.

The note is only honoured by a screen that was rebuilt, and `processAlreadyRunning` - a static, so it dies
with the process and no sooner - is what tells a rebuild from a fresh start. Nothing else can: `onDestroy`
was asked first and answered wrongly, because `keepReadingAfterFinish` is left true by the very rebuild that
set it, so an app swiped out of Recents from a settings page came back onto that page days later. A lifecycle
callback that may or may not run is the wrong thing to hang this on; whether the process is new is a fact.

**A theme change rebuilds the screen and cannot simply repaint it.** The two themes are built on different
platform parents - Material Light and Material - and everything the platform draws for this app comes from
there: the dialogs, the dropdown popups, the touch highlight on a row. `setTheme` over a window already
dressed merges rather than replaces, so those would keep the look of the theme being left.

**Whatever comes back on start, the settings page comes back after it.** `onCreate` had three ways in and two
of them returned outright, so a theme chosen with a web page open landed back on the page. The restore paths
are one branch now and nothing returns past the block that reopens the page.

**Play at the end of a document goes back to the beginning, and says so.** Only the end reached by reading
counts - `reachedEnd` - so walking to the last sentence by hand is unaffected. The announcement is an
utterance like any other and every way it can end, including the engine taking it and saying nothing, starts
the reading anyway: an announcement that failed is never a reason to leave Pause showing over silence.

**Every Play that comes from outside goes through `playFromOutside`, and nothing else does.** The guard for
"Prevent automatic playback" sat in `MediaSession.Callback.onPlay` alone, and a headset, a car and a watch all
send a key event, which `onMediaButtonEvent` answers first - so the setting stood in a doorway nobody used.
Play in the app and Play in our own notification call `play()` straight through and must never be refused.

## Reading a sentence aloud

**The filter for decoration works on a copy of one sentence and nothing else.** `speakable` is applied to what
is handed to `tts.speak` and to nothing that is stored. Every offset, the highlight, search, the contents and
the bookmarks describe the text as it was written, and they must go on doing so.

**Whole Unicode categories, not a list of characters.** A list only covers documents somebody has already
opened. Symbols, math signs, modifiers and the connector punctuation go; standard punctuation, currency and
digits stay. Two exceptions had to be named by hand: `№` is filed as a symbol but is a word in both
languages, and the invisible characters - a soft hyphen, a joiner - are removed without a space in their place,
because a space there splits a word.

**Never hand the engine an empty string.** Some engines answer one with silence and never report it finished,
and the reading stops there for good. `skipSilentSentence` moves on instead, and honours a timer that has run
out exactly as a spoken sentence would.

**Which unit Previous and Next work in is decided in the service, by `navUnitInForce`.** A press can arrive
from the lock screen, a headset or the notification when no screen of the app exists, so the rule cannot live
where the buttons are; `MainActivity.navUnit` asks the service for it, so there is one rule rather than two
that drift. Every way in - the notification actions, the session callbacks and the media keys - goes through
`navStep`, and none of them may call `move` directly again: `move` is sentences, and sentences are only one of
the three things Next can mean.

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

**Build and hand over an APK before a piece of work is called finished.** The whole DOCX heading reader was
written, verified against forty real manuals on the desktop and reported as done without once running on a
phone; the jsoup selector above then broke every DOCX, and it took a round trip to find. Desktop verification
says the logic is right, not that the app works.


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
