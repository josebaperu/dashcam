# Dashcam

Personal rear-camera recorder. Java 21, CameraX, tiny 96dp preview.

## Controls

| Button | Action |
| --- | --- |
| Record | Start recording |
| Pause | Pause the current clip |
| Resume | Continue the paused clip |
| Stop | Finish and save the clip |
| Loop | Off: clips go to `DCIM/Dashcam` and can fill the phone. On: clips go to `DCIM/Dashcam/loop` and stay within 5 GB (oldest deleted). Each clip is up to 10 minutes. |

Clips land in the public album `DCIM/Dashcam` (`/storage/emulated/0/DCIM/Dashcam/`), which Gallery and Google Photos pick up as the **Dashcam** album.

## Build

```bash
cd /home/super/dev/Aauto/dashcam_app
./gradlew assembleDebug
```

SDK path is `sdk.dir=/home/super/Android/Sdk` in `local.properties`.

Install: `./gradlew installDebug`

Open the app once, grant camera (and mic / notifications), then leave it running. The Android Auto helper binds to this process.
