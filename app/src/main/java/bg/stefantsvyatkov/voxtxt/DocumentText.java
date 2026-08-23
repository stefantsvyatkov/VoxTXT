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
    private static final long MAX_UNPACKED = 24L * 1024 * 1024;

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

    static String extract(String kind, byte[] bytes) throws IOException { return extract(kind, bytes, ""); }
    // notePrefix is the words a footnote is announced with, and it comes from the screen because that is
    // where the language lives. Empty means the notes are left out, which is what the older behaviour was.
    static String extract(String kind, byte[] bytes, String notePrefix) throws IOException {
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
    private static String fromFb2(byte[] bytes, String notePrefix) throws IOException {
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
        for (Element body : book.select("body")) {
            if ("notes".equalsIgnoreCase(body.attr("name"))) continue;
            append(text, ArticleReader.plainText(body));
        }
        if (text.length() == 0) throw new IOException("empty fb2");
        return weaveNotes(text.toString().trim(), used, notePrefix);
    }

    // EPUB is a zip. Which file inside it is the book, and in what order its chapters go, is written down in
    // the archive itself: container.xml points at the package file, and the spine of that package lists the
    // chapters in reading order. Following that is the difference between a book and a pile of chapters in
    // whatever order the archive happened to store them.
    private static String fromEpub(byte[] bytes, String notePrefix) throws IOException {
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
        StringBuilder text = new StringBuilder();
        for (String name : order) {
            byte[] chapter = archive.get(name);
            if (chapter == null) continue;
            Document page = Jsoup.parse(new String(chapter, StandardCharsets.UTF_8));
            page.select("script, style, nav, svg").remove();
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
        if (text.length() == 0) throw new IOException("empty epub");
        return text.toString().trim();
    }

    private static String collapse(String value) { return value.replaceAll("\\s+", " ").trim(); }
    // Word keeps footnotes and notes at the end of the document in two files of their own, each note under a
    // number the text refers to. The two sets number themselves separately, so a note is remembered under its
    // kind as well as its number. The first two entries of either file are the separator lines Word writes
    // into every document and are not notes at all.
    private static void readNotes(byte[] part, String tag, String kind, Map<String, String> into, String notePrefix) {
        if (part == null || notePrefix.isEmpty()) return;
        for (Element note : xml(new String(part, StandardCharsets.UTF_8)).getElementsByTag(tag)) {
            String id = note.attr("w:id");
            if (id.isEmpty() || id.startsWith("-") || "0".equals(id)) continue;
            String body = collapse(runsOf(note));
            if (!body.isEmpty()) into.put(kind + id, body);
        }
    }
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
    private static String fromDocx(byte[] bytes, String notePrefix) throws IOException {
        Map<String, byte[]> archive = unzip(bytes);
        byte[] main = archive.get("word/document.xml");
        if (main == null) throw new IOException("no document part");
        Map<String, String> notes = new HashMap<>();
        readNotes(archive.get("word/footnotes.xml"), "w:footnote", "f", notes, notePrefix);
        readNotes(archive.get("word/endnotes.xml"), "w:endnote", "e", notes, notePrefix);
        List<String> used = new ArrayList<>();
        Document document = xml(new String(main, StandardCharsets.UTF_8));
        StringBuilder text = new StringBuilder();
        for (Element paragraph : document.getElementsByTag("w:p")) {
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
            if (!value.isEmpty()) append(text, value);
        }
        if (text.length() == 0) throw new IOException("empty docx");
        return weaveNotes(text.toString().trim(), used, notePrefix);
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
        long total = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[16384]; int count;
                while ((count = zip.read(buffer)) > 0) {
                    total += count;
                    if (total > MAX_UNPACKED) throw new IOException("archive too large");
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
