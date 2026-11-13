package com.example.glyphtetris;

import java.util.Arrays;
import java.util.Random;

public class EngineSmokeTest {
    static int fails = 0;

    static void check(boolean c, String m) {
        if (!c) fails++;
        System.out.println((c ? "ok:   " : "FAIL: ") + m);
    }

    static String key(int[][] cells) {
        int[][] c = new int[cells.length][];
        for (int i = 0; i < c.length; i++) c[i] = cells[i].clone();
        Arrays.sort(c, (a, b) -> a[1] != b[1] ? a[1] - b[1] : a[0] - b[0]);
        return Arrays.deepToString(c);
    }

    public static void main(String[] args) {
        // 1) 4回転で元に戻る(全ピース、空き地)
        for (int t = 1; t <= 7; t++) {
            TetrisEngine e = new TetrisEngine(10, 20, 1);
            e.forcePieceForTest(t, 3, 8, 0);
            String before = key(e.activeCells());
            for (int i = 0; i < 4; i++) e.rotateCw();
            check(before.equals(key(e.activeCells())), "type " + t + ": CW x4 returns to start");
            for (int i = 0; i < 4; i++) e.rotateCcw();
            check(before.equals(key(e.activeCells())), "type " + t + ": CCW x4 returns to start");
        }

        // 2) 壁際でもIが縦に回れる(ウォールキック)
        {
            TetrisEngine e = new TetrisEngine(10, 20, 1);
            e.forcePieceForTest(TetrisEngine.I, 3, 8, 0);
            for (int i = 0; i < 6; i++) e.moveLeft();
            boolean r = e.rotateCw();
            int minX = 99;
            for (int[] c : e.activeCells()) minX = Math.min(minX, c[0]);
            check(r && minX >= 0, "I piece rotates at left wall via kick (minX=" + minX + ")");
        }

        // 3) 1ライン消し
        {
            TetrisEngine e = new TetrisEngine(10, 20, 1);
            int[][] g = e.gridForTest();
            for (int x = 0; x < 10; x++) if (x < 3 || x > 6) g[19][x] = 1;
            e.forcePieceForTest(TetrisEngine.I, 3, 18, 0);
            e.hardDrop();
            check(e.getState() == TetrisEngine.State.CLEARING, "full row enters CLEARING");
            check(e.isClearingRow(19), "row 19 flagged");
            e.update(TetrisEngine.CLEAR_MS + 10);
            boolean empty = true;
            for (int x = 0; x < 10; x++) if (e.getCell(x, 19) != 0) empty = false;
            check(e.getState() == TetrisEngine.State.PLAYING && empty, "row removed, back to PLAYING");
            check(e.getLines() == 1 && e.getScore() == 100, "lines=1 score=100 (got " + e.getLines() + "/" + e.getScore() + ")");
        }

        // 4) テトリス(4ライン)=800
        {
            TetrisEngine e = new TetrisEngine(10, 20, 1);
            int[][] g = e.gridForTest();
            for (int y = 16; y < 20; y++) for (int x = 1; x < 10; x++) g[y][x] = 2;
            e.forcePieceForTest(TetrisEngine.I, -2, 16, 1);
            check(Arrays.deepToString(e.activeCells()).contains("[0, 16]"), "vertical I sits in column 0");
            e.hardDrop();
            e.update(TetrisEngine.CLEAR_MS + 10);
            check(e.getLines() == 4 && e.getScore() == 800, "tetris: lines=4 score=800 (got " + e.getLines() + "/" + e.getScore() + ")");
            boolean allEmpty = true;
            for (int y = 0; y < 20; y++) for (int x = 0; x < 10; x++) if (e.getCell(x, y) != 0) allEmpty = false;
            check(allEmpty, "board empty after tetris");
        }

        // 5) ランダム操作ファズ(例外・重なり・範囲外が無いこと)
        {
            int overs = 0;
            boolean ok = true;
            for (long seed = 1; seed <= 40 && ok; seed++) {
                TetrisEngine e = new TetrisEngine(10, 20, seed);
                Random r = new Random(seed * 7);
                for (int step = 0; step < 30000; step++) {
                    switch (r.nextInt(8)) {
                        case 0: e.moveLeft(); break;
                        case 1: e.moveRight(); break;
                        case 2: e.rotateCw(); break;
                        case 3: e.rotateCcw(); break;
                        case 4: if (r.nextInt(6) == 0) e.hardDrop(); break;
                        case 5: e.setSoftDrop(r.nextBoolean()); break;
                        default: e.update(16 + r.nextInt(60));
                    }
                    if (e.getState() == TetrisEngine.State.PLAYING) {
                        int[][] a = e.activeCells();
                        if (a != null) for (int[] c : a) {
                            if (c[0] < 0 || c[0] >= 10 || c[1] >= 20 || (c[1] >= 0 && e.getCell(c[0], c[1]) != 0)) {
                                ok = false;
                                System.out.println("invariant broken seed=" + seed + " step=" + step + " " + Arrays.deepToString(a));
                            }
                        }
                    }
                    if (e.getState() == TetrisEngine.State.GAME_OVER) {
                        overs++;
                        e.reset();
                    }
                }
            }
            check(ok, "fuzz: 40 seeds x 30000 steps, no invariant violation (game overs seen: " + overs + ")");
        }

        // 6) コンポーザ: LED数・盤面がマスク内に収まる
        {
            int led = 0;
            for (int y = 0; y < 25; y++) for (int x = 0; x < 25; x++) if (GlyphFrameComposer.isLed(25, x, y)) led++;
            System.out.println("info: LED count in 25x25 mask = " + led);

            GlyphFrameComposer c = new GlyphFrameComposer(25);
            TetrisEngine e = new TetrisEngine(c.boardW, c.boardH, 3);
            int[][] g = e.gridForTest();
            for (int y = 0; y < c.boardH; y++) for (int x = 0; x < c.boardW; x++) g[y][x] = 1;
            int[] f = c.compose(e, 0);
            check(f.length == 625, "frame length 625");
            boolean allShown = true;
            for (int y = 0; y < c.boardH; y++)
                for (int x = 0; x < c.boardW; x++)
                    if (f[(c.originY + y) * 25 + c.originX + x] == 0) allShown = false;
            check(allShown, "all 10x20 board cells land on real LEDs");
            boolean wallsShown = true;
            for (int y = 0; y <= c.boardH; y++) {
                if (f[(c.originY + y) * 25 + c.originX - 1] == 0) wallsShown = false;
                if (f[(c.originY + y) * 25 + c.originX + c.boardW] == 0) wallsShown = false;
            }
            for (int x = -1; x <= c.boardW; x++) if (f[(c.originY + c.boardH) * 25 + c.originX + x] == 0) wallsShown = false;
            check(wallsShown, "walls and floor land on real LEDs");

            TetrisEngine e2 = new TetrisEngine(c.boardW, c.boardH, 3);
            e2.update(1200);
            int[] f2 = c.compose(e2, 0);
            StringBuilder sb = new StringBuilder();
            for (int y = 0; y < 25; y++) {
                for (int x = 0; x < 25; x++) {
                    int v = f2[y * 25 + x];
                    char ch = !GlyphFrameComposer.isLed(25, x, y) ? ' ' : v == 0 ? '.' : v >= 200 ? '#' : v >= 120 ? 'o' : '+';
                    sb.append(ch);
                }
                sb.append('\n');
            }
            System.out.print(sb);

            int led13 = 0;
            for (int y = 0; y < 13; y++) for (int x = 0; x < 13; x++) if (GlyphFrameComposer.isLed(13, x, y)) led13++;
            check(led13 == 137, "13x13 mask has 137 LEDs (got " + led13 + ")");
            check(led == 489, "25x25 mask has 489 LEDs (got " + led + ")");

            GlyphFrameComposer c13 = new GlyphFrameComposer(13);
            boolean fit13 = true;
            for (int y = 0; y <= c13.boardH; y++) {
                if (!GlyphFrameComposer.isLed(13, c13.originX - 1, c13.originY + y)) fit13 = false;
                if (!GlyphFrameComposer.isLed(13, c13.originX + c13.boardW, c13.originY + y)) fit13 = false;
            }
            for (int x = -1; x <= c13.boardW; x++) if (!GlyphFrameComposer.isLed(13, c13.originX + x, c13.originY + c13.boardH)) fit13 = false;
            check(fit13, "13x13 walls/floor all on real LEDs (board " + c13.boardW + "x" + c13.boardH
                    + " at " + c13.originX + "," + c13.originY + ")");
            TetrisEngine e13 = new TetrisEngine(c13.boardW, c13.boardH, 5);
            int[] f13 = c13.compose(e13, 0);
            check(f13.length == 169, "13x13 frame length 169");
        }

        System.out.println(fails == 0 ? "ALL PASSED" : (fails + " FAILED"));
        System.exit(fails == 0 ? 0 : 1);
    }
}
