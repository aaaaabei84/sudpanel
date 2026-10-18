package com.sudpanel.perf;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.sudpanel.config.AppProperties;

/**
 * 内存增长归因与新判定口径（2026-10-10，v1.10.0 #80/#81/#83/#84）。
 *
 * <p>要钉住的是四条规矩：
 * <ol>
 *   <li>能点名「哪一类内存在涨」（#80）—— 报告里那句结论就靠它；</li>
 *   <li>拟合度不足时**不判通过**（#83）—— 把「数据不支持结论」暴露出来，而不是默认放行；</li>
 *   <li>有回收后基线时改用回收后口径（#84）—— 不让可回收缓存冒充泄漏；</li>
 *   <li>缓存量本身单独报，不与泄漏混为一谈（#84）。</li>
 * </ol>
 */
class PerfJudgeAttributionTest {

    /** 全部走默认阈值（20% / 50MB / 占比60% / R²0.5 / 缓存30MB），warmup 显式给 0 便于构造短序列。 */
    private static AppProperties props() {
        return new AppProperties(null, 0, null, null, 0, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0, null, 0, 0, 0, 0);
    }

    private static PerfJudge judge() {
        return new PerfJudge(props());
    }

    /**
     * 造一圈数据。nativeKb 由调用方按圈号给，其余分项一律取常数（模拟「只有 Native 在涨」）。
     */
    private static Map<String, Object> loop(int no, long pssKb, long nativeKb) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", (long) no);
        m.put("loopNo", no);
        m.put("status", "OK");
        m.put("baselinePssKb", (int) pssKb);
        m.put("baselineJavaKb", 26000);
        m.put("baselineNativeKb", (int) nativeKb);
        m.put("baselineGraphicsKb", 52000);
        m.put("baselineCodeKb", 58400);
        m.put("baselinePrivateOtherKb", 17000);
        m.put("baselineSystemKb", 43500);
        // ws 线程全 0 → 项3 判不适用，本测试不关心线程
        m.put("baselineWsThreads", 0);
        m.put("inGameWsThreads", 0);
        m.put("cpuAvgSys", 13.9);
        return m;
    }

    /** 合成序列：Native 每圈线性增长，其余分项持平。 */
    private static List<Map<String, Object>> nativeGrowing(int loops, long stepKb) {
        List<Map<String, Object>> out = new ArrayList<>();
        long nativeKb = 64000;
        long pssKb = 263000;
        for (int i = 1; i <= loops; i++) {
            out.add(loop(i, pssKb, nativeKb));
            nativeKb += stepKb;
            pssKb += stepKb;
        }
        return out;
    }

    private static PerfJudge.CheckOutcome pick(List<PerfJudge.CheckOutcome> cs, int no) {
        return cs.stream().filter(c -> c.checkNo() == no).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("★ 只有 Native 在涨时，归因项直接点名「Native 堆」并判疑似")
    void namesTheGrowingDimension() {
        List<PerfJudge.CheckOutcome> cs = judge().judgeNormal(nativeGrowing(20, 800), null, Map.of());

        PerfJudge.CheckOutcome c6 = pick(cs, 6);
        assertThat(c6.verdict()).isEqualTo(PerfJudge.Verdict.SUSPECT);
        assertThat(c6.evidence()).contains("Native 堆").contains("增长主要来自");
        // 证据里要带全部六项的斜率，人工复核时能看到「其余持平」这个关键事实
        assertThat(c6.evidence()).contains("Java 堆").contains("图形");
    }

    @Test
    @DisplayName("★ 分项全平（只随圈号噪声）时归因项判通过，不冤枉")
    void flatDimensionsPass() {
        List<Map<String, Object>> loops = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            loops.add(loop(i, 263000, 64000));
        }
        PerfJudge.CheckOutcome c6 = pick(judge().judgeNormal(loops, null, Map.of()), 6);

        assertThat(c6.verdict()).isEqualTo(PerfJudge.Verdict.PASS);
        assertThat(c6.evidence()).contains("没有任何分项呈持续增长");
    }

    @Test
    @DisplayName("★ 拟合度不足时不判通过 —— 把「不确定」暴露出来，而不是默认放行")
    void lowR2IsNotPass() {
        // 锯齿序列：涨跌交替，首末确实在涨，但拟合度极低
        List<Map<String, Object>> loops = new ArrayList<>();
        long nativeKb = 64000;
        long pssKb = 263000;
        for (int i = 1; i <= 20; i++) {
            long bump = (i % 2 == 0) ? 40000 : -38000;
            loops.add(loop(i, pssKb, nativeKb));
            nativeKb += bump;
            pssKb += bump;
        }
        PerfJudge.CheckOutcome c1 = pick(judge().judgeNormal(loops, null, Map.of()), 1);

        assertThat(c1.evidence()).contains("拟合度R²=");
        assertThat(c1.verdict()).isEqualTo(PerfJudge.Verdict.SUSPECT);
        assertThat(c1.evidence()).contains("拟合度不足");
    }

    @Test
    @DisplayName("★ 有回收后基线时改用回收后口径 —— 不让可回收缓存冒充泄漏")
    void trimmedCaliberWins() {
        // 未回收基线一路涨（因为缓存越攒越多），回收后基线完全持平
        List<Map<String, Object>> loops = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            Map<String, Object> l = loop(i, 263000 + i * 2000L, 64000 + i * 200L);
            l.put("trimmedPssKb", 240000);
            l.put("trimmedNativeKb", 60000);
            loops.add(l);
        }
        PerfJudge.CheckOutcome c1 = pick(judge().judgeNormal(loops, null, Map.of()), 1);

        assertThat(c1.evidence()).contains("[回收后基线]");
        assertThat(c1.verdict()).isEqualTo(PerfJudge.Verdict.PASS);
    }

    @Test
    @DisplayName("可回收缓存量单独成项：超阈值判疑似，未超判通过")
    void reclaimableCacheIsItsOwnCheck() {
        // 差 20MB：低于默认阈值 30MB
        List<Map<String, Object>> small = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            Map<String, Object> l = loop(i, 300000, 100000);
            l.put("trimmedPssKb", 300000 - 20 * 1024);
            small.add(l);
        }
        assertThat(pick(judge().judgeNormal(small, null, Map.of()), 7).verdict())
                .isEqualTo(PerfJudge.Verdict.PASS);

        // 合成边界：差 45MB，超过 30MB 缓存量判定阈值
        List<Map<String, Object>> big = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            Map<String, Object> l = loop(i, 300000, 100000);
            l.put("trimmedPssKb", 300000 - 45 * 1024);
            big.add(l);
        }
        PerfJudge.CheckOutcome c7 = pick(judge().judgeNormal(big, null, Map.of()), 7);
        assertThat(c7.verdict()).isEqualTo(PerfJudge.Verdict.SUSPECT);
        assertThat(c7.evidence()).contains("属缓存不属泄漏");
    }

    @Test
    @DisplayName("没采回收后基线时判不适用，不猜")
    void noTrimmedMeansNa() {
        PerfJudge.CheckOutcome c7 = pick(judge().judgeNormal(nativeGrowing(20, 800), null, Map.of()), 7);

        assertThat(c7.verdict()).isEqualTo(PerfJudge.Verdict.N_A);
    }

    @Test
    @DisplayName("★ 单圈异常跳变会被点出来（累计曲线上看不出来的那种）")
    void outlierDeltaIsFlagged() {
        List<Map<String, Object>> loops = new ArrayList<>();
        long pssKb = 263000;
        for (int i = 1; i <= 20; i++) {
            loops.add(loop(i, pssKb, 64000));
            // 其余每圈只涨 500KB，到第 15 圈末尾一次性加 40MB —— 跳变落在第 16 圈的净增上
            pssKb += (i == 15) ? 40 * 1024 : 500;
        }
        PerfJudge.CheckOutcome c1 = pick(judge().judgeNormal(loops, null, Map.of()), 1);

        assertThat(c1.evidence()).contains("异常圈").contains("第16圈");
    }

    @Test
    @DisplayName("★ 三阶段净增写进归因证据，并且能指出增量留在哪一步")
    void stageDeltasAreReported() {
        List<Map<String, Object>> loops = nativeGrowing(20, 800);
        Map<Long, double[]> stages = new LinkedHashMap<>();
        for (int i = 1; i <= 20; i++) {
            // 进房分配 47MB、局内释放 6MB、收尾释放 40MB（几乎全还，只漏 1MB）
            stages.put((long) i, new double[]{47 * 1024.0, -6 * 1024.0, -40 * 1024.0});
        }
        PerfJudge.CheckOutcome c6 = pick(judge().judgeNormal(loops, null, stages), 6);

        assertThat(c6.evidence()).contains("三阶段净增中位数").contains("进房与开局");
    }

    @Test
    @DisplayName("R² 与中位数两个纯函数的边界：常数序列拟合度应为 1，空表中位数为 0")
    void helperEdgeCases() {
        assertThat(PerfJudge.linearRegressionR2(List.of(5.0, 5.0, 5.0))[2]).isEqualTo(1.0);
        assertThat(PerfJudge.linearRegressionR2(List.of(1.0, 2.0, 3.0))[2]).isEqualTo(1.0);
        assertThat(PerfJudge.median(List.of())).isEqualTo(0.0);
        assertThat(PerfJudge.median(List.of(3.0, 1.0, 2.0))).isEqualTo(2.0);
        assertThat(PerfJudge.median(List.of(4.0, 1.0, 3.0, 2.0))).isEqualTo(2.5);
    }
}
