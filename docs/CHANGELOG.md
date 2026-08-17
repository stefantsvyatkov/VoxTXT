# Vox TXT — changelog

Requires Android 8.0 (API 26) or later.

## Changes in Beta 4

### New

1. The Options button became More. It opens a list with Bookmarks, Search and Options.
2. Bookmarks on any sentence, named after the whole sentence and kept separately for each book.
3. Search for a word or phrase. It continues from the current place and wraps around the end of the book. On a hit the book itself starts reading from the sentence found, in its own voice, exactly as if the reading had been started from that place. The dialog stays open above it with three buttons on one row — Previous, Next, Close — so another occurrence is one press away in either direction, and closing leaves the reading where it is.

### Changed

1. Clearing the app from Recents, Close all included, now closes it for good. The reading stops, the notification disappears and nothing is left running, whether or not a book was being read at that moment. The position is saved on the way out.

2. Entries in Recent files and in Bookmarks are plain text rows now, without the grey slab of a button behind them. They still highlight while the finger is on them and are still announced as something that can be opened.

### Fixed

1. A long name in Recent files was drawn past the edge of its button, with the last line left outside the background. Rows of a list now grow with their text.

## Changes in Beta 3

### New

1. A short sound at the start and at the end of the text, with a setting to turn it off in Options.
2. With the sounds off, the end of the book is announced by speaking "End of text."
3. Pressing Play after the end shows "No more text." instead of repeating the last sentence.
4. A "Preview voice" button on the voice page. It reads the current sentence of the book with the selected voice and the current slider positions, before they are saved.
5. Detection of older encodings: Windows-1251, Windows-1252, Windows-1250 and ISO-8859-7, as well as UTF-16 without a byte order mark. Older Bulgarian and Russian books now open as text instead of garbled characters.

### Changed

1. The maximum file size is 5 MB instead of 15 MB. An average book is under 1 MB.
2. Fast seek now keeps exactly the interval set in Options. It used to be slowed down by redrawing the document on every step, so the range is now 200 to 600 ms with a default of 400 ms, which keeps the familiar pace.
3. Seeking follows one rule now: while the book is paused the screen reader announces the sentence reached, and while it is reading nothing interrupts it. Releasing a fast seek during playback continues from the new place straight away, with no announcement and no wait.
4. A new installation now starts clean: no recent files and no saved positions. Neither can work after a reinstall, because the permission to open those files is not restored with them. Settings are still backed up and restored, including onto a new phone.
5. Changing the interface language also works when the app is installed from Google Play.

### Fixed

1. Opening a file from Recent files did nothing - The previous book stayed open.
2. The app was killed when Play was pressed on headphones after it had been stopped.
3. The sleep timer could leave the device volume turned down when Do Not Disturb was on, or when the app was force closed during the fade-out.
4. Reading large files stuttered — the whole text was redrawn on every sentence.
5. Dragging the File progress slider interrupted playback on every percent.
6. Voice names were shown as technical codes, and some voices appeared twice in the list.
7. Reading stayed stopped after a short interruption such as a phone call or a navigation prompt. It now continues on its own once the sound comes back, and only then — when another player takes over for good, the reading stays paused.
8. Reading gave up when the speech engine was killed by the system. The connection to it is now rebuilt once and the reading carries on from the same sentence.

## Changes in Beta 2

### New

1. A media button receiver for Play/Pause, Previous, Next and Stop from TalkBack, headphones, Bluetooth devices and the system controls.
2. A digitally silent local stream, through which Android recognises Vox TXT as the active media source without any extra sound being heard.
3. The option to return to the start of an expired sleep timer now survives a force close of the app, together with the file, the starting sentence and the length of the timer.

### Changed

1. Every slider shows its current value at the right of its label: percentages, "ms" for milliseconds, "min" for minutes. The values are not announced twice by TalkBack.
2. The File progress slider spans the full width of its row.
3. The sentence being read is highlighted in a stronger, clearer colour in both themes.
4. The return button of the sleep timer has a permanently reserved row under the player, so its appearance no longer shifts the interface.
5. The media session stays active while paused, so the global Play/Pause keeps controlling Vox TXT until another player is started by hand.
6. The experimental variants using temporary audio files and pre-buffering were removed. Reading stays direct through the system Text-to-Speech engine.

### Fixed

1. The TalkBack two finger double tap gesture is more reliable, including on the home and lock screens.
