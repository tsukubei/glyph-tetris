package com.example.glyphtetris;

import android.os.Handler;
import android.os.Looper;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

/**
 * 押した瞬間に1回実行し、押し続けている間は一定間隔で繰り返す(左右移動のオートリピート用)。
 * repeatMs が 0 なら繰り返さない。離した/キャンセルされたら onRelease を呼ぶ。
 */
final class RepeatTouchListener implements View.OnTouchListener {

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable onPress;
    private final Runnable onRelease;
    private final long initialDelayMs;
    private final long repeatMs;
    private boolean down;

    private final Runnable repeater = new Runnable() {
        @Override
        public void run() {
            if (!down) return;
            onPress.run();
            handler.postDelayed(this, repeatMs);
        }
    };

    RepeatTouchListener(Runnable onPress, Runnable onRelease, long initialDelayMs, long repeatMs) {
        this.onPress = onPress;
        this.onRelease = onRelease;
        this.initialDelayMs = initialDelayMs;
        this.repeatMs = repeatMs;
    }

    @Override
    public boolean onTouch(View v, MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                down = true;
                v.setPressed(true);
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                onPress.run();
                if (repeatMs > 0) handler.postDelayed(repeater, initialDelayMs);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                down = false;
                v.setPressed(false);
                handler.removeCallbacks(repeater);
                if (onRelease != null) onRelease.run();
                return true;
            default:
                return true;
        }
    }
}
