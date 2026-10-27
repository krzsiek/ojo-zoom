![Ojo Logo](/media/icon.png)

# Ojo Zoom

**Ojo Zoom** is a fork of [Ojo](https://github.com/penguin86/ojo), the FLOSS RTSP surveillance camera viewer for Android, with a few features the original lacks.
It installs next to the original Ojo (package `it.danieleverducci.ojo.zoom`), so both can be used on the same device.

[**Download the latest APK**](https://github.com/krzsiek/ojo-zoom/releases/latest)

## What's different from Ojo

### Zoom
- **Pinch to zoom** (up to 8x) on a camera that fills the screen: a single camera, or one opened fullscreen from the grid.
- **Double tap** zooms to 2.5x on the tapped point; double tap again to zoom back out.
- **Drag** with one finger to move around the zoomed picture (it never leaves the image edges).
- **Back** first resets the zoom, the next back returns to the grid as usual.
- Single taps are ignored while zoomed, so you don't leave fullscreen by accident.
- Zoom is reset when going back to the grid, rotating the screen or resizing the view.

To make this possible the video is rendered on a `TextureView` instead of a `SurfaceView`.

### Swipe between cameras
- On a camera opened fullscreen from the grid, **swipe left** for the next camera and **swipe right** for the previous one (it wraps around).
- Only without zoom: while zoomed, dragging moves the picture as before.

### Portrait layout
- With "Allow screen rotation" enabled, a portrait screen shows the cameras **one under the other**, full width, 16:9. Scroll down when they don't fit.
- Landscape keeps the automatic grid. Rotating doesn't restart the streams.
- A camera opened fullscreen in portrait stays in portrait, fitted to the width; zoom works as usual.

### Snapshots and recordings
- On a camera filling the screen (opened fullscreen, or the only camera), **long press (1.5 s)** shows a camera and a video button. On Android TV, **long press OK** on the remote. They hide again after 5 seconds.
- **Snapshot**: saves the full current frame as JPEG to `Pictures/OjoZoom`, with a white flash.
- **Record**: saves the stream as it comes from the camera (no transcoding, so no extra CPU load) to `Movies/OjoZoom`. While recording the button is red, stays on screen and shows the elapsed time; press it again to stop.
- Safety limits: a recording stops by itself after the **max recording length** (settings menu, 1 to 60 minutes, default 10), when free space gets low (it doesn't start below 1 GB free), and when leaving the camera (back, swipe, leaving the app). Nothing is ever deleted automatically.
- Files appear in the gallery. Android 6 to 9 ask for the storage permission the first time; Android 10+ need no permission.

### Hidden settings button
- While cameras are shown the round settings button is hidden, so it never covers the picture.
- **Long press (1.5 s)** anywhere in the grid view (or on a single camera) shows it for 5 seconds.
- On **Android TV**, any remote key in the grid view shows it for 5 seconds.
- In a camera opened fullscreen from the grid the long press shows the snapshot and record buttons instead; press back to return to the grid for the settings.
- With no cameras configured the button is always visible.

### Build and devices
- ARM only (`arm64-v8a` and `armeabi-v7a` in one APK): phones, tablets and Android TV boxes. No x86 builds.
- Android 5.0 (API 21) or newer.
- libVLC 3.7.7 from Maven Central (`org.videolan.android:libvlc-all`). It replaced libVLC 2.1.12, which was built without stream output and could not record. Hardware decoding and H.265 cameras now work too.

### Building and releasing
- Every push builds the APK on GitHub Actions (`Build APK` workflow); it is kept as the `ojo-zoom-apk` artifact of the run.
- A push to `stable` with a new `versionName` publishes GitHub release `v<versionName>` with the APK attached and `RELEASE_NOTES.md` as its description.
- Release APKs are signed with the key stored in the repository secrets `OJO_KEYSTORE_BASE64`, `OJO_KEYSTORE_PASSWORD`, `OJO_KEY_ALIAS`, `OJO_KEY_PASSWORD`. Without them the APK is signed with a throwaway debug key and cannot update a previously installed copy.
- Local build: `./gradlew assembleRelease` (JDK 17, Android SDK 33).

---

*The original Ojo README follows.*

# Ojo: the FLOSS RTSP Surveillance camera viewer for Android

> This project was moved to [my private git server](https://git.ichibi.eu/penguin86/ojo) . This repository may not be up to date.

[<img src="https://raw.githubusercontent.com/andOTP/andOTP/master/assets/badges/get-it-on-github.png" height="80">](https://github.com/penguin86/ojo/releases/latest) 
[<img src="https://fdroid.gitlab.io/artwork/badge/get-it-on.png" alt="Get it on F-Droid" height="80">](https://f-droid.org/it/packages/it.danieleverducci.ojo)

(Always prefer [F-Droid](https://f-droid.org) build, when possible).

Ojo is a basic IP Camera surveillance wall.
IP camera's RTSP streams are added via its url and shown in the classic tile configuration. The number of tiles is automatically choosen based on the number of configured cameras: a single camera goes full screen, adding more cameras the app switches to a grid view: 2x2, 3x3, 4x4 and so on.
The maximum number of cameras is determined by the device's capabilities.

The stream decoding and rendering is demanded to [VLC's library](https://code.videolan.org/videolan/vlc-android): without their effort this app wouldn't be possible.
This app was specifically developed for F-Droid, as I couldn't find any open source RTSP vievers in the main repository.

The app can be opened deeplinking to url ojo://view.
To open the app with focus on a specific camera, you can use an intent (it.danieleverducci.ojo.OPEN_CAMERA) to specify which camera you want to view.
The extra argument it.danieleverducci.ojo.CAMERA_NAME will open the app with the camera with the name you specified while adding the camera.
The extra argument it.danieleverducci.ojo.CAMERA_NUMBER starting at 1 could be used as well, if you have multiple cameras with the same name.
See belows example how to use the intent. The flag (-f 268468224) could be useful if you want to switch to an other camera while the app is running.
```shell
adb -s <YOUR_DEVICE> shell am start -a it.danieleverducci.ojo.OPEN_CAMERA -f 268468224 --es it.danieleverducci.ojo.CAMERA_NAME <YOUR_CAMERA_NAME>
adb -s <YOUR_DEVICE> shell am start -a it.danieleverducci.ojo.OPEN_CAMERA -f 268468224 --es it.danieleverducci.ojo.CAMERA_NUMBER <YOUR_CAMERA_NUMBER>
```


![Screenshot 1](media/screenshots/1.png)      ![Screenshot 2](media/screenshots/2.png)      ![Screenshot 3](media/screenshots/3.png)

## Contributors
- Thanks to [brenard](https://github.com/brenard) for the new grid sizing method
- Thanks to [davquar](https://github.com/davquar) for the fullscreen compatibility fix on Android 11
- Thanks to [jayfan0](https://github.com/jayfan0) for the first deep link implementation
- Thanks to [free-bots](https://github.com/free-bots) for the selection border on Android TV, intents for direct camera access and leanback support

## How to contribute

The project is open to contribution, but with some limits:

- I'm sorry I can't accept AI-generated contributions. Reviewing a contribution requires time and effort from my side, while generating code with AI requires very little time and produces non reliable code that must be reviewed in detail. This is effectively shifting the work on my side, and in a forced way. If you feel you need a feature but you're not able to implement it by yourself, I prefer you to create an issue in the repository so I can implement it when I can, in a more mantainable way.
- Before implementing a big change, please contact me to ensure it goes in the project's direction.
- If working on an existing ticket, please name the branch accordingly (feature/ticketNumber, bugfix/ticketNumber...).
- Only issue pull requests to the develop branch, never on the main/master one.

Please see the main repository README file on how to obtain an account to my git instance.

