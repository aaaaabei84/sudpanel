package com.sudpanel.logcat;

import java.util.ArrayList;
import java.util.List;

/**
 * 上报查询的**增量取文**（2026-10-06，需求 #60）：从一次 logcat dump 里切出「比上次新」的那一段。
 *
 * <h2>为什么需要它</h2>
 * 实时看日志靠每秒轮询 {@code logcat -d}。每次都把整个缓冲推给界面，会让前端反复重建
 * 几千行 DOM。所以后端要能回答：「我上次给你的最后一行是 X，这次新增了哪些？」
 *
 * <h2>为什么用「最后一行文本」当锚点</h2>
 * {@code adb logcat -d} 的输出里**没有稳定的行号或游标** —— 设备端那个环形缓冲会自己滚动，
 * 同一行在第几次 dump 里排第几是不固定的。唯一稳定可传递的东西就是**行内容本身**：
 * 把上次收到的最后一行原样带回来，在新 dump 里找它，它后面的就是新增。
 *
 * <h2>锚点找不到怎么办</h2>
 * 说明那一行已经被环形缓冲挤掉了（或者设备被拔过、缓冲被清过）。这时**不能**假装没变化，
 * 也**不能**只给差量 —— 那会让界面显示的内容与设备上的实际内容错位。
 * 返回 {@code reset=true} 并把这一次的整段内容都给出去，让界面整段替换。
 * 宁可多推一次全量，也不要界面静静地显示错位的内容。
 *
 * <h2>空 dump 是另一回事（2026-10-06，需求 #64 修复）</h2>
 * 「什么都没取到」与「取到了但缓冲是空的」不是同一件事。前者是取文失败，必须**什么都不推**、
 * 锚点原样保留；把它当成 reset 会让界面把本地内容替换成空 ——
 * 表现就是用户报的「上报缓存隔几秒被自动清掉」。
 * 详见 {@link #diff} 里的那段注释。
 *
 * <h2>为什么整行重复在这里可以忽略</h2>
 * logcat 行的前缀含毫秒时间戳与 pid/tid，**整行完全相同**在实践中不会发生。
 * 万一真出现重复，这里取**最后一次出现**作为分界 —— 代价是可能漏掉一行，
 * 但重复出现本身的前提就不成立，不值得为它把实现复杂化。
 */
public final class LogcatTail {

    /**
     * 一次增量取文的结果。
     *
     * @param lines    本次要推给界面的行（{@code reset=true} 时是整段）
     * @param anchor   界面应当记住的新锚点（= 本次最后一行）；无内容时为原样透传
     * @param reset    true 表示「锚点失效、这是整段」——界面必须**替换**而不是追加。
     *                 **reset 一定伴随非空的 lines**：空 dump 走的是「什么都不推」那条路
     * @param total    本次 dump 到的总行数（用于提示与自检，界面不必用）
     */
    public record Result(List<String> lines, String anchor, boolean reset, int total) {
    }

    private LogcatTail() {
    }

    /**
     * 从 {@code dump} 里切出 {@code anchor} 之后的内容。
     *
     * @param dump   本次 logcat 输出的原文（可为 null / 空）
     * @param anchor 上次收到的最后一行；为空表示「首次取文」，返回整段
     */
    public static Result diff(String dump, String anchor) {
        List<String> all = split(dump);

        /*
         * 空 dump（2026-10-06，需求 #64 修复）。
         *
         * 一次取文**什么都没拿到**时，什么都不能推 —— 尤其不能报 reset。
         * 报 reset 的语义是「本地那份已经作废，拿我这份替换掉」，而这里手里根本没有内容，
         * 于是界面会被替换成空，表现就是「上报缓存隔几秒被自动清掉」。
         *
         * 拿不到内容的原因通常是一次瞬时的取文失败（adb 抖动、设备忙），下一次就好了；
         * 而真正的「设备缓冲被清空」在下一轮拿到非空 dump 时会自然表现为「锚点找不到」→ 正常走 reset 分支。
         * 所以这里的正确行为是：**原样保留锚点、什么都不推**。
         *
         * 判据是「一行内容都没有」，而不是「字符串为空」—— 只有换行或只有空格也要算，
         * 那种 dump 同样不含任何日志内容。
         */
        if (all.isEmpty() || all.stream().allMatch(String::isBlank)) {
            return new Result(List.of(), anchor, false, 0);
        }

        // 首次取文：没有锚点可比，整段推给界面（reset=true，界面据此替换）
        if (anchor == null || anchor.isBlank()) {
            return new Result(all, lastOf(all), true, all.size());
        }
        int at = all.lastIndexOf(anchor);
        // 锚点不在了：环形缓冲把它挤掉了。整段给出去，并明确告诉界面这是替换。
        if (at < 0) {
            return new Result(all, lastOf(all), true, all.size());
        }
        List<String> fresh = new ArrayList<>(all.subList(at + 1, all.size()));
        // 没有新行：锚点保持不变（不要变成 null，否则下一次会被当成首次取文而整段重推）
        if (fresh.isEmpty()) {
            return new Result(List.of(), anchor, false, all.size());
        }
        return new Result(fresh, lastOf(fresh), false, all.size());
    }

    /**
     * 按行切分。
     *
     * <p>去掉末尾的空元素：{@code logcat} 输出以换行结尾，直接 {@code split} 会多出一个空行，
     * 那个空行会被当成「一行日志」并顶掉 anchor，下一次轮询就会整段重推一遍。
     * 这是本类里最容易踩、也最难看出来的一处。
     */
    static List<String> split(String dump) {
        List<String> out = new ArrayList<>();
        if (dump == null || dump.isEmpty()) {
            return out;
        }
        // -1 保留末尾空串，好让下面把「末尾连续的空行」一次性削掉
        for (String line : dump.split("\n", -1)) {
            out.add(stripCr(line));
        }
        // 只削**末尾**的空行：日志中间真的可能有空行（多行日志的分隔），不能一并丢掉
        while (!out.isEmpty() && out.get(out.size() - 1).isEmpty()) {
            out.remove(out.size() - 1);
        }
        return out;
    }

    /** 去掉行尾的 \r：adb 在 Windows 上会带回车，带上它会导致下一轮锚点匹配不上。 */
    private static String stripCr(String s) {
        return s.endsWith("\r") ? s.substring(0, s.length() - 1) : s;
    }

    private static String lastOf(List<String> lines) {
        return lines.isEmpty() ? null : lines.get(lines.size() - 1);
    }
}
