"""ONVIF compatibility check using onvif-zeep-async (the library Home Assistant and Frigate use).

Usage: python onvif_check.py HOST PORT USER PASS
Exercises the calls HA's onvif integration and Frigate's PTZ code make, and fails loudly on errors.
"""
import asyncio
import datetime as dt
import sys

from onvif import ONVIFCamera

FOV = "http://www.onvif.org/ver10/tptz/PanTiltSpaces/TranslationSpaceFov"
failures = []


async def step(name, coro):
    try:
        r = await coro
        print(f"OK   {name}")
        return r
    except Exception as e:  # noqa: BLE001
        print(f"FAIL {name}: {type(e).__name__}: {e}")
        failures.append(name)
        return None


async def main(host, port, user, pw):
    cam = ONVIFCamera(host, int(port), user, pw)
    await step("update_xaddrs", cam.update_xaddrs())
    print("     xaddrs:", {k.split("/")[-2]: v for k, v in cam.xaddrs.items()})
    dev = await cam.create_devicemgmt_service()
    t = await step("GetSystemDateAndTime", dev.GetSystemDateAndTime())
    if t:
        u = t.UTCDateTime
        print("     camera UTC:", u.Date.Year, u.Date.Month, u.Date.Day, u.Time.Hour, u.Time.Minute, u.Time.Second)
    info = await step("GetDeviceInformation", dev.GetDeviceInformation())
    print("    ", info and (info.Manufacturer, info.Model, info.FirmwareVersion, info.SerialNumber))
    caps = await step("GetCapabilities", dev.GetCapabilities({"Category": "All"}))
    if caps:
        print("     events pullpoint:", caps.Events.WSPullPointSupport, "ptz:", caps.PTZ and caps.PTZ.XAddr)
    await step("GetServices", dev.GetServices({"IncludeCapability": True}))
    ni = await step("GetNetworkInterfaces", dev.GetNetworkInterfaces())
    if ni:
        print("     mac:", ni[0].Info.HwAddress)
    await step("GetScopes", dev.GetScopes())
    await step("GetHostname", dev.GetHostname())

    media = await cam.create_media_service()
    await step("Media.GetServiceCapabilities", media.GetServiceCapabilities())
    profiles = await step("GetProfiles", media.GetProfiles()) or []
    for p in profiles:
        vec = p.VideoEncoderConfiguration
        print(f"     profile {p.token}: {vec.Encoding} {vec.Resolution.Width}x{vec.Resolution.Height} "
              f"@{vec.RateControl.FrameRateLimit} {vec.RateControl.BitrateLimit}kbps "
              f"ptz={p.PTZConfiguration and p.PTZConfiguration.token} audio={p.AudioEncoderConfiguration and p.AudioEncoderConfiguration.Encoding}")
        uri = await step(f"GetStreamUri {p.token}", media.GetStreamUri(
            {"StreamSetup": {"Stream": "RTP-Unicast", "Transport": {"Protocol": "RTSP"}}, "ProfileToken": p.token}))
        print("     ", uri and uri.Uri)
        snap = await step(f"GetSnapshotUri {p.token}", media.GetSnapshotUri({"ProfileToken": p.token}))
        print("     ", snap and snap.Uri)
    if profiles:
        await step("GetVideoEncoderConfigurationOptions", media.GetVideoEncoderConfigurationOptions(
            {"ProfileToken": profiles[0].token}))
        await step("GetVideoSources", media.GetVideoSources())
        img_bytes = await step("snapshot download (HA camera.async_camera_image)", cam.get_snapshot(profiles[0].token))
        print("      snapshot bytes:", img_bytes and len(img_bytes))

    try:
        media2 = await cam.create_onvif_service("media2")
        p2 = await step("Media2.GetProfiles", media2.GetProfiles({"Type": ["All"]}))
        for p in p2 or []:
            print("     media2", p.token, p.Configurations.VideoEncoder.Encoding)
            u = await step(f"Media2.GetStreamUri {p.token}", media2.GetStreamUri({"Protocol": "RtspUnicast", "ProfileToken": p.token}))
            print("     ", u)
    except Exception as e:  # noqa: BLE001
        print("SKIP media2:", e)

    if profiles and profiles[0].PTZConfiguration:
        token = profiles[0].token
        ptz = await cam.create_ptz_service()
        await step("PTZ.GetServiceCapabilities", ptz.GetServiceCapabilities())
        await step("PTZ.GetNodes", ptz.GetNodes())
        opts = await step("PTZ.GetConfigurationOptions", ptz.GetConfigurationOptions(
            {"ConfigurationToken": profiles[0].PTZConfiguration.token}))
        if opts:
            uris = [s.URI for s in opts.Spaces.RelativePanTiltTranslationSpace]
            print("      FOV space present (Frigate autotracking):", any("TranslationSpaceFov" in u for u in uris))
        await step("ContinuousMove", ptz.ContinuousMove(
            {"ProfileToken": token, "Velocity": {"PanTilt": {"x": 0.5, "y": -0.2}, "Zoom": {"x": 0.0}}}))
        st = await step("GetStatus", ptz.GetStatus({"ProfileToken": token}))
        print("     ", st and (st.MoveStatus.PanTilt, st.Position.PanTilt.x))
        await step("Stop", ptz.Stop({"ProfileToken": token}))
        await step("RelativeMove FOV", ptz.RelativeMove({"ProfileToken": token, "Translation": {
            "PanTilt": {"x": 0.2, "y": 0.1, "space": FOV}, "Zoom": {"x": 0.1}}}))
        await step("AbsoluteMove", ptz.AbsoluteMove({"ProfileToken": token, "Position": {
            "PanTilt": {"x": -0.5, "y": 0.5}, "Zoom": {"x": 0.3}}}))
        pr = await step("SetPreset", ptz.SetPreset({"ProfileToken": token, "PresetName": "door"}))
        print("      preset token:", pr)
        presets = await step("GetPresets", ptz.GetPresets({"ProfileToken": token}))
        print("     ", presets and [(x.token, x.Name) for x in presets])
        if pr:
            await step("GotoPreset", ptz.GotoPreset({"ProfileToken": token, "PresetToken": pr}))
            await step("RemovePreset", ptz.RemovePreset({"ProfileToken": token, "PresetToken": pr}))
        await step("GotoHomePosition", ptz.GotoHomePosition({"ProfileToken": token}))

    imaging = await cam.create_imaging_service()
    s = await step("GetImagingSettings", imaging.GetImagingSettings({"VideoSourceToken": "vs0"}))
    print("     ", s and (s.Brightness, s.Focus and s.Focus.AutoFocusMode, s.IrCutFilter))
    await step("SetImagingSettings", imaging.SetImagingSettings(
        {"VideoSourceToken": "vs0", "ImagingSettings": {"Brightness": 70, "IrCutFilter": "OFF"}}))
    await step("GetOptions", imaging.GetOptions({"VideoSourceToken": "vs0"}))

    events = await cam.create_events_service()
    await step("Events.GetServiceCapabilities", events.GetServiceCapabilities())
    props = await step("GetEventProperties", events.GetEventProperties())

    # The way Home Assistant consumes events: PullPointManager + PullMessages loop.
    lost = []
    mgr = await step("create_pullpoint_manager", cam.create_pullpoint_manager(dt.timedelta(seconds=60), lambda: lost.append(1)))
    if mgr:
        svc = mgr.get_service()
        seen = []
        deadline = asyncio.get_event_loop().time() + 25
        while asyncio.get_event_loop().time() < deadline and len(seen) < 3:
            r = await step("PullMessages", svc.PullMessages({"MessageLimit": 100, "Timeout": dt.timedelta(seconds=10)}))
            for m in (r.NotificationMessage if r else []) or []:
                topic = m.Topic._value_1
                msg = m.Message._value_1
                data = msg.Data.SimpleItem[0]
                print(f"      event {topic} {msg.PropertyOperation} {data.Name}={data.Value}")
                seen.append(topic)
        if not seen:
            failures.append("no events received")
        await step("Renew", mgr._subscription.Renew(cam.get_next_termination_time(dt.timedelta(seconds=60))))
        await step("Unsubscribe (stop manager)", mgr.shutdown() if hasattr(mgr, "shutdown") else mgr.stop())

    await cam.close()
    print("\nFAILURES:", failures if failures else "none")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(asyncio.run(main(*sys.argv[1:5])))
