package bg.simpletxtreader;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.view.KeyEvent;

public class MediaButtonReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || !Intent.ACTION_MEDIA_BUTTON.equals(intent.getAction())) return;
        Intent service = new Intent(context, ReaderService.class).setAction(Intent.ACTION_MEDIA_BUTTON);
        KeyEvent event = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
        if (event != null) service.putExtra(Intent.EXTRA_KEY_EVENT, event);
        context.startForegroundService(service);
    }
}
