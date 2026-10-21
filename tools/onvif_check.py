"""ONVIF compatibility check using onvif-zeep-async (the library Home Assistant and Frigate use).

Usage: python onvif_check.py HOST PORT USER PASS [config]
Exercises the calls HA's onvif integration and Frigate's PTZ code make, and fails loudly on errors.
With "config" it also changes device configuration over ONVIF (name, video source, rotation, OSD,
audio, motion rule) and restores the original values afterwards. On the phone each change restarts streaming.
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


async def wait_ready(media, seconds=20):
    """After a config change the phone restarts its servers (0.5 s after replying); wait until they answer again."""
    await asyncio.sleep(2.5)
    for _ in range(seconds):
        try:
            await media.GetProfiles()
            return
        except Exception:  # noqa: BLE001
            await asyncio.sleep(1)


def rotation_of(cfg):
    """Rotation in degrees (or "AUTO") from a VideoSourceConfiguration, wherever zeep put the Extension."""
    rot = cfg.Extension.Rotate if cfg.Extension is not None else None
    if rot is not None:
        return 0 if rot.Mode == "OFF" else "AUTO" if rot.Mode == "AUTO" else int(rot.Degree or 0)
    for el in getattr(cfg, "_value_1", None) or []:
        if hasattr(el, "iter"):
            mode = next((e.text for e in el.iter() if e.tag.endswith("Mode")), "OFF")
            deg = next((e.text for e in el.iter() if e.tag.endswith("Degree")), "0")
            return 0 if mode == "OFF" else "AUTO" if mode == "AUTO" else int(deg)
    return 0


async def config_checks(cam, dev, media, profiles):
    print("\n--- configuration over ONVIF ---")
    scopes = await step("GetScopes", dev.GetScopes()) or []
    name_scope = next((x.ScopeItem for x in scopes if "/name/" in x.ScopeItem), None)
    print("      name scope:", name_scope)
    await step("SetScopes (rename)", dev.SetScopes({"Scopes": ["onvif://www.onvif.org/name/Scope%20Test"]}))
    await wait_ready(media)
    s2 = await dev.GetScopes()
    print("      name scope now:", next((x.ScopeItem for x in s2 if "/name/" in x.ScopeItem), None))
    await step("SetHostname", dev.SetHostname({"Name": "onvif-test"}))
    await wait_ready(media)
    h = await step("GetHostname after set", dev.GetHostname())
    print("      hostname:", h and h.Name)
    if h and h.Name != "onvif-test":
        failures.append("hostname not applied")

    vsc = await step("GetVideoSourceConfigurations", media.GetVideoSourceConfigurations())
    sources = await step("GetVideoSources", media.GetVideoSources()) or []
    print("      sources:", [x.token for x in sources])
    opts = await step("GetVideoSourceConfigurationOptions", media.GetVideoSourceConfigurationOptions({}))
    if vsc:
        cfg = vsc[0]
        old_source, old_rot = cfg.SourceToken, rotation_of(cfg)
        print("      current:", old_source, "rotation", old_rot)

        def vsc_with(rotation):
            # zeep parses <tt:Extension> into the preceding xs:any (_value_1), so send a clean structure.
            rot = {"Mode": "OFF"} if rotation == 0 else {"Mode": "AUTO"} if rotation == "AUTO" else {"Mode": "ON", "Degree": rotation}
            return {"Configuration": {"token": cfg.token, "Name": cfg.Name, "UseCount": cfg.UseCount, "SourceToken": cfg.SourceToken,
                                      "Bounds": cfg.Bounds, "Extension": {"Rotate": rot}}, "ForcePersistence": True}

        await step("SetVideoSourceConfiguration (rotate 180)", media.SetVideoSourceConfiguration(vsc_with(180)))
        await wait_ready(media)
        now = rotation_of((await media.GetVideoSourceConfigurations())[0])
        print("      rotation now:", now)
        if now != 180:
            failures.append("rotation not applied")
        await step("SetVideoSourceConfiguration (rotate AUTO)", media.SetVideoSourceConfiguration(vsc_with("AUTO")))
        await wait_ready(media)
        now = rotation_of((await media.GetVideoSourceConfigurations())[0])
        print("      rotation now:", now)
        if now != "AUTO":
            failures.append("auto rotation not applied")
        await step("restore rotation", media.SetVideoSourceConfiguration(vsc_with(old_rot)))
        await wait_ready(media)

    osds = await step("GetOSDs", media.GetOSDs({}))
    print("      OSDs:", osds and [o.token for o in osds])
    await step("GetOSDOptions", media.GetOSDOptions({"ConfigurationToken": "vsc0"}))
    await step("DeleteOSD", media.DeleteOSD({"OSDToken": "osd_datetime"}))
    await wait_ready(media)
    after = await media.GetOSDs({})
    print("      OSDs after delete:", after and [o.token for o in after])
    if after:
        failures.append("OSD not deleted")
    await step("CreateOSD (restore)", media.CreateOSD({"OSD": {"token": "", "VideoSourceConfigurationToken": "vsc0", "Type": "Text",
        "Position": {"Type": "UpperLeft"}, "TextString": {"Type": "DateAndTime"}}}))
    await wait_ready(media)

    token = profiles[0].token
    await step("RemoveAudioEncoderConfiguration", media.RemoveAudioEncoderConfiguration({"ProfileToken": token}))
    await wait_ready(media)
    p = (await media.GetProfiles())[0]
    print("      audio after remove:", p.AudioEncoderConfiguration)
    if p.AudioEncoderConfiguration:
        failures.append("audio not removed")
    await step("AddAudioEncoderConfiguration (restore)", media.AddAudioEncoderConfiguration({"ProfileToken": token, "ConfigurationToken": "aec0"}))
    await wait_ready(media)

    engine = await cam.create_analytics_service()
    await step("Analytics.GetServiceCapabilities", engine.GetServiceCapabilities())
    # Rule operations live on the RuleEngine binding of the analytics WSDL (same XAddr);
    # onvif-zeep-async only knows AnalyticsEngineBinding, so register the other one.
    from onvif.definition import SERVICES
    SERVICES["ruleengine"] = {"ns": "http://www.onvif.org/ver20/analytics/wsdl", "wsdl": "analytics.wsdl", "binding": "RuleEngineBinding"}
    analytics = await cam.create_onvif_service("ruleengine")
    await step("GetSupportedRules", analytics.GetSupportedRules({"ConfigurationToken": "vac0"}))
    rules = await step("GetRules", analytics.GetRules({"ConfigurationToken": "vac0"})) or []
    old_sens = None
    for r in rules:
        old_sens = next((i.Value for i in r.Parameters.SimpleItem if i.Name == "Sensitivity"), None)
        print("      rule:", r.Name, r.Type, "Sensitivity", old_sens)
    await step("ModifyRules (sensitivity 80)", analytics.ModifyRules({"ConfigurationToken": "vac0", "Rule": [{"Name": "MotionDetectorRule",
        "Type": "tt:CellMotionDetector", "Parameters": {"SimpleItem": [{"Name": "Sensitivity", "Value": "80"}]}}]}))
    await wait_ready(media)
    r2 = await analytics.GetRules({"ConfigurationToken": "vac0"})
    print("      sensitivity now:", r2 and r2[0].Parameters.SimpleItem[0].Value)
    if old_sens is not None:
        await step("ModifyRules (restore)", analytics.ModifyRules({"ConfigurationToken": "vac0", "Rule": [{"Name": "MotionDetectorRule",
            "Type": "tt:CellMotionDetector", "Parameters": {"SimpleItem": [{"Name": "Sensitivity", "Value": old_sens}]}}]}))
        await wait_ready(media)

    if name_scope:
        await step("SetScopes (restore name)", dev.SetScopes({"Scopes": [name_scope]}))
        await wait_ready(media)


async def main(host, port, user, pw, with_config=False):
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
        {"VideoSourceToken": "vs0", "ImagingSettings": {"Brightness": 70}}))
    await step("GetOptions", imaging.GetOptions({"VideoSourceToken": "vs0"}))
    if s:  # restore what we changed (IrCutFilter is left alone: OFF switches the phone's torch on)
        await step("restore imaging", imaging.SetImagingSettings(
            {"VideoSourceToken": "vs0", "ImagingSettings": {"Brightness": s.Brightness}}))

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

    if with_config:
        await config_checks(cam, dev, media, profiles)

    await cam.close()
    print("\nFAILURES:", failures if failures else "none")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(asyncio.run(main(*sys.argv[1:5], with_config=len(sys.argv) > 5 and sys.argv[5] == "config")))
