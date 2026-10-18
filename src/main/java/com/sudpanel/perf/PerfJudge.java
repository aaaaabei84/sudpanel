package com.sudpanel.perf;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.sudpanel.config.AppProperties;

/**
 * M6 自动判定（机器初判，人工可复核改判）。阈值全部来自 AppProperties（本地 config）。
 *
 * 检测项：
 * 1. 重复开关游戏内存持续增长 —— 关后基线 PSS 线性回归（剔前 warmup 圈），总增幅超阈值 → 疑似泄漏
 * 2. 加载中提前结算 —— 由执行引擎按圈判定（崩溃关键字 + 能否回大厅），不在此类
 * 3. 每局 ws 新线程+旧线程释放 —— 关后 ws 线程数应回落首圈基线，持续不回落/单调上涨 → 疑似
 * 4. CPU 占用过高 —— 圈均值超阈值 → 判过高
 * 5. 关游戏后 ws 未销毁致泄漏 —— 项3 与项1 联合判定
 * 6. 内存增长归因 —— 6 个分项各自回归，点名「增长主要来自哪一类内存」（v1.10.0 #80）
 * 7. 可回收缓存量 —— 回收前/回收后基线之差，把「缓存放着不还」与「真泄漏」分开（v1.10.0 #84）
 */
@Component
public class PerfJudge {

    /** 判定结论四档：PASS=通过 / SUSPECT=疑似问题（需人工复核）/ FAIL=明确失败 / N_A=检测项不适用。 */
    public enum Verdict { PASS, SUSPECT, FAIL, N_A }

    /**
     * 内存分项：显示名 → perf_loop 里的列名（2026-10-10，v1.10.0 #80）。
     *
     * <p>对 dumpsys meminfo 的 6 个 App Summary 分项分别回归，
     * 展示各分项的增长趋势和占比，帮助选择后续排查方向。
     * 分项趋势本身不能排除其他内存来源，也不能单独确认泄漏。
     */
    private static final String[][] MEM_DIMS = {
            {"Java 堆", "baselineJavaKb"},
            {"Native 堆", "baselineNativeKb"},
            {"图形", "baselineGraphicsKb"},
            {"代码与资源", "baselineCodeKb"},
            {"私有其它", "baselinePrivateOtherKb"},
            {"系统", "baselineSystemKb"},
    };

    // 全部判定阈值（warmup 圈数、内存增幅%、CPU 阈值等）统一来自配置，改阈值不动代码
    private final AppProperties props;

    /** 构造器：注入配置对象。阈值在运行时从 props 读取，便于调参。 */
    public PerfJudge(AppProperties props) {
        this.props = props;
    }

    /** 单条检测结果：检测项编号 / 名称 / 结论 / 证据描述（evidence 会展示在报告"证据"列）。 */
    public record CheckOutcome(int checkNo, String checkName, Verdict verdict, String evidence) {
    }

    /** 兼容老调用点：不带三阶段数据时按空表处理（三阶段只是补充证据，不参与判定）。 */
    public List<CheckOutcome> judgeNormal(List<Map<String, Object>> loops, Double priorGameEndBaselineKb) {
        return judgeNormal(loops, priorGameEndBaselineKb, Map.of());
    }

    /**
     * 正常模式（含人机对局）游戏结束后的聚合判定。loops 为游戏全部圈（含 INVALID，内部会过滤）。
     *
     * @param priorGameEndBaselineKb 本 run 内**前序游戏**结束时的关后基线（kB）；本游戏是第一个游戏时传 null。
     *        2026-09-28 新增（v1.2 #16）：同一 run 内被测进程不重启，前序游戏泄漏的内存会抬高本游戏的起跑线，
     *        使百分比增幅被稀释（与 #15 的漏判叠加）。把该水位写进证据串，人工复核时能看到「本游戏是在什么水位上起跑的」。
     * @param stageDeltas loopId → [进房与开局, 局内采样, 收尾退出] 三段净增（kB，PSS 口径）；
     *        2026-10-10 新增（v1.10.0 #82）。只为证据服务 —— 判定不用它，所以缺数据也不影响结论。
     */
    public List<CheckOutcome> judgeNormal(List<Map<String, Object>> loops, Double priorGameEndBaselineKb,
                                          Map<Long, double[]> stageDeltas) {
        List<CheckOutcome> out = new ArrayList<>();
        // 只取有效圈：状态 OK 且有关后基线 PSS 值（无基线无法回归）
        List<Map<String, Object>> ok = loops.stream()
                .filter(l -> "OK".equals(l.get("status")) && l.get("baselinePssKb") != null)
                .toList();

        // 口径选择（2026-10-10，v1.10.0 #84）：有「回收后基线」就用它算增长 ——
        // 原基线是系统没施压时采的，天然把可回收缓存算进去；回收后的序列才是真泄漏。
        // 必须全圈都有第二套基线才切口径，否则前后不同口径混在一条回归线里，比不切更糟。
        boolean useTrimmed = !ok.isEmpty()
                && ok.stream().allMatch(l -> l.get("trimmedPssKb") != null);
        String baseKey = useTrimmed ? "trimmedPssKb" : "baselinePssKb";
        String caliber = useTrimmed ? "回收后基线" : "未回收基线";

        // ---- 项1：基线 PSS 线性回归 ----
        Verdict c1;
        // 有效圈太少（warmup + 至少 5 圈）时线性回归不可靠，直接判 SUSPECT 交人工看趋势
        if (ok.size() < props.perfWarmupLoops() + 5) {
            c1 = Verdict.SUSPECT;
            out.add(new CheckOutcome(1, "重复开关内存增长", c1,
                    "有效圈不足（" + ok.size() + "圈），无法回归，需人工看趋势"));
        } else {
            // 取出每圈"关游戏后基线 PSS"组成回归序列（x=圈序 1..n，y=PSS）
            List<Double> series = kbSeries(ok, baseKey);
            // 前 warmup 圈是冷启动热身（数据波动大），剔除后再回归；warmup 不超过总数的 1/5 防过度剔除
            int warmup = Math.min(props.perfWarmupLoops(), series.size() / 5);
            List<Double> fit = series.subList(warmup, series.size());
            // 最小二乘回归 + 拟合度（v1.10.0 #83）
            double[] reg = linearRegressionR2(fit);
            // 用回归直线反推首/末圈预测值（首圈 x=1，末圈 x=剩余圈数），比直接取实测值更抗噪声
            double predictedFirst = reg[1] + reg[0];           // x=1（剔 warmup 后第一圈）
            double predictedLast = reg[1] + reg[0] * fit.size();
            // 总增幅百分比：首圈→末圈涨了百分之多少（首圈为 0 时无法算百分比，记 0 避免除零）
            double growthPct = predictedFirst > 0 ? (predictedLast - predictedFirst) / predictedFirst * 100 : 0;
            // 绝对增量（MB）：2026-09-28 新增（v1.2 #15）。
            // 同样的绝对增量在较高基线下对应较小的百分比，因此同时检查绝对增量。
            double growthMb = (predictedLast - predictedFirst) / 1024.0;
            boolean pctExceeded = growthPct > props.perfMemGrowthPercent();
            boolean absExceeded = growthMb > props.perfMemGrowthAbsMb();
            boolean r2Ok = reg[2] >= props.perfAttrMinR2();

            // 判定（v1.10.0 #83 改）：超阈值 → 疑似；未超但拟合度低 → **不判通过**，交人工并提示加圈数。
            // 把「不确定」默认当通过，正是漏判的来源；这条改动让「数据不支持结论」也会被看见。
            if (pctExceeded || absExceeded) {
                c1 = Verdict.SUSPECT;
            } else if (!r2Ok) {
                c1 = Verdict.SUSPECT;
            } else {
                c1 = Verdict.PASS;
            }
            // 证据字符串直接进报告"证据"列：同时给出百分比与绝对增量，人工复核时能一眼判断是否被基数稀释
            // 末尾再拼「跨游戏水位」标注（2026-09-28，v1.2 #16）
            String ev = String.format(
                    "[%s] warmup=%d圈, 回归首圈=%.0fKB, 末圈=%.0fKB, 总增幅=%.1f%%（阈值%.0f%%），"
                            + "绝对增量=%.1fMB（阈值%.0fMB），拟合度R²=%.2f（门槛%.2f）",
                    caliber, warmup, predictedFirst, predictedLast, growthPct, props.perfMemGrowthPercent(),
                    growthMb, props.perfMemGrowthAbsMb(), reg[2], props.perfAttrMinR2())
                    + (r2Ok ? "" : "；拟合度不足 → 趋势不可信，未判通过，建议增加圈数")
                    + deltaMark(fit)
                    + waterMark(ok, priorGameEndBaselineKb);
            out.add(new CheckOutcome(1, "重复开关内存增长", c1, ev));
        }

        // ---- 项3：ws 线程关后回落 ----
        // 独立方法判定（见 judgeWsThreads），结果记为 c3 供项5 联合判定复用
        CheckOutcome c3 = judgeWsThreads(ok);
        out.add(c3);

        // ---- 项4：CPU 均值 ----
        out.add(judgeCpu(ok));

        // ---- 项6：内存增长归因（v1.10.0 #80）----
        out.add(judgeAttribution(ok, baseKey, caliber, useTrimmed, stageDeltas));

        // ---- 项7：可回收缓存量（v1.10.0 #84）----
        out.add(judgeReclaimableCache(ok));

        // ---- 项5：项1 + 项3 联合 ----
        // 项3 不适用（游戏无 ws/网络线程特征）时，联合判定无从谈起，同样标 N/A
        if (c3.verdict() == Verdict.N_A) {
            out.add(new CheckOutcome(5, "关后 ws 未销毁致泄漏", Verdict.N_A,
                    "项3 不适用（未观测到 ws/网络线程）"));
            return out;
        }
        // 联合逻辑：内存增长(项1) 与 ws 线程未回落(项3) 同时异常 → 判定为"关后 ws 未销毁致泄漏"
        boolean leak = c1 != Verdict.PASS && c3.verdict() != Verdict.PASS;
        out.add(new CheckOutcome(5, "关后 ws 未销毁致泄漏",
                leak ? Verdict.SUSPECT : Verdict.PASS,
                "项1=" + c1 + "，项3=" + c3.verdict() + "，联合判定 " + (leak ? "SUSPECT" : "PASS")));
        return out;
    }

    /**
     * 项6：内存增长归因（2026-10-10，v1.10.0 #80）。
     *
     * <p>对 6 个分项各自做线性回归，算出「每圈涨多少」与「占总增量的比例」，
     * 把占比最大且超过阈值的那一项点名，并展示斜率和占比，供开发进一步复核。
     *
     * <p>判定条件三选一缺一不可：斜率必须为正、拟合度达标、占比过阈值。
     * 拟合度不达标时**不判通过**而是交人工 —— 与项1 同一取舍。
     */
    private CheckOutcome judgeAttribution(List<Map<String, Object>> ok, String baseKey, String caliber,
                                          boolean useTrimmed, Map<Long, double[]> stageDeltas) {
        if (ok.size() < props.perfWarmupLoops() + 5) {
            return new CheckOutcome(6, "内存增长归因", Verdict.N_A,
                    "有效圈不足（" + ok.size() + "圈），不做分项归因");
        }
        // 分项列在同一个 run 里口径一致：要么全有（本版新采的），要么全无（老 run）
        String prefix = useTrimmed ? "trimmed" : "baseline";
        Map<String, double[]> fits = new LinkedHashMap<>(); // 显示名 → [斜率KB/圈, R²]
        double positiveTotal = 0;
        for (String[] dim : MEM_DIMS) {
            String key = prefix + dim[1].substring("baseline".length());
            List<Double> series = kbSeries(ok, key);
            if (series.isEmpty()) {
                return new CheckOutcome(6, "内存增长归因", Verdict.N_A,
                        "本 run 无内存分项数据（老数据或未采集），无法归因");
            }
            int warmup = Math.min(props.perfWarmupLoops(), series.size() / 5);
            double[] reg = linearRegressionR2(series.subList(warmup, series.size()));
            fits.put(dim[0], new double[]{reg[0], reg[2]});
            if (reg[0] > 0) {
                positiveTotal += reg[0];
            }
        }
        // 点名：占比最大的那一项
        String top = null;
        double topSlope = 0;
        double topR2 = 0;
        for (Map.Entry<String, double[]> e : fits.entrySet()) {
            if (e.getValue()[0] > topSlope) {
                topSlope = e.getValue()[0];
                top = e.getKey();
                topR2 = e.getValue()[1];
            }
        }
        double share = positiveTotal > 0 ? topSlope / positiveTotal * 100 : 0;
        boolean named = top != null && topSlope > 0
                && share >= props.perfAttrSharePercent()
                && topR2 >= props.perfAttrMinR2();

        StringBuilder sb = new StringBuilder("逐项斜率（KB/圈，R²）：");
        for (Map.Entry<String, double[]> e : fits.entrySet()) {
            sb.append(String.format("%s %+.0f（%.2f）、", e.getKey(), e.getValue()[0], e.getValue()[1]));
        }
        if (sb.charAt(sb.length() - 1) == '、') {
            sb.setLength(sb.length() - 1);
        }
        if (top == null || positiveTotal <= 0) {
            sb.append("；没有任何分项呈持续增长");
            return new CheckOutcome(6, "内存增长归因", Verdict.PASS, sb.toString());
        }
        sb.append(String.format("；增长主要来自「%s」，每圈 %+.0fKB，占正增量 %.0f%%（阈值%.0f%%）",
                top, topSlope, share, props.perfAttrSharePercent()));
        if (named) {
            sb.append("——建议优先排查该类内存的释放路径");
        } else if (share >= props.perfAttrSharePercent() && topR2 < props.perfAttrMinR2()) {
            sb.append("；占比虽高但拟合度不足，需增加圈数后再判");
        }
        sb.append(stageMark(ok, stageDeltas));
        return new CheckOutcome(6, "内存增长归因",
                named ? Verdict.SUSPECT : Verdict.PASS, sb.toString());
    }

    /**
     * 项7：可回收缓存量（2026-10-10，v1.10.0 #84）。
     *
     * <p>逐圈算「未回收基线 − 回收后基线」，取中位数，表示在本次回收条件下减少的内存。
     * 单独展示这部分变化，帮助区分可回收部分与持续增长；最终原因仍需结合其他证据。
     *
     * <p>没有第二套基线（开关关闭或老数据）→ 判 N_A，不猜。
     */
    private CheckOutcome judgeReclaimableCache(List<Map<String, Object>> ok) {
        List<Double> cacheMb = new ArrayList<>();
        for (Map<String, Object> l : ok) {
            Integer raw = (Integer) l.get("baselinePssKb");
            Integer trimmed = (Integer) l.get("trimmedPssKb");
            if (raw != null && trimmed != null) {
                cacheMb.add((raw - trimmed) / 1024.0);
            }
        }
        if (cacheMb.isEmpty()) {
            return new CheckOutcome(7, "可回收缓存量", Verdict.N_A,
                    "未采集回收后基线（开关关闭或历史数据），无法区分缓存与泄漏");
        }
        double median = median(cacheMb);
        double max = cacheMb.stream().mapToDouble(d -> d).max().orElse(0);
        String ev = String.format(
                "逐圈「未回收基线 − 回收后基线」中位数=%.1fMB，最大=%.1fMB（阈值%.0fMB）；"
                        + "这部分系统一施压就会归还，属缓存不属泄漏，但会计入未回收口径的增长",
                median, max, props.perfReclaimableCacheMb());
        return new CheckOutcome(7, "可回收缓存量",
                median > props.perfReclaimableCacheMb() ? Verdict.SUSPECT : Verdict.PASS, ev);
    }

    /** 项4：CPU 均值判定（2026-10-03，v1.5 #21 起为整机口径）。 */
    private CheckOutcome judgeCpu(List<Map<String, Object>> ok) {
        List<Double> cpuAvgs = ok.stream().filter(l -> l.get("cpuAvgSys") != null)
                .map(l -> ((Number) l.get("cpuAvgSys")).doubleValue()).toList();
        List<Double> cpuAvgsLegacy = ok.stream().filter(l -> l.get("cpuAvgSys") == null && l.get("cpuAvg") != null)
                .map(l -> ((Number) l.get("cpuAvg")).doubleValue()).toList();
        if (cpuAvgs.isEmpty() && cpuAvgsLegacy.isEmpty()) {
            return new CheckOutcome(4, "CPU 占用", Verdict.SUSPECT, "无有效 CPU 样本");
        }
        if (!cpuAvgs.isEmpty()) {
            // 整机口径判定（新数据）
            double avg = cpuAvgs.stream().mapToDouble(d -> d).average().orElse(0);
            double peak = cpuAvgs.stream().mapToDouble(d -> d).max().orElse(0);
            String ev = String.format("全圈 CPU 均值=%.1f%%，单圈最高均值=%.1f%%（整机口径，阈值%.0f%%）",
                    avg, peak, props.perfCpuThreshold());
            // 平均 CPU 超阈值 → SUSPECT（占用过高），否则 PASS
            return new CheckOutcome(4, "CPU 占用",
                    avg > props.perfCpuThreshold() ? Verdict.SUSPECT : Verdict.PASS, ev);
        }
        // 历史圈回退：只有单核值，无法换算整机口径 → 判定降级为"仅供参考"
        double avg = cpuAvgsLegacy.stream().mapToDouble(d -> d).average().orElse(0);
        double peak = cpuAvgsLegacy.stream().mapToDouble(d -> d).max().orElse(0);
        String ev = String.format(
                "⚠️ 历史圈无核数记录，单核均值=%.1f%%直接对比整机阈值%.0f%%（口径不符，仅供参考）；"
                        + "单核最高均值=%.1f%%",
                avg, props.perfCpuThreshold(), peak);
        return new CheckOutcome(4, "CPU 占用",
                avg > props.perfCpuThreshold() ? Verdict.SUSPECT : Verdict.PASS, ev);
    }

    /**
     * 拼「跨游戏水位」标注（2026-09-28 新增，v1.2 #16）。
     *
     * <p>同一 run 内多个游戏共用一个进程，前序游戏结束后的内存状态可能影响后序游戏的基线。
     * 较高的起始基线会稀释百分比增幅，也会影响跨游戏比较。
     * 因此同时记录本游戏起始水位和前序游戏结束水位，供复核测试条件。
     */
    private static String waterMark(List<Map<String, Object>> ok, Double priorKb) {
        double startMb = ((Number) ok.get(0).get("baselinePssKb")).doubleValue() / 1024.0;
        if (priorKb == null) {
            return String.format("；起始水位=%.1fMB（本 run 首个游戏，无前序影响）", startMb);
        }
        return String.format("；起始水位=%.1fMB，前序游戏结束水位=%.1fMB（受前序游戏影响，跨游戏结论不可比）",
                startMb, priorKb / 1024.0);
    }

    /**
     * 拼「单圈净增 + 异常圈」标注（2026-10-10，v1.10.0 #81）。
     *
     * <p>累计曲线看不出「哪一圈突然跳了一下」。这里算逐圈净增，把超过「正增量中位数 3 倍
     * 且绝对值大于 5MB」的圈单独列出，便于将局部跳变与持续增长分开检查。
     */
    private static String deltaMark(List<Double> fit) {
        if (fit.size() < 3) {
            return "";
        }
        List<Double> deltas = new ArrayList<>();
        for (int i = 1; i < fit.size(); i++) {
            deltas.add(fit.get(i) - fit.get(i - 1));
        }
        List<Double> positive = deltas.stream().filter(d -> d > 0).toList();
        if (positive.isEmpty()) {
            return "；逐圈净增全为负或零，无异常圈";
        }
        double base = median(positive);
        List<Integer> outliers = new ArrayList<>();
        for (int i = 0; i < deltas.size(); i++) {
            if (deltas.get(i) > base * 3 && deltas.get(i) > 5120) {
                outliers.add(i + 2); // deltas[0] 对应第 2 圈（第 1 圈没有前一圈可比）
            }
        }
        String head = String.format("；单圈净增中位数=%.0fKB", base);
        if (outliers.isEmpty()) {
            return head + "，无异常圈";
        }
        StringBuilder sb = new StringBuilder(head).append("，异常圈（净增超中位数3倍且>5MB）：");
        for (int i = 0; i < Math.min(outliers.size(), 6); i++) {
            int no = outliers.get(i);
            sb.append("第").append(no).append("圈(+").append(String.format("%.1f", deltas.get(no - 2) / 1024.0)).append("MB)");
            if (i < Math.min(outliers.size(), 6) - 1) {
                sb.append("、");
            }
        }
        return sb.toString();
    }

    /**
     * 拼「三阶段净增」标注（2026-10-10，v1.10.0 #82）。
     *
     * <p>把一圈拆成「进房与开局 / 局内采样 / 收尾退出」，各段取中位数。
     * 判定用不上它，但它回答了判定回答不了的那个问题：**增量发生在哪一步**。
     */
    private static String stageMark(List<Map<String, Object>> ok, Map<Long, double[]> stageDeltas) {
        if (stageDeltas == null || stageDeltas.isEmpty()) {
            return "";
        }
        List<Double> enter = new ArrayList<>();
        List<Double> play = new ArrayList<>();
        List<Double> exit = new ArrayList<>();
        for (Map<String, Object> l : ok) {
            long loopId = ((Number) l.get("id")).longValue();
            double[] d = stageDeltas.get(loopId);
            if (d == null) {
                continue;
            }
            enter.add(d[0]);
            play.add(d[1]);
            exit.add(d[2]);
        }
        if (enter.isEmpty()) {
            return "";
        }
        double mEnter = median(enter) / 1024.0;
        double mPlay = median(play) / 1024.0;
        double mExit = median(exit) / 1024.0;
        String where = mExit > 0 ? "增量主要留在收尾退出这一步（退出后未归还）"
                : (mPlay > 0 ? "增量主要发生在局内运行期间" : "三段均无正向增量");
        return String.format("；三阶段净增中位数：进房与开局 %+.1fMB、局内采样 %+.1fMB、收尾退出 %+.1fMB（%s）",
                mEnter, mPlay, mExit, where);
    }

    /** 项3：关后 ws 线程应回落首圈基线；后半段均值 > 基线 → 疑似未释放。 */
    private CheckOutcome judgeWsThreads(List<Map<String, Object>> okLoops) {
        // 适用性检查：全部有效圈都观测不到任何 ws/网络线程（关后基线 + 对局中均为 0/空）
        // → 该游戏线程名不含 ws 特征（OkHttp 线程池复用 / 协程实现 / 线程名自定义），
        //   本检测项无从判定，标 N/A 交人工确认，避免误判 PASS（"没有泄漏"）或 SUSPECT。
        boolean anyWs = okLoops.stream().anyMatch(l -> {
            Integer b = (Integer) l.get("baselineWsThreads");
            Integer g = (Integer) l.get("inGameWsThreads");
            return (b != null && b > 0) || (g != null && g > 0);
        });
        if (!anyWs) {
            return new CheckOutcome(3, "ws 线程释放", Verdict.N_A,
                    "未观测到 ws/网络线程（线程名无 websocket/okhttp/okio 特征），检测项不适用");
        }
        // 取每圈"关游戏后 ws 线程数"（过滤 null）作为判定序列
        List<Integer> closed = okLoops.stream()
                .map(l -> (Integer) l.get("baselineWsThreads"))
                .filter(v -> v != null).toList();
        // 有效圈少于 6：样本太少，统计无意义，交人工
        if (closed.size() < 6) {
            return new CheckOutcome(3, "ws 线程释放", Verdict.SUSPECT,
                    "有效圈不足（" + closed.size() + "），需人工看趋势");
        }
        // 基准 = 前半段均值（不能拿首圈：首圈是冷启动值，OkHttp 线程池未预热、全序列最低，
        // 拿它当基准会让后面正常水平恒高于基准 → 恒误报 SUSPECT，2026-09-18 实证修复）
        int half = Math.max(1, closed.size() / 2);
        double headAvg = closed.subList(0, half).stream().mapToInt(i -> i).average().orElse(0);
        // 后半段均值：若线程泄漏，越往后越明显，后半段最能暴露问题
        double tailAvg = closed.subList(half, closed.size()).stream().mapToInt(i -> i).average().orElse(0);
        // 容差判定：后半段 > 基准×1.5 且绝对差 ≥3 才判疑似未释放。
        // 纯 > 基准会误报（OkHttp 常驻线程 3-8 正常波动，前后半段均值差 1-2 属正常）
        boolean suspect = tailAvg > headAvg * 1.5 && tailAvg - headAvg >= 3;
        // 口径局限（2026-09-28，v1.2 #8）：把局限写到**用户可见**的证据串里（报告与 CSV 都含该串）。
        // 本项按**线程名**统计，只能反映「存在 websocket/okhttp/okio 命名特征的线程」：
        // 对 OkHttp 线程池复用的实现（游戏侧常见），连接关闭后线程仍常驻复用 → 本项 PASS **不等于**「ws 无泄漏」。
        // 网络层的真实证据是 net_conn（ESTABLISHED 连接数，已采集但尚无趋势判定）。
        String ev = String.format(
                "前半段均值=%.1f，后半段关后均值=%.1f（容差: >1.5倍且差≥3）；"
                        + "口径局限：按线程名统计，OkHttp 系线程池复用时参考价值有限（网络层证据见 net_conn）",
                headAvg, tailAvg);
        return new CheckOutcome(3, "ws 线程释放",
                suspect ? Verdict.SUSPECT : Verdict.PASS, ev);
    }

    /** 取某个列名的完整序列；任一圈缺值就返回空表（不做回归，也不猜值）。 */
    private static List<Double> kbSeries(List<Map<String, Object>> ok, String key) {
        List<Double> out = new ArrayList<>();
        for (Map<String, Object> l : ok) {
            Object v = l.get(key);
            if (v == null) {
                return List.of();
            }
            out.add(((Number) v).doubleValue());
        }
        return out;
    }

    /** 中位数（偶数个取中间两个的均值）；空表返回 0。 */
    static double median(List<Double> xs) {
        if (xs.isEmpty()) {
            return 0;
        }
        List<Double> s = new ArrayList<>(xs);
        s.sort(Double::compare);
        int n = s.size();
        return n % 2 == 1 ? s.get(n / 2) : (s.get(n / 2 - 1) + s.get(n / 2)) / 2;
    }

    /** 最小二乘线性回归，返回 [slope, intercept]。 */
    static double[] linearRegression(List<Double> ys) {
        int n = ys.size();
        // 累加四个和：Σx、Σy、Σxy、Σx²（x 取 1..n 的自然序号）
        double sx = 0, sy = 0, sxy = 0, sxx = 0;
        for (int i = 0; i < n; i++) {
            double x = i + 1;
            sx += x;
            sy += ys.get(i);
            sxy += x * ys.get(i);
            sxx += x * x;
        }
        // 最小二乘公式的分母 = n·Σx² − (Σx)²；为 0 说明所有 x 相同（n=1 等退化情况）
        double denom = n * sxx - sx * sx;
        if (denom == 0) {
            // 退化：斜率取 0、截距取均值（水平线），保证调用方不炸
            return new double[]{0, n > 0 ? sy / n : 0};
        }
        // 斜率 slope = (n·Σxy − Σx·Σy) / 分母；截距 intercept = (Σy − slope·Σx) / n
        double slope = (n * sxy - sx * sy) / denom;
        double intercept = (sy - slope * sx) / n;
        return new double[]{slope, intercept};
    }

    /**
     * 最小二乘回归 + 决定系数 R²（2026-10-10，v1.10.0 #83）。返回 [slope, intercept, r2]。
     *
     * <p>R² 的含义：这条序列有多少比例能被「一条直线」解释。接近 1 = 确实是持续增长的趋势；
     * 接近 0 = 基本是噪声。没有它，「首末两个点一减」也能报出 50% 的增幅，而实际只是抖动。
     *
     * <p>常数序列（完全没有变化）返回 1.0 —— 那是拟合得最好的一种。
     */
    static double[] linearRegressionR2(List<Double> ys) {
        double[] lr = linearRegression(ys);
        int n = ys.size();
        double mean = ys.stream().mapToDouble(d -> d).average().orElse(0);
        double ssTot = 0;
        double ssRes = 0;
        for (int i = 0; i < n; i++) {
            double y = ys.get(i);
            double pred = lr[1] + lr[0] * (i + 1);
            ssTot += (y - mean) * (y - mean);
            ssRes += (y - pred) * (y - pred);
        }
        double r2 = ssTot == 0 ? 1.0 : 1 - ssRes / ssTot;
        return new double[]{lr[0], lr[1], r2};
    }
}
