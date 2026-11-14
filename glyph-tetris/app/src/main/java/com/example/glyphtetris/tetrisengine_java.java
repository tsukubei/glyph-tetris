package com.example.glyphtetris;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * 円形Glyph全体(isLed(n, x, y))をプレイフィールドとして扱う自由形状テトリスエンジン。
 * - 長方形の枠は一切なく、円形LEDの外周(段差を含む)そのものが壁と床になる
 * - 各行 y の有効LEDがすべて埋まるとライン消去
 * - ソフトドロップは 45ms/マス の連続高速落下
 * - 着弾後の確定猶予は 1000ms(1.0秒)
 */
public final class TetrisEngine {

    public enum State { PLAYING, CLEARING, GAME_OVER }

    private static final long SOFT_DROP_INTERVAL_MS = 45L;
    private static final long LOCK_DELAY_MS = 1000L;
    private static final int MAX_LOCK_RESETS = 15;
    private static final long CLEAR_ANIM_MS = 280L;

    private static final int[][][][] SHAPES = {
        // I
        {{{0,1},{1,1},{2,1},{3,1}}, {{2,0},{2,1},{2,2},{2,3}}, {{0,2},{1,2},{2,2},{3,2}}, {{1,0},{1,1},{1,2},{1,3}}},
        // O
        {{{1,0},{2,0},{1,1},{2,1}}, {{1,0},{2,0},{1,1},{2,1}}, {{1,0},{2,0},{1,1},{2,1}}, {{1,0},{2,0},{1,1},{2,1}}},
        // T
        {{{1,0},{0,1},{1,1},{2,1}}, {{1,0},{1,1},{2,1},{1,2}}, {{0,1},{1,1},{2,1},{1,2}}, {{1,0},{0,1},{1,1},{1,2}}},
        // S
        {{{1,0},{2,0},{0,1},{1,1}}, {{1,0},{1,1},{2,1},{2,2}}, {{1,1},{2,1},{0,2},{1,2}}, {{0,0},{0,1},{1,1},{1,2}}},
        // Z
        {{{0,0},{1,0},{1,1},{2,1}}, {{2,0},{1,1},{2,1},{1,2}}, {{0,1},{1,1},{1,2},{2,2}}, {{1,0},{0,1},{1,1},{0,2}}},
        // J
        {{{0,0},{0,1},{1,1},{2,1}}, {{1,0},{2,0},{1,1},{1,2}}, {{0,1},{1,1},{2,1},{2,2}}, {{1,0},{1,1},{0,2},{1,2}}},
        // L
        {{{2,0},{0,1},{1,1},{2,1}}, {{1,0},{1,1},{1,2},{2,2}}, {{0,1},{1,1},{2,1},{0,2}}, {{0,0},{1,0},{1,1},{1,2}}}
    };

    private static final int[][][] NEXT_SHAPES = {
        {{0,0},{1,0},{2,0},{3,0}},
        {{1,0},{2,0},{1,1},{2,1}},
        {{1,0},{0,1},{1,1},{2,1}},
        {{1,0},{2,0},{0,1},{1,1}},
        {{0,0},{1,0},{1,1},{2,1}},
        {{0,0},{0,1},{1,1},{2,1}},
        {{2,0},{0,1},{1,1},{2,1}}
    };

    private final int n;
    private final int[][] grid;
    private final boolean[] clearingRows;
    private final Random random;
    private final List<Integer> bag = new ArrayList<>();

    private State state = State.PLAYING;
    private boolean paused = false;
    private boolean softDrop = false;

    private int curType;
    private int curRot;
    private int curX;
    private int curY;
    private int nextType;

    private long fallTimerMs = 0L;
    private long lockTimerMs = 0L;
    private int lockResets = 0;
    private long clearTimerMs = 0L;

    private int score = 0;
    private int lines = 0;
    private int level = 1;

    public TetrisEngine(int n, long seed) {
        this.n = n;
        this.grid = new int[n][n];
        this.clearingRows = new boolean[n];
        this.random = new Random(seed);
        reset();
    }

    /** 既存の MainActivity(composer.boardW, composer.boardH, seed) 呼び出しとの互換用。 */
    public TetrisEngine(int width, int height, long seed) {
        this(width, seed);
    }

    public void reset() {
        for (int y = 0; y < n; y++) {
            clearingRows[y] = false;
            for (int x = 0; x < n; x++) grid[y][x] = 0;
        }
        bag.clear();
        score = 0;
        lines = 0;
        level = 1;
        paused = false;
        softDrop = false;
        state = State.PLAYING;
        nextType = drawFromBag();
        spawnNext();
    }

    public void update(long dtMs) {
        if (paused || state == State.GAME_OVER) return;

        if (state == State.CLEARING) {
            clearTimerMs += dtMs;
            if (clearTimerMs >= CLEAR_ANIM_MS) {
                finishClearingRows();
                state = State.PLAYING;
                spawnNext();
            }
            return;
        }

        long interval = softDrop
                ? Math.min(SOFT_DROP_INTERVAL_MS, normalDropIntervalMs())
                : normalDropIntervalMs();

        fallTimerMs += dtMs;
        while (fallTimerMs >= interval) {
            fallTimerMs -= interval;
            if (canPlace(curType, curRot, curX, curY + 1)) {
                curY++;
                if (softDrop) score += 1;
            } else {
                fallTimerMs = 0L;
                break;
            }
        }

        if (!canPlace(curType, curRot, curX, curY + 1)) {
            lockTimerMs += dtMs;
            if (lockTimerMs >= LOCK_DELAY_MS) {
                lockPiece();
            }
        } else {
            lockTimerMs = 0L;
        }
    }

    private long normalDropIntervalMs() {
        return Math.max(90L, 800L - (level - 1) * 65L);
    }

    public void setSoftDrop(boolean active) {
        if (this.softDrop == active) return;
        this.softDrop = active;
        if (active && !paused && state == State.PLAYING) {
            if (canPlace(curType, curRot, curX, curY + 1)) {
                curY++;
                score += 1;
                fallTimerMs = 0L;
            }
        }
    }

    public void moveLeft() {
        if (paused || state != State.PLAYING) return;
        if (canPlace(curType, curRot, curX - 1, curY)) {
            curX--;
            onGroundMoved();
        }
    }

    public void moveRight() {
        if (paused || state != State.PLAYING) return;
        if (canPlace(curType, curRot, curX + 1, curY)) {
            curX++;
            onGroundMoved();
        }
    }

    public void rotateCw() { rotate(1); }
    public void rotateCcw() { rotate(3); }

    private void rotate(int delta) {
        if (paused || state != State.PLAYING) return;
        int nextRot = (curRot + delta) % 4;
        // 円形の段差壁・床付近でも回転しやすいようキック候補を広めに試す
        int[][] kicks = {
            {0, 0}, {-1, 0}, {1, 0}, {0, -1},
            {-2, 0}, {2, 0}, {-1, -1}, {1, -1}, {0, -2}
        };
        for (int[] k : kicks) {
            if (canPlace(curType, nextRot, curX + k[0], curY + k[1])) {
                curRot = nextRot;
                curX += k[0];
                curY += k[1];
                onGroundMoved();
                return;
            }
        }
    }

    private void onGroundMoved() {
        if (lockResets < MAX_LOCK_RESETS) {
            lockTimerMs = 0L;
            lockResets++;
        }
    }

    public void hardDrop() {
        if (paused || state != State.PLAYING) return;
        int dropped = 0;
        while (canPlace(curType, curRot, curX, curY + 1)) {
            curY++;
            dropped++;
        }
        score += dropped * 2;
        lockPiece();
    }

    private void lockPiece() {
        for (int[] c : SHAPES[curType][curRot]) {
            int gx = curX + c[0];
            int gy = curY + c[1];
            if (gy < 0) {
                state = State.GAME_OVER;
                return;
            }
            if (GlyphFrameComposer.isLed(n, gx, gy)) {
                grid[gy][gx] = curType + 1;
            }
        }

        // 各行の「実在LED」がすべて埋まっているか判定
        int fullCount = 0;
        for (int y = 0; y < n; y++) {
            int ledCount = 0;
            boolean full = true;
            for (int x = 0; x < n; x++) {
                if (!GlyphFrameComposer.isLed(n, x, y)) continue;
                ledCount++;
                if (grid[y][x] == 0) {
                    full = false;
                    break;
                }
            }
            clearingRows[y] = (ledCount > 0 && full);
            if (clearingRows[y]) fullCount++;
        }

        if (fullCount > 0) {
            state = State.CLEARING;
            clearTimerMs = 0L;
            int[] pts = {0, 100, 300, 500, 800};
            score += pts[Math.min(4, fullCount)] * level;
            lines += fullCount;
            level = 1 + (lines / 10);
        } else {
            spawnNext();
        }
    }

    /**
     * 円形の下半分では下の行ほど横幅が狭くなるため、
     * 消去後にブロックが円の外(非LED)へ落ちないよう有効LED範囲内で着地させる。
     */
    private void finishClearingRows() {
        int[][] nextGrid = new int[n][n];
        int shift = 0;
        for (int readY = n - 1; readY >= 0; readY--) {
            if (clearingRows[readY]) {
                shift++;
                clearingRows[readY] = false;
                continue;
            }
            int idealY = readY + shift;
            for (int x = 0; x < n; x++) {
                int val = grid[readY][x];
                if (val == 0) continue;
                int targetY = idealY;
                while (targetY > readY && (!GlyphFrameComposer.isLed(n, x, targetY) || nextGrid[targetY][x] != 0)) {
                    targetY--;
                }
                if (GlyphFrameComposer.isLed(n, x, targetY)) {
                    nextGrid[targetY][x] = val;
                }
            }
        }
        for (int y = 0; y < n; y++) {
            System.arraycopy(nextGrid[y], 0, grid[y], 0, n);
        }
    }

    private void spawnNext() {
        curType = nextType;
        nextType = drawFromBag();
        curRot = 0;
        curX = (n - 4) / 2;
        curY = 0;
        fallTimerMs = 0L;
        lockTimerMs = 0L;
        lockResets = 0;

        if (!canPlace(curType, curRot, curX, curY)) {
            state = State.GAME_OVER;
        }
    }

    /**
     * 長方形の壁・床判定は一切使わず、isLed(n, gx, gy) の円形境界だけで判定する。
     */
    private boolean canPlace(int type, int rot, int px, int py) {
        for (int[] c : SHAPES[type][rot]) {
            int gx = px + c[0];
            int gy = py + c[1];
            if (gy < 0) {
                if (gx < 0 || gx >= n) return false;
                continue;
            }
            if (!GlyphFrameComposer.isLed(n, gx, gy)) return false;
            if (grid[gy][gx] != 0) return false;
        }
        return true;
    }

    private int drawFromBag() {
        if (bag.isEmpty()) {
            for (int i = 0; i < 7; i++) bag.add(i);
            Collections.shuffle(bag, random);
        }
        return bag.remove(bag.size() - 1);
    }

    public State getState() { return state; }
    public boolean isPaused() { return paused; }
    public void setPaused(boolean p) { this.paused = p; }
    public void togglePause() { if (state != State.GAME_OVER) this.paused = !this.paused; }
    public int getScore() { return score; }
    public int getLines() { return lines; }
    public int getLevel() { return level; }
    public int getNextType() { return nextType; }
    public int getCell(int x, int y) { return grid[y][x]; }
    public boolean isClearingRow(int y) { return clearingRows[y]; }

    /** 着弾後の確定猶予ゲージの進行度 (0.0f .. 1.0f)。 */
    public float getLockProgress() {
        if (state != State.PLAYING || lockTimerMs <= 0L) return 0f;
        return Math.min(1f, (float) lockTimerMs / (float) LOCK_DELAY_MS);
    }

    public int[][] activeCells() {
        if (state != State.PLAYING) return null;
        int[][] raw = SHAPES[curType][curRot];
        int[][] out = new int[4][2];
        for (int i = 0; i < 4; i++) {
            out[i][0] = curX + raw[i][0];
            out[i][1] = curY + raw[i][1];
        }
        return out;
    }

    public int[][] ghostCells() {
        if (state != State.PLAYING) return null;
        int gy = curY;
        while (canPlace(curType, curRot, curX, gy + 1)) gy++;
        int[][] raw = SHAPES[curType][curRot];
        int[][] out = new int[4][2];
        for (int i = 0; i < 4; i++) {
            out[i][0] = curX + raw[i][0];
            out[i][1] = gy + raw[i][1];
        }
        return out;
    }

    public int[][] nextCells() {
        return NEXT_SHAPES[nextType];
    }
}