package com.sudpanel.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.sudpanel.adb.AdbClient;
import com.sudpanel.adb.Device;
import com.sudpanel.config.AppProperties;
import com.sudpanel.logcat.LogcatKeywordRepository;
import com.sudpanel.logcat.LogcatTail;

/**
 * 上报查询的接口层（2026-10-06，需求 #60）。
 *
 * <p>把 Android Studio 里「实时看手机上报日志」这件事搬进面板：界面按固定间隔调
 * {@code /api/logcat/tail}，把上次收到的最后一行作为锚点带回来，只拿新增的那一段。
 *
 * <p><b>为什么不在这里做过滤</b>：关键字与日志级别的过滤都放在前端。原因是切换过滤条件
 * 不该重抓日志 —— 级别字符就在行的 meta 里，前端手里已经有全部内容，本地筛比让设备重跑一次
 * 快得多，也让「改一下过滤看看」变成瞬间的事。
 *
 * <p><b>为什么不落库</b>：这个功能定位是「看正在发生什么」，不是留痕（要留痕的是功能测试的
 * 命中日志，那套走 test_result）。不落库也意味着没有清理策略、没有库膨胀。
 * <b>注意这条只针对日志内容本身</b> —— 使用者自己起的「过滤关键字快捷方式」
 * （{@link #saveKeyword}，需求 #66）是配置而不是留痕，那个落库（见 {@link LogcatKeywordRepository}）。
 */
@RestController
@RequestMapping("/api/logcat")
public class LogcatController {

    private final AdbClient adb;
    private final int tailLines;
    private final LogcatKeywordRepository keywordRepo;

    /**
     * 设备在线状态的缓存（2026-10-06，需求 #60 把轮询提到每秒 4 次时新增）。
     *
     * <p><b>为什么必须缓存</b>：判断在线要跑一次 {@code adb devices}，那是**额外**一次 adb 调用。
     * 每秒 1 次时总开销是「devices + logcat」两次，还撑得住；提到每秒 4 次就变成 8 次/秒，
     * 两次 adb 会互相挤，表现为取文变慢、界面一顿一顿。
     *
     * <p>TTL 取 2 秒：设备拔掉后最迟 2 秒被发现（界面上是一次提示 + 停止轮询，晚 2 秒无所谓），
     * 换来 adb 调用从 8 次/秒降到约 4.5 次/秒。
     *
     * <p>缓存必须**连着设备号一起**存：只缓存「上一个是/否在线」的话，换设备时会把前一台的
     * 状态用到新设备上，那会表现为「刚选好设备却说过滤不到」，而且 2 秒后自己又好了 —— 最难查的一类。
     */
    private static final long DEVICE_CHECK_TTL_MS = 2000;

    /**
     * 一次检查的完整结果。**刻意打包成一个不可变对象**而不是三个 volatile 字段：
     * 分开写的时候，读侧可能读到「新设备号 + 旧状态」这种中间组合 ——
     * 那就成了「刚换设备却说过滤不到、2 秒后自己又好了」，最难查的一类。
     */
    private record DeviceCache(String serial, boolean online, long at) {
    }

    private volatile DeviceCache deviceCache = null;

    public LogcatController(AdbClient adb, AppProperties props, LogcatKeywordRepository keywordRepo) {
        this.adb = adb;
        this.tailLines = props.logcatTailLines();
        this.keywordRepo = keywordRepo;
    }

    /**
     * 取文请求体。
     *
     * @param serial 目标设备序列号
     * @param anchor 上次收到的最后一行（原样回传）；首次不传
     */
    public record TailRequest(String serial, String anchor) {
    }

    /** 清空请求体。 */
    public record ClearRequest(String serial) {
    }

    /**
     * 取当前设备 logcat 的增量。
     *
     * <p>返回 {@code {lines, anchor, reset, total, deviceOffline}}：
     * <ul>
     *   <li>{@code reset=true} —— 锚点失效（被环形缓冲挤掉），这 {@code lines} 是**整段**，
     *       界面必须替换而不是追加；</li>
     *   <li>{@code deviceOffline=true} —— 设备不在了。这不是错误，是实时查看的常态
     *       （拔线、锁屏掉线），界面据此提示并停止轮询，而不是报一条看不懂的异常。</li>
     * </ul>
     */
    @PostMapping("/tail")
    public Map<String, Object> tail(@RequestBody TailRequest req) {
        String serial = req == null ? null : req.serial();
        Map<String, Object> out = new LinkedHashMap<>();
        if (serial == null || serial.isBlank()) {
            out.put("lines", List.of());
            out.put("anchor", null);
            out.put("reset", false);
            out.put("total", 0);
            out.put("deviceOffline", true);
            out.put("message", "没有选择设备");
            return out;
        }
        if (!isOnline(serial)) {
            out.put("lines", List.of());
            out.put("anchor", null);
            out.put("reset", false);
            out.put("total", 0);
            out.put("deviceOffline", true);
            out.put("message", "设备不在线：" + serial);
            return out;
        }

        String dump = adb.logcatTail(serial, tailLines);
        LogcatTail.Result r = LogcatTail.diff(dump, req.anchor());
        out.put("lines", r.lines());
        out.put("anchor", r.anchor());
        out.put("reset", r.reset());
        out.put("total", r.total());
        out.put("deviceOffline", false);
        return out;
    }

    /**
     * 清空设备的 logcat 缓冲。
     *
     * <p>之所以要有这个入口：实时看日志时最常用的动作就是「先把缓冲清干净，再去操作手机」，
     * 这样屏幕上出现的每一行都确定是自己刚触发的。没有它就只能靠肉眼找时间点。
     */
    @PostMapping("/clear")
    public Map<String, Object> clear(@RequestBody ClearRequest req) {
        String serial = req == null ? null : req.serial();
        Map<String, Object> out = new LinkedHashMap<>();
        if (serial == null || serial.isBlank()) {
            out.put("ok", false);
            out.put("message", "没有选择设备");
            return out;
        }
        if (!isOnline(serial)) {
            out.put("ok", false);
            out.put("message", "设备不在线：" + serial);
            return out;
        }
        adb.logcatClear(serial);
        out.put("ok", true);
        return out;
    }

    /**
     * 设备是否在线（带 TTL 缓存，见 {@link #DEVICE_CHECK_TTL_MS}）。
     *
     * <p>用状态判定而不是直接跑一条命令：拔线时命令要挂到超时才返回，轮询里等不起。
     */
    private boolean isOnline(String serial) {
        long now = System.currentTimeMillis();
        DeviceCache c = deviceCache;
        if (c != null && serial.equals(c.serial()) && now - c.at() < DEVICE_CHECK_TTL_MS) {
            return c.online();
        }
        boolean online = false;
        for (Device d : adb.devices()) {
            if (serial.equals(d.serial())) {
                online = "device".equals(d.status());
                break;
            }
        }
        // 一次赋值即整体生效：读侧要么看到旧的整套，要么看到新的整套
        deviceCache = new DeviceCache(serial, online, now);
        return online;
    }

    // ==================== 过滤关键字库（2026-10-06，需求 #66）====================
    //
    // 这三个端点与上面的 tail/clear 不同：它们**不碰设备**，纯配置读写。
    // 之所以还是放在这个 Controller 里而不是另开一个：界面上它们只服务于上报查询这一页，
    // 路径前缀 /api/logcat 也就该是同一个人管；拆出去只会让「这一页用到的接口散在两处」。

    /**
     * 保存请求体。
     *
     * @param keyword 关键字本体（必填，会 trim）
     * @param note    注释（可空）—— 用来记「这个关键字是在找什么问题的」，下拉里显示在关键字旁边
     */
    public record KeywordSaveRequest(String keyword, String note) {
    }

    /** 列出保存过的过滤关键字（按加入顺序）。 */
    @GetMapping("/keywords")
    public List<LogcatKeywordRepository.Keyword> keywords() {
        return keywordRepo.list();
    }

    /**
     * 保存一个过滤关键字。
     *
     * <p>同一个关键字再存一次是**更新它的注释**，不是新增一条（唯一索引 + upsert）——
     * 否则下拉里会出现两条看起来一模一样的项，点了才知道差别。
     *
     * <p>关键字为空时回 {@code {"ok": false, "message": ...}} 而不是抛异常：
     * 这是使用者在界面上的正常操作失误，不该在日志里留一条 500，也不该让界面弹一个英文堆栈。
     *
     * <p>成功时把**整份列表**一起回传：界面存完要立刻刷新下拉，再发一次 GET 是多余的往返，
     * 而且两次请求之间列表可能已经变了（多设备/多窗口时）。
     */
    @PostMapping("/keywords")
    public Map<String, Object> saveKeyword(@RequestBody KeywordSaveRequest req) {
        Map<String, Object> out = new LinkedHashMap<>();
        LogcatKeywordRepository.Keyword saved = keywordRepo.save(
                req == null ? null : req.keyword(),
                req == null ? null : req.note());
        if (saved == null) {
            out.put("ok", false);
            out.put("message", "关键字不能为空");
            return out;
        }
        out.put("ok", true);
        out.put("item", saved);
        out.put("items", keywordRepo.list());
        return out;
    }

    /**
     * 删除一个保存过的关键字。
     *
     * <p>删不存在的 id 回 {@code ok=false} 而不是 404：界面上的「删两次」（连点、
     * 或另一个窗口已经删过）不是错误，回 404 会让浏览器控制台出现一条红字，
     * 而那正是使用者贴过来问「是不是坏了」的东西。
     */
    @DeleteMapping("/keywords/{id}")
    public Map<String, Object> deleteKeyword(@PathVariable long id) {
        Map<String, Object> out = new LinkedHashMap<>();
        boolean removed = keywordRepo.delete(id);
        out.put("ok", removed);
        if (!removed) {
            out.put("message", "这条关键字已经不在了");
        }
        out.put("items", keywordRepo.list());
        return out;
    }
}
