#!/usr/bin/env bash
# Signs every Mach-O file of the bundled Java runtime with the hardened runtime + JVM
# entitlements, so the notarized app passes Gatekeeper. Tauri signs the app itself afterwards.
# Requires: APPLE_CERTIFICATE (base64 .p12), APPLE_CERTIFICATE_PASSWORD, APPLE_SIGNING_IDENTITY.
set -euo pipefail
cd "$(dirname "$0")/.."

KEYCHAIN="$RUNNER_TEMP/flowlens-signing.keychain-db"
KEYCHAIN_PASSWORD="$(openssl rand -hex 16)"
echo "$APPLE_CERTIFICATE" | base64 --decode > "$RUNNER_TEMP/cert.p12"
security create-keychain -p "$KEYCHAIN_PASSWORD" "$KEYCHAIN"
security set-keychain-settings -lut 21600 "$KEYCHAIN"
security unlock-keychain -p "$KEYCHAIN_PASSWORD" "$KEYCHAIN"
security import "$RUNNER_TEMP/cert.p12" -P "$APPLE_CERTIFICATE_PASSWORD" -A -t cert -f pkcs12 -k "$KEYCHAIN"
security set-key-partition-list -S apple-tool:,apple:,codesign: -s -k "$KEYCHAIN_PASSWORD" "$KEYCHAIN"
security list-keychains -d user -s "$KEYCHAIN" $(security list-keychains -d user | tr -d '"')
rm -f "$RUNNER_TEMP/cert.p12"

RUNTIME=src-tauri/engine/runtime
ENT=src-tauri/entitlements.plist
count=0
while IFS= read -r -d '' f; do
  if file -b "$f" | grep -q "Mach-O"; then
    codesign --force --timestamp --options runtime --entitlements "$ENT" --sign "$APPLE_SIGNING_IDENTITY" "$f"
    count=$((count + 1))
  fi
done < <(find "$RUNTIME" -type f -print0)
echo "Signed $count runtime binaries"
codesign --verify --strict --verbose=2 "$RUNTIME/bin/java"
