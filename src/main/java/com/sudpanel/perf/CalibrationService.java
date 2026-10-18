package com.sudpanel.perf;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.sudpanel.adb.AdbClient;
import com.sudpanel.config.AppProperties;
import com.sudpanel.locator.UiLocator;

/**
 * 按钮坐标标定与 resource-id 解析服务（2026-10-02 从 PerfRunService 拆出，治理 God 文件）。
 *
 * <p>三大职责：
 * <ul>
 *   <li>resource-id 解析：{@link #rid} / {@link #pkg} / 对局内固定坐标 {@link #fixedCoord}；</li>
 *   <li>旧「按设备序列号」坐标缓存：buttonCoords + button-coords.properties 文件；</li>
 *   <li>分组化坐标方案（v1.2 #4）：分组管理 / 标定 / 启动前强制校验。</li>
 * </ul>
 *
 * <p><b>保密</b>：坐标数值只落本地 config/（gitignore）与 SQLite（data/），严禁进文档、记忆、git。
 */
@Service
public class CalibrationService {

    private static final Logger log = LoggerFactory.getLogger(CalibrationService.class);

    // ---------- 依赖与配置 ----------
    // adb：设备操作客户端（点按、shell 命令、UI dump）
    private final AdbClient adb;
    // locator：UI 布局解析器（按 resource-id / 文本找按钮中心坐标）
    private final UiLocator locator;
    // buttonGroups：按钮坐标分组仓储（v1.2 #4）——分组化的坐标方案，换机可复用、可校验
    private final ButtonGroupRepository buttonGroups;
    // resourceIds：resource-id 映射表（加载自 config/resource-ids.properties）
    private final Properties resourceIds = new Properties();

    /** 按钮坐标缓存：key = serial|buttonKey → 中心坐标。命中则直点，跳过 dump 定位。 */
    private final ConcurrentHashMap<String, int[]> buttonCoords = new ConcurrentHashMap<>();
    // coordsFile：按钮坐标缓存文件的路径（与 resource-ids.properties 同目录，单独文件）
    private final Path coordsFile;

    public CalibrationService(AdbClient adb, UiLocator locator,
                              ButtonGroupRepository buttonGroups, AppProperties props) {
        this.adb = adb;
        this.locator = locator;
        this.buttonGroups = buttonGroups;
        try {
            // 加载 resource-id 配置文件（文件路径来自配置项 resourceIdFile）
            Path p = Path.of(props.resourceIdFile());
            if (Files.exists(p)) {
                // 文件存在才加载；缺失时跳过，后续用到对应 rid 的方法会抛"未配置"异常
                resourceIds.load(Files.newInputStream(p));
            }
        } catch (Exception ignore) {
            // config 缺失在执行时报错
        }
        // 坐标缓存与 resource-ids 同目录，单独文件（本地配置，已在 gitignore 的 config/ 下）
        // 三元表达式：resourceIdFile 无父目录（纯相对路径）时用当前目录下的文件，否则放在其所在目录
        coordsFile = Path.of(props.resourceIdFile()).getParent() == null
                ? Path.of("button-coords.properties")
                : Path.of(props.resourceIdFile()).getParent().resolve("button-coords.properties");
        // 启动时把上次学习到的按钮坐标载入内存缓存（命中则直点，跳过 dump 定位）
        loadCoords();
        migrateLegacyCoordsIfNeeded();
    }

    // ---------- resource-id / 固定坐标解析 ----------

    /** 读取指定 key 的 resource-id 配置；未配置或为空白时抛异常（缺配置应尽早暴露，而不是点不到按钮才报）。 */
    public String rid(String key) {
        String v = resourceIds.getProperty(key);
        if (v == null || v.isBlank()) {
            throw new IllegalStateException("未配置 resource-id: " + key + "（见 config/resource-ids.properties）");
        }
        return v.trim();
    }

    /** 被测 app 的包名（来自配置 perf_package）。 */
    public String pkg() {
        return rid("perf_package");
    }

    /** 对局内按钮坐标由本地配置提供，不依赖缓存和 dump 学习。
     *  持续渲染的界面可能无法通过 uiautomator 获取控件树，需要人工标定或配置。
     *  perf_stop_game 对应结束游戏，perf_exit_game 对应退出房间。
     *  坐标值只存本地 config（gitignore），见 resource-ids.properties 的 perf_stop_game_xy / perf_exit_game_xy。 */
    public int[] fixedCoord(String key) {
        // 读配置里写死的 "x,y" 坐标串
        String v = resourceIds.getProperty(key);
        if (v == null || v.isBlank()) {
            return null; // 未配置：返回 null，由调用方决定兜底方案
        }
        String[] xy = v.trim().split(",");
        if (xy.length != 2) {
            return null; // 格式不对（不是两个数字段）：视为无效配置
        }
        try {
            return new int[]{Integer.parseInt(xy[0].trim()), Integer.parseInt(xy[1].trim())};
        } catch (NumberFormatException e) {
            return null; // 内容不是数字：同样视为无效配置
        }
    }

    /** 仅供本机运行冻结，禁止直接作为公开导出。 */
    public Map<String,String> snapshotResources() {
        Map<String,String> copy = new LinkedHashMap<>();
        resourceIds.stringPropertyNames().stream().sorted().forEach(key -> copy.put(key,resourceIds.getProperty(key)));
        return Map.copyOf(copy);
    }

    public Map<String,int[]> snapshotCoords(String serial) {
        if(!buttonGroups.layout("cache:"+serial).matches(PerfDeviceLayout.read(adb,serial)))return Map.of();
        Map<String,int[]> copy = new LinkedHashMap<>();
        String prefix=serial+"|";
        buttonCoords.forEach((key,value) -> { if(key.startsWith(prefix)) copy.put(key.substring(prefix.length()),value.clone()); });
        return copy;
    }

    // ---------- 旧「按设备序列号」坐标缓存 ----------

    /** 生成按钮坐标的缓存 key：设备序列号 + 按钮 key，保证同一按钮在不同设备上坐标互不串用。 */
    public static String coordKey(String serial, String key) {
        return serial + "|" + key;
    }

    /** 读取某设备某按钮已学习/已标定的坐标（旧缓存快路径）；无则返回 null。 */
    public int[] getCachedCoord(String serial, String key) {
        if(!buttonGroups.layout("cache:"+serial).matches(PerfDeviceLayout.read(adb,serial))) return null;
        return buttonCoords.get(coordKey(serial, key));
    }

    /** 学习某设备某按钮坐标并持久化（运行中定位成功时调用，供后续同设备直点）。 */
    public void learnCoord(String serial, String key, int[] coord) {
        var layout=PerfDeviceLayout.read(adb,serial);
        if(!layout.known()) return;
        if(!buttonGroups.layout("cache:"+serial).matches(layout))buttonCoords.keySet().removeIf(keyName->keyName.startsWith(serial+"|"));
        buttonGroups.bindLayout("cache:"+serial,layout);
        buttonCoords.put(coordKey(serial, key), coord.clone());
        saveCoords(); // 持久化，跨重启复用
    }

    /** 清空按设备序列号的坐标缓存（无效圈自愈：app 更新/布局变后强制下圈重新学习）。 */
    public void clearCoords() {
        buttonCoords.clear();
    }

    /**
     * 兼容迁移（v1.2 #4）：首次引入分组机制时，把旧的「&lt;设备序列号&gt;|&lt;按钮key&gt;」缓存
     * 迁成一个默认分组，避免用户重新标定一遍。
     *
     * <p>只在「分组表为空」且「旧缓存非空」时执行一次；失败只记日志，不影响服务启动。
     */
    private void migrateLegacyCoordsIfNeeded() {
        try {
            if (!buttonGroups.isEmpty() || buttonCoords.isEmpty()) {
                return;
            }
            long gid = buttonGroups.migrateFromLegacy(new LinkedHashMap<>(buttonCoords), null);
            if (gid > 0) {
                log.info("已把 {} 个旧按钮坐标迁入默认分组（groupId={}）", buttonCoords.size(), gid);
            }
        } catch (Exception e) {
            log.warn("旧按钮坐标迁移失败（不影响手动标定）: {}", e.getMessage());
        }
    }

    private void loadCoords() {
        try {
            // 缓存文件不存在说明是首次运行，跳过（点击时会现场学习）
            if (Files.exists(coordsFile)) {
                Properties p = new Properties();
                p.load(Files.newInputStream(coordsFile));
                // 缓存格式：name = "x,y"（name 即 coordKey：serial|按钮key）
                for (String name : p.stringPropertyNames()) {
                    String[] xy = p.getProperty(name).split(",");
                    // 只有恰好两个数字段的行才是合法坐标，脏数据直接忽略
                    if (xy.length == 2) {
                        buttonCoords.put(name, new int[]{Integer.parseInt(xy[0].trim()), Integer.parseInt(xy[1].trim())});
                    }
                }
                log.info("已加载 {} 个按钮坐标缓存（{}）", buttonCoords.size(), coordsFile);
            }
        } catch (Exception e) {
            // 加载失败不致命：后续点击会重新学习坐标
            log.warn("按钮坐标缓存加载失败（不影响运行，首次点击会重新学习）", e);
        }
    }

    // synchronized：与按钮坐标学习路径互斥，避免并发读写缓存文件
    private synchronized void saveCoords() {
        try {
            Properties p = new Properties();
            // 把内存缓存整体导出为 "x,y" 字符串键值对
            buttonCoords.forEach((k, v) -> p.setProperty(k, v[0] + "," + v[1]));
            // 直接覆写缓存文件（文件很小，写盘很快）
            try (OutputStream os = Files.newOutputStream(coordsFile)) {
                p.store(os, "mock button coord cache (auto-learned, per device serial). delete to re-learn.");
            }
        } catch (Exception e) {
            // 持久化失败只影响下次启动的直点命中率，本次运行仍用内存缓存
            log.warn("按钮坐标缓存持久化失败（仅内存生效）", e);
        }
    }

    // ---------- 按钮坐标分组（v1.2 #4） ----------

    /** 列出全部分组（含标定进度与逐按钮中文名明细），供前端下拉与标定面板使用。 */
    public List<Map<String, Object>> listButtonGroups() {
        return buttonGroups.listGroups();
    }

    /** 读取某分组已标定的坐标快照：key → [x,y]（启动时一次性载入内存，每圈点击查内存不查库）；null 分组返回空表。 */
    public Map<String, int[]> groupCoordsOf(Long groupId) {
        return groupId == null ? Map.of() : buttonGroups.coordsOf(groupId);
    }

    /** 新建分组并返回详情（含空进度）。name 为空时由仓储层给「分组N」兜底。 */
    public Map<String, Object> createButtonGroup(String name, String deviceSerial) {
        long gid = buttonGroups.createGroup(name, deviceSerial);
        Map<String, Object> g = buttonGroups.getGroup(gid);
        if (g != null) {
            g.put("progress", buttonGroups.progressOf(gid));
        }
        return g;
    }

    /** 删除分组（含其坐标）。分组不存在时抛异常（由全局异常处理器转成可读 JSON）。 */
    public void deleteButtonGroup(long groupId) {
        if (buttonGroups.getGroup(groupId) == null) {
            throw new IllegalStateException("分组不存在: " + groupId);
        }
        buttonGroups.deleteGroup(groupId);
    }

    /**
     * 标定分组内的按钮。
     *
     * <ul>
     *   <li>指定 key —— 只标定该按钮，返回 {key, label, x, y, status}；找不到时 status=not_found 并给出中文提示。</li>
     *   <li>不指定 key —— 对当前屏做一次 dump，尝试标定全部 6 个按钮（保留旧的「扫全屏」行为）。</li>
     * </ul>
     *
     * <p>status 三种取值：{@code calibrated}（实测并落库）、{@code assumed}（由同位按钮推定，
     * <b>不落库</b>）、{@code not_found}。对局内按钮（结束游戏）走 assumed，
     * 详见 {@link #locateButtonInGroup}。
     */
    public Map<String, Object> calibrateGroup(String deviceSerial, long groupId, String key) {
        if (buttonGroups.getGroup(groupId) == null) {
            throw new IllegalStateException("分组不存在: " + groupId);
        }
        buttonGroups.bindLayout("group:"+groupId,PerfDeviceLayout.read(adb,deviceSerial));
        // 只 dump 一次，作为本次所有按钮查找的输入
        String xml = adb.uiDump(deviceSerial);
        if (xml == null || xml.isEmpty()) {
            throw new IllegalStateException("dump 失败，检查设备连接与屏幕状态");
        }
        // 单按钮标定
        if (key != null && !key.isBlank()) {
            String label = ButtonGroupRepository.labelOf(key);
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("key", key);
            r.put("label", label);
            Located loc = locateButtonInGroup(xml, groupId, key);
            if (loc.coord() != null && "assumed".equals(loc.source())) {
                // 推定坐标不写库：它不是实测值，写进去会和真实标定混在一起、事后无法区分来源，
                // 而且它由同位按钮算出，同位按钮变了应当自动跟着变 —— 写死反而会过期。
                int[] c = loc.coord();
                r.put("x", c[0]);
                r.put("y", c[1]);
                r.put("status", "assumed");
                r.put("message", "「" + label + "」在对局内无法自动定位（dump 必失败），"
                        + "此坐标是按「与大厅同位按钮相同」推定的，未写入分组。"
                        + "如需精确值请在 config 里核对固定坐标。");
                log.info("分组{} 「{}」未实测，按同位关系推定（未落库）", groupId, label);
            } else if (loc.coord() != null) {
                int[] c = loc.coord();
                buttonGroups.upsertCoord(groupId, key, c[0], c[1], "manual");
                r.put("x", c[0]);
                r.put("y", c[1]);
                r.put("status", "calibrated");
                log.info("分组{} 标定「{}」成功 ({},{})", groupId, label, c[0], c[1]);
            } else {
                r.put("status", "not_found");
                r.put("message", "当前界面未找到「" + label + "」，请先让该按钮出现再点标定");
            }
            r.put("progress", buttonGroups.progressOf(groupId));
            return r;
        }
        // 全部标定：逐按钮尝试，成功几个算几个（不因个别失败中断）
        Map<String, Object> result = new LinkedHashMap<>();
        int found = 0;
        for (ButtonGroupRepository.ButtonDef b : ButtonGroupRepository.BUTTONS) {
            Located loc = locateButtonInGroup(xml, groupId, b.key());
            if (loc.coord() != null && "assumed".equals(loc.source())) {
                // 推定结果只回报、不落库（理由同上）
                result.put(b.key(), loc.coord()[0] + "," + loc.coord()[1]);
            } else if (loc.coord() != null) {
                buttonGroups.upsertCoord(groupId, b.key(), loc.coord()[0], loc.coord()[1], "manual");
                result.put(b.key(), loc.coord()[0] + "," + loc.coord()[1]);
                found++;
            } else {
                result.put(b.key(), null);
            }
        }
        result.put("_found", found);
        result.put("progress", buttonGroups.progressOf(groupId));
        log.info("分组{} 整体标定完成：{}/{}", groupId, found, ButtonGroupRepository.BUTTONS.size());
        return result;
    }

    /**
     * 对局内按钮的「推定同位」表：键 = 无法 dump 的对局内按钮，值 = 大厅里的同位按钮。
     *
     * <p>部分布局中，对局内结束按钮与大厅加入按钮位置相同，可以据此提供候选坐标。
     * 这属于布局假设，不能代替控件树识别或实际标定，使用前应复核当前设备和界面。
     * 因此返回 {@code assumed} 状态，与已有标定和控件树定位区分，且不自动落库。
     */
    private static final Map<String, String> ASSUMED_SAME_AS_LOBBY = Map.of(
            "perf_stop_game", "app_common_self_in");

    /**
     * 一次分组内定位的结果。
     *
     * @param coord  按钮中心坐标；null 表示没定位到
     * @param source 坐标来源：{@code existing} 分组里已有 / {@code dump} 控件树实测 /
     *               {@code assumed} 由同位按钮推定（非实测，见 {@link #ASSUMED_SAME_AS_LOBBY}）
     */
    private record Located(int[] coord, String source) {
    }

    /**
     * 在 dump 出的布局树里定位某按钮中心。
     *
     * <p>优先级（v1.7 #31 调整）：
     * <ol>
     *   <li><b>分组里已有该按钮的坐标</b> —— 用户显式标定过的值最可信，直接沿用；</li>
     *   <li><b>dump 定位</b> —— 大厅类按钮的正常路径；</li>
     *   <li><b>推定同位</b> —— 仅对 {@link #ASSUMED_SAME_AS_LOBBY} 里的对局内按钮兜底，
     *       取同位大厅按钮的坐标。原先这一步排在最前，会在用户想单独标定该按钮时
     *       直接覆盖成同位坐标，导致"标了也没用"。</li>
     * </ol>
     */
    private Located locateButtonInGroup(String xml, long groupId, String key) {
        Map<String, int[]> done = buttonGroups.coordsOf(groupId);
        // ① 已有坐标：不覆盖，尊重用户显式标定的结果
        int[] existing = done.get(key);
        if (existing != null) {
            return new Located(existing, "existing");
        }
        // ② dump 定位
        try {
            int[] c = locator.locateCenter(xml, rid(key));
            if (c != null) {
                return new Located(c, "dump");
            }
        } catch (Exception e) {
            // 该按钮未配置 resource-id（或 dump 里没有该节点）：继续走推定兜底
        }
        // ③ 对局内按钮的推定兜底
        String same = ASSUMED_SAME_AS_LOBBY.get(key);
        if (same != null) {
            int[] assumed = done.get(same);
            if (assumed != null) {
                return new Located(assumed, "assumed");
            }
        }
        return new Located(null, null);
    }

    public PerfDeviceLayout recordedLayout(String serial,Long groupId){return buttonGroups.layout(groupId==null?"fixed:"+serial:"group:"+groupId);}
    public Map<String,Object> layoutStatus(String serial,Long groupId) {
        var current=PerfDeviceLayout.read(adb,serial);
        var expected=buttonGroups.layout(groupId==null?"fixed:"+serial:"group:"+groupId);
        return Map.of("state",!expected.known()?"NOT_RECORDED":!current.known()?"UNKNOWN":expected.matches(current)?"MATCH":"STALE","current",current,"recorded",expected,
                "message",expected.matches(current)?"型号、有效尺寸与旋转一致":"坐标来源未知或已过期，请重新标定；固定坐标须现场核对后确认绑定");
    }
    public Map<String,Object> bindFixedLayout(String serial) {buttonGroups.bindLayout("fixed:"+serial,PerfDeviceLayout.read(adb,serial));return layoutStatus(serial,null);}
    public void ensureLayoutReady(String serial,Long groupId) {
        var current=PerfDeviceLayout.read(adb,serial);
        if(groupId!=null&&!buttonGroups.layout("group:"+groupId).matches(current))throw new IllegalStateException("分组坐标与当前型号、尺寸或旋转不匹配，请重新标定");
        var coordinates=groupCoordsOf(groupId);
        if((!coordinates.containsKey("perf_stop_game")||!coordinates.containsKey("perf_exit_game"))&&!buttonGroups.layout("fixed:"+serial).matches(current))throw new IllegalStateException("回落固定坐标未绑定当前布局；请现场核对固定坐标后确认，或补齐组内坐标");
    }

    /**
     * 启动前校验分组是否已全部标定（v1.2 #4 的「强制标定」，v1.7 #31 收敛范围）。
     *
     * <p>只校验<b>必标</b>按钮（能被 dump 定位的大厅按钮）。对局内的「结束游戏 / 退出房间」
     * 属选标：缺了不阻塞启动，运行期会回落到本地固定坐标 —— 但那条固定坐标不随设备走，
     * 因此这里会打一条警告日志，把"正在用固定坐标"这件事显式暴露出来，而不是静默带过。
     * 未指定分组（groupId == null）时跳过校验，保持旧的按设备缓存路径可用。
     *
     * @throws IllegalStateException 分组不存在或仍有必标按钮未标定（消息里列出缺失按钮的**中文名**）
     */
    public void ensureGroupReady(Long groupId) {
        if (groupId == null) {
            return;
        }
        Map<String, Object> g = buttonGroups.getGroup(groupId);
        if (g == null) {
            throw new IllegalStateException("按钮分组不存在: " + groupId);
        }
        List<String> missing = buttonGroups.missingLabels(groupId);
        if (!missing.isEmpty()) {
            throw new IllegalStateException("分组「" + g.get("name") + "」还有 " + missing.size()
                    + " 个按钮未标定：" + String.join("、", missing) + "。请先在「按钮坐标分组」里完成标定再启动。");
        }
        List<String> optionalMissing = buttonGroups.missingOptionalLabels(groupId);
        if (!optionalMissing.isEmpty()) {
            // 警告而非拦截：这两个按钮在对局内无法 dump，只能间接标定；缺了会走固定坐标
            log.warn("分组「{}」未标定选配按钮 [{}]，运行期将回落到本地固定坐标（该坐标不随设备变化，"
                    + "换设备跑压测前建议先在组内标定它们）", g.get("name"), String.join("、", optionalMissing));
        }
    }
}
