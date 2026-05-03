APK Hosting Folder
==================

This folder is served by the /download route on the server.

To enable direct APK download via the dashboard's QR code:

1. In Android Studio:
     Build menu -> Build Bundle(s) / APK(s) -> Build APK(s)

2. After the build finishes, locate the APK at:
     NetworkCellAnalyzer/app/build/outputs/apk/debug/app-debug.apk
   (or the release variant if you built one)

3. Copy that file here and rename it to exactly:
     network-cell-analyzer.apk

4. Commit and push:
     git add NetworkCellAnalyzer/server/static/network-cell-analyzer.apk
     git commit -m "Add APK for direct download via QR code"
     git push

5. Render auto-redeploys. The QR code on the dashboard now serves the APK directly.

If this folder is empty, the /download route falls back to the GitHub Releases page.
