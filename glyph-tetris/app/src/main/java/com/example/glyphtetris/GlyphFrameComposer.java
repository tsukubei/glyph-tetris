package com.example.glyphtetris;

/**
 * エンジンの状態をGlyph Matrix用の明るさ配列(0..255, 行優先 n*n)に変換する。
 * 実機への送信と、画面上のGlyph模擬ディスプレイの両方がこの配列を使うので、常に同じ絵になる。
 */
public final class GlyphFrameComposer {

    public static final int BR_WALL = 40;
    public static final int BR_LOCKED = 150;
    public static final int BR_ACTIVE = 255;
    public static final int BR_GHOST = 45;
    public static final int BR_NEXT = 110;

    public final int n;
    public final int boardW, boardH;
    public final int originX, originY;
    private final boolean showNext;

    public GlyphFrameComposer(int n) {
        this.n = n;
        int bw, bh, ox, oy;
        if (n >= 25) {
            bw = 10;
            bh = 20;
            ox = (n - bw) / 2;
            oy = (n - (bh + 1)) / 2;
            showNext = true;
        } else {
            int[] b = findBoard(n);
            bw = b[0];
            bh = b[1];
            ox = b[2];
            oy = b[3];
            showNext = false;
        }
        boardW = bw;
        boardH = bh;
        originX = ox;
        originY = oy;
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

    /** 小さいマトリクス用: 壁・床・盤面がすべて実在LEDに乗る最大の盤面を探す。 {幅, 高さ, originX, originY} */
    private static int[] findBoard(int n) {
        for (int bh = n - 2; bh >= 8; bh--) {
            for (int bw = 6; bw >= 4; bw--) {
                int ox = (n - bw) / 2;
                for (int oy = 0; oy + bh + 1 <= n; oy++) {
                    if (fits(n, bw, bh, ox, oy)) return new int[]{bw, bh, ox, oy};
                }
            }
        }
        return new int[]{4, 8, (n - 4) / 2, Math.max(0, (n - 9) / 2)};
    }

    private static boolean fits(int n, int bw, int bh, int ox, int oy) {
        for (int y = 0; y <= bh; y++) {
            if (!isLed(n, ox - 1, oy + y) || !isLed(n, ox + bw, oy + y)) return false;
        }
        for (int x = -1; x <= bw; x++) {
            if (!isLed(n, ox + x, oy + bh)) return false;
        }
        for (int y = 0; y < bh; y++) {
            for (int x = 0; x < bw; x++) {
                if (!isLed(n, ox + x, oy + y)) return false;
            }
        }
        return true;
    }

    public int[] compose(TetrisEngine e, long nowMs) {
        int[] f = new int[n * n];
        TetrisEngine.State st = e.getState();
        boolean paused = e.isPaused();
        float dim = paused ? 0.35f : 1f;
        boolean flash = (nowMs / 80) % 2 == 0;
        boolean overBlink = (nowMs / 450) % 2 == 0;

        // 壁と床
        for (int y = 0; y <= boardH; y++) {
            put(f, originX - 1, originY + y, BR_WALL);
            put(f, originX + boardW, originY + y, BR_WALL);
        }
        for (int x = -1; x <= boardW; x++) {
            put(f, originX + x, originY + boardH, BR_WALL);
        }

        // 固定ブロック
        for (int y = 0; y < boardH; y++) {
            for (int x = 0; x < boardW; x++) {
                if (e.getCell(x, y) == 0) continue;
                int b = BR_LOCKED;
                if (st == TetrisEngine.State.CLEARING && e.isClearingRow(y)) {
                    b = flash ? BR_ACTIVE : 0;
                } else if (st == TetrisEngine.State.GAME_OVER) {
                    b = overBlink ? BR_LOCKED : 50;
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

        // NEXT(右側)
        if (showNext && st != TetrisEngine.State.GAME_OVER) {
            int nx = originX + boardW + 2;
            int ny = originY + 3;
            for (int[] c : e.nextCells()) {
                put(f, nx + c[0], ny + c[1], (int) (BR_NEXT * dim));
            }
        }

        // 実在しないLEDを消す
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                if (!isLed(n, x, y)) f[y * n + x] = 0;
            }
        }
        return f;
    }

    private void put(int[] f, int x, int y, int v) {
        if (x < 0 || y < 0 || x >= n || y >= n) return;
        f[y * n + x] = Math.max(0, Math.min(255, v));
    }
}
