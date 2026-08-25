package bg.stefantsvyatkov.voxtxt;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

// Everything that is not a plain text file, turned into plain text. Three formats, and all three are the same
// job underneath: find the part of the file that holds the words and read it in the order a person would.
//
// EPUB and DOCX are zip archives with XML inside. FB2 is a single XML file. jsoup is already in the app for
// web pages and parses all of it, so none of this costs a new library.
final class DocumentText {

    // A zipped book unpacks to far more than it weighs, so what comes out of the archive is capped as well.
    // The one measure that means anything to a reader: how long the finished reading is. The size of a file
    // says almost nothing - a ten megabyte manual is mostly pictures, and a three megabyte book can be five
    // million words. So the door only turns away what is too big to pick up, and this decides the rest.
    // Affordable because the reading is shown through a window: the text view is handed sixty thousand
    // characters whatever the book, and the rest is only a string held in memory.
    static final int MAX_TEXT = 8_000_000;
    // Not a limit on how large a book may be - that is what MAX_TEXT is for - but a stop against an archive
    // built to unpack into something enormous. No real document comes anywhere near it.
    private static final long MAX_UNPACKED = 512L * 1024 * 1024;
    // What is worth unpacking. Pictures, fonts, sound, film and stylesheets are never opened by this reader,
    // so they are counted past without being held: that is what lets a ten megabyte manual open at all, and
    // it is also why an EPUB carrying recorded narration costs nothing here - the recording is simply never
    // taken out of the archive.
    private static final String[] WORTH_KEEPING = {".xml", ".xhtml", ".html", ".htm", ".ncx", ".opf", ".txt", ".fb2", ".docx", ".epub"};
    private static boolean worthKeeping(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase(Locale.ROOT);
        for (String ending : WORTH_KEEPING) if (lower.endsWith(ending)) return true;
        return false;
    }
    // Too much of it to read, as opposed to unreadable. Two different things to be told.
    static class TooLong extends IOException { TooLong() { super("too long"); } }

    private DocumentText() {}

    static String kindOf(String fileName) {
        String lower = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".txt")) return "txt";
        if (lower.endsWith(".fb2")) return "fb2";
        if (lower.endsWith(".epub")) return "epub";
        if (lower.endsWith(".docx")) return "docx";
        if (lower.endsWith(".zip")) return "zip";
        return "";
    }

    // When the name settles nothing - a file manager that hands over a nameless stream, a file saved without
    // an extension - the file itself is asked. Every one of these formats says what it is in its first bytes,
    // which is a better witness than a name anyway.
    static String kindOfContent(byte[] bytes) {
        if (bytes == null || bytes.length < 4) return "";
        if (bytes[0] == 'P' && bytes[1] == 'K' && bytes[2] == 3 && bytes[3] == 4) {
            try {
                Map<String, byte[]> archive = unzip(bytes);
                if (archive.containsKey("word/document.xml")) return "docx";
                for (String name : archive.keySet())
                    if ("META-INF/container.xml".equals(name) || name.toLowerCase(Locale.ROOT).endsWith(".opf")) return "epub";
                // A zip that is neither: it may still be wrapping paper around a single book.
                return "zip";
            } catch (Exception e) { return ""; }
        }
        String head = new String(bytes, 0, Math.min(bytes.length, 4096), StandardCharsets.ISO_8859_1);
        if (head.contains("FictionBook")) return "fb2";
        return looksLikeText(bytes) ? "txt" : "";
    }

    // Whether the bytes read as writing rather than as a picture or a program. Control characters that never
    // appear in text are the giveaway; a page of them means this is not something to hand to a reader.
    private static boolean looksLikeText(byte[] bytes) {
        // Text written two bytes to the character is full of zeros and would fail every test below, so it is
        // recognised by the mark it carries at its front.
        if (bytes.length >= 2 && ((bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xFE
            || (bytes[0] & 0xFF) == 0xFE && (bytes[1] & 0xFF) == 0xFF)) return true;
        int checked = Math.min(bytes.length, 4096), odd = 0;
        if (checked == 0) return false;
        for (int i = 0; i < checked; i++) {
            int value = bytes[i] & 0xFF;
            if (value == 0) return false;
            if (value < 32 && value != '\t' && value != '\n' && value != '\r') odd++;
        }
        return odd * 20 < checked;
    }

    // What is inside an archive that holds exactly one book.
    static final class Entry {
        final String name, kind; final byte[] bytes;
        Entry(String name, String kind, byte[] bytes) { this.name = name; this.kind = kind; this.bytes = bytes; }
    }

    // A ZIP is treated as wrapping paper and nothing more. Libraries hand out books wrapped this way -
    // chitanka.info gives its TXT and FB2 as .txt.zip and .fb2.zip - and unwrapping one file is not the same
    // as becoming a file manager. So: exactly one book inside and it is opened; anything else and the archive
    // is refused, rather than asking the reader to pick from a list they never wanted to see.
    //
    // Returns null when the archive does not hold exactly one book, so the caller can say so in its own words.
    static Entry singleDocument(byte[] bytes) throws IOException {
        Entry only = null;
        for (Map.Entry<String, byte[]> item : unzip(bytes).entrySet()) {
            String name = item.getKey();
            String plain = name.contains("/") ? name.substring(name.lastIndexOf('/') + 1) : name;
            // The folder a Mac adds to every archive it makes, and the shadow files inside it.
            if (name.startsWith("__MACOSX/") || plain.startsWith(".") || plain.isEmpty()) continue;
            String kind = kindOf(plain);
            // An archive inside an archive is not unwrapped. One layer is a container; two is a filing system.
            if (kind.isEmpty() || "zip".equals(kind)) continue;
            if (only != null) return null;
            only = new Entry(plain, kind, item.getValue());
        }
        return only;
    }

    // The name shown for the book. A file name is what the user recognises, but these formats carry the real
    // title inside them, and that is the better name when it is there.
    static String titleOf(String kind, byte[] bytes, String fallback) {
        try {
            if ("fb2".equals(kind)) {
                Element found = xml(new String(bytes, charsetOfXml(bytes))).selectFirst("book-title");
                if (found != null && !found.text().trim().isEmpty()) return found.text().trim();
            } else if ("epub".equals(kind)) {
                Map<String, byte[]> archive = unzip(bytes);
                byte[] opf = archive.get(opfPath(archive));
                if (opf != null) {
                    // The title of an EPUB is a Dublin Core element, so in the package file it is written
                    // dc:title. Read as XML, that prefix is part of the tag name and has to be asked for.
                    Document document = xml(new String(opf, StandardCharsets.UTF_8));
                    for (String tag : new String[]{"dc:title", "title"}) {
                        for (Element found : document.getElementsByTag(tag))
                            if (!found.text().trim().isEmpty()) return found.text().trim();
                    }
                }
            }
        } catch (Exception ignored) {}
        return fallback;
    }

    // A document that declares its own shape says so here: the headings it names, how deeply each one sits, and
    // where in the finished text it begins. FB2 nests sections and titles them, EPUB carries a table of its own,
    // DOCX marks paragraphs with heading levels; plain text declares nothing and gets an empty list.
    //
    // The place is remembered by putting a mark in the text at the moment the heading is written, and reading
    // the marks off at the very end. Anything that happens to the text in between - notes woven in, white space
    // squeezed - moves the marks with it, which counting characters as we went would not have survived.
    static final class Heading {
        final String title; final int level; final int offset;
        Heading(String title, int level, int offset) { this.title = title; this.level = level; this.offset = offset; }
    }
    static final class Content {
        final String text; final List<Heading> headings;
        Content(String text, List<Heading> headings) { this.text = text; this.headings = headings; }
    }
    private static final char HEAD_OPEN = '\uE002', HEAD_CLOSE = '\uE003';
    private static String headingMark(int index) { return HEAD_OPEN + String.valueOf(index) + HEAD_CLOSE; }
    // A heading may itself carry a footnote, and the mark standing in for that note is no part of its name.
    // Left in, it travelled into the contents as a pair of characters with a number between them, which a
    // screen reader reads out as so much noise, and stopped the name from ever matching the text again. The
    // mark stays where it is in the reading, so the note is still heard in its place.
    private static String withoutMarks(String value) {
        int at = value.indexOf(NOTE_OPEN);
        if (at < 0) return value;
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c != NOTE_OPEN) { out.append(c); continue; }
            int close = value.indexOf(NOTE_CLOSE, i);
            if (close < 0) break;
            i = close;
        }
        return collapse(out.toString());
    }
    private static Content withHeadings(String text, List<String> titles, List<Integer> levels, String what) throws IOException {
        Content content = withHeadings(text, titles, levels);
        if (content.text.isEmpty()) throw new IOException("empty " + what);
        return content;
    }
    private static Content withHeadings(String text, List<String> titles, List<Integer> levels) {
        // Taken out from the front of the text backwards, so that a mark still waiting keeps the place it had.
        // The marks are not always in the order they were written: a part, its first chapter and that chapter's
        // first scene can all point at the same spot, and pulling them out in any other order moves them apart.
        StringBuilder rest = new StringBuilder(text);
        int[] places = new int[titles.size()];
        for (int i = 0; i < titles.size(); i++) places[i] = text.indexOf(headingMark(i));
        Integer[] order = new Integer[titles.size()];
        for (int i = 0; i < order.length; i++) order[i] = i;
        java.util.Arrays.sort(order, (a, b) -> Integer.compare(places[a], places[b]));
        int removed = 0;
        Heading[] found = new Heading[titles.size()];
        for (Integer i : order) {
            if (places[i] < 0) continue;
            int at = places[i] - removed, length = headingMark(i).length();
            rest.delete(at, at + length);
            removed += length;
            found[i] = new Heading(titles.get(i), levels.get(i), at);
        }
        int lead = 0;
        while (lead < rest.length() && Character.isWhitespace(rest.charAt(lead))) lead++;
        String value = rest.toString().trim();
        // Back into the order the document names them in, and no entry may begin before the one above it.
        List<Heading> moved = new ArrayList<>();
        int floor = 0;
        for (int i = 0; i < places.length; i++) {
            if (places[i] < 0) continue;
            Heading h = found[i];
            floor = Math.max(floor, Math.max(0, Math.min(value.length(), h.offset - lead)));
            moved.add(new Heading(h.title, h.level, floor));
        }
        return new Content(value, moved);
    }

    static String extract(String kind, byte[] bytes) throws IOException { return extract(kind, bytes, ""); }
    // notePrefix is the words a footnote is announced with, and it comes from the screen because that is
    // where the language lives. Empty means the notes are left out, which is what the older behaviour was.
    static String extract(String kind, byte[] bytes, String notePrefix) throws IOException {
        return read(kind, bytes, notePrefix).text;
    }
    static Content read(String kind, byte[] bytes, String notePrefix) throws IOException {
        switch (kind) {
            case "fb2": return fromFb2(bytes, notePrefix);
            case "epub": return fromEpub(bytes, notePrefix);
            case "docx": return fromDocx(bytes, notePrefix);
            default: throw new IOException("unsupported");
        }
    }

    // A note is read where it is referred to, not left in a heap at the end of the book where half of what it
    // explains has been forgotten. The little number in the text is swapped for a mark, the book is turned
    // into text as before, and then each mark is taken out and its note put in after the end of the sentence
    // it stood in. After the sentence and not at the mark itself: a note almost always sits in the middle of
    // a thought, and read there it cuts the thought in half.
    private static final char NOTE_OPEN = '\uE000', NOTE_CLOSE = '\uE001';
    private static String mark(int index) { return NOTE_OPEN + String.valueOf(index) + NOTE_CLOSE; }
    private static String weaveNotes(String text, List<String> notes, String prefix) {
        for (int i = notes.size() - 1; i >= 0; i--) {
            String mark = mark(i);
            int at = text.indexOf(mark);
            if (at < 0) continue;
            text = text.substring(0, at) + text.substring(at + mark.length());
            String note = notes.get(i).trim();
            if (note.isEmpty()) continue;
            if (".!?".indexOf(note.charAt(note.length() - 1)) < 0) note = note + ".";
            int end = sentenceEndAfter(text, at);
            text = text.substring(0, end) + " " + prefix + " " + note + text.substring(end);
        }
        return text;
    }
    // Only the sentence around the mark is looked at, not the whole book: a book with five hundred notes
    // would otherwise be walked five hundred times over.
    private static int sentenceEndAfter(String text, int at) {
        int back = at;
        while (back > 0 && Character.isWhitespace(text.charAt(back - 1))) back--;
        // The mark already stands after the end of a sentence; nothing has to be looked for.
        if (back > 0 && ".!?".indexOf(text.charAt(back - 1)) >= 0) return back;
        String window = text.substring(at, Math.min(text.length(), at + 2000));
        java.text.BreakIterator it = java.text.BreakIterator.getSentenceInstance(Locale.getDefault());
        it.setText(window);
        int end = it.following(0);
        int absolute = end == java.text.BreakIterator.DONE ? at + window.length() : at + end;
        while (absolute > at && Character.isWhitespace(text.charAt(absolute - 1))) absolute--;
        return absolute;
    }
    // The title of a note is usually the very number that pointed at it. Read out, it would say that number a
    // second time, so a title that is nothing but a number is dropped and a real one is kept.
    private static String noteBody(Element note) {
        Element title = note.selectFirst("title");
        if (title != null && title.text().trim().replaceAll("[.\\s]", "").matches("[0-9]+[\\p{L}]?")) title.remove();
        // The note often opens with the very number that pointed at it, written as [1] or as 1. - the
        // link back to the place it came from. Only those two shapes are taken off, so that a note which
        // genuinely begins with a year is left alone.
        return collapse(ArticleReader.plainText(note))
            .replaceAll("^(\\[[0-9]+\\][.)]?|[0-9]+[.)])\\s+", "")
            // and often closes with the arrow that leads back to it, which is a picture rather than a word.
            .replaceAll("[\u21A9\u2190\u2191\uFE0E]+\\s*$", "").trim();
    }

    // FB2 is one XML file holding the whole book. Everything worth reading is in its body elements; a body
    // named "notes" is not one of them - it holds the texts of the footnotes, gathered at the end of the file
    // rather than where they are referred to. Read in place they would arrive after the last chapter as a
    // heap of fragments with nothing around them: "Same, page 45."
    //
    // The little numbers that point at those notes are removed as well. They sit tight against the word they
    // follow, so what reaches the synthesizer is "the tavern1" - and what comes out is the word with a digit
    // stuck to its end, in the middle of a sentence.
    private static Content fromFb2(byte[] bytes, String notePrefix) throws IOException {
        Document book = xml(new String(bytes, charsetOfXml(bytes)));
        // The notes of an FB2 are gathered in a body of their own at the end, each in a section carrying the
        // name the little number points at.
        Map<String, String> notes = new HashMap<>();
        for (Element body : book.select("body"))
            if ("notes".equalsIgnoreCase(body.attr("name")))
                for (Element section : body.select("section[id]")) notes.put(section.attr("id"), noteBody(section));
        List<String> used = new ArrayList<>();
        for (Element link : book.select("a[type=note], a[type=comment]")) {
            String href = link.attr("l:href");
            if (href.isEmpty()) href = link.attr("xlink:href");
            if (href.isEmpty()) href = link.attr("href");
            String note = notes.get(href.startsWith("#") ? href.substring(1) : href);
            if (notePrefix.isEmpty() || note == null || note.trim().isEmpty()) { link.remove(); continue; }
            used.add(note);
            link.replaceWith(new org.jsoup.nodes.TextNode(mark(used.size() - 1)));
        }
        StringBuilder text = new StringBuilder();
        List<String> titles = new ArrayList<>();
        List<Integer> levels = new ArrayList<>();
        for (Element body : book.select("body")) {
            if ("notes".equalsIgnoreCase(body.attr("name"))) continue;
            readFb2(body, 0, text, titles, levels);
        }
        return withHeadings(weaveNotes(text.toString().trim(), used, notePrefix), titles, levels, "fb2");
    }
    // Sections within sections is how an FB2 says that a chapter belongs to a part. Each one is read in turn:
    // its title first, marked as a heading of its depth, then whatever of it is not another section, then the
    // sections inside it. Reading a whole body in one go, as this used to, gives the same words in the same
    // order but says nothing about where anything begins.
    private static void readFb2(Element node, int depth, StringBuilder text, List<String> titles, List<Integer> levels) {
        for (Element child : node.children()) {
            if ("section".equalsIgnoreCase(child.normalName())) {
                Element title = child.selectFirst("> title");
                String name = title == null ? "" : withoutMarks(collapse(ArticleReader.plainText(title)));
                if (!name.isEmpty()) {
                    titles.add(name); levels.add(depth);
                    append(text, headingMark(titles.size() - 1) + name);
                    title.remove();
                }
                readFb2(child, depth + 1, text, titles, levels);
            } else append(text, ArticleReader.plainText(child));
        }
    }

    // EPUB is a zip. Which file inside it is the book, and in what order its chapters go, is written down in
    // the archive itself: container.xml points at the package file, and the spine of that package lists the
    // chapters in reading order. Following that is the difference between a book and a pile of chapters in
    // whatever order the archive happened to store them.
    private static Content fromEpub(byte[] bytes, String notePrefix) throws IOException {
        Map<String, byte[]> archive = unzip(bytes);
        String opfPath = opfPath(archive);
        byte[] opfBytes = archive.get(opfPath);
        if (opfBytes == null) throw new IOException("no package file");
        Document opf = xml(new String(opfBytes, StandardCharsets.UTF_8));
        String base = opfPath.contains("/") ? opfPath.substring(0, opfPath.lastIndexOf('/') + 1) : "";

        Map<String, String> manifest = new HashMap<>();
        for (Element item : opf.select("manifest > item")) manifest.put(item.attr("id"), item.attr("href"));
        List<String> order = new ArrayList<>();
        for (Element item : opf.select("spine > itemref")) {
            String href = manifest.get(item.attr("idref"));
            if (href != null && !href.isEmpty()) order.add(resolve(base, href));
        }
        // A package without a usable spine still has its chapters; reading them in the order the archive
        // stores them is a poor second, but it is better than refusing the book.
        if (order.isEmpty()) for (String name : archive.keySet()) if (isChapter(name)) order.add(name);

        // Notes are gathered before a word is read, because an EPUB is free to keep them anywhere: beside the
        // number that points at them, at the end of the chapter, or all together in a file of their own at the
        // end of the book. Which places are wanted is settled first, by looking for what the numbers point at,
        // so that only those are collected and nothing else is carried about.
        Map<String, String> notes = new HashMap<>();
        if (!notePrefix.isEmpty()) {
            Map<String, List<String>> wanted = new HashMap<>();
            for (String name : order) {
                byte[] chapter = archive.get(name);
                if (chapter == null) continue;
                Document page = Jsoup.parse(new String(chapter, StandardCharsets.UTF_8));
                for (Element link : page.select("a")) {
                    if (!isNoteRef(link)) continue;
                    String target = noteTarget(name, link.attr("href"));
                    if (target == null) continue;
                    String file = target.substring(0, target.indexOf('#')), id = target.substring(target.indexOf('#') + 1);
                    List<String> ids = wanted.get(file);
                    if (ids == null) { ids = new ArrayList<>(); wanted.put(file, ids); }
                    if (!ids.contains(id)) ids.add(id);
                }
            }
            for (Map.Entry<String, List<String>> entry : wanted.entrySet()) {
                byte[] chapter = archive.get(entry.getKey());
                if (chapter == null) continue;
                Document page = Jsoup.parse(new String(chapter, StandardCharsets.UTF_8));
                for (String id : entry.getValue()) {
                    Element note = page.getElementById(id);
                    if (note == null) continue;
                    String body = noteBody(note);
                    if (!body.isEmpty()) notes.put(entry.getKey() + "#" + id, body);
                }
            }
        }
        List<String> titles = new ArrayList<>();
        List<Integer> levels = new ArrayList<>();
        // file -> the ids within it the table of contents points at, in the order it points at them. An entry
        // that names a file with no id at all belongs at the very start of that file.
        Map<String, List<String>> wantedHeadings = new HashMap<>();
        // Same rule as everywhere: a table of contents that cannot be read leaves the book without one, and
        // the book is still read.
        try { readEpubContents(archive, opfPath, base, order, titles, levels, wantedHeadings); }
        catch (RuntimeException ignored) { titles.clear(); levels.clear(); wantedHeadings.clear(); }
        StringBuilder text = new StringBuilder();
        for (String name : order) {
            byte[] chapter = archive.get(name);
            if (chapter == null) continue;
            Document page = Jsoup.parse(new String(chapter, StandardCharsets.UTF_8));
            page.select("script, style, nav, svg").remove();
            List<String> here = titles.isEmpty() ? null : wantedHeadings.get(name);
            if (here != null && page.body() != null)
                for (String id : here) {
                    int index = Integer.parseInt(id.substring(0, id.indexOf(':')));
                    String target = id.substring(id.indexOf(':') + 1);
                    Element at = target.isEmpty() ? page.body() : page.getElementById(target);
                    if (at != null) at.prependChild(new org.jsoup.nodes.TextNode(headingMark(index)));
                }
            // A note is read where it is referred to, and taken out of the place it was kept, so that it is
            // not heard a second time when the reading reaches the end of the chapter or the end of the book.
            // One note may be pointed at from several places; it is read at every one of them.
            List<String> used = new ArrayList<>();
            for (Element link : page.select("a")) {
                if (!isNoteRef(link)) continue;
                String target = noteTarget(name, link.attr("href"));
                String body = target == null ? null : notes.get(target);
                if (body == null) { link.remove(); continue; }
                used.add(body);
                link.replaceWith(new org.jsoup.nodes.TextNode(mark(used.size() - 1)));
            }
            for (String target : notes.keySet())
                if (target.startsWith(name + "#")) {
                    Element note = page.getElementById(target.substring(target.indexOf('#') + 1));
                    if (note != null) note.remove();
                }
            if (page.body() != null) append(text, weaveNotes(ArticleReader.plainText(page.body()), used, notePrefix));
        }
        return withHeadings(text.toString().trim(), titles, levels, "epub");
    }
    // Read out of the book's own table of contents rather than guessed at from its headings: an EPUB names its
    // parts and chapters there, and says by nesting which belongs to which. Both shapes are read - the older
    // NCX with its navPoints, and the newer navigation document with its nested lists.
    private static void readEpubContents(Map<String, byte[]> archive, String opfPath, String base, List<String> order,
                                         List<String> titles, List<Integer> levels, Map<String, List<String>> wanted) {
        byte[] part = null; String partPath = "";
        for (String name : archive.keySet())
            if (name.toLowerCase(Locale.ROOT).endsWith(".ncx")) { part = archive.get(name); partPath = name; break; }
        if (part != null) {
            Document ncx = xml(new String(part, StandardCharsets.UTF_8));
            readNcx(ncx.selectFirst("navMap"), 0, partPath, titles, levels, wanted);
            if (!titles.isEmpty()) return;
        }
        for (String name : archive.keySet()) {
            if (!isChapter(name)) continue;
            Document page = Jsoup.parse(new String(archive.get(name), StandardCharsets.UTF_8));
            Element nav = page.selectFirst("nav[epub:type=toc]");
            if (nav == null) continue;
            readNavList(nav.selectFirst("ol"), 0, name, titles, levels, wanted);
            if (!titles.isEmpty()) return;
        }
    }
    private static void readNcx(Element node, int depth, String from, List<String> titles, List<Integer> levels, Map<String, List<String>> wanted) {
        if (node == null) return;
        for (Element point : node.children()) {
            if (!"navPoint".equalsIgnoreCase(point.normalName())) continue;
            Element label = point.selectFirst("navLabel > text");
            Element content = point.selectFirst("content");
            String title = label == null ? "" : collapse(label.text());
            if (!title.isEmpty() && content != null) rememberHeading(title, depth, from, content.attr("src"), titles, levels, wanted);
            readNcx(point, depth + 1, from, titles, levels, wanted);
        }
    }
    private static void readNavList(Element list, int depth, String from, List<String> titles, List<Integer> levels, Map<String, List<String>> wanted) {
        if (list == null) return;
        for (Element item : list.children()) {
            if (!"li".equalsIgnoreCase(item.normalName())) continue;
            Element link = item.selectFirst("> a");
            if (link != null) {
                String title = collapse(link.text());
                if (!title.isEmpty()) rememberHeading(title, depth, from, link.attr("href"), titles, levels, wanted);
            }
            readNavList(item.selectFirst("> ol"), depth + 1, from, titles, levels, wanted);
        }
    }
    private static void rememberHeading(String title, int depth, String from, String href, List<String> titles,
                                        List<Integer> levels, Map<String, List<String>> wanted) {
        if (href == null || href.isEmpty()) return;
        String base = from.contains("/") ? from.substring(0, from.lastIndexOf('/') + 1) : "";
        String file = resolve(base, href);
        String id = href.contains("#") ? href.substring(href.indexOf('#') + 1) : "";
        titles.add(title); levels.add(depth);
        List<String> here = wanted.get(file);
        if (here == null) { here = new ArrayList<>(); wanted.put(file, here); }
        here.add((titles.size() - 1) + ":" + id);
    }

    private static String collapse(String value) { return value.replaceAll("\\s+", " ").trim(); }
    // Word keeps footnotes and notes at the end of the document in two files of their own, each note under a
    // number the text refers to. The two sets number themselves separately, so a note is remembered under its
    // kind as well as its number. The first two entries of either file are the separator lines Word writes
    // into every document and are not notes at all.
    private static void readNotes(byte[] part, String tag, String kind, Map<String, String> into, String notePrefix) {
        if (part == null || notePrefix.isEmpty()) return;
        Document notes = xml(new String(part, StandardCharsets.UTF_8));
        dropFallbacks(notes);
        for (Element note : notes.getElementsByTag(tag)) {
            String id = note.attr("w:id");
            if (id.isEmpty() || id.startsWith("-") || "0".equals(id)) continue;
            String body = collapse(runsOf(note));
            if (!body.isEmpty()) into.put(kind + id, body);
        }
    }
    // A shape is written twice over: once the way Word draws it now, and once again the old way, so that an
    // older Word still has something to show. Both halves carry the same words, and reading them both is what
    // made the text inside such a shape arrive twice - said twice by the voice, and standing twice in the
    // contents. The old half is the one to drop: the new one is the one this reader understands.
    private static void dropFallbacks(Document document) { document.getElementsByTag("mc:Fallback").remove(); }
    private static String runsOf(Element node) {
        StringBuilder value = new StringBuilder();
        for (Element run : node.getElementsByTag("w:t")) value.append(run.wholeText()).append(' ');
        return value.toString();
    }
    private static boolean isNoteRef(Element link) {
        return "noteref".equalsIgnoreCase(link.attr("epub:type")) || "doc-noteref".equalsIgnoreCase(link.attr("role"));
    }
    // Where a note lives, as a path inside the archive and an id within it. A bare #id means the same file.
    private static String noteTarget(String from, String href) {
        if (href.isEmpty() || !href.contains("#")) return null;
        String id = href.substring(href.indexOf('#') + 1);
        if (id.isEmpty()) return null;
        if (href.startsWith("#")) return from + "#" + id;
        String base = from.contains("/") ? from.substring(0, from.lastIndexOf('/') + 1) : "";
        return resolve(base, href) + "#" + id;
    }
    private static boolean isChapter(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".xhtml") || lower.endsWith(".html") || lower.endsWith(".htm");
    }

    private static String opfPath(Map<String, byte[]> archive) throws IOException {
        byte[] container = archive.get("META-INF/container.xml");
        if (container != null) {
            Element root = xml(new String(container, StandardCharsets.UTF_8)).selectFirst("rootfile");
            if (root != null && !root.attr("full-path").isEmpty()) return root.attr("full-path");
        }
        for (String name : archive.keySet()) if (name.toLowerCase(Locale.ROOT).endsWith(".opf")) return name;
        throw new IOException("no package file");
    }

    // A path inside the archive, relative to the folder the package file sits in, with any ../ resolved.
    private static String resolve(String base, String href) {
        String path = href.contains("#") ? href.substring(0, href.indexOf('#')) : href;
        if (path.startsWith("/")) return path.substring(1);
        String joined = base + path;
        List<String> parts = new ArrayList<>();
        for (String part : joined.split("/")) {
            if (part.isEmpty() || ".".equals(part)) continue;
            if ("..".equals(part)) { if (!parts.isEmpty()) parts.remove(parts.size() - 1); continue; }
            parts.add(part);
        }
        return String.join("/", parts);
    }

    // DOCX is a zip too, and all the words are in one file inside it. Every w:p is a paragraph and every w:t a
    // run of text within it; a paragraph can be broken into many runs by nothing more than a change of font,
    // so the runs are joined and the break is made at the paragraph.
    private static Content fromDocx(byte[] bytes, String notePrefix) throws IOException {
        Map<String, byte[]> archive = unzip(bytes);
        byte[] main = archive.get("word/document.xml");
        if (main == null) throw new IOException("no document part");
        Map<String, String> notes = new HashMap<>();
        readNotes(archive.get("word/footnotes.xml"), "w:footnote", "f", notes, notePrefix);
        readNotes(archive.get("word/endnotes.xml"), "w:endnote", "e", notes, notePrefix);
        List<String> used = new ArrayList<>();
        Document document = xml(new String(main, StandardCharsets.UTF_8));
        dropFallbacks(document);
        Map<String, Integer> styleLevels = headingStyles(archive.get("word/styles.xml"));
        List<String> titles = new ArrayList<>();
        List<Integer> levels = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        for (Element paragraph : document.getElementsByTag("w:p")) {
            // A text box holds its own paragraphs inside the paragraph that carries it, so the same words
            // arrive twice: once from the paragraph around the box and once from the one inside it. Reading
            // only the outer one keeps the words in their place and says them once, and a heading inside a
            // box is still found, because the style is looked for through everything the paragraph holds.
            if (insideAnotherParagraph(paragraph)) continue;
            int level = headingLevel(paragraph, styleLevels);
            StringBuilder line = new StringBuilder();
            for (Element node : paragraph.getAllElements()) {
                String tag = node.tagName();
                // wholeText and not text: a run marked to preserve its spaces often ends with the one that
                // separates it from the next run, and a trimming read joins two words into one.
                if ("w:t".equals(tag)) line.append(node.wholeText());
                else if ("w:tab".equals(tag)) line.append(' ');
                else if ("w:br".equals(tag) || "w:cr".equals(tag)) line.append('\n');
                else if ("w:footnoteReference".equals(tag) || "w:endnoteReference".equals(tag)) {
                    String note = notes.get(("w:footnoteReference".equals(tag) ? "f" : "e") + node.attr("w:id"));
                    if (note != null && !note.isEmpty()) { used.add(note); line.append(mark(used.size() - 1)); }
                }
            }
            String value = line.toString().replaceAll("[ \t]+", " ").trim();
            if (value.isEmpty()) continue;
            if (level >= 0) { titles.add(withoutMarks(collapse(value))); levels.add(level); value = headingMark(titles.size() - 1) + value; }
            append(text, value);
        }
        return withHeadings(weaveNotes(text.toString().trim(), used, notePrefix), titles, levels, "docx");
    }
    // Word's element names carry a colon, and a colon is how a stylesheet selector names a state, so asking
    // for one through a selector means escaping it and trusting that every parser on the way reads the escape
    // the same. The names are looked for by hand instead: it is the same walk the selector would have made,
    // it cannot be misread, and it does not depend on anything outside this file.
    private static Element firstChild(Element parent, String tag) {
        if (parent == null) return null;
        for (Element child : parent.children()) if (tag.equalsIgnoreCase(child.tagName())) return child;
        return null;
    }
    private static boolean insideAnotherParagraph(Element paragraph) {
        for (Element above = paragraph.parent(); above != null; above = above.parent())
            if ("w:p".equals(above.tagName())) return true;
        return false;
    }
    // Which level a paragraph sits at, asked of Word in the order Word itself would answer: what is set on the
    // paragraph, then what its style says, then the name the style goes by.
    //
    // The name a style is filed under is not to be trusted on its own. Real manuals arrive with heading styles
    // filed as "1", "21" or "Style37", and with "Heading10" and "Heading11" - which Word writes when it has to
    // rename a clashing style, and which mean heading one, not heading ten and heading eleven. Every one of
    // those says plainly in word/styles.xml what outline level it carries, and that is what is read.
    //
    // Nine is Word's way of saying body text. It is written on ordinary paragraphs, and on the heading of a
    // table of contents so that the contents does not list itself - so a nine is an answer, not a miss, and
    // stops the question there.
    private static int headingLevel(Element paragraph, Map<String, Integer> styleLevels) {
        try { return levelOf(paragraph, styleLevels); } catch (RuntimeException ignored) { return -1; }
    }
    private static int levelOf(Element paragraph, Map<String, Integer> styleLevels) {
        Element properties = firstChild(paragraph, "w:pPr");
        if (properties == null) return -1;
        Element outline = firstChild(properties, "w:outlineLvl");
        if (outline != null) {
            int level = number(outline.attr("w:val"));
            if (level >= 0) return level <= 8 ? level : -1;
        }
        Element style = firstChild(properties, "w:pStyle");
        if (style == null) return -1;
        String id = style.attr("w:val");
        if (id == null || id.isEmpty()) return -1;
        Integer known = styleLevels.get(id);
        if (known != null) return known <= 8 ? known : -1;
        int named = levelFromName(id);
        return named <= 8 ? named : -1;
    }
    // "Heading 1", "heading1", "Heading_20_1", "Heading #1" - the same style, written by different hands. What
    // is left after the punctuation is a word and a number, and Word has nine levels, so anything above nine
    // was never a level in the first place.
    private static int levelFromName(String name) {
        if (name == null) return -1;
        StringBuilder plain = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isLetterOrDigit(c)) plain.append(Character.toLowerCase(c));
        }
        String value = plain.toString();
        if (!value.startsWith("heading") || value.length() < 8) return -1;
        int level = number(value.substring(7));
        return level >= 1 && level <= 9 ? level - 1 : -1;
    }
    private static int number(String value) {
        try { return Integer.parseInt(value.trim()); } catch (Exception ignored) { return -1; }
    }
    // What every paragraph style in the document declares about its own outline level, worked out once. Only
    // what a style says of itself counts - the level it declares, or the heading name it goes by.
    //
    // What a style is built on is deliberately not followed. Word does pass an outline level down that way,
    // and following it looked right until a real manual showed what it costs: RUBY 10 sets its front matter
    // in a style built on Heading 3, and inheriting turned a page of copyright text into three entries of the
    // contents, one of them twenty lines long. A style meant as a heading says so itself.
    private static Map<String, Integer> headingStyles(byte[] part) {
        Map<String, Integer> levels = new HashMap<>();
        if (part == null) return levels;
        Map<String, String> basedOn = new HashMap<>();
        Map<String, String> names = new HashMap<>();
        Map<String, Integer> declared = new HashMap<>();
        try {
            Document styles = xml(new String(part, StandardCharsets.UTF_8));
            for (Element style : styles.getElementsByTag("w:style")) {
                if (!"paragraph".equals(style.attr("w:type"))) continue;
                String id = style.attr("w:styleId");
                if (id == null || id.isEmpty()) continue;
                Element name = firstChild(style, "w:name");
                if (name != null) names.put(id, name.attr("w:val"));
                Element parent = firstChild(style, "w:basedOn");
                if (parent != null) basedOn.put(id, parent.attr("w:val"));
                Element properties = firstChild(style, "w:pPr");
                Element outline = firstChild(properties, "w:outlineLvl");
                if (outline != null) {
                    int level = number(outline.attr("w:val"));
                    if (level >= 0 && level <= 9) declared.put(id, level);
                }
            }
        } catch (Exception ignored) { return levels; }
        for (String id : names.keySet()) resolveStyle(id, declared, names, basedOn, levels, 0);
        for (String id : declared.keySet()) resolveStyle(id, declared, names, basedOn, levels, 0);
        return levels;
    }
    private static int resolveStyle(String id, Map<String, Integer> declared, Map<String, String> names,
                                    Map<String, String> basedOn, Map<String, Integer> levels, int depth) {
        if (id == null || depth > 8) return -1;
        Integer already = levels.get(id);
        if (already != null) return already;
        int level = -1;
        Integer own = declared.get(id);
        if (own != null) level = own;
        if (level < 0) level = levelFromName(names.get(id));
        if (level < 0) level = levelFromName(id);
        if (level >= 0) levels.put(id, level);
        return level;
    }

    private static Document xml(String content) { return Jsoup.parse(content, "", Parser.xmlParser()); }

    // An XML file says its own encoding on its first line. FB2 files from older collections are still written
    // in Windows-1251, and reading one as UTF-8 gives a page of question marks.
    private static java.nio.charset.Charset charsetOfXml(byte[] bytes) {
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) return StandardCharsets.UTF_8;
        String head = new String(bytes, 0, Math.min(bytes.length, 200), StandardCharsets.ISO_8859_1);
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("encoding=[\"']([\\w-]+)[\"']", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(head);
        if (m.find()) {
            try { if (java.nio.charset.Charset.isSupported(m.group(1))) return java.nio.charset.Charset.forName(m.group(1)); }
            catch (Exception ignored) {}
        }
        return StandardCharsets.UTF_8;
    }

    private static Map<String, byte[]> unzip(byte[] bytes) throws IOException {
        Map<String, byte[]> archive = new java.util.LinkedHashMap<>();
        long unpacked = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                byte[] buffer = new byte[16384]; int count;
                // Counted past without being held. A picture, a font or a recording is never opened by this
                // reader, so it costs nothing but the time it takes to walk over it.
                if (!worthKeeping(entry.getName())) {
                    while ((count = zip.read(buffer)) > 0) {
                        unpacked += count;
                        if (unpacked > MAX_UNPACKED) throw new TooLong();
                    }
                    continue;
                }
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                while ((count = zip.read(buffer)) > 0) {
                    unpacked += count;
                    if (unpacked > MAX_UNPACKED) throw new TooLong();
                    out.write(buffer, 0, count);
                }
                archive.put(entry.getName(), out.toByteArray());
            }
        }
        if (archive.isEmpty()) throw new IOException("empty archive");
        return archive;
    }

    private static void append(StringBuilder text, String part) {
        if (part == null || part.trim().isEmpty()) return;
        if (text.length() > 0) text.append("\n\n");
        text.append(part.trim());
    }
}
