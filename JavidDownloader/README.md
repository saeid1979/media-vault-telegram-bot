# جاوید دانلودر | Javid Downloader 1.2.0

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
