package bg.stefantsvyatkov.voxtxt;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONTokener;

import java.io.ByteArrayInputStream;
import java.util.Locale;

// The way in for pages that will not give a plain request anything useful: sites that build themselves with
// scripts, and sites that answer a first request with a check and only then with the page. The page is loaded
// in a browser that is never shown, and what is taken from it is the finished HTML.
//
// Nothing here is relaxed about security. No certificate error is overridden - onReceivedSslError is left
// alone, so a page with a bad certificate is refused by the system exactly as a browser would refuse it. The
// page is not stored, no cookies are kept between runs, and images are not fetched at all.
final class HiddenPageLoader {

    interface Callback { void onHtml(String html); void onFailure(); }

    private static final long SETTLE_MS = 900;
    private static final long TIMEOUT_MS = 30000;

    // The cookie wall is not part of the page; it is bolted on by a consent tool loaded from one of a handful
    // of well known addresses. Those requests are answered with nothing, so the banner is never built in the
    // first place - which is both quieter and quicker than building it and then throwing it away.
    //
    // Refusing to load a consent tool is not the same as answering it. Nothing is accepted on the reader's
    // behalf, no preference is stored, and no cookie is set. Whether to agree to anything stays a question for
    // the browser, where the page was open and where it was asked.
    private static final String[] CONSENT_HOSTS = {
        "onetrust.com", "cookielaw.org", "cookiepro.com",
        "didomi.io", "quantcast.mgr.consensu.org", "quantserve.com", "quantcast.com",
        "usercentrics.eu", "cookiebot.com", "cookieyes.com", "sourcepoint.mgr.consensu.org",
        "sp-prod.net", "consensu.org", "iubenda.com", "termly.io", "cookie-script.com",
        "funding-choices.google.com", "fundingchoicesmessages.google.com"
    };

    // Read out of the finished page, after one sweep that no list of names can do: anything the page has
    // pinned on top of itself and filled with text. A cookie wall, a consent dialog, a newsletter box and a
    // "subscribe to read on" panel are all built that way, whoever wrote them and whatever they are called.
    // An article is never pinned on top of its own page, so nothing that is being read is at risk; a pinned
    // menu bar carries too little text to qualify, and would be harmless to lose anyway.
    private static final String CLEAN_AND_READ =
        "(function(){try{var b=document.body;if(b){var n=b.querySelectorAll('*'),drop=[],i,s,e;"
        + "for(i=0;i<n.length;i++){e=n[i];s=window.getComputedStyle(e);if(!s)continue;"
        + "if(s.position!=='fixed'&&s.position!=='sticky')continue;"
        + "if((e.textContent||'').trim().length<150)continue;drop.push(e);}"
        + "for(i=0;i<drop.length;i++){if(drop[i].parentNode)drop[i].parentNode.removeChild(drop[i]);}}}"
        + "catch(err){}return document.documentElement.outerHTML;})()";

    private HiddenPageLoader() {}

    static void load(Context context, String address, Callback callback) {
        WebView web;
        try { web = new WebView(context); } catch (Exception e) { callback.onFailure(); return; }
        Handler handler = new Handler(Looper.getMainLooper());
        boolean[] finished = {false};

        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setUserAgentString(ArticleReader.USER_AGENT);
        settings.setLoadsImagesAutomatically(false);
        settings.setBlockNetworkImage(true);
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);

        // The tidy-up is posted rather than run where it stands, because it is reached from inside a callback
        // of the very browser being torn down. Removing what is pending comes first, so the post survives it.
        Runnable done = () -> { handler.removeCallbacksAndMessages(null); web.stopLoading(); web.setWebViewClient(new WebViewClient()); handler.post(web::destroy); };
        Runnable fail = () -> { if (finished[0]) return; finished[0] = true; done.run(); callback.onFailure(); };

        web.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                if (request != null && !request.isForMainFrame() && isConsentTool(request.getUrl()))
                    return new WebResourceResponse("text/plain", "utf-8", new ByteArrayInputStream(new byte[0]));
                return null;
            }
            @Override public void onPageFinished(WebView view, String url) {
                // A moment for the scripts of the page to put the article in place. Reading the tree the
                // instant loading reports itself done would often read an empty frame.
                handler.postDelayed(() -> {
                    if (finished[0]) return;
                    view.evaluateJavascript(CLEAN_AND_READ, value -> {
                        if (finished[0]) return; finished[0] = true;
                        String html = unquote(value);
                        done.run();
                        if (html.isEmpty()) callback.onFailure(); else callback.onHtml(html);
                    });
                }, SETTLE_MS);
            }
            @Override public void onReceivedError(WebView view, WebResourceRequest request, android.webkit.WebResourceError error) {
                if (request != null && request.isForMainFrame()) fail.run();
            }
        });

        // The browser is never shown, so it has no size of its own, and a page with no viewport lays itself
        // out as if it were on nothing. Giving it the size of the screen is what makes a site build the page
        // it would build for this phone - and what makes "pinned on top" mean anything at all.
        int width = context.getResources().getDisplayMetrics().widthPixels;
        int height = context.getResources().getDisplayMetrics().heightPixels;
        web.measure(android.view.View.MeasureSpec.makeMeasureSpec(width, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(height, android.view.View.MeasureSpec.EXACTLY));
        web.layout(0, 0, width, height);

        handler.postDelayed(fail, TIMEOUT_MS);
        web.loadUrl(address);
    }

    private static boolean isConsentTool(android.net.Uri url) {
        if (url == null || url.getHost() == null) return false;
        String host = url.getHost().toLowerCase(Locale.ROOT);
        for (String known : CONSENT_HOSTS) if (host.equals(known) || host.endsWith("." + known)) return true;
        return false;
    }

    // evaluateJavascript hands back a JSON string, so the whole page arrives quoted and escaped.
    private static String unquote(String value) {
        if (value == null || value.isEmpty() || "null".equals(value)) return "";
        try { Object parsed = new JSONTokener(value).nextValue(); return parsed instanceof String ? (String)parsed : ""; }
        catch (Exception e) { return ""; }
    }
}
