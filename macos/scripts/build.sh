#!/bin/bash
set -euo pipefail
project_dir="$(cd "$(dirname "$0")/.." && pwd)"
if [[ "$(uname -s)" != "Darwin" ]]; then
  echo "Mac app bundles must be compiled on macOS with Xcode Command Line Tools." >&2
  exit 1
fi
output_dir="${MOZHI_MAC_OUTPUT:-$project_dir/build}"
mkdir -p "$output_dir"
stage_dir="$(mktemp -d "$output_dir/.stage.XXXXXX")"
trap 'rm -rf "$stage_dir"' EXIT
app_dir="$stage_dir/墨知.app"
mkdir -p "$app_dir/Contents/MacOS" "$app_dir/Contents/Resources"
cp "$project_dir/Info.plist" "$app_dir/Contents/Info.plist"
cp -R "$project_dir/Web" "$app_dir/Contents/Resources/Web"
sdk_path="$(xcrun --sdk macosx --show-sdk-path)"
for arch in arm64 x86_64; do
  xcrun swiftc -swift-version 5 -O -sdk "$sdk_path" -target "$arch-apple-macosx12.0" \
    "$project_dir"/Sources/*.swift -o "$stage_dir/Mozhi-$arch" \
    -framework AppKit -framework WebKit -framework Security -framework CryptoKit
done
xcrun lipo -create "$stage_dir/Mozhi-arm64" "$stage_dir/Mozhi-x86_64" -output "$app_dir/Contents/MacOS/Mozhi"
icon_dir="$stage_dir/AppIcon.iconset"
mkdir -p "$icon_dir"
for size in 16 32 128 256 512; do
  sips -z "$size" "$size" "$project_dir/Web/motuan.png" --out "$icon_dir/icon_${size}x${size}.png" >/dev/null
  double=$((size * 2))
  sips -z "$double" "$double" "$project_dir/Web/motuan.png" --out "$icon_dir/icon_${size}x${size}@2x.png" >/dev/null
done
iconutil -c icns "$icon_dir" -o "$app_dir/Contents/Resources/AppIcon.icns"
plutil -lint "$app_dir/Contents/Info.plist"
if [[ -n "${MOZHI_MAC_SIGN_IDENTITY:-}" ]]; then
  codesign --force --options runtime --timestamp --sign "$MOZHI_MAC_SIGN_IDENTITY" "$app_dir"
else
  codesign --force --sign - "$app_dir"
fi
codesign --verify --deep --strict "$app_dir"
ditto -c -k --sequesterRsrc --keepParent "$app_dir" "$output_dir/mozhi-mac-0.2.0-universal.zip"
if [[ -n "${MOZHI_MAC_NOTARY_PROFILE:-}" ]]; then
  if [[ -z "${MOZHI_MAC_SIGN_IDENTITY:-}" ]]; then echo "Notarization requires a Developer ID signing identity." >&2; exit 1; fi
  xcrun notarytool submit "$output_dir/mozhi-mac-0.2.0-universal.zip" --keychain-profile "$MOZHI_MAC_NOTARY_PROFILE" --wait
  xcrun stapler staple "$app_dir"
  ditto -c -k --sequesterRsrc --keepParent "$app_dir" "$output_dir/mozhi-mac-0.2.0-universal.zip"
fi
mkdir -p "$stage_dir/disk"
ditto "$app_dir" "$stage_dir/disk/墨知.app"
ln -s /Applications "$stage_dir/disk/Applications"
hdiutil create -volname "墨知" -srcfolder "$stage_dir/disk" -ov -format UDZO "$output_dir/mozhi-mac-0.2.0-universal.dmg" >/dev/null
if [[ -n "${MOZHI_MAC_SIGN_IDENTITY:-}" ]]; then
  codesign --timestamp --sign "$MOZHI_MAC_SIGN_IDENTITY" "$output_dir/mozhi-mac-0.2.0-universal.dmg"
fi
if [[ -n "${MOZHI_MAC_NOTARY_PROFILE:-}" ]]; then
  xcrun notarytool submit "$output_dir/mozhi-mac-0.2.0-universal.dmg" --keychain-profile "$MOZHI_MAC_NOTARY_PROFILE" --wait
  xcrun stapler staple "$output_dir/mozhi-mac-0.2.0-universal.dmg"
fi
ditto "$app_dir" "$output_dir/墨知.app"
shasum -a 256 "$output_dir/mozhi-mac-0.2.0-universal.zip" "$output_dir/mozhi-mac-0.2.0-universal.dmg" > "$output_dir/SHA256SUMS.txt"
echo "Built universal app, ZIP, and DMG in $output_dir"
