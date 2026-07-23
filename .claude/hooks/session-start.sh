#!/bin/bash
set -euo pipefail

echo '{"async": true, "asyncTimeout": 300000}'

# Only run in remote (Claude Code on the web) environments
if [ "${CLAUDE_CODE_REMOTE:-}" != "true" ]; then
  exit 0
fi

ANDROID_SDK_DIR="/opt/android-sdk"
CMDLINE_TOOLS_URL="https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
CMDLINE_TOOLS_ZIP="/tmp/cmdline-tools.zip"

# ScottPipe (NewPipe fork) requires compileSdk 37.0 / targetSdk 35 / minSdk 23,
# per buildSrc/src/main/kotlin/ProjectConfig.kt — do not copy the API 36 values
# used by other repos' session-start.sh without checking ProjectConfig.kt first.
PLATFORM="platforms;android-37.0"
BUILD_TOOLS="build-tools;37.0.0"

# Skip if SDK already installed
if [ -x "$ANDROID_SDK_DIR/cmdline-tools/latest/bin/sdkmanager" ]; then
  echo "Android SDK already installed, skipping."
  echo "sdk.dir=$ANDROID_SDK_DIR" > "$CLAUDE_PROJECT_DIR/local.properties"
  echo "export ANDROID_HOME=$ANDROID_SDK_DIR" >> "$CLAUDE_ENV_FILE"
  echo "export PATH=\$PATH:$ANDROID_SDK_DIR/cmdline-tools/latest/bin:$ANDROID_SDK_DIR/platform-tools" >> "$CLAUDE_ENV_FILE"
  exit 0
fi

echo "Installing Android command-line tools..."
mkdir -p "$ANDROID_SDK_DIR/cmdline-tools"
curl -fL --retry 3 --retry-connrefused "$CMDLINE_TOOLS_URL" -o "$CMDLINE_TOOLS_ZIP"
unzip -q "$CMDLINE_TOOLS_ZIP" -d "$ANDROID_SDK_DIR/cmdline-tools"
rm -rf "$ANDROID_SDK_DIR/cmdline-tools/latest"
mv "$ANDROID_SDK_DIR/cmdline-tools/cmdline-tools" "$ANDROID_SDK_DIR/cmdline-tools/latest"
rm "$CMDLINE_TOOLS_ZIP"

export ANDROID_HOME="$ANDROID_SDK_DIR"
export PATH="$PATH:$ANDROID_SDK_DIR/cmdline-tools/latest/bin:$ANDROID_SDK_DIR/platform-tools"

echo "Accepting licenses..."
# `yes` runs forever and only dies from SIGPIPE once sdkmanager closes the
# pipe on exit, so under pipefail its 141 (not sdkmanager's real exit code)
# would otherwise be what `set -e` sees here and kills the script on every run.
set +o pipefail
yes | sdkmanager --licenses
sdkmanager_licenses_status="${PIPESTATUS[1]}"
set -o pipefail
if [ "$sdkmanager_licenses_status" -ne 0 ]; then
  echo "sdkmanager --licenses failed with exit code $sdkmanager_licenses_status" >&2
  exit "$sdkmanager_licenses_status"
fi

echo "Installing SDK packages..."
sdkmanager "platform-tools" "$PLATFORM" "$BUILD_TOOLS"

echo "sdk.dir=$ANDROID_SDK_DIR" > "$CLAUDE_PROJECT_DIR/local.properties"
echo "export ANDROID_HOME=$ANDROID_SDK_DIR" >> "$CLAUDE_ENV_FILE"
echo "export PATH=\$PATH:$ANDROID_SDK_DIR/cmdline-tools/latest/bin:$ANDROID_SDK_DIR/platform-tools" >> "$CLAUDE_ENV_FILE"

echo "Android SDK installation complete."

# ScottPipe's Gradle wrapper is pinned to 9.6.1 (AGP 9.2.1 requires Gradle
# >= 9.4.1), and Gradle's distribution downloads redirect to
# github.com/gradle/gradle-distributions release assets, which this session's
# repo-scoped GitHub access can't reach (confirmed 2026-07-22; system Gradle
# 8.14.3 is also too old for AGP 9.2.1). To avoid that, pre-populate the
# wrapper's own dist cache from a tarball vendored in this repo's own GitHub
# releases (reachable under repo-scoped access) so `./gradlew` finds the
# distribution already extracted and never attempts the download.
GRADLE_USER_HOME_DIR="${GRADLE_USER_HOME:-$HOME/.gradle}"
GRADLE_DIST_HASH_DIR="$GRADLE_USER_HOME_DIR/wrapper/dists/gradle-9.6.1-bin/4ticwg1pgcbps2hj28r8so764"
GRADLE_DIST_CACHE_URL="https://github.com/Gizmozyk/ScottPipe/releases/download/gradle-9.6.1-dist-cache/gradle-9.6.1-bin-dist-cache.tar.gz"
GRADLE_DIST_CACHE_SHA256="75158e61120868a62076fefcf8a7075f43e959172cd5ba0b05fb27bac367282a"
GRADLE_DIST_CACHE_TAR="/tmp/gradle-9.6.1-bin-dist-cache.tar.gz"

if [ -f "$GRADLE_DIST_HASH_DIR/gradle-9.6.1-bin.zip.ok" ]; then
  echo "Gradle 9.6.1 dist cache already present, skipping."
else
  echo "Fetching vendored Gradle 9.6.1 dist cache..."
  curl -fL --retry 3 --retry-connrefused "$GRADLE_DIST_CACHE_URL" -o "$GRADLE_DIST_CACHE_TAR"
  echo "$GRADLE_DIST_CACHE_SHA256  $GRADLE_DIST_CACHE_TAR" | sha256sum -c -
  mkdir -p "$GRADLE_USER_HOME_DIR/wrapper/dists"
  tar -xzf "$GRADLE_DIST_CACHE_TAR" -C "$GRADLE_USER_HOME_DIR/wrapper/dists"
  rm "$GRADLE_DIST_CACHE_TAR"
  echo "Gradle 9.6.1 dist cache installed."
fi
