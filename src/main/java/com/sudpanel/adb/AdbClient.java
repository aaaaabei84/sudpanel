package com.sudpanel.adb;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import com.sudpanel.config.AppProperties;

/**
 * adb 命令封装。所有与 adb 的交互都收敛到这里，便于替换/测试。
 */
// ===== 设计要点 =====
// 1. 平台层唯一的 adb 出口：上层组件（locator / run / report 等）不直接拼 adb 命令行，
//    统一走本类，便于集中管理超时/异常/设备选择，也方便日后替换执行引擎或注入 mock 测试；
// 2. 每次执行都有超时控制（见 run() 的 timeoutSec），避免 adb 进程卡死拖垮整个测试任务；
// 3. 所有操作都通过 serial 指定目标设备，天然支持多设备并行/切换；
// 4. 实机踩过的坑（uiautomator dump 超时、/dev/tty 模式被杀等）都收敛在本类并注释留档，
//    避免后人重复踩坑。
@Service
public class AdbClient {

    /** mCurrentFocus=Window{hash u0 pkg/activity} / mFocusedApp=ActivityRecord{...} */
    // 正则结构说明：
    // 匹配形如 "mCurrentFocus=Window{abc123 u0 com.example.game/com.example.game.MainActivity}" 的一行；
    // (?:...) 非捕获分组：同时兼容 mCurrentFocus=Window 与 mFocusedApp=ActivityRecord 两种前缀；
    // \\{ 匹配字面左花括号，[^}]*? 非贪婪跳过花括号内的 hash 与 user 段，
    // \\s 跳过空格，([\\w.]+) 捕获包名（\\w 加 . 以覆盖点分域名），最后 / 结束于 activity 名之前。
    private static final Pattern FOCUS_PATTERN =
            Pattern.compile("(?:mCurrentFocus=Window|mFocusedApp=ActivityRecord)\\{[^}]*?\\s([\\w.]+)/");

    /** adb 可执行文件的绝对路径（来自配置 sudpanel.adb-path，缺省时禁用，不自动查找本机 SDK）。 */
    private final String adbPath;

    /** 构造器：从 Spring 配置对象 AppProperties 中取出 adb 路径。 */
    public AdbClient(AppProperties props) {
        this.adbPath = props.adbPath();
    }

    /** 命令执行结果。 */
    // record（Java 16+ 不可变数据类）承载一次命令执行的三个结果：
    // exitCode：进程退出码，0=成功；-1=超时/IO 异常/线程被中断等异常情形；
    // out：标准输出（已按 UTF-8 解码为字符串）；
    // err：标准错误输出。
    // ok()：便捷判断——退出码为 0 即认为命令执行成功。
    public record ExecResult(int exitCode, String out, String err) {
        public boolean ok() {
            return exitCode == 0;
        }
    }

    /**
     * 通用命令执行入口：组装 "adb <参数>" 命令、启动进程、读取输出、超时强杀。
     * 本类所有对外方法最终都汇聚到这里，统一处理进程生命周期与异常。
     *
     * @param args       adb 的子命令与参数列表（如 ["devices","-l"]，或 ["-s",serial,"shell",...]）
     * @param timeoutSec 超时秒数；超时后强制销毁进程，返回失败结果
     * @return ExecResult 记录（退出码 + 标准输出 + 标准错误）
     */
    private ExecResult run(List<String> args, long timeoutSec) {
        List<String> cmd = new ArrayList<>();
        cmd.add(adbPath);
        cmd.addAll(args);
        AdbProcessRunner.Result result = AdbProcessRunner.run(cmd, TimeUnit.SECONDS.toMillis(timeoutSec));
        return new ExecResult(result.exitCode(), new String(result.out(), StandardCharsets.UTF_8),
                new String(result.err(), StandardCharsets.UTF_8));
    }

    /** 列出已连接设备（含离线/未授权）。 */
    // @return 设备列表；每台设备包含序列号、状态、机型名（无设备时返回空列表）
    public List<Device> devices() {
        if ("disabled".equalsIgnoreCase(adbPath)) return List.of();
        // adb devices -l：-l 会额外输出设备详情键值对（如 model:V2183A），10 秒超时
        ExecResult r = run(List.of("devices", "-l"), 10);
        if (!r.ok()) {
            String kind = r.err().contains("timeout") ? "执行超时"
                    : r.err().contains("could not start adb") ? "命令不存在或不可执行"
                    : r.err().contains("interrupted") ? "执行已中断" : "服务或连接异常";
            throw new IllegalStateException("ADB 设备查询失败（" + kind + "），请检查 ADB 路径、连接或服务状态");
        }
        // 结果列表
        List<Device> list = new ArrayList<>();
        // 输出格式：第一行表头 "List of devices attached"，随后每行一台设备；
        // 行内按空白分隔：序列号  状态  键值对...（如 "emulator-5554 device product:sdk_gphone model:sdk_gphone"）
        for (String line : r.out().split("\n")) {
            String t = line.trim();
            // 跳过空行与表头行
            if (t.isEmpty() || t.startsWith("List of devices")) {
                continue;
            }
            // 按一个或多个空白字符切分
            String[] parts = t.split("\\s+");
            // 至少要有"序列号+状态"两列，否则视为格式异常行
            if (parts.length < 2) {
                continue;
            }
            String serial = parts[0];
            String status = parts[1];
            if (!List.of("device", "offline", "unauthorized", "recovery", "sideload", "bootloader",
                    "rescue", "no").contains(status)) continue;
            if ("no".equals(status)) status = "no permissions";
            // 遍历剩余键值对，取出 model: 前缀的机型名（没有则为空串）
            String model = "";
            for (String p : parts) {
                if (p.startsWith("model:")) {
                    model = p.substring("model:".length());
                }
            }
            // 组装成 Device 记录加入列表
            list.add(new Device(serial, status, model));
        }
        return list;
    }

        /** dump 当前屏幕控件树，返回 XML 原文（失败返回空串）。
     *  仅用文件模式 rm+dump+cat 并校验 XML 合法性。
     *  设备兼容注意事项：
     *  1. /dev/tty 直出模式被系统 SIGKILL(exit 137)，且会毒化紧随其后的文件 dump——禁用；
     *  2. 持续渲染的界面可能使 uiautomator 等待 idle 超时
     *     后报 "could not get idle state"——多次重试只会放大卡顿，改为单次快败。 */
    public String uiDump(String serial) {
        // 第一步：删除手机上可能残留的旧 dump 文件（rm -f 不存在也不报错，保证读到的是本次新生成的文件）
        run(List.of("-s", serial, "shell", "rm", "-f", "/sdcard/window_dump.xml"), 5);
        // 第二步：执行 uiautomator dump，把当前屏幕控件树写到手机 /sdcard/window_dump.xml（15 秒超时）
        ExecResult d = run(List.of("-s", serial, "shell", "uiautomator", "dump", "/sdcard/window_dump.xml"), 15);
        // 第三步：cat 读出文件内容（走文件模式而非 /dev/tty 直出，规避实机上的 SIGKILL 问题）
        String cat = run(List.of("-s", serial, "shell", "cat", "/sdcard/window_dump.xml"), 10).out();
        // 从 cat 输出里截取合法 XML 段（去掉状态行等非 XML 文本）
        String xml = extractXml(cat);
        if (xml != null) {
            // 一次就解析出合法 XML：直接返回
            return xml;
        }
        if (d.exitCode() == 0) {
            // 罕见：dump 报成功但文件异常，再试一次
            // 说明：命令退出码为 0 但文件内容缺失/损坏（可能上次写入未落盘），
            // 完整重来一遍（rm → dump → cat）；仍失败则放弃，避免多次重试放大卡顿
            run(List.of("-s", serial, "shell", "rm", "-f", "/sdcard/window_dump.xml"), 5);
            run(List.of("-s", serial, "shell", "uiautomator", "dump", "/sdcard/window_dump.xml"), 15);
            cat = run(List.of("-s", serial, "shell", "cat", "/sdcard/window_dump.xml"), 10).out();
            xml = extractXml(cat);
            if (xml != null) {
                return xml;
            }
        }
        // 两次尝试都失败：返回空串，由上层决定（跳过该测试点等）
        return "";
    }

    /** 从 adb 输出里截取合法 XML 段（/dev/tty 模式会带尾部状态行）。 */
    private static String extractXml(String out) {
        // 输出为空：没有可解析内容，返回 null
        if (out == null) {
            return null;
        }
        // 标准 XML 以 <?xml 声明开头；部分机型/版本可能省略声明，退而找 <hierarchy 根标签
        int i = out.indexOf("<?xml");
        if (i < 0) {
            i = out.indexOf("<hierarchy");
        }
        // 两种开头都找不到：说明输出里根本没有控件树，返回 null
        if (i < 0) {
            return null;
        }
        // 结尾用 lastIndexOf 取最后一个 </hierarchy>，防止输出尾部混入重复/干扰文本
        int j = out.lastIndexOf("</hierarchy>");
        if (j < 0) {
            return null;
        }
        // 截取 [i, j+标签长度) 区间，得到完整 XML 文档字符串
        return out.substring(i, j + "</hierarchy>".length());
    }

    /** 模拟点击屏幕坐标。 */
    // 通过 adb shell input tap 在 (x, y) 处模拟一次手指点击；
    // 坐标是屏幕物理像素，与 uiautomator dump 的 bounds 坐标系一致（即 UiLocator 算出的中心点）
    public void tap(String serial, int x, int y) {
        // input tap 需要两个整数字符串参数；String.valueOf 把 int 转成字符串
        run(List.of("-s", serial, "shell", "input", "tap", String.valueOf(x), String.valueOf(y)), 10);
    }

    /** 清空 logcat 缓冲。 */
    // 测试开始前清空历史日志，保证随后 dump 到的都是本次测试期间新产生的日志，
    // 避免把历史日志误判为本次上报动作的命中证据
    public void logcatClear(String serial) {
        // 注意：这里 -s serial 是 adb 的"指定设备"参数（adb -s <serial> logcat -c），
        // 与 logcat 自身的 -s（按 tag 过滤）不是同一个东西，不要混淆
        run(List.of("-s", serial, "logcat", "-c"), 10);
    }

    /** dump 当前 logcat 缓冲内容。 */
    // logcat -d：dump 当前缓冲区后立即退出（区别于持续跟随的 logcat），返回全部缓冲日志文本
    public String logcatDump(String serial) {
        return logcatSnapshot(serial).out();
    }

    /** 带成功状态的只读快照：性能圈不能把失败后的部分输出当成完整日志边界。 */
    public ExecResult logcatSnapshot(String serial) {
        return run(List.of("-s", serial, "logcat", "-d"), 20);
    }

    /**
     * dump logcat 缓冲的**最后 N 行**（2026-10-06，需求 #60 上报查询）。
     *
     * <h2>★ {@code -t N} 不能用：它在某些 ROM 上根本不是「最后 N 行」（2026-10-06，需求 #64 实测）</h2>
     * 原来这里是 {@code logcat -d -t N}。在有真机的机器上一测就发现：**它不按行数返回**。
     * 实测（Android 15 的某国产 ROM）：
     * <pre>
     *   -t 100  → 0 行      -t 600  → 6 行
     *   -t 2000 → 21 行     -t 5000 → 52 行        （设备缓冲里实际有 1 万多行）
     * </pre>
     * 回的行数大致是 {@code N/100} —— 也就是说这个 ROM 把 {@code -t} 的参数当成了别的东西
     * （不是行数）。后果很严重：窗口里经常**一行都没有**，而「锚点找不到」会被
     * {@link com.sudpanel.logcat.LogcatTail#diff} 判成 {@code reset}，
     * 界面每轮都在被整段替换、或直接清空 —— 用户看到的就是「上报内容少了非常多 + 缓存隔几秒被自动清掉」。
     *
     * <p><b>这个坑从 #60 落地起就存在，只是当时没有真机、验不到</b>（当时选的正是「确定能用」的 {@code -t}，
     * 理由写在下面这版注释里 —— 那条理由本身是错的，留在这里当教训）。
     *
     * <h2>现在怎么取尾部：本地裁剪</h2>
     * 直接把整缓冲 dump 回来，在本地取最后 N 行。为什么不放在设备侧做（{@code logcat -d | tail -n N}）：
     * <b>实测耗时几乎一样，而且设备侧那条还稍慢</b> —— 花时间的是**设备端把环形缓冲读出来**
     * （约 330ms，与取多少行无关），多传的那点字节在 USB 上量不出来：
     * <pre>
     *   本地方法（整缓冲 1 万多行）：312ms      设备侧 tail -n 2000：342ms
     *   设备侧 tail -n 200：326ms              设备侧 tail -n 600：327ms
     * </pre>
     * 本地裁剪还少两个依赖：不要求设备端有 {@code tail}，也不碰任何 ROM 的 {@code -t} 语义。
     *
     * <p><b>代价（已知并接受）</b>：若两次轮询之间产生的新日志超过 N 行，中间那一段就取不到了 ——
     * 调用方靠「锚点找不到」察觉（{@link com.sudpanel.logcat.LogcatTail#diff} 会报 reset），
     * 表现为界面整段刷新、不会静默错位。N 取足够大（默认见配置）时这个概率极低。
     *
     * @param serial     设备序列号
     * @param limitLines 取尾部多少行；小于等于 0 时退化为 {@link #logcatDump}
     */
    public String logcatTail(String serial, int limitLines) {
        String dump = logcatDump(serial);
        return limitLines <= 0 ? dump : lastLines(dump, limitLines);
    }

    /**
     * 取一段文本的**最后 n 行**（纯函数，单独抽出来才压得住边界）。
     *
     * <p>三条边界都是踩过的：末尾换行不能算成一行（否则「最后一行」会变成空串，
     * 而空串当锚点会让每一轮都被判成首次取文）；{@code \r} 要去掉（Windows 上的 adb 会带回车，
     * 带上它下一轮锚点就匹配不上）；行数不够时原样返回而不是报错。
     *
     * @param text 原文（可为 null）
     * @param n    取最后多少行；小于等于 0 时原样返回
     */
    static String lastLines(String text, int n) {
        if (text == null || text.isEmpty() || n <= 0) {
            return text == null ? "" : text;
        }
        // 末尾换行先削掉：不削的话 split 出来的最后一个元素是空串，
        // 「最后 n 行」就会变成「最后 n-1 行 + 一个空行」
        int end = text.length();
        while (end > 0 && (text.charAt(end - 1) == '\n' || text.charAt(end - 1) == '\r')) {
            end--;
        }
        if (end == 0) {
            return "";
        }
        int lines = 1;
        for (int i = end - 1; i >= 0; i--) {
            if (text.charAt(i) == '\n') {
                lines++;
                if (lines > n) {
                    return text.substring(i + 1, end);
                }
            }
        }
        return text.substring(0, end);
    }

    /** 在指定设备上执行一条 shell 命令（可含管道/通配符），返回完整结果。 */
    // 整条命令作为单个参数传给 adb shell，命令内部的管道 |、通配符 * 等由手机端 shell 解释，
    // 本地进程不会经过本地 shell，从而避免一层转义问题
    public ExecResult shell(String serial, String command, long timeoutSec) {
        // 超时时间由调用方按命令复杂度指定（grep 类快命令给短超时，长任务给长超时）
        return run(List.of("-s", serial, "shell", command), timeoutSec);
    }

    /** 调试面板用：任意 adb 子命令。 */
    // 与 shell() 不同：这里 args 是完整参数列表（第一个元素就是子命令，如 "version"），
    // 不经过 shell 解释；固定 30 秒超时兜底，防止调试命令卡死
    public ExecResult raw(List<String> args) {
        return run(args, 30);
    }

    /** 截图留证（无效圈 / 异常现场）：screencap -p 二进制 PNG 输出，直接写文件。 */
    // 不能走 shell()/run()：它们的输出经 String 转码会破坏 PNG 二进制，必须读字节流原样落盘
    public boolean screencap(String serial, String destPath) {
        AdbProcessRunner.Result result = AdbProcessRunner.run(
                List.of(adbPath, "-s", serial, "exec-out", "screencap", "-p"), 15_000);
        byte[] bytes = result.out();
        byte[] png = {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};
        if (result.exitCode() != 0 || bytes.length < png.length
                || !java.util.Arrays.equals(png, java.util.Arrays.copyOf(bytes, png.length))) return false;
        try {
            Path dest = Path.of(destPath);
            if (dest.getParent() != null) Files.createDirectories(dest.getParent());
            Files.write(dest, bytes);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    // ---------- 设备健康 / 防锁屏 ----------
    // 以下方法保障测试期间设备处于可用状态：在线、亮屏、解锁、且前台是目标 app

    /** 设备状态："device"=在线；其余（absent/offline/unauthorized/unreachable）都视为掉线。 */
    public String getState(String serial) {
        // adb get-state：查询指定设备状态，正常时输出 device/offline/unauthorized 之一
        ExecResult r = run(List.of("-s", serial, "get-state"), 10);
        if (r.ok()) {
            // 命令成功：去掉首尾空白即是状态文本
            return r.out().trim();
        }
        // 命令失败（adb 会把原因写到 stderr）：按错误消息中的关键词归类成统一的状态枚举值
        String err = r.err() == null ? "" : r.err();
        // 设备未连接/未识别：adb 报 "device 'xxx' not found"
        if (err.contains("not found")) {
            return "absent";
        }
        // 设备存在但处于离线状态（如系统尚未就绪）
        if (err.contains("offline")) {
            return "offline";
        }
        // 设备弹了 USB 调试授权框但未确认
        if (err.contains("unauthorized")) {
            return "unauthorized";
        }
        // 其它未知失败统一归为不可达
        return "unreachable";
    }

    /** 当前前台包名（拿不到返回 null）。 */
    public String foregroundPackage(String serial) {
        // dumpsys window 输出窗口焦点信息；grep -E 只保留 mCurrentFocus / mFocusedApp 两行
        //（不同 Android 版本字段名不同，两个都查兼容性更好）
        String out = run(List.of("-s", serial, "shell", "dumpsys window | grep -E 'mCurrentFocus|mFocusedApp'"), 15).out();
        // 用预编译正则从行文本里提取包名
        Matcher m = FOCUS_PATTERN.matcher(out);
        if (m.find()) {
            // group(1) 即正则捕获组中的包名（如 com.example.game）
            return m.group(1);
        }
        // 解析不到（锁屏、桌面等无应用焦点场景）返回 null，由调用方决定是否拉起 app
        return null;
    }

    /** 系统明确报告锁屏/输入受限；未知不能当作已解锁。 */
    public enum KeyguardState { LOCKED, UNLOCKED, UNKNOWN }

    private static final int KEYGUARD_MAX_OUTPUT_BYTES = 64 * 1024;
    private static final Pattern KEYGUARD_FIELD = Pattern.compile(
            "(?<![A-Za-z0-9_$])(?:mInputRestricted|mKeyguardShowing|isKeyguardShowing(?:\\(\\))?)\\s*[:=]\\s*(true|false)\\b");
    private static final Pattern KEYGUARD_PERMISSION_ERROR = Pattern.compile(
            "(?i)(?:permission\\s+(?:denial|denied)|securityexception|not\\s+allowed\\s+to\\s+dump)");

    /** 只读系统策略，10 秒和每管道 64KiB 上限；不亮屏、不拉起应用、不解除锁屏。 */
    public KeyguardState keyguardState(String serial) {
        if (serial == null || serial.isBlank()) return KeyguardState.UNKNOWN;
        try {
            AdbProcessRunner.Result result = AdbProcessRunner.run(
                    List.of(adbPath, "-s", serial, "shell", "dumpsys window policy"), 10_000,
                    KEYGUARD_MAX_OUTPUT_BYTES);
            return parseKeyguardState(new ExecResult(result.exitCode(),
                    new String(result.out(), StandardCharsets.UTF_8),
                    new String(result.err(), StandardCharsets.UTF_8)));
        } catch (RuntimeException failedRead) {
            return KeyguardState.UNKNOWN;
        }
    }

    static KeyguardState parseKeyguardState(ExecResult result) {
        if (result == null || !result.ok() || result.out() == null
                || result.out().length() > KEYGUARD_MAX_OUTPUT_BYTES
                || result.err() != null && result.err().length() > KEYGUARD_MAX_OUTPUT_BYTES)
            return KeyguardState.UNKNOWN;
        if (KEYGUARD_PERMISSION_ERROR.matcher(result.out()).find()
                || result.err() != null && KEYGUARD_PERMISSION_ERROR.matcher(result.err()).find())
            return KeyguardState.UNKNOWN;
        Matcher fields = KEYGUARD_FIELD.matcher(result.out());
        boolean seen = false;
        while (fields.find()) {
            seen = true;
            // 不同策略字段矛盾时，明确受限/锁屏优先，不能被后面的 false 覆盖。
            if ("true".equals(fields.group(1))) return KeyguardState.LOCKED;
        }
        return seen ? KeyguardState.UNLOCKED : KeyguardState.UNKNOWN;
    }

    /** 显式拉起目标包的Launcher Activity；不使用会释放旋转锁的事件注入工具。 */
    public void startApp(String serial, String pkg) {
        if (serial == null || serial.isBlank()) throw new IllegalArgumentException("启动应用需要设备序列号");
        if (pkg == null || pkg.length() > 255 || !pkg.matches("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)*"))
            throw new IllegalArgumentException("应用包名无效：仅支持点分ASCII标识符，不能含空白或shell字符");
        ExecResult resolved = shell(serial,
                "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p " + shellWord(pkg), 10);
        requireStartCommand(resolved, "解析启动组件");
        String component = null;
        for (String line : resolved.out().split("\\R")) {
            String candidate = line.trim();
            if (!candidate.matches("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)*/\\.?[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)*")) continue;
            if (!candidate.substring(0, candidate.indexOf('/')).equals(pkg))
                throw new IllegalStateException("解析到其它包的启动组件，已拒绝启动");
            if (component != null) throw new IllegalStateException("解析到多个启动组件，已拒绝猜测启动");
            component = candidate;
        }
        if (component == null || component.length() > 512)
            throw new IllegalStateException("未解析到目标包唯一安全的Launcher组件，请核对安装状态与启动入口");
        // adb shell还会经过远端shell；组件内合法的$须保留字面量，不能只依赖本地List参数隔离。
        ExecResult started = shell(serial, "am start -W -n " + shellWord(component)
                + " -a android.intent.action.MAIN -c android.intent.category.LAUNCHER", 15);
        requireStartCommand(started, "启动应用");
        List<String> statuses = started.out().lines().map(String::trim)
                .filter(line -> line.matches("(?i)Status:.*")).toList();
        if (statuses.size() != 1 || !statuses.getFirst().matches("(?i)Status:\\s*ok"))
            throw new IllegalStateException("启动应用未确认成功（缺少唯一Status: ok或启动等待超时），请核对锁屏及应用状态");
    }

    private static String shellWord(String value) { return "'" + value.replace("'", "'\\''") + "'"; }

    private static void requireStartCommand(ExecResult result, String stage) {
        if (result == null) throw new IllegalStateException(stage + "失败：ADB未返回结果");
        String output = (result.out() == null ? "" : result.out()) + "\n" + (result.err() == null ? "" : result.err());
        Matcher error = Pattern.compile("(?im)^\\s*(?:Error\\b|Exception\\b|(?:[A-Za-z_$][A-Za-z0-9_$]*\\.)*[A-Za-z_$][A-Za-z0-9_$]*Exception\\b|Failure\\b|Permission Denial\\b)[^\\r\\n]*").matcher(output);
        if (!result.ok() || error.find()) {
            String reason = output.contains("timeout") ? "ADB执行超时" : output.contains("interrupted") ? "ADB执行已中断"
                    : error.reset().find() ? error.group().trim() : "ADB退出码 " + result.exitCode();
            throw new IllegalStateException(stage + "失败：" + reason.substring(0, Math.min(reason.length(), 240)));
        }
    }

    /** 屏幕是否点亮（mWakefulness=Awake）。 */
    public boolean isScreenOn(String serial) {
        // dumpsys power 的输出里有 mWakefulness=Awake（唤醒）或 mWakefulness=Asleep（休眠）
        // 用 grep 只保留该行，再判断是否包含 Awake
        String out = run(List.of("-s", serial, "shell", "dumpsys power | grep mWakefulness="), 10).out();
        return out.contains("mWakefulness=Awake");
    }

    /** 唤醒屏幕 + 解除滑动锁（无密码锁屏可直接进桌面）。 */
    // 组合拳：先按键唤醒（等效按电源键），再移除锁屏界面；两步都执行保证亮屏且可用
    public void wakeScreen(String serial) {
        // KEYCODE_WAKEUP：电源键的等效按键事件，用于点亮屏幕
        run(List.of("-s", serial, "shell", "input", "keyevent", "KEYCODE_WAKEUP"), 10);
        // wm dismiss-keyguard：请求系统解除锁屏（仅对无密码/滑动锁生效；图案或数字密码仍需人工解锁）
        run(List.of("-s", serial, "shell", "wm", "dismiss-keyguard"), 10);
    }

    /** 读取常亮位掩码；null 代表无法读取，此时不自动更改设备原设置。 */
    public Integer stayOnSetting(String serial) {
        ExecResult result = run(List.of("-s", serial, "shell", "settings", "get", "global", "stay_on_while_plugged_in"), 10);
        if (!result.ok()) return null;
        String raw = result.out().trim();
        if (!raw.matches("[0-9]+")) return null;
        try { return Integer.parseInt(raw); } catch (NumberFormatException ignored) { return null; }
    }

    /** 精确恢复运行前的常亮位掩码；返回失败供调用方记录，避免冒称已恢复。 */
    public boolean restoreStayOnSetting(String serial, int original) {
        if (original < 0) throw new IllegalArgumentException("常亮设置不能为负数");
        return run(List.of("-s", serial, "shell", "settings", "put", "global",
                "stay_on_while_plugged_in", String.valueOf(original)), 10).ok();
    }

    /** USB 插着时常亮/恢复（等价开发者选项「不锁定屏幕」）。 */
    // 长跑测试前打开，防止屏幕自动熄灭导致后续 dump/点击失败；测试结束可恢复
    public void setStayOn(String serial, boolean on) {
        // svc power stayon true：USB 连接时常亮；false：恢复系统默认的熄屏策略
        run(List.of("-s", serial, "shell", "svc", "power", "stayon", on ? "true" : "false"), 10);
    }
}
