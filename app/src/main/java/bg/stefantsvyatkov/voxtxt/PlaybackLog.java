package bg.stefantsvyatkov.voxtxt;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// A written record of what the player did, for the times when it behaves differently on someone else's
// phone than it does on ours. It answers the questions that cannot be answered by reading the code: whether
// a callback the system is supposed to send actually arrived, and in what order things happened.
//
// Two rules it lives by. It never writes a word of what is being read - the file is going to travel through
// a chat app, and a book has no business in it. And it never throws: a fault in the record keeping must not
// become a fault in the reading, so every path here swallows its own errors.
//
// On by default for as long as the player is under test, and switched off in Options. Before this goes out
// on Play it belongs off again: the file sits in Downloads where the reader can see it, and nobody who is
// not chasing a fault has any use for it.
final class PlaybackLog {

    private static final String FILE_NAME = "voxtxt-log.txt";
    // Long enough to hold an evening of testing, short enough to send over a chat app. Past it the file
    // starts again rather than growing without end.
    private static final long MAX_BYTES = 512 * 1024;

    private static final ExecutorService writer = Executors.newSingleThreadExecutor();
    private static final SimpleDateFormat clock = new SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT);
    private static Boolean enabled;
    private static boolean headerWritten;
    private static Uri cached;

    private PlaybackLog() {}

    static boolean isOn(Context context) {
        if (enabled == null) enabled = context.getSharedPreferences("reader_settings", Context.MODE_PRIVATE).getBoolean("log_events", true);
        return enabled;
    }
    // Called when the setting changes, so the next event does not consult a stale answer.
    static void forget() { enabled = null; }

    static void event(Context context, String message) {
        if (context == null || !isOn(context)) return;
        Context application = context.getApplicationContext();
        String line = clock.format(new Date()) + "  " + message + "\n";
        writer.execute(() -> {
            try {
                if (!headerWritten) { headerWritten = true; append(application, header(application)); }
                append(application, line);
            } catch (Throwable ignored) {}
        });
    }

    // Everything needed to compare one phone against another, which is the whole reason the file exists.
    private static String header(Context context) {
        String version = "?";
        try { version = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName; }
        catch (Throwable ignored) {}
        return "\n=== Vox TXT " + version
            + " | " + Build.MANUFACTURER + " " + Build.MODEL
            + " | Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")"
            + " | " + new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(new Date())
            + " ===\n";
    }

    private static void append(Context context, String text) throws Exception {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (Build.VERSION.SDK_INT >= 29) {
            Uri target = target(context);
            if (target == null) return;
            try (OutputStream out = context.getContentResolver().openOutputStream(target, "wa")) {
                if (out != null) out.write(bytes);
            }
            return;
        }
        File directory = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Vox TXT");
        if (!directory.exists() && !directory.mkdirs()) return;
        File file = new File(directory, FILE_NAME);
        if (file.length() > MAX_BYTES && !file.delete()) return;
        try (OutputStream out = new FileOutputStream(file, true)) { out.write(bytes); }
    }

    // The row in Downloads that holds the file, found once and then remembered. When it has grown past the
    // cap it is thrown away and a fresh one takes its place.
    private static Uri target(Context context) {
        if (cached != null) {
            if (size(context, cached) <= MAX_BYTES) return cached;
            try { context.getContentResolver().delete(cached, null, null); } catch (Throwable ignored) {}
            cached = null; headerWritten = false;
        }
        Uri found = existing(context);
        if (found != null) { cached = found; return cached; }
        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, FILE_NAME);
            values.put(MediaStore.Downloads.MIME_TYPE, "text/plain");
            values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Vox TXT");
            cached = context.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        } catch (Throwable ignored) { cached = null; }
        return cached;
    }

    private static Uri existing(Context context) {
        try (Cursor c = context.getContentResolver().query(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                new String[]{MediaStore.Downloads._ID}, MediaStore.Downloads.DISPLAY_NAME + "=?",
                new String[]{FILE_NAME}, null)) {
            if (c != null && c.moveToFirst())
                return Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, String.valueOf(c.getLong(0)));
        } catch (Throwable ignored) {}
        return null;
    }

    private static long size(Context context, Uri uri) {
        try (Cursor c = context.getContentResolver().query(uri, new String[]{MediaStore.Downloads.SIZE}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getLong(0);
        } catch (Throwable ignored) {}
        return 0;
    }

    // What Share the log hands to the share sheet. Null when nothing has been written yet.
    static Uri fileUri(Context context) {
        if (Build.VERSION.SDK_INT >= 29) return existing(context);
        return null;
    }
    static String filePath(Context context) { return Environment.DIRECTORY_DOWNLOADS + "/Vox TXT/" + FILE_NAME; }
}
