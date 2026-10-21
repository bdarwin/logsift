#!/bin/bash
# Makes Logsift.app open .log files using macOS's own log type, instead of the private type
# jpackage declares. Logsift then shows up in Open With without claiming the type, so it doesn't
# confuse Quick Look or other apps. The app is re-signed afterwards (ad hoc, as jpackage does).
set -euo pipefail
APP="$1"
PL="$APP/Contents/Info.plist"
B=/usr/libexec/PlistBuddy
$B -c "Delete :UTExportedTypeDeclarations" "$PL" 2>/dev/null || true
$B -c "Delete :CFBundleDocumentTypes" "$PL" 2>/dev/null || true
$B -c "Add :CFBundleDocumentTypes array" "$PL"
$B -c "Add :CFBundleDocumentTypes:0 dict" "$PL"
$B -c "Add :CFBundleDocumentTypes:0:CFBundleTypeName string Log file" "$PL"
$B -c "Add :CFBundleDocumentTypes:0:CFBundleTypeRole string Viewer" "$PL"
$B -c "Add :CFBundleDocumentTypes:0:LSHandlerRank string Default" "$PL"
$B -c "Add :CFBundleDocumentTypes:0:LSItemContentTypes array" "$PL"
$B -c "Add :CFBundleDocumentTypes:0:LSItemContentTypes:0 string com.apple.log" "$PL"
$B -c "Add :CFBundleDocumentTypes:0:LSItemContentTypes:1 string public.log" "$PL"
codesign --force --deep --sign - "$APP"
