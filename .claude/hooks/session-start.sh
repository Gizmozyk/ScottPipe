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
curl -L "$CMDLINE_TOOLS_URL" -o "$CMDLINE_TOOLS_ZIP"
unzip -q "$CMDLINE_TOOLS_ZIP" -d "$ANDROID_SDK_DIR/cmdline-tools"
mv "$ANDROID_SDK_DIR/cmdline-tools/cmdline-tools" "$ANDROID_SDK_DIR/cmdline-tools/latest"
rm "$CMDLINE_TOOLS_ZIP"

export ANDROID_HOME="$ANDROID_SDK_DIR"
export PATH="$PATH:$ANDROID_SDK_DIR/cmdline-tools/latest/bin:$ANDROID_SDK_DIR/platform-tools"

echo "Accepting licenses..."
yes | sdkmanager --licenses

echo "Installing SDK packages..."
sdkmanager "platform-tools" "$PLATFORM" "$BUILD_TOOLS"

echo "sdk.dir=$ANDROID_SDK_DIR" > "$CLAUDE_PROJECT_DIR/local.properties"
echo "export ANDROID_HOME=$ANDROID_SDK_DIR" >> "$CLAUDE_ENV_FILE"
echo "export PATH=\$PATH:$ANDROID_SDK_DIR/cmdline-tools/latest/bin:$ANDROID_SDK_DIR/platform-tools" >> "$CLAUDE_ENV_FILE"

echo "Android SDK installation complete."

# NOTE: this only provisions the Android SDK. ScottPipe's Gradle wrapper is
# pinned to 9.6.1 (AGP 9.2.1 requires Gradle >= 9.4.1), and Gradle's
# distribution downloads now redirect to github.com/gradle/gradle-distributions
# release assets, which this session's repo-scoped GitHub access can't reach
# (confirmed 2026-07-22; system Gradle 8.14.3 is also too old for AGP 9.2.1).
# Until that's resolved, ./gradlew will still fail at the wrapper-download step.
