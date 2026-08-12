package bg.simpletxtreader;

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
    public static final String ACTION_PLAY = "bg.simpletxtreader.PLAY";
    public static final String ACTION_PAUSE = "bg.simpletxtreader.PAUSE";
    public static final String ACTION_PREVIOUS = "bg.simpletxtreader.PREVIOUS";
    public static final String ACTION_NEXT = "bg.simpletxtreader.NEXT";
    public static final String ACTION_STOP = "bg.simpletxtreader.STOP";
    private static final String CHANNEL = "reader_playback";
    private static final int NOTIFICATION_ID = 42;
    private static final String SLEEP_STATE = "sleep_rewind_state";

    public interface Listener { void onPlaybackState(int index, int count, boolean playing); void onPlaybackError(String message); }
    public class ReaderBinder extends Binder {
        public ReaderService service() { return ReaderService.this; }
    }
    public static class EngineOption {
        public final String name, label;
        EngineOption(String name, String label) { this.name = name; this.label = label; }
    }
    public static class VoiceOption {
        public final String name, label, localeTag;
        VoiceOption(String name, String label) { this(name, label, ""); }
        VoiceOption(String name, String label, String localeTag) { this.name = name; this.label = label; this.localeTag = localeTag == null ? "" : localeTag; }
    }
    public interface VoicesCallback { void onVoices(List<VoiceOption> voices); }

    private final IBinder binder = new ReaderBinder();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Handler sleepHandler = new Handler(Looper.getMainLooper());
    private final ArrayList<Range> sentences = new ArrayList<>();
    private TextToSpeech tts;
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
    private int volumeBeforeFade = -1;
    private int sleepStartSentence = -1, sleepFadeStartSentence = -1, completedSleepMinutes;
    private boolean sleepRewindAvailable, captureSleepStartOnPlay;
    private int transientRetries;
    private long utteranceSerial;
    private String activeUtterance = "";
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
    private final AudioManager.OnAudioFocusChangeListener focusListener = change -> {
        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) pause();
    };

    @Override public void onCreate() {
        super.onCreate(); restoreSleepRewindState(); createChannel(); createMediaSession(); registerAudioRouteListeners(); initializeTts(getSharedPreferences("reader_settings", MODE_PRIVATE).getString("engine", ""));
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
                    KeyEvent event = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
                    if (event != null && mediaSession != null) mediaSession.getController().dispatchMediaButtonEvent(event);
                    break;
            }
        }
        return START_NOT_STICKY;
    }

    public void setListener(Listener value) { listener = value; notifyState(); }
    public String getText() { return text; }
    public int getCurrent() { return current; }
    public int getCount() { return sentences.size(); }
    public boolean isPlaying() { return playing; }
    public boolean isReady() { return ready; }
    public boolean isSleepRewindAvailable() { return sleepRewindAvailable; }
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

    public void load(String newUri, String newTitle, String newText, int position) {
        pause(); uri = newUri; title = newTitle; text = newText; current = Math.max(0, position);
        if (sleepRewindAvailable && !newUri.equals(getSharedPreferences(SLEEP_STATE, MODE_PRIVATE).getString("uri", ""))) clearSleepRewindState();
        split(); current = Math.min(current, Math.max(0, sentences.size() - 1));
        savePosition(); notifyState();
    }
    public void clearDocument() {
        pause(); uri = ""; title = ""; text = ""; current = 0; sentences.clear();
        notifyState(); updateMediaSession(); stopForeground(true);
    }
    public void play() {
        if (sleepRewindAvailable) { clearSleepRewindState(); notifyState(); }
        if (!ready) { pendingPlay = true; return; }
        if (sentences.isEmpty()) return;
        if (captureSleepStartOnPlay) { sleepStartSentence = current; captureSleepStartOnPlay = false; }
        applySettings(); transientRetries = 0; playing = true;
        if (!requestAudioFocus()) { playing = false; notifyState(); updateMediaSession(); updateNotification(); return; }
        startSilentPlayback(); promoteMediaSession(); updateMediaSession(); startService(new Intent(this, ReaderService.class)); startForeground(NOTIFICATION_ID, notification());
        speakCurrent();
    }
    public void pause() {
        pendingPlay = false; playing = false; activeUtterance = ""; utteranceSerial++; handler.removeCallbacksAndMessages(null); if (tts != null) tts.stop(); stopSilentPlayback();
        restoreVolumeAfterFade(); abandonAudioFocus(); savePosition(); if (!sentences.isEmpty()) updateNotification(); notifyState(); updateMediaSession();
    }
    public void move(int delta) {
        boolean resume = playing; playing = false; activeUtterance = ""; utteranceSerial++; handler.removeCallbacksAndMessages(null); if (tts != null) tts.stop();
        if (!sentences.isEmpty()) current = Math.max(0, Math.min(current + delta, sentences.size() - 1));
        savePosition(); notifyState(); if (resume) { playing = true; speakCurrent(); } else updateNotification();
    }
    public void seekTo(int index) {
        playing = false; activeUtterance = ""; utteranceSerial++; handler.removeCallbacksAndMessages(null); if (tts != null) tts.stop(); stopSilentPlayback();
        if (!sentences.isEmpty()) current = Math.max(0, Math.min(index, sentences.size() - 1));
        savePosition(); notifyState(); updateNotification();
    }
    public void updateSettings(boolean playAfterUpdate) {
        String wantedEngine = getSharedPreferences("reader_settings", MODE_PRIVATE).getString("engine", "");
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

    private void split() {
        sentences.clear(); BreakIterator it = BreakIterator.getSentenceInstance(Locale.getDefault()); it.setText(text);
        int start = it.first();
        for (int end = it.next(); end != BreakIterator.DONE; start = end, end = it.next())
            if (!text.substring(start, end).trim().isEmpty()) sentences.add(new Range(start, end));
        if (sentences.isEmpty() && !text.trim().isEmpty()) sentences.add(new Range(0, text.length()));
    }
    private void speakCurrent() {
        if (!playing || current >= sentences.size()) { pause(); return; }
        applySettings(); Range range = sentences.get(current);
        activeUtterance = "sentence-" + current + "-" + (++utteranceSerial); String utterance = activeUtterance;
        Bundle parameters = new Bundle(); parameters.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, getSharedPreferences("reader_settings", MODE_PRIVATE).getInt("volume_percent", 50) / 100f);
        int result = tts.speak(text.substring(range.start, range.end).trim(), TextToSpeech.QUEUE_FLUSH, parameters, utterance);
        if (result == TextToSpeech.ERROR) { pause(); error(getString(R.string.tts_error)); }
        notifyState(); updateMediaSession(); updateNotification();
    }
    private void finishCurrentSentence(String utterance) {
        if (!playing || !utterance.equals(activeUtterance)) return;
        transientRetries = 0; activeUtterance = ""; current++; savePosition();
        if (current >= sentences.size()) { current = Math.max(0, sentences.size() - 1); pause(); return; }
        int delay = getSharedPreferences("reader_settings", MODE_PRIVATE).getInt("sentence_pause", 0);
        handler.postDelayed(ReaderService.this::speakCurrent, delay);
    }
    private void retryCurrentSentence(String utterance) {
        if (!utterance.equals(activeUtterance)) return;
        activeUtterance = "";
        if (playing && transientRetries++ < 3) handler.postDelayed(ReaderService.this::speakCurrent, 1200); else { pause(); error(getString(R.string.tts_error)); }
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
        tts.setSpeechRate(speechRate(p.getInt("rate_percent", 20))); tts.setPitch(speechPitch(p.getInt("pitch_percent", 20)));
        String selectedVoice = p.getString(voicePreferenceKey(activeEngine), "");
        if (selectedVoice.isEmpty()) { Voice defaultVoice = tts.getDefaultVoice(); if (defaultVoice != null) tts.setVoice(defaultVoice); }
        else { Set<Voice> voices = tts.getVoices(); if (voices != null) for (Voice voice : voices) if (selectedVoice.equals(voice.getName())) { tts.setVoice(voice); break; } }
    }
    public static String voicePreferenceKey(String engine) { return "voice_for_" + (engine == null || engine.isEmpty() ? "system_default" : engine); }
    private List<VoiceOption> filteredVoices(TextToSpeech source) {
        Set<Voice> voices = source.getVoices(); if (voices == null || voices.isEmpty()) return Collections.emptyList();
        TreeMap<String, VoiceOption> unique = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (Voice voice : voices) {
            if (voice == null || voice.getName() == null || voice.getName().trim().isEmpty() || voice.isNetworkConnectionRequired()) continue;
            Set<String> features = voice.getFeatures();
            if (features != null && (features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) || features.contains(TextToSpeech.Engine.KEY_FEATURE_NETWORK_SYNTHESIS))) continue;
            String localeTag = voice.getLocale() == null ? "" : voice.getLocale().toLanguageTag();
            VoiceOption option = new VoiceOption(voice.getName(), voice.getName(), localeTag);
            if (!unique.containsKey(voice.getName())) unique.put(voice.getName(), option);
        }
        return new ArrayList<>(unique.values());
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
        ready = false; if (tts != null) { tts.stop(); tts.shutdown(); }
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
        if (!ready) { error(getString(R.string.tts_unavailable)); notifyState(); return; }
        applySettings(); refreshEngines();
        tts.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String id) { handler.post(() -> { if (id.equals(activeUtterance)) refreshSilentPlaybackPriority(); }); }
            @Override public void onDone(String id) { handler.post(() -> finishCurrentSentence(id)); }
            @Override public void onStop(String id, boolean interrupted) { handler.post(() -> { if (playing && interrupted && id.equals(activeUtterance)) retryCurrentSentence(id); }); }
            @Override public void onError(String id) { handler.post(() -> retryCurrentSentence(id)); }
        });
        notifyState(); if (pendingPlay) { pendingPlay = false; play(); }
    }

    private void savePosition() {
        if (uri.isEmpty()) return;
        getSharedPreferences("book_positions", MODE_PRIVATE).edit().putInt(key(uri), current).apply();
    }
    public int savedPosition(String value) { return getSharedPreferences("book_positions", MODE_PRIVATE).getInt(key(value), 0); }
    private String key(String value) {
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
            .setAudioAttributes(attributes).setOnAudioFocusChangeListener(focusListener, handler).setWillPauseWhenDucked(false).build();
        int result = audioManager.requestAudioFocus(audioFocusRequest);
        hasAudioFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        return hasAudioFocus;
    }
    private void abandonAudioFocus() {
        if (!hasAudioFocus || audioManager == null) return;
        if (audioFocusRequest != null) audioManager.abandonAudioFocusRequest(audioFocusRequest);
        hasAudioFocus = false;
    }
    private void beginSleepFade() {
        if (sleepDeadline <= 0) return;
        if (!playing) { sleepHandler.postDelayed(this::finishSleepTimer, Math.max(0, sleepDeadline - SystemClock.elapsedRealtime())); return; }
        sleepFadeStartSentence = Math.max(0, current - 1);
        volumeBeforeFade = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC); fadeSleepStep();
    }
    private void fadeSleepStep() {
        long remaining = sleepDeadline - SystemClock.elapsedRealtime();
        if (remaining <= 0) { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0); finishSleepTimer(); return; }
        if (!playing || volumeBeforeFade < 0) { sleepHandler.postDelayed(this::finishSleepTimer, remaining); return; }
        double progress = 1.0 - Math.min(SLEEP_FADE_DURATION_MS, remaining) / (double)SLEEP_FADE_DURATION_MS;
        int faded = Math.max(0, (int)Math.round(volumeBeforeFade * (1.0 - progress)));
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, faded, 0);
        sleepHandler.postDelayed(this::fadeSleepStep, Math.min(SLEEP_FADE_UPDATE_MS, remaining));
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
        if (restoreVolume >= 0 && audioManager != null) sleepHandler.postDelayed(() -> audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, restoreVolume, 0), 250L);
    }
    private void restoreVolumeAfterFade() { if (volumeBeforeFade >= 0 && audioManager != null) { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, volumeBeforeFade, 0); volumeBeforeFade = -1; } }
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
        String line = getString(playing ? R.string.notification_text : R.string.notification_paused, current + 1, sentences.size());
        Notification.Builder b = new Notification.Builder(this, CHANNEL);
        b.setSmallIcon(R.drawable.ic_launcher).setContentTitle(title).setContentText(line).setContentIntent(content).setOngoing(playing).setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_media_previous, getString(R.string.previous), command(ACTION_PREVIOUS, 1))
            .addAction(playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play, getString(playing ? R.string.pause : R.string.read), command(playing ? ACTION_PAUSE : ACTION_PLAY, 2))
            .addAction(android.R.drawable.ic_media_next, getString(R.string.next), command(ACTION_NEXT, 3))
            .setStyle(new Notification.MediaStyle().setMediaSession(mediaSession.getSessionToken()).setShowActionsInCompactView(0, 1, 2));
        return b.build();
    }
    private void updateNotification() { if (!sentences.isEmpty()) ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(NOTIFICATION_ID, notification()); }
    @Override public void onDestroy() { sleepHandler.removeCallbacksAndMessages(null); pause(); stopSilentPlayback(); if (audioManager != null) audioManager.unregisterAudioDeviceCallback(audioDeviceCallback); try { unregisterReceiver(becomingNoisyReceiver); } catch (IllegalArgumentException ignored) {} if (mediaSession != null) mediaSession.release(); if (tts != null) tts.shutdown(); super.onDestroy(); }

    public static class Range { public final int start, end; Range(int start, int end) { this.start = start; this.end = end; } }
}
