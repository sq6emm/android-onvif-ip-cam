# Client configuration examples

Examples for using ONVIF IP Cam with common NVR / home automation software.
Replace `PHONE_IP` and `PASS` with the values shown in the app.

## Frigate
```yaml
go2rtc:
  streams:
    phone: rtsp://admin:PASS@PHONE_IP:8554/stream1
    phone_sub: rtsp://admin:PASS@PHONE_IP:8554/stream2
cameras:
  phone:
    ffmpeg:
      inputs:
        - path: rtsp://127.0.0.1:8554/phone
          roles: [record]
        - path: rtsp://127.0.0.1:8554/phone_sub
          roles: [detect]
    onvif:
      host: PHONE_IP
      port: 8080
      user: admin
      password: PASS
```

## Home Assistant
Settings → Devices → Add integration → ONVIF. The camera is discovered automatically, or enter
`PHONE_IP`, port `8080`, user and password. Motion arrives as a binary sensor (PullPoint events).

