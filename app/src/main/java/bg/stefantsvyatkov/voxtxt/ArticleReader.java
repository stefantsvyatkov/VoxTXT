package bg.stefantsvyatkov.voxtxt;

import android.content.Context;
import android.text.format.DateFormat;

import net.dankito.readability4j.Article;
import net.dankito.readability4j.Readability4J;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.NodeVisitor;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Takes a web address and gives back the article on it and nothing else: the title, the text, and the date
// and author when the page says who and when. Everything a browser puts around an article - menus, adverts,
// related stories, comments, cookie notices - is what is being thrown away.
//
// The reading itself is done by Readability4J, the same Readability that Firefox uses for its Reader View,
// so the result is what a Reader View would show rather than a guess of our own.
final class ArticleReader {

    // Sent with the request so that the page answers the way it would answer a browser. Many sites hand a
    // stripped or empty page to anything that does not look like one. Nothing here weakens the connection
    // itself: the certificate of the site is checked as strictly as anywhere else in the system.
    static final String USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36";
    private static final int TIMEOUT_MS = 20000;
    private static final int MAX_BYTES = 4 * 1024 * 1024;
    private static final int MAX_REDIRECTS = 5;
    // Below this, whatever came back is a consent wall, a block page or an empty shell rather than an article.
    private static final int USABLE_LENGTH = 400;

    private ArticleReader() {}

    static final class Result {
        final String url, title, text;
        Result(String url, String title, String text) { this.url = url; this.title = title; this.text = text; }
    }

    static String firstUrl(String shared) {
        if (shared == null) return "";
        Matcher m = Pattern.compile("https?://\\S+").matcher(shared);
        if (!m.find()) return "";
        // A shared address often ends up beside a full stop or a closing bracket from the sentence around it.
        return m.group().replaceAll("[.,;:!?)\\]}\"'>]+$", "");
    }

    static boolean isUsable(Result result) { return result != null && result.text.length() >= USABLE_LENGTH; }

    // Redirects are followed by hand, because the built-in following stops at a change of protocol and a plain
    // address that moves to its secure form is the most ordinary redirect there is.
    static String download(String address) throws IOException {
        String current = address;
        for (int redirect = 0; redirect <= MAX_REDIRECTS; redirect++) {
            HttpURLConnection connection = (HttpURLConnection)new URL(current).openConnection();
            try {
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(TIMEOUT_MS); connection.setReadTimeout(TIMEOUT_MS);
                connection.setRequestProperty("User-Agent", USER_AGENT);
                connection.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
                connection.setRequestProperty("Accept-Language", Locale.getDefault().toLanguageTag() + ",en;q=0.7");
                connection.setRequestProperty("Upgrade-Insecure-Requests", "1");
                connection.setRequestProperty("Sec-Fetch-Dest", "document");
                connection.setRequestProperty("Sec-Fetch-Mode", "navigate");
                connection.setRequestProperty("Sec-Fetch-Site", "none");
                int code = connection.getResponseCode();
                if (code == HttpURLConnection.HTTP_MOVED_PERM || code == HttpURLConnection.HTTP_MOVED_TEMP
                    || code == HttpURLConnection.HTTP_SEE_OTHER || code == 307 || code == 308) {
                    String next = connection.getHeaderField("Location");
                    if (next == null || next.isEmpty()) throw new IOException("redirect without a location");
                    current = new URL(new URL(current), next).toString();
                    continue;
                }
                if (code < 200 || code > 299) throw new IOException("http " + code);
                return read(connection);
            } finally { connection.disconnect(); }
        }
        throw new IOException("too many redirects");
    }

    private static String read(HttpURLConnection connection) throws IOException {
        byte[] body;
        try (InputStream in = connection.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[16384]; int count;
            while ((count = in.read(buffer)) > 0 && out.size() < MAX_BYTES) out.write(buffer, 0, count);
            body = out.toByteArray();
        }
        // The header is asked first, and jsoup finds the declaration inside the page itself when there is none.
        String charset = charsetOf(connection.getContentType());
        return new String(body, charset == null ? StandardCharsets.UTF_8 : Charset.forName(charset));
    }

    private static String charsetOf(String contentType) {
        if (contentType == null) return null;
        Matcher m = Pattern.compile("charset=\\s*\"?([\\w-]+)", Pattern.CASE_INSENSITIVE).matcher(contentType);
        if (!m.find()) return null;
        try { return Charset.isSupported(m.group(1)) ? m.group(1) : null; } catch (Exception e) { return null; }
    }

    // Cookie walls, consent dialogs, newsletter boxes and "subscribe to read on" panels. On a page fetched the
    // plain way these are usually not there at all; on one loaded in the hidden browser they are, because the
    // scripts that build them have run, and to Readability a wall of consent text looks a lot like an article.
    //
    // Only the containers the big consent tools are known to use, plus what a page marks as a dialog itself.
    // Nothing here is guessed from a word appearing somewhere in a class name, because an article about cookies
    // would be the first casualty. And nothing is ever agreed to on the reader's behalf - the notice is simply
    // not read out; whether to accept it stays a question for the browser, where it was asked.
    private static final String OVERLAY_SELECTOR = String.join(", ",
        "#onetrust-consent-sdk", "#onetrust-banner-sdk", ".onetrust-pc-dark-filter",
        "#didomi-host", "#didomi-popup", ".didomi-popup-open",
        ".qc-cmp2-container", ".qc-cmp-cleanslate", "#qcCmpButtons",
        "#usercentrics-root", "#uc-center-container",
        ".cc-window", ".cookie-consent", "#cookie-consent", "#cookieConsent", "#cookie-banner", "#cookie-notice",
        "#cookie-law-info-bar", ".cmpbox", "#cmpbox", "#gdpr-consent-tool-wrapper", ".fc-consent-root",
        "[aria-modal=true]", "dialog[open]");

    static Result extract(Context context, String address, String html) {
        if (html == null || html.isEmpty()) return null;
        Document page = Jsoup.parse(html, address);
        String title = metaTitle(page);
        Result cleaned = read(context, address, page.clone(), title, true);
        // If throwing the overlays out left nothing worth reading, the page is read again untouched. Better a
        // page with a cookie notice in front of it than no page at all.
        return isUsable(cleaned) ? cleaned : read(context, address, page, title, false);
    }

    private static Result read(Context context, String address, Document page, String title, boolean removeOverlays) {
        if (removeOverlays) page.select(OVERLAY_SELECTOR).remove();
        Article article;
        try { article = new Readability4J(address, page.outerHtml()).parse(); }
        catch (Exception e) { return null; }
        Element content = article.getArticleContent();
        if (content == null) return null;
        if (title.isEmpty() && article.getTitle() != null) title = article.getTitle().trim();

        String body = withoutRepeatedTitle(plainText(content), title);
        StringBuilder text = new StringBuilder();
        if (!title.isEmpty()) text.append(title).append("\n\n");
        // The author and the date are written only when the page actually says them. A line that announces an
        // unknown date is worse than no line, so nothing stands in for what was not found.
        String credit = joinCredit(author(article.getByline()), publishedDate(context, page));
        if (!credit.isEmpty()) text.append(credit).append("\n\n");
        text.append(body);
        return new Result(address, title, text.toString().trim());
    }

    // Most pages carry the headline both in their metadata and as the first heading of the article itself.
    // Read out loud, that is the title said twice before the text even starts.
    private static String withoutRepeatedTitle(String body, String title) {
        if (title.isEmpty() || body.isEmpty()) return body;
        int firstBreak = body.indexOf("\n");
        String opening = (firstBreak < 0 ? body : body.substring(0, firstBreak)).trim();
        if (!opening.equalsIgnoreCase(title.trim())) return body;
        return firstBreak < 0 ? "" : body.substring(firstBreak).trim();
    }

    // What Readability calls a byline is whatever line sat under the headline, and on many sites that is the
    // time of publication rather than a person. Anything that opens with a figure, or says how long ago the
    // article appeared, is not a name.
    private static String author(String byline) {
        String value = byline == null ? "" : byline.trim().replaceAll("\\s+", " ");
        if (value.length() < 3 || value.length() > 80) return "";
        if (Character.isDigit(value.charAt(0))) return "";
        if (Pattern.compile("\\b(ago|преди)\\b", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE).matcher(value).find()) return "";
        return value;
    }

    private static String joinCredit(String author, String date) {
        if (author.isEmpty()) return date;
        return date.isEmpty() ? author : author + ", " + date;
    }

    private static String metaTitle(Document page) {
        for (String query : new String[]{"meta[property=og:title]", "meta[name=twitter:title]"}) {
            Element tag = page.selectFirst(query);
            if (tag != null && !tag.attr("content").trim().isEmpty()) return tag.attr("content").trim();
        }
        return page.title().trim();
    }

    // Sites announce the date in several places and none of them is guaranteed. Each is tried in turn, and if
    // none of them holds a date that can actually be read, the article simply has no date.
    private static String publishedDate(Context context, Document page) {
        String[] candidates = {
            attribute(page, "meta[property=article:published_time]", "content"),
            attribute(page, "meta[itemprop=datePublished]", "content"),
            attribute(page, "meta[name=date]", "content"),
            attribute(page, "meta[name=pubdate]", "content"),
            // Only a time element that says it is the publication date. Any dated time element at all would
            // pick up the first citation in a reference list and announce it as the date of the article.
            attribute(page, "time[itemprop=datePublished]", "datetime"),
            attribute(page, "time[pubdate]", "datetime"),
            attribute(page, "header time[datetime]", "datetime"),
            jsonLdDate(page)
        };
        for (String candidate : candidates) {
            String formatted = formatDate(context, candidate);
            if (!formatted.isEmpty()) return formatted;
        }
        return "";
    }

    private static String attribute(Document page, String query, String name) {
        Element tag = page.selectFirst(query);
        return tag == null ? "" : tag.attr(name).trim();
    }

    private static String jsonLdDate(Document page) {
        for (Element script : page.select("script[type=application/ld+json]")) {
            Matcher m = Pattern.compile("\"datePublished\"\\s*:\\s*\"([^\"]+)\"").matcher(script.data());
            if (m.find()) return m.group(1);
        }
        return "";
    }

    // Only the day is kept, in the form the phone itself uses for dates. The hour of publication is noise in
    // something that is going to be read out loud.
    private static String formatDate(Context context, String raw) {
        if (raw == null) return "";
        Matcher m = Pattern.compile("(\\d{4})-(\\d{2})-(\\d{2})").matcher(raw.trim());
        if (!m.find()) return "";
        try {
            java.util.Calendar day = java.util.Calendar.getInstance();
            day.clear();
            day.set(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)) - 1, Integer.parseInt(m.group(3)));
            Date date = day.getTime();
            return DateFormat.getMediumDateFormat(context).format(date);
        } catch (Exception e) { return ""; }
    }

    // Readability hands back tidied HTML; the reader needs plain text with the paragraphs still in it. Walking
    // the tree and breaking the line when a block element ends keeps them, which is what the sentence splitter
    // and the reading itself are built around.
    static String plainText(Element content) {
        StringBuilder out = new StringBuilder();
        content.traverse(new NodeVisitor() {
            @Override public void head(Node node, int depth) {
                if (node instanceof TextNode) {
                    String value = ((TextNode)node).text();
                    if (value.trim().isEmpty()) { appendSpace(out); return; }
                    if (Character.isWhitespace(value.charAt(0))) appendSpace(out);
                    out.append(value.trim());
                    if (Character.isWhitespace(value.charAt(value.length() - 1))) appendSpace(out);
                }
            }
            @Override public void tail(Node node, int depth) {
                if (node instanceof Element && isBlock(((Element)node).normalName())) appendBreak(out);
                else if (node instanceof Element && "br".equals(((Element)node).normalName())) appendBreak(out);
            }
        });
        return out.toString().replaceAll("[ \t]+\n", "\n").replaceAll("\n{3,}", "\n\n").trim();
    }

    // Web pages and EPUB chapters are HTML; FB2 has a vocabulary of its own, and its elements are listed here
    // too. Without them a scene break written as a subtitle - the "* * *" between two scenes - is glued to the
    // paragraph that follows it, and a line of verse runs into the next line.
    private static boolean isBlock(String tag) {
        switch (tag) {
            case "p": case "div": case "section": case "article": case "blockquote": case "pre":
            case "li": case "tr": case "figcaption": case "dd": case "dt":
            case "h1": case "h2": case "h3": case "h4": case "h5": case "h6":
            case "title": case "subtitle": case "epigraph": case "annotation": case "cite":
            case "poem": case "stanza": case "v": case "text-author": case "empty-line": return true;
            default: return false;
        }
    }

    private static void appendSpace(StringBuilder out) {
        int length = out.length();
        if (length > 0 && !Character.isWhitespace(out.charAt(length - 1))) out.append(' ');
    }

    private static void appendBreak(StringBuilder out) {
        while (out.length() > 0 && out.charAt(out.length() - 1) == ' ') out.setLength(out.length() - 1);
        if (out.length() == 0) return;
        if (out.length() >= 2 && out.charAt(out.length() - 1) == '\n' && out.charAt(out.length() - 2) == '\n') return;
        out.append(out.charAt(out.length() - 1) == '\n' ? "\n" : "\n\n");
    }
}
