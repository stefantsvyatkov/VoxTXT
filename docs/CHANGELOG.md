# Vox TXT — changelog

## Changes in Beta 6

### New

1. Navigation by paragraphs and by sections. The Go to sentence button is now Navigation, and what it opens chooses what everything moves by - sentences, paragraphs or sections - and takes a number to go to in whichever was chosen. Previous and Next under the player follow that choice and say which they are moving by, and the line above the reading counts in it as well.
2. Contents, in More, shows the parts a book names for itself, read out of the FB2 sections, the EPUB's own table or the level a Word style declares. It is a page of its own, like Bookmarks, with Read at the bottom where Apply stands elsewhere. It is a tree and starts closed: pressing a part opens it, pressing it again closes it, and that same press is the choice. It is offered only for a document that declares a shape, and it opens on the part being read.
3. Footnotes are read where they are referred to, right after the sentence their little number stood in, announced as "Footnote:". Until now they were thrown away: the texts at the end of an FB2 were skipped and the links in an EPUB removed, so half of what some books have to say never reached the reader. FB2, EPUB and DOCX are all read this way, notes at the end of a Word document included, and a note pointed at from several places is read at every one of them.
4. Calculate the duration, in More, says how long a document takes to read out with the voice in use. Android will not say how long a sentence takes without producing it, and producing a whole novel takes an hour on a slow voice - so two hundred sentences spread through the document are written out silently and the rest is worked out from them. Measured against a full production of two books it came within a minute, which on seven hours is a fifth of one per cent. The line above the progress bar then says about how much has been read of how much in all, and stays right after seeking. It is kept per document and belongs to the exact voice, rate and pitch it was taken under: change any of them and it steps aside, come back to them and it counts again. It runs with the screen off, says so in the notification shade, can be called off, and every run replaces the one before it.
5. Read the clipboard, in More, reads whatever has been copied. It is taken as it stands: an address on the clipboard is read out rather than fetched, which is what Open URL one line above is for.
6. Go to the beginning, in More, returns to the first sentence of whatever is open.
7. What was read out of the last twenty documents is kept, the way the text of the last web page already was. A document handed over by another app arrives with an address good for that moment only, so its row in Recent files opened nothing; now it opens. A document that is still where it was opens at once, without being unpacked and read through again. The file itself stays the truth: its size and time are checked, and a changed document is read afresh.

### Changed

1. Options is now called Settings and holds three of its own: General, Reading and Seeking. Each opens a page of its own, and Apply and Back both return to that list rather than out to the book. Language and theme take effect the moment they are chosen.
2. Open TXT is now Open file, which is what it has been since FB2, EPUB, DOCX and ZIP were added.
3. Pause playback outside reader is now Pause reading when another screen opens, and Voice settings is now Voice options.
4. Fast seek interval is now called Fast seek speed, which is what it always was. Beside it are two new settings: how far one step of a fast seek reaches in sentences, and how far in paragraphs. A paragraph is about five sentences, so the two are counted separately.
5. Go to the beginning moves the reading without deciding whether there should be any: a book that was silent stays silent at its first sentence, and one that was speaking carries on from there. Setting a sleep timer no longer starts a stopped book either - it says when to stop, not when to begin.
6. Settings can be reached with nothing open, and More offers only what applies. With nothing open it holds the two ways of bringing something in, the settings and the credits, instead of a search with nothing to search.

### Fixed

1. An archive that could not be opened at all reported it in English, in the words of the library that failed rather than the app's own.
2. The reading is shown through a window onto the book rather than all at once. Android lays out every character it is given, so a long book took seconds to lay out - when it was opened, and again every time the screen was rebuilt, which is what froze the app on coming back from a page. About sixty thousand characters are held around the place being read, and the window moves itself. Scrolling by hand reaches the end of the window rather than the end of the book; everything else works on the whole text as before.
3. A book opened from the file picker could not be opened again from Recent files, and said only that the content was unsupported. The app was never asking to keep the right to read the file. Books opened from now on are remembered properly; a book already in the list has to be opened once more through Open file.
4. A document was turned away by its size on the storage, which says almost nothing about what it costs to read: a ten megabyte manual is mostly pictures and a three megabyte book can be five million words. Files up to fifty megabytes open now, pictures, fonts, sound and film in a book are never unpacked at all, and the only other refusal is a reading longer than eight million characters.
5. A text box in a Word document was read twice, and a shape Word stores in two forms was read once for each of them.
6. Word documents whose heading styles are filed under a name of their own - "1", "Style37", "Heading10" - had no contents at all, and "Heading10" was read as heading level ten. What a style declares in the document is read now, rather than the name it happens to be filed under.

## Changes in Beta 5

### New

1. Copy text, in More, puts the article that was read in on the clipboard. It is offered for a web page.
2. Share text, in More, hands the article to another app as plain text. A book is far too large to travel that way and goes through Save as TXT instead.
3. The last web page comes back when the app is opened again, from text kept on the phone - no fetching, no waiting and no connection needed. It returns where it was left rather than at its beginning.
4. Every page names its own window, so a screen reader says which page it has come back to after a dialog or another app.
5. Close the app with Back or Home, in Options, off by default. Back normally steps aside and leaves the reading running, the way every player does.

### Changed

1. Opening a file with Open TXT starts reading it, as every other way into a book already did.
2. Buttons are blue rather than the grey the platform gives them, deep on the light theme and a shade lighter on the dark one, with white lettering on both.
3. The sentence being read is marked in yellow and written in black, in both themes.
4. The sliders are thicker, and the part already covered is drawn in the colour of the app against a grey track.
5. The launcher icon is adaptive, so the phone can give it its own shape, and it has a monochrome form for themed icons.
6. The open tab in Voice settings and in Recent files is filled in, so which one is open can be seen and not only heard.
7. The dropdowns have an outline, so they read as fields rather than as a line of text.
8. A dropdown long enough to scroll opens with the screen reader on its first entry.
9. The player sits at the bottom of the screen and its symbols are larger. The row under it is there only while the sleep timer button is, so with no timer running the reading has about three more lines; the button fades in and out rather than jumping, and on a phone with animations turned off it simply appears.
10. Headings, fields and sliders begin on the same line down the page, every button is the same height, and the text runs on one scale of sizes.
11. The Custom row of the sleep timer shows the minutes chosen at its right-hand end, instead of naming itself a second time above its slider.
12. The interface holds together at any text size. A button grows to fit its caption instead of cutting it off along the bottom, and two buttons share a line only while both of them fit on it - measured on the phone that is asking, so the app's own text size, the phone's, the extra weight of type from accessibility and the length of the language are all accounted for at once.
13. Switching between the two tabs of Voice settings keeps what has been set on each of them. Leaving without Apply still forgets it all, as it always did.
14. Close in the search dialog moved to the row the dialog keeps for it, leaving Previous and Next in the content where they are used over and over.
15. The back arrow at the top of every page is an arrow now, instead of the rewind symbol Android hands out.
16. Several Bulgarian labels are reworded: shorter, and in the style the rest of them use.
17. The app appears finished. The icon Android shows while an app starts now stays until the book is open and in its place, rather than giving way to a screen that then assembled itself - the name arriving, the buttons moving down, the reading sliding up to where it was left.

### Fixed

1. Clearing the app from Recents did not always finish it off. A Play from a headset could bring it back, and the player could be left sitting in the notification shade doing nothing.
2. Footnote reference numbers were read out stuck to the word in front of them - "the tavern1" - in FB2 and in EPUB alike.
3. A solid white square appeared in the status bar in place of the notification symbol.
4. Back ended the screen of the app while leaving the reading running behind it, and a book could then go on reading after the app had been cleared from Recents.
5. Removing an entry from Recent files or Bookmarks moved the screen reader focus to the top of the page. The focus now remains at the position of the removed entry.
6. Reading gave up when the screen reader spoke through the same voice engine. A busy engine was treated as a failed one, so the book either stopped with a message about the voice or stood in silence with the player still showing that it was reading. It now waits for the engine and says the sentence again once it is free.
7. The File progress slider was drawn thicker than the sliders in Options, although both come from the same two lines of code: a progress bar is stretched to fill whatever row it is put in.
8. Scrolling to the sentence being read left a fraction of a line showing along the top edge, and the bottom edge cut through one. The reading now shows whole lines at any text size.
9. Pressing Previous or Next in the search dialog with nothing typed announced that nothing was found. It puts the screen reader in the field instead.
10. Names in Recent files, in Bookmarks and in the sleep timer were invisible - white on white in one theme and black on black in the other. The screen reader read them out all along.

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
