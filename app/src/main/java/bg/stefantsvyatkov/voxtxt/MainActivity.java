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
    private static final int OPEN_TEXT = 10, SAVE_TXT_PERMISSION = 12, MAX_BYTES = 5 * 1024 * 1024;
    // What the file picker offers. Some file managers report an FB2 or an EPUB as a plain stream of bytes, so
    // that type is offered as well and the extension of the file decides what it actually is.
    private static final String[] OPENABLE_TYPES = {"text/plain", "application/epub+zip",
        "application/x-fictionbook+xml", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/zip", "application/octet-stream"};
    private static final long AUTOMATIC_RESUME_DELAY_MS = 1500L, PREVIEW_DELAY_MS = 800L;
    // Each fast seek step schedules the next one only after it has finished its work, so the interval used
    // to be padded by however long redrawing the document took - roughly 150 ms on a full book. Redrawing is
    // cheap now and the interval is honoured exactly, which made the old default of 200 ms feel twice as
    // fast. This is the value that gives the pace the setting always seemed to have.
    private static final int FAST_SEEK_DEFAULT_MS = 400, FAST_SEEK_MIN_MS = 200, FAST_SEEK_MAX_MS = 600;
    private static final long MAX_FAST_SEEK_MS = 60_000L;
    // Off, while a document is being read, while a web page is, or both.
    private static final String[] KEEP_SCREEN_VALUES = {"off", "documents", "web", "both"};
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
    private final Handler sleepRowHandler = new Handler(Looper.getMainLooper());
    private boolean fastSeeking, resumeAfterFastSeek;
    private boolean pausedAutomaticallyOutsideReader, automaticResumePending, resumeAfterRecreate, resumeAfterFilePickerLoad;
    private boolean showingRecent;
    private String renderedText;
    private BackgroundColorSpan highlightSpan;
    private android.text.style.ForegroundColorSpan highlightInk;
    private Runnable subpageCloseAction, previewAction;
    private String lastSearch = "";
    // Whatever is being read, kept so that Save as TXT has something to write, and two facts about where it
    // came from. They are settled once, at the moment the text is loaded, where the app already knows
    // exactly what it did - rather than worked out again from a file name that may not even have a proper
    // extension. Save as TXT is offered whenever the text is not already sitting on the phone as a plain
    // text file: a converted book, a web page, a shared passage, or a TXT that arrived inside an archive.
    private String loadedText;
    private boolean fromPlainTextFile, fromWeb;
    // A page fetched from an address, as opposed to a passage shared from somewhere. Both read with the web
    // voice, but only the fetched one is worth copying: a shared passage came from a place that already had
    // it.
    private boolean fromWebPage;
    // True while a screen of another app is open on our behalf - the file picker - so that leaving for it is
    // not mistaken for the user walking away.
    private boolean leavingForResult;
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
        // Whether it came from a plain text file already on the phone, which decides whether Save as TXT has
        // anything to offer for it.
        final boolean plainTextFile;
        RecentDocument(String uri, String name, String text, boolean plainTextFile) { this.uri = uri; this.name = name; this.text = text; this.plainTextFile = plainTextFile; }
    }
    private class AccessibleSpinner extends Spinner {
        private boolean selectionFromPopup;
        AccessibleSpinner(Context context) {
            super(context);
            // The platform draws a bare arrow at the right and nothing else, so the control reads as a
            // sentence rather than a field. An outline says where it begins and ends without touching the
            // contrast of what is written inside it.
            android.graphics.drawable.GradientDrawable box = new android.graphics.drawable.GradientDrawable();
            box.setColor(Color.TRANSPARENT);
            box.setStroke(Math.max(1, dp(1)), appColor(R.color.text_secondary));
            box.setCornerRadius(dp(10));
            setBackground(box);
            setPadding(dp(12), dp(10), dp(12), dp(10));
        }
        @Override public boolean performClick() { selectionFromPopup = true; spinnerPopupOpening = true; return super.performClick(); }
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
        // One-off tidying. "keep_screen_on" held a yes or no for a single build, before the setting grew to
        // four states and moved to a name of its own. Nothing reads the old one any more, and a settings file
        // never clears itself - left alone it would ride the backup onto every future phone. Delete these two
        // lines in Beta 5, when no phone still carries it.
        if (getSettings().contains("keep_screen_on")) getSettings().edit().remove("keep_screen_on").apply();
        applySavedLanguage();
        String selectedTheme = getSharedPreferences("reader_settings", MODE_PRIVATE).getString("theme", "system");
        if ("light".equals(selectedTheme)) setTheme(R.style.AppThemeLight); else if ("dark".equals(selectedTheme)) setTheme(R.style.AppThemeDark);
        super.onCreate(state); if (Build.VERSION.SDK_INT >= 33) getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, () -> { if (showingRecent) closeRecent(); else leaveReader(); }); buildUi();
        resumeAfterRecreate = state != null && state.getBoolean(STATE_RESUME_AFTER_RECREATE, false);
        bindRequested = bindService(new Intent(this, ReaderService.class), connection, BIND_AUTO_CREATE);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 11);
        if (handleIncoming(getIntent())) return;
        if (restoreLastPage()) return;
        String last = documents().getString("last_uri", "");
        if (!last.isEmpty()) loadUri(Uri.parse(last), false);
    }
    // A web page shared from a browser, or a text file opened from a file manager. Anything else falls through
    // to the book that was open last.
    @Override protected void onNewIntent(Intent intent) { super.onNewIntent(intent); setIntent(intent); handleIncoming(intent); }
    private boolean handleIncoming(Intent intent) {
        if (intent == null) return false;
        // Whatever page the app was left on, something arriving from outside belongs on the reader screen.
        // Reading an article while a list of recent files is on the screen is nobody's idea of opening it.
        boolean incoming = (Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null)
            || Intent.ACTION_SEND.equals(intent.getAction())
            || "android.intent.action.PROCESS_TEXT".equals(intent.getAction());
        if (incoming) returnToReaderScreen();
        // Anything handed to the app from outside starts reading by itself. Opening it was the whole request;
        // reaching for Play afterwards is a step that says nothing new.
        if (Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) { resumeAfterFilePickerLoad = true; loadUri(intent.getData(), true); return true; }
        if ("android.intent.action.PROCESS_TEXT".equals(intent.getAction())) {
            CharSequence selected = intent.getCharSequenceExtra("android.intent.extra.PROCESS_TEXT");
            if (selected != null && selected.toString().trim().length() > 0) { showSharedText(selected.toString().trim()); return true; }
            toast(getString(R.string.no_address_shared));
            return false;
        }
        if (Intent.ACTION_SEND.equals(intent.getAction())) {
            String shared = intent.getStringExtra(Intent.EXTRA_TEXT);
            String address = ArticleReader.firstUrl(shared);
            if (!address.isEmpty()) { loadArticle(address, true); return true; }
            if (shared != null && shared.trim().length() > 0) { showSharedText(shared.trim()); return true; }
            toast(getString(R.string.no_address_shared));
        }
        return false;
    }

    private void buildUi() {
        stopFastSeek(false);
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
        bookProgress = new BookProgressSeekBar(this); thicken(bookProgress);
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
        // The page opens on the settings that belong to what is open right now, which is nearly always the
        // set the reader came to change.
        voiceButton.setOnClickListener(v -> { pendingVoice.clear(); showVoiceSettings(fromWeb ? WEB_PROFILE : ""); }); sleepButton.setOnClickListener(v -> showSleepDialog());
        sleepRewindButton.setOnClickListener(v -> {
            if (reader == null) return;
            // Only the timer is called off. A book that is reading carries on reading; the timer was the thing
            // that was no longer wanted, not the book.
            if (reader.sleepRemainingMillis() > 0) { getSettings().edit().putInt("sleep_choice", 0).apply(); reader.setSleepMinutes(0); updateSleepRow(); }
            else reader.rewindCompletedSleepTimer();
        }); sleepRewindButton.setVisibility(View.INVISIBLE);
        playerPanel.addView(bookProgress, new LinearLayout.LayoutParams(-1, dp(52)));
        attachSeekButton(previous, -1);
        play.setOnClickListener(v -> { if (reader == null) return; cancelAutomaticResume(true); if (reader.isPlaying()) reader.pause(); else reader.play(); });
        attachSeekButton(next, 1);
        addPlayerButton(playerButtons, voiceButton); addPlayerButton(playerButtons, previous); addPlayerButton(playerButtons, play); addPlayerButton(playerButtons, next); addPlayerButton(playerButtons, sleepButton);
        playerPanel.addView(playerButtons, new LinearLayout.LayoutParams(-1, dp(64)));
        playerPanel.addView(sleepRewindButton, new LinearLayout.LayoutParams(-1, dp(58)));
        LinearLayout.LayoutParams panelParams = new LinearLayout.LayoutParams(-1, dp(200)); panelParams.setMargins(0, dp(4), 0, dp(4)); root.addView(playerPanel, panelParams); setContentView(root); setTitle(getString(R.string.app_name)); applyScreenSetting(); updateControls(); if (reader != null) showCurrent(reader.getCurrent(), reader.getCount()); root.requestApplyInsets();
    }
    private TextView label(String value, int sp, boolean bold) { TextView v = new TextView(this); v.setText(value); v.setTextSize(uiSize(sp)); v.setTextColor(appColor(R.color.text_primary)); if (bold) v.setTypeface(v.getTypeface(), android.graphics.Typeface.BOLD); return v; }
    // The plain grey the platform gives a button is what made the app look like a form. A blue one reads as
    // something to press without any of the contrast being given up: the lettering stays at nine to one
    // against its own background in both themes, and the button stands as far from the page behind it.
    private Button button(String value) {
        Button b = new Button(this); b.setText(value); b.setTextSize(uiSize(17)); b.setAllCaps(false);
        b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(appColor(R.color.button_bg)));
        b.setTextColor(appColor(R.color.button_text));
        return b;
    }
    private ImageButton imageButton(int icon, int description) { ImageButton b = new ImageButton(this); b.setImageResource(icon); b.setScaleType(ImageView.ScaleType.CENTER_INSIDE); b.setImageTintList(android.content.res.ColorStateList.valueOf(appColor(R.color.text_primary))); b.setContentDescription(getString(description)); b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.TRANSPARENT)); b.setPadding(dp(7), dp(7), dp(7), dp(7)); return b; }
    private void addPlayerButton(LinearLayout row, ImageButton button) { FrameLayout column = new FrameLayout(this); column.addView(button, new FrameLayout.LayoutParams(dp(56), dp(62), Gravity.CENTER)); row.addView(column, new LinearLayout.LayoutParams(0, dp(64), 1)); }
    private Button compactButton(String value) { Button b = button(value); b.setMinWidth(0); b.setMinimumWidth(0); b.setPadding(dp(12), 0, dp(12), 0); return b; }
    // The platform draws a slider as a hairline. At the sizes this app uses everywhere else it looks like a
    // scratch on the screen rather than a control, and for someone who makes out shapes but not detail it is
    // the hardest thing here to see. This is the same slider, thick enough to find with a finger and no
    // thicker.
    //
    // The two halves are coloured apart on purpose. Filled in the colour of the app, empty in a grey that
    // belongs to its theme: white on light grey looked like one bar and gave away nothing about how far it
    // had been dragged, which is the one thing a slider exists to say.
    private void thicken(SeekBar bar) {
        int track = appColor(R.color.slider_track), filled = appColor(R.color.slider_fill);
        android.graphics.drawable.GradientDrawable behind = new android.graphics.drawable.GradientDrawable();
        behind.setColor(track); behind.setCornerRadius(dp(3)); behind.setSize(-1, dp(5));
        android.graphics.drawable.GradientDrawable ahead = new android.graphics.drawable.GradientDrawable();
        ahead.setColor(filled); ahead.setCornerRadius(dp(3)); ahead.setSize(-1, dp(5));
        android.graphics.drawable.Drawable[] layers = {
            behind, new android.graphics.drawable.ClipDrawable(ahead, Gravity.START, android.graphics.drawable.ClipDrawable.HORIZONTAL)};
        android.graphics.drawable.LayerDrawable stack = new android.graphics.drawable.LayerDrawable(layers);
        stack.setId(0, android.R.id.background); stack.setId(1, android.R.id.progress);
        bar.setProgressDrawable(stack);
        bar.setThumbTintList(android.content.res.ColorStateList.valueOf(filled));
        bar.setSplitTrack(false);
    }
    private int indexOf(String[] values, String wanted) {
        for (int i = 0; i < values.length; i++) if (values[i].equals(wanted)) return i;
        return 0;
    }
    private int dp(int value) { return (int)(value * getResources().getDisplayMetrics().density + .5f); }
    private float uiSize(float base) { return base * getSettings().getInt("interface_scale", 100) / 100f; }
    private int appColor(int resource) { String theme = getSettings().getString("theme", "system"); if ("dark".equals(theme)) { if (resource == R.color.text_secondary) return Color.rgb(208,208,208); if (resource == R.color.highlight) return Color.rgb(255,213,79); if (resource == R.color.button_bg) return Color.rgb(18,90,173); if (resource == R.color.button_text) return Color.WHITE; if (resource == R.color.slider_track) return Color.rgb(60,60,60); if (resource == R.color.slider_fill) return Color.rgb(66,165,245); return resource == R.color.text_primary || resource == R.color.accent ? Color.WHITE : Color.BLACK; } if ("light".equals(theme)) { if (resource == R.color.text_secondary) return Color.rgb(51,51,51); if (resource == R.color.highlight) return Color.rgb(255,213,79); if (resource == R.color.button_bg) return Color.rgb(13,71,161); if (resource == R.color.button_text) return Color.WHITE; if (resource == R.color.slider_track) return Color.rgb(201,201,201); if (resource == R.color.slider_fill) return Color.rgb(13,71,161); return resource == R.color.text_primary || resource == R.color.accent ? Color.BLACK : Color.WHITE; } return getColor(resource); }
    private android.content.SharedPreferences getSettings() { return getSharedPreferences("reader_settings", MODE_PRIVATE); }
    // Explicitly named so it no longer depends on the class package the way Activity.getPreferences() does.
    private android.content.SharedPreferences documents() { return getSharedPreferences(DOCUMENT_PREFS, MODE_PRIVATE); }
    // Clamped, because a value saved under the earlier range could sit below what the slider now offers.
    private int fastSeekInterval(android.content.SharedPreferences p) {
        return Math.max(FAST_SEEK_MIN_MS, Math.min(FAST_SEEK_MAX_MS, p.getInt("fast_seek_interval", FAST_SEEK_DEFAULT_MS)));
    }

    private void chooseFile() {
        pausePlaybackOutsideReader();
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE)
            .putExtra(Intent.EXTRA_MIME_TYPES, OPENABLE_TYPES);
        leavingForResult = true;
        try { startActivityForResult(i, OPEN_TEXT); } catch (ActivityNotFoundException e) { leavingForResult = false; toast(getString(R.string.open_failed)); returnToReader(); }
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        leavingForResult = false;
        if (request == OPEN_TEXT && result == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Exception ignored) {}
            // Choosing a file is asking for it to be read, exactly as choosing one from Recent files is,
            // and as handing one over from another app is. It used to start only if the reading had been
            // running before the picker opened, which made Open the one way in that left you looking at a
            // book and wondering why it was silent.
            resumeAfterFilePickerLoad = true; pausedAutomaticallyOutsideReader = false;
            loadUri(uri, true);
        } else if (request == OPEN_TEXT) returnToReader();
    }
    private void loadUri(Uri uri, boolean remember) {
        if (loading) return; loading = true; if (reader != null) reader.pause(); status.setText(R.string.loading); updateControls();
        io.execute(() -> {
            try {
                String fileName = displayFileName(uri);
                byte[] bytes = readLimited(uri);
                // The name first, because it is cheap and usually right. When it settles nothing - a
                // manager that hands over a nameless stream - the bytes themselves are asked.
                String kind = DocumentText.kindOf(fileName);
                if (kind.isEmpty()) kind = DocumentText.kindOfContent(bytes);
                if (kind.isEmpty()) throw new IOException(getString(R.string.unsupported_content));
                boolean wrapped = "zip".equals(kind);
                if (wrapped) {
                    DocumentText.Entry inner = DocumentText.singleDocument(bytes);
                    if (inner == null) throw new IOException(getString(R.string.zip_one_book));
                    fileName = inner.name; kind = inner.kind; bytes = inner.bytes;
                }
                boolean plain = "txt".equals(kind) && !wrapped;
                // A plain text file is guessed at, because nothing in it says how it was written. The other
                // three say so themselves, so they are simply read.
                String loaded = ("txt".equals(kind) ? decode(bytes) : DocumentText.extract(kind, bytes))
                    .replace("\r\n", "\n").replace('\r', '\n');
                if (loaded.trim().isEmpty()) throw new IOException(getString(R.string.file_empty));
                String name = DocumentText.titleOf(kind, bytes, withoutExtension(fileName)); runOnUiThread(() -> {
                    if (destroyed) return; loadedText = loaded; fromPlainTextFile = plain; fromWeb = false; fromWebPage = false; currentUri = uri.toString(); currentName = name; title.setText(name); title.setVisibility(View.VISIBLE); title.setPadding(0, 0, 0, dp(8)); loading = false;
                    if (remember) documents().edit().putString("last_uri", currentUri).apply();
                    forgetCachedPage();
                    addRecent(DOCUMENTS_LIST, currentUri, currentName); if (reader == null) pendingText = loaded; else finishLoad(loaded);
                });
            } catch (Exception e) { runOnUiThread(() -> { loading = false; status.setText(R.string.open_failed); toast(e.getMessage()); updateControls(); if (resumeAfterFilePickerLoad) { resumeAfterFilePickerLoad = false; scheduleAutomaticPlayback(); } }); }
        });
    }
    private void finishLoad(String loaded) {
        // A book is picked up where it was left. A web page is three minutes long, and being dropped into
        // the middle of one is more often a puzzle than a convenience - so unless the setting says
        // otherwise, it starts at the top.
        boolean fromStart = fromWeb && !restoringPage && getSettings().getBoolean("web_from_start", true);
        restoringPage = false;
        int position = fromStart ? 0 : reader.savedPosition(currentUri);
        reader.load(currentUri, currentName, loaded, position, fromWeb);
        if (resumeAfterFilePickerLoad) { resumeAfterFilePickerLoad = false; scheduleAutomaticPlayback(); }
    }

    // Read the web page. The address is fetched as a browser would fetch it and stripped down to the article:
    // the title, the text, and the author and date when the page names them. If a plain request comes back
    // with a check page, a consent wall or an empty shell, the same address is loaded once more in a browser
    // that is never shown, and the finished page is read from there.
    private void loadArticle(String address, boolean startReading) {
        if (loading) return; loading = true; if (reader != null) reader.pause();
        // The name of whatever was open before must not stay over an article that is still being fetched. It
        // is put aside rather than thrown away: if the page cannot be read, the book that was open goes on
        // being the book that is open, with its name and its Save as TXT.
        String previousUri = currentUri, previousName = currentName, previousText = loadedText;
        boolean previousPlain = fromPlainTextFile, previousWeb = fromWeb, previousPage = fromWebPage;
        currentUri = ""; currentName = ""; loadedText = null;
        title.setText(""); title.setVisibility(View.GONE); title.setPadding(0, 0, 0, 0);
        status.setText(R.string.loading_page); updateControls();
        io.execute(() -> {
            ArticleReader.Result direct = null;
            try { direct = ArticleReader.extract(this, address, ArticleReader.download(address)); } catch (Exception ignored) {}
            ArticleReader.Result plain = direct;
            runOnUiThread(() -> {
                if (destroyed) return;
                if (ArticleReader.isUsable(plain)) { showArticle(plain, startReading); return; }
                HiddenPageLoader.load(this, address, new HiddenPageLoader.Callback() {
                    @Override public void onHtml(String html) {
                        io.execute(() -> {
                            ArticleReader.Result rendered = ArticleReader.extract(MainActivity.this, address, html);
                            runOnUiThread(() -> { if (destroyed) return; if (ArticleReader.isUsable(rendered)) showArticle(rendered, startReading); else articleFailed(previousUri, previousName, previousText, previousPlain, previousWeb, previousPage); });
                        });
                    }
                    @Override public void onFailure() { if (!destroyed) articleFailed(previousUri, previousName, previousText, previousPlain, previousWeb, previousPage); }
                });
            });
        });
    }
    private void showArticle(ArticleReader.Result article, boolean startReading) {
        // Remembered under Web pages, by its address. It is not remembered as the file to open next time and
        // it is not a file at all until Save as TXT in More makes it one.
        loadedText = article.text; fromPlainTextFile = false; fromWeb = true; fromWebPage = true;
        currentUri = article.url; currentName = article.title.isEmpty() ? getString(R.string.web_page) : article.title;
        title.setText(currentName); title.setVisibility(View.VISIBLE); title.setPadding(0, 0, 0, dp(8)); loading = false;
        addRecent(PAGES_LIST, currentUri, currentName);
        rememberPage(article.text, true);
        if (reader == null) { pendingText = article.text; resumeAfterFilePickerLoad = startReading; }
        else { finishLoad(article.text); if (startReading) scheduleAutomaticPlayback(); }
        updateControls();
    }
    private void articleFailed(String previousUri, String previousName, String previousText, boolean previousPlain, boolean previousWeb, boolean previousPage) {
        loading = false; resumeAfterFilePickerLoad = false;
        currentUri = previousUri; currentName = previousName; loadedText = previousText;
        fromPlainTextFile = previousPlain; fromWeb = previousWeb; fromWebPage = previousPage;
        title.setText(currentName); title.setVisibility(currentName.isEmpty() ? View.GONE : View.VISIBLE);
        title.setPadding(0, 0, 0, currentName.isEmpty() ? 0 : dp(8));
        status.setText(R.string.page_failed); toast(getString(R.string.page_failed)); updateControls();
    }
    // Text shared without an address in it - a passage copied out of an app, a note. There is nothing to
    // fetch and nothing to strip; it is read as it arrived, and Save as TXT keeps it if it is worth keeping.
    private void showSharedText(String shared) {
        loadedText = shared; fromPlainTextFile = false; fromWeb = true; fromWebPage = false;
        currentUri = "shared:" + Integer.toHexString(shared.hashCode()); currentName = getString(R.string.shared_text);
        title.setText(currentName); title.setVisibility(View.VISIBLE); title.setPadding(0, 0, 0, dp(8)); loading = false;
        rememberPage(shared, false);
        if (reader == null) { pendingText = shared; resumeAfterFilePickerLoad = true; }
        else { finishLoad(shared); scheduleAutomaticPlayback(); }
        updateControls();
    }
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
    private String withoutExtension(String value) {
        if (value == null) return null;
        int dot = value.lastIndexOf('.');
        return dot > 0 && !DocumentText.kindOf(value).isEmpty() ? value.substring(0, dot) : value;
    }

    @Override public void onPlaybackState(int index, int count, boolean playing) { runOnUiThread(() -> { if (playing && (pausedAutomaticallyOutsideReader || automaticResumePending)) cancelAutomaticResume(true); showCurrent(index, count); updatePlayButton(playing); applyScreenSetting(); updateSleepRow(); updateControls(); }); }
    // The row under the player has one place and three states: a running timer offers to be called off, an
    // expired one offers to go back to where it started, and the rest of the time it is empty but keeps its
    // height, so nothing on the screen moves. The two offers never arrive together - starting a timer clears
    // the return, and the return only appears once a timer has run out.
    private void updateSleepRow() {
        sleepRowHandler.removeCallbacksAndMessages(null);
        long remaining = reader == null ? 0 : reader.sleepRemainingMillis();
        if (remaining > 0) {
            // Rounded up, so a timer just set for thirty says thirty, and the last seconds say one minute
            // rather than none.
            int minutes = (int)((remaining + 59_999L) / 60_000L);
            sleepRewindButton.setText(getResources().getQuantityString(R.plurals.cancel_sleep_timer, minutes, minutes));
            sleepRewindButton.setVisibility(View.VISIBLE);
            // Woken exactly when the minute shown changes, instead of a tick running the whole time. The text
            // is not a live region, so a screen reader reads it when it is reached and not on every change.
            sleepRowHandler.postDelayed(this::updateSleepRow, remaining - (minutes - 1) * 60_000L + 200L);
            return;
        }
        if (reader != null && reader.isSleepRewindAvailable()) {
            int minutes = reader.getCompletedSleepMinutes();
            sleepRewindButton.setText(getResources().getQuantityString(R.plurals.rewind_sleep_minutes, minutes, minutes));
            sleepRewindButton.setVisibility(View.VISIBLE);
            return;
        }
        sleepRewindButton.setText(""); sleepRewindButton.setVisibility(View.INVISIBLE);
    }
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
            // Yellow in both themes, because that is what a highlighter is, and against a black page it is
            // the most visible thing on the screen. White lettering on yellow would be unreadable, so the
            // marked sentence is written in black wherever it appears - ink on a paper page.
            highlightInk = new android.text.style.ForegroundColorSpan(Color.BLACK);
            body.setText(new SpannableString(document), TextView.BufferType.SPANNABLE);
        }
        Spannable marked = (Spannable)body.getText();
        marked.removeSpan(highlightSpan); marked.removeSpan(highlightInk);
        marked.setSpan(highlightSpan, r.start, r.end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        marked.setSpan(highlightInk, r.start, r.end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        body.setContentDescription(document.substring(r.start, r.end).trim());
        status.setText(getString(R.string.sentence_count, index + 1, count));
        int percent = count <= 1 ? 0 : Math.round(index * 100f / (count - 1));
        updatingBookProgress = true; bookProgress.setProgress(percent); updatingBookProgress = false; updateBookProgressDescription(percent);
        body.post(() -> { android.text.Layout l = body.getLayout(); if (l != null) scroll.smoothScrollTo(0, Math.max(0, l.getLineTop(l.getLineForOffset(r.start)) - dp(80))); });
    }
    // Kept awake only while the reading is actually running and the reader itself is on the screen, and only
    // if the reader asked for it. The power button still turns the screen off, so a book listened to in a
    // pocket costs nothing; what this spares is the timeout in the middle of a short article, when the next
    // thing wanted is a button.
    private void applyScreenSetting() {
        // A new key on purpose. The setting was a checkbox for one build, so the old name may still hold a
        // true or false on a phone that had it, and asking for a word where a yes or no is stored is a crash.
        String wanted = getSettings().getString("keep_screen", "off");
        boolean matches = "both".equals(wanted) || (fromWeb ? "web".equals(wanted) : "documents".equals(wanted));
        boolean keep = matches && reader != null && reader.isPlaying();
        if (keep) getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
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
        int last = reader.getCount() - 1;
        int index = last <= 0 ? 0 : Math.round(percent * last / 100f);
        // A book has thousands of sentences and one percent of it is worth many of them. A news article has
        // twelve, and one percent is worth a fraction of one - so the sum gives back the sentence that is
        // already open, the slider is redrawn where it started, and it looks as though it refuses to move
        // forward. A step that changed the percentage has to change the sentence too, whatever the length of
        // the text; and a step that changed nothing at all does not interrupt the reading for nothing.
        if (last > 0) {
            int shown = Math.round(reader.getCurrent() * 100f / last);
            if (percent == shown) return;
            if (index == reader.getCurrent()) index = percent > shown ? Math.min(last, index + 1) : Math.max(0, index - 1);
        }
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
            updatePlayButton(reader.isPlaying());
            long deadline = SystemClock.elapsedRealtime() + MAX_FAST_SEEK_MS;
            Runnable repeated = new Runnable() {
                @Override public void run() {
                    if (!fastSeeking || reader == null) return;
                    int before = reader.getCurrent();
                    reader.move(direction * 5);
                    // At either end the sentence stops changing. Carrying on would tick against a wall for as
                    // long as the finger is down - and if the release is ever missed, for as long as the app
                    // is open, moving the reading back to the same place every few hundred milliseconds. That
                    // is what a book repeating one word for ever sounds like.
                    if (reader.getCurrent() == before) { stopFastSeek(true); return; }
                    if (getSettings().getBoolean("seek_vibration", true)) button.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                    // A backstop for a release that never arrives, whatever the reason. Nobody holds a seek
                    // button for a minute on purpose.
                    if (SystemClock.elapsedRealtime() > deadline) { stopFastSeek(true); return; }
                    seekHandler.postDelayed(this, fastSeekInterval(getSettings()));
                }
            };
            repeated.run(); return true;
        });
        // One rule for both kinds of seeking: while the book is silent the screen reader says where the user
        // has landed, and while it is reading nothing interrupts it - the text itself is the answer. Letting
        // go resumes at once; the delay used elsewhere exists to leave room for an announcement, and there
        // is none here.
        button.setOnTouchListener((v, event) -> { if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) stopFastSeek(true); return false; });
    }
    // The one way out of a fast seek, so that a release, an end of the text, a backstop, a rebuilt screen
    // and a closing app all leave the same state behind.
    private void stopFastSeek(boolean announce) {
        if (!fastSeeking) return;
        fastSeeking = false; seekHandler.removeCallbacksAndMessages(null);
        boolean resume = resumeAfterFastSeek; resumeAfterFastSeek = false;
        if (resume && reader != null) reader.play();
        else { updatePlayButton(reader != null && reader.isPlaying()); if (announce) announceCurrentSentence(); }
    }
    // A fast seek does stop the reading, because otherwise every step would start a sentence only to cut it
    // off a moment later. From where the reader is standing, though, nothing has stopped: a finger is held on
    // a button and the book is being wound forward, and it will carry on talking the moment the finger comes
    // up. So for as long as that is what is happening, the button keeps saying Pause - it is still the thing
    // that would interrupt the reading.
    private void updatePlayButton(boolean playing) {
        boolean reading = playing || (fastSeeking && resumeAfterFastSeek);
        play.setImageResource(reading ? R.drawable.ic_pause : R.drawable.ic_play);
        play.setContentDescription(getString(reading ? R.string.pause_sentence : R.string.play_sentence));
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
        // Save as TXT is offered only when what is open is a web page, because a book that came from a file is
        // already a file.
        java.util.List<String> names = new ArrayList<>(); java.util.List<Runnable> actions = new ArrayList<>();
        names.add(getString(R.string.open_url)); actions.add(this::showOpenUrlDialog);
        if (loadedText != null && fromWebPage) {
            names.add(getString(R.string.copy_text)); actions.add(this::copyLoadedText);
            names.add(getString(R.string.share_text)); actions.add(this::shareLoadedText);
        }
        if (loadedText != null && !fromPlainTextFile) { names.add(getString(R.string.save_as_txt)); actions.add(this::saveAsTxt); }
        names.add(getString(R.string.search)); actions.add(this::showSearchDialog);
        if (!fromWeb) { names.add(getString(R.string.bookmarks)); actions.add(this::showBookmarks); }
        names.add(getString(R.string.settings)); actions.add(this::showSettings);
        names.add(getString(R.string.credits)); actions.add(this::showCredits);
        final boolean[] chosen = {false};
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(R.string.more).setItems(names.toArray(new String[0]), (d, which) -> {
            chosen[0] = true; actions.get(which).run();
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
        if (marks.length() == 0) {
            TextView empty = emptyNotice(getString(R.string.no_bookmarks));
            box.addView(empty);
            if (pendingListRow >= 0) listFocusTarget = empty;
        }
        java.util.List<View> entries = new ArrayList<>();
        for (int i = 0; i < marks.length(); i++) {
            JSONObject mark = marks.optJSONObject(i); if (mark == null) continue;
            int sentence = mark.optInt("sentence"); String name = mark.optString("text");
            LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(0, dp(6), 0, dp(6));
            Button open = listRowButton(name, 18);
            open.setOnClickListener(v -> jumpFromList(sentence));
            ImageButton remove = imageButton(android.R.drawable.ic_menu_delete, R.string.delete); remove.setContentDescription(getString(R.string.remove_bookmark, name));
            final int position = entries.size();
            remove.setOnClickListener(v -> { removeBookmark(sentence); pendingListRow = position; showBookmarks(); });
            entries.add(open);
            row.addView(open, new LinearLayout.LayoutParams(0, -2, 1));
            LinearLayout.LayoutParams removeParams = new LinearLayout.LayoutParams(dp(56), dp(56)); removeParams.setMargins(dp(10), 0, 0, 0); row.addView(remove, removeParams);
            box.addView(row);
        }
        if (pendingListRow >= 0 && !entries.isEmpty())
            listFocusTarget = entries.get(Math.min(pendingListRow, entries.size() - 1));
        pendingListRow = -1;
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

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request != SAVE_TXT_PERMISSION) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) saveAsTxt();
        else toast(getString(R.string.save_failed));
    }
    // An address typed or pasted by hand, for when sharing from the browser is not to hand. The clipboard is
    // read once, as the dialog opens, and only to fill the field: if what is on it looks like an address, it
    // is already there and the reader only presses Open. Nothing is read from the clipboard at any other time.
    private void showOpenUrlDialog() {
        EditText input = new EditText(this); input.setTextSize(uiSize(20)); input.setHint(getString(R.string.web_address));
        input.setContentDescription(getString(R.string.web_address)); input.setInputType(android.text.InputType.TYPE_TEXT_VARIATION_URI);
        input.setPadding(dp(24), dp(12), dp(24), dp(12));
        String pasted = ArticleReader.firstUrl(clipboardText());
        if (!pasted.isEmpty()) { input.setText(pasted); input.setSelectAllOnFocus(true); }
        LinearLayout content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL); content.setPadding(dp(24), dp(12), dp(24), dp(12));
        TextView heading = label(getString(R.string.open_url), 23, true); if (Build.VERSION.SDK_INT >= 28) heading.setAccessibilityHeading(true);
        content.addView(heading); content.addView(input, new LinearLayout.LayoutParams(-1, -2));
        final boolean[] opened = {false};
        AlertDialog dialog = new AlertDialog.Builder(this).setView(content)
            .setNegativeButton(R.string.close, null).setPositiveButton(R.string.open_url_action, null).create();
        dialog.setOnDismissListener(d -> { if (!opened[0]) returnToReader(); });
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String address = ArticleReader.firstUrl(input.getText().toString());
            if (address.isEmpty()) { toast(getString(R.string.no_address_shared)); return; }
            opened[0] = true; dialog.dismiss();
            returnToReaderScreen(); pausedAutomaticallyOutsideReader = false; loadArticle(address, true);
        });
        focusHeading(heading);
    }
    // The article on the clipboard, to be pasted wherever the reader wants it. From Android 13 the system
    // shows its own confirmation of a copy, so saying it again here would be the same news twice.
    private void copyLoadedText() {
        if (loadedText == null) { returnToReader(); return; }
        try {
            android.content.ClipboardManager clipboard = (android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
            if (clipboard == null) throw new IllegalStateException();
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText(currentName, loadedText));
            if (Build.VERSION.SDK_INT < 33) toast(getString(R.string.text_copied));
        } catch (Exception e) { toast(getString(R.string.copy_failed)); }
        returnToReader();
    }
    // The article on its way to somebody else, as text rather than as a file. It stands beside Copy because
    // it carries the same content under the same ceiling: a share hands its text through a system transaction
    // that a whole book would never fit into. That is why neither is offered for a book - a book travels as a
    // file, which is what Save as TXT is for.
    private void shareLoadedText() {
        if (loadedText == null) { returnToReader(); return; }
        Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, loadedText);
        if (!currentName.isEmpty()) send.putExtra(Intent.EXTRA_SUBJECT, currentName);
        // Marked as a trip out of the app on purpose. Without it, leaving for the chooser looks like the
        // reader walking away, and with Close the app with Back or Home switched on the app would shut itself
        // down underneath the chooser.
        leavingForResult = true;
        try { startActivity(Intent.createChooser(send, getString(R.string.share_text))); }
        catch (Exception e) { leavingForResult = false; toast(getString(R.string.share_failed)); returnToReader(); }
    }
    private String clipboardText() {
        try {
            android.content.ClipboardManager clipboard = (android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
            if (clipboard == null || !clipboard.hasPrimaryClip()) return "";
            android.content.ClipData clip = clipboard.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) return "";
            CharSequence value = clip.getItemAt(0).coerceToText(this);
            return value == null ? "" : value.toString();
        } catch (Exception e) { return ""; }
    }
    private void showCredits() {
        LinearLayout box = listPage();
        TextView text = label(getString(R.string.credits_text), 18, false);
        text.setPadding(dp(8), dp(12), dp(8), dp(12)); text.setLineSpacing(0, 1.25f);
        box.addView(text);
        showListPage(R.string.credits, box);
    }

    // The article as a file, in the Downloads folder under a Vox TXT of its own, so it sits with everything
    // else the phone has downloaded and is opened from Open TXT like any other book.
    private void saveAsTxt() {
        if (loadedText == null) { returnToReader(); return; }
        if (Build.VERSION.SDK_INT < 29 && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, SAVE_TXT_PERMISSION); return;
        }
        String fileName = safeFileName(currentName) + ".txt";
        String text = loadedText;
        io.execute(() -> {
            String written = null;
            try { written = writeToDownloads(fileName, text); } catch (Exception ignored) {}
            String message = written;
            runOnUiThread(() -> { toast(message == null ? getString(R.string.save_failed) : getString(R.string.saved_to, message)); returnToReader(); });
        });
    }
    // UTF-8, and a byte order mark in front of it whenever the text is not plain English letters. That mark is
    // what makes Windows editors and readers open a Cyrillic file as Cyrillic instead of as rubbish; a file
    // that has nothing but ASCII in it needs no mark and gets none.
    private byte[] encodeForFile(String text) {
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        if (body.length == text.length()) return body;
        byte[] withMark = new byte[body.length + 3];
        withMark[0] = (byte)0xEF; withMark[1] = (byte)0xBB; withMark[2] = (byte)0xBF;
        System.arraycopy(body, 0, withMark, 3, body.length);
        return withMark;
    }
    private String writeToDownloads(String fileName, String text) throws IOException {
        byte[] bytes = encodeForFile(text);
        String folder = getString(R.string.app_name);
        if (Build.VERSION.SDK_INT >= 29) {
            android.content.ContentValues values = new android.content.ContentValues();
            values.put(android.provider.MediaStore.Downloads.DISPLAY_NAME, fileName);
            values.put(android.provider.MediaStore.Downloads.MIME_TYPE, "text/plain");
            values.put(android.provider.MediaStore.Downloads.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS + "/" + folder);
            Uri target = getContentResolver().insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (target == null) throw new IOException("no place to write");
            try (OutputStream out = getContentResolver().openOutputStream(target)) {
                if (out == null) throw new IOException("no place to write");
                out.write(bytes);
            }
            return android.os.Environment.DIRECTORY_DOWNLOADS + "/" + folder + "/" + fileName;
        }
        File directory = new File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), folder);
        if (!directory.exists() && !directory.mkdirs()) throw new IOException("no place to write");
        File target = new File(directory, fileName);
        try (OutputStream out = new java.io.FileOutputStream(target)) { out.write(bytes); }
        return android.os.Environment.DIRECTORY_DOWNLOADS + "/" + folder + "/" + fileName;
    }
    // The log is meant to travel. A tester who cannot see the screen should not have to hunt for a file in
    // Downloads and attach it by hand, so one press hands it straight to the share sheet.
    // Left from the days when Share the log lived here: the file picker sets this flag too, and a stale
    // one would make the app shut itself down under a chooser. Cheap, and it guards a path that was hard won.
    @Override protected void onResume() { super.onResume(); leavingForResult = false; }

    // Characters a file name cannot hold, and a length a file name should not have.
    private String safeFileName(String name) {
        String cleaned = (name == null ? "" : name).replaceAll("[\\\\/:*?\"<>|\\r\\n\\t]", " ").replaceAll("\\s+", " ").trim();
        if (cleaned.length() > 80) cleaned = cleaned.substring(0, 80).trim();
        return cleaned.isEmpty() ? getString(R.string.web_page) : cleaned;
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
        // The lettering has to come back to the colour of the page. A button hands out the colour that
        // belongs on top of a filled button, and the fill is taken away on the next line - which left white
        // on white in one theme and black on black in the other, so the rows were there and could be read
        // aloud but could not be seen at all.
        row.setTextColor(appColor(R.color.text_primary));
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
        TextView interfaceValueLabel = addSliderHeader(box, R.string.interface_font_size, 18, dp(12)); SeekBar interfaceFont = new SeekBar(this); thicken(interfaceFont); interfaceFont.setMax(20); int originalInterfaceScale = p.getInt("interface_scale", 100); interfaceFont.setProgress(Math.max(0, Math.min(20, (originalInterfaceScale - 50) / 5))); sliderValues.put(interfaceFont, interfaceValueLabel); updateSliderPercentValue(interfaceFont); box.addView(interfaceFont);
        SeekBar font = seek(box, R.string.document_font_size, 14, 32, p.getInt("font_size", 23));
        SeekBar fastSeekInterval = millisecondSeek(box, R.string.fast_seek_interval, FAST_SEEK_MIN_MS, FAST_SEEK_MAX_MS, 50, fastSeekInterval(p));
        CheckBox seekVibration = new CheckBox(this); seekVibration.setText(R.string.seek_vibration); seekVibration.setTextSize(uiSize(18)); seekVibration.setChecked(p.getBoolean("seek_vibration", true)); seekVibration.setPadding(0, dp(6), 0, dp(10)); box.addView(seekVibration, new LinearLayout.LayoutParams(-1, -2));
        CheckBox pauseForSettings = new CheckBox(this); pauseForSettings.setText(R.string.pause_for_settings); pauseForSettings.setTextSize(uiSize(18)); pauseForSettings.setChecked(p.getBoolean("pause_for_settings", true)); pauseForSettings.setPadding(0, dp(10), 0, dp(6)); box.addView(pauseForSettings, new LinearLayout.LayoutParams(-1, -2));
        CheckBox preventDeviceAutoplay = new CheckBox(this); preventDeviceAutoplay.setText(R.string.prevent_device_autoplay); preventDeviceAutoplay.setTextSize(uiSize(18)); preventDeviceAutoplay.setChecked(p.getBoolean("prevent_device_autoplay", true)); preventDeviceAutoplay.setPadding(0, dp(6), 0, dp(10)); box.addView(preventDeviceAutoplay, new LinearLayout.LayoutParams(-1, -2));
        TextView keepScreenLabel = label(getString(R.string.keep_screen_on), 18, true); keepScreenLabel.setPadding(0, dp(12), 0, 0); box.addView(keepScreenLabel);
        AccessibleSpinner keepScreenSpinner = new AccessibleSpinner(this);
        String[] keepScreenNames = {getString(R.string.keep_screen_off), getString(R.string.documents_section), getString(R.string.pages_section), getString(R.string.keep_screen_both)};
        keepScreenSpinner.setAdapter(themedSpinnerAdapter(keepScreenNames));
        int keepScreenSaved = Math.max(0, Math.min(KEEP_SCREEN_VALUES.length - 1, indexOf(KEEP_SCREEN_VALUES, p.getString("keep_screen", "off"))));
        keepScreenSpinner.setSelection(keepScreenSaved, false); configureSpinnerAccessibility(keepScreenSpinner, keepScreenNames);
        box.addView(keepScreenSpinner);
        CheckBox webFromStart = new CheckBox(this); webFromStart.setText(R.string.web_from_start); webFromStart.setTextSize(uiSize(18)); webFromStart.setChecked(p.getBoolean("web_from_start", true)); webFromStart.setPadding(0, dp(6), 0, dp(10)); box.addView(webFromStart, new LinearLayout.LayoutParams(-1, -2));
        CheckBox closeOnBack = new CheckBox(this); closeOnBack.setText(R.string.close_on_back); closeOnBack.setTextSize(uiSize(18)); closeOnBack.setChecked(p.getBoolean("close_on_back", false)); closeOnBack.setPadding(0, dp(6), 0, dp(10)); box.addView(closeOnBack, new LinearLayout.LayoutParams(-1, -2));
        final int[] previewScale = {originalInterfaceScale}; final boolean[] keepPreview = {false};
        interfaceFont.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onStartTrackingTouch(SeekBar seekBar) {} public void onStopTrackingTouch(SeekBar seekBar) {}
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) { int value = 50 + progress * 5; updateSliderPercentValue(seekBar); if (value != previewScale[0]) { previewInterfaceScale(previewScale[0], value); previewScale[0] = value; } }
        });
        Runnable applyOptions = () -> {
            int fontValue = seekValue(font), interfaceValue = 50 + interfaceFont.getProgress() * 5; String selectedTheme = themeValues[themeSpinner.getSelectedItemPosition()], selectedLanguage = languageValues[languageSpinner.getSelectedItemPosition()]; boolean themeChanged = !selectedTheme.equals(p.getString("theme", "system")), languageChanged = !selectedLanguage.equals(p.getString("language", "system"));
            p.edit().putInt("font_size", fontValue).putInt("interface_scale", interfaceValue).putInt("fast_seek_interval", seekValue(fastSeekInterval)).putString("theme", selectedTheme).putString("language", selectedLanguage).putBoolean("pause_for_settings", pauseForSettings.isChecked()).putBoolean("seek_vibration", seekVibration.isChecked()).putBoolean("web_from_start", webFromStart.isChecked()).putBoolean("close_on_back", closeOnBack.isChecked()).putBoolean("prevent_device_autoplay", preventDeviceAutoplay.isChecked()).putString("keep_screen", KEEP_SCREEN_VALUES[keepScreenSpinner.getSelectedItemPosition()]).apply(); applyScreenSetting(); keepPreview[0] = true; body.setTextSize(fontValue); if (languageChanged) applyLanguage(selectedLanguage); if (themeChanged || languageChanged) { resumeAfterRecreate = pausedAutomaticallyOutsideReader; pausedAutomaticallyOutsideReader = false; subpageCloseAction = null; keepReadingAfterFinish = true; getWindow().getDecorView().post(this::recreate); } else closeRecent();
        };
        Runnable closeOptions = () -> { if (!keepPreview[0]) previewInterfaceScale(previewScale[0], originalInterfaceScale); };
        showSettingsPage(R.string.settings, box, applyOptions, closeOptions);
    }

    // Two tabs under the heading. The app builds its screens in code and has no tab widget to borrow, so this
    // is the whole of it: a row of two buttons where the open one is marked as selected. A screen reader says
    // "selected" for it on its own, which is why the state is carried by setSelected and not by a tick drawn
    // into the caption.
    private LinearLayout tabRow(int[] captions, int active, java.util.function.IntConsumer onPick) {
        LinearLayout row = new LinearLayout(this); row.setPadding(dp(4), dp(2), dp(4), dp(6));
        pickedTab = null;
        for (int i = 0; i < captions.length; i++) {
            final int index = i;
            Button tab = compactButton(getString(captions[i]));
            tab.setTextSize(uiSize(18)); tab.setPadding(dp(8), dp(10), dp(8), dp(10)); tab.setMinimumHeight(dp(52));
            boolean open = i == active;
            tab.setSelected(open);
            tab.setTypeface(tab.getTypeface(), open ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
            // Both tabs used to be the same grey, so which one was open could only be heard, never seen. The
            // open one is filled; the other is left as plain lettering on the page, which is also the only
            // way to keep them apart now that every button is blue.
            if (!open) {
                tab.setBackgroundTintList(android.content.res.ColorStateList.valueOf(appColor(R.color.window_bg)));
                tab.setTextColor(appColor(R.color.text_secondary));
            }
            if (open) pickedTab = tab;
            else tab.setOnClickListener(v -> { focusPickedTab = true; onPick.accept(index); });
            row.addView(tab, new LinearLayout.LayoutParams(0, -2, 1));
        }
        return row;
    }
    private void showVoiceSettings(String profile) {
        if (reader == null) return; android.content.SharedPreferences p = getSettings(); pausePlaybackOutsideReader(); sliderValues.clear();
        final String[] kept = pendingVoice.get(profile);
        ArrayList<ReaderService.EngineOption> engines = new ArrayList<>(); engines.add(new ReaderService.EngineOption("", getString(R.string.default_voice))); engines.addAll(reader.getEngineOptions());
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(dp(20), 0, dp(20), 0);
        TextView engineLabel = label(getString(R.string.speech_engine), 18, true); engineLabel.setPadding(0, dp(12), 0, 0); box.addView(engineLabel);
        String[] engineLabels = new String[engines.size()]; for (int i = 0; i < engines.size(); i++) engineLabels[i] = engines.get(i).label; AccessibleSpinner engineSpinner = new AccessibleSpinner(this); engineSpinner.setAdapter(themedSpinnerAdapter(engineLabels)); String savedEngine = kept != null ? kept[0] : p.getString(profile + "engine", ""); int selectedEngine = 0; for (int i = 1; i < engines.size(); i++) if (engines.get(i).name.equals(savedEngine)) { selectedEngine = i; break; } engineSpinner.setSelection(selectedEngine, false); box.addView(engineSpinner);
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
                languageSpinner.setAdapter(themedSpinnerAdapter(languageNames)); String savedVoice = kept != null && requestedEngine.equals(kept[0]) ? kept[1] : p.getString(ReaderService.voicePreferenceKey(profile, requestedEngine), "");
                String wantedTag = localeForVoice(available, savedVoice);
                int selectedLanguage = bestLanguagePosition(voiceLanguages, wantedTag); languageSpinner.setSelection(selectedLanguage, false);
                configureSpinnerAccessibility(languageSpinner, languageNames, selected -> populateVoiceChoices(voiceSpinner, voiceSelection, voiceLanguages.get(selected).tag, savedVoice));
                populateVoiceChoices(voiceSpinner, voiceSelection, voiceLanguages.get(selectedLanguage).tag, savedVoice);
                languageLabel.setVisibility(View.VISIBLE); languageSpinner.setVisibility(View.VISIBLE); voiceLabel.setVisibility(View.VISIBLE); voiceSpinner.setVisibility(View.VISIBLE); if (previewButton != null) previewButton.setVisibility(View.VISIBLE);
            }));
        });
        PercentSeekBar rate = percentSeek(box, R.string.speech_rate, kept != null ? Integer.parseInt(kept[2]) : p.getInt(profile + "rate_percent", 20));
        PercentSeekBar pitch = percentSeek(box, R.string.pitch, kept != null ? Integer.parseInt(kept[3]) : p.getInt(profile + "pitch_percent", 20));
        PercentSeekBar volume = percentSeek(box, R.string.volume, kept != null ? Integer.parseInt(kept[4]) : p.getInt(profile + "volume_percent", 50));
        SeekBar gap = millisecondSeek(box, kept != null ? Integer.parseInt(kept[5]) : p.getInt(profile + "sentence_pause", 0));
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
        // What the open tab holds right now, ready either to be written out or to be kept while the other
        // tab is looked at.
        Runnable rememberTab = () -> pendingVoice.put(profile, new String[]{
            engines.get(Math.max(0, engineSpinner.getSelectedItemPosition())).name,
            selectedVoiceName(voiceSelection, voiceSpinner),
            String.valueOf(rate.percent()), String.valueOf(pitch.percent()),
            String.valueOf(volume.percent()), String.valueOf(seekValue(gap))});
        voiceTabSwitch = wanted -> { rememberTab.run(); previewHandler.removeCallbacksAndMessages(null); reader.stopPreview(); showVoiceSettings(wanted); };
        // Apply writes both sets, not only the one on the screen. Edit the web voice, step back to the
        // documents tab, press Apply - and the web voice is saved too, because it was remembered on the way
        // out of its tab rather than left on a screen that no longer exists.
        Runnable applyVoice = () -> {
            previewHandler.removeCallbacksAndMessages(null); reader.stopPreview();
            rememberTab.run();
            android.content.SharedPreferences.Editor edit = p.edit().remove("voice").remove("rate").remove("pitch");
            for (Map.Entry<String, String[]> entry : pendingVoice.entrySet()) {
                String which = entry.getKey(); String[] values = entry.getValue();
                edit.putString(which + "engine", values[0])
                    .putString(ReaderService.voicePreferenceKey(which, values[0]), values[1])
                    .putInt(which + "rate_percent", Integer.parseInt(values[2]))
                    .putInt(which + "pitch_percent", Integer.parseInt(values[3]))
                    .putInt(which + "volume_percent", Integer.parseInt(values[4]))
                    .putInt(which + "sentence_pause", Integer.parseInt(values[5]));
            }
            edit.apply(); pendingVoice.clear();
            boolean restartAfterApply = reader.isPlaying(); reader.updateSettings(false); closeRecent();
            if (restartAfterApply) scheduleAutomaticPlayback();
        };
        Runnable closeVoice = () -> { previewHandler.removeCallbacksAndMessages(null); previewAction = null; pendingVoice.clear(); if (reader != null) reader.stopPreview(); };
        LinearLayout tabs = tabRow(new int[]{R.string.documents_section, R.string.pages_section},
            WEB_PROFILE.equals(profile) ? 1 : 0, index -> voiceTabSwitch.accept(index == 1 ? WEB_PROFILE : ""));
        showSettingsPage(R.string.voice_settings, box, previewButton, applyVoice, closeVoice, tabs);
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
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(dp(20), dp(8), dp(20), dp(8)); int[] minutes = {15, 30, 45, 60, 90};
        // A menu and not a set of radio buttons: each row is a thing that happens the moment it is touched,
        // and there is nothing to mark as chosen, because a running timer is shown by its own button under
        // the player. Off is gone from here for the same reason - that button is where a timer is called off.
        LinearLayout choices = new LinearLayout(this); choices.setOrientation(LinearLayout.VERTICAL); int savedChoice = p.getInt("sleep_choice", 0);
        for (int m : minutes) { Button option = listRowButton(getResources().getQuantityString(R.plurals.minutes, m, m), 18); option.setTag(m); choices.addView(option, new LinearLayout.LayoutParams(-1, -2)); }
        Button customChoice = listRowButton(getString(R.string.custom_timer), 18); customChoice.setTag(-1); choices.addView(customChoice, new LinearLayout.LayoutParams(-1, -2)); box.addView(choices);
        LinearLayout customHeader = new LinearLayout(this); customHeader.setGravity(Gravity.CENTER_VERTICAL); TextView customLabel = label(getString(R.string.custom_timer), 18, true); TextView customValue = valueLabel(); customHeader.addView(customLabel, new LinearLayout.LayoutParams(0, -2, 1)); customHeader.addView(customValue, new LinearLayout.LayoutParams(-2, -2)); MinuteSeekBar custom = new MinuteSeekBar(this); thicken(custom); custom.setProgress(Math.max(0, Math.min(89, p.getInt("custom_sleep_minutes", 30) - 1)));
        SeekBar.OnSeekBarChangeListener customListener = new SeekBar.OnSeekBarChangeListener() { public void onStartTrackingTouch(SeekBar s) {} public void onStopTrackingTouch(SeekBar s) {} public void onProgressChanged(SeekBar s, int progress, boolean fromUser) { int value = progress + 1; String spoken = getResources().getQuantityString(R.plurals.minutes, value, value); customValue.setText(getString(R.string.minutes_short_value, value)); setSliderValueDescription(custom, spoken); } }; custom.setOnSeekBarChangeListener(customListener); customListener.onProgressChanged(custom, custom.getProgress(), false);
        box.addView(customHeader); box.addView(custom, new LinearLayout.LayoutParams(-1, dp(56))); boolean showCustom = savedChoice == -1; customHeader.setVisibility(showCustom ? View.VISIBLE : View.GONE); custom.setVisibility(showCustom ? View.VISIBLE : View.GONE);
        ScrollView timerScroll = new ScrollView(this); timerScroll.addView(box);
        final boolean[] applied = {false};
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(R.string.sleep_timer).setView(timerScroll).setNegativeButton(R.string.close, null).setPositiveButton(R.string.apply, null).create();
        dialog.setOnDismissListener(d -> { if (!applied[0]) returnToReader(); }); dialog.show();
        // A ready-made value is the whole decision by itself: one tap starts the timer and the dialog closes.
        // Only the custom one is still being set while it is being touched, so it alone keeps Apply, and Apply
        // is shown only while it is chosen. The number stands above the slider, so there is no reason to
        // repeat it on the button.
        Button apply = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        apply.setVisibility(showCustom ? View.VISIBLE : View.GONE);
        apply.setOnClickListener(v -> { applySleepChoice(p, applied, -1, custom.getProgress() + 1); dialog.dismiss(); });
        for (int i = 0; i < choices.getChildCount(); i++) {
            View option = choices.getChildAt(i); int value = (Integer)option.getTag();
            if (value == -1) option.setOnClickListener(v -> {
                customHeader.setVisibility(View.VISIBLE); custom.setVisibility(View.VISIBLE); apply.setVisibility(View.VISIBLE);
                custom.post(() -> { custom.requestFocus(); custom.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null); });
            });
            else option.setOnClickListener(v -> { applySleepChoice(p, applied, value, custom.getProgress() + 1); dialog.dismiss(); });
        }
        focusDialogTitle(dialog);
    }
    private void applySleepChoice(android.content.SharedPreferences p, boolean[] applied, int choice, int customMinutes) {
        if (reader == null) return;
        p.edit().putInt("custom_sleep_minutes", customMinutes).putInt("sleep_choice", choice).apply();
        int chosen = choice == -1 ? customMinutes : choice;
        applied[0] = true; reader.setSleepMinutes(chosen); updateSleepRow();
        if (chosen > 0 && !reader.isPlaying()) { pausedAutomaticallyOutsideReader = false; scheduleAutomaticPlayback(); }
    }
    private SeekBar seek(LinearLayout box, int label, int min, int max, int value) {
        TextView valueLabel = addSliderHeader(box, label, 18, dp(12)); SeekBar s = new SeekBar(this); thicken(s); s.setTag(new SeekRange(min, max)); s.setMax(20); s.setProgress(Math.round((value - min) * 20f / (max - min))); sliderValues.put(s, valueLabel); updateSliderPercentValue(s); s.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() { public void onStartTrackingTouch(SeekBar seekBar) {} public void onStopTrackingTouch(SeekBar seekBar) {} public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) { updateSliderPercentValue(seekBar); } }); box.addView(s); return s;
    }
    private PercentSeekBar percentSeek(LinearLayout box, int label, int value) {
        TextView valueLabel = addSliderHeader(box, label, 18, dp(12)); PercentSeekBar seek = new PercentSeekBar(this); thicken(seek); sliderValues.put(seek, valueLabel); seek.setPercent(Math.round(Math.max(0, Math.min(100, value)) / 5f) * 5); updatePercentValue(seek, seek.percent());
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() { public void onStartTrackingTouch(SeekBar s) {} public void onStopTrackingTouch(SeekBar s) {} public void onProgressChanged(SeekBar s, int progress, boolean fromUser) { int percent = seek.percent(); int rounded = Math.max(0, Math.min(100, Math.round(percent / 5f) * 5)); if (rounded != percent) seek.setPercent(rounded); else updatePercentValue(seek, rounded); } });
        box.addView(seek); return seek;
    }
    private void setMillisecondsDescription(SeekBar gap) { updateMillisecondsValue(gap); gap.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() { public void onStartTrackingTouch(SeekBar s) {} public void onStopTrackingTouch(SeekBar s) {} public void onProgressChanged(SeekBar s, int progress, boolean fromUser) { updateMillisecondsValue(s); } }); }
    private void setSliderValueDescription(SeekBar seek, String value) { if (Build.VERSION.SDK_INT >= 30) { seek.setContentDescription(null); seek.setStateDescription(value); } else seek.setContentDescription(value); }
    private SeekBar millisecondSeek(LinearLayout box, int value) { return millisecondSeek(box, R.string.sentence_pause, 0, 2000, 100, value); }
    private SeekBar millisecondSeek(LinearLayout box, int labelResource, int min, int max, int step, int value) { TextView valueLabel = addSliderHeader(box, labelResource, 18, dp(12)); SeekBar seek = new SeekBar(this); thicken(seek); seek.setTag(new SeekRange(min, max)); seek.setMax((max - min) / step); seek.setProgress(Math.max(0, Math.min(seek.getMax(), Math.round((value - min) / (float)step)))); sliderValues.put(seek, valueLabel); box.addView(seek); setMillisecondsDescription(seek); return seek; }
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
    private void populateVoiceChoices(AccessibleSpinner spinner, VoiceSelection selection, String localeTag, String wantedVoice) {
        // Which voices exist at all is decided in the service; here they are only narrowed to one language.
        ArrayList<ReaderService.VoiceOption> matching = new ArrayList<>(); for (ReaderService.VoiceOption voice : selection.all) { String language = voice.localeTag.isEmpty() ? Locale.getDefault().getLanguage() : Locale.forLanguageTag(voice.localeTag).getLanguage(); if (localeTag.equalsIgnoreCase(language)) matching.add(voice); }
        selection.visible = new ArrayList<>(); selection.visible.add(new ReaderService.VoiceOption("", getString(R.string.default_voice), localeTag)); selection.visible.addAll(matching);
        String[] labels = new String[selection.visible.size()]; labels[0] = getString(R.string.default_voice); for (int i = 0; i < matching.size(); i++) labels[i + 1] = voiceLabel(matching.get(i).name, i + 1);
        spinner.setAdapter(themedSpinnerAdapter(labels)); int selected = 0; for (int i = 1; i < selection.visible.size(); i++) if (selection.visible.get(i).name.equals(wantedVoice)) { selected = i; break; } spinner.setSelection(selected, false); spinner.setEnabled(selection.visible.size() > 1); configureSpinnerAccessibility(spinner, labels);
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
    // Where the screen reader goes when a page has been built. Two cases ask for it by name: an entry
    // removed from a list, where the reader belongs at the place it stood, and a tab just picked, which must
    // not throw the reader back to the top of the page.
    //
    // Otherwise nothing is moved. The window carries the name of the page now and the system announces that
    // by itself; putting the reader on a heading that says those same words only said them a second time.
    private void focusAfterBuild(View heading) {
        if (listFocusTarget != null) { View target = listFocusTarget; listFocusTarget = null; focusPickedTab = false; focusHeading(target); return; }
        if (focusPickedTab && pickedTab != null) { focusPickedTab = false; focusHeading(pickedTab); return; }
        focusPickedTab = false;
    }
    private void focusHeading(View heading) { heading.postDelayed(() -> heading.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null), 220L); }
    private ArrayAdapter<String> themedSpinnerAdapter(String[] values) { return new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, values) { private View style(View view, boolean dropdown) { if (view instanceof TextView) { ((TextView)view).setTextColor(appColor(R.color.text_primary)); ((TextView)view).setTextSize(uiSize(18)); view.setBackgroundColor(appColor(R.color.window_bg)); view.setPadding(dp(12), dp(12), dp(12), dp(12)); view.setImportantForAccessibility(dropdown ? View.IMPORTANT_FOR_ACCESSIBILITY_YES : View.IMPORTANT_FOR_ACCESSIBILITY_NO); view.setAccessibilityDelegate(new View.AccessibilityDelegate() { @Override public void onInitializeAccessibilityNodeInfo(View host, android.view.accessibility.AccessibilityNodeInfo info) { super.onInitializeAccessibilityNodeInfo(host, info); info.setCollectionItemInfo(null); } }); } return view; } @Override public View getView(int position, View convertView, ViewGroup parent) { return style(super.getView(position, convertView, parent), false); } @Override public View getDropDownView(int position, View convertView, ViewGroup parent) { View row = style(super.getDropDownView(position, convertView, parent), true); nudgePopupFocus(row); return row; } }; }
    // The platform's popup is left exactly as it is. This only moves the screen reader onto a row of it when
    // the system has not put it on one itself - which is what happens once the list is long enough to scroll,
    // and is why a long list used to announce itself and then leave the reader to go looking.
    //
    // The wait is for the window to finish appearing; asking before that lands on a row that is not on the
    // screen yet. The check afterwards is what leaves a short list alone: there the first entry already has
    // the focus, and taking it again would only say it twice.
    //
    // Nothing at all is done on the way out. Whichever way the popup closes, where the reader goes next is
    // the system's business, and every attempt to improve on it made the two ways out disagree.
    private void nudgePopupFocus(View row) {
        if (!spinnerPopupOpening) return;
        row.postDelayed(() -> {
            if (!spinnerPopupOpening || !row.isAttachedToWindow()) return;
            spinnerPopupOpening = false;
            if (row.isAccessibilityFocused()) return;
            row.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null);
        }, 300L);
    }
    private boolean spinnerPopupOpening;
    private int seekValue(SeekBar seek) { SeekRange range = (SeekRange)seek.getTag(); return Math.round(range.min + seek.getProgress() * (range.max - range.min) / (float)seek.getMax()); }
    private void applySavedLanguage() { applyLanguage(getSharedPreferences("reader_settings", MODE_PRIVATE).getString("language", "system")); }
    private void applyLanguage(String language) {
        if (Build.VERSION.SDK_INT >= 33) { android.os.LocaleList locales = "system".equals(language) ? android.os.LocaleList.getEmptyLocaleList() : android.os.LocaleList.forLanguageTags(language); android.app.LocaleManager manager = getSystemService(android.app.LocaleManager.class); if (manager != null && !manager.getApplicationLocales().equals(locales)) manager.setApplicationLocales(locales); }
        else { Locale locale = "system".equals(language) ? android.content.res.Resources.getSystem().getConfiguration().getLocales().get(0) : Locale.forLanguageTag(language); android.content.res.Configuration configuration = new android.content.res.Configuration(getResources().getConfiguration()); configuration.setLocale(locale); getResources().updateConfiguration(configuration, getResources().getDisplayMetrics()); }
    }
    private void previewInterfaceScale(int oldValue, int newValue) { if (oldValue <= 0 || oldValue == newValue) return; scaleTextViews(appRoot, newValue / (float)oldValue); }
    private void scaleTextViews(View view, float factor) { if (view instanceof TextView && view != body) ((TextView)view).setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, ((TextView)view).getTextSize() * factor); if (view instanceof ViewGroup) { ViewGroup group = (ViewGroup)view; for (int i = 0; i < group.getChildCount(); i++) scaleTextViews(group.getChildAt(i), factor); } }
    // Two lists, kept apart because they are opened in different ways: a document is read from a file that is
    // still on the phone, a web page is fetched again from its address.
    private static final String DOCUMENTS_LIST = "recent", PAGES_LIST = "recent_pages", WEB_PROFILE = "web_";
    // engine, voice, rate, pitch, volume, gap - as last seen in each tab of the voice page.
    private final Map<String, String[]> pendingVoice = new HashMap<>();
    private String recentTab = DOCUMENTS_LIST;
    // Picking a tab rebuilds the page. Sending the screen reader back to the heading afterwards would make
    // every switch a trip back to the top; the finger is on the tab, so that is where the focus belongs and
    // what it should say is the name of the tab and that it is selected.
    // Where the screen reader should land after a list has been rebuilt. Removing an entry leaves the
    // reader at the place the entry stood, on whatever moved up into it, rather than sending it back to the
    // heading and making it walk down the whole list again.
    private View listFocusTarget;
    private int pendingListRow = -1;
    private boolean focusPickedTab;
    private View pickedTab;
    private java.util.function.Consumer<String> voiceTabSwitch;
    private static final int RECENT_LIMIT = 20;
    private void addRecent(String list, String uri, String name) {
        try { JSONArray old = new JSONArray(documents().getString(list, "[]")); JSONArray fresh = new JSONArray(); fresh.put(new JSONObject().put("uri", uri).put("name", name));
            for (int i = 0; i < old.length() && fresh.length() < RECENT_LIMIT; i++) if (!uri.equals(old.getJSONObject(i).optString("uri"))) fresh.put(old.getJSONObject(i));
            documents().edit().putString(list, fresh.toString()).apply();
        } catch (JSONException ignored) {}
    }
    private void showRecent() {
        pausePlaybackOutsideReader(); setTitle(getString(R.string.recent_books));
        showingRecent = true; int pad = dp(16);
        LinearLayout page = new LinearLayout(this); page.setOrientation(LinearLayout.VERTICAL); page.setPadding(pad, dp(10), pad, dp(16)); page.setBackgroundColor(appColor(R.color.window_bg));
        page.setOnApplyWindowInsetsListener((v, insets) -> { v.setPadding(pad, insets.getSystemWindowInsetTop() + dp(10), pad, insets.getSystemWindowInsetBottom() + dp(16)); return insets; });
        LinearLayout bar = new LinearLayout(this); bar.setGravity(Gravity.CENTER_VERTICAL);
        ImageButton back = imageButton(android.R.drawable.ic_media_previous, R.string.back); back.setOnClickListener(v -> closeRecent()); bar.addView(back, new LinearLayout.LayoutParams(dp(56), dp(56)));
        TextView heading = label(getString(R.string.recent_books), 25, true); heading.setPadding(dp(8), 0, 0, 0); if (Build.VERSION.SDK_INT >= 28) heading.setAccessibilityHeading(true); bar.addView(heading, new LinearLayout.LayoutParams(0, -2, 1)); page.addView(bar);
        page.addView(tabRow(new int[]{R.string.documents_section, R.string.pages_section},
            PAGES_LIST.equals(recentTab) ? 1 : 0,
            index -> { recentTab = index == 1 ? PAGES_LIST : DOCUMENTS_LIST; showRecent(); }),
            new LinearLayout.LayoutParams(-1, -2));
        LinearLayout list = new LinearLayout(this); list.setOrientation(LinearLayout.VERTICAL); ScrollView scrolling = new ScrollView(this); scrolling.addView(list); page.addView(scrolling, new LinearLayout.LayoutParams(-1, 0, 1));
        if (!addRecentSection(list, recentTab, PAGES_LIST.equals(recentTab) ? R.string.remove_page : R.string.remove_book)) {
            TextView empty = label(getString(R.string.no_recent), 19, false); empty.setPadding(0, dp(24), 0, dp(24)); list.addView(empty);
            // Nothing left where the entry stood, so the reader is put on the line that says so.
            if (pendingListRow >= 0) listFocusTarget = empty;
        }
        pendingListRow = -1;
        setContentView(page); page.requestApplyInsets(); focusAfterBuild(heading);
    }
    private boolean addRecentSection(LinearLayout list, String which, int removeResource) {
        JSONArray recent;
        try { recent = new JSONArray(documents().getString(which, "[]")); } catch (JSONException e) { return false; }
        int count = Math.min(RECENT_LIMIT, recent.length());
        if (count == 0) return false;
        boolean pages = PAGES_LIST.equals(which);
        java.util.List<View> entries = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            JSONObject item = recent.optJSONObject(i); if (item == null) continue;
            String itemUri = item.optString("uri"), name = item.optString("name");
            LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(0, dp(6), 0, dp(6));
            Button open = listRowButton(name, 19);
            open.setOnClickListener(v -> { if (pages) openRecentPage(itemUri); else openRecent(itemUri); });
            ImageButton remove = imageButton(android.R.drawable.ic_menu_delete, R.string.delete); remove.setContentDescription(getString(removeResource, name));
            final int position = entries.size();
            remove.setOnClickListener(v -> removeRecent(which, itemUri, position));
            entries.add(open);
            row.addView(open, new LinearLayout.LayoutParams(0, -2, 1));
            LinearLayout.LayoutParams removeParams = new LinearLayout.LayoutParams(dp(56), dp(56)); removeParams.setMargins(dp(10), 0, 0, 0);
            row.addView(remove, removeParams); list.addView(row);
        }
        if (pendingListRow >= 0 && !entries.isEmpty())
            listFocusTarget = entries.get(Math.min(pendingListRow, entries.size() - 1));
        return true;
    }
    // A saved page is an address and not a file, so it is fetched again. What is stored is where it was, not
    // what it said.
    // Leaves whatever subpage is open and puts the reader back on the screen, without touching the document.
    private void returnToReaderScreen() {
        if (!showingRecent) return;
        Runnable action = subpageCloseAction; subpageCloseAction = null; if (action != null) action.run();
        showingRecent = false; buildUi(); if (reader != null) reader.setListener(this);
    }
    private void openRecentPage(String address) {
        if (loading) return;
        Runnable action = subpageCloseAction; subpageCloseAction = null; if (action != null) action.run();
        buildUi(); if (reader != null) reader.setListener(this);
        // The same rule as a book chosen from the list: the reading carries on only if opening the list is
        // what stopped it. Choosing from a list is not the same as being handed something from outside.
        pausedAutomaticallyOutsideReader = false;
        loadArticle(address, true);
    }
    private void showSettingsPage(int headingResource, View content, Runnable applyAction, Runnable closeAction) {
        showSettingsPage(headingResource, content, null, applyAction, closeAction);
    }
    // A view passed as bottomExtra sits outside the scrolling area, directly on top of Apply. Inside the
    // scroll view it would float wherever the content happens to end, leaving a gap above the button.
    private void showSettingsPage(int headingResource, View content, View bottomExtra, Runnable applyAction, Runnable closeAction) {
        showSettingsPage(headingResource, content, bottomExtra, applyAction, closeAction, null);
    }
    private void showSettingsPage(int headingResource, View content, View bottomExtra, Runnable applyAction, Runnable closeAction, View tabs) {
        setTitle(getString(headingResource));
        showingRecent = true; subpageCloseAction = closeAction; int pad = dp(16);
        LinearLayout page = new LinearLayout(this); page.setOrientation(LinearLayout.VERTICAL); page.setPadding(pad, dp(10), pad, dp(16)); page.setBackgroundColor(appColor(R.color.window_bg));
        page.setOnApplyWindowInsetsListener((v, insets) -> { v.setPadding(pad, insets.getSystemWindowInsetTop() + dp(10), pad, insets.getSystemWindowInsetBottom() + dp(16)); return insets; });
        LinearLayout bar = new LinearLayout(this); bar.setGravity(Gravity.CENTER_VERTICAL); ImageButton back = imageButton(android.R.drawable.ic_media_previous, R.string.back); back.setOnClickListener(v -> closeRecent()); bar.addView(back, new LinearLayout.LayoutParams(dp(56), dp(56)));
        TextView heading = label(getString(headingResource), 25, true); heading.setPadding(dp(8), 0, 0, 0); if (Build.VERSION.SDK_INT >= 28) heading.setAccessibilityHeading(true); bar.addView(heading, new LinearLayout.LayoutParams(0, -2, 1)); page.addView(bar);
        // The tabs sit directly under the heading, above everything the page holds, so they are the first
        // thing reached after the title and it is clear that they govern the whole page and not one field.
        if (tabs != null) page.addView(tabs, new LinearLayout.LayoutParams(-1, -2));
        ScrollView scrolling = new ScrollView(this); scrolling.addView(content); page.addView(scrolling, new LinearLayout.LayoutParams(-1, 0, 1));
        if (bottomExtra != null) page.addView(bottomExtra, new LinearLayout.LayoutParams(-1, dp(58)));
        // A page that only lists things has nothing to apply; Back is the only way out of it.
        if (applyAction != null) { Button apply = button(getString(R.string.apply)); apply.setOnClickListener(v -> applyAction.run()); page.addView(apply, new LinearLayout.LayoutParams(-1, dp(58))); }
        appRoot = page; setContentView(page); page.requestApplyInsets(); focusAfterBuild(heading);
    }
    // "Remove the book" is taken at its word: the row goes, and with it everything the app knew about that
    // book - the place it was left at, its bookmarks and the permission to open the file at all. Otherwise a
    // book removed and opened again would come back at the percentage it was left at, which is the opposite
    // of what removing it looked like. If it is the book currently open, it is closed as well; leaving it
    // loaded would only write its position back on the next sentence.
    private void removeRecent(String which, String itemUri, int row) {
        try { JSONArray old = new JSONArray(documents().getString(which, "[]")), fresh = new JSONArray(); for (int i = 0; i < old.length(); i++) if (!itemUri.equals(old.getJSONObject(i).optString("uri"))) fresh.put(old.getJSONObject(i)); documents().edit().putString(which, fresh.toString()).apply(); }
        catch (JSONException e) { toast(getString(R.string.no_recent)); return; }
        forgetBook(itemUri);
        pendingListRow = row;
        showRecent();
    }
    private void forgetBook(String itemUri) {
        if (itemUri.equals(currentUri)) clearCurrentDocument();
        if (itemUri.equals(documents().getString("last_uri", ""))) documents().edit().remove("last_uri").apply();
        if (itemUri.equals(documents().getString("last_page_uri", ""))) forgetCachedPage();
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
        // Picking a book out of the list is asking for it to be read, not merely to be opened.
        resumeAfterFilePickerLoad = true; pausedAutomaticallyOutsideReader = false;
        loadUri(Uri.parse(itemUri), true);
    }
    private void closeRecent() {
        if (!showingRecent || loading) return;
        Runnable action = subpageCloseAction; subpageCloseAction = null; if (action != null) action.run();
        // Returning from a settings page leaves open whatever was open, without reading and decoding the
        // whole file from storage again. There is no need to ask whether it is still in a list: removing a
        // book from the list already closes it if it is the one being read. Asking was what threw away a
        // web page, and then a shared passage of text, neither of which is in the list of documents.
        if (!currentUri.isEmpty() && reader != null && reader.getCount() > 0) {
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
                    loadedText = selected.text; fromPlainTextFile = selected.plainTextFile; fromWeb = false; fromWebPage = false;
                    currentUri = selected.uri; currentName = selected.name;
                    documents().edit().putString("last_uri", currentUri).apply();
                    forgetCachedPage();
                    if (reader == null) pendingText = selected.text; else finishLoad(selected.text);
                }
                buildUi(); if (reader != null) reader.setListener(this); returnToReader();
            });
        });
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
            byte[] bytes = readLimited(uri);
            String kind = DocumentText.kindOf(fileName);
            if (kind.isEmpty()) kind = DocumentText.kindOfContent(bytes);
            if (kind.isEmpty()) return null;
            boolean wrapped = "zip".equals(kind);
            if (wrapped) {
                DocumentText.Entry inner = DocumentText.singleDocument(bytes);
                if (inner == null) return null;
                fileName = inner.name; kind = inner.kind; bytes = inner.bytes;
            }
            boolean plain = "txt".equals(kind) && !wrapped;
            String loaded = ("txt".equals(kind) ? decode(bytes) : DocumentText.extract(kind, bytes)).replace("\r\n", "\n").replace('\r', '\n');
            if (loaded.trim().isEmpty()) return null;
            String savedName = item.optString("name");
            return new RecentDocument(uriValue, savedName.isEmpty() ? withoutExtension(fileName) : savedName, loaded, plain);
        } catch (Exception ignored) { return null; }
    }
    // What was being read when a web page or a shared passage was open. Neither is a file on the phone, so
    // neither survives the app being killed the way a book does - the book is still on the storage and is
    // simply opened again, while a page exists only as an address. Fetching it afresh at every start would
    // cost the network, could fail, and with Start a web page from the beginning switched on would land the
    // reader at the top of it anyway. So the finished text is kept here instead, and read back from the disk.
    //
    // Coming back this way is not the same as opening a page. Nothing was chosen; the app is putting back
    // what was interrupted, so the place it was left at is honoured and the from-the-beginning rule is not.
    private static final String PAGE_CACHE = "last_page.txt";
    private static final String LAST_KIND = "last_kind";
    private boolean restoringPage;

    private void rememberPage(String text, boolean webPage) {
        documents().edit().putString(LAST_KIND, "page").putString("last_page_uri", currentUri)
            .putString("last_page_title", currentName).putBoolean("last_page_web", webPage).apply();
        io.execute(() -> {
            try (OutputStream out = openFileOutput(PAGE_CACHE, MODE_PRIVATE)) { out.write(text.getBytes(StandardCharsets.UTF_8)); }
            catch (Exception ignored) {}
        });
    }
    private void forgetCachedPage() {
        documents().edit().remove(LAST_KIND).remove("last_page_uri").remove("last_page_title").remove("last_page_web").apply();
        try { deleteFile(PAGE_CACHE); } catch (Exception ignored) {}
    }
    // Put back on the screen without a word to the network. Anything wrong with what was kept - missing,
    // empty, unreadable - and the app falls quietly through to the last book, exactly as it did before.
    private boolean restoreLastPage() {
        if (!"page".equals(documents().getString(LAST_KIND, ""))) return false;
        String text = readCachedPage();
        if (text == null || text.trim().isEmpty()) { forgetCachedPage(); return false; }
        loadedText = text; fromPlainTextFile = false; fromWeb = true;
        fromWebPage = documents().getBoolean("last_page_web", true);
        currentUri = documents().getString("last_page_uri", "");
        currentName = documents().getString("last_page_title", getString(R.string.web_page));
        title.setText(currentName); title.setVisibility(View.VISIBLE); title.setPadding(0, 0, 0, dp(8));
        restoringPage = true;
        if (reader == null) pendingText = text; else finishLoad(text);
        return true;
    }
    private String readCachedPage() {
        try (InputStream in = openFileInput(PAGE_CACHE); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int total = 0, n;
            while ((n = in.read(buffer)) != -1) { total += n; if (total > MAX_BYTES) return null; out.write(buffer, 0, n); }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) { return null; }
    }
    private void clearCurrentDocument() {
        cancelAutomaticResume(true); resumeAfterFilePickerLoad = false; pendingText = null; loadedText = null; fromPlainTextFile = false; fromWeb = false; fromWebPage = false; currentUri = ""; currentName = "";
        documents().edit().remove("last_uri").apply();
        forgetCachedPage();
        if (reader != null) reader.clearDocument();
    }
    @android.annotation.SuppressLint("GestureBackNavigation")
    @Override public void onBackPressed() { if (showingRecent) closeRecent(); else leaveReader(); }
    // Back out of the reader. By default this behaves like every other player: the app steps aside and the
    // reading carries on, so a stray press costs nothing and the headset button brings it straight back. The
    // option turns Back into a full stop instead, for someone who would rather have one button that ends
    // everything than a book still talking from a screen they have left.
    // Home and the app switcher end the app on the same terms as Back, when the option asks for it. This is
    // the only hook Android gives for "the user chose to leave"; it does not fire when the app itself opens
    // another screen, but the file picker is guarded anyway, because an app that shuts itself down while the
    // reader is choosing a book would be a fine way to lose a book.
    @Override protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (leavingForResult || showingRecent || loading) return;
        if (!getSettings().getBoolean("close_on_back", false)) return;
        endEverything();
    }
    private void leaveReader() {
        if (getSettings().getBoolean("close_on_back", false)) { endEverything(); return; }
        // Back steps the app aside; it deliberately does not end the screen.
        //
        // Finishing it was the quiet fault behind a book that went on reading after the app had been cleared
        // away. What was left in Recents was a task with a reading service behind it and no screen at all -
        // and with no screen there is nothing left to notice a later Clear from Recents. The only thing that
        // could have noticed is a callback the system does not promise to send. Kept alive and merely put
        // behind, the screen is still there to see the task go, and to take the reading with it.
        moveTaskToBack(true);
    }
    // The one place the app ends itself, so Back, Home and the app switcher all leave the same nothing
    // behind. The service is told first, while it can still be reached; then the binding is given up,
    // because a service that something is still bound to cannot be destroyed and the binding is the last
    // thing holding it; only then does the activity go.
    private void endEverything() {
        keepReadingAfterFinish = false;
        if (reader != null) reader.stopEverything();
        releaseBinding();
        finishAndRemoveTask();
    }
    private void releaseBinding() {
        if (!bindRequested) return;
        bindRequested = false;
        if (reader != null) reader.setListener(null);
        try { unbindService(connection); } catch (IllegalArgumentException ignored) {}
        reader = null; bound = false;
    }
    // Set before the screen is rebuilt for a theme or a language, so that the check in onDestroy cannot
    // mistake a rebuild for the app being cleared out of Recents. Back no longer needs it: it leaves the
    // screen standing rather than ending it.
    private boolean keepReadingAfterFinish;
    @Override protected void onSaveInstanceState(Bundle outState) { outState.putBoolean(STATE_RESUME_AFTER_RECREATE, resumeAfterRecreate); super.onSaveInstanceState(outState); }
    private void toast(String value) { Toast.makeText(this, value == null ? getString(R.string.open_failed) : value, Toast.LENGTH_LONG).show(); }
    @Override protected void onDestroy() {
        // Cleared out of Recents. onTaskRemoved in the service is the proper signal for that and usually
        // arrives, but it is not delivered on every phone, so the activity checks for itself. A finish that
        // is only the app being rebuilt for a theme change, or a Back that is meant to leave the reading
        // running, must not end anything.
        boolean cleared = isFinishing() && !isChangingConfigurations() && !keepReadingAfterFinish;
        if (cleared && reader != null) reader.stopEverything();
        destroyed = true; stopFastSeek(false); automaticResumeHandler.removeCallbacksAndMessages(null); seekHandler.removeCallbacksAndMessages(null); previewHandler.removeCallbacksAndMessages(null); sleepRowHandler.removeCallbacksAndMessages(null);
        if (reader != null) reader.setListener(null);
        if (bindRequested) { bindRequested = false; try { unbindService(connection); } catch (IllegalArgumentException ignored) {} }
        io.shutdownNow(); super.onDestroy();
    }
}
