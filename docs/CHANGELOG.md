# Vox TXT — changelog

Requires Android 8.0 (API 26) or later.

## 1.0-beta3

### New
- A short sound at the start and at the end of the text, with a setting to turn it off in Options.
- With the sounds off, the end of the book is announced by speaking "End of text."
- Pressing Play after the end shows "No more text." instead of repeating the last sentence.
- A "Preview voice" button on the voice page. It reads the current sentence of the book with the selected voice and the current slider positions, before they are saved.
- Detection of older encodings: Windows-1251, Windows-1252, Windows-1250 and ISO-8859-7, as well as UTF-16 without a byte order mark. Older Bulgarian and Russian books now open as text instead of garbled characters.

### Fixed
- Opening a file from Recent files did nothing — the previous book stayed open.
- The app was killed when Play was pressed on headphones after it had been stopped.
- The sleep timer could leave the device volume turned down when Do Not Disturb was on, or when the app was force closed during the fade-out.
- Reading large files stuttered — the whole text was redrawn on every sentence.
- Dragging the File progress slider interrupted playback on every percent.
- Voice names were shown as technical codes, and some voices appeared twice in the list.
- Reading stayed stopped after a short interruption such as a phone call or a navigation prompt. It now continues on its own once the sound comes back, and only then — when another player takes over for good, the reading stays paused.
- Reading gave up when the speech engine was killed by the system. The connection to it is now rebuilt once and the reading carries on from the same sentence.

### Changed
- The maximum file size is 5 MB instead of 15 MB. An average book is under 1 MB.
- Fast seek now keeps exactly the interval set in Options. It used to be slowed down by redrawing the document on every step, so the range is now 200 to 600 ms with a default of 400 ms, which keeps the familiar pace.
- Seeking follows one rule now: while the book is paused the screen reader announces the sentence reached, and while it is reading nothing interrupts it. Releasing a fast seek during playback continues from the new place straight away, with no announcement and no wait.
- The recent files list is cleared once during this update. Saved positions inside books are kept.
- A new installation now starts clean: no recent files and no saved positions. Neither can work after a reinstall, because the permission to open those files is not restored with them. Settings are still backed up and restored, including onto a new phone.
- Changing the interface language also works when the app is installed from Google Play.

## 1.0-beta2

### New
- A media button receiver for Play/Pause, Previous, Next and Stop from TalkBack, headphones, Bluetooth devices and the system controls.
- A digitally silent local stream, through which Android recognises Vox TXT as the active media source without any extra sound being heard.
- The option to return to the start of an expired sleep timer now survives a force close of the app, together with the file, the starting sentence and the length of the timer.

### Changed
- Every slider shows its current value at the right of its label: percentages, "ms" for milliseconds, "min" for minutes. The values are not announced twice by TalkBack.
- The File progress slider spans the full width of its row.
- The sentence being read is highlighted in a stronger, clearer colour in both themes.
- The return button of the sleep timer has a permanently reserved row under the player, so its appearance no longer shifts the interface.
- The media session stays active while paused, so the global Play/Pause keeps controlling Vox TXT until another player is started by hand.
- The TalkBack two finger double tap gesture is more reliable, including on the home and lock screens.

### Removed
- The experimental variants using temporary audio files and pre-buffering. Reading stays direct through the system Text-to-Speech engine.
