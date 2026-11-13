package com.example.glyphtetris;

import java.util.Arrays;
import java.util.Random;

/**
 * Android非依存のテトリスエンジン。
 * SRS回転・ウォールキック・7-bag・ロックディレイ・ライン消去演出に対応。
 * 座標は左上原点、yは下向き。
 */
public final class TetrisEngine {

    public enum State { PLAYING, CLEARING, GAME_OVER }

    public static final int EMPTY = 0, I = 1, O = 2, T = 3, S = 4, Z = 5, J = 6, L = 7;

    public static final long CLEAR_MS = 320;
    private static final long LOCK_MS = 500;
    private static final long SOFT_DROP_MS = 45;
    private static final int MAX_LOCK_RESETS = 15;
    private static final int[] LINE_SCORE = {0, 100, 300, 500, 800};

    /** SRSのスポーン姿勢(I/Oは4x4、他は3x3のボックス内座標 {x, y})。 */
    private static final int[][][] BASE = {
            null,
            {{0, 1}, {1, 1}, {2, 1}, {3, 1}}, // I
            {{1, 0}, {2, 0}, {1, 1}, {2, 1}}, // O
            {{1, 0}, {0, 1}, {1, 1}, {2, 1}}, // T
            {{1, 0}, {2, 0}, {0, 1}, {1, 1}}, // S
            {{0, 0}, {1, 0}, {1, 1}, {2, 1}}, // Z
            {{0, 0}, {0, 1}, {1, 1}, {2, 1}}, // J
            {{2, 0}, {0, 1}, {1, 1}, {2, 1}}, // L
    };

    /** SHAPES[type][rotation] = 4セル。 */
    private static final int[][][][] SHAPES = new int[8][4][][];

    static {
        for (int t = 1; t <= 7; t++) {
            int n = boxSize(t);
            SHAPES[t][0] = BASE[t];
            for (int r = 1; r < 4; r++) {
                SHAPES[t][r] = rotateCw(SHAPES[t][r - 1], n);
            }
        }
    }

    // ウォールキック表(wiki記載のy-up表記 {dx, dy})。使用時にyを反転する。
    // 順序: 0>1, 1>0, 1>2, 2>1, 2>3, 3>2, 3>0, 0>3
    private static final int[][][] KICK_JLSTZ = {
            {{0, 0}, {-1, 0}, {-1, 1}, {0, -2}, {-1, -2}},
            {{0, 0}, {1, 0}, {1, -1}, {0, 2}, {1, 2}},
            {{0, 0}, {1, 0}, {1, -1}, {0, 2}, {1, 2}},
            {{0, 0}, {-1, 0}, {-1, 1}, {0, -2}, {-1, -2}},
            {{0, 0}, {1, 0}, {1, 1}, {0, -2}, {1, -2}},
            {{0, 0}, {-1, 0}, {-1, -1}, {0, 2}, {-1, 2}},
            {{0, 0}, {-1, 0}, {-1, -1}, {0, 2}, {-1, 2}},
            {{0, 0}, {1, 0}, {1, 1}, {0, -2}, {1, -2}},
    };
    private static final int[][][] KICK_I = {
            {{0, 0}, {-2, 0}, {1, 0}, {-2, -1}, {1, 2}},
            {{0, 0}, {2, 0}, {-1, 0}, {2, 1}, {-1, -2}},
            {{0, 0}, {-1, 0}, {2, 0}, {-1, 2}, {2, -1}},
            {{0, 0}, {1, 0}, {-2, 0}, {1, -2}, {-2, 1}},
            {{0, 0}, {2, 0}, {-1, 0}, {2, 1}, {-1, -2}},
            {{0, 0}, {-2, 0}, {1, 0}, {-2, -1}, {1, 2}},
            {{0, 0}, {1, 0}, {-2, 0}, {1, -2}, {-2, 1}},
            {{0, 0}, {-1, 0}, {2, 0}, {-1, 2}, {2, -1}},
    };

    private static int boxSize(int type) {
        return (type == I || type == O) ? 4 : 3;
    }

    private static int[][] rotateCw(int[][] cells, int n) {
        int[][] out = new int[cells.length][2];
        for (int i = 0; i < cells.length; i++) {
            out[i][0] = n - 1 - cells[i][1];
            out[i][1] = cells[i][0];
        }
        return out;
    }

    private static int kickIndex(int from, int to) {
        if (from == 0 && to == 1) return 0;
        if (from == 1 && to == 0) return 1;
        if (from == 1 && to == 2) return 2;
        if (from == 2 && to == 1) return 3;
        if (from == 2 && to == 3) return 4;
        if (from == 3 && to == 2) return 5;
        if (from == 3 && to == 0) return 6;
        return 7; // 0>3
    }

    private final int w, h;
    private final Random rnd;
    private final boolean[] clearRows;
    private final int[] bag = new int[7];
    private int bagPos = 7;

    private int[][] grid;
    private State state = State.PLAYING;
    private boolean paused;
    private boolean softDrop;

    private int curType, curRot, px, py, nextType;
    private long gravityAcc, lockTimer, clearTimer;
    private int lockResets, pendingClear;
    private int score, lines, level;

    public TetrisEngine(int width, int height, long seed) {
        this.w = width;
        this.h = height;
        this.rnd = new Random(seed);
        this.clearRows = new boolean[height];
        reset();
    }

    public void reset() {
        grid = new int[h][w];
        Arrays.fill(clearRows, false);
        state = State.PLAYING;
        paused = false;
        softDrop = false;
        score = 0;
        lines = 0;
        level = 1;
        pendingClear = 0;
        clearTimer = 0;
        bagPos = 7;
        nextType = nextFromBag();
        spawn();
    }

    // ---------------------------------------------------------------- 入力

    public boolean moveLeft() {
        return move(-1);
    }

    public boolean moveRight() {
        return move(1);
    }

    private boolean move(int dx) {
        if (!canControl()) return false;
        if (!fits(curType, curRot, px + dx, py)) return false;
        px += dx;
        onPieceMoved();
        return true;
    }

    public boolean rotateCw() {
        return rotate(1);
    }

    public boolean rotateCcw() {
        return rotate(-1);
    }

    private boolean rotate(int dir) {
        if (!canControl() || curType == O) return false;
        int to = (curRot + dir + 4) % 4;
        int[][][] table = (curType == I) ? KICK_I : KICK_JLSTZ;
        int[][] kicks = table[kickIndex(curRot, to)];
        for (int[] k : kicks) {
            int nx = px + k[0];
            int ny = py - k[1]; // y-up表 → y-down
            if (fits(curType, to, nx, ny)) {
                curRot = to;
                px = nx;
                py = ny;
                onPieceMoved();
                return true;
            }
        }
        return false;
    }

    public void setSoftDrop(boolean on) {
        softDrop = on;
    }

    public void hardDrop() {
        if (!canControl()) return;
        int d = ghostDrop();
        py += d;
        score += 2 * d;
        lockPiece();
    }

    public void setPaused(boolean p) {
        if (state == State.GAME_OVER) return;
        paused = p;
        if (p) softDrop = false;
    }

    public void togglePause() {
        setPaused(!paused);
    }

    // ---------------------------------------------------------------- 時間経過

    public void update(long dtMs) {
        if (paused || state == State.GAME_OVER) return;
        if (state == State.CLEARING) {
            clearTimer += dtMs;
            if (clearTimer >= CLEAR_MS) finishClear();
            return;
        }
        if (curType == EMPTY) return;

        if (isGrounded()) {
            gravityAcc = 0;
            lockTimer += dtMs;
            if (lockTimer >= LOCK_MS) lockPiece();
            return;
        }
        long interval = gravityMs();
        if (softDrop) interval = Math.min(interval, SOFT_DROP_MS);
        gravityAcc += dtMs;
        while (gravityAcc >= interval && !isGrounded()) {
            py++;
            gravityAcc -= interval;
            if (softDrop) score += 1;
        }
        if (isGrounded()) {
            gravityAcc = 0;
            lockTimer = 0;
        }
    }

    private long gravityMs() {
        double sec = Math.pow(0.8 - (level - 1) * 0.007, level - 1);
        return Math.max(30L, (long) (sec * 1000));
    }

    // ---------------------------------------------------------------- 内部処理

    private boolean canControl() {
        return state == State.PLAYING && !paused && curType != EMPTY;
    }

    private boolean fits(int type, int rot, int x0, int y0) {
        for (int[] c : SHAPES[type][rot]) {
            int x = x0 + c[0];
            int y = y0 + c[1];
            if (x < 0 || x >= w || y >= h) return false;
            if (y >= 0 && grid[y][x] != 0) return false;
        }
        return true;
    }

    private boolean isGrounded() {
        return !fits(curType, curRot, px, py + 1);
    }

    private void onPieceMoved() {
        if (isGrounded()) {
            if (lockResets < MAX_LOCK_RESETS) {
                lockTimer = 0;
                lockResets++;
            }
        } else {
            lockTimer = 0;
        }
    }

    private int ghostDrop() {
        int d = 0;
        while (fits(curType, curRot, px, py + d + 1)) d++;
        return d;
    }

    private void spawn() {
        curType = nextType;
        nextType = nextFromBag();
        curRot = 0;
        int n = boxSize(curType);
        px = (w - n) / 2;
        int minY = 99;
        for (int[] c : SHAPES[curType][0]) minY = Math.min(minY, c[1]);
        py = -minY;
        gravityAcc = 0;
        lockTimer = 0;
        lockResets = 0;
        if (!fits(curType, 0, px, py)) {
            state = State.GAME_OVER;
        }
    }

    private void lockPiece() {
        boolean overflow = false;
        for (int[] c : SHAPES[curType][curRot]) {
            int x = px + c[0];
            int y = py + c[1];
            if (y < 0) {
                overflow = true;
                continue;
            }
            grid[y][x] = curType;
        }
        curType = EMPTY;
        softDrop = softDrop && !overflow;
        if (overflow) {
            state = State.GAME_OVER;
            return;
        }
        int n = 0;
        for (int y = 0; y < h; y++) {
            boolean full = true;
            for (int x = 0; x < w; x++) {
                if (grid[y][x] == 0) {
                    full = false;
                    break;
                }
            }
            clearRows[y] = full;
            if (full) n++;
        }
        if (n > 0) {
            pendingClear = n;
            clearTimer = 0;
            state = State.CLEARING;
        } else {
            spawn();
        }
    }

    private void finishClear() {
        int[][] ng = new int[h][w];
        int dst = h - 1;
        for (int src = h - 1; src >= 0; src--) {
            if (clearRows[src]) continue;
            ng[dst--] = grid[src];
        }
        grid = ng;
        int n = Math.min(pendingClear, 4);
        lines += pendingClear;
        score += LINE_SCORE[n] * level;
        level = Math.min(15, lines / 10 + 1);
        pendingClear = 0;
        Arrays.fill(clearRows, false);
        state = State.PLAYING;
        spawn();
    }

    private int nextFromBag() {
        if (bagPos >= 7) {
            for (int i = 0; i < 7; i++) bag[i] = i + 1;
            for (int i = 6; i > 0; i--) {
                int j = rnd.nextInt(i + 1);
                int tmp = bag[i];
                bag[i] = bag[j];
                bag[j] = tmp;
            }
            bagPos = 0;
        }
        return bag[bagPos++];
    }

    // ---------------------------------------------------------------- 描画用の参照

    public int getWidth() { return w; }
    public int getHeight() { return h; }
    public State getState() { return state; }
    public boolean isPaused() { return paused; }
    public int getScore() { return score; }
    public int getLines() { return lines; }
    public int getLevel() { return level; }

    /** 固定済みセル(0=空, 1..7=ピース種別)。 */
    public int getCell(int x, int y) {
        return grid[y][x];
    }

    public boolean isClearingRow(int y) {
        return clearRows[y];
    }

    /** 操作中ピースの絶対座標(4セル)。ピースが無い間はnull。yは負になりうる。 */
    public int[][] activeCells() {
        if (curType == EMPTY) return null;
        return absolute(curType, curRot, px, py);
    }

    /** 落下予測位置(ゴースト)。 */
    public int[][] ghostCells() {
        if (curType == EMPTY) return null;
        return absolute(curType, curRot, px, py + ghostDrop());
    }

    /** NEXTピースを左上(0,0)基準の4x4に正規化したセル。 */
    public int[][] nextCells() {
        int[][] src = SHAPES[nextType][0];
        int minX = 99, minY = 99;
        for (int[] c : src) {
            minX = Math.min(minX, c[0]);
            minY = Math.min(minY, c[1]);
        }
        int[][] out = new int[4][2];
        for (int i = 0; i < 4; i++) {
            out[i][0] = src[i][0] - minX;
            out[i][1] = src[i][1] - minY;
        }
        return out;
    }

    private static int[][] absolute(int type, int rot, int x0, int y0) {
        int[][] out = new int[4][2];
        for (int i = 0; i < 4; i++) {
            out[i][0] = x0 + SHAPES[type][rot][i][0];
            out[i][1] = y0 + SHAPES[type][rot][i][1];
        }
        return out;
    }

    // ---------------------------------------------------------------- テスト用

    int[][] gridForTest() {
        return grid;
    }

    void forcePieceForTest(int type, int x, int y, int rot) {
        curType = type;
        px = x;
        py = y;
        curRot = rot;
        lockTimer = 0;
        gravityAcc = 0;
        lockResets = 0;
    }
}
