# PureCam

A no-AI camera app for Android, tuned for the **Redmi Note 13 Pro+** (200 MP HP3 main, 8 MP ultra-wide, 2 MP macro, 16 MP front). It runs on any Android 11+ phone.

## What "no AI processing" means here

PureCam talks to the sensor only through the standard **Camera2** API:

- no scene detection, beautification, sky or face enhancement, multi-frame HDR/night merging or AI super-resolution;
- **noise reduction and sharpening default to OFF**, and you choose how much in Settings (Off / Minimal / Fast / High quality);
- photos without a filter are saved as **the exact JPEG bytes the camera produced**, untouched;
- **RAW (DNG)** saves the unprocessed Bayer data;
- **Full-sensor mode**: if the HAL exposes the 200 MP mode to apps (Android 12+ `ULTRA_HIGH_RESOLUTION_SENSOR`), it appears in Settings → Resolution as "(full sensor)".

## Features

| Area | What you get |
|---|---|
| Lenses | Every camera the system exposes, **including hidden IDs** (probes IDs 0–63, a common Xiaomi/MediaTek trick) and the physical sub-cameras of logical cameras. Shown as 0.6x / 1x / 2x / Macro / Front chips. Pinch to zoom. Settings shows each detected lens with its ID, focal length and MP. |
| Modes | Photo, Pro, Video |
| Pro | Manual ISO, shutter (1/8000 s to 30 s, limited by the lens), manual focus with **focus peaking**, white balance presets plus Kelvin, EV, live **histogram**, and a one-tap reset to auto |
| Photo | Tap to focus/meter, long-press for **AE/AF lock**, EV slider, flash (off/auto/on/torch), timer (3/5/10 s), **burst** (hold the shutter), aspect 4:3 / 16:9 / 1:1, resolution picker, JPEG quality, RAW+JPEG |
| Live Photo | Tap the **LIVE** icon in Photo mode. Saves about 1.5 s before and 1.5 s after the shutter as a **Motion Photo** (`.MP.jpg`, Google Motion Photo v1 + MicroVideo XMP). It plays in Google Photos and Xiaomi Gallery, and you can also save the clip as a separate MP4. |
| Filters | 26 real-time GPU filters (Vivid, Warm, Cool, Golden, Teal & Orange, Cinematic, Film with grain, Vintage, Instant, Fade, Matte, Pastel, Chrome, Dramatic, Lomo, Neon, Ice, Mono, Noir, Soft B&W, Sepia, Posterize, Sketch, Negative…) with a strength slider. One shader handles the preview, photos, Live Photos and videos, so what you see is what gets saved. "Keep unfiltered original" also saves the raw camera JPEG. |
| Video | 4K / 1080p / 720p at 24/30/60 fps (when the sensor supports it), H.264 or HEVC, high bitrate, EIS toggle (OIS always on), stereo AAC audio, torch, filters |
| Slow motion / time-lapse | Video mode → *Slow motion* (120/240 fps constrained high-speed session, saved as a real slowed-down 30 fps clip) when the lens offers it to apps; *Time-lapse* at 5–120× |
| While recording | Pause / resume, and take full-resolution stills from the video stream |
| Zoom | Tap the active lens chip (or pinch) for a zoom slider with the current value; pinching below 1x hops to the 0.6x lens and back automatically |
| File size | Live Photos use ~12 MP stills and a small HEVC clip (≤1440 px, ~4 Mbit/s). Optional **HEIC** photo format (camera-native when supported, otherwise hardware HEIF encoding up to ~50 MP); Live Photos stay JPEG Motion Photos |
| Selfies | Screen flash for the front camera: white screen at full brightness |
| Location | Optional GPS tagging of photos, RAW and videos (Settings → Save location) |
| Layout | Real landscape layout when the phone is turned sideways; haptic feedback on the shutter, lens switches, mode changes and slider steps |
| Viewfinder | Grid (thirds / golden / 4×4), level indicator, icons that rotate with the phone |
| Controls | Volume keys as shutter or zoom, shutter sound toggle (forced where the law requires it), mirror front photos |

Files are saved to `DCIM/PureCam`.

## Build and install

You need JDK 17 and the Android SDK (platform 35). The easiest route is **Android Studio**: *Open* this folder, let it sync, then *Run* with the phone connected.

From the command line (the Gradle wrapper is included; `local.properties` must point `sdk.dir` at the SDK):

```bash
./gradlew assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

The release APK (~2 MB) is signed with the debug key so it can be side-loaded directly. On the phone, turn on Developer options → USB debugging and, on Xiaomi, also **Install via USB**.

> Windows note: if Gradle fails with `Unable to establish loopback connection`, the temp path is too long for Java's Unix-domain socket. Set `TEMP`/`TMP` to a short folder such as `D:\gtmp` before building.

To set it as the double-press-power camera on the Redmi, go to Settings → Additional settings → Gesture shortcuts → Launch camera. PureCam registers itself as a still/video camera, so it can be picked there.

### GitHub Actions

`.github/workflows/build-apk.yml` builds the release APK on every push (or manually under *Actions → Build APK → Run workflow*) and attaches it to the run as the `PureCam` artifact. CI signs with its own throw-away debug key, so uninstall a locally built copy before installing a CI build (or the other way round).

## Redmi / HyperOS limitations

- **Auxiliary lens whitelist.** Xiaomi firmware often exposes the macro (and sometimes the ultra-wide) only to its own camera app. PureCam finds every ID the system will describe. If the system still refuses to open one, you get a message saying so, and nothing short of root changes that.
- **200 MP.** Third-party access to the 200 MP remosaic mode depends on the firmware. If it isn't listed, the sensor's native binned output (12.5 MP or 50 MP) is the best any app can get.
- **Filtered photos** are processed at up to ~16.8 MP (to fit in memory). Unfiltered photos always keep the full resolution.

## Code map

```
app/src/main/java/com/purecam/
  camera/   Lens discovery (incl. hidden IDs), stream sizes, Camera2 controller
  gl/       EGL + filter shader, live renderer (viewfinder + encoders), photo post-processor
  media/    MediaStore saving, MediaRecorder video, Live Photo ring buffer, Motion Photo writer
  ui/       Compose UI + ViewModel
  util/     Orientation and level sensor, EXIF helpers
```
