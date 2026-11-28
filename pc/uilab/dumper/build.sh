#!/usr/bin/env bash
# Rebuild uidump.jar from WSL. UILAB_TOOLS = folder holding java/jdk-* and sdk/ (Windows toolchain).
set -euo pipefail
tools="${UILAB_TOOLS:?set UILAB_TOOLS}"
jdk=$(echo "$tools"/java/jdk-*/bin)
here="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
out=$(mktemp -d "$tools/uidump-build.XXXXXX")
trap 'rm -rf "$out"' EXIT
cp "$here/UiDump.java" "$out/"
cd "$out"
android=$(wslpath -w "$(echo "$tools"/sdk/platforms/android-35/android.jar)")
"$jdk/javac.exe" -source 8 -target 8 -bootclasspath "$android" -d . UiDump.java
"$jdk/java.exe" -cp "$(wslpath -w "$(echo "$tools"/sdk/build-tools/*/lib/d8.jar)")" com.android.tools.r8.D8 --min-api 30 --lib "$android" --output uidump.jar UiDump.class
cp uidump.jar "$here/uidump.jar"
