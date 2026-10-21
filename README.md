# ONVIF IP Cam

Android app (8.0+) that turns a phone into an IP camera: RTSP (H.264/H.265 + AAC), two streams,
ONVIF Profile S with digital PTZ, motion events, snapshots and WS-Discovery. Design: [docs/DESIGN.md](docs/DESIGN.md),
client configuration examples: [docs/CLIENTS.md](docs/CLIENTS.md).

## Endpoints (defaults)
| What | URL |
|---|---|
| Main stream | `rtsp://USER:PASS@PHONE_IP:8554/stream1` |
| Sub stream (H.264) | `rtsp://USER:PASS@PHONE_IP:8554/stream2` |
| ONVIF device service | `http://PHONE_IP:8080/onvif/device_service` |
| Snapshot (Digest/Basic) | `http://PHONE_IP:8080/onvif/snapshot.jpg` |

The user is `admin`; a random password is generated on first start and shown in the app.
An empty password disables authentication.

## Settings: app and ONVIF
Every setting can be changed both in the app and over ONVIF:

| Setting | App | ONVIF |
|---|---|---|
| Resolution, fps, bitrate, codec, GOP | Settings | Media `SetVideoEncoderConfiguration` (Media2 for H.265) |
| Camera (front/back/...) | Settings | Media `SetVideoSourceConfiguration` (`SourceToken`, sources from `GetVideoSources`) |
| Rotation | Settings | Media `SetVideoSourceConfiguration` (`Extension/Rotate`) |
| Date/time OSD | Settings | Media `CreateOSD` / `DeleteOSD` / `GetOSDs` |
| Audio on/off | Settings | Media `Add/RemoveAudioEncoderConfiguration` (Media2 `Add/RemoveConfiguration`) |
| Motion detection, sensitivity | Settings | Analytics `CreateRules` / `DeleteRules` / `ModifyRules` (`MotionDetectorRule`, `Sensitivity` 0..100) |
| Camera name | Settings | Device `SetScopes` (name scope) or `SetHostname` |
| User, password | Settings | Device `SetUser` |
| Ports | Settings | Device `SetNetworkProtocols` |
| Torch, brightness, focus | Main screen | Imaging `SetImagingSettings` (`IrCutFilter` OFF = torch on), `Move` |
| PTZ, presets, home | Main screen | PTZ service |
| Autostart, PTZ zoom headroom | Settings | — (no ONVIF equivalent) |

Most changes restart streaming for about a second; credentials, imaging and PTZ apply live.

## Phone setup tips
The app asks for the permissions it needs on first start (camera, microphone, notifications,
battery optimisation exemption, and "display over other apps" for autostart on Android 11+).

- Keep the phone on a charger; the app lowers the bitrate when the phone gets hot.
- PTZ is digital: "PTZ zoom headroom" captures at a higher resolution than the stream so zooming in loses less detail.

## Build (Docker)
```sh
docker/gradle.sh :app:assembleDebug        # APK: app/build/outputs/apk/debug/app-debug.apk
docker/gradle.sh :rtsp:test :onvif:test    # unit tests
```
The APK is signed with the committed `keystore/debug.keystore`, so newer builds install over older ones.

## Testing without a phone
- `devserver` runs the same RTSP/ONVIF code on the JVM, fed from files:
  `docker/gradle.sh :devserver:installDist`, then run `devserver/build/install/devserver/bin/devserver`
  with `DATA` (H.264/H.265/AAC files), `CAM_USER`, `CAM_PASS`, `MAIN_CODEC=h264|h265`.
- `tools/onvif_check.py HOST PORT USER PASS` exercises the common ONVIF client calls
  (uses `onvif-zeep-async`).
- `docker/Dockerfile.emulator` is a headless KVM emulator with a virtual camera for end-to-end tests of the APK.
