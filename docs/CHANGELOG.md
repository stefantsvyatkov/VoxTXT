# Vox TXT — changelog

Requires Android 8.0 (API 26) or later.

## Changes in Beta 4

### New

1. Read a web page. Share an article from the browser to Vox TXT and it is stripped down to the article itself: the title, the text, and the author and date when the page names them — no menus, adverts, related stories, comments or cookie notices. If a page will not give a plain request anything usable, the address is loaded once more in a browser that is never shown and read from there; no certificate check is relaxed anywhere in this. The cleaning is done by Readability4J and jsoup, the same Readability that Firefox uses for its Reader View.
2. FB2, EPUB and DOCX open alongside TXT. FB2 and EPUB are the two formats Bulgarian books are actually found in — chitanka.info hands out its library in those and in TXT — and EPUB is the worldwide standard. EPUB chapters are read in the order the book itself declares rather than the order the archive happens to store them, FB2 footnotes are left out, and the title comes from inside the file when it is there. None of it costs a new library: jsoup, already in the app for web pages, parses all three. A ZIP is opened too, but strictly as wrapping paper: exactly one book inside and it is read, anything else and the archive is refused. Libraries hand books out this way — chitanka.info gives its TXT and FB2 as .txt.zip and .fb2.zip.
3. Two tabs under the heading in Voice settings and in Recent files, for documents and for web pages. In Voice settings each tab is its own set of settings — engine, voice, speed, pitch, volume and the pause between sentences — and Apply writes both, not only the one on the screen. Separate voice settings for documents and for web pages — engine, voice, speed, pitch, volume and the pause between sentences. A book is listened to for hours and wants a calm voice; an article is a few minutes and wants a quick one, often from another engine. The switch is at the top of Voice settings, and the page opens on the set that belongs to whatever is open.
4. Recent files has two sections, Documents and Web pages, twenty entries each. An empty section is not shown at all. A document is read from the file still on the phone; a web page is fetched again from its address.
5. Anything handed to the app from outside opens the reader first, whatever page the app was left on, and starts reading by itself — a shared page, a shared passage of text, or a file opened through Open with.
6. Save as TXT, at the top of More, writes whatever is being read to Downloads, in a Vox TXT folder of its own, in UTF-8. It then opens from Open TXT like any other book. It is offered for everything except a plain text file that is already sitting on the phone — so for FB2, EPUB and DOCX, for a web page, for shared text, and for a TXT that arrived inside an archive. Nothing is worked out from a file name when the menu opens; the app remembers what it did when it loaded the text.
7. Text selected in any other app can be sent straight here from the toolbar that appears over the selection. That toolbar does not go through the share sheet; it lists apps that offer to process text, and Vox TXT now offers.
8. Open a link, in More, takes an address typed or pasted by hand. If the clipboard holds something that looks like an address it is already in the field, so opening it is one press. The clipboard is read at that moment and only to fill the field.
9. Credits, at the end of More, names the open source libraries behind reading a web page and their licences.
10. The Options button became More. It opens a list with Bookmarks, Search and Options.
11. Bookmarks on any sentence, named after the whole sentence and kept separately for each book.
12. Search for a word or phrase. It continues from the current place and wraps around the end of the book. On a hit the book itself starts reading from the sentence found, in its own voice, exactly as if the reading had been started from that place. The dialog stays open above it with three buttons on one row — Previous, Next, Close — so another occurrence is one press away in either direction, and closing leaves the reading where it is.

### Changed

1. Keep the screen on while reading, in Options: off, for documents, for web pages, or for both. Off by default. It holds the screen while the reading is running and what is being read matches the choice; the power button still turns it off, so a book listened to in a pocket costs nothing.
2. Start a web page from the beginning, in Options, on by default. An article is three minutes long and being dropped into the middle of one puzzles more than it helps. Books always continue where they were left.
3. Vox TXT is offered in the system Open with for anything that might turn out to be a book, including the nameless stream of bytes some file managers and cloud apps hand over for a file they did not recognise. What the file actually is gets decided from its first bytes when the name settles nothing, and something that is not text is refused with "Unsupported content."
4. Choosing an entry in Recent files starts reading it. Picking from a list is asking for it to be read, not merely opened.
5. More is ordered: Open URL, Save as TXT, Search, Bookmarks, Options, Credits. Bookmarks is left out for a web page and for shared text: a bookmark is for something you come back to, and neither of those is fetched twice from the same place.
6. Options are ordered: Language, Theme, Interface text size, Document text size, Fast seek interval, Fast seek haptic feedback, Pause playback outside reader, Prevent automatic playback when an audio device connects, Keep the screen on while reading, Start a web page from the beginning.
7. The sounds at the start and end of the text are gone, and so is their option. They were an extra that raised the question of where they belonged with every new kind of content, and with reading that now starts by itself they only delayed the start. The end of a book is announced by the synthesizer saying "End of text.", as it already did when the sounds were off. A web page says nothing at its end — three minutes of article end plainly enough when the voice stops — but pressing Play past the end still says there is no more text, for both.
8. Clearing the app from Recents, Close all included, now closes it for good. The reading stops, the notification disappears and nothing is left running, whether or not a book was being read at that moment. The position is saved on the way out.
9. Removing a book from Recent files now forgets it entirely: the row, the place it was left at, its bookmarks and the permission to open the file. The file itself is untouched. A book removed and opened again starts from the beginning instead of coming back at the percentage it was left at.
10. The sleep timer is a menu now. One tap on a value starts it and closes the dialog. Apply is left only for the custom one, and only while it is chosen; the rest of the time the dialog offers Close alone.
11. A running sleep timer shows under the player as "Cancel the timer, 12 minutes left", and pressing it stops the timer alone — a book that is reading carries on. Off has left the menu, and no row is marked as the active one: a running timer is visible on the main screen instead of inside a menu.
12. Entries in Recent files and in Bookmarks are plain text rows now, without the grey slab of a button behind them. They still highlight while the finger is on them and are still announced as something that can be opened.

### Fixed

1. Opening any subpage while a shared passage of text was being read threw it away and loaded the last book over it - the same fault as the one fixed for web pages, and fixed at the root this time by dropping the question that caused it. Whatever is open stays open; removing a book from the list already closes it if it is the one being read.
2. Pressing Play within a moment of moving a sentence could speak that sentence twice.
3. Fast seek could be left running after the button was released: it went on ticking against the start of the text, and pressing Play then produced one word over and over, because every few hundred milliseconds the reading was moved back to the same place. It now stops on its own at either end of the text, is stopped by a rebuilt screen and by closing the app, and gives up after a minute if a release is ever missed.
4. Tapping Previous or Next repeatedly could make a sentence repeat. A stop the app asked for itself was reported against the sentence that had only just started, and was mistaken for the speech engine failing. An interruption that early is now known for what it is, and repeated taps wait out each other so the engine is asked once, for the sentence actually landed on.
5. The File progress slider could not be moved forward through a short text, and appeared to jam when tapped repeatedly. One percent of a twelve-sentence article is worth less than one sentence, so the sum gave back the sentence already open and the slider was redrawn where it started. A step that changes the percentage now changes the sentence too, at any length.
6. A cookie wall could be read out instead of the article. Three things now stand against it: the scripts that build one are not loaded, anything the page has pinned on top of itself and filled with text is removed in the hidden browser — whoever wrote it and whatever it is called — and the containers the well known consent tools use are removed before the article is picked out. Nothing is accepted on the reader's behalf — the notice is simply not read; whether to agree stays a question for the browser. If removing them leaves nothing readable, the page is read again untouched.
7. Switching a tab sent the screen reader back to the page heading. It stays on the tab, which announces itself as selected.
8. Opening any settings page while a web page was being read threw the article away and loaded the last book over it — in the voice of the web profile it had just left. Leaving a page only checked the list of documents for what was open, and a web page is not in that list.
9. A long name in Recent files was drawn past the edge of its button, with the last line left outside the background. Rows of a list now grow with their text.

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
