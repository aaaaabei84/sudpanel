package com.sudpanel.perf;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * M6 per-game 报告：自包含 HTML（内嵌 SVG 折线：跨圈趋势 + 单圈曲线）+ CSV 导出。
 * 无外部依赖（不引 CDN/图片），字符串拼接模式参考旧 ReportService。
 * 只读库生成内容，不写报告文件到磁盘。
 */
@Service
public class PerfReportService {

    /** 单圈曲线最多渲染的有效圈数（防爆 HTML 体积）。 */
    private static final int LOOP_CURVE_CAP = 60;

    private final PerfRepository repo;
    private final PerfEvidenceRepository stepEvidence;
    /** 判定阈值（CPU 阈值等）：报告里的参考线要跟判定用同一个值，否则图与结论会打架。 */
    private final com.sudpanel.config.AppProperties props;
    // 复用同一个 ObjectMapper（线程安全、无状态），避免每次解析 judge_json 都新建
    private final ObjectMapper json = new ObjectMapper();

    /** 构造器：注入 Repository 与全局配置。ObjectMapper 直接字段初始化（线程安全，可复用）。 */
    public PerfReportService(PerfRepository repo, com.sudpanel.config.AppProperties props) {
        this(repo,props,null);
    }
    @org.springframework.beans.factory.annotation.Autowired
    public PerfReportService(PerfRepository repo,com.sudpanel.config.AppProperties props,PerfEvidenceRepository stepEvidence) {
        this.repo=repo;this.props=props;this.stepEvidence=stepEvidence;
    }

    // ---------- HTML ----------

    /**
     * 生成游戏维度性能报告的完整 HTML（自包含，无外部依赖，直接返回给浏览器）。
     *
     * <p>2026-10-08（#72）改版：展示结构对齐平台控制面板的「结果分析」卡片布局——
     * 概览统计卡 / 机器初判条 / 聚合判定表 / 跨圈趋势三图 / 每圈数据表 /
     * 单圈曲线（details 折叠）。人机对局（AI_ROOM）与正常开关（NORMAL）同构渲染；
     * 提前结算（EARLY_SETTLE）保持它自己的判定/证据列。旧版把 AI_ROOM 错并进
     * 提前结算分支（展示「提前结算实际延迟」这种不相干内容），本次一并修正。
     */
    public String html(long runId, long gameId) {
        Map<String, Object> run = repo.getRun(runId);
        if (run == null) {
            throw new IllegalStateException("运行不存在: " + runId);
        }
        Map<String, Object> game = repo.getGame(gameId);
        if (game == null) {
            throw new IllegalStateException("游戏不存在: " + gameId);
        }
        List<Map<String, Object>> loops = repo.loopsOf(runId, gameId);
        Map<Long,String> stepSummaries=stepSummaries(runId,gameId);
        String mode = String.valueOf(run.get("mode"));
        boolean early = "EARLY_SETTLE".equals(mode);
        String gameName = game.get("shortName") != null
                ? String.valueOf(game.get("shortName")) : String.valueOf(game.get("name"));
        long okCount = loops.stream().filter(l -> "OK".equals(l.get("status"))).count();
        List<Map<String, Object>> checks = repo.checksOf(runId, gameId);

        StringBuilder sb = new StringBuilder(64 * 1024);
        sb.append("<!DOCTYPE html><html lang='zh'><head><meta charset='utf-8'>")
          .append("<meta http-equiv='Content-Security-Policy' content=\"default-src 'none'; style-src 'unsafe-inline'; img-src data:; base-uri 'none'; form-action 'none'\">")
          .append("<meta name='viewport' content='width=device-width,initial-scale=1'>")
          // <title> 也用作另存文件名，过滤会导致路径或 URL 解释歧义的字符。
          .append("<title>").append(esc(safeFileName(gameName, runId))).append("</title><style>")
          .append("body{margin:0;background:#f8fafc;color:#0f172a;font-family:-apple-system,BlinkMacSystemFont,'PingFang SC',system-ui,sans-serif;font-size:13px;line-height:1.5}")
          .append(".wrap{max-width:1180px;margin:0 auto;padding:24px 20px 48px}")
          .append(".card{background:#fff;border:1px solid #e2e8f0;border-radius:14px;padding:16px 18px;margin-bottom:16px;box-shadow:0 1px 2px rgba(15,23,42,.04)}")
          .append("h1{font-size:18px;margin:0 0 6px}h2{font-size:13px;margin:0 0 12px;color:#475569;font-weight:600}")
          .append(".meta{color:#64748b;font-size:12px;line-height:1.8}")
          .append(".badge{display:inline-block;padding:1px 8px;border-radius:999px;font-size:11px;font-weight:600;border:1px solid transparent;vertical-align:middle}")
          .append(".b-pass{color:#047857;background:#ecfdf5;border-color:#a7f3d0}")
          .append(".b-fail{color:#b91c1c;background:#fef2f2;border-color:#fecaca}")
          .append(".b-suspect{color:#b45309;background:#fffbeb;border-color:#fde68a}")
          .append(".b-na{color:#475569;background:#f1f5f9;border-color:#e2e8f0}")
          .append(".stats{display:grid;grid-template-columns:repeat(4,1fr);gap:12px}")
          .append(".stat{border:1px solid #e2e8f0;border-radius:12px;padding:12px 14px}")
          .append(".stat .t{color:#64748b;font-size:11px}.stat .v{font-size:22px;font-weight:700;margin:2px 0 6px}")
          .append(".stat .s{color:#94a3b8;font-size:11px;display:flex;align-items:center;gap:6px}")
          .append(".tablewrap{border:1px solid #e2e8f0;border-radius:12px;overflow:auto;max-height:440px}")
          .append("table{border-collapse:collapse;width:100%;font-size:12px}")
          .append("th,td{border-bottom:1px solid #eef2f7;padding:6px 8px;text-align:left;vertical-align:top}")
          .append("th{background:#f8fafc;color:#475569;font-weight:600;white-space:nowrap;position:sticky;top:0}")
          .append("tr:last-child td{border-bottom:none}")
          .append(".chart{margin:8px 0}.chart svg{display:block;margin:0 auto;max-width:100%}")
          .append(".chips{display:flex;flex-wrap:wrap;gap:10px;align-items:center;color:#64748b;font-size:12px}")
          .append("details{border:1px solid #e2e8f0;border-radius:10px;padding:6px 10px;margin:6px 0}")
          .append("summary{cursor:pointer;font-size:12px;color:#334155;font-weight:600}")
          .append(".mode{font-size:11px;padding:1px 8px;border-radius:999px;background:#eff6ff;color:#1d4ed8;border:1px solid #bfdbfe;vertical-align:middle}")
          .append(".small{color:#94a3b8;font-size:11px}")
          .append("pre{margin:0;white-space:pre-wrap;word-break:break-all;font-size:11px}")
          .append("@media(max-width:860px){.stats{grid-template-columns:repeat(2,1fr)}}")
          // 打印/存 PDF 优化（2026-10-08）：卡片不分页撕裂、表格完整展开、单圈曲线不打印
          // （50 张 SVG 进 PDF 不现实），白底无阴影。分享报告建议「打印 → 存储为 PDF」。
          .append("@media print{body{background:#fff}.card{box-shadow:none;break-inside:avoid}")
          .append(".tablewrap{max-height:none;overflow:visible}th{position:static}")
          .append(".no-print{display:none}}")
          .append("</style></head><body><div class='wrap'>")
          .append("<div class='card'><h1>性能测试报告 · ").append(esc(gameName))
          .append(" <span style='color:#94a3b8;font-weight:500'>run#").append(runId).append("</span> ")
          .append("<span class='mode'>").append(modeLabel(mode)).append("</span></h1>")
          .append("<div class='meta'>设备 ").append("本机设备（标识已隐藏）")
          .append(" &nbsp;|&nbsp; 状态 ").append(esc(str(run.get("status"))))
          .append(" &nbsp;|&nbsp; ").append(esc(str(run.get("startTime"))))
          .append(" → ").append(esc(str(run.get("endTime"))))
          .append(" &nbsp;|&nbsp; 配置圈数 ").append(run.get("loopCount"))
          .append(" &nbsp;|&nbsp; 单圈采样 ").append(run.get("sampleSeconds")).append("s")
          .append(" &nbsp;|&nbsp; 有效圈 ").append(okCount).append(" / ").append(loops.size())
          .append("</div></div>");

        // 「不合格就提示跑对照」（2026-10-12 需求 #88）：判到疑似持续增长时，
        // 在报告最上方给一句**可执行的下一步**。
        // 为什么放在最上面：读报告的人第一眼要的是「那我现在该干什么」，
        // 判定项里那句「疑似持续增长」只说了现象，没说怎么定责。
        if ("AI_ROOM".equals(str(run.get("mode"))) && hasSuspect(checks)) {
            sb.append("<div class='card' style='border-left:4px solid #f59e0b'>")
              .append("<h2 style='margin-top:0'>建议再跑一次「重复开关游戏」</h2>")
              .append("<p>本轮判定有<b>疑似持续增长</b>。要判断这是不是游戏本身的问题，"
                      + "再跑一次「重复开关游戏」即可对照 —— 它的链路与人机对局完全相同"
                      + "（推人机 / 开局 / 结束 / 清房全部走接口，不点坐标），"
                      + "只是<b>局内只待 5 秒</b>（那几秒只是加载冗余，不算真跑局）。</p>")
              .append("<p style='margin-bottom:0'>判据：<b>它若照样每圈稳定增长</b> → "
                      + "泄漏与游戏运行时长无关，问题在进出游戏流程；"
                      + "<b>它若不涨或明显变缓</b> → 泄漏发生在对局运行期间，指向游戏侧。</p>")
              .append("</div>");
        }

        if (loops.isEmpty()) {
            sb.append("<div class='card'><p>该游戏在此 run 无圈数据。</p></div>");
            sb.append(runContextCard(runId)).append(qualityCard(loops)).append(stepEvidenceCard(runId,gameId,loops,stepSummaries)).append("</div></body></html>");
            return PerfReportPrivacy.redact(sb.toString(),repo.plan(runId));
        }

        if (early) {
            appendEarlyOverview(sb, loops);
            appendEarlyTrend(sb, loops);
            appendLoopTable(sb, loops, false);
        } else {
            appendStats(sb, loops, checks);
            appendChecks(sb, checks);
        appendMemDimensionTrends(sb, loops);
        appendCpuTrends(sb, loops,repo.plan(runId));
        appendMemDelta(sb, loops);
            appendNormalTrends(sb, loops);
            appendGrowthCurves(sb, loops);
            appendLoopTable(sb, loops, true);
            appendLoopCurves(sb, loops);
        }
        sb.append(runContextCard(runId)).append(qualityCard(loops)).append(stepEvidenceCard(runId,gameId,loops,stepSummaries)).append("</div></body></html>");
        return PerfReportPrivacy.redact(sb.toString(),repo.plan(runId));
    }

    /**
     * 把报告导出为 PDF（2026-10-08，v1.9.0）：调本机 Chrome 无头打印，渲染效果
     * 就是浏览器「打印 → 存储为 PDF」的质量。
     *
     * <p>使用临时浏览器 profile，关闭后台联网、扩展与同步，保留浏览器沙箱。
     * 报告自包含，CSP 拒绝外部资源；这些设置不等同于操作系统级网络隔离。
     *
     * <p>找不到 Chrome 时抛中文提示（可先走预览页的 Cmd+P）。60 秒超时兜底。
     */
    public byte[] pdf(long runId, long gameId) {
        String html = html(runId, gameId);
        String chrome = findChrome();
        if (chrome == null) {
            throw new IllegalStateException(
                    "本机未找到 Chrome，无法导出 PDF（请先安装 Chrome，或在报告预览页用「打印 → 存储为 PDF」）");
        }
        return renderPdf(html, chrome);
    }

    static byte[] renderPdf(String html, String chrome) {
        java.nio.file.Path tmpDir = null;
        Process proc = null;
        try {
            tmpDir = java.nio.file.Files.createTempDirectory("sudpanel-report-");
            java.nio.file.Path htmlFile = tmpDir.resolve("report.html");
            java.nio.file.Path pdfFile = tmpDir.resolve("report.pdf");
            java.nio.file.Files.writeString(htmlFile, html, java.nio.charset.StandardCharsets.UTF_8);
            proc = new ProcessBuilder(chrome,
                    "--headless=new", "--disable-gpu",
                    "--user-data-dir=" + tmpDir.resolve("browser-profile").toAbsolutePath(),
                    "--no-first-run", "--no-default-browser-check",
                    "--disable-background-networking", "--disable-component-update",
                    "--disable-sync", "--disable-extensions", "--disable-default-apps",
                    "--disable-breakpad", "--disable-features=MediaRouter,OptimizationHints",
                    "--metrics-recording-only",
                    "--no-pdf-header-footer",
                    "--virtual-time-budget=8000",
                    "--print-to-pdf=" + pdfFile.toAbsolutePath(),
                    htmlFile.toUri().toString())
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (!proc.waitFor(60, java.util.concurrent.TimeUnit.SECONDS)) {
                proc.destroyForcibly();
                throw new IllegalStateException("PDF 生成超时（60s）");
            }
            if (!java.nio.file.Files.exists(pdfFile)) {
                throw new IllegalStateException("PDF 生成失败（Chrome 退出码 " + proc.exitValue() + "）");
            }
            return java.nio.file.Files.readAllBytes(pdfFile);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("PDF 生成失败：" + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("PDF 生成被中断");
        } finally {
            if (proc != null && proc.isAlive()) {
                proc.destroyForcibly();
                try { proc.waitFor(5, java.util.concurrent.TimeUnit.SECONDS); }
                catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            }
            deleteQuietly(tmpDir);
        }
    }

    /** 找本机 Chrome（环境变量 SUDPANEL_CHROME 优先，其次两个常见安装位置）。 */
    private static String findChrome() {
        String[] candidates = {
                System.getenv("SUDPANEL_CHROME"),
                "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
                System.getProperty("user.home") + "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
        };
        for (String c : candidates) {
            if (c != null && !c.isBlank()) {
                try {
                    if (java.nio.file.Files.isExecutable(java.nio.file.Path.of(c))) {
                        return c;
                    }
                } catch (Exception ignore) {
                    // 继续找下一个
                }
            }
        }
        return null;
    }

    /** 静默递归删临时目录（导出后清理，失败不影响返回）。 */
    private static void deleteQuietly(java.nio.file.Path dir) {
        if (dir == null) {
            return;
        }
        try (var walk = java.nio.file.Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    java.nio.file.Files.deleteIfExists(p);
                } catch (Exception ignore) {
                    // 清理尽力而为
                }
            });
        } catch (Exception ignore) {
            // 清理尽力而为
        }
    }

    /** 供下载端点用的安全文件名（不含扩展名）。 */
    public static String fileName(long runId, String gameName) {
        return safeFileName(gameName, runId);
    }

    /**
     * 把游戏名 + runId 压成安全文件名：只保留中文、字母数字、点与横线，其余一律换成横线。
     *
     * <p>文件名里的 # 可能被当作 URL fragment，括号与空格也可能引起本地路径编码问题。
     * 统一生成安全文件名，减少跨应用传递时的兼容问题。
     */
    static String safeFileName(String gameName, long runId) {
        String base = "性能报告-" + (gameName == null ? "" : gameName) + "-run" + runId;
        String cleaned = base.replaceAll("[^\\p{IsHan}\\w.-]", "-")
                .replaceAll("-+", "-")
                .replaceAll("^-|-$", "");
        return cleaned.isEmpty() ? "性能报告-run" + runId : cleaned;
    }

    /** 模式显示名（与控制面板 MODE_LABEL 同一口径）。 */
    private static String modeLabel(String mode) {
        return switch (mode == null ? "" : mode) {
            case "AI_ROOM" -> "游戏内性能监测";
            case "EARLY_SETTLE" -> "异常时序";
            default -> "重复开关游戏";
        };
    }

    /** 判定徽章（与控制面板同一套色系：PASS 绿 / FAIL 红 / SUSPECT 橙 / 其余灰）。 */
    private static String badge(String verdict) {
        String v = verdict == null || verdict.isBlank() || "null".equals(verdict) ? "" : verdict.trim();
        if (v.isEmpty()) {
            return "<span class='badge b-na'>—</span>";
        }
        String cls = switch (v) {
            case "PASS" -> "b-pass";
            case "FAIL" -> "b-fail";
            case "SUSPECT" -> "b-suspect";
            default -> "b-na";
        };
        return "<span class='badge " + cls + "'>" + esc(v) + "</span>";
    }

    /** 一张统计卡（标题 / 大数值 / 判定徽章 + 副题）。 */
    private static void statCard(StringBuilder sb, String title, String value, String sub, String verdict) {
        sb.append("<div class='stat'><div class='t'>").append(esc(title)).append("</div>")
          .append("<div class='v'>").append(esc(value)).append("</div>")
          .append("<div class='s'>").append(badge(verdict))
          .append("<span>").append(esc(sub)).append("</span></div></div>");
    }

    /** 正常开关 / 人机对局模式的跨圈趋势：PSS（基线/均值/峰值）、CPU（均值/峰值）、ws 线程（基线/局内峰值）。 */
    private void appendNormalTrends(StringBuilder sb, List<Map<String, Object>> loops) {
        int n = loops.size();
        List<String> x = new ArrayList<>(n);
        List<Double> basePss = new ArrayList<>(n);
        List<Double> avgPss = new ArrayList<>(n);
        List<Double> peakPss = new ArrayList<>(n);
        List<Double> cpuAvg = new ArrayList<>(n);
        List<Double> cpuPeak = new ArrayList<>(n);
        List<Double> baseWs = new ArrayList<>(n);
        List<Double> inWs = new ArrayList<>(n);
        // 无效圈下标：图表里对应 x 位置画红 ×（面板用空心点，报告用 × 更醒目）
        Set<Integer> invalid = new HashSet<>();
        for (int i = 0; i < n; i++) {
            Map<String, Object> l = loops.get(i);
            x.add(String.valueOf(l.get("loopNo")));
            boolean ok = "OK".equals(l.get("status"));
            if (!ok) {
                invalid.add(i);
            }
            basePss.add(ok ? kbToMb(l.get("baselinePssKb")) : null);
            avgPss.add(ok ? kbToMb(l.get("pssAvgKb")) : null);
            peakPss.add(ok ? kbToMb(l.get("pssPeakKb")) : null);
            cpuAvg.add(ok ? num(l.get("cpuAvg")) : null);
            cpuPeak.add(ok ? num(l.get("cpuPeak")) : null);
            baseWs.add(ok ? num(l.get("baselineWsThreads")) : null);
            inWs.add(ok ? num(l.get("inGameWsThreads")) : null);
        }
        // 三图与控制面板的 PSS / CPU / ws 三张趋势图同构，统一包进一张卡片
        sb.append("<div class='card'><h2>跨圈趋势（× = 无效圈）</h2>");
        sb.append("<div class='chart'>").append(lineChart("PSS 趋势（MB）", x, List.of(
                new SeriesSpec("关后基线", "#9a6700", basePss, false),
                new SeriesSpec("局内均值", "#0969da", avgPss, false),
                new SeriesSpec("局内峰值", "#cf222e", peakPss, false)), invalid)).append("</div>");
        sb.append("<div class='chart'>").append(lineChart("CPU 占用趋势（%）", x, List.of(
                new SeriesSpec("圈均值", "#0969da", cpuAvg, false),
                new SeriesSpec("圈峰值", "#cf222e", cpuPeak, false)), invalid)).append("</div>");
        sb.append("<div class='chart'>").append(lineChart("WebSocket 线程数趋势（个）", x, List.of(
                new SeriesSpec("关后基线", "#1a7f37", baseWs, false),
                new SeriesSpec("局内峰值", "#8250df", inWs, false)), invalid)).append("</div>");
        sb.append("</div>");
    }

    /** 提前结算模式趋势：每圈实际提前量（ms）。 */
    private void appendEarlyTrend(StringBuilder sb, List<Map<String, Object>> loops) {
        int n = loops.size();
        List<String> x = new ArrayList<>(n);
        List<Double> delay = new ArrayList<>(n);
        Set<Integer> invalid = new HashSet<>();
        for (int i = 0; i < n; i++) {
            Map<String, Object> l = loops.get(i);
            x.add(String.valueOf(l.get("loopNo")));
            boolean ok = "OK".equals(l.get("status"));
            if (!ok) {
                invalid.add(i);
            }
            delay.add(ok ? num(evidence(l).get("actualDelayMs")) : null);
        }
        sb.append("<div class='card'><h2>跨圈趋势（× = 无效圈）</h2>");
        sb.append("<div class='chart'>").append(lineChart("提前结算实际延迟（ms，点开始→点到「游戏结束」）", x,
                List.of(new SeriesSpec("实际延迟", "#0969da", delay, false)), invalid)).append("</div>");
        sb.append("</div>");
    }

    /** 内存增长曲线（v1.9.1）：各局局内 PSS 抽样叠加，浅→深 = 局序先后。 */
    private void appendGrowthCurves(StringBuilder sb, List<Map<String, Object>> loops) {
        List<Map<String, Object>> ok = loops.stream().filter(l -> "OK".equals(l.get("status"))).toList();
        if (ok.isEmpty()) {
            return;
        }
        // 抽样 ≤6 条：首末必取、中间均匀（与面板端点同一规则）
        List<Map<String, Object>> picked;
        if (ok.size() <= 6) {
            picked = ok;
        } else {
            picked = new ArrayList<>(6);
            for (int i = 0; i < 6; i++) {
                picked.add(ok.get((int) Math.round((double) i * (ok.size() - 1) / 5.0)));
            }
        }
        List<List<Double>> curves = new ArrayList<>();
        List<String> names = new ArrayList<>();
        int minLen = Integer.MAX_VALUE;
        for (Map<String, Object> l : picked) {
            List<Double> pss = new ArrayList<>();
            for (Map<String, Object> smp : repo.samplesOf((Long) l.get("id"))) {
                pss.add(kbToMb(smp.get("pssKb")));
            }
            if (pss.isEmpty()) {
                continue;
            }
            curves.add(pss);
            names.add("第" + l.get("loopNo") + "局");
            minLen = Math.min(minLen, pss.size());
        }
        if (curves.isEmpty()) {
            return;
        }
        // x 轴 = 局内秒（各曲线截断到最短长度对齐）
        List<String> x = new ArrayList<>(minLen);
        for (int i = 1; i <= minLen; i++) {
            x.add(String.valueOf(i));
        }
        String[] palette = {"#93c5fd", "#60a5fa", "#3b82f6", "#2563eb", "#1d4ed8", "#172554"};
        List<SeriesSpec> specs = new ArrayList<>();
        for (int i = 0; i < curves.size(); i++) {
            specs.add(new SeriesSpec(names.get(i), palette[i % palette.length],
                    curves.get(i).subList(0, minLen), false));
        }
        sb.append("<div class='card'><h2>内存增长曲线（各局局内 PSS 叠加 · 浅→深 = 局序先后）</h2>");
        if (ok.size() > 6) {
            sb.append("<p class='small'>共 ").append(ok.size())
              .append(" 个有效局，等距抽样 6 条覆盖全程；后段曲线整体抬高 = 存在内存增长。</p>");
        } else {
            sb.append("<p class='small'>后段曲线整体抬高 = 存在内存增长。</p>");
        }
        sb.append("<div class='chart'>").append(lineChart("各局局内 PSS（MB）", x, specs, Set.of())).append("</div>");
        sb.append("</div>");
    }

    /** 内存六项的显示名 / 列名后缀 / 颜色（2026-10-10，v1.11.0 #80 的呈现部分）。 */
    private static final String[] DIM_NAMES = {"Java 堆", "Native 堆", "图形", "代码与资源", "私有其它", "系统"};
    private static final String[] DIM_KEYS = {"JavaKb", "NativeKb", "GraphicsKb", "CodeKb",
            "PrivateOtherKb", "SystemKb"};
    // Native 使用红色，与其他内存分项保持明显区分。
    private static final String[] DIM_COLORS = {"#0ea5e9", "#dc2626", "#8b5cf6", "#f59e0b",
            "#10b981", "#64748b"};

    /**
     * 「局内 CPU 趋势」小节（2026-10-11，v1.11.0）：逐圈的局内 CPU 均值与峰值，带阈值参考线。
     *
     * <p>与内存那节同构：内存看「涨不涨」，CPU 看「稳不稳、有没有圈次顶到阈值」。
     * 两者合起来才是完整的局内资源视图 —— 内存持续增长时 CPU 往往也在抬，能互相印证。
     */
    private void appendCpuTrends(StringBuilder sb, List<Map<String, Object>> loops,PerfExecutionPlan plan) {
        List<Map<String, Object>> ok = loops.stream().filter(l -> "OK".equals(l.get("status"))).toList();
        if (ok.size() < 3) {
            return;
        }
        // 整机口径优先（v1.5 #21）：老 run 无 cpu_avg_sys，回退单核值并在图上与文字里都注明，
        // 免得把两种口径的数值放在一起比 —— 那是这个项目已经吃过一次的坑。
        boolean sys = ok.stream().anyMatch(l -> l.get("cpuAvgSys") != null);
        String avgKey = sys ? "cpuAvgSys" : "cpuAvg";
        String peakKey = sys ? "cpuPeakSys" : "cpuPeak";
        List<Double> avg = new ArrayList<>();
        List<Double> peak = new ArrayList<>();
        for (Map<String, Object> l : ok) {
            Object a = l.get(avgKey);
            Object p = l.get(peakKey);
            if (a == null || p == null) {
                avg.clear();
                break;
            }
            avg.add(((Number) a).doubleValue());
            peak.add(((Number) p).doubleValue());
        }
        if (avg.isEmpty()) {
            return;
        }
        List<Integer> loopNos = ok.stream().map(l -> ((Number) l.get("loopNo")).intValue()).toList();
        double threshold = plan==null?props.perfCpuThreshold():plan.judgeProperties(props).perfCpuThreshold();
        double peakMax = peak.stream().mapToDouble(d -> d).max().orElse(0);
        // 纵轴上限留出阈值以上的空间：全部圈次都远低于阈值时，若上限贴着峰值走，
        // 阈值参考线会跑到图外，看不出「离阈值还有多远」
        double yMax = Math.max(peakMax, threshold) * 1.15;
        boolean over = avg.stream().anyMatch(v -> v > threshold);

        sb.append("<div class='card'><h2>局内 CPU 趋势（每圈）</h2>");
        sb.append("<div class='small'>口径：<b>")
          .append(sys ? "整机占比（单核值 ÷ 在线核数）" : "单核占比（历史圈无核数记录，口径不符，仅供参考）")
          .append("</b>；每条线取该圈<b>对局采样窗口内</b>的均值与峰值。</div>");
        sb.append("<div style='display:flex;flex-wrap:wrap;gap:16px;margin:10px 0;font-size:12px;color:#475569'>")
          .append("<span style='white-space:nowrap'><span style='display:inline-block;width:10px;height:10px;")
          .append("border-radius:2px;background:#0284c7;margin-right:5px'></span>均值中位数 ")
          .append(fmt(PerfJudge.median(avg))).append("%</span>")
          .append("<span style='white-space:nowrap'><span style='display:inline-block;width:10px;height:10px;")
          .append("border-radius:2px;background:#a855f7;margin-right:5px'></span>峰值最大 ")
          .append(fmt(peakMax)).append("%</span>")
          .append("<span style='color:#f43f5e;white-space:nowrap'>— — 阈值 ").append(fmt(threshold))
          .append("%</span></div>");
        sb.append(memChart(1050, 290, List.of(avg, peak), List.of("#0284c7", "#a855f7"),
                yMax, loopNos, true, threshold, "阈值 " + fmt(threshold) + "%"));
        sb.append("<div class='small'>")
          .append(over ? "有圈次的均值超过阈值 —— 结论见「自动判定」里的 CPU 占用项。"
                       : "全部圈次的均值都在阈值以内。")
          .append("</div></div>");
    }

    /**
     * 「内存分项趋势」小节（2026-10-10，v1.11.0）：六项各画一条曲线 + 六张逐项小图。
     *
     * <p>为什么要有图：判定项 6 的结论是文字（「增长主要来自 Native 堆，每圈 +809KB，占 81%」），
     * 但复核的人往往想亲眼看到「另外五条是平的」。六条线画在一张图上，这件事一眼就能确认。
     *
     * <p>口径与判定层保持一致：有回收后基线就用回收后序列，避免「图与结论口径不一致」这种最坑人的情况。
     */
    private void appendMemDimensionTrends(StringBuilder sb, List<Map<String, Object>> loops) {
        List<Map<String, Object>> ok = loops.stream()
                .filter(l -> "OK".equals(l.get("status")) && l.get("baselinePssKb") != null)
                .toList();
        if (ok.size() < 3) {
            return;
        }
        boolean useTrimmed = ok.stream().allMatch(l -> l.get("trimmedPssKb") != null);
        String prefix = useTrimmed ? "trimmed" : "baseline";
        List<List<Double>> series = new ArrayList<>();
        for (String key : DIM_KEYS) {
            List<Double> s = new ArrayList<>();
            for (Map<String, Object> l : ok) {
                Object v = l.get(prefix + key);
                if (v == null) {
                    s.clear();
                    break;
                }
                s.add(((Number) v).doubleValue() / 1024.0);
            }
            series.add(s);
        }
        if (series.get(0).isEmpty()) {
            return; // 老 run 没有分项数据，这一节整块不出现（不画一张空图充数）
        }
        List<Integer> loopNos = ok.stream().map(l -> ((Number) l.get("loopNo")).intValue()).toList();
        double yMax = 1;
        for (List<Double> s : series) {
            for (double v : s) {
                yMax = Math.max(yMax, v);
            }
        }

        sb.append("<div class='card'><h2>内存分项趋势（每圈）</h2>");
        // 首圈可能包含首次加载资源的开销，因此另外展示从第二圈开始的持续变化。
        // 这只是对比口径，是否存在泄漏仍需结合长期趋势和其他证据。
        int baseIdx = series.get(0).size() > 1 ? 1 : 0;
        sb.append("<div class='small'>口径：<b>").append(useTrimmed ? "回收后基线" : "未回收基线")
          .append("</b>（与检测项 1 / 6 一致）。图例里的「持续」= <b>第 2 圈 → 末圈</b>；"
                  + "第 1 圈标为<b>冷启动</b>（首次进游戏加载资源，一次性，不计入增长）。"
                  + "六条线一起看：哪一条在往上走，就是增长的那一类。</div>");
        sb.append("<div style='display:flex;flex-wrap:wrap;gap:16px;margin:10px 0;font-size:12px;color:#475569'>");
        for (int i = 0; i < DIM_NAMES.length; i++) {
            List<Double> s = series.get(i);
            double last = s.get(s.size() - 1);
            double base = s.get(baseIdx);
            sb.append("<span style='white-space:nowrap'><span style='display:inline-block;width:10px;height:10px;")
              .append("border-radius:2px;background:").append(DIM_COLORS[i]).append(";margin-right:5px'></span>")
              .append(DIM_NAMES[i]).append(' ').append(Math.round(base)).append(" → ")
              .append(Math.round(last)).append("MB（持续 ").append(last - base >= 0 ? "+" : "")
              .append(Math.round(last - base)).append("）</span>");
        }
        sb.append("</div>");
        sb.append(memChart(1050, 290, series, List.of(DIM_COLORS), yMax, loopNos, true, null, null, 0, "冷启动"));
        sb.append("</div>");

        sb.append("<div class='card'><h2>逐项曲线（各自量级）</h2>")
          .append("<div class='small'>总览里六条线共用一条纵轴，小量级的那几项会被压扁；"
                  + "下面的每一张按自身区间绘制，便于看它自己有没有在涨。</div>")
          .append("<div style='display:grid;grid-template-columns:1fr 1fr;gap:12px;margin-top:10px'>");
        for (int i = 0; i < DIM_NAMES.length; i++) {
            List<Double> s = series.get(i);
            double base = s.get(baseIdx);
            double last = s.get(s.size() - 1);
            double lo = s.stream().mapToDouble(d -> d).min().orElse(0);
            double hi = s.stream().mapToDouble(d -> d).max().orElse(1);
            sb.append("<div style='border:1px solid #e2e8f0;border-radius:10px;padding:8px 10px'>")
              .append("<div style='display:flex;justify-content:space-between;font-size:12px;margin-bottom:4px'>")
              .append("<b style='color:").append(DIM_COLORS[i]).append("'>").append(DIM_NAMES[i]).append("</b>")
              .append("<span style='color:#475569'>").append(Math.round(base)).append(" → ")
              .append(Math.round(last)).append("MB（持续 ").append(last - base >= 0 ? "+" : "")
              .append(Math.round(last - base)).append("）</span></div>")
              // 纵轴留 8% 余量：贴着走的平坦序列如果正好等于上限，线会贴顶看不清起伏
              .append(memChart(480, 120, List.of(s), List.of(DIM_COLORS[i]),
                      Math.max(hi, lo + 1) * 1.08, loopNos, false))
              .append("</div>");
        }
        sb.append("</div></div>");
    }

    /**
     * 生成一张内联折线 SVG。
     *
     * <p>与报告里其它图一样用**内联 SVG**：PDF 打印时不会掉图，也不依赖任何外部资源或脚本。
     *
     * @param series 多条序列（每条点数相同）；只有一条时线加粗
     * @param colors 与 series 一一对应的颜色
     */
    private static String memChart(int w, int h, List<List<Double>> series, List<String> colors,
                                   double yMax, List<Integer> loopNos, boolean showAxis) {
        return memChart(w, h, series, colors, yMax, loopNos, showAxis, null, null, null, null);
    }

    /** 带横向参考线（画阈值用）的版本。 */
    private static String memChart(int w, int h, List<List<Double>> series, List<String> colors,
                                   double yMax, List<Integer> loopNos, boolean showAxis,
                                   Double refValue, String refLabel) {
        return memChart(w, h, series, colors, yMax, loopNos, showAxis, refValue, refLabel, null, null);
    }

    /**
     * 完整版：横向参考线 + 竖向标记线。
     *
     * @param refValue  横向参考线所在纵轴值；null = 不画
     * @param refLabel  横向参考线旁边的文字
     * @param markIdx   在第几个数据点画一条竖虚线（用来标「第 1 圈冷启动」）；null = 不画
     * @param markLabel 竖向标记线的文字
     */
    private static String memChart(int w, int h, List<List<Double>> series, List<String> colors,
                                   double yMax, List<Integer> loopNos, boolean showAxis,
                                   Double refValue, String refLabel, Integer markIdx, String markLabel) {
        int left = showAxis ? 54 : 8;
        int right = showAxis ? 14 : 8;
        int top = 10;
        int bottom = showAxis ? 28 : 8;
        double x0 = left;
        double x1 = w - right;
        double y0 = top;
        double y1 = h - bottom;
        int n = series.get(0).size();
        StringBuilder g = new StringBuilder();
        g.append("<svg viewBox='0 0 ").append(w).append(' ').append(h)
         .append("' width='100%' style='display:block' xmlns='http://www.w3.org/2000/svg'>");
        if (showAxis) {
            for (int k = 0; k <= 4; k++) {
                double val = yMax * k / 4.0;
                double y = y1 - (val / yMax) * (y1 - y0);
                g.append("<line x1='").append(fmt(x0)).append("' y1='").append(fmt(y))
                 .append("' x2='").append(fmt(x1)).append("' y2='").append(fmt(y))
                 .append("' stroke='#e2e8f0' stroke-width='1'/>")
                 .append("<text x='").append(fmt(x0 - 6)).append("' y='").append(fmt(y + 4))
                 .append("' font-size='10' fill='#94a3b8' text-anchor='end'>")
                 .append(Math.round(val)).append(" MB</text>");
            }
        }
        for (int si = 0; si < series.size(); si++) {
            List<Double> s = series.get(si);
            StringBuilder pts = new StringBuilder();
            for (int i = 0; i < s.size(); i++) {
                double x = n == 1 ? x0 : x0 + (x1 - x0) * i / (n - 1);
                double y = y1 - (s.get(i) / yMax) * (y1 - y0);
                if (i > 0) {
                    pts.append(' ');
                }
                pts.append(fmt(x)).append(',').append(fmt(y));
            }
            g.append("<polyline fill='none' stroke='").append(colors.get(si))
             .append("' stroke-width='").append(series.size() == 1 ? "2" : "1.8")
             .append("' stroke-linejoin='round' points='").append(pts).append("'/>");
        }
        if (refValue != null && refValue > 0 && refValue <= yMax) {
            double ry = y1 - (refValue / yMax) * (y1 - y0);
            g.append("<line x1='").append(fmt(x0)).append("' y1='").append(fmt(ry))
             .append("' x2='").append(fmt(x1)).append("' y2='").append(fmt(ry))
             .append("' stroke='#f43f5e' stroke-width='1' stroke-dasharray='5 4'/>");
            if (refLabel != null) {
                g.append("<text x='").append(fmt(x1 - 4)).append("' y='").append(fmt(ry - 5))
                 .append("' font-size='10' fill='#f43f5e' text-anchor='end'>")
                 .append(esc(refLabel)).append("</text>");
            }
        }
        if (markIdx != null && markIdx >= 0 && markIdx < n) {
            double mx = n == 1 ? x0 : x0 + (x1 - x0) * markIdx / (n - 1);
            g.append("<line x1='").append(fmt(mx)).append("' y1='").append(fmt(y0))
             .append("' x2='").append(fmt(mx)).append("' y2='").append(fmt(y1))
             .append("' stroke='#94a3b8' stroke-width='1' stroke-dasharray='3 3'/>");
            if (markLabel != null) {
                g.append("<text x='").append(fmt(mx + 4)).append("' y='").append(fmt(y0 + 11))
                 .append("' font-size='10' fill='#94a3b8'>").append(esc(markLabel)).append("</text>");
            }
        }
        if (showAxis) {
            // 横轴只标首 / 中 / 末三个圈号：50 圈时全标会挤成一团
            int[] idx = {0, (n - 1) / 2, n - 1};
            for (int i : idx) {
                double x = n == 1 ? x0 : x0 + (x1 - x0) * i / (n - 1);
                g.append("<text x='").append(fmt(x)).append("' y='").append(fmt(y1 + 16))
                 .append("' font-size='10' fill='#94a3b8' text-anchor='middle'>第")
                 .append(loopNos.get(i)).append("圈</text>");
            }
        }
        g.append("</svg>");
        return g.toString();
    }

    /**
     * 「每圈净增与可回收缓存」小节（2026-10-10，v1.10.0 #81/#84）。
     *
     * <p>两个作用：
     * <ol>
     *   <li><b>净增</b>：累计曲线看不出「哪一圈突然跳了一下」。这里逐圈显示相对上一圈的净增，
     *       并把超过「正增量中位数 3 倍且大于 5MB」的圈标红 —— 那种单圈跳变通常是某次事件，
     *       不是趋势，混在回归里会把斜率带偏。</li>
     *   <li><b>缓存</b>：采了「回收后基线」时，显示两者之差。这块系统一施压就归还，
     *       <b>不是泄漏</b>，但它确实计入了未回收口径的增长 —— 复核时能一眼看出「增长里有多少是缓存」。</li>
     * </ol>
     */
    private void appendMemDelta(StringBuilder sb, List<Map<String, Object>> loops) {
        List<Map<String, Object>> ok = loops.stream()
                .filter(l -> "OK".equals(l.get("status")) && l.get("baselinePssKb") != null)
                .toList();
        if (ok.size() < 3) {
            return;
        }
        List<Integer> deltaKb = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        List<Integer> cacheKb = new ArrayList<>();
        for (int i = 1; i < ok.size(); i++) {
            int cur = ((Number) ok.get(i).get("baselinePssKb")).intValue();
            deltaKb.add(cur - ((Number) ok.get(i - 1).get("baselinePssKb")).intValue());
            labels.add("第" + ok.get(i).get("loopNo") + "圈");
            Object t = ok.get(i).get("trimmedPssKb");
            cacheKb.add(t == null ? null : cur - ((Number) t).intValue());
        }
        int maxAbs = Math.max(1, deltaKb.stream().mapToInt(Math::abs).max().orElse(1));
        List<Integer> pos = deltaKb.stream().filter(d -> d > 0).sorted().toList();
        int posMedian = pos.isEmpty() ? 0 : pos.get(pos.size() / 2);
        long outlierCount = deltaKb.stream().filter(d -> d > posMedian * 3 && d > 5120).count();
        boolean hasCache = cacheKb.stream().anyMatch(Objects::nonNull);

        sb.append("<div class='card'><h2>每圈净增与可回收缓存</h2>");
        sb.append("<div class='small'>逐圈净增相对的是<b>上一圈的关后基线</b>（不是绝对值）。");
        sb.append("单圈净增的中位数=").append(posMedian / 1024).append("MB");
        if (outlierCount > 0) {
            sb.append("，其中 <b>").append(outlierCount).append(" 圈</b>净增超过中位数 3 倍且大于 5MB（下表标红）");
        }
        sb.append("。下表<b>只列异常圈及其前后各一圈</b>，中间平稳的段落折叠。</div>");
        if (hasCache) {
            List<Integer> cs = cacheKb.stream().filter(Objects::nonNull).sorted().toList();
            sb.append("<div class='small'>可回收缓存（未回收基线 − 回收后基线）中位数=")
              .append(cs.isEmpty() ? 0 : cs.get(cs.size() / 2) / 1024)
              .append("MB：这部分系统一施压就归还，<b>属于缓存不属于泄漏</b>。</div>");
        } else {
            sb.append("<div class='small'>未采集「回收后基线」（开关关闭），无法区分缓存与泄漏。</div>");
        }
        sb.append("<div class='tablewrap'><table><tr><th>圈</th><th>净增 vs 上一圈基线</th><th>数值</th>");
        sb.append(hasCache ? "<th>可回收缓存</th>" : "");
        sb.append("</tr>");
        // 只列异常圈及其前后各一圈（2026-10-10 使用者拍板）：50 圈的报告里铺满 49 行净增，
        // 真正要看的往往只有那几处跳变 —— 把上下文留一圈，其余折叠成一行省略提示。
        boolean[] show = new boolean[deltaKb.size()];
        boolean anyOutlier = false;
        for (int i = 0; i < deltaKb.size(); i++) {
            if (deltaKb.get(i) > posMedian * 3 && deltaKb.get(i) > 5120) {
                anyOutlier = true;
                show[i] = true;
                if (i > 0) {
                    show[i - 1] = true;
                }
                if (i + 1 < show.length) {
                    show[i + 1] = true;
                }
            }
        }
        if (!anyOutlier) {
            sb.append("<tr><td colspan='").append(hasCache ? 4 : 3)
              .append("' class='small'>未发现异常圈（逐圈净增都在中位数的 3 倍以内）</td></tr>");
        }
        for (int i = 0; i < deltaKb.size(); i++) {
            if (!show[i]) {
                // 连续被折叠的一段只提示一次
                if (i == 0 || show[i - 1]) {
                    sb.append("<tr><td class='small'>…</td><td class='small' colspan='")
                      .append(hasCache ? 3 : 2)
                      .append("'>中间若干圈净增平稳，已折叠</td></tr>");
                }
                continue;
            }
            int d = deltaKb.get(i);
            boolean outlier = d > posMedian * 3 && d > 5120;
            // 条形宽度按最大绝对净增等比缩放；负增量描成灰色以示方向
            int pct = (int) Math.round(Math.abs(d) * 100.0 / maxAbs);
            String color = d < 0 ? "#94a3b8" : (outlier ? "#dc2626" : "#0ea5e9");
            sb.append("<tr><td>").append(labels.get(i).replace("第", "").replace("圈", ""))
              .append("</td><td><div style='background:").append(color)
              .append(";height:10px;border-radius:3px;width:").append(Math.max(1, pct)).append("%'></div></td>")
              .append("<td>").append(d / 1024).append("MB").append(outlier ? " ⚠" : "").append("</td>");
            if (hasCache) {
                Integer c = cacheKb.get(i);
                sb.append("<td>").append(c == null ? "—" : (c / 1024) + "MB").append("</td>");
            }
            sb.append("</tr>");
        }
        sb.append("</table></div>");
        sb.append("<div class='small'>完整逐圈净增可在面板的「每圈数据」里逐圈查看。</div>");
        sb.append("</div>");
    }

    /** 每圈数据表：列与控制面板「每圈数据」对齐（early 用判定/证据列）。 */
    private void appendLoopTable(StringBuilder sb, List<Map<String, Object>> loops, boolean normal) {
        sb.append("<div class='card'><h2>每圈数据</h2><div class='tablewrap'><table>");
        if (normal) {
            // 对局ID 紧跟状态（v1.10.4 #86）：它是标识不是指标，且只有「游戏内性能监测」模式有值，
            // 放在指标列中间会把表头撑得更难读
            sb.append("<tr><th>圈</th><th>状态</th><th>对局ID</th>")
              .append("<th>基线PSS(MB)</th><th>PSS均值(MB)</th><th>PSS峰值(MB)</th>")
              .append("<th>CPU均值(%)</th><th>CPU峰值(%)</th><th>ws 基线/局内</th><th>人工</th></tr>");
            for (Map<String, Object> l : loops) {
                boolean inv = "INVALID".equals(l.get("status"));
                sb.append("<tr><td>").append(l.get("loopNo"))
                  .append("</td><td>").append(badge(inv ? "INVALID" : "OK"));
                // 对局提前结束（v1.9.4 #78）：这一圈没跑满设定时长，均值天然偏低 —— 必须标出来，
                // 否则复核者会把「提前结束圈」的低值当成「这游戏内存增长慢」的证据。
                // 判空用 == 而非 equals：老 run 这一列是 NULL，读到的是 null。
                if (l.get("earlyEnd") != null && ((Number) l.get("earlyEnd")).intValue() == 1) {
                    sb.append(" <span class='small'>（提前结束）</span>");
                }
                // 无效原因以小字附在状态格：面板标准模式不单列该列，报告里留痕有价值
                if (inv && l.get("invalidReason") != null && !str(l.get("invalidReason")).isBlank()) {
                    sb.append("<div class='small'>").append(esc(str(l.get("invalidReason")))).append("</div>");
                }
                // 对局ID（v1.10.7 修）：v1.10.4 只加了表头、漏了这个单元格，
                // 结果是「列整体错位一格 + 对局ID 永远空着」。只有游戏内性能监测模式有值，
                // 其余模式显示破折号 —— 与 csv 导出、面板表格三处口径一致。
                Object rid = l.get("gameRoundId");
                sb.append("</td><td>")
                  .append(rid == null ? "—" : esc(str(rid)))
                  .append("</td><td>").append(cell(kbToMb(l.get("baselinePssKb"))))
                  .append("</td><td>").append(cell(kbToMb(l.get("pssAvgKb"))))
                  .append("</td><td>").append(cell(kbToMb(l.get("pssPeakKb"))))
                  .append("</td><td>").append(cell(num(l.get("cpuAvg"))))
                  .append("</td><td>").append(cell(num(l.get("cpuPeak"))))
                  .append("</td><td>").append(str(l.get("baselineWsThreads"))).append(" / ")
                  .append(str(l.get("inGameWsThreads")))
                  // 电量列已去掉（2026-10-12 使用者要求）：这项在压测里几乎不变，
                  // 占一列反而把真正要看的指标挤窄
                  .append("</td><td>").append(badge(str(l.get("humanVerdict"))))
                  .append("</td></tr>");
            }
        } else {
            sb.append("<tr><th>圈</th><th>状态</th><th>判定</th><th>无效原因 / 摘要</th>")
              .append("<th>实际延迟ms</th><th>崩溃命中数</th><th>能否回大厅</th><th>崩溃日志摘要</th>")
              .append("<th>人工</th><th>备注</th></tr>");
            for (Map<String, Object> l : loops) {
                boolean inv = "INVALID".equals(l.get("status"));
                Map<String, Object> ev = evidence(l);
                Object hits = ev.get("crashHits");
                String hitStr = hits instanceof List<?> list
                        ? String.join(" | ", list.stream().map(String::valueOf).toList()) : "";
                Object recovered = ev.get("recoveredToLobby");
                sb.append("<tr><td>").append(l.get("loopNo"))
                  .append("</td><td>").append(badge(inv ? "INVALID" : "OK"))
                  .append("</td><td>").append(badge(str(l.get("verdict"))))
                  .append("</td><td class='small'>").append(esc(str(l.get("invalidReason"))))
                  .append("</td><td>").append(str(ev.get("actualDelayMs")))
                  .append("</td><td>").append(str(ev.get("crashHitCount")))
                  .append("</td><td>").append(recovered == null ? "" : (Boolean.TRUE.equals(recovered) ? "是" : "否"))
                  .append("</td><td><pre>").append(esc(hitStr)).append("</pre>")
                  .append("</td><td>").append(badge(str(l.get("humanVerdict"))))
                  .append("</td><td>").append(esc(str(l.get("humanNote"))))
                  .append("</td></tr>");
            }
        }
        sb.append("</table></div></div>");
    }

    /** 单圈曲线：每个有效圈一张双轴图（左 PSS MB / 右 CPU %），details 折叠逐圈展示。 */
    private void appendLoopCurves(StringBuilder sb, List<Map<String, Object>> loops) {
        List<Map<String, Object>> ok = loops.stream().filter(l -> "OK".equals(l.get("status"))).toList();
        sb.append("<div class='card no-print'><h2>单圈曲线（局内逐秒采样，点击展开）</h2>");
        if (ok.isEmpty()) {
            sb.append("<p class='small'>无有效圈。</p></div>");
            return;
        }
        // 圈数超上限时等距抽样覆盖全程（不截断早期，避免误读长程 run）
        List<Map<String, Object>> toDraw;
        if (ok.size() <= LOOP_CURVE_CAP) {
            toDraw = ok;
        } else {
            sb.append("<p class='small'>共 ").append(ok.size()).append(" 个有效圈，超过展示上限 ")
              .append(LOOP_CURVE_CAP).append("，已等距抽样覆盖全程。</p>");
            toDraw = new ArrayList<>(LOOP_CURVE_CAP);
            for (int i = 0; i < LOOP_CURVE_CAP; i++) {
                toDraw.add(ok.get((int) Math.round((double) i * (ok.size() - 1) / (LOOP_CURVE_CAP - 1))));
            }
        }
        for (Map<String, Object> l : toDraw) {
            List<Map<String, Object>> samples = repo.samplesOf((Long) l.get("id"));
            if (samples.isEmpty()) {
                continue;
            }
            int n = samples.size();
            List<String> x = new ArrayList<>(n);
            List<Double> pss = new ArrayList<>(n);
            List<Double> cpu = new ArrayList<>(n);
            for (Map<String, Object> smp : samples) {
                x.add(String.valueOf(((Number) smp.get("seq")).intValue() + 1));
                pss.add(kbToMb(smp.get("pssKb")));
                cpu.add(num(smp.get("cpuPercent")));
            }
            sb.append("<details><summary>第 ").append(l.get("loopNo")).append(" 圈</summary>")
              .append("<div class='chart'>").append(lineChart("第 " + l.get("loopNo") + " 圈", x, List.of(
                      new SeriesSpec("PSS(MB)", "#0969da", pss, false),
                      new SeriesSpec("CPU(%)", "#cf222e", cpu, true)), Set.of())).append("</div></details>");
        }
        sb.append("</div>");
    }

    /** 从聚合判定里取某检测项的判定（无则 null）。 */
    private static String checkVerdict(List<Map<String, Object>> checks, int no) {
        for (Map<String, Object> c : checks) {
            if (c.get("checkNo") instanceof Number n && n.intValue() == no) {
                return str(c.get("verdict"));
            }
        }
        return null;
    }

    /** 概览卡（对齐控制面板）：内存增幅 / CPU 均值 / ws 线程 / 有效圈 + 圈统计 + 机器初判条。 */
    private void appendStats(StringBuilder sb, List<Map<String, Object>> loops,
                             List<Map<String, Object>> checks) {
        List<Map<String, Object>> ok = loops.stream().filter(l -> "OK".equals(l.get("status"))).toList();
        // 内存增幅：首末有效圈的关后基线 PSS 相对变化（与控制面板同一算法）
        String memGrowth = "—";
        if (!ok.isEmpty()) {
            Double first = kbToMb(ok.get(0).get("baselinePssKb"));
            Double last = kbToMb(ok.get(ok.size() - 1).get("baselinePssKb"));
            if (first != null && last != null && first > 0) {
                memGrowth = String.format("%.1f%%", (last - first) / first * 100);
            }
        }
        // CPU 均值（有效圈单核口径平均）与 ws 线程区间（局内峰值 min~max）
        double cpuSum = 0;
        int cpuN = 0;
        Integer wsMin = null, wsMax = null;
        for (Map<String, Object> l : ok) {
            Double c = num(l.get("cpuAvg"));
            if (c != null) {
                cpuSum += c;
                cpuN++;
            }
            Double w = num(l.get("inGameWsThreads"));
            if (w != null) {
                int v = w.intValue();
                wsMin = wsMin == null ? v : Math.min(wsMin, v);
                wsMax = wsMax == null ? v : Math.max(wsMax, v);
            }
        }
        String cpuAvgAll = cpuN > 0 ? String.format("%.1f%%", cpuSum / cpuN) : "—";
        String wsRange = wsMin != null ? wsMin + "–" + wsMax : "—";

        sb.append("<div class='card'><h2>概览</h2><div class='stats'>");
        statCard(sb, "内存增幅", memGrowth, "首末圈基线 PSS", checkVerdict(checks, 1));
        statCard(sb, "CPU 均值", cpuAvgAll, "阈值 60%", checkVerdict(checks, 4));
        statCard(sb, "ws 线程", wsRange, "局内区间", checkVerdict(checks, 3));
        statCard(sb, "有效圈", ok.size() + "/" + loops.size(),
                (loops.size() - ok.size()) + " 圈无效", null);
        sb.append("</div>");
        // 圈统计 + 机器初判条（与控制面板的徽章行同构）
        sb.append("<div class='chips' style='margin-top:12px'>")
          .append("<span class='badge b-pass'>OK ").append(ok.size()).append("</span>")
          .append("<span class='badge b-fail'>INVALID ").append(loops.size() - ok.size()).append("</span>")
          .append("<span>共 ").append(loops.size()).append(" 圈</span>");
        if (!checks.isEmpty()) {
            sb.append("<span style='margin-left:10px;font-weight:600'>机器初判</span>");
            for (Map<String, Object> c : checks) {
                sb.append("<span>项").append(c.get("checkNo")).append(' ')
                  .append(esc(str(c.get("checkName")))).append(' ')
                  .append(badge(str(c.get("verdict")))).append("</span>");
            }
            sb.append("<span class='small'>人工复核后生效</span>");
        }
        sb.append("</div></div>");
    }

    /** 提前结算模式的概览卡：PASS / SUSPECT / FAIL 圈数 + 人工待复核数。 */
    private void appendEarlyOverview(StringBuilder sb, List<Map<String, Object>> loops) {
        long pass = loops.stream().filter(l -> "PASS".equals(str(l.get("verdict")))).count();
        long suspect = loops.stream().filter(l -> "SUSPECT".equals(str(l.get("verdict")))).count();
        long fail = loops.stream().filter(l -> "FAIL".equals(str(l.get("verdict")))).count();
        long pending = loops.stream()
                .filter(l -> l.get("humanVerdict") == null || str(l.get("humanVerdict")).isBlank()).count();
        sb.append("<div class='card'><h2>概览</h2><div class='stats'>");
        statCard(sb, "PASS", String.valueOf(pass), "机器初判通过", null);
        statCard(sb, "SUSPECT", String.valueOf(suspect), "机器初判可疑", null);
        statCard(sb, "FAIL", String.valueOf(fail), "机器初判失败", null);
        statCard(sb, "人工待复核", String.valueOf(pending), "共 " + loops.size() + " 圈", null);
        sb.append("</div><div class='chips' style='margin-top:12px'>")
          .append("<span>圈统计：</span>")
          .append("<span class='badge b-pass'>PASS ").append(pass).append("</span>")
          .append("<span class='badge b-suspect'>SUSPECT ").append(suspect).append("</span>")
          .append("<span class='badge b-fail'>FAIL ").append(fail).append("</span>")
          .append("<span>共 ").append(loops.size()).append(" 圈</span>")
          .append("</div></div>");
    }

    /** 聚合判定表（机器初判 + 人工复核），与面板「聚合判定（机器初判 / 人工复核）」同构。 */
    /** checks 里有没有「疑似」判定（2026-10-12 #88：报告顶部的对照提示据此显示）。 */
    private static boolean hasSuspect(List<Map<String, Object>> checks) {
        if (checks == null) {
            return false;
        }
        for (Map<String, Object> c : checks) {
            if ("SUSPECT".equals(str(c.get("verdict")))) {
                return true;
            }
        }
        return false;
    }

    private void appendChecks(StringBuilder sb, List<Map<String, Object>> checks) {
        if (checks.isEmpty()) {
            return;
        }
        sb.append("<div class='card'><h2>聚合判定（机器初判 / 人工复核）</h2><div class='tablewrap'>")
          .append("<table><tr><th>项</th><th>检测项</th><th>初判</th><th>依据</th>")
          .append("<th>人工</th><th>人工备注</th></tr>");
        for (Map<String, Object> c : checks) {
            sb.append("<tr><td>").append(c.get("checkNo"))
              .append("</td><td>").append(esc(str(c.get("checkName"))))
              .append("</td><td>").append(badge(str(c.get("verdict"))))
              .append("</td><td>").append(esc(str(c.get("evidence"))))
              .append("</td><td>").append(badge(str(c.get("humanVerdict"))))
              .append("</td><td>").append(esc(str(c.get("humanNote"))))
              .append("</td></tr>");
        }
        sb.append("</table></div></div>");
    }

    // ---------- CSV ----------

    /** 圈粒度 CSV（带 BOM，Excel 打开中文不乱码）。 */
    public String csv(long runId, long gameId) {
        Map<String, Object> run = repo.getRun(runId);
        if (run == null) {
            throw new IllegalStateException("运行不存在: " + runId);
        }
        // 校验游戏存在（run 已在上面校验）
        if (repo.getGame(gameId) == null) {
            throw new IllegalStateException("游戏不存在: " + gameId);
        }
        // 取圈列表；根据 mode 决定导出哪套表头与行格式
        List<Map<String, Object>> loops = repo.loopsOf(runId, gameId);
        Map<Long,String> stepSummaries=stepSummaries(runId,gameId);
        // 与 HTML 同一口径：只有提前结算走证据列，正常开关与人机对局都走指标列
        boolean normal = !"EARLY_SETTLE".equals(String.valueOf(run.get("mode")));

        StringBuilder sb = new StringBuilder("﻿");
        // 正常模式表头：与 HTML 汇总表的列一一对应
        if (normal) {
            // 2026-09-28（v1.2 #14）：新增 6 列「关游戏后基线的 meminfo 分项」。
            // 用途：总 PSS 只能看出「涨了多少」，分项才能看出「是哪一类在涨」，用于定位泄漏方向。
            // 对局ID（v1.10.4 #86）：跟服务端对局记录对得上的标识，放在状态后面便于按它筛
            sb.append("圈号,状态,对局ID,无效原因,基线PSS(MB),局内PSS均值(MB),局内PSS峰值(MB),CPU均值(单核%),CPU峰值(单核%),CPU均值(整机%),")
              .append("基线ws线程,局内ws峰值线程,")
              .append("基线Native(MB),基线Java(MB),基线图形(MB),基线代码(MB),基线其它(MB),基线System(MB),")
              .append("人工判定,人工备注,清房状态,清房说明,证据缺失,接口步骤摘要,对局ID可靠度\n");
            // 正常模式行：逗号分隔；文本字段走 csvCell（自动加引号转义），数值走 cell（null → 空）
            for (Map<String, Object> l : loops) {
                sb.append(l.get("loopNo")).append(',')
                  .append(str(l.get("status"))).append(',')
                  // 对局ID（v1.10.4 #86）：与表头一一对应，缺了它整行列会错位
                  .append(csvCell(str(l.get("gameRoundId")))).append(',')
                  .append(csvCell(str(l.get("invalidReason")))).append(',')
                  .append(cell(kbToMb(l.get("baselinePssKb")))).append(',')
                  .append(cell(kbToMb(l.get("pssAvgKb")))).append(',')
                  .append(cell(kbToMb(l.get("pssPeakKb")))).append(',')
                  .append(cell(num(l.get("cpuAvg")))).append(',')
                  .append(cell(num(l.get("cpuPeak")))).append(',')
                  // 整机口径（2026-10-03，v1.5 #21）：历史 run 无此数据 → 空单元格
                  .append(cell(num(l.get("cpuAvgSys")))).append(',')
                  .append(str(l.get("baselineWsThreads"))).append(',')
                  .append(str(l.get("inGameWsThreads"))).append(',')
                  // 基线 meminfo 分项（2026-09-28，v1.2 #14）：历史 run 无此数据 → 空单元格
                  .append(cell(kbToMb(l.get("baselineNativeKb")))).append(',')
                  .append(cell(kbToMb(l.get("baselineJavaKb")))).append(',')
                  .append(cell(kbToMb(l.get("baselineGraphicsKb")))).append(',')
                  .append(cell(kbToMb(l.get("baselineCodeKb")))).append(',')
                  .append(cell(kbToMb(l.get("baselinePrivateOtherKb")))).append(',')
                  .append(cell(kbToMb(l.get("baselineSystemKb")))).append(',')
                  .append(csvCell(str(l.get("humanVerdict")))).append(',')
                  .append(csvCell(str(l.get("humanNote")))).append(',')
                  .append(csvCell(str(l.get("cleanupStatus")))).append(',').append(csvCell(PerfExportService.cleanupDescription(l))).append(',').append(str(l.get("evidenceMissing"))).append(',')
                  .append(csvCell(stepSummaries.getOrDefault(((Number)l.get("id")).longValue(),"未记录"))).append(',')
                  .append(csvCell(str(((Map<?,?>)l.getOrDefault("roundIdEvidence",Map.of("state","NOT_RECORDED"))).get("state")))).append('\n');
            }
        } else {
            // 提前结算模式表头：重点在判定结论与崩溃证据类字段
            sb.append("圈号,状态,无效原因,判定,实际延迟ms,崩溃命中数,能否回大厅,崩溃日志摘要,人工判定,人工备注,清房状态,清房说明,证据缺失,接口步骤摘要,对局ID可靠度\n");
            // 提前结算模式行：额外带出判定结论与崩溃证据（hitStr 为崩溃关键字命中的日志摘要）
            for (Map<String, Object> l : loops) {
                Map<String, Object> ev = evidence(l);
                Object hits = ev.get("crashHits");
                String hitStr = hits instanceof List<?> list
                        ? String.join(" | ", list.stream().map(String::valueOf).toList()) : "";
                Object recovered = ev.get("recoveredToLobby");
                sb.append(l.get("loopNo")).append(',')
                  .append(str(l.get("status"))).append(',')
                  .append(csvCell(str(l.get("invalidReason")))).append(',')
                  .append(str(l.get("verdict"))).append(',')
                  .append(str(ev.get("actualDelayMs"))).append(',')
                  .append(str(ev.get("crashHitCount"))).append(',')
                  .append(recovered == null ? "" : (Boolean.TRUE.equals(recovered) ? "是" : "否")).append(',')
                  .append(csvCell(hitStr)).append(',')
                  .append(csvCell(str(l.get("humanVerdict")))).append(',')
                  .append(csvCell(str(l.get("humanNote")))).append(',')
                  .append(csvCell(str(l.get("cleanupStatus")))).append(',').append(csvCell(PerfExportService.cleanupDescription(l))).append(',').append(str(l.get("evidenceMissing"))).append(',')
                  .append(csvCell(stepSummaries.getOrDefault(((Number)l.get("id")).longValue(),"未记录"))).append(',')
                  .append(csvCell(str(((Map<?,?>)l.getOrDefault("roundIdEvidence",Map.of("state","NOT_RECORDED"))).get("state")))).append('\n');
            }
        }
        return PerfReportPrivacy.redact(sb.toString(),repo.plan(runId));
    }

    private Map<Long,String> stepSummaries(long runId,long gameId) {
        var summaries=new LinkedHashMap<Long,StringBuilder>();
        if(stepEvidence!=null)stepEvidence.forEachStep(runId,gameId,row -> {
            if(row.get("loopId") instanceof Number number) {
                var text=summaries.computeIfAbsent(number.longValue(),id->new StringBuilder());if(!text.isEmpty())text.append("；");
                text.append(str(row.get("step"))).append(':').append(str(row.get("verdict"))).append(" HTTP=").append(str(row.get("httpStatus"))).append(' ').append(str(row.get("elapsedMs"))).append("ms");
                if(Boolean.TRUE.equals(row.get("truncated")))text.append(" [截断]");if(Boolean.TRUE.equals(row.get("evidenceUnreadable")))text.append(" [证据损坏]");
            }
        });
        var result=new LinkedHashMap<Long,String>();summaries.forEach((id,value)->result.put(id,value.toString()));return result;
    }
    private String runContextCard(long runId) {
        var out=new StringBuilder("<div class='card'><h2>冻结可比条件与恢复时间线</h2>");
        try{out.append("<p>").append(esc(String.valueOf(repo.facts().summary(runId)))).append("</p>");var timeline=repo.facts().timeline(runId);out.append("<p>类型化事件按存储序号排列；没有记录不代表未发生，停止不会自动续跑。超1000条仅展示前1000条。</p><pre>").append(esc(new com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(timeline))).append("</pre>");}
        catch(Exception unavailable){out.append("<p>条件或时间线无法读取，不能推定为正常</p>");}
        return out.append("</div>").toString();
    }
    private String qualityCard(List<Map<String,Object>> loops){var out=new StringBuilder("<div class='card'><h2>采样条件与完整性</h2><p>温度、供电、thermal只读记录，未知不视为正常；单调槽位调度不补发积压。进程变化圈不参与连续均值。</p><table><tr><th>圈</th><th>采样依据</th></tr>");for(var loop:loops){var q=repo.loopQuality(((Number)loop.get("id")).longValue());out.append("<tr><td>").append(loop.get("loopNo")).append("</td><td>").append(esc(PerfQualityView.summary(q))).append("</td></tr>");}return out.append("</table></div>").toString();}

    private String stepEvidenceCard(long runId,long gameId,List<Map<String,Object>> loops,Map<Long,String> stepSummaries) {
        var out=new StringBuilder("<div class='card'><h2>接口步骤与清房证据</h2><p>本报告仅含步骤结果摘要，实际报文与失败详情保留在本机；旧记录显示未记录。清房失败不改采样结论。</p><table><thead><tr><th>圈号</th><th>对局ID可靠度</th><th>清房</th><th>证据缺失</th><th>接口步骤</th></tr></thead><tbody>");
        for(var loop:loops) {
            var round=(Map<?,?>)loop.getOrDefault("roundIdEvidence",Map.of("state","NOT_RECORDED"));
            out.append("<tr><td>").append(loop.get("loopNo")).append("</td><td>").append(esc(str(round.get("state")))).append("</td><td>").append(esc(str(loop.get("cleanupStatus")))).append(" · ").append(esc(PerfExportService.cleanupDescription(loop))).append("</td><td>").append(esc(str(loop.get("evidenceMissing")))).append("</td><td>").append(esc(stepSummaries.getOrDefault(((Number)loop.get("id")).longValue(),"未记录"))).append("</td></tr>");
        }
        return out.append("</tbody></table></div>").toString();
    }

    // ---------- SVG 折线图 ----------

    /**
     * 折线图序列描述：名称（图例用）、颜色（SVG stroke 色）、数据值（null 表示断点）、
     * rightAxis 标记该序列用右轴刻度（双轴图中 PSS 用左轴、CPU 用右轴）。
     */
    private record SeriesSpec(String name, String color, List<Double> values, boolean rightAxis) {
    }

    /**
     * 简易双轴折线图（纯 SVG，无外部依赖）。values 中 null = 无数据（折线断开）。
     * invalidIdx 中的下标在图内标红 ×。左轴刻度在左、右轴刻度在右。
     */
    private String lineChart(String title, List<String> xLabels, List<SeriesSpec> specs, Set<Integer> invalidIdx) {
        int n = xLabels.size();
        // 无数据直接返回空串（调用方不会渲染空的图表容器内容）
        if (n == 0) {
            return "";
        }
        // 只要有一个序列声明用右轴，就保留右轴空间；否则右轴留白收窄，图更舒展
        boolean hasRight = specs.stream().anyMatch(SeriesSpec::rightAxis);
        // 画布尺寸与边距：W×H 总尺寸，L/R/T/B 为左/右/上/下留白，pw/ph 为实际绘图区宽高
        int W = 760, H = 280, L = 64, R = hasRight ? 64 : 18, T = 40, B = 42;
        int pw = W - L - R, ph = H - T - B;
        // 分别计算左轴（false）与右轴（true）序列的数值范围 [min,max]，用于坐标映射；该轴无数据为 null
        double[] sl = scaleOf(specs, false);
        double[] sr = hasRight ? scaleOf(specs, true) : null;

        // 预分配 4KB：一张 SVG 通常 1~3KB，避免小图频繁扩容
        StringBuilder sb = new StringBuilder(4096);
        // 用 viewBox 等比缩放：固定逻辑尺寸，实际按容器宽度 100% 自适应，不糊图
        sb.append("<svg viewBox='0 0 ").append(W).append(' ').append(H)
          .append("' xmlns='http://www.w3.org/2000/svg' style='width:100%;max-width:").append(W)
          .append("px;background:#fff;border:1px solid #eef2f7;border-radius:10px'>");
        // 标题：固定在绘图区左上角（x=L 起、y=20）
        svgText(sb, L, 20, title, "font-size:13px;font-weight:600;fill:#222", null);
        // 图例（右上，横排）：每条图例 = 一段彩色短线 + 序列名；lx 是当前图例的起始 x 坐标
        // 图例（右上，横排）
        int lx = W - R - legendWidth(specs);
        for (SeriesSpec sp : specs) {
            // 彩色短线：18px 宽的实线，颜色与数据线一致
            sb.append("<line x1='").append(lx).append("' y1='15' x2='").append(lx + 18)
              .append("' y2='15' stroke='").append(sp.color()).append("' stroke-width='2'/>");
            // 序列名文本（11px 灰色）
            svgText(sb, lx + 22, 19, sp.name(), "font-size:11px;fill:#444", null);
            // 推进 x：短线(18) + 间距(4) + 文字宽度（按字符数估算 12px/字）+ 间距(18)
            lx += 22 + sp.name().length() * 12 + 18;
        }
        // 横向网格线 + 左右轴刻度（min/mid/max）：f=0 → 底部（数值最小），f=1 → 顶部（数值最大）
        // 横向网格线 + 左右轴刻度（min/mid/max）
        for (int g = 0; g <= 2; g++) {
            double f = g / 2.0;
            // y 是网格线的像素纵坐标（绘图区顶部为 T、底部为 T+ph）
            double y = T + ph - f * ph;
            sb.append("<line x1='").append(L).append("' y1='").append(f1(y)).append("' x2='").append(L + pw)
              .append("' y2='").append(f1(y)).append("' stroke='").append(g == 0 ? "#999" : "#e5e5e5").append("'/>");
            // 左轴刻度文字（右对齐，贴在绘图区左边界左侧）；值 = min + f*(max-min)
            if (sl != null) {
                svgText(sb, L - 6, y + 4, fmt(sl[0] + f * (sl[1] - sl[0])), "font-size:10px;fill:#666", "end");
            }
            // 右轴刻度文字（左对齐，贴在绘图区右边界右侧）
            if (sr != null) {
                svgText(sb, L + pw + 6, y + 4, fmt(sr[0] + f * (sr[1] - sr[0])), "font-size:10px;fill:#666", "start");
            }
        }
        // x 轴刻度（最多 12 个）：按步长抽稀，避免圈数多时标签挤成一团
        // x 轴刻度（最多 12 个）
        int step = Math.max(1, (int) Math.ceil(n / 12.0));
        for (int i = 0; i < n; i += step) {
            // 刻度文字居中放在对应 x 位置下方（y = 绘图区底部 + 16px）
            svgText(sb, (int) Math.round(xAt(i, n, L, pw)), T + ph + 16, xLabels.get(i),
                    "font-size:10px;fill:#666", "middle");
        }
        // 无效圈 × 标记（图内底部）：红色 × 提示该圈数据无效，肉眼可直接看出哪几圈不可信
        // 无效圈 × 标记（图内底部）
        for (int i : invalidIdx) {
            // 防御性边界检查（下标理论上合法，但保护一下避免越界画图）
            if (i >= 0 && i < n) {
                svgText(sb, (int) Math.round(xAt(i, n, L, pw)), T + ph - 6, "×",
                        "font-size:14px;fill:#cf222e", "middle");
            }
        }
        // 数据线 + 数据点：逐序列绘制；遇到 null 值折线断开（核心：分段连线）
        // 数据线 + 数据点
        for (SeriesSpec sp : specs) {
            // 选该序列对应的坐标刻度：右轴序列用 sr，否则用 sl
            double[] sc = sp.rightAxis() ? sr : sl;
            // 该轴没有任何数据（理论上不会走到，防御）
            if (sc == null) {
                continue;
            }
            // seg 缓存当前连续段的坐标串（如 "12.0,34.5 13.0,35.2 ..."）；遇 null 就把攒好的段画出来
            StringBuilder seg = null;
            int segCount = 0;
            // 多循环一次（i == n 时 v 必为 null），保证最后一段也能被 flush 出来
            for (int i = 0; i <= n; i++) {
                Double v = i < n ? sp.values().get(i) : null;
                if (v != null) {
                    // 数值 → 像素坐标：x 按索引均分，y 按 (v-min)/(max-min) 比例映射到绘图区
                    double x = xAt(i, n, L, pw);
                    double y = T + ph - (v - sc[0]) / (sc[1] - sc[0]) * ph;
                    // 每个数据点画一个小圆点（半径 2.2，与数据线同色）
                    sb.append("<circle cx='").append(f1(x)).append("' cy='").append(f1(y))
                      .append("' r='2.2' fill='").append(sp.color()).append("'/>");
                    // 把点追加进当前段；段不存在则新开一段
                    if (seg == null) {
                        seg = new StringBuilder();
                        segCount = 0;
                    }
                    seg.append(f1(x)).append(',').append(f1(y)).append(' ');
                    segCount++;
                } else if (seg != null) {
                    // 遇到 null（或哨兵 i==n）：结束当前段；≥2 个点才画线（单点画线无意义）
                    if (segCount >= 2) {
                        sb.append("<polyline points='").append(seg).append("' fill='none' stroke='")
                          .append(sp.color()).append("' stroke-width='1.6'/>");
                    }
                    // 清空段，等待下一段数据
                    seg = null;
                }
            }
        }
        // 闭合 SVG 并返回
        sb.append("</svg>");
        return sb.toString();
    }

    /** 某轴（左/右）上全部序列的 min/max，带 8% 边距；无数据返回 null。 */
    private static double[] scaleOf(List<SeriesSpec> specs, boolean right) {
        // 用极值初始化，方便后续 Math.min/max 比较
        double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
        boolean any = false;   // 是否至少有一个有效值
        for (SeriesSpec sp : specs) {
            // 只统计指定轴（left/right）上的序列，两轴独立算刻度
            if (sp.rightAxis() != right) {
                continue;
            }
            for (Double v : sp.values()) {
                if (v == null) {
                    continue;   // null 是断点，不参与刻度计算
                }
                any = true;
                min = Math.min(min, v);
                max = Math.max(max, v);
            }
        }
        // 该轴上没有任何数据点：无刻度可用，返回 null 让调用方跳过该轴
        if (!any) {
            return null;
        }
        // 上下各留 8% 边距，避免曲线顶到图框边缘
        double pad = (max - min) * 0.08;
        // 全等值（max==min）时 8% 为 0，退化为"按数值量级的 10% + 0.5"保底，保证刻度区间非零
        if (pad == 0) {
            pad = Math.abs(max) * 0.1 + 0.5;
        }
        return new double[]{min - pad, max + pad};
    }

    /** 第 i 个数据点的像素 x 坐标：n>1 时在绘图区内均分（首点 L、末点 L+pw）；n==1 时水平居中。 */
    private static double xAt(int i, int n, int L, int pw) {
        return L + (n > 1 ? i * (double) pw / (n - 1) : pw / 2.0);
    }

    /** 估算整排图例的总宽度（字符数×12px + 每项固定 40px），用于从右往左排布图例。 */
    private static int legendWidth(List<SeriesSpec> specs) {
        int w = 0;
        for (SeriesSpec sp : specs) {
            w += sp.name().length() * 12 + 40;
        }
        return w;
    }

    /** SVG 文本元素小工具：anchor 非空时输出 text-anchor（start/middle/end），文本统一转义防 XSS。 */
    private static void svgText(StringBuilder sb, int x, double y, String s, String style, String anchor) {
        sb.append("<text x='").append(x).append("' y='").append(f1(y)).append("' style='").append(style).append("'");
        if (anchor != null) {
            sb.append(" text-anchor='").append(anchor).append("'");
        }
        sb.append('>').append(esc(s)).append("</text>");
    }

    // ---------- 小工具 ----------

    /** 解析异常时序模式圈的 judge_json 证据。 */
    private Map<String, Object> evidence(Map<String, Object> loop) {
        // judge_json 为 null 或空白：无证据，返回空 Map（调用方 get 到 null 也能处理）
        Object j = loop.get("judgeJson");
        if (j == null || j.toString().isBlank()) {
            return Map.of();
        }
        try {
            // 反序列化为 Map<String,Object>（TypeReference 保留泛型信息）
            return json.readValue(j.toString(), new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            // 历史数据可能缺字段/格式漂移，解析失败就按"无证据"兜底，不让报告整体报错
            return Map.of();
        }
    }

    /** 任意数值类型 → Double；非数字（含 null）返回 null。 */
    private static Double num(Object o) {
        return o instanceof Number nb ? nb.doubleValue() : null;
    }

    /** KB → MB（除以 1024）；非数字返回 null。 */
    private static Double kbToMb(Object kb) {
        return kb instanceof Number nb ? nb.doubleValue() / 1024.0 : null;
    }

    /** 任意对象转字符串，null 安全（返回空串）。 */
    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    /** 表格单元格取值：null → 空串，否则格式化数值（整数不带小数）。 */
    private static String cell(Double v) {
        return v == null ? "" : fmt(v);
    }

    /**
     * 数值格式化：整数（误差 <1e-9 且绝对值 <1e12）直接输出无小数；否则保留 1 位小数。
     * 效果：12.0 → "12"（清爽），12.34 → "12.3"。
     */
    private static String fmt(double v) {
        if (Math.abs(v - Math.rint(v)) < 1e-9 && Math.abs(v) < 1e12) {
            return String.valueOf((long) Math.rint(v));
        }
        return String.format("%.1f", v);
    }

    /** 固定 1 位小数的字符串（SVG 坐标用，压缩体积）。 */
    private static String f1(double v) {
        return String.format("%.1f", v);
    }

    /** 判定结论 → CSS class：PASS 绿 / FAIL 红 / SUSPECT 橙 / N_A 灰。 */
    private static String verdictCls(String verdict) {
        return switch (verdict) {
            case "PASS" -> "pass";
            case "FAIL" -> "fail";
            case "N_A" -> "na";
            default -> "suspect";
        };
    }

    /** HTML 转义：& < > 三字符替换，防注入（游戏名/日志等外部文本进 HTML 前必须过）。 */
    private static String esc(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /**
     * CSV 字段转义：含逗号/引号/换行时用双引号包裹，内部引号翻倍（"" 是 CSV 标准转义）。
     * 否则直接原样输出。
     */
    private static String csvCell(String s) {
        if (s == null) {
            return "";
        }
        if (!s.isEmpty() && "=+-@".indexOf(s.charAt(0)) >= 0) s="\'"+s;
        if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }
}
