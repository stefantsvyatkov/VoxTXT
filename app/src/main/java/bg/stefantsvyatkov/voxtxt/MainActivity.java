package bg.stefantsvyatkov.voxtxt;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.*;
import android.provider.OpenableColumns;
import android.text.*;
import android.text.style.BackgroundColorSpan;
import android.view.*;
import android.widget.*;

import org.json.*;
import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.util.*;
import java.util.concurrent.*;

public class MainActivity extends Activity implements ReaderService.Listener {
    private static final int OPEN_TEXT = 10, MAX_BYTES = 5 * 1024 * 1024;
    private static final long AUTOMATIC_RESUME_DELAY_MS = 1500L, PREVIEW_DELAY_MS = 800L;
    // Each fast seek step schedules the next one only after it has finished its work, so the interval used
    // to be padded by however long redrawing the document took - roughly 150 ms on a full book. Redrawing is
    // cheap now and the interval is honoured exactly, which made the old default of 200 ms feel twice as
    // fast. This is the value that gives the pace the setting always seemed to have.
    private static final int FAST_SEEK_DEFAULT_MS = 400, FAST_SEEK_MIN_MS = 200, FAST_SEEK_MAX_MS = 600;
    private static final String STATE_RESUME_AFTER_RECREATE = "resume_after_recreate";
    private static final String DOCUMENT_PREFS = "reader_documents";
    private static final String[] CYRILLIC_LANGUAGES = {"bg", "ru", "uk", "sr", "mk", "be"};
    private static final String[] CENTRAL_EUROPEAN_LANGUAGES = {"cs", "sk", "pl", "hu", "sl", "hr", "ro", "sq"};
    private static final String READABLE_PUNCTUATION = "!?;:'\"()[]{}<>«»„“”‘’–—-…*/\\%&@#№+=|~^$€£°§";
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private TextView appHeading, title, status, body;
    private ImageButton voiceButton, previous, play, next, sleepButton;
    private Button sleepRewindButton;
    private SeekBar bookProgress;
    private ScrollView scroll;
    private View appRoot;
    private ReaderService reader;
    private boolean bound, bindRequested, loading, destroyed;
    private String currentUri = "", currentName = "";
    private String pendingText;
    private final Handler seekHandler = new Handler(Looper.getMainLooper());
    private final Handler automaticResumeHandler = new Handler(Looper.getMainLooper());
    private final Handler previewHandler = new Handler(Looper.getMainLooper());
    private boolean fastSeeking, resumeAfterFastSeek;
    private boolean pausedAutomaticallyOutsideReader, automaticResumePending, resumeAfterRecreate, resumeAfterFilePickerLoad;
    private boolean showingRecent;
    private String renderedText;
    private BackgroundColorSpan highlightSpan;
    private Runnable subpageCloseAction, previewAction;
    private String lastSearch = "";
    private Button previewButton;
    private boolean previewSpeaking;
    private boolean updatingBookProgress, resumeAfterProgressSeek, draggingBookProgress;
    private final IdentityHashMap<SeekBar, TextView> sliderValues = new IdentityHashMap<>();
    private interface SpinnerSelectionObserver { void onSelected(int position); }
    private static class VoiceSelection { ArrayList<ReaderService.VoiceOption> all = new ArrayList<>(), visible = new ArrayList<>(); }
    private static class LanguageOption { final String tag, label; LanguageOption(String tag, String label) { this.tag = tag; this.label = label; } }
    private static class SeekRange { final int min, max; SeekRange(int min, int max) { this.min = min; this.max = max; } }
    private static class RecentDocument {
        final String uri, name, text;
        RecentDocument(String uri, String name, String text) { this.uri = uri; this.name = name; this.text = text; }
    }
    private class AccessibleSpinner extends Spinner {
        private boolean selectionFromPopup;
        AccessibleSpinner(Context context) { super(context); }
        @Override public boolean performClick() { selectionFromPopup = true; return super.performClick(); }
        boolean consumePopupSelection() { boolean value = selectionFromPopup; selectionFromPopup = false; return value; }
    }
    private class PercentSeekBar extends SeekBar {
        PercentSeekBar(Context context) { super(context); setMax(100); }
        int percent() { return getProgress(); }
        void setPercent(int value) { setProgress(Math.max(0, Math.min(100, value))); }
    }
    private class MinuteSeekBar extends SeekBar {
        MinuteSeekBar(Context context) { super(context); setMax(89); }
        @Override public boolean performAccessibilityAction(int action, Bundle args) { if (action == android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) { setProgress(Math.min(getMax(), getProgress() + 1)); return true; } if (action == android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) { setProgress(Math.max(0, getProgress() - 1)); return true; } return super.performAccessibilityAction(action, args); }
    }
    private class LockedScrollView extends ScrollView {
        LockedScrollView(Context context) { super(context); setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO); }
        @Override public boolean onInterceptTouchEvent(MotionEvent event) { return false; }
        @Override public boolean onTouchEvent(MotionEvent event) { return false; }
        @Override public boolean performAccessibilityAction(int action, Bundle args) {
            if (action == android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD || action == android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) return false;
            return super.performAccessibilityAction(action, args);
        }
    }
    private class BookProgressSeekBar extends SeekBar {
        BookProgressSeekBar(Context context) { super(context); setMax(100); }
        @Override public boolean performAccessibilityAction(int action, Bundle args) {
            if (action == android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                return changeByOnePercent(1);
            if (action == android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                return changeByOnePercent(-1);
            return super.performAccessibilityAction(action, args);
        }
        private boolean changeByOnePercent(int direction) {
            int value = Math.max(0, Math.min(100, getProgress() + direction));
            if (value == getProgress()) return true;
            boolean resume = reader != null && reader.isPlaying();
            setProgress(value);
            seekToBookPercent(value, resume);
            return true;
        }
    }

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, android.os.IBinder service) {
            reader = ((ReaderService.ReaderBinder)service).service(); bound = true; reader.setListener(MainActivity.this);
            setMediaController(reader.getMediaController());
            if (pendingText != null) { finishLoad(pendingText); pendingText = null; }
            if (resumeAfterRecreate) { resumeAfterRecreate = false; scheduleAutomaticPlayback(); }
            updateControls();
        }
        @Override public void onServiceDisconnected(ComponentName name) { setMediaController(null); bound = false; reader = null; updateControls(); }
    };

    @Override public void onCreate(Bundle state) {
        applySavedLanguage();
        String selectedTheme = getSharedPreferences("reader_settings", MODE_PRIVATE).getString("theme", "system");
        if ("light".equals(selectedTheme)) setTheme(R.style.AppThemeLight); else if ("dark".equals(selectedTheme)) setTheme(R.style.AppThemeDark);
        super.onCreate(state); if (Build.VERSION.SDK_INT >= 33) getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, () -> { if (showingRecent) closeRecent(); else finishAfterTransition(); }); buildUi();
        resumeAfterRecreate = state != null && state.getBoolean(STATE_RESUME_AFTER_RECREATE, false);
        bindRequested = bindService(new Intent(this, ReaderService.class), connection, BIND_AUTO_CREATE);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 11);
        Uri incoming = Intent.ACTION_VIEW.equals(getIntent().getAction()) ? getIntent().getData() : null;
        if (incoming != null) loadUri(incoming, true);
        else {
            String last = documents().getString("last_uri", "");
            if (!last.isEmpty()) loadUri(Uri.parse(last), false);
        }
    }

    private void buildUi() {
        showingRecent = false;
        renderedText = null; sliderValues.clear(); previewButton = null; previewAction = null; previewSpeaking = false;
        int pad = dp(16);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(pad, dp(10), pad, dp(12));
        root.setOnApplyWindowInsetsListener((v, insets) -> { v.setPadding(pad, insets.getSystemWindowInsetTop() + dp(10), pad, insets.getSystemWindowInsetBottom() + dp(12)); return insets; });
        appRoot = root; root.setBackgroundColor(appColor(R.color.window_bg));
        appHeading = label(getString(R.string.app_name), 27, true); appHeading.setPadding(0, 0, 0, dp(8)); if (Build.VERSION.SDK_INT >= 28) appHeading.setAccessibilityHeading(true);
        root.addView(appHeading, new LinearLayout.LayoutParams(-1, -2));
        title = label(currentName, 22, true); title.setMaxLines(3); title.setEllipsize(null); title.setPadding(0, 0, 0, currentName.isEmpty() ? 0 : dp(8)); title.setVisibility(currentName.isEmpty() ? View.GONE : View.VISIBLE);
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));
        Button open = button("＋  " + getString(R.string.open_txt)); open.setTextSize(uiSize(19)); open.setOnClickListener(v -> chooseFile()); root.addView(open, new LinearLayout.LayoutParams(-1, dp(60)));
        LinearLayout header = new LinearLayout(this); header.setGravity(Gravity.CENTER_VERTICAL);
        Button recent = compactButton("☰ " + getString(R.string.recent)); recent.setOnClickListener(v -> showRecent());
        Button settings = compactButton("⚙  " + getString(R.string.more)); settings.setContentDescription(getString(R.string.more)); settings.setOnClickListener(v -> showMoreMenu());
        recent.setTextSize(uiSize(17)); settings.setTextSize(uiSize(17)); header.addView(recent, new LinearLayout.LayoutParams(0, dp(52), 1)); header.addView(settings, new LinearLayout.LayoutParams(0, dp(52), 1));
        root.addView(header);
        LinearLayout sentenceRow = new LinearLayout(this); sentenceRow.setGravity(Gravity.CENTER_VERTICAL);
        status = label(getString(R.string.welcome), 17, false); status.setTextColor(appColor(R.color.text_secondary)); status.setPadding(0, dp(7), 0, dp(8)); sentenceRow.addView(status, new LinearLayout.LayoutParams(0, -2, 1));
        Button jump = compactButton(getString(R.string.go_to_sentence)); jump.setTextSize(uiSize(16)); jump.setOnClickListener(v -> showGoToSentenceDialog()); sentenceRow.addView(jump, new LinearLayout.LayoutParams(-2, dp(52))); root.addView(sentenceRow);

        body = label("", 20, false); body.setTextSize(getSettings().getInt("font_size", 23)); body.setTextIsSelectable(false); body.setLongClickable(false); body.setLineSpacing(0, 1.3f); body.setPadding(pad, pad, pad, pad);
        body.setBackgroundColor(appColor(R.color.panel_bg));
        scroll = new LockedScrollView(this); scroll.setFillViewport(true); scroll.addView(body, new ScrollView.LayoutParams(-1, -2));
        LinearLayout.LayoutParams content = new LinearLayout.LayoutParams(-1, 0, 1); content.setMargins(0, dp(10), 0, dp(10)); root.addView(scroll, content);

        LinearLayout playerPanel = new LinearLayout(this); playerPanel.setOrientation(LinearLayout.VERTICAL); playerPanel.setGravity(Gravity.BOTTOM); playerPanel.setPadding(dp(12), 0, dp(12), 0); playerPanel.setBackgroundColor(appColor(R.color.panel_bg));
        TextView progressValue = addSliderHeader(playerPanel, R.string.book_progress, 17, 0);
        bookProgress = new BookProgressSeekBar(this);
        sliderValues.put(bookProgress, progressValue);
        bookProgress.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onStartTrackingTouch(SeekBar seekBar) { draggingBookProgress = true; resumeAfterProgressSeek = reader != null && reader.isPlaying(); if (resumeAfterProgressSeek) reader.pause(); }
            public void onStopTrackingTouch(SeekBar seekBar) { draggingBookProgress = false; seekToBookPercent(seekBar.getProgress(), false); if (resumeAfterProgressSeek && reader != null) scheduleAutomaticPlayback(); resumeAfterProgressSeek = false; }
            // A finger drag fires this for every percent; seeking there would stop the engine and repaint the
            // whole document dozens of times, so the drag is applied once on release. Keyboard and TalkBack
            // changes arrive without tracking events and still take effect immediately.
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) { updateBookProgressDescription(progress); if (fromUser && !updatingBookProgress && !draggingBookProgress) seekToBookPercent(progress, false); }
        });
        LinearLayout playerButtons = new LinearLayout(this); playerButtons.setGravity(Gravity.CENTER);
        voiceButton = imageButton(R.drawable.ic_voice, R.string.choose_voice); previous = imageButton(R.drawable.ic_previous, R.string.previous_sentence); play = imageButton(R.drawable.ic_play, R.string.play_sentence); next = imageButton(R.drawable.ic_next, R.string.next_sentence); sleepButton = imageButton(R.drawable.ic_sleep, R.string.open_sleep_timer); sleepRewindButton = button("");
        voiceButton.setOnClickListener(v -> showVoiceSettings()); sleepButton.setOnClickListener(v -> showSleepDialog());
        sleepRewindButton.setOnClickListener(v -> { if (reader != null) reader.rewindCompletedSleepTimer(); }); sleepRewindButton.setVisibility(View.INVISIBLE);
        playerPanel.addView(bookProgress, new LinearLayout.LayoutParams(-1, dp(52)));
        attachSeekButton(previous, -1);
        play.setOnClickListener(v -> { if (reader == null) return; cancelAutomaticResume(true); if (reader.isPlaying()) reader.pause(); else reader.play(); });
        attachSeekButton(next, 1);
        addPlayerButton(playerButtons, voiceButton); addPlayerButton(playerButtons, previous); addPlayerButton(playerButtons, play); addPlayerButton(playerButtons, next); addPlayerButton(playerButtons, sleepButton);
        playerPanel.addView(playerButtons, new LinearLayout.LayoutParams(-1, dp(64)));
        playerPanel.addView(sleepRewindButton, new LinearLayout.LayoutParams(-1, dp(58)));
        LinearLayout.LayoutParams panelParams = new LinearLayout.LayoutParams(-1, dp(200)); panelParams.setMargins(0, dp(4), 0, dp(4)); root.addView(playerPanel, panelParams); setContentView(root); updateControls(); if (reader != null) showCurrent(reader.getCurrent(), reader.getCount()); root.requestApplyInsets(); focusHeading(appHeading);
    }
    private TextView label(String value, int sp, boolean bold) { TextView v = new TextView(this); v.setText(value); v.setTextSize(uiSize(sp)); v.setTextColor(appColor(R.color.text_primary)); if (bold) v.setTypeface(v.getTypeface(), android.graphics.Typeface.BOLD); return v; }
    private Button button(String value) { Button b = new Button(this); b.setText(value); b.setTextSize(uiSize(17)); b.setAllCaps(false); return b; }
    private ImageButton imageButton(int icon, int description) { ImageButton b = new ImageButton(this); b.setImageResource(icon); b.setScaleType(ImageView.ScaleType.CENTER_INSIDE); b.setImageTintList(android.content.res.ColorStateList.valueOf(appColor(R.color.text_primary))); b.setContentDescription(getString(description)); b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.TRANSPARENT)); b.setPadding(dp(7), dp(7), dp(7), dp(7)); return b; }
    private void addPlayerButton(LinearLayout row, ImageButton button) { FrameLayout column = new FrameLayout(this); column.addView(button, new FrameLayout.LayoutParams(dp(56), dp(62), Gravity.CENTER)); row.addView(column, new LinearLayout.LayoutParams(0, dp(64), 1)); }
    private Button compactButton(String value) { Button b = button(value); b.setMinWidth(0); b.setMinimumWidth(0); b.setPadding(dp(12), 0, dp(12), 0); return b; }
    private int dp(int value) { return (int)(value * getResources().getDisplayMetrics().density + .5f); }
    private float uiSize(float base) { return base * getSettings().getInt("interface_scale", 100) / 100f; }
    private int appColor(int resource) { String theme = getSettings().getString("theme", "system"); if ("dark".equals(theme)) { if (resource == R.color.text_secondary) return Color.rgb(208,208,208); if (resource == R.color.highlight) return Color.rgb(0,82,128); return resource == R.color.text_primary || resource == R.color.accent ? Color.WHITE : Color.BLACK; } if ("light".equals(theme)) { if (resource == R.color.text_secondary) return Color.rgb(51,51,51); if (resource == R.color.highlight) return Color.rgb(125,183,232); return resource == R.color.text_primary || resource == R.color.accent ? Color.BLACK : Color.WHITE; } return getColor(resource); }
    private android.content.SharedPreferences getSettings() { return getSharedPreferences("reader_settings", MODE_PRIVATE); }
    // Explicitly named so it no longer depends on the class package the way Activity.getPreferences() does.
    private android.content.SharedPreferences documents() { return getSharedPreferences(DOCUMENT_PREFS, MODE_PRIVATE); }
    // Clamped, because a value saved under the earlier range could sit below what the slider now offers.
    private int fastSeekInterval(android.content.SharedPreferences p) {
        return Math.max(FAST_SEEK_MIN_MS, Math.min(FAST_SEEK_MAX_MS, p.getInt("fast_seek_interval", FAST_SEEK_DEFAULT_MS)));
    }

    private void chooseFile() {
        pausePlaybackOutsideReader();
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("text/plain").addCategory(Intent.CATEGORY_OPENABLE);
        try { startActivityForResult(i, OPEN_TEXT); } catch (ActivityNotFoundException e) { toast(getString(R.string.open_failed)); returnToReader(); }
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == OPEN_TEXT && result == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Exception ignored) {}
            resumeAfterFilePickerLoad = pausedAutomaticallyOutsideReader; pausedAutomaticallyOutsideReader = false;
            loadUri(uri, true);
        } else if (request == OPEN_TEXT) returnToReader();
    }
    private void loadUri(Uri uri, boolean remember) {
        if (loading) return; loading = true; if (reader != null) reader.pause(); status.setText(R.string.loading); updateControls();
        io.execute(() -> {
            try {
                String fileName = displayFileName(uri);
                if (fileName == null || !fileName.toLowerCase(Locale.ROOT).endsWith(".txt")) throw new IOException(getString(R.string.txt_files_only));
                byte[] bytes = readLimited(uri); String loaded = decode(bytes).replace("\r\n", "\n").replace('\r', '\n');
                if (loaded.trim().isEmpty()) throw new IOException(getString(R.string.file_empty));
                String name = withoutTxtExtension(fileName); runOnUiThread(() -> {
                    if (destroyed) return; currentUri = uri.toString(); currentName = name; title.setText(name); title.setVisibility(View.VISIBLE); title.setPadding(0, 0, 0, dp(8)); loading = false;
                    if (remember) documents().edit().putString("last_uri", currentUri).apply();
                    addRecent(currentUri, currentName); if (reader == null) pendingText = loaded; else finishLoad(loaded);
                });
            } catch (Exception e) { runOnUiThread(() -> { loading = false; status.setText(R.string.open_failed); toast(e.getMessage()); updateControls(); if (resumeAfterFilePickerLoad) { resumeAfterFilePickerLoad = false; scheduleAutomaticPlayback(); } }); }
        });
    }
    private void finishLoad(String loaded) { int position = reader.savedPosition(currentUri); reader.load(currentUri, currentName, loaded, position); if (resumeAfterFilePickerLoad) { resumeAfterFilePickerLoad = false; scheduleAutomaticPlayback(); } }
    private byte[] readLimited(Uri uri) throws IOException {
        try (InputStream in = getContentResolver().openInputStream(uri); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if (in == null) throw new IOException(getString(R.string.file_unavailable)); byte[] buf = new byte[8192]; int total = 0, n;
            while ((n = in.read(buf)) != -1) { total += n; if (total > MAX_BYTES) throw new IOException(getString(R.string.file_too_large)); out.write(buf, 0, n); } return out.toByteArray();
        }
    }
    private String decode(byte[] b) {
        if (b.length >= 3 && b[0] == (byte)0xEF && b[1] == (byte)0xBB && b[2] == (byte)0xBF) return new String(b, 3, b.length - 3, StandardCharsets.UTF_8);
        if (b.length >= 2 && b[0] == (byte)0xFF && b[1] == (byte)0xFE) return new String(b, 2, b.length - 2, StandardCharsets.UTF_16LE);
        if (b.length >= 2 && b[0] == (byte)0xFE && b[1] == (byte)0xFF) return new String(b, 2, b.length - 2, StandardCharsets.UTF_16BE);
        try { return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(b)).toString(); }
        catch (CharacterCodingException notUtf8) { /* an older single or double byte encoding */ }
        String utf16 = decodeUnmarkedUtf16(b);
        return utf16 != null ? utf16 : decodeLegacy(b);
    }
    // UTF-16 without a byte order mark shows up as one zero byte in every pair.
    private String decodeUnmarkedUtf16(byte[] b) {
        if (b.length < 16 || b.length % 2 != 0) return null;
        int limit = Math.min(b.length, 8192), pairs = limit / 2, even = 0, odd = 0;
        for (int i = 0; i + 1 < limit; i += 2) { if (b[i] == 0) even++; if (b[i + 1] == 0) odd++; }
        if (odd > pairs * 0.3f && even < pairs * 0.05f) return new String(b, StandardCharsets.UTF_16LE);
        if (even > pairs * 0.3f && odd < pairs * 0.05f) return new String(b, StandardCharsets.UTF_16BE);
        return null;
    }
    // Old TXT books carry no marker of their encoding, so every candidate is decoded and the most
    // text-like result wins. Cyrillic books read as Western European are the case that matters most here.
    private String decodeLegacy(byte[] b) {
        String best = null; long bestScore = Long.MIN_VALUE;
        for (String name : legacyCharsets()) {
            Charset charset;
            try { charset = Charset.forName(name); } catch (Exception unsupported) { continue; }
            String candidate = new String(b, charset);
            long score = plausibility(candidate);
            if (score > bestScore) { bestScore = score; best = candidate; }
        }
        return best != null ? best : new String(b, StandardCharsets.ISO_8859_1);
    }
    private long plausibility(String text) {
        long score = 0; int limit = Math.min(text.length(), 128 * 1024);
        int ascii = 0, accented = 0, cyrillic = 0, greek = 0, word = 0, preferred = 0;
        char script = preferredScript();
        for (int i = 0; i <= limit; i++) {
            char c = i < limit ? text.charAt(i) : ' ';
            if (Character.isLetter(c)) {
                word++;
                if (c < 128) ascii++;
                else if (c < 0x0250) accented++;
                else if (c >= 0x0400 && c <= 0x04FF) { cyrillic++; if (script == 'c') preferred++; }
                else if (c >= 0x0370 && c <= 0x03FF) { greek++; if (script == 'g') preferred++; }
                continue;
            }
            if (word > 0) { score += scoreWord(word, ascii, accented, cyrillic, greek); ascii = accented = cyrillic = greek = word = 0; }
            if (c == ' ' || c == '\n' || c == '\r' || c == '\t' || c == '.' || c == ',') score += 2;
            else if (c < 0x20 || (c >= 0x7F && c <= 0x9F)) score -= 60;
            else if (Character.isDigit(c) || READABLE_PUNCTUATION.indexOf(c) >= 0) score += 1;
            else score -= 8;
        }
        return score + preferred;
    }
    private long scoreWord(int length, int ascii, int accented, int cyrillic, int greek) {
        int scripts = (ascii + accented > 0 ? 1 : 0) + (cyrillic > 0 ? 1 : 0) + (greek > 0 ? 1 : 0);
        if (scripts > 1) return -10L * length;                                 // no real word mixes alphabets
        if (accented > 0 && ascii == 0 && length > 1) return -6L * length;     // Latin words are not all accents
        return 3L * length;
    }
    // Several encodings can produce equally plausible text from the same bytes - Czech in windows-1250 and
    // in windows-1252 differ only in which accents appear - so the reader's own language breaks the tie.
    private String[] legacyCharsets() {
        String language = Locale.getDefault().getLanguage();
        if (matches(CYRILLIC_LANGUAGES, language)) return new String[]{"windows-1251", "windows-1252", "windows-1250", "ISO-8859-7"};
        if ("el".equals(language)) return new String[]{"ISO-8859-7", "windows-1252", "windows-1250", "windows-1251"};
        if (matches(CENTRAL_EUROPEAN_LANGUAGES, language)) return new String[]{"windows-1250", "windows-1252", "windows-1251", "ISO-8859-7"};
        return new String[]{"windows-1252", "windows-1250", "windows-1251", "ISO-8859-7"};
    }
    private char preferredScript() {
        String language = Locale.getDefault().getLanguage();
        if (matches(CYRILLIC_LANGUAGES, language)) return 'c';
        return "el".equals(language) ? 'g' : 'l';
    }
    private boolean matches(String[] languages, String value) { for (String language : languages) if (language.equals(value)) return true; return false; }
    private String displayFileName(Uri uri) {
        try (android.database.Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) { if (c != null && c.moveToFirst()) return c.getString(0); }
        catch (Exception ignored) {}
        String segment = uri.getLastPathSegment(); return segment == null ? null : Uri.decode(segment);
    }
    private String withoutTxtExtension(String value) { return value != null && value.toLowerCase(Locale.ROOT).endsWith(".txt") ? value.substring(0, value.length() - 4) : value; }

    @Override public void onPlaybackState(int index, int count, boolean playing) { runOnUiThread(() -> { if (playing && (pausedAutomaticallyOutsideReader || automaticResumePending)) cancelAutomaticResume(true); showCurrent(index, count); play.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play); play.setContentDescription(getString(playing ? R.string.pause_sentence : R.string.play_sentence)); if (reader != null && reader.isSleepRewindAvailable()) { int minutes = reader.getCompletedSleepMinutes(); sleepRewindButton.setText(getResources().getQuantityString(R.plurals.rewind_sleep_minutes, minutes, minutes)); sleepRewindButton.setVisibility(View.VISIBLE); } else { sleepRewindButton.setText(""); sleepRewindButton.setVisibility(View.INVISIBLE); } updateControls(); }); }
    @Override public void onPlaybackError(String message) { runOnUiThread(() -> toast(message)); }
    private void showCurrent(int index, int count) {
        if (reader == null || reader.getText().isEmpty()) return;
        String document = reader.getText();
        if (count == 0) { renderedText = null; body.setText(document); body.setContentDescription(getString(R.string.no_text)); status.setText(R.string.no_text); return; }
        ReaderService.Range r = reader.currentRange();
        // The document text is handed to the TextView once; every following sentence only moves the
        // highlight span. Re-creating a SpannableString of the whole book per sentence is what made
        // large files stutter.
        if (document != renderedText || !(body.getText() instanceof Spannable)) {
            renderedText = document; highlightSpan = new BackgroundColorSpan(appColor(R.color.highlight));
            body.setText(new SpannableString(document), TextView.BufferType.SPANNABLE);
        }
        Spannable marked = (Spannable)body.getText();
        marked.removeSpan(highlightSpan);
        marked.setSpan(highlightSpan, r.start, r.end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        body.setContentDescription(document.substring(r.start, r.end).trim());
        status.setText(getString(R.string.sentence_count, index + 1, count));
        int percent = count <= 1 ? 0 : Math.round(index * 100f / (count - 1));
        updatingBookProgress = true; bookProgress.setProgress(percent); updatingBookProgress = false; updateBookProgressDescription(percent);
        body.post(() -> { android.text.Layout l = body.getLayout(); if (l != null) scroll.smoothScrollTo(0, Math.max(0, l.getLineTop(l.getLineForOffset(r.start)) - dp(80))); });
    }
    private void updateControls() { boolean has = bound && reader != null && reader.getCount() > 0 && !loading; int i = has ? reader.getCurrent() : 0; previous.setEnabled(has && i > 0); next.setEnabled(has && i + 1 < reader.getCount()); play.setEnabled(has && reader.isReady()); bookProgress.setEnabled(has); }

    private void pausePlaybackOutsideReader() {
        cancelAutomaticResume(false);
        if (reader != null && reader.isPlaying() && getSettings().getBoolean("pause_for_settings", true)) {
            pausedAutomaticallyOutsideReader = true;
            reader.pause();
        }
    }
    private void returnToReader() {
        if (!pausedAutomaticallyOutsideReader) return;
        scheduleAutomaticPlayback();
    }
    private void scheduleAutomaticPlayback() {
        automaticResumeHandler.removeCallbacksAndMessages(null);
        pausedAutomaticallyOutsideReader = true;
        automaticResumePending = true;
        automaticResumeHandler.postDelayed(() -> {
            if (!automaticResumePending) return;
            automaticResumePending = false;
            pausedAutomaticallyOutsideReader = false;
            if (!destroyed && reader != null && reader.getCount() > 0 && !reader.isPlaying()) reader.play();
        }, AUTOMATIC_RESUME_DELAY_MS);
    }
    private void cancelAutomaticResume(boolean clearOutsidePause) {
        automaticResumeHandler.removeCallbacksAndMessages(null);
        automaticResumePending = false;
        if (clearOutsidePause) pausedAutomaticallyOutsideReader = false;
    }

    private void seekToBookPercent(int percent, boolean resume) {
        if (reader == null || reader.getCount() == 0) return;
        int index = reader.getCount() <= 1 ? 0 : Math.round(percent * (reader.getCount() - 1) / 100f);
        reader.seekTo(index);
        if (resume) scheduleAutomaticPlayback();
    }
    private void updateBookProgressDescription(int percent) {
        updatePercentValue(bookProgress, percent);
    }

    private void attachSeekButton(ImageButton button, int direction) {
        button.setOnClickListener(v -> { if (reader != null && !fastSeeking) { boolean announce = !reader.isPlaying(); reader.move(direction); if (announce) announceCurrentSentence(); } });
        button.setOnLongClickListener(v -> {
            if (reader == null || reader.getCount() == 0) return false;
            // A resume left over from the previous release must not fire while this seek is under way.
            cancelAutomaticResume(true);
            fastSeeking = true; resumeAfterFastSeek = reader.isPlaying(); if (resumeAfterFastSeek) reader.pause();
            Runnable repeated = new Runnable() { @Override public void run() { if (!fastSeeking || reader == null) return; reader.move(direction * 5); if (getSettings().getBoolean("seek_vibration", true)) button.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); seekHandler.postDelayed(this, fastSeekInterval(getSettings())); } };
            repeated.run(); return true;
        });
        // One rule for both kinds of seeking: while the book is silent the screen reader says where the user
        // has landed, and while it is reading nothing interrupts it - the text itself is the answer. Letting
        // go resumes at once; the delay used elsewhere exists to leave room for an announcement, and there
        // is none here.
        button.setOnTouchListener((v, event) -> { if ((event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) && fastSeeking) { fastSeeking = false; seekHandler.removeCallbacksAndMessages(null); boolean resume = resumeAfterFastSeek; resumeAfterFastSeek = false; if (resume && reader != null) reader.play(); else announceCurrentSentence(); } return false; });
    }
    private void announceCurrentSentence() { if (reader == null || reader.getCount() == 0) return; status.announceForAccessibility(getString(R.string.sentence_position, reader.getCurrent() + 1)); }

    private void showGoToSentenceDialog() {
        if (reader == null || reader.getCount() == 0) return; pausePlaybackOutsideReader(); final boolean[] applied = {false}; EditText input = new EditText(this); input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER); input.setTextSize(uiSize(20)); input.setHint("1-" + reader.getCount()); input.setContentDescription(getString(R.string.sentence_number)); input.setPadding(dp(24), dp(12), dp(24), dp(12));
        LinearLayout content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL); content.setPadding(dp(24), dp(12), dp(24), 0);
        TextView heading = label(getString(R.string.go_to_sentence), 23, true); if (Build.VERSION.SDK_INT >= 28) heading.setAccessibilityHeading(true); content.addView(heading); content.addView(input, new LinearLayout.LayoutParams(-1, -2));
        AlertDialog dialog = new AlertDialog.Builder(this).setView(content).setNegativeButton(R.string.cancel, null).setPositiveButton(R.string.apply, (d, w) -> {
            try { int number = Integer.parseInt(input.getText().toString()); if (number < 1 || number > reader.getCount()) { toast(getString(R.string.invalid_sentence, reader.getCount())); return; } applied[0] = true; reader.seekTo(number - 1); pausedAutomaticallyOutsideReader = false; scheduleAutomaticPlayback(); }
            catch (NumberFormatException e) { toast(getString(R.string.invalid_sentence, reader.getCount())); }
        }).create(); dialog.setOnDismissListener(d -> { if (!applied[0]) returnToReader(); }); dialog.show(); focusHeading(heading);
    }

    // One entry point for everything that is not the reading itself. A plain list is used rather than a
    // floating menu: it is themed like the rest of the app and a screen reader walks it top to bottom.
    private void showMoreMenu() {
        pausePlaybackOutsideReader();
        String[] items = {getString(R.string.bookmarks), getString(R.string.search), getString(R.string.settings)};
        final boolean[] chosen = {false};
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(R.string.more).setItems(items, (d, which) -> {
            chosen[0] = true;
            if (which == 0) showBookmarks(); else if (which == 1) showSearchDialog(); else showSettings();
        }).create();
        dialog.setOnDismissListener(d -> { if (!chosen[0]) returnToReader(); });
        dialog.show(); focusDialogTitle(dialog);
    }

    private void showBookmarks() {
        if (reader == null) return;
        LinearLayout box = listPage();
        Button add = button(getString(R.string.add_bookmark));
        add.setOnClickListener(v -> { addBookmark(); showBookmarks(); });
        box.addView(add, new LinearLayout.LayoutParams(-1, dp(58)));
        JSONArray marks = bookmarks();
        if (marks.length() == 0) box.addView(emptyNotice(getString(R.string.no_bookmarks)));
        for (int i = 0; i < marks.length(); i++) {
            JSONObject mark = marks.optJSONObject(i); if (mark == null) continue;
            int sentence = mark.optInt("sentence"); String name = mark.optString("text");
            LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(0, dp(6), 0, dp(6));
            Button open = listRowButton(name, 18);
            open.setOnClickListener(v -> jumpFromList(sentence));
            ImageButton remove = imageButton(android.R.drawable.ic_menu_delete, R.string.delete); remove.setContentDescription(getString(R.string.remove_bookmark, name));
            remove.setOnClickListener(v -> { removeBookmark(sentence); showBookmarks(); });
            row.addView(open, new LinearLayout.LayoutParams(0, -2, 1));
            LinearLayout.LayoutParams removeParams = new LinearLayout.LayoutParams(dp(56), dp(56)); removeParams.setMargins(dp(10), 0, 0, 0); row.addView(remove, removeParams);
            box.addView(row);
        }
        showListPage(R.string.bookmarks, box);
    }

    private void showSearchDialog() {
        if (reader == null || reader.getCount() == 0) { returnToReader(); return; }
        final boolean[] applied = {false};
        EditText input = new EditText(this); input.setTextSize(uiSize(20)); input.setHint(getString(R.string.search_phrase)); input.setContentDescription(getString(R.string.search_phrase)); input.setPadding(dp(24), dp(12), dp(24), dp(12));
        input.setText(lastSearch); input.setSelectAllOnFocus(true);
        LinearLayout content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL); content.setPadding(dp(24), dp(12), dp(24), dp(12));
        TextView heading = label(getString(R.string.search), 23, true); if (Build.VERSION.SDK_INT >= 28) heading.setAccessibilityHeading(true);
        content.addView(heading); content.addView(input, new LinearLayout.LayoutParams(-1, -2));
        // The dialog stays open on a hit and the book itself starts reading from the sentence found, in its
        // own voice - exactly as if the reading had been started from that place. What is heard is then the
        // book and not a description of it, and judging whether this is the right place is simply listening.
        //
        // The three buttons belong to the dialog itself rather than to the standard row underneath it. That
        // row decides on its own whether the captions fit side by side or have to be stacked, reverses their
        // order when it stacks them, and resizes the dialog whenever a caption changes. Three equal buttons
        // in the order previous, next, close always stand the same way and are read in that order. What the
        // screen reader says is what is written on them, so nobody is told a different thing than the person
        // sitting next to them sees.
        LinearLayout buttons = new LinearLayout(this); buttons.setPadding(0, dp(14), 0, 0); buttons.setGravity(Gravity.CENTER);
        Button previousMatch = searchButton(R.string.previous_result);
        Button nextMatch = searchButton(R.string.next_result);
        Button closeSearch = searchButton(R.string.close);
        buttons.addView(previousMatch, new LinearLayout.LayoutParams(-2, -2, 1));
        buttons.addView(nextMatch, new LinearLayout.LayoutParams(-2, -2, 1));
        buttons.addView(closeSearch, new LinearLayout.LayoutParams(-2, -2, 1));
        content.addView(buttons, new LinearLayout.LayoutParams(-1, -2));
        AlertDialog dialog = new AlertDialog.Builder(this).setView(content).create();
        dialog.setOnDismissListener(d -> {
            // After a hit the book is already reading; only the flag that would start it a second time is
            // cleared. Without a hit nothing was touched, so the reading goes back to what it was.
            if (applied[0]) cancelAutomaticResume(true); else returnToReader();
        });
        previousMatch.setOnClickListener(v -> playMatch(input, applied, -1));
        nextMatch.setOnClickListener(v -> playMatch(input, applied, 1));
        closeSearch.setOnClickListener(v -> dialog.dismiss());
        dialog.show();
        focusHeading(heading);
    }
    private Button searchButton(int caption) {
        Button b = compactButton(getString(caption)); b.setTextSize(uiSize(17));
        b.setPadding(dp(6), dp(10), dp(6), dp(10)); b.setMinimumHeight(dp(56));
        return b;
    }
    private void playMatch(EditText input, boolean[] applied, int direction) {
        if (reader == null) return;
        lastSearch = input.getText().toString().trim();
        int found = direction > 0 ? reader.findSentence(lastSearch, reader.getCurrent()) : reader.findPreviousSentence(lastSearch, reader.getCurrent());
        if (found < 0) { toast(getString(R.string.not_found)); return; }
        applied[0] = true;
        cancelAutomaticResume(true);
        reader.seekTo(found); reader.play();
    }

    // Lands the reader on a sentence chosen from a list and leaves the page behind.
    private void jumpFromList(int sentence) {
        if (reader == null) return;
        reader.seekTo(sentence);
        pausedAutomaticallyOutsideReader = false;
        closeRecent();
        scheduleAutomaticPlayback();
    }
    // A row of a list carries a whole sentence or a whole file name, so it wraps over as many lines as it
    // needs and the row grows with it. It is an entry in a list and not a button to press, so it carries no
    // grey slab behind its text - only the standard touch highlight, which appears while the finger is down
    // and leaves nothing behind it. It stays a button underneath, so a screen reader still announces it as
    // something that can be opened.
    private Button listRowButton(String name, int textSize) {
        Button row = compactButton(name); row.setGravity(Gravity.START | Gravity.CENTER_VERTICAL); row.setTextSize(uiSize(textSize)); row.setContentDescription(name);
        android.util.TypedValue highlight = new android.util.TypedValue(); getTheme().resolveAttribute(android.R.attr.selectableItemBackground, highlight, true);
        row.setBackgroundResource(highlight.resourceId);
        // After the background, because a new one brings its own padding along and would undo this.
        row.setPadding(dp(16), dp(14), dp(16), dp(14)); row.setMinimumHeight(dp(64));
        return row;
    }
    private LinearLayout listPage() { LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(dp(4), 0, dp(4), 0); return box; }
    private TextView emptyNotice(String message) { TextView empty = label(message, 19, false); empty.setPadding(0, dp(24), 0, dp(24)); return empty; }
    private void showListPage(int headingResource, LinearLayout box) { showSettingsPage(headingResource, box, null, null, null); }

    private JSONArray bookmarks() {
        try { JSONObject all = new JSONObject(documents().getString("bookmarks", "{}")); JSONArray marks = all.optJSONArray(currentUri); if (marks != null) return marks; }
        catch (JSONException ignored) {}
        return new JSONArray();
    }
    private void saveBookmarks(JSONArray marks) {
        try { JSONObject all = new JSONObject(documents().getString("bookmarks", "{}")); all.put(currentUri, marks); documents().edit().putString("bookmarks", all.toString()).apply(); }
        catch (JSONException ignored) {}
    }
    private void addBookmark() {
        if (reader == null || reader.getCount() == 0 || currentUri.isEmpty()) return;
        int sentence = reader.getCurrent();
        // The whole sentence. Cut short, it was exactly the missing half that said which place this is.
        String preview = reader.sentenceText(sentence);
        if (preview.isEmpty()) preview = getString(R.string.sentence_position, sentence + 1);
        JSONArray marks = bookmarks(), fresh = new JSONArray();
        try {
            boolean inserted = false;
            for (int i = 0; i < marks.length(); i++) {
                JSONObject mark = marks.getJSONObject(i);
                if (mark.optInt("sentence") == sentence) { toast(getString(R.string.bookmark_exists)); return; }
                if (!inserted && mark.optInt("sentence") > sentence) { fresh.put(new JSONObject().put("sentence", sentence).put("text", preview)); inserted = true; }
                fresh.put(mark);
            }
            if (!inserted) fresh.put(new JSONObject().put("sentence", sentence).put("text", preview));
        } catch (JSONException e) { return; }
        saveBookmarks(fresh); toast(getString(R.string.bookmark_added));
    }
    private void removeBookmark(int sentence) {
        JSONArray marks = bookmarks(), fresh = new JSONArray();
        for (int i = 0; i < marks.length(); i++) { JSONObject mark = marks.optJSONObject(i); if (mark != null && mark.optInt("sentence") != sentence) fresh.put(mark); }
        saveBookmarks(fresh);
    }

    private void showSettings() {
        android.content.SharedPreferences p = getSettings();
        pausePlaybackOutsideReader(); sliderValues.clear();
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(dp(20), 0, dp(20), 0);
        TextView languageLabel = label(getString(R.string.language), 18, true); languageLabel.setPadding(0, dp(12), 0, 0); box.addView(languageLabel);
        AccessibleSpinner languageSpinner = new AccessibleSpinner(this); String[] languageValues = {"system", "en", "bg"}; String[] languageLabels = {getString(R.string.language_system), getString(R.string.language_english), getString(R.string.language_bulgarian)}; languageSpinner.setAdapter(themedSpinnerAdapter(languageLabels)); int languagePosition = Arrays.asList(languageValues).indexOf(p.getString("language", "system")); languageSpinner.setSelection(Math.max(0, languagePosition), false); box.addView(languageSpinner); configureSpinnerAccessibility(languageSpinner, languageLabels);
        TextView themeLabel = label(getString(R.string.theme), 18, true); themeLabel.setPadding(0, dp(12), 0, 0); box.addView(themeLabel);
        AccessibleSpinner themeSpinner = new AccessibleSpinner(this); String[] themeValues = {"system", "light", "dark"}; String[] themeLabels = {getString(R.string.theme_system), getString(R.string.theme_light), getString(R.string.theme_dark)}; themeSpinner.setAdapter(themedSpinnerAdapter(themeLabels)); int themePosition = Arrays.asList(themeValues).indexOf(p.getString("theme", "system")); themeSpinner.setSelection(Math.max(0, themePosition), false); box.addView(themeSpinner); configureSpinnerAccessibility(themeSpinner, themeLabels);
        SeekBar font = seek(box, R.string.document_font_size, 14, 32, p.getInt("font_size", 23));
        TextView interfaceValueLabel = addSliderHeader(box, R.string.interface_font_size, 18, dp(12)); SeekBar interfaceFont = new SeekBar(this); interfaceFont.setMax(20); int originalInterfaceScale = p.getInt("interface_scale", 100); interfaceFont.setProgress(Math.max(0, Math.min(20, (originalInterfaceScale - 50) / 5))); sliderValues.put(interfaceFont, interfaceValueLabel); updateSliderPercentValue(interfaceFont); box.addView(interfaceFont);
        SeekBar fastSeekInterval = millisecondSeek(box, R.string.fast_seek_interval, FAST_SEEK_MIN_MS, FAST_SEEK_MAX_MS, 50, fastSeekInterval(p));
        CheckBox pauseForSettings = new CheckBox(this); pauseForSettings.setText(R.string.pause_for_settings); pauseForSettings.setTextSize(uiSize(18)); pauseForSettings.setChecked(p.getBoolean("pause_for_settings", true)); pauseForSettings.setPadding(0, dp(10), 0, dp(6)); box.addView(pauseForSettings, new LinearLayout.LayoutParams(-1, -2));
        CheckBox seekVibration = new CheckBox(this); seekVibration.setText(R.string.seek_vibration); seekVibration.setTextSize(uiSize(18)); seekVibration.setChecked(p.getBoolean("seek_vibration", true)); seekVibration.setPadding(0, dp(6), 0, dp(10)); box.addView(seekVibration, new LinearLayout.LayoutParams(-1, -2));
        CheckBox preventDeviceAutoplay = new CheckBox(this); preventDeviceAutoplay.setText(R.string.prevent_device_autoplay); preventDeviceAutoplay.setTextSize(uiSize(18)); preventDeviceAutoplay.setChecked(p.getBoolean("prevent_device_autoplay", true)); preventDeviceAutoplay.setPadding(0, dp(6), 0, dp(10)); box.addView(preventDeviceAutoplay, new LinearLayout.LayoutParams(-1, -2));
        CheckBox textCues = new CheckBox(this); textCues.setText(R.string.text_cues); textCues.setTextSize(uiSize(18)); textCues.setChecked(p.getBoolean("text_cues", true)); textCues.setPadding(0, dp(6), 0, dp(10)); box.addView(textCues, new LinearLayout.LayoutParams(-1, -2));
        final int[] previewScale = {originalInterfaceScale}; final boolean[] keepPreview = {false};
        interfaceFont.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onStartTrackingTouch(SeekBar seekBar) {} public void onStopTrackingTouch(SeekBar seekBar) {}
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) { int value = 50 + progress * 5; updateSliderPercentValue(seekBar); if (value != previewScale[0]) { previewInterfaceScale(previewScale[0], value); previewScale[0] = value; } }
        });
        Runnable applyOptions = () -> {
            int fontValue = seekValue(font), interfaceValue = 50 + interfaceFont.getProgress() * 5; String selectedTheme = themeValues[themeSpinner.getSelectedItemPosition()], selectedLanguage = languageValues[languageSpinner.getSelectedItemPosition()]; boolean themeChanged = !selectedTheme.equals(p.getString("theme", "system")), languageChanged = !selectedLanguage.equals(p.getString("language", "system"));
            p.edit().putInt("font_size", fontValue).putInt("interface_scale", interfaceValue).putInt("fast_seek_interval", seekValue(fastSeekInterval)).putString("theme", selectedTheme).putString("language", selectedLanguage).putBoolean("pause_for_settings", pauseForSettings.isChecked()).putBoolean("seek_vibration", seekVibration.isChecked()).putBoolean("prevent_device_autoplay", preventDeviceAutoplay.isChecked()).putBoolean("text_cues", textCues.isChecked()).apply(); keepPreview[0] = true; body.setTextSize(fontValue); if (languageChanged) applyLanguage(selectedLanguage); if (themeChanged || languageChanged) { resumeAfterRecreate = pausedAutomaticallyOutsideReader; pausedAutomaticallyOutsideReader = false; subpageCloseAction = null; getWindow().getDecorView().post(this::recreate); } else closeRecent();
        };
        Runnable closeOptions = () -> { if (!keepPreview[0]) previewInterfaceScale(previewScale[0], originalInterfaceScale); };
        showSettingsPage(R.string.settings, box, applyOptions, closeOptions);
    }

    private void showVoiceSettings() {
        if (reader == null) return; android.content.SharedPreferences p = getSettings(); pausePlaybackOutsideReader(); sliderValues.clear();
        ArrayList<ReaderService.EngineOption> engines = new ArrayList<>(); engines.add(new ReaderService.EngineOption("", getString(R.string.default_voice))); engines.addAll(reader.getEngineOptions());
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(dp(20), 0, dp(20), 0);
        TextView engineLabel = label(getString(R.string.speech_engine), 18, true); engineLabel.setPadding(0, dp(12), 0, 0); box.addView(engineLabel);
        String[] engineLabels = new String[engines.size()]; for (int i = 0; i < engines.size(); i++) engineLabels[i] = engines.get(i).label; AccessibleSpinner engineSpinner = new AccessibleSpinner(this); engineSpinner.setAdapter(themedSpinnerAdapter(engineLabels)); String savedEngine = p.getString("engine", ""); int selectedEngine = 0; for (int i = 1; i < engines.size(); i++) if (engines.get(i).name.equals(savedEngine)) { selectedEngine = i; break; } engineSpinner.setSelection(selectedEngine, false); box.addView(engineSpinner);
        TextView languageLabel = label(getString(R.string.language), 18, true); languageLabel.setPadding(0, dp(12), 0, 0); languageLabel.setVisibility(View.GONE); box.addView(languageLabel);
        AccessibleSpinner languageSpinner = new AccessibleSpinner(this); languageSpinner.setVisibility(View.GONE); box.addView(languageSpinner); ArrayList<LanguageOption> voiceLanguages = new ArrayList<>();
        TextView voiceLabel = label(getString(R.string.voice), 18, true); voiceLabel.setPadding(0, dp(12), 0, 0); voiceLabel.setVisibility(View.GONE); box.addView(voiceLabel);
        AccessibleSpinner voiceSpinner = new AccessibleSpinner(this); voiceSpinner.setVisibility(View.GONE); box.addView(voiceSpinner);
        previewButton = button(getString(R.string.preview_voice)); previewButton.setVisibility(View.INVISIBLE); previewSpeaking = false;
        VoiceSelection voiceSelection = new VoiceSelection();
        configureSpinnerAccessibility(engineSpinner, engineLabels, position -> {
            String requestedEngine = engines.get(position).name; languageLabel.setVisibility(View.GONE); languageSpinner.setVisibility(View.GONE); voiceLabel.setVisibility(View.GONE); voiceSpinner.setVisibility(View.GONE); previewButton.setVisibility(View.INVISIBLE);
            reader.loadVoiceOptions(requestedEngine, available -> runOnUiThread(() -> {
                if (destroyed || engineSpinner.getSelectedItemPosition() < 0 || !requestedEngine.equals(engines.get(engineSpinner.getSelectedItemPosition()).name)) return;
                voiceSelection.all = new ArrayList<>(available); voiceLanguages.clear(); voiceLanguages.addAll(buildVoiceLanguages(available));
                String[] languageNames = new String[voiceLanguages.size()]; for (int i = 0; i < voiceLanguages.size(); i++) languageNames[i] = voiceLanguages.get(i).label;
                languageSpinner.setAdapter(themedSpinnerAdapter(languageNames)); String savedVoice = p.getString(ReaderService.voicePreferenceKey(requestedEngine), ""), wantedTag = localeForVoice(available, savedVoice); int selectedLanguage = bestLanguagePosition(voiceLanguages, wantedTag); languageSpinner.setSelection(selectedLanguage, false);
                configureSpinnerAccessibility(languageSpinner, languageNames, selected -> populateVoiceChoices(voiceSpinner, voiceSelection, voiceLanguages.get(selected).tag, requestedEngine, p));
                populateVoiceChoices(voiceSpinner, voiceSelection, voiceLanguages.get(selectedLanguage).tag, requestedEngine, p);
                languageLabel.setVisibility(View.VISIBLE); languageSpinner.setVisibility(View.VISIBLE); voiceLabel.setVisibility(View.VISIBLE); voiceSpinner.setVisibility(View.VISIBLE); if (previewButton != null) previewButton.setVisibility(View.VISIBLE);
            }));
        });
        PercentSeekBar rate = percentSeek(box, R.string.speech_rate, p.getInt("rate_percent", 20));
        PercentSeekBar pitch = percentSeek(box, R.string.pitch, p.getInt("pitch_percent", 20));
        PercentSeekBar volume = percentSeek(box, R.string.volume, p.getInt("volume_percent", 50));
        SeekBar gap = millisecondSeek(box, p.getInt("sentence_pause", 0));
        previewAction = () -> {
            if (reader == null || destroyed) return;
            reader.previewVoice(engines.get(Math.max(0, engineSpinner.getSelectedItemPosition())).name, selectedVoiceName(voiceSelection, voiceSpinner),
                rate.percent(), pitch.percent(), volume.percent(), getString(R.string.voice_preview_sample));
        };
        previewButton.setOnClickListener(v -> {
            if (reader == null) return;
            previewHandler.removeCallbacksAndMessages(null);
            if (previewSpeaking) { reader.stopPreview(); return; }
            // The screen reader speaks the button first, and most engines can only say one thing at a time,
            // so the sample waits for that announcement instead of being cut off by it.
            startPreview(PREVIEW_DELAY_MS);
        });
        Runnable applyVoice = () -> { previewHandler.removeCallbacksAndMessages(null); reader.stopPreview(); ReaderService.EngineOption engine = engines.get(engineSpinner.getSelectedItemPosition()); String voiceName = selectedVoiceName(voiceSelection, voiceSpinner); p.edit().putString("engine", engine.name).putString(ReaderService.voicePreferenceKey(engine.name), voiceName).remove("voice").remove("rate").remove("pitch").putInt("rate_percent", rate.percent()).putInt("pitch_percent", pitch.percent()).putInt("volume_percent", volume.percent()).putInt("sentence_pause", seekValue(gap)).apply(); boolean restartAfterApply = reader.isPlaying(); reader.updateSettings(false); closeRecent(); if (restartAfterApply) scheduleAutomaticPlayback(); };
        Runnable closeVoice = () -> { previewHandler.removeCallbacksAndMessages(null); previewAction = null; if (reader != null) reader.stopPreview(); };
        showSettingsPage(R.string.voice_settings, box, previewButton, applyVoice, closeVoice);
    }
    private void startPreview(long delay) {
        previewHandler.removeCallbacksAndMessages(null);
        previewHandler.postDelayed(() -> { if (previewAction != null) previewAction.run(); }, delay);
    }
    @Override public void onPreviewState(boolean speaking) {
        runOnUiThread(() -> {
            previewSpeaking = speaking;
            if (previewButton != null) previewButton.setText(getString(speaking ? R.string.stop_preview : R.string.preview_voice));
        });
    }
    private String selectedVoiceName(VoiceSelection selection, Spinner voiceSpinner) {
        if (selection.visible.isEmpty() || voiceSpinner.getSelectedItemPosition() < 0) return "";
        return selection.visible.get(Math.min(voiceSpinner.getSelectedItemPosition(), selection.visible.size() - 1)).name;
    }

    private void showSleepDialog() {
        if (reader == null) return; pausePlaybackOutsideReader(); android.content.SharedPreferences p = getSettings();
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(dp(20), dp(8), dp(20), dp(8)); int[] minutes = {0, 15, 30, 45, 60, 90};
        RadioGroup choices = new RadioGroup(this); choices.setOrientation(RadioGroup.VERTICAL); int savedChoice = p.getInt("sleep_choice", 0); int checkedId = -1;
        for (int m : minutes) { RadioButton option = new RadioButton(this); option.setId(View.generateViewId()); option.setTag(m); option.setText(m == 0 ? getString(R.string.off) : getResources().getQuantityString(R.plurals.minutes, m, m)); option.setTextSize(uiSize(18)); choices.addView(option, new RadioGroup.LayoutParams(-1, dp(48))); if (savedChoice == m) checkedId = option.getId(); }
        RadioButton customChoice = new RadioButton(this); customChoice.setId(View.generateViewId()); customChoice.setTag(-1); customChoice.setText(R.string.custom_timer); customChoice.setTextSize(uiSize(18)); choices.addView(customChoice, new RadioGroup.LayoutParams(-1, dp(48))); if (savedChoice == -1) checkedId = customChoice.getId(); choices.check(checkedId == -1 ? choices.getChildAt(0).getId() : checkedId); box.addView(choices);
        LinearLayout customHeader = new LinearLayout(this); customHeader.setGravity(Gravity.CENTER_VERTICAL); TextView customLabel = label(getString(R.string.custom_timer), 18, true); TextView customValue = valueLabel(); customHeader.addView(customLabel, new LinearLayout.LayoutParams(0, -2, 1)); customHeader.addView(customValue, new LinearLayout.LayoutParams(-2, -2)); MinuteSeekBar custom = new MinuteSeekBar(this); custom.setProgress(Math.max(0, Math.min(89, p.getInt("custom_sleep_minutes", 30) - 1)));
        SeekBar.OnSeekBarChangeListener customListener = new SeekBar.OnSeekBarChangeListener() { public void onStartTrackingTouch(SeekBar s) {} public void onStopTrackingTouch(SeekBar s) {} public void onProgressChanged(SeekBar s, int progress, boolean fromUser) { int value = progress + 1; String spoken = getResources().getQuantityString(R.plurals.minutes, value, value); customValue.setText(getString(R.string.minutes_short_value, value)); setSliderValueDescription(custom, spoken); } }; custom.setOnSeekBarChangeListener(customListener); customListener.onProgressChanged(custom, custom.getProgress(), false);
        box.addView(customHeader); box.addView(custom, new LinearLayout.LayoutParams(-1, dp(56))); boolean showCustom = savedChoice == -1; customHeader.setVisibility(showCustom ? View.VISIBLE : View.GONE); custom.setVisibility(showCustom ? View.VISIBLE : View.GONE);
        choices.setOnCheckedChangeListener((group, id) -> { RadioButton selected = group.findViewById(id); boolean show = selected != null && (Integer)selected.getTag() == -1; customHeader.setVisibility(show ? View.VISIBLE : View.GONE); custom.setVisibility(show ? View.VISIBLE : View.GONE); if (show) custom.post(() -> { custom.requestFocus(); custom.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null); }); });
        ScrollView timerScroll = new ScrollView(this); timerScroll.addView(box);
        final boolean[] applied = {false};
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(R.string.sleep_timer).setView(timerScroll).setNegativeButton(R.string.cancel, null).setPositiveButton(R.string.apply, (d, w) -> { RadioButton selected = choices.findViewById(choices.getCheckedRadioButtonId()); int choice = selected == null ? 0 : (Integer)selected.getTag(); int customMinutes = custom.getProgress() + 1; int chosen = choice == -1 ? customMinutes : choice; p.edit().putInt("custom_sleep_minutes", customMinutes).putInt("sleep_choice", choice).apply(); applied[0] = true; reader.setSleepMinutes(chosen); if (chosen > 0 && !reader.isPlaying()) { pausedAutomaticallyOutsideReader = false; scheduleAutomaticPlayback(); } }).create();
        dialog.setOnDismissListener(d -> { if (!applied[0] || p.getInt("sleep_choice", 0) == 0) returnToReader(); }); dialog.show(); focusDialogTitle(dialog);
    }
    private SeekBar seek(LinearLayout box, int label, int min, int max, int value) {
        TextView valueLabel = addSliderHeader(box, label, 18, dp(12)); SeekBar s = new SeekBar(this); s.setTag(new SeekRange(min, max)); s.setMax(20); s.setProgress(Math.round((value - min) * 20f / (max - min))); sliderValues.put(s, valueLabel); updateSliderPercentValue(s); s.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() { public void onStartTrackingTouch(SeekBar seekBar) {} public void onStopTrackingTouch(SeekBar seekBar) {} public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) { updateSliderPercentValue(seekBar); } }); box.addView(s); return s;
    }
    private PercentSeekBar percentSeek(LinearLayout box, int label, int value) {
        TextView valueLabel = addSliderHeader(box, label, 18, dp(12)); PercentSeekBar seek = new PercentSeekBar(this); sliderValues.put(seek, valueLabel); seek.setPercent(Math.round(Math.max(0, Math.min(100, value)) / 5f) * 5); updatePercentValue(seek, seek.percent());
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() { public void onStartTrackingTouch(SeekBar s) {} public void onStopTrackingTouch(SeekBar s) {} public void onProgressChanged(SeekBar s, int progress, boolean fromUser) { int percent = seek.percent(); int rounded = Math.max(0, Math.min(100, Math.round(percent / 5f) * 5)); if (rounded != percent) seek.setPercent(rounded); else updatePercentValue(seek, rounded); } });
        box.addView(seek); return seek;
    }
    private void setMillisecondsDescription(SeekBar gap) { updateMillisecondsValue(gap); gap.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() { public void onStartTrackingTouch(SeekBar s) {} public void onStopTrackingTouch(SeekBar s) {} public void onProgressChanged(SeekBar s, int progress, boolean fromUser) { updateMillisecondsValue(s); } }); }
    private void setSliderValueDescription(SeekBar seek, String value) { if (Build.VERSION.SDK_INT >= 30) { seek.setContentDescription(null); seek.setStateDescription(value); } else seek.setContentDescription(value); }
    private SeekBar millisecondSeek(LinearLayout box, int value) { return millisecondSeek(box, R.string.sentence_pause, 0, 2000, 100, value); }
    private SeekBar millisecondSeek(LinearLayout box, int labelResource, int min, int max, int step, int value) { TextView valueLabel = addSliderHeader(box, labelResource, 18, dp(12)); SeekBar seek = new SeekBar(this); seek.setTag(new SeekRange(min, max)); seek.setMax((max - min) / step); seek.setProgress(Math.max(0, Math.min(seek.getMax(), Math.round((value - min) / (float)step)))); sliderValues.put(seek, valueLabel); box.addView(seek); setMillisecondsDescription(seek); return seek; }
    private TextView addSliderHeader(LinearLayout box, int labelResource, int textSize, int topPadding) { LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(0, topPadding, 0, 0); TextView name = label(getString(labelResource), textSize, true); TextView value = valueLabel(); row.addView(name, new LinearLayout.LayoutParams(0, -2, 1)); row.addView(value, new LinearLayout.LayoutParams(-2, -2)); box.addView(row, new LinearLayout.LayoutParams(-1, -2)); return value; }
    private TextView valueLabel() { TextView value = label("", 18, true); value.setGravity(Gravity.END); value.setFocusable(false); value.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO); return value; }
    private void updatePercentValue(SeekBar seek, int percent) { TextView value = sliderValues.get(seek); if (value != null) value.setText(getString(R.string.percentage_value, percent)); setSliderValueDescription(seek, getString(R.string.percentage_spoken, percent)); }
    private void updateMillisecondsValue(SeekBar seek) { int milliseconds = seekValue(seek); TextView value = sliderValues.get(seek); if (value != null) value.setText(getString(R.string.milliseconds_short_value, milliseconds)); setSliderValueDescription(seek, getString(R.string.milliseconds_value, milliseconds)); }
    private void updateSliderPercentValue(SeekBar seek) { int percent = seek.getMax() == 0 ? 0 : Math.round(seek.getProgress() * 100f / seek.getMax()); updatePercentValue(seek, percent); }
    private void configureSpinnerAccessibility(AccessibleSpinner spinner, String[] values) {
        configureSpinnerAccessibility(spinner, values, null);
    }
    private void configureSpinnerAccessibility(AccessibleSpinner spinner, String[] values, SpinnerSelectionObserver selectionObserver) {
        updateSpinnerDescription(spinner, values[Math.max(0, spinner.getSelectedItemPosition())]);
        spinner.setAccessibilityDelegate(new View.AccessibilityDelegate() { @Override public void onInitializeAccessibilityNodeInfo(View host, android.view.accessibility.AccessibilityNodeInfo info) { super.onInitializeAccessibilityNodeInfo(host, info); info.setCollectionItemInfo(null); if (Build.VERSION.SDK_INT >= 30) info.setStateDescription(null); } });
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() { public void onNothingSelected(AdapterView<?> parent) {} public void onItemSelected(AdapterView<?> parent, View view, int position, long id) { updateSpinnerDescription(spinner, values[position]); if (selectionObserver != null) selectionObserver.onSelected(position); if (spinner.consumePopupSelection()) spinner.postDelayed(() -> { View title = spinner.getRootView().findViewById(getResources().getIdentifier("alertTitle", "id", "android")); if (title != null) title.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS, null); spinner.requestFocus(); spinner.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null); }, 600L); } });
    }
    private void updateSpinnerDescription(Spinner spinner, String value) { spinner.setContentDescription(value); }
    private ArrayList<LanguageOption> buildVoiceLanguages(List<ReaderService.VoiceOption> voices) {
        TreeMap<String, LanguageOption> sorted = new TreeMap<>(String.CASE_INSENSITIVE_ORDER); Locale displayLocale = getResources().getConfiguration().getLocales().get(0);
        for (ReaderService.VoiceOption voice : voices) { String fullTag = voice.localeTag.isEmpty() ? Locale.getDefault().toLanguageTag() : voice.localeTag; Locale locale = Locale.forLanguageTag(fullTag); String tag = locale.getLanguage(), display = locale.getDisplayLanguage(displayLocale); if (tag.isEmpty()) tag = fullTag; if (display == null || display.trim().isEmpty()) display = tag; String key = display + "\u0000" + tag; if (!sorted.containsKey(key)) sorted.put(key, new LanguageOption(tag, display)); }
        if (sorted.isEmpty()) { Locale locale = Locale.getDefault(); sorted.put(locale.getLanguage(), new LanguageOption(locale.getLanguage(), locale.getDisplayLanguage(displayLocale))); }
        return new ArrayList<>(sorted.values());
    }
    private String localeForVoice(List<ReaderService.VoiceOption> voices, String name) { for (ReaderService.VoiceOption voice : voices) if (voice.name.equals(name)) return Locale.forLanguageTag(voice.localeTag).getLanguage(); return ""; }
    private int bestLanguagePosition(List<LanguageOption> languages, String wantedTag) {
        if (!wantedTag.isEmpty()) for (int i = 0; i < languages.size(); i++) if (languages.get(i).tag.equalsIgnoreCase(wantedTag)) return i;
        String deviceLanguage = Locale.getDefault().getLanguage();
        for (int i = 0; i < languages.size(); i++) if (languages.get(i).tag.equalsIgnoreCase(deviceLanguage)) return i;
        return 0;
    }
    private void populateVoiceChoices(AccessibleSpinner spinner, VoiceSelection selection, String localeTag, String engine, android.content.SharedPreferences preferences) {
        // Which voices exist at all is decided in the service; here they are only narrowed to one language.
        ArrayList<ReaderService.VoiceOption> matching = new ArrayList<>(); for (ReaderService.VoiceOption voice : selection.all) { String language = voice.localeTag.isEmpty() ? Locale.getDefault().getLanguage() : Locale.forLanguageTag(voice.localeTag).getLanguage(); if (localeTag.equalsIgnoreCase(language)) matching.add(voice); }
        selection.visible = new ArrayList<>(); selection.visible.add(new ReaderService.VoiceOption("", getString(R.string.default_voice), localeTag)); selection.visible.addAll(matching);
        String[] labels = new String[selection.visible.size()]; labels[0] = getString(R.string.default_voice); for (int i = 0; i < matching.size(); i++) labels[i + 1] = voiceLabel(matching.get(i).name, i + 1);
        spinner.setAdapter(themedSpinnerAdapter(labels)); String saved = preferences.getString(ReaderService.voicePreferenceKey(engine), ""); int selected = 0; for (int i = 1; i < selection.visible.size(); i++) if (selection.visible.get(i).name.equals(saved)) { selected = i; break; } spinner.setSelection(selected, false); spinner.setEnabled(selection.visible.size() > 1); configureSpinnerAccessibility(spinner, labels);
    }
    // Voice names arrive in every shape: "Milena", "bg-BG-Ivan", "bg-bg-x-ifb-local",
    // "en-us-x-sfg#female_1-local". The locale prefix and the technical tails are stripped; whatever real
    // name is left is kept, and only a bare engine code like "ifb", which a screen reader would spell out
    // letter by letter, is replaced by a number.
    private String voiceLabel(String name, int number) {
        String cleaned = name.replace('_', '-');
        int variant = cleaned.indexOf('#'); if (variant > 0) cleaned = cleaned.substring(0, variant);
        cleaned = cleaned.replaceAll("(?i)-(local|network)$", "").replaceAll("(?i)^[a-z]{2,3}(-[a-z]{2,4})?-", "").replaceAll("(?i)^x-", "").trim();
        if (cleaned.length() <= 3 || !cleaned.matches(".*\\p{L}.*")) return getString(R.string.numbered_voice, number);
        return Character.toUpperCase(cleaned.charAt(0)) + cleaned.substring(1);
    }
    private void focusDialogTitle(AlertDialog dialog) { View titleView = dialog.findViewById(getResources().getIdentifier("alertTitle", "id", "android")); if (titleView != null) { if (Build.VERSION.SDK_INT >= 28) titleView.setAccessibilityHeading(true); titleView.postDelayed(() -> titleView.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null), 220L); } }
    private void focusHeading(View heading) { heading.postDelayed(() -> heading.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null), 220L); }
    private ArrayAdapter<String> themedSpinnerAdapter(String[] values) { return new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, values) { private View style(View view, boolean dropdown) { if (view instanceof TextView) { ((TextView)view).setTextColor(appColor(R.color.text_primary)); ((TextView)view).setTextSize(uiSize(18)); view.setBackgroundColor(appColor(R.color.window_bg)); view.setPadding(dp(12), dp(12), dp(12), dp(12)); view.setImportantForAccessibility(dropdown ? View.IMPORTANT_FOR_ACCESSIBILITY_YES : View.IMPORTANT_FOR_ACCESSIBILITY_NO); view.setAccessibilityDelegate(new View.AccessibilityDelegate() { @Override public void onInitializeAccessibilityNodeInfo(View host, android.view.accessibility.AccessibilityNodeInfo info) { super.onInitializeAccessibilityNodeInfo(host, info); info.setCollectionItemInfo(null); } }); } return view; } @Override public View getView(int position, View convertView, ViewGroup parent) { return style(super.getView(position, convertView, parent), false); } @Override public View getDropDownView(int position, View convertView, ViewGroup parent) { return style(super.getDropDownView(position, convertView, parent), true); } }; }
    private int seekValue(SeekBar seek) { SeekRange range = (SeekRange)seek.getTag(); return Math.round(range.min + seek.getProgress() * (range.max - range.min) / (float)seek.getMax()); }
    private void applySavedLanguage() { applyLanguage(getSharedPreferences("reader_settings", MODE_PRIVATE).getString("language", "system")); }
    private void applyLanguage(String language) {
        if (Build.VERSION.SDK_INT >= 33) { android.os.LocaleList locales = "system".equals(language) ? android.os.LocaleList.getEmptyLocaleList() : android.os.LocaleList.forLanguageTags(language); android.app.LocaleManager manager = getSystemService(android.app.LocaleManager.class); if (manager != null && !manager.getApplicationLocales().equals(locales)) manager.setApplicationLocales(locales); }
        else { Locale locale = "system".equals(language) ? android.content.res.Resources.getSystem().getConfiguration().getLocales().get(0) : Locale.forLanguageTag(language); android.content.res.Configuration configuration = new android.content.res.Configuration(getResources().getConfiguration()); configuration.setLocale(locale); getResources().updateConfiguration(configuration, getResources().getDisplayMetrics()); }
    }
    private void previewInterfaceScale(int oldValue, int newValue) { if (oldValue <= 0 || oldValue == newValue) return; scaleTextViews(appRoot, newValue / (float)oldValue); }
    private void scaleTextViews(View view, float factor) { if (view instanceof TextView && view != body) ((TextView)view).setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, ((TextView)view).getTextSize() * factor); if (view instanceof ViewGroup) { ViewGroup group = (ViewGroup)view; for (int i = 0; i < group.getChildCount(); i++) scaleTextViews(group.getChildAt(i), factor); } }
    private void addRecent(String uri, String name) {
        try { JSONArray old = new JSONArray(documents().getString("recent", "[]")); JSONArray fresh = new JSONArray(); fresh.put(new JSONObject().put("uri", uri).put("name", name));
            for (int i = 0; i < old.length() && fresh.length() < 10; i++) if (!uri.equals(old.getJSONObject(i).optString("uri"))) fresh.put(old.getJSONObject(i));
            documents().edit().putString("recent", fresh.toString()).apply();
        } catch (JSONException ignored) {}
    }
    private void showRecent() {
        pausePlaybackOutsideReader();
        showingRecent = true; int pad = dp(16);
        LinearLayout page = new LinearLayout(this); page.setOrientation(LinearLayout.VERTICAL); page.setPadding(pad, dp(10), pad, dp(16)); page.setBackgroundColor(appColor(R.color.window_bg));
        page.setOnApplyWindowInsetsListener((v, insets) -> { v.setPadding(pad, insets.getSystemWindowInsetTop() + dp(10), pad, insets.getSystemWindowInsetBottom() + dp(16)); return insets; });
        LinearLayout bar = new LinearLayout(this); bar.setGravity(Gravity.CENTER_VERTICAL);
        ImageButton back = imageButton(android.R.drawable.ic_media_previous, R.string.back); back.setOnClickListener(v -> closeRecent()); bar.addView(back, new LinearLayout.LayoutParams(dp(56), dp(56)));
        TextView heading = label(getString(R.string.recent_books), 25, true); heading.setPadding(dp(8), 0, 0, 0); if (Build.VERSION.SDK_INT >= 28) heading.setAccessibilityHeading(true); bar.addView(heading, new LinearLayout.LayoutParams(0, -2, 1)); page.addView(bar);
        LinearLayout list = new LinearLayout(this); list.setOrientation(LinearLayout.VERTICAL); ScrollView scrolling = new ScrollView(this); scrolling.addView(list); page.addView(scrolling, new LinearLayout.LayoutParams(-1, 0, 1));
        try {
            JSONArray recent = new JSONArray(documents().getString("recent", "[]")); int count = Math.min(10, recent.length());
            if (count == 0) { TextView empty = label(getString(R.string.no_recent), 19, false); empty.setPadding(0, dp(24), 0, dp(24)); list.addView(empty); }
            for (int i = 0; i < count; i++) {
                JSONObject item = recent.getJSONObject(i); String itemUri = item.optString("uri"), name = withoutTxtExtension(item.optString("name"));
                LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(0, dp(6), 0, dp(6));
                Button open = listRowButton(name, 19); open.setOnClickListener(v -> openRecent(itemUri));
                ImageButton remove = imageButton(android.R.drawable.ic_menu_delete, R.string.delete); remove.setContentDescription(getString(R.string.remove_book, name)); remove.setOnClickListener(v -> removeRecent(itemUri));
                row.addView(open, new LinearLayout.LayoutParams(0, -2, 1)); LinearLayout.LayoutParams removeParams = new LinearLayout.LayoutParams(dp(56), dp(56)); removeParams.setMargins(dp(10), 0, 0, 0); row.addView(remove, removeParams); list.addView(row);
            }
        } catch (JSONException e) { toast(getString(R.string.no_recent)); }
        setContentView(page); page.requestApplyInsets(); focusHeading(heading);
    }
    private void showSettingsPage(int headingResource, View content, Runnable applyAction, Runnable closeAction) {
        showSettingsPage(headingResource, content, null, applyAction, closeAction);
    }
    // A view passed as bottomExtra sits outside the scrolling area, directly on top of Apply. Inside the
    // scroll view it would float wherever the content happens to end, leaving a gap above the button.
    private void showSettingsPage(int headingResource, View content, View bottomExtra, Runnable applyAction, Runnable closeAction) {
        showingRecent = true; subpageCloseAction = closeAction; int pad = dp(16);
        LinearLayout page = new LinearLayout(this); page.setOrientation(LinearLayout.VERTICAL); page.setPadding(pad, dp(10), pad, dp(16)); page.setBackgroundColor(appColor(R.color.window_bg));
        page.setOnApplyWindowInsetsListener((v, insets) -> { v.setPadding(pad, insets.getSystemWindowInsetTop() + dp(10), pad, insets.getSystemWindowInsetBottom() + dp(16)); return insets; });
        LinearLayout bar = new LinearLayout(this); bar.setGravity(Gravity.CENTER_VERTICAL); ImageButton back = imageButton(android.R.drawable.ic_media_previous, R.string.back); back.setOnClickListener(v -> closeRecent()); bar.addView(back, new LinearLayout.LayoutParams(dp(56), dp(56)));
        TextView heading = label(getString(headingResource), 25, true); heading.setPadding(dp(8), 0, 0, 0); if (Build.VERSION.SDK_INT >= 28) heading.setAccessibilityHeading(true); bar.addView(heading, new LinearLayout.LayoutParams(0, -2, 1)); page.addView(bar);
        ScrollView scrolling = new ScrollView(this); scrolling.addView(content); page.addView(scrolling, new LinearLayout.LayoutParams(-1, 0, 1));
        if (bottomExtra != null) page.addView(bottomExtra, new LinearLayout.LayoutParams(-1, dp(58)));
        // A page that only lists things has nothing to apply; Back is the only way out of it.
        if (applyAction != null) { Button apply = button(getString(R.string.apply)); apply.setOnClickListener(v -> applyAction.run()); page.addView(apply, new LinearLayout.LayoutParams(-1, dp(58))); }
        appRoot = page; setContentView(page); page.requestApplyInsets(); focusHeading(heading);
    }
    // "Remove the book" is taken at its word: the row goes, and with it everything the app knew about that
    // book - the place it was left at, its bookmarks and the permission to open the file at all. Otherwise a
    // book removed and opened again would come back at the percentage it was left at, which is the opposite
    // of what removing it looked like. If it is the book currently open, it is closed as well; leaving it
    // loaded would only write its position back on the next sentence.
    private void removeRecent(String itemUri) {
        try { JSONArray old = new JSONArray(documents().getString("recent", "[]")), fresh = new JSONArray(); for (int i = 0; i < old.length(); i++) if (!itemUri.equals(old.getJSONObject(i).optString("uri"))) fresh.put(old.getJSONObject(i)); documents().edit().putString("recent", fresh.toString()).apply(); }
        catch (JSONException e) { toast(getString(R.string.no_recent)); return; }
        forgetBook(itemUri);
        showRecent();
    }
    private void forgetBook(String itemUri) {
        if (itemUri.equals(currentUri)) clearCurrentDocument();
        if (itemUri.equals(documents().getString("last_uri", ""))) documents().edit().remove("last_uri").apply();
        if (reader != null) reader.forgetBook(itemUri);
        try { JSONObject all = new JSONObject(documents().getString("bookmarks", "{}")); all.remove(itemUri); documents().edit().putString("bookmarks", all.toString()).apply(); }
        catch (JSONException ignored) {}
        // Held since the file was opened. Android keeps a limited number of them per app, so one that is no
        // longer of use is given back rather than left to pile up.
        try { getContentResolver().releasePersistableUriPermission(Uri.parse(itemUri), Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Exception ignored) {}
    }
    // Opening a book from the recent list must not go through closeRecent(): that one marks the activity as
    // loading before the tap is handled, which used to swallow the requested file and keep the current one.
    private void openRecent(String itemUri) {
        if (loading) return;
        Runnable action = subpageCloseAction; subpageCloseAction = null; if (action != null) action.run();
        buildUi(); if (reader != null) reader.setListener(this);
        resumeAfterFilePickerLoad = pausedAutomaticallyOutsideReader; pausedAutomaticallyOutsideReader = false;
        loadUri(Uri.parse(itemUri), true);
    }
    private void closeRecent() {
        if (!showingRecent || loading) return;
        Runnable action = subpageCloseAction; subpageCloseAction = null; if (action != null) action.run();
        // Returning from a settings page leaves the open document untouched, so rebuild the reader without
        // reading and decoding the whole file from storage again.
        if (!currentUri.isEmpty() && reader != null && reader.getCount() > 0 && isInRecent(currentUri)) {
            buildUi(); reader.setListener(this); returnToReader(); return;
        }
        loading = true;
        io.execute(() -> {
            RecentDocument selected = selectDocumentForReader();
            runOnUiThread(() -> {
                if (destroyed) return;
                loading = false;
                if (selected == null) clearCurrentDocument();
                else if (!selected.uri.equals(currentUri)) {
                    currentUri = selected.uri; currentName = selected.name;
                    documents().edit().putString("last_uri", currentUri).apply();
                    if (reader == null) pendingText = selected.text; else finishLoad(selected.text);
                }
                buildUi(); if (reader != null) reader.setListener(this); returnToReader();
            });
        });
    }
    private boolean isInRecent(String uri) {
        try {
            JSONArray recent = new JSONArray(documents().getString("recent", "[]"));
            for (int i = 0; i < recent.length(); i++) if (uri.equals(recent.getJSONObject(i).optString("uri"))) return true;
        } catch (JSONException ignored) {}
        return false;
    }
    private RecentDocument selectDocumentForReader() {
        try {
            JSONArray recent = new JSONArray(documents().getString("recent", "[]"));
            for (int i = 0; i < Math.min(10, recent.length()); i++) {
                JSONObject item = recent.getJSONObject(i); String uri = item.optString("uri");
                if (uri.isEmpty() || (!currentUri.isEmpty() && !currentUri.equals(uri))) continue;
                RecentDocument document = readRecentDocument(item); if (document != null) return document;
            }
            for (int i = 0; i < Math.min(10, recent.length()); i++) {
                JSONObject item = recent.getJSONObject(i); if (currentUri.equals(item.optString("uri"))) continue;
                RecentDocument document = readRecentDocument(item); if (document != null) return document;
            }
        } catch (JSONException ignored) {}
        return null;
    }
    private RecentDocument readRecentDocument(JSONObject item) {
        try {
            String uriValue = item.optString("uri"); Uri uri = Uri.parse(uriValue); String fileName = displayFileName(uri);
            if (fileName == null || !fileName.toLowerCase(Locale.ROOT).endsWith(".txt")) return null;
            String loaded = decode(readLimited(uri)).replace("\r\n", "\n").replace('\r', '\n');
            if (loaded.trim().isEmpty()) return null;
            String savedName = item.optString("name");
            return new RecentDocument(uriValue, withoutTxtExtension(savedName.isEmpty() ? fileName : savedName), loaded);
        } catch (Exception ignored) { return null; }
    }
    private void clearCurrentDocument() {
        cancelAutomaticResume(true); resumeAfterFilePickerLoad = false; pendingText = null; currentUri = ""; currentName = "";
        documents().edit().remove("last_uri").apply();
        if (reader != null) reader.clearDocument();
    }
    @android.annotation.SuppressLint("GestureBackNavigation")
    @Override public void onBackPressed() { if (showingRecent) closeRecent(); else super.onBackPressed(); }
    @Override protected void onSaveInstanceState(Bundle outState) { outState.putBoolean(STATE_RESUME_AFTER_RECREATE, resumeAfterRecreate); super.onSaveInstanceState(outState); }
    private void toast(String value) { Toast.makeText(this, value == null ? getString(R.string.open_failed) : value, Toast.LENGTH_LONG).show(); }
    @Override protected void onDestroy() {
        destroyed = true; automaticResumeHandler.removeCallbacksAndMessages(null); seekHandler.removeCallbacksAndMessages(null); previewHandler.removeCallbacksAndMessages(null);
        if (reader != null) reader.setListener(null);
        if (bindRequested) { bindRequested = false; try { unbindService(connection); } catch (IllegalArgumentException ignored) {} }
        io.shutdownNow(); super.onDestroy();
    }
}
