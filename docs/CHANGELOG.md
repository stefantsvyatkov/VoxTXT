# Vox TXT — changelog

## Changes in Beta 5

### New

1. Copy text, in More, puts the article that was read in on the clipboard. It is offered for a web page; a passage shared from another app came from somewhere that already has it.
2. Share text, in More, hands the article to another app as plain text. It stands beside Copy text and is offered on the same terms: an article is a few thousand characters and travels as text, while a book is far too large for that and travels as a file, which is what Save as TXT is for.
3. Every page names its own window - Options, Voice settings, Recent files, Bookmarks, Credits - so a screen reader says which page it has come back to after a dialog, a dropdown or another app, instead of saying the name of the app whichever page is open.
4. Close the app with Back or Home, in Options, off by default. Back normally steps aside and leaves the reading running, the way every player does, so a stray press costs nothing and the headset button brings it straight back. The option turns Back into a full stop instead.

### Changed

1. Opening a file with Open TXT starts reading it. Every other way in already did — a file chosen from Recent files, a page shared from the browser, a book opened from a file manager — and Open was the one that left you looking at a book and wondering why it was silent.
2. Buttons are blue rather than the grey the platform gives them, deep on the light theme and a shade lighter on the dark one so that the button is still a shape against a black page. The lettering is white on both and none of the contrast is given up for the colour.
3. The sentence being read is marked in yellow and written in black, in both themes — a highlighter over ink, which against a black page is the most visible thing on the screen.
4. The sliders are thicker, and the part already covered is drawn in the colour of the app against a grey track. They used to be a hairline, and white on light grey said nothing about how far along they were.
5. The launcher icon is adaptive, so it takes the shape the phone gives icons instead of being cropped, and it has a monochrome form for themed icons.
6. The open tab in Voice settings and in Recent files is filled in, so which one is open can be seen and not only heard.
7. The dropdowns have an outline, so they read as fields rather than as a line of text.
8. A dropdown long enough to scroll opens with the screen reader on its first entry. A short one already did, and is left alone - taking a focus that is already there would only announce it twice. Nothing is done on the way out: where the reader goes when a dropdown closes is the system's business.

### Fixed

1. Clearing the app from Recents did not always finish it off. The book stayed loaded and the media session stayed open, so a Play from a headset or from the notification brought the app back from the dead; and because a service something is still bound to does not end when asked, the player could sit in the notification shade doing nothing. Stopping now drops the document, closes the session and takes the notification down by name.
2. Footnote reference numbers were read out stuck to the word in front of them — "the tavern1" — in FB2 and in EPUB alike.
3. The notification carried the app icon, which Android draws as a silhouette, so a solid white square appeared in the status bar instead of a symbol.
4. Back ended the screen of the app while leaving the reading running behind it. What was left in Recents was a task with no screen at all, and with no screen there was nothing left to notice a later Clear from Recents - which is how a book could go on reading after the app had been cleared away. Back now steps the app aside and leaves the screen standing.
5. Removing an entry from Recent files or from Bookmarks sent the screen reader back to the top of the page. It now stays where the entry stood, on whatever moved up into its place, or on the line that says the list is empty.
6. The Interface text size slider was the one slider left thin and grey while every other had been made thicker and coloured.
7. Names in Recent files, in Bookmarks and in the sleep timer were invisible: the rows had been given the lettering that belongs on a filled button and then had the fill taken away, leaving white on white in one theme and black on black in the other. The screen reader read them out all along.

## Changes in Beta 4

### New

1. Read a web page. Share an article from the browser, or paste an address into Open URL, and it is stripped down to the article itself: the title, the text, and the author and date when the page gives them. A page that will not answer a plain request is loaded once in a browser that is never shown. The cleaning is done by Readability4J and jsoup, the same Readability that Firefox uses for its Reader View.
2. FB2, EPUB and DOCX open alongside TXT, and a ZIP holding exactly one of them is unwrapped on the way in — the way chitanka.info hands out its books. EPUB chapters follow the order the book declares rather than the order the archive stores them. In FB2 the footnote texts are left out — they sit at the end of the file, away from what they refer to — and in both formats the little reference numbers are removed, so a word is not read with a digit stuck to its end.
3. Text selected in another app can be sent here from the toolbar over the selection, and text shared without an address in it is read as it arrived.
4. Separate voice settings for documents and for web pages: engine, voice, speed, pitch, volume and the pause between sentences. A book is listened to for hours and wants a calm voice; an article wants a quick one, often from another engine. Voice settings and Recent files each have two tabs, and Apply writes both sets.
5. Recent files keeps documents and web pages in separate tabs, twenty entries each. A document is read from the file on the phone; a web page is fetched again from its address.
6. Save as TXT writes whatever is being read to Downloads, in a Vox TXT folder of its own, in UTF-8. It is offered for everything except a plain text file that is already on the phone.
7. Bookmarks on any sentence, named after the whole sentence and kept separately for each book.
8. Search for a word or phrase. On a hit the book starts reading from the sentence found, and the dialog stays open with Previous, Next and Close, so another occurrence is one press away.
9. The Options button became More: Open URL, Save as TXT, Search, Bookmarks, Options, Credits.
10. Credits names the open source libraries behind the reading and their licences.
11. Keep the screen on while reading, in Options: off, for documents, for web pages, or for both.
12. Anything handed to the app from outside opens the reader and starts reading by itself.

### Changed

1. The sounds at the start and end of the text are gone, and so is their option. A book still announces its end by speaking "End of text."; a web page ends silently, and Play past the end still says there is no more text.
2. Clearing the app from Recents, Close all included, closes it for good, whether or not it was reading. The position is saved on the way out.
3. Removing a book from Recent files forgets it entirely: the row, the place it was left at, its bookmarks and the permission to open the file. The file itself is untouched.
4. The sleep timer is a menu — one tap on a value starts it, and Apply is left only for the custom one. A running timer shows under the player as "Cancel the timer, 12 minutes left", and cancelling stops the timer alone.
5. A web page starts from its beginning by default, which can be turned off in Options. Books always continue where they were left.
6. Choosing an entry in Recent files starts reading it.
7. Vox TXT is offered in the system Open with for anything that might turn out to be a book, including the nameless stream of bytes some file managers hand over. What the file really is comes from its first bytes when the name settles nothing, and anything else is refused with "Unsupported content."
8. Entries in Recent files and in Bookmarks are plain text rows now, without the grey slab of a button behind them.

### Fixed

1. Opening a settings page while a web page or a shared passage of text was being read threw it away and loaded the last book over it, in the wrong voice.
2. Fast seek could keep running after the button was released, ticking against the start of the text and turning the reading into one word repeated over and over.
3. Repeated taps on Previous or Next could make a sentence repeat, and Play just after a move could speak that sentence twice.
4. The File progress slider could not be moved forward through a short text, and appeared to jam when tapped repeatedly.
5. A cookie wall could be read out instead of the article.
6. The Play button flickered between Play and Pause while seeking during playback.
7. A long name in Recent files was drawn past the edge of its row, with the last line outside the background.
8. Switching a tab sent the screen reader back to the page heading instead of leaving it on the tab.

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
