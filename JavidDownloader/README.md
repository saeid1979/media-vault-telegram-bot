# جاوید دانلودر | Javid Downloader 1.4.0

A customized **Persian-first Android downloader** for the owner's permitted/public YouTube and Instagram downloads. Not a renamed build of YTDLnis.

## Features
- Native Android app, Persian RTL design, exclusive name and icon
- Paste from clipboard or Share a link from YouTube/Instagram
- Best quality, 1080p, 720p, 480p, 360p and MP3 audio options
- Check link/title, download progress, saved-history list
- Saves results to **Downloads/JavidDownloader** using MediaStore
- Updates of yt-dlp engine, no account or cloud backend
- Android 10+ arm64, package id `com.javid.downloader`

## Build APK
In GitHub Actions, open **Javid Downloader Android APK** and download the resulting APK artifact, if the workflow succeeds. Source path: `JavidDownloader/`. Local build also works using Android SDK 35, JDK 17 and Gradle 8.9:
```sh
cd JavidDownloader
gradle :app:assembleDebug
```
Output: `app/build/outputs/apk/debug/app-debug.apk`.

**Important:** This initial build is a test APK signed with the Android debug certificate, which might change between CI runs. Production in-place upgrades require a secure *persistent* release signing key.

## Usage and limitations
Download only media you own or have permission to download, and observe platform terms. This app cannot access private/login-only media, DRM streams, or bypass restrictions. YouTube and Instagram link support depends on yt-dlp extractors, which can break when sites change. The first release runs the download in an Activity background thread; keep the app open until the download finishes. No guarantee that downloads will succeed on all links.

## Open source acknowledgements
Uses the independently maintained [youtubedl-android](https://github.com/yausername/youtubedl-android) wrapper of [yt-dlp](https://github.com/yt-dlp/yt-dlp). youtubedl-android is GPL-3.0-licensed. This JavidDownloader source is made available under GNU GPLv3 and should preserve applicable upstream notices upon redistribution. It is a custom native interface built from new code, not copied branding or a fork of YTDLnis.

## Platforms recognised (v1.2.0)

YouTube, Instagram, Facebook, TikTok, X/Twitter, Pinterest, Vimeo,
Reddit, Dailymotion, and Twitch. Shortened links are accepted only from
known domains such as youtu.be, fb.watch, pin.it and dai.ly. Link
recognition is not a guarantee that the source permits or provides a downloadable
video; some content types (private/restricted/DRM-protected/live streams,
still images, age-gated posts) are not downloadable. This app does not
try to bypass authentication, protection, or access controls.

Version 1.2.0 displays the detected site as links are pasted/shared,
retains the visual overhaul and quality controls of v1.1, and uses
format fallback for combined audio/video sources.


## On-device Telegram bot companion — v1.4.0

Android now offers **خانه · دانلودها · بات · بیشتر**. The Telegram tab
accepts the user's **existing BotFather token**, encrypted locally by
Android Keystore AES-GCM (no token baked into the APK, source code or logs).

1. Stop the old Python polling process / webhook host for **the same token**
   before starting the phone bot. Telegram supports one active getUpdates
   receiver. If a webhook is active, disable it deliberately before switching.
2. Enter the existing bot token in the app (never send it in chat or paste it
   into GitHub) and tap **ذخیره امن توکن**.
3. Tap **روشن کردن بات**. A foreground notification remains visible.
4. Copy the 10-minute pairing code from the app and send
   `/pair 123456` in the bot's private Telegram conversation, replacing
   123456 with the displayed code.
5. Once paired, send a permitted public video URL in that private chat.
   Confirm downloading rights, then select video (up to 720p) or MP3.
   Files below 45 MiB are sent back to Telegram. Temporary files are deleted.

**Important limitations of first release:** This is a separately implemented
**Android companion** reusing the *same existing bot identity/token*, not an
automatic port of all Python Bot features. Users/roles/VIP subscriptions,
administrator dashboards and the old server SQLite history are **not yet
migrated**. For safety only the paired owner can download using phone mode.
There is no remote-control API and no open ports on the phone.

Wi-Fi-only mode is enabled by default to avoid unexpected mobile-data usage.
The phone and internet must remain available. Android background execution
policies (especially Android 15+ dataSync foreground-service time limits),
battery optimizations and network interruptions prevent 24/7 availability.
For uninterrupted public service a continuously hosted backend is preferable.

### Packaging & identity

Package name remains `com.javid.downloader`, versionCode is 10.
Signed updates must reuse the **same private signing certificate** as
v1.2.2/v1.3.1. Do not install unsigned or independently debug-signed APKs
over existing user installations. Configure only the GitHub Actions secrets
`JAVID_KEYSTORE_B64` and `JAVID_SIGN_PASSWORD` or sign the unsigned release
locally using the permanent key. **Never add keystore or secret to GitHub.**
