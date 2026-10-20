package pl.arhea.onvifipcam.onvif

class ImagingService {
    fun handle(c: Ctx): String? {
        val img = c.device.imaging ?: return null
        val op = c.op
        return when (c.soap.opName) {
            "GetServiceCapabilities" -> "<timg:GetServiceCapabilitiesResponse>${capabilities()}</timg:GetServiceCapabilitiesResponse>"
            "GetImagingSettings" -> {
                val s = img.settings()
                "<timg:GetImagingSettingsResponse><timg:ImagingSettings>" +
                    "<tt:Brightness>${s.brightness}</tt:Brightness>" +
                    "<tt:Focus><tt:AutoFocusMode>${if (s.autoFocus) "AUTO" else "MANUAL"}</tt:AutoFocusMode>" +
                    "<tt:DefaultSpeed>1</tt:DefaultSpeed><tt:NearLimit>0</tt:NearLimit><tt:FarLimit>0</tt:FarLimit></tt:Focus>" +
                    (if (s.hasLight) "<tt:IrCutFilter>${if (s.light) "OFF" else "ON"}</tt:IrCutFilter>" else "") +
                    "</timg:ImagingSettings></timg:GetImagingSettingsResponse>"
            }
            "SetImagingSettings" -> {
                val s = op.child("ImagingSettings")
                val focus = s?.child("Focus")
                img.apply(
                    brightness = s?.float("Brightness"),
                    autoFocus = focus?.childText("AutoFocusMode")?.let { it.equals("AUTO", ignoreCase = true) },
                    focusPosition = null,
                    light = s?.childText("IrCutFilter")?.let { it.equals("OFF", ignoreCase = true) },
                )
                "<timg:SetImagingSettingsResponse/>"
            }
            "GetOptions" -> {
                val s = img.settings()
                "<timg:GetOptionsResponse><timg:ImagingOptions>" +
                    "<tt:Brightness><tt:Min>0</tt:Min><tt:Max>100</tt:Max></tt:Brightness>" +
                    "<tt:Focus><tt:AutoFocusModes>AUTO</tt:AutoFocusModes><tt:AutoFocusModes>MANUAL</tt:AutoFocusModes>" +
                    "<tt:DefaultSpeed><tt:Min>1</tt:Min><tt:Max>1</tt:Max></tt:DefaultSpeed>" +
                    "<tt:NearLimit><tt:Min>0</tt:Min><tt:Max>0</tt:Max></tt:NearLimit>" +
                    "<tt:FarLimit><tt:Min>0</tt:Min><tt:Max>0</tt:Max></tt:FarLimit></tt:Focus>" +
                    (if (s.hasLight) "<tt:IrCutFilterModes>ON</tt:IrCutFilterModes><tt:IrCutFilterModes>OFF</tt:IrCutFilterModes>" else "") +
                    "</timg:ImagingOptions></timg:GetOptionsResponse>"
            }
            "GetMoveOptions" -> "<timg:GetMoveOptionsResponse><timg:MoveOptions><tt:Absolute>" +
                "<tt:Position><tt:Min>0</tt:Min><tt:Max>1</tt:Max></tt:Position></tt:Absolute></timg:MoveOptions></timg:GetMoveOptionsResponse>"
            "Move" -> {
                val pos = op.path("Focus", "Absolute")?.float("Position")
                if (pos != null) img.apply(null, false, pos, null)
                "<timg:MoveResponse/>"
            }
            "Stop" -> "<timg:StopResponse/>"
            "GetStatus" -> {
                val s = img.settings()
                "<timg:GetStatusResponse><timg:Status><tt:FocusStatus20><tt:Position>${s.focusPosition}</tt:Position>" +
                    "<tt:MoveStatus>IDLE</tt:MoveStatus></tt:FocusStatus20></timg:Status></timg:GetStatusResponse>"
            }
            else -> null
        }
    }

    companion object {
        fun capabilities() = "<timg:Capabilities ImageStabilization=\"false\" Presets=\"false\" AdaptablePreset=\"false\"/>"
    }
}
