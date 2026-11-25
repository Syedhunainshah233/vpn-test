#!/usr/bin/env bash
# Puts our app (UI + branding) on top of the ics-openvpn source checked out in $1.
set -euo pipefail
ICS="$1"
HERE="$(cd "$(dirname "$0")/.." && pwd)"
SK="$ICS/main/src/skeleton"

# 1) Our Java code + icon into the "skeleton" flavor (minimal OpenVPN, no OpenVPN UI)
mkdir -p "$SK/java/com/syedtechdevelopers/vpntest" "$SK/res/drawable"
cp "$HERE/overlay/java/com/syedtechdevelopers/vpntest/"*.java "$SK/java/com/syedtechdevelopers/vpntest/"
cp "$HERE/overlay/res/drawable/"*.xml "$SK/res/drawable/"

# 2) Our launcher activity + app name/icon
cp "$HERE/overlay/AndroidManifest.skeleton.xml" "$SK/AndroidManifest.xml"

# 3) Our own package name (so it never clashes with the real OpenVPN app)
sed -i 's/^\(\s*\)minSdk = 23/\1minSdk = 23\n\1applicationId = "com.syedtechdevelopers.vpntest"/' "$ICS/main/build.gradle.kts"
sed -i 's/versionName = "[^"]*"/versionName = "0.2-test"/' "$ICS/main/build.gradle.kts"
grep -n 'applicationId\|versionName' "$ICS/main/build.gradle.kts"

# 4) Notification texts say "VPN Test" instead of "OpenVPN for Android"
sed -i 's|<string name="app">OpenVPN for Android</string>|<string name="app">VPN Test</string>|' "$ICS/main/src/main/res/values/strings.xml"

echo "Patch OK"
