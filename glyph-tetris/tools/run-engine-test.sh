#!/bin/sh
# ゲームロジックの単体テスト(Android SDK不要、JDKのみ)
set -e
cd "$(dirname "$0")/.."
OUT=$(mktemp -d)
javac -d "$OUT" app/src/main/java/com/example/glyphtetris/TetrisEngine.java \
  app/src/main/java/com/example/glyphtetris/GlyphFrameComposer.java \
  tools/enginetest/com/example/glyphtetris/EngineSmokeTest.java
java -cp "$OUT" com.example.glyphtetris.EngineSmokeTest
