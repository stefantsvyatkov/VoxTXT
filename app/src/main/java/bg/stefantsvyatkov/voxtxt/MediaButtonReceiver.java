package bg.stefantsvyatkov.voxtxt;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.view.KeyEvent;

// The way a headset button, a Bluetooth remote or a screen reader gesture reaches the reading.
//
// It asks one question before it does anything: is there a book. A phone remembers which app was the last
// to play something and goes on sending media keys there long after that app has been closed - which is how
// a reader that had been shut down could answer a Play with its own name and nothing else, or start reading
// a book the user thought they had put away. When there is nothing to read, this receiver is silent and
// starts no service at all, so there is nothing to come back from.
public class MediaButtonReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || !Intent.ACTION_MEDIA_BUTTON.equals(intent.getAction())) return;
        boolean armed = context.getSharedPreferences("reader_documents", Context.MODE_PRIVATE)
            .getBoolean(ReaderService.ARMED, false);
        if (!armed) return;
        Intent service = new Intent(context, ReaderService.class).setAction(Intent.ACTION_MEDIA_BUTTON);
        KeyEvent event = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
        if (event != null) service.putExtra(Intent.EXTRA_KEY_EVENT, event);
        context.startForegroundService(service);
    }
}
