# StikSound for Android

A native Android app, written in Kotlin with Jetpack Compose, that turns a sticker image and a short sound into a square 5 s or 10 s MP4. It also exports a static 512×512 WebP sticker. It is a port of [StikSound](https://github.com/SilverFeit/StikSound) by SilverFeit. The upstream project offers a web app, a desktop app and a Chrome extension. This port adds a native Android app.

Everything runs on the phone. The only network request downloads a MyInstants link that you paste or share into the app.

## What it does

- **Sticker:** PNG, JPG, WebP or animated GIF, from the file picker, the clipboard, or another app's share sheet.
  - Non-square images are center-cropped to 1:1. Drag the picture to move the crop.
  - Animated GIFs play in the editor with their own frame timing. In the export they loop to fill the video.
- **Sound:** any audio file Android can decode (MP3, WAV, M4A/AAC, OGG, FLAC), the sound track of a video, or a MyInstants link.
  - Links are accepted only from `www.myinstants.com` and `myinstants.com`. The download is HTTPS only, follows at most 2 redirects, is limited to 25 MB, and times out after 20 s. Private and loopback addresses are refused.
- **Trim:** a waveform with a selection window. Drag the edges to resize it, drag the middle to move it, or tap to jump. Play previews the selection. A **5s / 10s** toggle sets the maximum length (10 s by default).
- **Background:** white, black or any color, used where the sticker is transparent, because MP4 has no alpha channel.
- **Export:**
  - **MP4:** H.264 video and 48 kHz mono AAC audio, square, at most 512 px, never upscaled. Encoded with `MediaCodec` and `MediaMuxer`.
  - **Static WebP:** 512×512, transparency kept, under WhatsApp's 100 KB limit. Lossless is tried first; if it is too big, lossy quality is lowered until it fits.
  - Results can be saved to `Movies/StikSound` or `Pictures/StikSound`, or shared to any app.
- **Languages:** English, Spanish and Portuguese.

> **WhatsApp:** as of today, turning the MP4 into a sound sticker only works on Android with WhatsApp Beta, on accounts where Meta has enabled it. The steps are Stickers → Create → pick the video from the gallery. Sharing the MP4 into a chat sends it as a normal video. The app says this in its UI and does not claim to create a native WhatsApp sound sticker.

## Build and install

Requirements: JDK 17 or newer, and the Android SDK with platform 36 (Android Studio installs both).

```bash
cd stiksound-android
./gradlew :app:assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:installDebug           # with a phone connected over adb
```

Minimum Android version: 8.0 (API 26). Target: API 36.

## Tests

```bash
./gradlew :core:test                       # pure Kotlin logic (57 tests)
./gradlew :app:testDebugUnitTest           # Robolectric, no device needed (28 tests)
./gradlew :app:lintDebug
./gradlew :app:connectedDebugAndroidTest   # real codecs; needs a device or emulator
```

- **`core` tests** cover the GIF decoder (including a pixel-exact comparison against Pillow's decoding), frame scheduling, crop and trim rules, audio math, WAV, format sniffing, and the MyInstants URL policy, extractor and redirect handling.
- **Robolectric tests (`app/src/test`)** run the Android code on the JVM:
  - `EditorFlowTest` drives the real Compose screens and ViewModel. It loads files through content Uris, drags the crop and the trim window, switches 5s/10s, exports MP4 and WebP through the buttons, checks the share intent, and checks error messages.
  - `MediaPipelineJvmTest` covers sticker decoding and WebP encoding with real Skia (Robolectric native graphics). It runs the app's MediaCodec loops with deterministic fake codecs (`FakeCodecs`) and checks frame count, timestamps, crop, matte and BT.601 colors per frame, GIF frame timing, exact audio length, track interleaving, stereo downmix, and preview playback.
  - `ScreenshotTest` renders the editor and result screens to `app/build/screenshots/*.png`.
- **Instrumented tests (`app/src/androidTest`)** cover what Robolectric can't: real H.264/AAC encoding, MP4 muxing and MP3/M4A/OGG decoding on a device. They check tracks, size, duration and decoded pixel colors.
- **CI:** `.github/workflows/stiksound-android.yml` runs all of the above, including the instrumented tests on API 29 and API 34 emulators.

## Layout

```text
core/   Pure Kotlin (JVM) port of StikSound's domain logic. No Android dependencies.
        Limits, square crop, trim rules, GIF decoder + frame scheduler, audio DSP,
        WAV, format sniffing, ARGB → YUV 4:2:0, MyInstants URL policy/extractor/provider.
app/    Android app.
        media/  MediaCodec audio decoding, sticker loading, MP4 encoder (H.264 + AAC + MediaMuxer),
                WebP export, gallery saving and sharing, preview playback.
        ui/     Compose screens: sticker stage, waveform trim bar, result screen.
        EditorViewModel.kt  Editor state and actions.
```

## Differences from upstream StikSound

| Upstream feature | Android port |
| --- | --- |
| MP4 export (H.264 + AAC) | Yes |
| Static WebP (≤ 100 KB) | Yes |
| MyInstants links | Yes (no CORS limit on Android, same URL policy as the desktop app) |
| Animated GIF stickers | Yes |
| Transparent WebM export | No. Android's encoders do not write VP8/VP9 alpha |
| `.stiksound.zip` bundle and projects | Not yet |
| Video clip as the sticker | Not yet. A video can be used as the *sound* source |
| 9 UI languages | 3 (EN, ES, PT) |

## License and attribution

This app ports code and assets from StikSound (© 2026 StikSound Contributors), which is dual-licensed under MIT or Apache-2.0. The ported parts include the GIF decoder, crop and trim rules, limits, MyInstants URL policy and extractor, app icon, and the WhatsApp notes. The port is distributed under the same terms: [LICENSE-MIT](LICENSE-MIT) or [LICENSE-APACHE](LICENSE-APACHE), at your option.

Sounds from MyInstants remain the property of their owners.
