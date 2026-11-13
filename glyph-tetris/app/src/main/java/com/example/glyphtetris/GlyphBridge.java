package com.example.glyphtetris;

import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;

import com.nothing.ketchum.Common;
import com.nothing.ketchum.Glyph;
import com.nothing.ketchum.GlyphException;
import com.nothing.ketchum.GlyphMatrixFrame;
import com.nothing.ketchum.GlyphMatrixManager;
import com.nothing.ketchum.GlyphMatrixObject;

/**
 * Glyph Matrix SDK とのやり取りをここに閉じ込める。
 * 送信は専用スレッドで行い、溜まったフレームは最新1枚だけを送る(UIをブロックしない)。
 * 値の変換は公開APIの経路(Bitmap → GlyphMatrixObject → GlyphMatrixFrame.render())を使う。
 */
public final class GlyphBridge {

    public enum Status { UNSUPPORTED, CONNECTING, READY, FAILED }

    public interface Listener {
        void onStatus(Status status);
    }

    private static final String TAG = "GlyphBridge";

    private final Context app;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final int matrixLength;

    private HandlerThread thread;
    private Handler worker;
    private GlyphMatrixManager gm;
    private volatile boolean ready;
    private volatile Status status;

    private int[] pending;      // synchronized(this)
    private boolean scheduled;  // synchronized(this)

    public GlyphBridge(Context appContext, Listener listener) {
        this.app = appContext;
        this.listener = listener;
        int len = 0;
        try {
            len = Common.getDeviceMatrixLength(); // Nothing以外/未対応機種では 0
        } catch (Throwable t) {
            Log.w(TAG, "getDeviceMatrixLength failed", t);
        }
        this.matrixLength = len;
        this.status = len == 0 ? Status.UNSUPPORTED : Status.CONNECTING;
    }

    /** 0 のときはこの端末にGlyph Matrixが無い(画面だけで動く)。 */
    public int getMatrixLength() {
        return matrixLength;
    }

    public Status getStatus() {
        return status;
    }

    public void connect() {
        if (matrixLength == 0 || gm != null) return;

        thread = new HandlerThread("glyph-send");
        thread.start();
        worker = new Handler(thread.getLooper());

        final GlyphMatrixManager m = GlyphMatrixManager.getInstance(app);
        gm = m;
        publish(Status.CONNECTING);
        m.init(new GlyphMatrixManager.Callback() {
            @Override
            public void onServiceConnected(ComponentName name) {
                String target = Common.is23112() ? Glyph.DEVICE_23112 : Glyph.DEVICE_25111p;
                boolean ok = m.register(target);
                ready = ok;
                publish(ok ? Status.READY : Status.FAILED);
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                ready = false;
                publish(Status.CONNECTING);
            }
        });
    }

    public void disconnect() {
        final GlyphMatrixManager m = gm;
        if (m == null) return;
        ready = false;
        gm = null;
        final HandlerThread t = thread;
        final Handler w = worker;
        thread = null;
        worker = null;
        w.post(new Runnable() {
            @Override
            public void run() {
                try {
                    m.closeAppMatrix();
                } catch (GlyphException ignored) {
                    // 未接続なら何もしなくてよい
                }
                m.unInit();
                t.quitSafely();
            }
        });
    }

    /** levels: 明るさ 0..255 の n*n 配列(行優先)。渡した配列は以後変更しないこと。 */
    public void send(int[] levels) {
        if (!ready || levels.length != matrixLength * matrixLength) return;
        final Handler w = worker;
        if (w == null) return;
        synchronized (this) {
            pending = levels;
            if (scheduled) return;
            scheduled = true;
        }
        w.post(new Runnable() {
            @Override
            public void run() {
                flush();
            }
        });
    }

    private void flush() {
        int[] f;
        synchronized (this) {
            f = pending;
            pending = null;
            scheduled = false;
        }
        final GlyphMatrixManager m = gm;
        if (f == null || m == null || !ready) return;

        final int n = matrixLength;
        try {
            int[] px = new int[n * n];
            for (int i = 0; i < px.length; i++) {
                int v = Math.max(0, Math.min(255, f[i]));
                px[i] = 0xFF000000 | (v << 16) | (v << 8) | v;
            }
            Bitmap bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
            bmp.setPixels(px, 0, n, 0, 0, n, n);

            GlyphMatrixObject obj = new GlyphMatrixObject.Builder()
                    .setImageSource(bmp)
                    .setScale(100)
                    .setOrientation(0)
                    .setPosition(0, 0)
                    .setBrightness(255)
                    .build();
            GlyphMatrixFrame frame = new GlyphMatrixFrame.Builder()
                    .addTop(obj)
                    .build(app);
            m.setAppMatrixFrame(frame.render());
        } catch (GlyphException | RuntimeException e) {
            Log.w(TAG, "send failed: " + e.getMessage());
        }
    }

    private void publish(final Status s) {
        status = s;
        main.post(new Runnable() {
            @Override
            public void run() {
                listener.onStatus(s);
            }
        });
    }
}
