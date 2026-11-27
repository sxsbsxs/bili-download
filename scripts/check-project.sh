#!/usr/bin/env bash
set -euo pipefail

required_files=(
  "app/src/main/java/com/example/bilidl/BiliRepository.kt"
  "app/src/main/java/com/example/bilidl/BiliDownloadEngine.kt"
  "app/src/main/java/com/example/bilidl/common/content/BiliDanmakuProtoParser.kt"
  "app/src/main/cpp/mp4_inspector.cpp"
  "app/src/test/cpp/mp4_inspector_test.cpp"
  "docs/ARCHITECTURE.md"
  "docs/FEATURE_MATRIX.md"
)

for path in "${required_files[@]}"; do
  if [[ ! -s "$path" ]]; then
    echo "Required project file missing or empty: $path" >&2
    exit 1
  fi
done

if grep -Eiq 'yt-dlp|ffmpeg|aria2' app/build.gradle.kts; then
  echo "A prohibited generic downloader/media engine dependency was found." >&2
  exit 1
fi

echo "Repository structure and dependency checks passed."
