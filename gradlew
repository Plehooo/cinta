#!/usr/bin/env sh
# Lightweight bootstrap for Termux/Linux when the Gradle wrapper JAR is not shipped.
set -eu
PROJECT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
GRADLE_VERSION=8.13
if command -v gradle >/dev/null 2>&1; then
  exec gradle -p "$PROJECT_DIR" "$@"
fi
CACHE_DIR="${GRADLE_USER_HOME:-$HOME/.gradle}/bootstrap"
GRADLE_HOME="$CACHE_DIR/gradle-$GRADLE_VERSION"
if [ ! -x "$GRADLE_HOME/bin/gradle" ]; then
  mkdir -p "$CACHE_DIR"
  ARCHIVE="$CACHE_DIR/gradle-$GRADLE_VERSION-bin.zip"
  if ! command -v curl >/dev/null 2>&1; then echo "Install curl terlebih dahulu." >&2; exit 1; fi
  curl -fL --retry 3 "https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip" -o "$ARCHIVE"
  curl -fL --retry 3 "https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip.sha256" -o "$ARCHIVE.sha256"
  EXPECTED=$(tr -d '[:space:]' < "$ARCHIVE.sha256")
  ACTUAL=$(sha256sum "$ARCHIVE" | awk '{print $1}')
  if [ -z "$EXPECTED" ] || [ "$ACTUAL" != "$EXPECTED" ]; then
    echo "Checksum Gradle tidak cocok; arsip dibatalkan." >&2
    rm -f "$ARCHIVE" "$ARCHIVE.sha256"
    exit 1
  fi
  rm -f "$ARCHIVE.sha256"
  unzip -q -o "$ARCHIVE" -d "$CACHE_DIR"
fi
exec "$GRADLE_HOME/bin/gradle" -p "$PROJECT_DIR" "$@"
