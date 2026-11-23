# Anatej C2S

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

- No `INTERNET` or `ACCESS_NETWORK_STATE` permission.
- Captured frames stay in memory and are not written to disk by the app.
- No analytics, ads, accounts, or background screen monitoring.
- Windows marked `FLAG_SECURE` cannot be captured, including banking and
  password screens.

## Languages

The bundled Latin recognizer covers English, German, French, Spanish, Italian,
Portuguese, Dutch, Polish, Czech, Danish, Swedish, Norwegian, Finnish,
Hungarian, Romanian, Turkish, and other Latin-script languages. Chinese,
Japanese, Korean, and Devanagari need separate model packs and are not bundled.

## Building

```sh
./gradlew assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`.

For a release build:

```sh
./gradlew assembleRelease
```

The application ID is `com.anatej.c2s`. Release builds are signed with the
keystore described below. The signing certificate SHA-256 fingerprint is:

```
a5a3ab4b402cb313059329cf2a4b4826d98967bc0df550079c8bbd2ed9f51b3d
```

Check a downloaded APK with:

```sh
apksigner verify --print-certs app-release.apk
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
