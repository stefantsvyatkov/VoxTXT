package bg.stefantsvyatkov.voxtxt;

import android.app.*;
import android.content.*;
import android.os.*;
import android.speech.tts.*;
import android.media.*;
import android.media.session.*;
import android.view.KeyEvent;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.BreakIterator;
import java.util.*;

public class ReaderService extends Service implements TextToSpeech.OnInitListener {
    public static final String ACTION_PLAY = "bg.stefantsvyatkov.voxtxt.PLAY";
    public static final String ACTION_PAUSE = "bg.stefantsvyatkov.voxtxt.PAUSE";
    public static final String ACTION_PREVIOUS = "bg.stefantsvyatkov.voxtxt.PREVIOUS";
    public static final String ACTION_NEXT = "bg.stefantsvyatkov.voxtxt.NEXT";
    public static final String ACTION_STOP = "bg.stefantsvyatkov.voxtxt.STOP";
    private static final String CHANNEL = "reader_playback";
    private static final int NOTIFICATION_ID = 42;
    private static final String SLEEP_STATE = "sleep_rewind_state";
    private static final String PREVIEW_UTTERANCE = "voice-preview";
    private static final String END_UTTERANCE = "end-of-text";
    private static final long PREVIEW_RETRY_MS = 700L;
    private static final String PARAM_VOICE_NAME = "voiceName";

    public interface Listener { void onPlaybackState(int index, int count, boolean playing); void onPlaybackError(String message); void onPreviewState(boolean speaking); }
    public class ReaderBinder extends Binder {
        public ReaderService service() { return ReaderService.this; }
    }
    public static class EngineOption {
        public final String name, label;
        EngineOption(String name, String label) { this.name = name; this.label = label; }
    }
    public static class VoiceOption {
        public final String name, label, localeTag;
        VoiceOption(String name, String label, String localeTag) { this.name = name; this.label = label; this.localeTag = localeTag == null ? "" : localeTag; }
    }
    public interface VoicesCallback { void onVoices(List<VoiceOption> voices); }

    private final IBinder binder = new ReaderBinder();
    // Two channels, and the difference between them is who owns what is on them.
    //
    // speechHandler carries only what this app has scheduled: the next sentence after the pause between
    // them, the start left waiting while the seek buttons are being tapped, the delay before a retry.
    // Moving, pausing and seeking cancel all of that, because none of it is wanted any more.
    //
    // handler carries what the engine has told us - that a sentence finished, stopped or failed. Those are
    // reports of things that have already happened, and clearing them wholesale was how a finished sentence
    // could be lost and spoken again.
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Handler speechHandler = new Handler(Looper.getMainLooper());
    private final Handler sleepHandler = new Handler(Looper.getMainLooper());
    private final Handler focusHandler = new Handler(Looper.getMainLooper());
    private final Handler lifecycleHandler = new Handler(Looper.getMainLooper());
    private boolean reachedEnd;
    private final ArrayList<Range> sentences = new ArrayList<>();
    private TextToSpeech tts, previewTts, previewSpeaker;
    private String previewEngine = "", previewVoiceName = "", previewText, activePreviewUtterance = "";
    private int previewRate, previewPitch, previewVolume, previewRetries;
    private long previewSerial;
    private final UtteranceProgressListener previewProgress = new UtteranceProgressListener() {
        @Override public void onStart(String id) {}
        @Override public void onDone(String id) { handler.post(() -> previewFinished(id)); }
        @Override public void onStop(String id, boolean interrupted) { handler.post(() -> previewStopped(id, interrupted)); }
        @Override public void onError(String id) { handler.post(() -> previewFinished(id)); }
    };
    private AudioTrack silentAudioTrack;
    private final ArrayList<EngineOption> allEngines = new ArrayList<>();
    private final Map<String, List<VoiceOption>> voiceCache = new HashMap<>();
    private String activeEngine = "";
    private boolean pendingPlay;
    private Listener listener;
    private String text = "", title = "Vox TXT", uri = "";
    private int current;
    private boolean ready, playing;
    private long sleepDeadline;
    private static final long SLEEP_FADE_DURATION_MS = 10_000L;
    private static final long SLEEP_FADE_UPDATE_MS = 100L;
    private int volumeBeforeFade = -1, pendingVolumeRestore = -1;
    private int sleepStartSentence = -1, sleepFadeStartSentence = -1, completedSleepMinutes;
    private String positionKeyUri = "", positionKey = "";
    private Voice cachedVoice;
    private String cachedVoiceName = "";
    private boolean sleepRewindAvailable, captureSleepStartOnPlay;
    private int transientRetries, engineRestarts;
    private boolean pausedByFocusLoss;
    private long utteranceSerial;
    private String activeUtterance = "";
    private long utteranceStartedAt;
    // A tap on Previous or Next stops the engine and starts the next sentence. Tap again quickly and the
    // sentence just started is stopped in turn - so the reading is asked to start something it will not
    // finish, over and over. Waiting out the taps means the engine is asked once, for the sentence actually
    // landed on. Short enough not to be felt on a single tap.
    private static final long SEEK_SETTLE_MS = 180L;
    private MediaSession mediaSession;
    private AudioManager audioManager;
    private AudioFocusRequest audioFocusRequest;
    private boolean hasAudioFocus;
    private long suppressExternalPlayUntil;
    private final Set<Integer> knownAudioOutputs = new HashSet<>();
    private final BroadcastReceiver becomingNoisyReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction())) pause();
        }
    };
    private final AudioDeviceCallback audioDeviceCallback = new AudioDeviceCallback() {
        @Override public void onAudioDevicesAdded(AudioDeviceInfo[] addedDevices) {
            boolean newAccessory = false;
            for (AudioDeviceInfo device : addedDevices) {
                if (!knownAudioOutputs.add(device.getId())) continue;
                if (isExternalAudioOutput(device)) newAccessory = true;
            }
            if (newAccessory && getSharedPreferences("reader_settings", MODE_PRIVATE).getBoolean("prevent_device_autoplay", true)) suppressExternalPlayUntil = SystemClock.elapsedRealtime() + 3000L;
        }
        @Override public void onAudioDevicesRemoved(AudioDeviceInfo[] removedDevices) {
            for (AudioDeviceInfo device : removedDevices) knownAudioOutputs.remove(device.getId());
        }
    };
    // A phone call, a navigation prompt or an assistant takes the sound away for a moment and gives it back.
    // Only that short interruption resumes on its own, and only if the reading was running when it started.
    // A permanent loss - another player taking over - stays paused, because the user moved on to something
    // else. The focus request itself is kept during a short loss; abandoning it would mean never hearing
    // that the sound came back.
    private final AudioManager.OnAudioFocusChangeListener focusListener = change -> {
        if (change == AudioManager.AUDIOFOCUS_LOSS) { pause(); return; }
        if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) { boolean wasPlaying = playing; pause(false); pausedByFocusLoss = wasPlaying; return; }
        if (change == AudioManager.AUDIOFOCUS_GAIN && pausedByFocusLoss) { pausedByFocusLoss = false; play(); }
    };

    @Override public void onCreate() {
        super.onCreate(); restoreSleepRewindState(); createChannel(); createMediaSession(); restoreVolumeAfterCrash(); registerAudioRouteListeners(); initializeTts(getSharedPreferences("reader_settings", MODE_PRIVATE).getString("engine", ""));
    }
    @Override public IBinder onBind(Intent intent) { return binder; }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getAction() != null) {
            switch (intent.getAction()) {
                case ACTION_PLAY: play(); break;
                case ACTION_PAUSE: pause(); break;
                case ACTION_PREVIOUS: move(-1); break;
                case ACTION_NEXT: move(1); break;
                case ACTION_STOP: pause(); stopForeground(true); stopSelf(); break;
                case Intent.ACTION_MEDIA_BUTTON:
                    // Started with startForegroundService(), so startForeground() must follow within a few
                    // seconds even when the button turns out to be a no-op (no document, TTS not ready yet).
                    startForeground(NOTIFICATION_ID, notification());
                    KeyEvent event = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
                    if (event != null && mediaSession != null) mediaSession.getController().dispatchMediaButtonEvent(event);
                    lifecycleHandler.removeCallbacksAndMessages(null);
                    lifecycleHandler.postDelayed(this::stopIfIdle, 2000L);
                    break;
            }
        }
        return START_NOT_STICKY;
    }
    private void stopIfIdle() {
        if (playing || pendingPlay || !sentences.isEmpty()) return;
        stopForeground(true); stopSelf();
    }
    // Swiping the app away, or Close all in Recents, is a request to be rid of it, and it is taken literally:
    // the reading stops, the notification disappears and the service ends, whether or not it was reading at
    // that moment. Closing an app from the very place meant for closing apps should leave nothing behind.
    // The position is saved on the way out, so the book opens again where it was left.
    @Override public void onTaskRemoved(Intent rootIntent) {
        pause(); stopForeground(true); stopSelf();
        super.onTaskRemoved(rootIntent);
    }

    public void setListener(Listener value) { listener = value; notifyState(); }
    public String getText() { return text; }
    public int getCurrent() { return current; }
    public int getCount() { return sentences.size(); }
    public boolean isPlaying() { return playing; }
    public boolean isReady() { return ready; }
    public MediaController getMediaController() { return mediaSession == null ? null : mediaSession.getController(); }
    public boolean isSleepRewindAvailable() { return sleepRewindAvailable; }
    public long sleepRemainingMillis() { return sleepDeadline <= 0 ? 0 : Math.max(0, sleepDeadline - SystemClock.elapsedRealtime()); }
    public int getCompletedSleepMinutes() { return completedSleepMinutes; }
    public synchronized List<EngineOption> getEngineOptions() { return new ArrayList<>(allEngines); }
    public void loadVoiceOptions(String engine, VoicesCallback callback) {
        String requested = engine == null ? "" : engine;
        List<VoiceOption> cached = voiceCache.get(requested); if (cached != null) { callback.onVoices(new ArrayList<>(cached)); return; }
        if (ready && requested.equals(activeEngine)) { List<VoiceOption> result = filteredVoices(tts); voiceCache.put(requested, result); callback.onVoices(new ArrayList<>(result)); return; }
        final TextToSpeech[] probe = new TextToSpeech[1];
        TextToSpeech.OnInitListener listener = status -> {
            TextToSpeech instance = probe[0]; List<VoiceOption> result = status == TextToSpeech.SUCCESS && instance != null ? filteredVoices(instance) : Collections.emptyList();
            if (instance != null) instance.shutdown(); voiceCache.put(requested, result); handler.post(() -> callback.onVoices(new ArrayList<>(result)));
        };
        probe[0] = requested.isEmpty() ? new TextToSpeech(this, listener) : new TextToSpeech(this, listener, requested);
    }

    // A book and a web page each have their own engine, voice, speed and pitch. Which of the two is in use
    // is decided by what was opened, and is switched here rather than being asked for every sentence.
    public void load(String newUri, String newTitle, String newText, int position, boolean fromWeb) {
        pause(); reachedEnd = false; uri = newUri; title = newTitle; text = newText; current = Math.max(0, position);
        useProfile(fromWeb ? WEB_PROFILE : "");
        if (sleepRewindAvailable && !newUri.equals(getSharedPreferences(SLEEP_STATE, MODE_PRIVATE).getString("uri", ""))) clearSleepRewindState();
        split(); current = Math.min(current, Math.max(0, sentences.size() - 1));
        savePosition(); notifyState();
    }
    public void clearDocument() {
        pause(); reachedEnd = false; uri = ""; title = ""; text = ""; current = 0; sentences.clear();
        notifyState(); stopForeground(true);
    }
    public void play() {
        if (sleepRewindAvailable) { clearSleepRewindState(); notifyState(); }
        if (!ready) { pendingPlay = true; return; }
        if (sentences.isEmpty()) return;
        // The last sentence has already been read out; playing again would simply repeat it.
        if (reachedEnd) { error(getString(R.string.no_more_text)); return; }
        if (captureSleepStartOnPlay) { sleepStartSentence = current; captureSleepStartOnPlay = false; }
        // Anything already waiting to be spoken is dropped first. Moving a sentence leaves a start waiting
        // a moment, so pressing Play inside that moment used to speak the sentence, then speak it again
        // when the waiting start came round.
        speechHandler.removeCallbacksAndMessages(null);
        applySettings(); transientRetries = 0; engineRestarts = 0; pausedByFocusLoss = false; playing = true;
        if (!requestAudioFocus()) { playing = false; notifyState(); updateNotification(); return; }
        startSilentPlayback(); promoteMediaSession(); updateMediaSession(); startService(new Intent(this, ReaderService.class)); startForeground(NOTIFICATION_ID, notification());
        speakCurrent();
    }
    public void pause() { pausedByFocusLoss = false; pause(true); }
    private void pause(boolean releaseFocus) {
        pendingPlay = false; playing = false; activeUtterance = ""; utteranceSerial++; speechHandler.removeCallbacksAndMessages(null); if (tts != null) tts.stop(); stopSilentPlayback();
        restoreVolumeAfterFade(); if (releaseFocus) abandonAudioFocus(); savePosition(); if (!sentences.isEmpty()) updateNotification(); notifyState();
    }
    public void move(int delta) {
        // Moving cancels whatever the previous step left pending. Without this, stopping a sentence number
        // that is still being announced counts as "finished" and starts the reading in the middle of a new
        boolean resume = playing; reachedEnd = false; playing = false; activeUtterance = ""; utteranceSerial++; speechHandler.removeCallbacksAndMessages(null); if (tts != null) tts.stop();
        if (!sentences.isEmpty()) current = Math.max(0, Math.min(current + delta, sentences.size() - 1));
        // Stopping the engine and starting it again is how a move is carried out, but it is not what is
        // happening as far as anyone watching is concerned: the reading was running before the move and
        // is running after it. Reporting the momentary stop turned the Play button into a flashing light
        // and did the same to the notification, once per step of a fast seek.
        playing = resume;
        savePosition(); notifyState();
        if (resume) speechHandler.postDelayed(this::speakCurrent, SEEK_SETTLE_MS); else updateNotification();
    }
    public void seekTo(int index) {
        reachedEnd = false; playing = false; activeUtterance = ""; utteranceSerial++; speechHandler.removeCallbacksAndMessages(null); if (tts != null) tts.stop(); stopSilentPlayback();
        if (!sentences.isEmpty()) current = Math.max(0, Math.min(index, sentences.size() - 1));
        savePosition(); notifyState(); updateNotification();
    }
    public void updateSettings(boolean playAfterUpdate) {
        String wantedEngine = getSharedPreferences("reader_settings", MODE_PRIVATE).getString(setting("engine"), "");
        pause();
        if (!wantedEngine.equals(activeEngine)) { pendingPlay = playAfterUpdate; initializeTts(wantedEngine); }
        else { if (ready) applySettings(); if (playAfterUpdate) play(); }
    }
    public void setSleepMinutes(int minutes) {
        sleepHandler.removeCallbacksAndMessages(null); restoreVolumeAfterFade(); clearSleepRewindState(); sleepFadeStartSentence = -1; captureSleepStartOnPlay = minutes > 0; sleepDeadline = minutes <= 0 ? 0 : SystemClock.elapsedRealtime() + minutes * 60_000L;
        if (sleepDeadline > 0) sleepHandler.postDelayed(this::beginSleepFade, Math.max(0, minutes * 60_000L - SLEEP_FADE_DURATION_MS));
        if (minutes > 0) completedSleepMinutes = minutes;
        notifyState();
    }
    public void rewindCompletedSleepTimer() {
        if (!sleepRewindAvailable || sleepStartSentence < 0) return;
        int target = sleepStartSentence; clearSleepRewindState();
        seekTo(target); play();
    }
    public Range currentRange() { return sentences.isEmpty() ? new Range(0, 0) : sentences.get(current); }

    // Finds the next sentence containing the phrase, continuing past the end of the book back to the start.
    public int findSentence(String query, int fromSentence) {
        String needle = query == null ? "" : query.trim();
        if (needle.isEmpty() || sentences.isEmpty()) return -1;
        // Compared in place. Lowercasing sentence after sentence would copy the whole book through memory
        // before reporting that a word is not in it.
        int from = sentences.get(Math.max(0, Math.min(fromSentence, sentences.size() - 1))).end;
        int found = indexOfIgnoreCase(needle, from);
        if (found < 0) found = indexOfIgnoreCase(needle, 0);
        return found < 0 ? -1 : sentenceContaining(found);
    }
    // The same search the other way round: the nearest occurrence before the current sentence, continuing
    // past the beginning of the book back to its end.
    public int findPreviousSentence(String query, int fromSentence) {
        String needle = query == null ? "" : query.trim();
        if (needle.isEmpty() || sentences.isEmpty()) return -1;
        int before = sentences.get(Math.max(0, Math.min(fromSentence, sentences.size() - 1))).start;
        int found = lastIndexOfIgnoreCase(needle, before - 1);
        if (found < 0) found = lastIndexOfIgnoreCase(needle, text.length());
        return found < 0 ? -1 : sentenceContaining(found);
    }
    private int indexOfIgnoreCase(String needle, int from) {
        int last = text.length() - needle.length();
        for (int i = Math.max(0, from); i <= last; i++) if (text.regionMatches(true, i, needle, 0, needle.length())) return i;
        return -1;
    }
    private int lastIndexOfIgnoreCase(String needle, int from) {
        for (int i = Math.min(from, text.length() - needle.length()); i >= 0; i--) if (text.regionMatches(true, i, needle, 0, needle.length())) return i;
        return -1;
    }
    private int sentenceContaining(int offset) {
        int low = 0, high = sentences.size() - 1, found = 0;
        while (low <= high) {
            int middle = (low + high) / 2;
            if (sentences.get(middle).start <= offset) { found = middle; low = middle + 1; } else high = middle - 1;
        }
        return found;
    }
    public String sentenceText(int index) {
        if (index < 0 || index >= sentences.size()) return "";
        Range range = sentences.get(index);
        return text.substring(range.start, range.end).trim();
    }

    private void split() {
        sentences.clear(); BreakIterator it = BreakIterator.getSentenceInstance(Locale.getDefault()); it.setText(text);
        int start = it.first();
        for (int end = it.next(); end != BreakIterator.DONE; start = end, end = it.next())
            if (!text.substring(start, end).trim().isEmpty()) sentences.add(new Range(start, end));
        if (sentences.isEmpty() && !text.trim().isEmpty()) sentences.add(new Range(0, text.length()));
    }
    // Only what a single sentence needs. Speech rate, pitch and the voice are not set again here: Android
    // keeps them and sends them along with every request anyway, so re-applying them - and scanning the
    // engine's entire voice list to do it - was work that changed nothing in what is heard.
    private void speakCurrent() {
        if (!playing || current >= sentences.size()) { pause(); return; }
        if (!ready || tts == null) { pendingPlay = true; return; }
        Range range = sentences.get(current);
        activeUtterance = "sentence-" + current + "-" + (++utteranceSerial); String utterance = activeUtterance;
        utteranceStartedAt = SystemClock.elapsedRealtime();
        android.content.SharedPreferences p = getSharedPreferences("reader_settings", MODE_PRIVATE);
        Bundle parameters = new Bundle(); parameters.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, p.getInt(setting("volume_percent"), 50) / 100f);
        addVoiceParam(parameters, p.getString(voicePreferenceKey(profile, activeEngine), ""));
        int result = tts.speak(text.substring(range.start, range.end).trim(), TextToSpeech.QUEUE_FLUSH, parameters, utterance);
        if (result == TextToSpeech.ERROR) { pause(); error(getString(R.string.tts_error)); }
        notifyState(); updateNotification();
    }
    private void finishCurrentSentence(String utterance) {
        if (!playing || !utterance.equals(activeUtterance)) return;
        transientRetries = 0; engineRestarts = 0; activeUtterance = ""; current++; savePosition();
        if (current >= sentences.size()) { current = Math.max(0, sentences.size() - 1); reachedEnd = true; announceEnd(); return; }
        int delay = getSharedPreferences("reader_settings", MODE_PRIVATE).getInt(setting("sentence_pause"), 0);
        speechHandler.postDelayed(ReaderService.this::speakCurrent, delay);
    }
    // A book ends and says so; there is a lot of it behind you and it is worth being told where you are. An
    // article is three minutes long and its end is obvious from the fact that the voice stopped - announcing
    // it there is the app talking about itself. Pressing Play past the end still says there is no more text,
    // in both cases.
    private void announceEnd() {
        savePosition(); notifyState();
        if (WEB_PROFILE.equals(profile)) { pause(); return; }
        activeUtterance = END_UTTERANCE;
        android.content.SharedPreferences p = getSharedPreferences("reader_settings", MODE_PRIVATE);
        Bundle parameters = new Bundle(); parameters.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, p.getInt(setting("volume_percent"), 50) / 100f);
        addVoiceParam(parameters, p.getString(voicePreferenceKey(profile, activeEngine), ""));
        if (tts == null || tts.speak(getString(R.string.end_of_text), TextToSpeech.QUEUE_FLUSH, parameters, END_UTTERANCE) == TextToSpeech.ERROR) pause();
    }
    private void retryCurrentSentence(String utterance) {
        if (!utterance.equals(activeUtterance)) return;
        activeUtterance = "";
        if (!playing) return;
        if (transientRetries++ < 3) { speechHandler.postDelayed(ReaderService.this::speakCurrent, 1200); return; }
        // The engine lives in its own process and the system can kill it. Then the connection this app holds
        // is dead and asking it to speak again will never work, however many times it is asked - it has to
        // be built anew. One rebuild per failure, and only then does the reading give up.
        if (engineRestarts++ < 1) { transientRetries = 0; pendingPlay = true; initializeTts(activeEngine); return; }
        pause(); error(getString(R.string.tts_error));
    }
    private void startSilentPlayback() {
        if (silentAudioTrack != null) return;
        int sampleRate = 8000, samples = sampleRate;
        AudioFormat format = new AudioFormat.Builder().setSampleRate(sampleRate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build();
        AudioTrack track = new AudioTrack.Builder().setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()).setAudioFormat(format).setBufferSizeInBytes(samples * 2).setTransferMode(AudioTrack.MODE_STATIC).build();
        short[] silence = new short[samples]; if (track.write(silence, 0, silence.length) < 0) { track.release(); return; }
        track.setLoopPoints(0, samples, -1); track.setVolume(1f); silentAudioTrack = track; track.play();
    }
    private void stopSilentPlayback() { AudioTrack track = silentAudioTrack; silentAudioTrack = null; if (track != null) { try { track.pause(); track.flush(); } catch (IllegalStateException ignored) {} track.release(); } }
    private void refreshSilentPlaybackPriority() {
        AudioTrack track = silentAudioTrack; if (!playing || track == null) return;
        try { track.pause(); track.setPlaybackHeadPosition(0); track.play(); promoteMediaSession(); updateMediaSession(); }
        catch (IllegalStateException ignored) { stopSilentPlayback(); startSilentPlayback(); promoteMediaSession(); updateMediaSession(); }
    }
    private void applySettings() {
        android.content.SharedPreferences p = getSharedPreferences("reader_settings", MODE_PRIVATE);
        tts.setSpeechRate(speechRate(p.getInt(setting("rate_percent"), 20))); tts.setPitch(speechPitch(p.getInt(setting("pitch_percent"), 20)));
        applyVoice(tts, p.getString(voicePreferenceKey(profile, activeEngine), ""));
    }
    // setVoice() is only half the story. Android forwards the chosen voice to the engine only when the
    // engine answered its loadVoice call with SUCCESS, and the default implementation of that call accepts
    // nothing but names that parse as a language tag - so an engine whose voices are called "Alexander" or
    // "Gergana" never receives the choice and keeps reading with its own default. setLanguage() is not a way
    // out either: it overwrites the stored voice with the default one for that language.
    // What does get through is the speak() parameter bundle, which the framework merges over its own
    // parameters, so the voice is also named there before every sentence. See addVoiceParam().
    private void applyVoice(TextToSpeech engine, String selectedVoice) {
        if (selectedVoice.isEmpty()) { Voice defaultVoice = engine.getDefaultVoice(); if (defaultVoice != null) engine.setVoice(defaultVoice); return; }
        // Finding the voice means asking the engine for its entire list - hundreds of entries on some of
        // them. The answer cannot change while the same voice stays chosen on the same engine, so it is
        // kept and the search happens once instead of on every start of reading.
        if (engine == tts && cachedVoice != null && selectedVoice.equals(cachedVoiceName)) { engine.setVoice(cachedVoice); return; }
        Set<Voice> voices = engine.getVoices();
        if (voices == null) return;
        for (Voice voice : voices) if (selectedVoice.equals(voice.getName())) {
            engine.setVoice(voice);
            if (engine == tts) { cachedVoice = voice; cachedVoiceName = selectedVoice; }
            return;
        }
    }
    // PARAM_VOICE_NAME is the framework's own key for the voice of a single utterance. The constant is not
    // public API, but the key itself is what TextToSpeechService reads out of the bundle, and passing a
    // string in a Bundle is not a restricted call.
    private void addVoiceParam(Bundle parameters, String selectedVoice) {
        if (!selectedVoice.isEmpty()) parameters.putString(PARAM_VOICE_NAME, selectedVoice);
    }

    // Lets the voice page speak a sample before anything is saved. The sample is the sentence the reader is
    // on, so the voice is judged on the actual book instead of a canned phrase in the wrong language.
    public void previewVoice(String engine, String voiceName, int ratePercent, int pitchPercent, int volumePercent, String fallbackSample) {
        // Drop the previous sample first: pausing flushes it, and its callback must not schedule a retry
        // over the one that is about to start.
        previewSpeaker = null; previewText = null; previewRetries = 0; activePreviewUtterance = "";
        pause();
        String spoken = fallbackSample;
        if (!sentences.isEmpty()) {
            Range range = sentences.get(Math.min(current, sentences.size() - 1));
            String sentence = text.substring(range.start, range.end).trim();
            if (!sentence.isEmpty()) spoken = sentence.length() > 240 ? sentence.substring(0, 240) : sentence;
        }
        String wanted = engine == null ? "" : engine;
        if (wanted.equals(activeEngine) && ready && tts != null) { speakPreview(tts, voiceName, ratePercent, pitchPercent, volumePercent, spoken); return; }
        if (previewTts != null && wanted.equals(previewEngine)) { speakPreview(previewTts, voiceName, ratePercent, pitchPercent, volumePercent, spoken); return; }
        stopPreview();
        previewEngine = wanted;
        final String sample = spoken;
        final TextToSpeech[] holder = new TextToSpeech[1];
        TextToSpeech.OnInitListener init = status -> handler.post(() -> {
            if (previewTts != holder[0] || holder[0] == null) return;
            if (status != TextToSpeech.SUCCESS) { error(getString(R.string.tts_unavailable)); stopPreview(); return; }
            holder[0].setOnUtteranceProgressListener(previewProgress);
            speakPreview(holder[0], voiceName, ratePercent, pitchPercent, volumePercent, sample);
        });
        holder[0] = wanted.isEmpty() ? new TextToSpeech(this, init) : new TextToSpeech(this, init, wanted);
        previewTts = holder[0];
    }
    private void speakPreview(TextToSpeech engine, String voiceName, int ratePercent, int pitchPercent, int volumePercent, String spoken) {
        previewSpeaker = engine; previewVoiceName = voiceName == null ? "" : voiceName; previewText = spoken;
        previewRate = ratePercent; previewPitch = pitchPercent; previewVolume = volumePercent;
        engine.setSpeechRate(speechRate(ratePercent)); engine.setPitch(speechPitch(pitchPercent));
        applyVoice(engine, previewVoiceName);
        activePreviewUtterance = PREVIEW_UTTERANCE + "-" + (++previewSerial);
        Bundle parameters = new Bundle(); parameters.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, Math.max(0, Math.min(100, volumePercent)) / 100f);
        addVoiceParam(parameters, previewVoiceName);
        if (engine.speak(spoken, TextToSpeech.QUEUE_FLUSH, parameters, activePreviewUtterance) == TextToSpeech.ERROR) { error(getString(R.string.tts_error)); notifyPreview(false); return; }
        notifyPreview(true);
    }
    private boolean isPreview(String id) { return id != null && id.startsWith(PREVIEW_UTTERANCE); }
    private void previewFinished(String id) {
        if (!id.equals(activePreviewUtterance)) return;
        activePreviewUtterance = ""; previewRetries = 0; notifyPreview(false);
    }
    // A book being read survives the screen reader talking over it because an interrupted sentence is
    // spoken again; the sample gets the same treatment instead of simply disappearing.
    private void previewStopped(String id, boolean interrupted) {
        if (!id.equals(activePreviewUtterance)) return;
        activePreviewUtterance = "";
        if (!interrupted || playing || previewSpeaker == null || previewText == null || previewRetries >= 2) { notifyPreview(false); return; }
        previewRetries++;
        TextToSpeech engine = previewSpeaker; String voice = previewVoiceName, spoken = previewText;
        int ratePercent = previewRate, pitchPercent = previewPitch, volumePercent = previewVolume;
        handler.postDelayed(() -> { if (!playing && previewSpeaker == engine) speakPreview(engine, voice, ratePercent, pitchPercent, volumePercent, spoken); }, PREVIEW_RETRY_MS);
    }
    private void notifyPreview(boolean speaking) { if (listener != null) listener.onPreviewState(speaking); }
    public void stopPreview() {
        previewSpeaker = null; previewText = null; previewRetries = 0; activePreviewUtterance = "";
        if (tts != null) tts.stop();
        TextToSpeech instance = previewTts; previewTts = null; previewEngine = "";
        if (instance != null) { try { instance.stop(); instance.shutdown(); } catch (Exception ignored) {} }
        notifyPreview(false);
    }
    public static final String WEB_PROFILE = "web_";
    private String profile = "";
    private String setting(String name) { return profile + name; }
    private void useProfile(String wanted) {
        if (wanted.equals(profile)) return;
        profile = wanted;
        String wantedEngine = getSharedPreferences("reader_settings", MODE_PRIVATE).getString(setting("engine"), "");
        if (!wantedEngine.equals(activeEngine)) initializeTts(wantedEngine);
        else if (ready) applySettings();
    }
    public static String voicePreferenceKey(String profile, String engine) { return profile + "voice_for_" + (engine == null || engine.isEmpty() ? "system_default" : engine); }
    // The whole voice list is decided here, so there is one place to reason about instead of rules spread
    // between the service and the screen. Engines name and duplicate their voices very differently, so the
    // list is built twice if it has to be: once with the availability filters, and if that leaves nothing,
    // once without them. An engine whose voices all look unavailable is still better shown than hidden.
    private List<VoiceOption> filteredVoices(TextToSpeech source) {
        Set<Voice> voices = source.getVoices(); if (voices == null || voices.isEmpty()) return Collections.emptyList();
        List<VoiceOption> usable = collectVoices(voices, true);
        return usable.isEmpty() ? collectVoices(voices, false) : usable;
    }
    private List<VoiceOption> collectVoices(Set<Voice> voices, boolean onlyAvailable) {
        TreeMap<String, VoiceOption> unique = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        Map<String, Integer> bestPerLanguage = new HashMap<>();
        for (Voice voice : voices) {
            if (voice == null || voice.getName() == null || voice.getName().trim().isEmpty()) continue;
            if (onlyAvailable && !isAvailableOffline(voice)) continue;
            String name = voice.getName(), localeTag = voice.getLocale() == null ? "" : voice.getLocale().toLanguageTag();
            String language = languageOf(localeTag, name);
            Integer best = bestPerLanguage.get(language);
            if (best == null || nameRank(name) < best) bestPerLanguage.put(language, nameRank(name));
            // "bg-bg-x-ifb-local" and "bg-bg-x-ifb-network" are the same voice; keep one entry per variant,
            // preferring the one that does not need the network.
            String key = variantKey(name);
            VoiceOption existing = unique.get(key);
            if (existing == null || (isLocalName(name) && !isLocalName(existing.name))) unique.put(key, new VoiceOption(name, name, localeTag));
        }
        List<VoiceOption> result = new ArrayList<>();
        // Per language only the most precise kind of entry survives. A named voice beats every stand-in for
        // "this engine's default", and among those a regional one beats a bare language code. Nothing is
        // dropped when it is the only thing a language has, so no language can disappear from the list.
        for (VoiceOption option : unique.values())
            if (nameRank(option.name) <= bestPerLanguage.get(languageOf(option.localeTag, option.name))) result.add(option);
        return result;
    }
    private int nameRank(String name) {
        if (isLanguageAlias(name)) return 2;                                            // "bg-BG-language"
        if (!isBareLanguageName(name)) return 0;                                        // "Milena", "bg-bg-x-ifb-local"
        return name.indexOf('-') >= 0 || name.indexOf('_') >= 0 ? 1 : 3;                // "bg-BG" beats "bul"
    }
    private boolean isAvailableOffline(Voice voice) {
        if (voice.isNetworkConnectionRequired()) return false;
        Set<String> features = voice.getFeatures();
        return features == null || !(features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) || features.contains(TextToSpeech.Engine.KEY_FEATURE_NETWORK_SYNTHESIS));
    }
    private String languageOf(String localeTag, String name) {
        String tag = localeTag.isEmpty() ? name : localeTag;
        String language = Locale.forLanguageTag(tag.replace('_', '-')).getLanguage();
        return (language.isEmpty() ? tag : language).toLowerCase(Locale.ROOT);
    }
    private boolean isLanguageAlias(String name) { return name.toLowerCase(Locale.ROOT).matches("^[a-z]{2,3}([-_][a-z]{2,4})?[-_]language$"); }
    private boolean isBareLanguageName(String name) { return name.replace('_', '-').matches("(?i)^[a-z]{2,3}(-[a-z]{2,4})?$"); }
    private boolean isLocalName(String name) { return name.toLowerCase(Locale.ROOT).endsWith("-local"); }
    private String variantKey(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith("-local")) return lower.substring(0, lower.length() - 6);
        if (lower.endsWith("-network")) return lower.substring(0, lower.length() - 8);
        return lower;
    }
    private float speechRate(int sliderPercent) {
        int value = Math.max(0, Math.min(100, sliderPercent));
        return 0.1f + value * 5.9f / 100f;
    }
    private float speechPitch(int sliderPercent) {
        int value = Math.max(0, Math.min(100, sliderPercent));
        return 0.25f + value * 3.75f / 100f;
    }

    private void initializeTts(String engine) {
        ready = false; cachedVoice = null; cachedVoiceName = ""; if (tts != null) { tts.stop(); tts.shutdown(); }
        activeEngine = engine == null ? "" : engine;
        tts = activeEngine.isEmpty() ? new TextToSpeech(this, this) : new TextToSpeech(this, this, activeEngine);
    }

    private void refreshEngines() {
        List<TextToSpeech.EngineInfo> engines = tts.getEngines();
        synchronized (this) {
            allEngines.clear();
            if (engines != null) for (TextToSpeech.EngineInfo info : engines) allEngines.add(new EngineOption(info.name, info.label == null ? info.name : info.label));
        }
    }

    @Override public void onInit(int status) {
        ready = status == TextToSpeech.SUCCESS;
        if (!ready) { pendingPlay = false; error(getString(R.string.tts_unavailable)); notifyState(); stopIfIdle(); return; }
        applySettings(); refreshEngines();
        tts.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String id) { handler.post(() -> { if (id.equals(activeUtterance)) refreshSilentPlaybackPriority(); }); }
            @Override public void onDone(String id) { handler.post(() -> { if (isPreview(id)) previewFinished(id); else if (END_UTTERANCE.equals(id)) pause(); else finishCurrentSentence(id); }); }
            @Override public void onStop(String id, boolean interrupted) { handler.post(() -> { if (isPreview(id)) { previewStopped(id, interrupted); return; } if (END_UTTERANCE.equals(id)) { pause(); return; } if (playing && interrupted && id.equals(activeUtterance)
                    && SystemClock.elapsedRealtime() - utteranceStartedAt > SEEK_SETTLE_MS * 2) retryCurrentSentence(id); }); }
            @Override public void onError(String id) { handler.post(() -> { if (isPreview(id)) previewFinished(id); else if (END_UTTERANCE.equals(id)) pause(); else retryCurrentSentence(id); }); }
        });
        notifyState(); if (pendingPlay) { pendingPlay = false; play(); }
    }

    private void savePosition() {
        if (uri.isEmpty()) return;
        getSharedPreferences("book_positions", MODE_PRIVATE).edit().putInt(key(uri), current).apply();
    }
    public int savedPosition(String value) { return getSharedPreferences("book_positions", MODE_PRIVATE).getInt(key(value), 0); }
    // Removing a book from Recent files means forgetting it, so the place it was left at goes with it, and so
    // does a sleep timer waiting to return into that very book.
    public void forgetBook(String value) {
        if (value == null || value.isEmpty()) return;
        getSharedPreferences("book_positions", MODE_PRIVATE).edit().remove(key(value)).apply();
        if (value.equals(getSharedPreferences(SLEEP_STATE, MODE_PRIVATE).getString("uri", ""))) { clearSleepRewindState(); notifyState(); }
    }
    // savePosition() runs once per sentence, so the hash of the (unchanged) document uri is worth keeping.
    private String key(String value) {
        if (value.equals(positionKeyUri)) return positionKey;
        positionKeyUri = value; positionKey = hash(value); return positionKey;
    }
    private String hash(String value) {
        try { byte[] b = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); StringBuilder s = new StringBuilder(); for (byte x : b) s.append(String.format(Locale.ROOT, "%02x", x)); return s.toString(); }
        catch (Exception e) { return Integer.toHexString(value.hashCode()); }
    }
    private void notifyState() { updateMediaSession(); if (listener != null) listener.onPlaybackState(current, sentences.size(), playing); }
    private void error(String value) { if (listener != null) listener.onPlaybackError(value); }

    private void createMediaSession() {
        audioManager = (AudioManager)getSystemService(AUDIO_SERVICE);
        mediaSession = new MediaSession(this, "Vox TXT");
        mediaSession.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        mediaSession.setPlaybackToLocal(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        mediaSession.setSessionActivity(PendingIntent.getActivity(this, 10, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        Intent mediaButtons = new Intent(Intent.ACTION_MEDIA_BUTTON).setClass(this, MediaButtonReceiver.class);
        mediaSession.setMediaButtonReceiver(PendingIntent.getBroadcast(this, 11, mediaButtons, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE));
        mediaSession.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { if (!getSharedPreferences("reader_settings", MODE_PRIVATE).getBoolean("prevent_device_autoplay", true) || SystemClock.elapsedRealtime() >= suppressExternalPlayUntil) play(); }
            @Override public void onPause() { pause(); }
            @Override public void onStop() { pause(); }
            @Override public void onSkipToPrevious() { move(-1); }
            @Override public void onSkipToNext() { move(1); }
            @Override public boolean onMediaButtonEvent(Intent mediaButtonIntent) {
                KeyEvent event = mediaButtonIntent == null ? null : mediaButtonIntent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
                if (event == null || event.getAction() != KeyEvent.ACTION_DOWN || event.getRepeatCount() != 0) return super.onMediaButtonEvent(mediaButtonIntent);
                switch (event.getKeyCode()) {
                    case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                    case KeyEvent.KEYCODE_HEADSETHOOK: if (playing) pause(); else play(); return true;
                    case KeyEvent.KEYCODE_MEDIA_PLAY: play(); return true;
                    case KeyEvent.KEYCODE_MEDIA_PAUSE: pause(); return true;
                    case KeyEvent.KEYCODE_MEDIA_NEXT: move(1); return true;
                    case KeyEvent.KEYCODE_MEDIA_PREVIOUS: move(-1); return true;
                    case KeyEvent.KEYCODE_MEDIA_STOP: pause(); return true;
                    default: return super.onMediaButtonEvent(mediaButtonIntent);
                }
            }
        });
        mediaSession.setActive(true);
        updateMediaSession();
    }
    private void promoteMediaSession() {
        if (mediaSession == null) return;
        mediaSession.setActive(false);
        mediaSession.setActive(true);
    }
    private void updateMediaSession() {
        if (mediaSession == null) return;
        long actions = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE |
            PlaybackState.ACTION_STOP | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_SKIP_TO_NEXT;
        mediaSession.setPlaybackState(new PlaybackState.Builder().setActions(actions)
            .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED, current, playing ? 1f : 0f, SystemClock.elapsedRealtime()).build());
        mediaSession.setMetadata(new MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, getString(R.string.app_name))
            .putLong(MediaMetadata.METADATA_KEY_TRACK_NUMBER, current + 1L)
            .putLong(MediaMetadata.METADATA_KEY_NUM_TRACKS, sentences.size()).build());
    }
    private boolean requestAudioFocus() {
        if (hasAudioFocus) return true;
        AudioAttributes attributes = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
        if (audioFocusRequest == null) audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes).setOnAudioFocusChangeListener(focusListener, focusHandler).setWillPauseWhenDucked(false).build();
        int result = audioManager.requestAudioFocus(audioFocusRequest);
        hasAudioFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        return hasAudioFocus;
    }
    private void abandonAudioFocus() {
        if (!hasAudioFocus || audioManager == null) return;
        if (audioFocusRequest != null) audioManager.abandonAudioFocusRequest(audioFocusRequest);
        hasAudioFocus = false;
    }
    // The fade is the 1.0-beta2 one, unchanged: the device volume falls step by step over the ten seconds.
    // Everything tried instead of it - a gain effect on the speech session, a scheduled curve - sounded
    // worse on a real phone. The only additions are the guards around setStreamVolume().
    private void beginSleepFade() {
        if (sleepDeadline <= 0) return;
        if (!playing) { sleepHandler.postDelayed(this::finishSleepTimer, Math.max(0, sleepDeadline - SystemClock.elapsedRealtime())); return; }
        sleepFadeStartSentence = Math.max(0, current - 1);
        volumeBeforeFade = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC); rememberFadeVolume(volumeBeforeFade); fadeSleepStep();
    }
    private void fadeSleepStep() {
        long remaining = sleepDeadline - SystemClock.elapsedRealtime();
        if (remaining <= 0) { setMusicVolume(0); finishSleepTimer(); return; }
        if (!playing || volumeBeforeFade < 0) { sleepHandler.postDelayed(this::finishSleepTimer, remaining); return; }
        double progress = 1.0 - Math.min(SLEEP_FADE_DURATION_MS, remaining) / (double)SLEEP_FADE_DURATION_MS;
        int faded = Math.max(0, (int)Math.round(volumeBeforeFade * (1.0 - progress)));
        if (!setMusicVolume(faded)) { finishSleepTimer(); return; }
        sleepHandler.postDelayed(this::fadeSleepStep, Math.min(SLEEP_FADE_UPDATE_MS, remaining));
    }
    // Do Not Disturb can forbid volume changes; in that case give the stream back to the user and let the
    // timer stop playback without a fade rather than crashing halfway through it.
    private boolean setMusicVolume(int value) {
        if (audioManager == null) return false;
        try { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, value, 0); return true; }
        catch (SecurityException denied) { volumeBeforeFade = -1; forgetFadeVolume(); return false; }
    }
    private void finishSleepTimer() {
        sleepHandler.removeCallbacksAndMessages(null);
        sleepDeadline = 0;
        int returnSentence = sleepFadeStartSentence;
        int restoreVolume = volumeBeforeFade;
        volumeBeforeFade = -1;
        pause();
        if (returnSentence >= 0 && !sentences.isEmpty()) { current = Math.min(returnSentence, sentences.size() - 1); savePosition(); }
        sleepFadeStartSentence = -1;
        sleepRewindAvailable = sleepStartSentence >= 0 && completedSleepMinutes > 0;
        persistSleepRewindState();
        notifyState();
        // Short technical pause so the tail of the interrupted sentence is not heard again at full volume.
        if (restoreVolume >= 0) { pendingVolumeRestore = restoreVolume; sleepHandler.postDelayed(this::flushVolumeRestore, 250L); }
    }
    // Called from pause(), so stopping in the middle of a fade-out hands the device volume straight back.
    private void restoreVolumeAfterFade() {
        flushVolumeRestore();
        if (volumeBeforeFade >= 0) { setMusicVolume(volumeBeforeFade); volumeBeforeFade = -1; forgetFadeVolume(); }
    }
    // The delayed restore lives on sleepHandler, which any new timer clears - never leave the device muted.
    private void flushVolumeRestore() {
        if (pendingVolumeRestore < 0) return;
        int value = pendingVolumeRestore; pendingVolumeRestore = -1; setMusicVolume(value); forgetFadeVolume();
    }
    private void rememberFadeVolume(int value) { getSharedPreferences(SLEEP_STATE, MODE_PRIVATE).edit().putInt("fade_volume", value).apply(); }
    private void forgetFadeVolume() { getSharedPreferences(SLEEP_STATE, MODE_PRIVATE).edit().remove("fade_volume").apply(); }
    // If the process was killed mid fade-out the device is left quiet; put the volume back on next start.
    private void restoreVolumeAfterCrash() {
        int stored = getSharedPreferences(SLEEP_STATE, MODE_PRIVATE).getInt("fade_volume", -1);
        if (stored < 0) return;
        forgetFadeVolume();
        if (audioManager != null && audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) < stored) setMusicVolume(stored);
    }
    private void persistSleepRewindState() {
        if (!sleepRewindAvailable) { getSharedPreferences(SLEEP_STATE, MODE_PRIVATE).edit().clear().apply(); return; }
        getSharedPreferences(SLEEP_STATE, MODE_PRIVATE).edit().putBoolean("available", true).putString("uri", uri).putInt("sentence", sleepStartSentence).putInt("minutes", completedSleepMinutes).apply();
    }
    private void restoreSleepRewindState() {
        android.content.SharedPreferences state = getSharedPreferences(SLEEP_STATE, MODE_PRIVATE);
        sleepRewindAvailable = state.getBoolean("available", false); sleepStartSentence = state.getInt("sentence", -1); completedSleepMinutes = state.getInt("minutes", 0);
        if (sleepStartSentence < 0 || completedSleepMinutes <= 0) clearSleepRewindState();
    }
    private void clearSleepRewindState() { sleepRewindAvailable = false; completedSleepMinutes = 0; sleepStartSentence = -1; getSharedPreferences(SLEEP_STATE, MODE_PRIVATE).edit().clear().apply(); }
    private void registerAudioRouteListeners() {
        for (AudioDeviceInfo device : audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) knownAudioOutputs.add(device.getId());
        audioManager.registerAudioDeviceCallback(audioDeviceCallback, handler);
        IntentFilter filter = new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(becomingNoisyReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(becomingNoisyReceiver, filter);
    }
    private boolean isExternalAudioOutput(AudioDeviceInfo device) {
        switch (device.getType()) {
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:
            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO:
            case AudioDeviceInfo.TYPE_WIRED_HEADSET:
            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:
            case AudioDeviceInfo.TYPE_USB_HEADSET:
            case AudioDeviceInfo.TYPE_BLE_HEADSET:
            case AudioDeviceInfo.TYPE_BLE_SPEAKER:
                return true;
            default:
                return false;
        }
    }

    private void createChannel() {
        NotificationChannel c = new NotificationChannel(CHANNEL, getString(R.string.playback_channel), NotificationManager.IMPORTANCE_LOW);
        c.setDescription(getString(R.string.playback_channel)); ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(c);
    }
    private PendingIntent command(String action, int request) {
        Intent i = new Intent(this, ReaderService.class).setAction(action);
        return PendingIntent.getService(this, request, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
    private Notification notification() {
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent content = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String line = sentences.isEmpty() ? "" : getString(playing ? R.string.notification_text : R.string.notification_paused, current + 1, sentences.size());
        Notification.Builder b = new Notification.Builder(this, CHANNEL);
        b.setSmallIcon(R.drawable.ic_launcher).setContentTitle(title).setContentText(line).setContentIntent(content).setOngoing(playing).setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_media_previous, getString(R.string.previous), command(ACTION_PREVIOUS, 1))
            .addAction(playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play, getString(playing ? R.string.pause : R.string.read), command(playing ? ACTION_PAUSE : ACTION_PLAY, 2))
            .addAction(android.R.drawable.ic_media_next, getString(R.string.next), command(ACTION_NEXT, 3))
            .setStyle(new Notification.MediaStyle().setMediaSession(mediaSession.getSessionToken()).setShowActionsInCompactView(0, 1, 2));
        return b.build();
    }
    private void updateNotification() { if (!sentences.isEmpty()) ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(NOTIFICATION_ID, notification()); }
    @Override public void onDestroy() { speechHandler.removeCallbacksAndMessages(null); handler.removeCallbacksAndMessages(null); sleepHandler.removeCallbacksAndMessages(null); lifecycleHandler.removeCallbacksAndMessages(null); pause(); stopPreview(); flushVolumeRestore(); stopSilentPlayback(); if (audioManager != null) audioManager.unregisterAudioDeviceCallback(audioDeviceCallback); try { unregisterReceiver(becomingNoisyReceiver); } catch (IllegalArgumentException ignored) {} if (mediaSession != null) mediaSession.release(); if (tts != null) tts.shutdown(); super.onDestroy(); }

    public static class Range { public final int start, end; Range(int start, int end) { this.start = start; this.end = end; } }
}
