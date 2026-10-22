# XRdroid

App screens that stay fixed in space around you, for VITURE glasses on an Android phone. Turn your head and the screens stay put. Look at a screen to control it with a trackpad on the phone.

## What you need

- An Android 14 or newer phone with USB-C display output (developed on a Pixel 9 Pro).
- VITURE Gen2 glasses, such as the Pro 2. Head tracking reads their motion sensor over USB.
- [Shizuku](https://shizuku.rikka.app), which runs XRdroid's helper as the shell user. The helper creates the screens, opens the glasses view fullscreen and delivers the trackpad's touches.

## Setup

1. Install and start Shizuku.
2. Install XRdroid and open it. On the Settings tab, allow Shizuku and the glasses' motion sensor.
3. Plug in the glasses. The screens open on the glasses, each with a home screen and app drawer.

## Using it

- **Remote tab:** move with one finger and tap to click. Scroll with two fingers. Hold, then move, to drag. Back and Home act on the screen you're looking at.
- **Recenter** puts the screens back in front of you. **− / +** pushes them back or brings them closer.
- **Settings tab:** pick a layout (one screen, two screens, or one curved ultrawide) and a home background.
- **Dock:** shows your most-used apps until you add or remove one (long-press an app). XRdroid turns on usage access for itself through Shizuku to rank them.

## Building

```bash
./gradlew assembleDebug
scripts/deploy.sh   # install, open, and show the first log lines (tag XRdroid)
```

Needs JDK 21 and the Android SDK (compile SDK 36).

For a signed release APK, create `keystore.properties` in the project root (it's ignored by git) with `storeFile`, `storePassword`, `keyAlias` and `keyPassword`, then run `./gradlew assembleRelease`. The APK lands in `app/build/outputs/apk/release/`.

## Credits

The glasses' USB protocol comes from [viture-v2](https://github.com/elasticjava/viture-v2) (MIT). XRdroid doesn't use the VITURE SDK.

## Licence

MIT. See [LICENSE](LICENSE).
