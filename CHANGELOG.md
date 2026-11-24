# Changelog

## 1.2.0

- Fixed the selection magnifier lingering over the action toolbar and made
  handle geometry match the visible selection.
- Added explicit Idle / Selected / Adjusting selection state and accessibility
  nodes for recognized words.
- Added Draw/Pan crop modes, bounded pinch zoom, undimmed lasso preview and
  lifecycle-safe selector bitmap retention.
- Added a single request-checked capture session, safe service teardown, stable
  overlay restoration and bubble position persistence/clamping.
- Added reliable keyboard suppression using IME windows with an editable-focus
  fallback, plus operational call-suppression state.
- Fixed quick-settings and bubble captures including the picker overlay.
- Moved public launcher/share handling to a non-privileged trampoline so internal
  capture commands cannot be injected into MainActivity.
- Fixed captured-image OCR crashes and stale out-of-order recognition results.
- Added bounded asynchronous settings import/export with schema validation.
- Excluded clipboard history from settings export and Android backup; history
  is now opt-in, expiring and optionally protected by device unlock.
- Added structured QR/barcode actions and non-interrupting code results.
- Added editable recognized text and Save as `.txt`.
- Added script-aware OCR Auto mode, conservative smart actions and HTTP link
  handling.
- Added gesture help, replayable introduction and build variant in About.
- Fixed image downsampling, edge-to-edge insets and small-screen scrolling.
- Added unit tests, an emulator smoke test and GitHub Actions CI.
- Added an opt-in x86_64 debug ABI for emulator verification; release APKs stay ARM-only.

## 1.1.0

- Added confirmation vibration and an optional capture sound.
- Added a clear message when a screen blocks screenshots.
- Added smart actions for selected phone numbers, emails, links and addresses.
- Added Light / Dark / Follow system theme selection.
- Improved TalkBack announcements for selections and the region picker.
- Added settings export and import.
- Added a one-time three-step introduction.
- Added on-device QR code and barcode scanning.
- Added a second APK variant with Chinese, Japanese, Korean and Devanagari OCR.
- Added confidence underlines for uncertain words and a selection magnifier.
- Added double-tap line selection, triple-tap paragraph selection and pinch zoom
  in both the reader and the region picker.
- Added optional hiding of the picker while typing or during calls.
- Added a local clipboard history.
- Added a rotate button for sideways text in the region picker.
- Removed emulator-only libraries from the APKs, reducing their size.

## 1.0.1

- Changed the application ID to `com.anatej.c2s`.
- Release APKs are now signed with a project keystore.
- Added an About screen with version and GitHub links.

## 1.0.0

- Renamed the app to Anatej C2S.
- Added a bottom-corner swipe trigger with left, right, or both corner selection.
- Added live preview and controls for corner box width, height, side distance,
  and bottom distance.
- Added picker customization: size, colour, opacity, and snap-to-edge.
- Fixed the floating picker re-enabling itself after cancelling the picker.
- Kept on-device OCR, word selection, clipboard, and share workflows from TextGrab.
