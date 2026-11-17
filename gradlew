#!/usr/bin/env sh
set -eu
VERSION="9.6.0"
if command -v gradle >/dev/null 2>&1 && gradle --version 2>/dev/null | grep -q "Gradle $VERSION"; then exec gradle "$@"; fi
BASE="${GRADLE_USER_HOME:-$HOME/.gradle}/mbox-bootstrap"
HOME_DIR="$BASE/gradle-$VERSION"
if [ ! -x "$HOME_DIR/bin/gradle" ]; then
  mkdir -p "$BASE"
  ZIP="$BASE/gradle-$VERSION-bin.zip"
  if [ ! -f "$ZIP" ]; then
    command -v curl >/dev/null 2>&1 || { echo 'curl is required to download Gradle.' >&2; exit 1; }
    curl -fL "https://services.gradle.org/distributions/gradle-$VERSION-bin.zip" -o "$ZIP"
  fi
  command -v unzip >/dev/null 2>&1 || { echo 'unzip is required to extract Gradle.' >&2; exit 1; }
  unzip -q -o "$ZIP" -d "$BASE"
fi
exec "$HOME_DIR/bin/gradle" "$@"
