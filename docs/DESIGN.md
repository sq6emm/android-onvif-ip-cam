# ONVIF IP Cam — design

Android app (`pl.arhea.onvifipcam`) that turns a phone into an IP camera exposing RTSP streams
and an ONVIF (Profile S + PTZ + Events) interface. Primary clients: Frigate (go2rtc) and Home Assistant.

## Stack
- Kotlin, Jetpack Compose, minSdk 26, targetSdk 35.
- No heavy media libraries: RTSP/RTP, ONVIF SOAP and WS-Discovery are implemented in-house.
- Built inside Docker (`docker/Dockerfile.android`).

## Modules
| Module | Kind | Contents |
|---|---|---|
| `app` | Android app | UI (Compose), `CameraService` (foreground service), settings (DataStore), media pipeline (Camera2, GL, MediaCodec, motion detection) |
| `rtsp` | pure Kotlin/JVM | RTSP server, RTP packetizers (H.264 RFC 6184, H.265 RFC 7798, AAC RFC 3640), Digest auth |
| `onvif` | pure Kotlin/JVM | SOAP dispatcher, Device/Media/Media2/PTZ/Imaging/Events services, WS-Discovery, WS-Security |

`rtsp` and `onvif` know nothing about Android; they talk to the app through small interfaces
(`StreamSource`, `CameraControl`), so they can be tested on the JVM against ffmpeg, go2rtc and onvif-zeep.

## Video pipeline
```
Camera2 ─► SurfaceTexture (OES) ─► GL renderer (+OSD) ─┬─► MediaCodec main (H.264/H.265) ─┐
   ▲ SCALER_CROP_REGION / ZOOM_RATIO = PTZ               ├─► MediaCodec sub  (H.264)        ├─► RTSP
   │                                                     ├─► preview                        │
   │                                                     ├─► JPEG snapshot (on demand)      │
   │                                                     └─► 160x90 luma ─► motion ─► Events│
AudioRecord ─► MediaCodec AAC-LC ──────────────────────────────────────────────────────────┘
```

## RTSP (default port 8554)
- `/stream1` main, `/stream2` sub.
- OPTIONS, DESCRIBE, SETUP, PLAY, TEARDOWN, GET_PARAMETER, SET_PARAMETER.
- TCP interleaved and UDP unicast transport.
- Parameter sets in SDP and repeated before every IDR.
- Per-client bounded queue; slow clients drop frames (until next keyframe) instead of blocking.
- Digest auth (Basic fallback).

## ONVIF (default HTTP port 8080)
- Discovery: WS-Discovery on 239.255.255.250:3702 (Probe/ProbeMatches, Hello, Bye).
- Device: GetDeviceInformation, GetCapabilities, GetServices, GetSystemDateAndTime, GetScopes,
  GetNetworkInterfaces, GetHostname, GetUsers, GetDNS, GetNTP, ...
- Media v1: GetProfiles, GetProfile, GetStreamUri, GetSnapshotUri, Get*Configuration(s),
  GetVideoEncoderConfigurationOptions, SetVideoEncoderConfiguration.
- Media2: GetProfiles, GetStreamUri, GetSnapshotUri (needed for H.265).
- PTZ: GetNodes, GetConfigurations, ContinuousMove, RelativeMove (incl. FOV translation space for
  Frigate autotracking), AbsoluteMove, Stop, GetStatus, presets, GotoHomePosition.
  Implemented as sensor crop (zoom + pan/tilt inside the crop).
- Imaging: Get/SetImagingSettings (brightness → exposure compensation, focus), GetOptions.
- Events: GetEventProperties, CreatePullPointSubscription, PullMessages, Renew, Unsubscribe,
  topics `tns1:RuleEngine/CellMotionDetector/Motion` and `tns1:VideoSource/MotionAlarm`.
- Auth: WS-Security UsernameToken (PasswordDigest) or HTTP Digest. GetSystemDateAndTime is anonymous.

## Background operation
- Foreground service `camera|microphone`, WakeLock, high-perf WifiLock, MulticastLock.
- Battery optimisation exemption requested from the UI.
- Thermal status listener lowers bitrate/fps under throttling.
- Boot autostart; on Android 14+ requires "display over other apps" permission.

## Stages
1. Skeleton, Docker build, Camera2 → GL → H.264 → RTSP `/stream1`, foreground service.
2. Sub-stream, H.265, AAC audio, Digest auth.
3. ONVIF Device/Media/Media2, WS-Discovery, snapshot, WS-Security.
4. PTZ, Imaging, Events + motion detection; compatibility tests (go2rtc, onvif-zeep).
5. Autostart, thermal, OSD, UI polish.
