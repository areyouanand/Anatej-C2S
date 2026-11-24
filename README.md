# Anatej C2S

[![CI](https://github.com/areyouanand/Anatej-C2S/actions/workflows/ci.yml/badge.svg)](https://github.com/areyouanand/Anatej-C2S/actions/workflows/ci.yml)

Anatej C2S copies text from anywhere on an Android screen. It reads pixels, not
just selectable text nodes, so it works on images, video frames, and games.
Recognition runs on the device with a bundled ML Kit model. The app has no
internet permission and does not need Google Play services.

## Features

- Floating picker over any app. Tap it, draw around the text, then copy.
- Bottom-corner swipe trigger, with a choice of left, right, or both corners.
- Live preview of the corner touch area while adjusting size and position.
- Customizable picker button: size, colour, opacity, and snap-to-edge.
- Word-level text selection after capture, plus select-all, copy all, share,
  and web search.
- Quick-settings tile and image share/open-with for screenshots and photos.
- About screen with app version and maintainer links.
- QR code and barcode reading, smart actions for links, phone numbers, emails
  and addresses, and a local clipboard history.
- Light, dark and system themes, haptic or sound capture feedback, and a
  one-time introduction.
- Settings export and import; pinch zoom, Draw/Pan crop modes and rotation for awkward text.
- Editable recognized text with Save as `.txt`, structured QR actions and gesture help.
- Optional expiring clipboard history with device-unlock protection.

## Requirements

- Android 12 (API 31) or newer for screen capture through the floating picker
  or corner swipe.
- Android 8 (API 26) or newer for opening or sharing existing images.
- An accessibility service must be enabled once in system settings. It only
  takes a screenshot when you trigger it and reads no screen content.

## Setup

1. Install the APK and open Anatej C2S.
2. Tap **Enable instant capture** and switch on **Anatej C2S capture** in
   system Accessibility settings.
3. Back in the app, tap **Floating picker: off** to show the draggable button,
   or open **Customize picker** and enable **Swipe bottom corner to capture**.
4. Tap the button, or swipe up from a bottom corner, then draw around the text
   and press **Copy**.

The corner swipe uses a transparent accessibility overlay. On some phones it
can overlap the system navigation gesture. The active corners, touch-box size,
distance from the side, and distance from the bottom are all adjustable.

## Privacy

- No `INTERNET` or `ACCESS_NETWORK_STATE` permission. Recognition and barcode
  scanning are entirely on-device.
- Captured frames stay in memory and are not written to disk by the app.
- No analytics, ads, accounts, or background screen monitoring.
- Android Auto Backup and device-transfer backup are disabled. Clipboard
  history also lives in a separate preferences file excluded by backup rules.
- `VIBRATE` is used only for the optional capture confirmation.
- `READ_PHONE_STATE` is optional and off by default. It is requested only if
  you enable "Hide picker during calls", and is used for nothing else.
- Clipboard history is off by default, capped, expiring and excluded from
  settings export.
- Keyboard hiding checks editable focus and keyboard-window type only; it does
  not read typed text or screen content.
- Windows marked `FLAG_SECURE` cannot be captured, including banking and
  password screens.

## Languages

The default APK bundles the Latin recognizer, covering English, German, French,
Spanish, Italian, Portuguese, Dutch, Polish, Czech, Danish, Swedish, Norwegian,
Finnish, Hungarian, Romanian, Turkish and other Latin-script languages.

A separate `full` APK adds Chinese, Japanese, Korean and Devanagari models. ML
Kit's bundled models cannot be downloaded at runtime, so these scripts require
the larger variant rather than an in-app download. Pick the active script under
Recognition in Settings; Auto detect tries every bundled model and keeps the
best result.

## Building

```sh
./gradlew assembleDebug
```

Outputs: `app/build/outputs/apk/latinDebug/app-latin-debug.apk` and
`app/build/outputs/apk/fullDebug/app-full-debug.apk`.

For signed release builds:

```sh
./gradlew assembleRelease
```

Outputs: `app/build/outputs/apk/latin/release/app-latin-release.apk` (about 29 MB)
and `app/build/outputs/apk/full/release/app-full-release.apk` (about 32 MB).
Only ARM libraries are packaged in release APKs. For local emulator verification,
build debug with x86_64 support:

```sh
./gradlew assembleDebug -PincludeEmulatorAbi=true
./gradlew connectedLatinDebugAndroidTest -PincludeEmulatorAbi=true
```

The application ID is `com.anatej.c2s`. Release builds are signed with the
keystore described below. The signing certificate SHA-256 fingerprint is:

```
a5a3ab4b402cb313059329cf2a4b4826d98967bc0df550079c8bbd2ed9f51b3d
```

Check a downloaded APK with:

```sh
apksigner verify --print-certs app/build/outputs/apk/latin/release/app-latin-release.apk
apksigner verify --print-certs app/build/outputs/apk/full/release/app-full-release.apk
```

To sign release builds yourself, create
`keystore/keystore.properties` (gitignored):

```properties
storeFile=keystore/your-release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

Keep the keystore safe. Android rejects updates signed with a different key.

## Tests

```sh
./gradlew testLatinDebugUnitTest testFullDebugUnitTest
./gradlew lintLatinDebug lintFullDebug
```

The unit suite covers backup privacy and validation, clipboard expiry, smart
content classification, script detection and OCR text assembly. An instrumented
smoke test covers Home, About and Settings navigation on an emulator.

## Attribution

Anatej C2S is a modified fork of [TextGrab](https://github.com/notune/TextGrab)
by Noah (notune), used under the MIT License. The original copyright notice is
preserved in [LICENSE](LICENSE).

The bundled ML Kit text recognition SDK and its model are proprietary Google
software, used under the [ML Kit Terms of Service](https://developers.google.com/ml-kit/terms).
They are downloaded from Google's Maven repository at build time and are not
stored in this repository.

## License

MIT License. See [LICENSE](LICENSE).
