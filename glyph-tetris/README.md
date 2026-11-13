# Glyph Tetris

Nothing Phone の Glyph Matrix で遊ぶテトリス。画面の上半分に Glyph を模した表示、下半分に操作ボタンがあり、
**同じフレームを模擬表示と実機 Glyph の両方に送る**ので、常に同じ絵になります。

- 対応: Phone (3)(25×25 / 盤面 10×20 + NEXT 表示)、Phone (4a) Pro(13×13 / 盤面 6×10。**実機未確認**)
- Glyph が無い端末では、画面の模擬表示だけで動きます(動作確認用)
- 操作: `← →`(長押しで連続移動)、`↺ ↻` 回転、`↓ SOFT` ソフトドロップ(長押し)、`⇊ DROP` ハードドロップ
- ルール: SRS 回転(ウォールキック付き)、7-bag、ゴースト表示、ライン消去の点滅、レベル上昇

## APK の作り方

### A. GitHub Actions(おすすめ・PC不要)

1. GitHub で新しいリポジトリを作り、このフォルダの中身をそのまま push する
2. リポジトリの **Actions** タブ → **Build APK** が自動で走る(`workflow_dispatch` で手動実行も可)
3. 完了後、実行結果の **Artifacts** から `glyph-tetris-debug-apk` をダウンロード → 中の `app-debug.apk` を端末に入れて、提供元不明アプリのインストールを許可して開く

SDK(`glyph-matrix-sdk-2.0.aar`)は、再配布が禁止されているためこのプロジェクトには含めていません。
ワークフローがビルド時に Nothing 公式リポジトリから取得します。

### B. Android Studio

1. [公式リポジトリ](https://github.com/Nothing-Developer-Programme/GlyphMatrix-Developer-Kit)から `glyph-matrix-sdk-2.0.aar` を入手し、`app/libs/` に置く
2. Android Studio でこのフォルダを開く(JDK 17)
3. `Build > Build Bundle(s) / APK(s) > Build APK(s)` → `app/build/outputs/apk/debug/app-debug.apk`

生成される APK は debug 署名で、そのままインストールできます(個人利用向け)。

## 仕組み

| ファイル | 役割 |
|---|---|
| `TetrisEngine.java` | Android 非依存のゲームロジック(単体テスト済み) |
| `GlyphFrameComposer.java` | ゲーム状態 → 明るさ配列(0〜255)。実機と模擬表示の共通フレーム |
| `GlyphBridge.java` | SDK との接続・送信。専用スレッドで最新フレームだけ送る |
| `GlyphPreviewView.java` | Glyph を模した表示(円形の LED 配列) |
| `MainActivity.java` | 画面構築・入力・ゲームループ |

送信は最大およそ30fps、変化が無くても1秒ごとに再送します。
アプリ用の `setAppMatrixFrame` を使うため、Glyph Toy ではなく通常のアプリとして動きます。

## 注意点

- **システムバージョン `20250801` 以降が必要**です(`setAppMatrixFrame` の要件)。古いと「Glyph登録に失敗」と表示されます
- Glyph ボタンを押すと Glyph Toy のカルーセルが優先され、アプリの表示は上書きされます(SDK の仕様)
- 一定時間で Glyph が消灯するという[報告](https://github.com/Nothing-Developer-Programme/GlyphMatrix-Example-Project/issues/5)があります。対策として1秒ごとの再送を入れていますが、効果は実機で確認してください
- Nothing 公式 SDK のライセンス上、**商用利用は不可**です
