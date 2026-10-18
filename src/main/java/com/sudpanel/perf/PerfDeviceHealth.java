package com.sudpanel.perf;

import com.sudpanel.adb.AdbClient;
import java.time.Instant;
import java.util.*;
import java.util.regex.*;

/** 只读设备条件，未知值保持 null；阈值仅提示，绝不改变设备供电/温控。 */
public final class PerfDeviceHealth {
    public static final String TEMP_WARN="perf_health_temperature_warn_c", THERMAL_WARN="perf_health_thermal_warn";
    public record Snapshot(String checkedAt,Integer batteryLevel,Double temperatureC,Boolean powered,Integer thermalStatus,String batterySource,String thermalSource) {}
    public record Policy(Double temperatureWarnC,Integer thermalWarn) {
        public Policy {if(temperatureWarnC!=null&&(!Double.isFinite(temperatureWarnC)||temperatureWarnC<0||temperatureWarnC>100))throw new IllegalArgumentException("温度提示阈值须为0～100摄氏度或留空");if(thermalWarn!=null&&(thermalWarn<0||thermalWarn>6))throw new IllegalArgumentException("thermal提示阈值须为0～6或留空");}
        public static Policy of(Map<String,String> settings){try{return new Policy(number(settings.get(TEMP_WARN)),integer(settings.get(THERMAL_WARN)));}catch(NumberFormatException invalid){throw new IllegalArgumentException("设备健康阈值格式无效");}}
        private static Double number(String v){return v==null||v.isBlank()?null:Double.valueOf(v);}
        private static Integer integer(String v){return v==null||v.isBlank()?null:Integer.valueOf(v);}
    }
    private PerfDeviceHealth(){}
    public static Snapshot read(AdbClient adb,String serial){
        AdbClient.ExecResult battery=null,thermal=null;
        try{battery=adb.shell(serial,"dumpsys battery",5);}catch(Exception ignored){}
        try{thermal=adb.shell(serial,"dumpsys thermalservice",5);}catch(Exception ignored){}
        return parse(battery,thermal);
    }
    static Snapshot parse(AdbClient.ExecResult battery,AdbClient.ExecResult thermal){
        String b=battery==null?"":battery.out(),t=thermal==null?"":thermal.out();
        boolean bok=battery!=null&&battery.ok(),tok=thermal!=null&&thermal.ok();
        Integer level=bok?field(b,"(?m)^\\s*level:\\s*(\\d+)\\s*$"):null,temp=bok?field(b,"(?m)^\\s*temperature:\\s*(-?\\d+)\\s*$"):null;
        if(level!=null&&(level<0||level>100))level=null;
        Double degrees=temp!=null&&temp>=-500&&temp<=1500?temp/10.0:null;
        Boolean powered=null;
        if(bok){var m=Pattern.compile("(?m)^\\s*(?:AC|USB|Wireless|Dock) powered:\\s*(true|false)\\s*$",Pattern.CASE_INSENSITIVE).matcher(b);boolean seen=false,on=false;while(m.find()){seen=true;on|=Boolean.parseBoolean(m.group(1));}if(seen)powered=on;}
        Integer status=tok?field(t,"(?mi)^\\s*(?:Thermal Status|mStatus|status):?\\s*[=:]?\\s*([0-6])\\s*$"):null;
        return new Snapshot(Instant.now().toString(),level,degrees,powered,status,bok?"DUMPSYS_BATTERY":"UNAVAILABLE",tok?"DUMPSYS_THERMALSERVICE":"UNAVAILABLE");
    }
    private static Integer field(String text,String regex){var m=Pattern.compile(regex).matcher(text);try{return m.find()?Integer.valueOf(m.group(1)):null;}catch(NumberFormatException e){return null;}}
    public static List<String> warnings(Snapshot health,Policy policy){var result=new ArrayList<String>();if(health==null){result.add("设备条件未记录");return result;}if(health.temperatureC()==null)result.add("设备温度未知");else if(policy.temperatureWarnC()!=null&&health.temperatureC()>=policy.temperatureWarnC())result.add("设备温度达到配置提示阈值");if(health.thermalStatus()==null)result.add("thermal状态未知");else if(policy.thermalWarn()!=null&&health.thermalStatus()>=policy.thermalWarn())result.add("thermal状态达到配置提示阈值");if(health.powered()==null)result.add("供电状态未知");return result;}
}
