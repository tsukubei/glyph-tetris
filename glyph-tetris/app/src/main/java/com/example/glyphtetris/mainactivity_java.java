package com.example.glyphtetris;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Arrays;
import java.util.Locale;

/**
 * 上: Glyph Matrixを模した表示(実機と同じフレームを描画) / 下: 操作ボタン。
 * ゲームは1つのループで進み、同じフレームを「画面の模擬表示」と「実機Glyph」の両方へ渡す。
 * 起動直後は「Tap to start」を押すまで始まらない。
 */
public final class MainActivity extends Activity implements Choreographer.FrameCallback {

    private static final int BG = 0xFF0B0B0D;
    private static final int BTN_DARK = 0xFF2C2C2E;
    private static final int BTN_BLUE = 0xFF1F6FEB;
    private static final int BTN_RED = 0xFFD71921;

    private static final long HEARTBEAT_MS = 1000;   // 変化が無くても1秒ごとに再送(消灯対策)
    private static final long MIN_SEND_GAP_MS = 33;  // 最大およそ30fps

    private GlyphBridge bridge;
    private GlyphFrameComposer composer;
    private TetrisEngine engine;

    private GlyphPreviewView preview;
    private NextPieceView nextView;
    private View lockBar;
    private TextView hud;
    private TextView status;
    private TextView pauseBtn;
    private TextView startBtn;

    private boolean started;             // 「Tap to start」を押したか
    private long gameOverStartMs = -1;    // ゲームオーバー演出の開始時刻

    private boolean running;
    private long lastFrameNanos;
    private int[] lastFrame;
    private int[] lastSent;
    private long lastSentMs;
    private String lastHud = "";
    private int lastNextType = -1;
    private float lastLockProgress = -1f;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        bridge = new GlyphBridge(getApplicationContext(), new GlyphBridge.Listener() {
            @Override
            public void onStatus(GlyphBridge.Status s) {
                onGlyphStatus(s);
            }
        });
        int n = bridge.getMatrixLength() > 0 ? bridge.getMatrixLength() : 25;
        composer = new GlyphFrameComposer(n);
        engine = new TetrisEngine(composer.boardW, composer.boardH, System.nanoTime());
        engine.setPaused(true); // 開始ボタンを押すまで動かさない

        setContentView(buildUi(n));
        onGlyphStatus(bridge.getStatus());
    }

    @Override
    protected void onStart() {
        super.onStart();
        bridge.connect();
    }

    @Override
    protected void onResume() {
        super.onResume();
        running = true;
        lastFrameNanos = 0;
        Choreographer.getInstance().postFrameCallback(this);
    }

    @Override
    protected void onPause() {
        running = false;
        Choreographer.getInstance().removeFrameCallback(this);
        engine.setPaused(true);
        super.onPause();
    }

    @Override
    protected void onStop() {
        bridge.disconnect();
        super.onStop();
    }

    /** 新しいゲームを始める(Tap to start / RESTART 共通)。 */
    private void startGame() {
        engine.reset();
        started = true;
        gameOverStartMs = -1;
        startBtn.setVisibility(View.GONE);
        lastFrame = null;
        lastSent = null;
        lastHud = "";
        lastNextType = -1;
        lastLockProgress = -1f;
    }

    // ------------------------------------------------------------------ ゲームループ

    @Override
    public void doFrame(long frameTimeNanos) {
        if (!running) return;
        long dtMs = lastFrameNanos == 0 ? 0 : (frameTimeNanos - lastFrameNanos) / 1_000_000L;
        lastFrameNanos = frameTimeNanos;
        if (dtMs > 100) dtMs = 100;

        if (started) engine.update(dtMs);

        long now = SystemClock.uptimeMillis();
        int[] f;
        if (started && engine.getState() == TetrisEngine.State.GAME_OVER) {
            // ゲームオーバー: 点滅×3 → 全点灯 → 下へフェードアウト → 消灯。終わったら開始ボタンを再表示
            if (gameOverStartMs < 0) gameOverStartMs = now;
            long t = now - gameOverStartMs;
            f = composer.composeGameOver(t);
            if (t >= GlyphFrameComposer.GAME_OVER_MS && startBtn.getVisibility() != View.VISIBLE) {
                startBtn.setVisibility(View.VISIBLE);
            }
        } else {
            f = composer.compose(engine, now);
        }

        if (lastFrame == null || !Arrays.equals(f, lastFrame)) {
            preview.setFrame(f);
            lastFrame = f;
        }

        boolean changed = lastSent == null || !Arrays.equals(f, lastSent);
        if ((changed && now - lastSentMs >= MIN_SEND_GAP_MS) || now - lastSentMs >= HEARTBEAT_MS) {
            bridge.send(f);
            lastSent = f;
            lastSentMs = now;
        }

        refreshHud();
        Choreographer.getInstance().postFrameCallback(this);
    }

    private void refreshHud() {
        String state = "";
        if (started) {
            if (engine.getState() == TetrisEngine.State.GAME_OVER) state = "   GAME OVER";
            else if (engine.isPaused()) state = "   PAUSED";
        }
        String s = String.format(Locale.US, "SCORE %d   LINES %d   LV %d%s",
                engine.getScore(), engine.getLines(), engine.getLevel(), state);
        if (!s.equals(lastHud)) {
            lastHud = s;
            hud.setText(s);
            pauseBtn.setText(started && engine.isPaused() ? "RESUME" : "PAUSE");
        }

        // NEXT表示の更新
        int nextType = engine.getNextType();
        if (nextType != lastNextType) {
            lastNextType = nextType;
            nextView.setCells(engine.nextCells());
        }

        // 着弾後の確定猶予バー(白色)の更新
        float lockProgress = started ? engine.getLockProgress() : 0f;
        if (Math.abs(lockProgress - lastLockProgress) > 0.005f) {
            lastLockProgress = lockProgress;
            lockBar.setPivotX(0f);
            lockBar.setScaleX(lockProgress);
        }
    }

    private static String deviceName(int matrixLength) {
        if (matrixLength == 25) return "Phone (3)";
        if (matrixLength == 13) return "Phone (4a)";
        return "Nothing Phone";
    }

    private void onGlyphStatus(GlyphBridge.Status s) {
        if (status == null) return;
        switch (s) {
            case UNSUPPORTED:
                status.setText("Glyph非対応の端末です(画面のみで動作)");
                break;
            case CONNECTING:
                status.setText("Glyph接続中…");
                break;
            case READY:
                status.setText("Playing on " + deviceName(bridge.getMatrixLength()));
                lastSent = null; // 接続できたら直ちに最新フレームを送る
                break;
            default:
                status.setText("Glyph登録に失敗(システムが古い/権限を確認)");
                break;
        }
    }

    // ------------------------------------------------------------------ UI構築

    private View buildUi(int n) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(12), dp(8), dp(12), dp(12));

        // 上: Glyph模擬ディスプレイ(その上に大きな「Tap to start」)
        FrameLayout top = new FrameLayout(this);
        preview = new GlyphPreviewView(this);
        preview.setMatrixLength(n);
        top.addView(preview, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));

        startBtn = button("Tap to start", BTN_RED, 30);
        startBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startGame();
            }
        });
        int startW = Math.round(getResources().getDisplayMetrics().widthPixels * 0.7f);
        top.addView(startBtn, new FrameLayout.LayoutParams(startW, dp(96), Gravity.CENTER));

        root.addView(top, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 5f));

        // 中: 状態表示・NEXT表示・猶予バー・PAUSE/RESTART
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        status = label(12, 0xFF8E8E93);
        info.addView(status);

        // スコア行の右側にNEXT表示を配置
        LinearLayout scoreRow = new LinearLayout(this);
        scoreRow.setOrientation(LinearLayout.HORIZONTAL);
        scoreRow.setGravity(Gravity.CENTER_VERTICAL);

        hud = label(15, Color.WHITE);
        hud.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        scoreRow.addView(hud, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout nextBox = new LinearLayout(this);
        nextBox.setOrientation(LinearLayout.HORIZONTAL);
        nextBox.setGravity(Gravity.CENTER_VERTICAL);
        nextBox.setBackground(rounded(0xFF18181C));
        nextBox.setPadding(dp(6), dp(3), dp(6), dp(3));

        TextView nextLabel = label(9, 0xFF8E8E93);
        nextLabel.setText("NEXT");
        nextLabel.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        nextBox.addView(nextLabel);

        nextView = new NextPieceView(this);
        LinearLayout.LayoutParams nvParams = new LinearLayout.LayoutParams(dp(32), dp(16));
        nvParams.leftMargin = dp(4);
        nextBox.addView(nextView, nvParams);

        LinearLayout.LayoutParams nbParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nbParams.rightMargin = dp(8);
        scoreRow.addView(nextBox, nbParams);
        info.addView(scoreRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 着弾してからの確定猶予バー(白色)
        FrameLayout lockTrack = new FrameLayout(this);
        lockTrack.setBackground(rounded(0x1AFFFFFF));
        lockBar = new View(this);
        lockBar.setBackgroundColor(Color.WHITE);
        lockBar.setPivotX(0f);
        lockBar.setScaleX(0f);
        lockTrack.addView(lockBar, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout.LayoutParams ltParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(3));
        ltParams.topMargin = dp(4);
        ltParams.rightMargin = dp(8);
        info.addView(lockTrack, ltParams);

        bar.addView(info, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        pauseBtn = button("PAUSE", BTN_DARK, 13);
        pauseBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (started) engine.togglePause();
            }
        });
        TextView restartBtn = button("RESTART", BTN_DARK, 13);
        restartBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startGame();
            }
        });
        bar.addView(pauseBtn, new LinearLayout.LayoutParams(dp(84), dp(40)));
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(dp(84), dp(40));
        rp.leftMargin = dp(6);
        bar.addView(restartBtn, rp);
        root.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 下: 操作ボタン(左手: 移動と落下 / 右手: 回転とハードドロップ)
        TextView left = button("←", BTN_DARK, 26);
        TextView right = button("→", BTN_DARK, 26);
        TextView soft = button("↓  SOFT", BTN_DARK, 18);
        TextView ccw = button("↺", BTN_BLUE, 28);
        TextView cw = button("↻", BTN_BLUE, 28);
        TextView hard = button("⇊  DROP", BTN_RED, 18);

        left.setOnTouchListener(new RepeatTouchListener(new Runnable() {
            @Override
            public void run() {
                engine.moveLeft();
            }
        }, null, 170, 55));
        right.setOnTouchListener(new RepeatTouchListener(new Runnable() {
            @Override
            public void run() {
                engine.moveRight();
            }
        }, null, 170, 55));
        soft.setOnTouchListener(new RepeatTouchListener(new Runnable() {
            @Override
            public void run() {
                engine.setSoftDrop(true);
            }
        }, new Runnable() {
            @Override
            public void run() {
                engine.setSoftDrop(false);
            }
        }, 0, 0));
        ccw.setOnTouchListener(new RepeatTouchListener(new Runnable() {
            @Override
            public void run() {
                engine.rotateCcw();
            }
        }, null, 0, 0));
        cw.setOnTouchListener(new RepeatTouchListener(new Runnable() {
            @Override
            public void run() {
                engine.rotateCw();
            }
        }, null, 0, 0));
        hard.setOnTouchListener(new RepeatTouchListener(new Runnable() {
            @Override
            public void run() {
                engine.hardDrop();
            }
        }, null, 0, 0));

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.addView(half(left, right, soft), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        controls.addView(half(ccw, cw, hard), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 4f);
        cp.topMargin = dp(8);
        root.addView(controls, cp);

        return root;
    }

    /** 上段に2ボタン(横並び)、下段に1ボタン(横幅いっぱい)。 */
    private LinearLayout half(View a, View b, View wide) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(a, cell(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        row.addView(b, cell(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        col.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 3f));
        col.addView(wide, cell(ViewGroup.LayoutParams.MATCH_PARENT, 0, 2f));
        return col;
    }

    private LinearLayout.LayoutParams cell(int w, int h, float weight) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h, weight);
        int m = dp(5);
        p.setMargins(m, m, m, m);
        return p;
    }

    private TextView label(int sp, int color) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        return t;
    }

    private TextView button(String text, int color, int sp) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(Color.WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.CENTER);

        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_pressed}, rounded(lighten(color)));
        states.addState(new int[]{}, rounded(color));
        t.setBackground(states);
        return t;
    }

    private GradientDrawable rounded(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(16));
        return g;
    }

    private static int lighten(int color) {
        int r = Math.min(255, Color.red(color) + 55);
        int g = Math.min(255, Color.green(color) + 55);
        int b = Math.min(255, Color.blue(color) + 55);
        return Color.rgb(r, g, b);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    /** HUDに次のミノ(4x2のLEDドット)を描画する軽量ビュー。 */
    private static final class NextPieceView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int[][] cells;

        NextPieceView(Context context) {
            super(context);
        }

        void setCells(int[][] cells) {
            this.cells = cells;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float cellW = getWidth() / 4f;
            float cellH = getHeight() / 2f;
            float r = Math.min(cellW, cellH) * 0.34f;
            for (int y = 0; y < 2; y++) {
                for (int x = 0; x < 4; x++) {
                    boolean lit = false;
                    if (cells != null) {
                        for (int[] c : cells) {
                            if (c[0] == x && c[1] == y) {
                                lit = true;
                                break;
                            }
                        }
                    }
                    paint.setColor(lit ? Color.WHITE : 0x22FFFFFF);
                    canvas.drawCircle((x + 0.5f) * cellW, (y + 0.5f) * cellH, r, paint);
                }
            }
        }
    }
}