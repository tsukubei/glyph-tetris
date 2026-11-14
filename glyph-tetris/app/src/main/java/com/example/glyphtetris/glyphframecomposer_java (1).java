package com.example.glyphtetris;

/**
 * エンジンの状態をGlyph Matrix用の明るさ配列(0..255, 行優先 n*n)に変換する。
 * 実機への送信と、画面上のGlyph模擬ディスプレイの両方がこの配列を使うので、常に同じ絵になる。
 *
 * 長方形の盤面切り出しや床・左右の枠を廃止し、isLed(n, x, y) が有効な円形LED全体
 * (Phone (3) なら489個すべて、Phone (4a) なら137個すべて) をそのまま盤面として描画する。
 */
public final class GlyphFrameComposer {

    // プロジェクト内の他クラス(GlyphPreviewView等)からの参照互換用に定数を維持
    public static final int BR_FLOOR = 40;
    public static final int BR_LOCKED = 115;
    public static final int BR_ACTIVE = 255;
    public static final int BR_GHOST = 14;
    public static final int BR_NEXT = 90;

    // ---- ゲームオーバー演出: 弱い点滅×3 → 全点灯 → 上から下へフェードアウト
    public static final int BR_BLINK = 70;
    private static final long BLINK_MS = 240;    // 点滅1回(点灯120ms + 消灯120ms)
    private static final int BLINKS = 3;
    private static final long BLINK_TOTAL_MS = BLINK_MS * BLINKS;
    private static final long HOLD_MS = 300;     // 全点灯で止める時間
    private static final long FADE_MS = 1500;    // 消えていく時間
    /** この時間を過ぎると全消灯(演出終了)。 */
    public static final long GAME_OVER_MS = BLINK_TOTAL_MS + HOLD_MS + FADE_MS;

    public final int n;
    public final int boardW, boardH;
    public final int originX = 0, originY = 0;
    /** 円形全体を使うためGlyph上のNEXT表示は無効化(-1)。NEXTはMainActivityのHUDに表示する。 */
    public final int nextX = -1, nextY = -1;

    public GlyphFrameComposer(int n) {
        this.n = n;
        this.boardW = n;
        this.boardH = n;
    }

    /**
     * LEDの有無。Phone (3) の25x25は円形で489個(実機仕様と一致を確認済み)。
     * Phone (4a) Pro の13x13は137個で、円(半径^2=42)がその個数に一致するため同形と仮定している。
     * この判定そのものがテトリスの壁と床の当たり判定になる。
     */
    public static boolean isLed(int n, int x, int y) {
        if (x < 0 || y < 0 || x >= n || y >= n) return false;
        if (n == 25) {
            int dx = x - 12;
            int dy = y - 12;
            return dx * dx + dy * dy <= 156;
        }
        if (n == 13) {
            int dx = x - 6;
            int dy = y - 6;
            return dx * dx + dy * dy <= 42;
        }
        return true;
    }

    public int[] compose(TetrisEngine e, long nowMs) {
        int[] f = new int[n * n];
        TetrisEngine.State st = e.getState();
        boolean paused = e.isPaused();
        float dim = paused ? 0.35f : 1f;
        boolean flash = (nowMs / 80) % 2 == 0;

        // 固定ブロック（床や左右の枠は描かず、円形LED全体を対象にする）
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                if (!isLed(n, x, y) || e.getCell(x, y) == 0) continue;
                int b = BR_LOCKED;
                if (st == TetrisEngine.State.CLEARING && e.isClearingRow(y)) {
                    b = flash ? BR_ACTIVE : 0;
                }
                put(f, x, y, (int) (b * dim));
            }
        }

        // ゴースト → 操作中ピース
        if (st == TetrisEngine.State.PLAYING) {
            int[][] ghost = e.ghostCells();
            if (ghost != null && !paused) {
                for (int[] c : ghost) {
                    if (c[1] >= 0 && isLed(n, c[0], c[1]) && e.getCell(c[0], c[1]) == 0) {
                        put(f, c[0], c[1], BR_GHOST);
                    }
                }
            }
            int[][] act = e.activeCells();
            if (act != null) {
                for (int[] c : act) {
                    if (c[1] >= 0 && isLed(n, c[0], c[1])) {
                        put(f, c[0], c[1], (int) (BR_ACTIVE * dim));
                    }
                }
            }
        }

        maskToLeds(f);
        return f;
    }

    /**
     * ゲームオーバー演出のフレーム。elapsedMs はゲームオーバーになってからの経過時間。
     * 0〜720ms: 全LEDが弱く3回点滅 / 〜1020ms: 全点灯 / 〜2520ms: 上から下へ消えていく / それ以降: 全消灯
     */
    public int[] composeGameOver(long elapsedMs) {
        int[] f = new int[n * n];
        long t = Math.max(0, elapsedMs);
        if (t >= GAME_OVER_MS) return f;

        if (t < BLINK_TOTAL_MS) {
            if (t % BLINK_MS < BLINK_MS / 2) fill(f, BR_BLINK);
        } else if (t < BLINK_TOTAL_MS + HOLD_MS) {
            fill(f, 255);
        } else {
            float q = (float) (t - BLINK_TOTAL_MS - HOLD_MS) / FADE_MS;
            q = q * q * (3f - 2f * q);                  // 始まりと終わりをなめらかに
            float soft = n * 0.35f;                      // 境目のぼかしの行数
            float front = q * (n + soft);
            for (int y = 0; y < n; y++) {
                float b = (y - front + soft) / soft;     // 上の行ほど先に暗くなる
                b = Math.max(0f, Math.min(1f, b));
                int v = Math.round(255 * b);
                for (int x = 0; x < n; x++) f[y * n + x] = v;
            }
        }
        maskToLeds(f);
        return f;
    }

    private void fill(int[] f, int v) {
        for (int i = 0; i < f.length; i++) f[i] = v;
    }

    /** 実在しないLEDを消す。 */
    private void maskToLeds(int[] f) {
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                if (!isLed(n, x, y)) f[y * n + x] = 0;
            }
        }
    }

    private void put(int[] f, int x, int y, int v) {
        if (x < 0 || y < 0 || x >= n || y >= n) return;
        f[y * n + x] = Math.max(0, Math.min(255, v));
    }
}