package com.asjohn720.cursor;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Toast;

/** Opened from the Ball's app drawer. Shows or hides the trackpad. */
public class CursorActivity extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        CursorService s = CursorService.instance;
        if (s != null) {
            s.toggle();
        } else {
            Toast.makeText(this, "Turn on 'Cursor' in Accessibility first", Toast.LENGTH_LONG).show();
            try {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception ignored) { }
        }
        finish();
    }
}
