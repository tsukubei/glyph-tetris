package com.example.glyphtetris;

/**
 * エンジンの状態をGlyph Matrix用の明るさ配列(0..255, 行優先 n*n)に変換する。
 * 実機への送信と、画面上のGlyph模擬ディスプレイの両方がこの配列を使うので、常に同じ絵になる。
 *
 * 盤面は左右の枠を持たず、円形のLED配列に収まる最大の長方形を使う(床の1行だけ残す)。
 */
public final class GlyphFrameComposer {

    public static final int BR_FLOOR = 40;
    public static final int BR_LOCKED = 150;
    public static final int BR_ACTIVE = 255;
    public static final int BR_GHOST = 45;
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
    public final int originX, originY;
    /** NEXT表示の左上。置ける場所が無ければ -1。 */
    public final int nextX, nextY;

    public GlyphFrameComposer(int n) {
        this.n = n;
        // 25x25(Phone (3))は11x22、13x13は7x10になる
        int[] b = findBoard(n);
        int bw = b[0], bh = b[1], ox = b[2], oy = b[3];
        boardW = bw;
        boardH = bh;
        originX = ox;
        originY = oy;

        // NEXT: 盤面の右側に1列あけて、4x2が実在LEDに収まる最初の位置
        int nx = -1, ny = -1;
        int x0 = ox + bw + 1;
        for (int y0 = oy; y0 + 2 <= oy + bh && nx < 0; y0++) {
            boolean ok = true;
            for (int dy = 0; dy < 2; dy++) {
                for (int dx = 0; dx < 4; dx++) {
                    if (!isLed(n, x0 + dx, y0 + dy)) ok = false;
                }
            }
            if (ok) {
                nx = x0;
                ny = y0;
            }
        }
        nextX = nx;
        nextY = ny;
    }

    /**
     * LEDの有無。Phone (3) の25x25は円形で489個(実機仕様と一致を確認済み)。
     * Phone (4a) Pro の13x13は137個で、円(半径^2=42)がその個数に一致するため同形と仮定している(実機未確認)。
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

    /**
     * 盤面と床が全て実在LEDに乗る、左右対称で面積が最大の盤面を探す。 {幅, 高さ, originX, originY}
     * 幅の上限は 25x25 で11、13x13 で7(広げすぎるとゲームが別物になるため)。
     */
    private static int[] findBoard(int n) {
        int cap = n >= 25 ? 11 : 7;
        int[] best = null;
        int bestArea = -1;
        for (int bw = 4; bw <= cap; bw++) {
            if ((n - bw) % 2 != 0) continue;
            int ox = (n - bw) / 2;
            for (int bh = 8; bh <= n - 1; bh++) {
                for (int oy = 0; oy + bh + 1 <= n; oy++) {
                    if (fits(n, bw, bh, ox, oy)) {
                        if (bw * bh > bestArea) {
                            bestArea = bw * bh;
                            best = new int[]{bw, bh, ox, oy};
                        }
                        break;
                    }
                }
            }
        }
        if (best != null) return best;
        return new int[]{4, 8, (n - 4) / 2, Math.max(0, (n - 9) / 2)};
    }

    private static boolean fits(int n, int bw, int bh, int ox, int oy) {
        for (int y = 0; y < bh; y++) {
            for (int x = 0; x < bw; x++) {
                if (!isLed(n, ox + x, oy + y)) return false;
            }
        }
        for (int x = 0; x < bw; x++) {
            if (!isLed(n, ox + x, oy + bh)) return false; // 床
        }
        return true;
    }

    public int[] compose(TetrisEngine e, long nowMs) {
        int[] f = new int[n * n];
        TetrisEngine.State st = e.getState();
        boolean paused = e.isPaused();
        float dim = paused ? 0.35f : 1f;
        boolean flash = (nowMs / 80) % 2 == 0;

        // 床(左右の枠は無し)
        for (int x = 0; x < boardW; x++) {
            put(f, originX + x, originY + boardH, BR_FLOOR);
        }

        // 固定ブロック
        for (int y = 0; y < boardH; y++) {
            for (int x = 0; x < boardW; x++) {
                if (e.getCell(x, y) == 0) continue;
                int b = BR_LOCKED;
                if (st == TetrisEngine.State.CLEARING && e.isClearingRow(y)) {
                    b = flash ? BR_ACTIVE : 0;
                }
                put(f, originX + x, originY + y, (int) (b * dim));
            }
        }

        // ゴースト → 操作中ピース
        if (st == TetrisEngine.State.PLAYING) {
            int[][] ghost = e.ghostCells();
            if (ghost != null && !paused) {
                for (int[] c : ghost) {
                    if (c[1] >= 0 && e.getCell(c[0], c[1]) == 0) {
                        put(f, originX + c[0], originY + c[1], BR_GHOST);
                    }
                }
            }
            int[][] act = e.activeCells();
            if (act != null) {
                for (int[] c : act) {
                    if (c[1] >= 0) put(f, originX + c[0], originY + c[1], (int) (BR_ACTIVE * dim));
                }
            }
        }

        // NEXT(盤面の右側。置き場所が無い機種では出さない)
        if (nextX >= 0 && st != TetrisEngine.State.GAME_OVER) {
            for (int[] c : e.nextCells()) {
                put(f, nextX + c[0], nextY + c[1], (int) (BR_NEXT * dim));
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
