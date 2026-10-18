package com.sudpanel.perf;

import com.sudpanel.adb.AdbClient;
import java.util.regex.Pattern;

/** 几何来源未知时不能认可旧坐标；不操作设备设置。 */
public record PerfDeviceLayout(String model, Integer width, Integer height, Integer rotation) {
    public boolean known() { return model != null && !model.isBlank() && !model.equalsIgnoreCase("unknown") && width != null && height != null && width > 0 && height > 0 && rotation != null && rotation >= 0 && rotation <= 3; }
    public static PerfDeviceLayout unknown() { return new PerfDeviceLayout(null,null,null,null); }
    public static PerfDeviceLayout read(AdbClient adb,String serial) {
        try { if(adb.devices().stream().noneMatch(device->serial.equals(device.serial())&&"device".equals(device.status())))return unknown();var result=adb.shell(serial,"getprop ro.product.model; wm size; dumpsys input",10); return result!=null&&result.ok()?parse(result.out()):unknown(); }
        catch(Exception failure) { return unknown(); }
    }
    public static PerfDeviceLayout parse(String text) {
        if(text==null||text.isBlank()) return unknown();
        String model=text.lines().findFirst().orElse("").trim();
        if(model.isBlank()||model.length()>120||model.contains(":")) model=null;
        Integer width=null,height=null,rotation=null;
        var size=Pattern.compile("(?:Physical|Override) size:\\s*(\\d+)x(\\d+)").matcher(text);
        while(size.find()) { try { width=Integer.valueOf(size.group(1));height=Integer.valueOf(size.group(2)); } catch(NumberFormatException invalid){return unknown();} }
        var orientation=Pattern.compile("(?:SurfaceOrientation\\s*:\\s*|mCurrentRotation\\s*=\\s*(?:ROTATION_)?)([0-3])(?:\\D|$)").matcher(text);
        if(orientation.find()) rotation=Integer.valueOf(orientation.group(1));
        if(rotation==null){var viewport=Pattern.compile("Viewport\\{[^}]*displayId=0[^}]*orientation=([0-3])(?:\\D|$)").matcher(text);if(viewport.find())rotation=Integer.valueOf(viewport.group(1));}
        return new PerfDeviceLayout(model,width,height,rotation);
    }
    public boolean matches(PerfDeviceLayout other) { return known() && other != null && other.known() && equals(other); }
}
