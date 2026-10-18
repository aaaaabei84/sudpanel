package com.sudpanel.perf;

import com.sudpanel.adb.AdbClient;
import com.sudpanel.adb.Device;
import com.sudpanel.api.ApiBodyAssert;
import com.sudpanel.api.ApiRepository;
import com.sudpanel.api.ApiRequestEngine;
import com.sudpanel.api.ApiSpec;
import com.sudpanel.api.ApiVariables;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/** 启动前只读检查：无点按、唤醒、日志清理、业务网络请求或运行记录写入。 */
@Service
public class PerfPreflightService {
    public record Request(String deviceSerial, String mode, List<Long> gameIds, Long buttonGroupId) { }
    public record Check(String key, String name, String status, String message) { }
    public record Result(boolean ready, String checkedAt, List<Check> checks) { }
    private final AdbClient adb;
    private final PerfRepository perf;
    private final ApiRepository api;
    private final CalibrationService calibration;

    public PerfPreflightService(AdbClient adb, PerfRepository perf, ApiRepository api, CalibrationService calibration) {
        this.adb = adb; this.perf = perf; this.api = api; this.calibration = calibration;
    }

    public Result check(Request request) { return check(request, null); }

    public Result check(Request request, PerfExecutionPlan plan) {
        if (request == null) throw new IllegalArgumentException("检查参数不能为空");
        List<Check> checks = new ArrayList<>();
        checkDevice(request.deviceSerial(), checks, plan);
        boolean interfaceDriven = "NORMAL".equals(request.mode()) || "AI_ROOM".equals(request.mode());
        if (!interfaceDriven && !"EARLY_SETTLE".equals(request.mode()))
            checks.add(new Check("mode", "执行模式", "BLOCK", "请选择有效执行模式"));
        if ("EARLY_SETTLE".equals(request.mode())) {
            try { calibration.ensureLayoutReady(request.deviceSerial(),request.buttonGroupId()); if (plan == null) calibration.ensureGroupReady(request.buttonGroupId());
                else if (!plan.resources().containsKey("perf_package")) throw new IllegalStateException("缺少冻结标定");
                checks.add(pass("calibration", "按钮标定")); }
            catch (Exception incomplete) { checks.add(new Check("calibration", "按钮标定", "BLOCK", "异常时序模式坐标未标定、来源未知或设备布局已变化，请重新标定并核对固定坐标来源")); }
        }
        Map<String, ApiSpec> bindings;
        if (interfaceDriven && plan != null) { bindings = new LinkedHashMap<>(); plan.requests().values().forEach(item -> bindings.put(item.role(),item.spec())); }
        else bindings = interfaceDriven ? bindings(checks) : Map.of();
        if (request.gameIds() == null || request.gameIds().isEmpty()) {
            checks.add(new Check("games", "目标游戏", "BLOCK", "至少选择一个游戏"));
        } else {
            int number = 0;
            for (Long gameId : request.gameIds()) {
                String key = "game-" + (++number);
                Map<String, Object> game = gameId == null ? null : plan == null ? perf.getGame(gameId) : plan.games().get(gameId);
                if (game == null) { checks.add(new Check(key, "第 " + number + " 个游戏", "BLOCK", "游戏已不存在，请重新选择")); continue; }
                if (!interfaceDriven) { checks.add(pass(key, "第 " + number + " 个游戏")); continue; }
                inspectGame(key, number, game, bindings, checks, plan);
            }
        }
        checks.add(new Check("scope", "检查范围", "INFO", "仅检查当前设备与配置，不点手机、不发业务请求；不保证网络可达、登录态或游戏服务成功；启动时校验并冻结本次有效配置"));
        return new Result(checks.stream().noneMatch(item -> "BLOCK".equals(item.status())), Instant.now().toString(), List.copyOf(checks));
    }

    public Result device(String serial) {
        List<Check> checks = new ArrayList<>(); checkDevice(serial, checks);
        return new Result(checks.stream().noneMatch(item -> "BLOCK".equals(item.status())), Instant.now().toString(), checks);
    }

    private void checkDevice(String serial, List<Check> checks) { checkDevice(serial, checks, null); }

    private void checkDevice(String serial, List<Check> checks, PerfExecutionPlan plan) {
        if (serial == null || serial.isBlank()) {
            checks.add(new Check("device", "设备连接", "BLOCK", "请先选择在线设备")); return;
        }
        Device target;
        try { target = adb.devices().stream().filter(item -> serial.equals(item.serial())).findFirst().orElse(null); }
        catch (Exception failure) { checks.add(new Check("device", "设备连接", "BLOCK", "ADB 查询失败，请检查工具路径与服务")); return; }
        if (target == null || !"device".equals(target.status())) {
            String cause = target == null ? "未找到设备" : "unauthorized".equals(target.status()) ? "手机尚未授权 USB 调试" : "设备离线或尚未就绪";
            checks.add(new Check("device", "设备连接", "BLOCK", cause)); return;
        }
        checks.add(pass("device", "设备连接"));
        AdbClient.KeyguardState keyguard;
        try { keyguard = adb.keyguardState(serial); }
        catch (RuntimeException unavailable) { keyguard = AdbClient.KeyguardState.UNKNOWN; }
        if (keyguard == AdbClient.KeyguardState.LOCKED)
            checks.add(new Check("keyguard", "锁屏状态", "BLOCK", "系统报告锁屏或输入受限，请先在手机上手动解锁后重新检查；本次检查不唤醒或解锁设备"));
        else if (keyguard == AdbClient.KeyguardState.UNLOCKED)
            checks.add(new Check("keyguard", "锁屏状态", "PASS", "系统锁屏/输入限制字段为未限制"));
        else
            checks.add(new Check("keyguard", "锁屏状态", "WARN", "无法确认锁屏状态，请在手机上核对已解锁；本次检查不唤醒或解锁设备"));
        var health=PerfDeviceHealth.read(adb,serial);var policy=PerfDeviceHealth.Policy.of(plan==null?perf.getSettings(List.of(PerfDeviceHealth.TEMP_WARN,PerfDeviceHealth.THERMAL_WARN)):plan.settings());
        var healthWarnings=PerfDeviceHealth.warnings(health,policy);
        checks.add(new Check("health","设备采样条件",healthWarnings.isEmpty()?"PASS":"WARN","温度="+(health.temperatureC()==null?"未知":health.temperatureC()+"℃")+"；供电="+(health.powered()==null?"未知":health.powered()?"外部供电":"电池")+"；thermal="+(health.thermalStatus()==null?"未知":health.thermalStatus())+(healthWarnings.isEmpty()?"":"；"+String.join("；",healthWarnings))+"（仅提示，不改变设备设置）"));
        String pkg;
        try { pkg = plan == null ? calibration.pkg() : plan.resources().get("perf_package"); }
        catch (Exception missing) { checks.add(new Check("package", "被测应用", "BLOCK", "未配置被测应用包名")); return; }
        if(pkg!=null)pkg=pkg.trim();
        if (pkg == null || !pkg.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")) {
            checks.add(new Check("package", "被测应用", "BLOCK", "被测应用包名配置无效")); return;
        }
        AdbClient.ExecResult installed = adb.shell(serial, "pm path " + pkg, 10);
        if (!installed.ok()) checks.add(new Check("package", "被测应用安装", "BLOCK", "无法读取应用安装状态"));
        else if (!installed.out().contains("package:")) checks.add(new Check("package", "被测应用安装", "BLOCK", "被测应用尚未安装"));
        else checks.add(pass("package", "被测应用安装"));
        String foreground = adb.foregroundPackage(serial);
        checks.add(new Check("foreground", "当前前台应用", pkg.equals(foreground) ? "PASS" : "WARN",
                pkg.equals(foreground) ? "被测应用在前台" : "被测应用未在前台或无法读取；执行时会尝试拉起，本次检查不操作手机"));
        boolean screenOn = adb.isScreenOn(serial);
        checks.add(new Check("screen", "屏幕状态", screenOn ? "PASS" : "WARN",
                screenOn ? "屏幕已亮" : "屏幕未亮或无法读取；本次检查不唤醒设备"));
    }

    private Map<String, ApiSpec> bindings(List<Check> checks) {
        List<ApiSpec> cases = api.listCases();
        Map<String, String> saved = perf.getSettings(PerfRepository.aiRoomSettingKeys());
        Map<String, ApiSpec> found = new LinkedHashMap<>();
        String[][] roles = {{"推人机", PerfRepository.SETTING_AI_CASE_BATCH, "批量加入"},
                {"开局", PerfRepository.SETTING_AI_CASE_START, "游戏开始"},
                {"结束游戏", PerfRepository.SETTING_AI_CASE_END, "游戏结束"},
                {"清空房间", PerfRepository.SETTING_AI_CASE_CLEAR, "房间清理"}};
        for (String[] role : roles) {
            Long id = PerfRunService.resolveCaseRef(saved.get(role[1]), role[2], cases);
            ApiSpec spec = id == null ? null : cases.stream().filter(item -> id.equals(item.id())).findFirst().orElse(null);
            if (spec == null) checks.add(new Check("binding-" + role[1], role[0] + "用例", "BLOCK", "未找到唯一可用绑定，请在运行控制中选择"));
            else {
                found.put(role[0], spec);
                checks.add(new Check("binding-" + role[0], role[0] + "用例", spec.enabled() ? "PASS" : "WARN",
                        spec.enabled() ? "已解析绑定" : "已绑定用例处于禁用状态；性能引擎仍按明确绑定调用，请核对"));
            }
        }
        return found;
    }

    private void inspectGame(String key, int number, Map<String, Object> game, Map<String, ApiSpec> bindings, List<Check> checks, PerfExecutionPlan plan) {
        try {
            String override = string(game.get("bodyOverride"));
            Integer humans = integer(game.get("humanCount")), ais = integer(game.get("aiCount"));
            String global = plan == null ? perf.getSetting(PerfRepository.SETTING_AI_COUNT) : plan.aiCount() == null ? null : plan.aiCount().toString();
            if (ais == null && global != null && !global.isBlank()) ais = Integer.valueOf(global.trim());
            if (override != null) AiPlayers.requireValidBodyCount(override);
            else if (humans != null || ais != null) AiPlayers.requireValidCounts(humans == null ? 1 : humans, ais == null ? 0 : ais);
            else if (bindings.get("推人机") != null) AiPlayers.requireValidBodyCount(bindings.get("推人机").body());
            checks.add(pass(key + "-roster", "第 " + number + " 个游戏总人数"));
        } catch (Exception invalid) { checks.add(new Check(key + "-roster", "第 " + number + " 个游戏总人数", "BLOCK", "有效名单总人数必须为 1～64，请检查真人、人机、全局回落或自定义推送体")); }
        for (Map.Entry<String, ApiSpec> binding : bindings.entrySet()) {
            try {
                ApiSpec spec = binding.getValue();
                long frozenCaseId=spec.id();
                var frozen = plan == null ? null : plan.requests().values().stream().filter(item -> item.spec().id()==frozenCaseId).findFirst().orElseThrow();
                long envId = frozen == null ? api.resolveEnvForSpec(spec, null) : frozen.environment().envId();
                ApiVariables.VarSet vars = frozen == null ? api.getVars(envId) : frozen.environment().vars();
                Map<String, String> identity = new LinkedHashMap<>();
                for (String field : List.of("mg_id", "app_id", "room_id")) {
                    String gameKey = switch (field) { case "mg_id" -> "mgId"; case "app_id" -> "appId"; default -> "roomId"; };
                    String value = string(game.get(gameKey));
                    if (value != null) identity.put(field, value);
                }
                for (String field : List.of("mg_id", "app_id", "room_id")) {
                    boolean effective = identity.containsKey(field) || vars.body().stream().anyMatch(variable -> field.equals(variable.name()) && string(variable.value()) != null);
                    if (!effective) checks.add(new Check(key + "-identity-" + field + "-" + binding.getKey(), "第 " + number + " 个游戏 " + field, "WARN", "游戏配置与该步骤环境中未找到此身份变量，请核对用例是否使用字面值或其他变量"));
                }
                if ("推人机".equals(binding.getKey()) && string(game.get("bodyOverride")) != null)
                    spec = new ApiSpec(spec.id(), spec.name(), spec.method(), spec.url(), spec.headers(), string(game.get("bodyOverride")), spec.note(), spec.expectStatus(), spec.expectCode(), spec.timeoutMs(), spec.insecure(), spec.enabled(), spec.groupId(), spec.asserts(), spec.options());
                ApiBodyAssert.validateRules(spec.asserts());
                var environment = frozen == null ? new ApiRequestEngine.Environment(envId, api.getEnv(envId).name(), Instant.now().toString(), vars, api.getSignConfig(envId),api.getEnv(envId).strictVariables(),null) : frozen.environment();
                ApiRequestEngine.Built built = ApiRequestEngine.build(spec, environment, identity, Map.of());
                ApiRequestEngine.assertionRules(spec.asserts(), environment, Map.of(), Map.of(), identity);
                boolean strict=spec.options()!=null&&spec.options().strictVariables()!=null?spec.options().strictVariables():Boolean.TRUE.equals(environment.strictVariables());
                if ((strict && !built.unresolved().isEmpty())
                        || built.unresolved().stream().anyMatch(name -> name.startsWith("$") || name.startsWith("run.") || name.startsWith("data.")))
                    throw new IllegalArgumentException("unresolved protected variable");
                java.net.URI uri = java.net.URI.create(built.url());
                if (!List.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null) throw new IllegalArgumentException("invalid uri");
                var http = java.net.http.HttpRequest.newBuilder(uri).method(built.method(),
                        built.body() == null ? java.net.http.HttpRequest.BodyPublishers.noBody()
                                : java.net.http.HttpRequest.BodyPublishers.ofString(built.body()));
                built.headers().forEach(http::header);
                http.build();
                checks.add(new Check(key + "-request-" + binding.getKey(), "第 " + number + " 个游戏 " + binding.getKey() + "模板",
                        built.unresolved().isEmpty() ? "PASS" : "WARN",
                        built.unresolved().isEmpty() ? "可组装" : "存在未解析的旧变量，发送前请在接口测试页核对"));
            } catch (Exception invalid) { checks.add(new Check(key + "-request-" + binding.getKey(), "第 " + number + " 个游戏 " + binding.getKey() + "模板", "BLOCK", "模板、变量、鉴权或断言配置无法组装，请在接口测试页预览检查")); }
        }
    }

    private static Check pass(String key, String name) { return new Check(key, name, "PASS", "检查通过"); }
    private static String string(Object value) { return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value); }
    private static Integer integer(Object value) { return value instanceof Number number ? number.intValue() : null; }
}
