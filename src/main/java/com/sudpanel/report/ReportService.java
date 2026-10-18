package com.sudpanel.report;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.sudpanel.runner.RunService;

/**
 * 生成 HTML / CSV 报告（功能测试结果归档）。
 *
 * <p>职责：把一次 runId 的执行结果渲染成两种交付格式：
 *   html()：内嵌样式的完整 HTML 报告，浏览器直接打开即可查看；
 *   csv()：Excel/WPS 可直接打开的逗号分隔表格（比 HTML 多一列「耗时ms」）。
 *
 * <p><b>数据来源（2026-09-23 变更）</b>：一律走 {@link RunService#runMeta(long)} +
 * {@link RunService#storedResults(long)} 的<b>数据库直查</b>，不再依赖 RunService 内存态。
 * 原因：原实现取 {@code summary(runId)}（内存里的 runs map），进程重启后内存态清空，
 * 历史 run 的报告与导出全部报「运行不存在」，而 test_run / test_result 表的数据其实是完整的。
 * 改为 DB 直查后，历史报告永久可用。
 */
@Service
public class ReportService {

    // RunService：提供 run 元信息与结果明细的 DB 直查（见 com.sudpanel.runner 包）
    private final RunService runService;

    // 构造器注入：Spring 自动传入 RunService 单例；字段声明为 final，赋值后不可再变
    public ReportService(RunService runService) {
        this.runService = runService;
    }

    /** 取出某次运行的结果明细列表（DB 直查）。 */
    public List<RunService.StoredResult> results(long runId) {
        return runService.storedResults(runId);
    }

    /**
     * 结果字符串 → HTML 配色 class。
     * 刻意用「大写精确匹配 + default 兜底」而不是枚举 valueOf：
     * DB 里若存在历史脏值（NULL / 小写 / 未知值），报告仍然能渲染，不抛异常。
     */
    private static String resultClass(String result) {
        String r = result == null ? "" : result.toUpperCase();
        return switch (r) {
            case "PASS" -> "pass";
            case "FAIL" -> "fail";
            default -> "skip";
        };
    }

    /**
     * 生成一份完整的 HTML 报告字符串。
     * 结构：内嵌 CSS 的页面头 + 元信息行（runId/方向/设备/状态/通过/失败/跳过统计）+ 逐测试点明细表格。
     *
     * @param runId 运行 ID
     * @return 完整 HTML 文档（可直接保存为 .html 文件用浏览器打开）
     * @throws org.springframework.dao.EmptyResultDataAccessException run 不存在时（由 Controller 转 404）
     */
    public String html(long runId) {
        // 元信息与结果明细都从数据库读（不依赖内存态）
        Map<String, Object> meta = runService.runMeta(runId);
        List<RunService.StoredResult> rs = results(runId);
        // 用流统计三类结果数量，展示在报告头部（大小写不敏感，容忍历史脏值）
        long pass = rs.stream().filter(r -> "PASS".equalsIgnoreCase(r.result())).count();
        long fail = rs.stream().filter(r -> "FAIL".equalsIgnoreCase(r.result())).count();
        long skip = rs.stream().filter(r -> "SKIP".equalsIgnoreCase(r.result())).count();

        // StringBuilder 拼接 HTML：相比字符串直接相加，避免产生大量中间临时字符串，性能更好
        StringBuilder sb = new StringBuilder();
        // ---- 页面头：DOCTYPE、中文字符集、内嵌 CSS 样式 ----
        sb.append("<!DOCTYPE html><html lang='zh'><head><meta charset='utf-8'>")
          // 标题与内嵌 CSS 开始
          .append("<title>sud-panel 报告</title><style>")
          // 正文基础样式：系统字体、四周留白、深灰文字
          .append("body{font-family:system-ui,sans-serif;margin:24px;color:#222}")
          // 大标题字号 + 元信息行样式（灰、小字、下边距）
          .append("h1{font-size:20px}.meta{color:#666;font-size:13px;margin-bottom:16px}")
          // 表格：边框合并、撑满宽度、小字号便于展示更多列
          .append("table{border-collapse:collapse;width:100%;font-size:13px}")
          // 单元格边框内边距；表头浅灰底
          .append("th,td{border:1px solid #ddd;padding:6px 8px;text-align:left;vertical-align:top}")
          // 结果颜色：通过绿 / 失败红 / 跳过棕黄
          .append("th{background:#f5f5f5}.pass{color:#1a7f37}.fail{color:#cf222e}.skip{color:#9a6700}")
          // 命中日志用 pre 等宽展示：自动换行、最大高 200px、超出内部滚动
          .append("pre{margin:0;white-space:pre-wrap;word-break:break-all;font-size:12px;max-height:200px;overflow:auto}")
          .append("</style></head><body>")
          // ---- 报告主体：标题 + 元信息行 ----
          .append("<h1>sud-report-panel 执行报告</h1>")
          .append("<div class='meta'>runId=").append(runId)
          .append(" | 方向=").append(meta.get("direction"))
          .append(" | 设备=").append(meta.get("device"))
          .append(" | 状态=").append(meta.get("status"))
          .append(" | 通过 ").append(pass).append(" / 失败 ").append(fail)
          .append(" / 跳过 ").append(skip).append("</div>")
          // ---- 明细表格：表头 6 列 ----
          .append("<table><tr><th>#</th><th>测试点</th><th>state</th><th>结果</th><th>结果备注</th><th>命中日志</th></tr>");
        // 逐行输出每个测试点（序号从 1 递增）
        int i = 1;
        for (RunService.StoredResult r : rs) {
            // 所有用户可控文本（名称/状态/备注/日志）一律经 esc() 转义，
            // 防止特殊字符破坏页面结构（如名称里含 <script> 或 <table>）
            sb.append("<tr><td>").append(i++).append("</td><td>").append(esc(r.pointName()))
              .append("</td><td>").append(esc(r.stateName()))
              .append("</td><td class='").append(resultClass(r.result())).append("'>")
              .append(esc(r.result())).append("</td><td>")
              .append(esc(r.note() == null ? "" : r.note())).append("</td><td><pre>")
              .append(esc(r.logSnippet() == null ? "" : r.logSnippet())).append("</pre></td></tr>");
        }
        sb.append("</table></body></html>");
        // 输出完整 HTML 字符串
        return sb.toString();
    }

    /**
     * 生成 CSV 报告：首行为表头（比 HTML 多「耗时ms」列），之后每行一个测试点。
     *
     * @param runId 运行 ID
     * @return CSV 文本（逗号分隔，行以 \n 结尾，UTF-8 编码）
     */
    public String csv(long runId) {
        List<RunService.StoredResult> rs = results(runId);
        // 表头固定 7 列
        StringBuilder sb = new StringBuilder("序号,测试点,state,结果,结果备注,命中日志,耗时ms\n");
        int i = 1;
        for (RunService.StoredResult r : rs) {
            // 文本字段经 csvCell() 处理：含逗号/引号/换行时自动加引号并转义，保证 Excel 打开不串列
            sb.append(i++).append(',').append(csvCell(r.pointName())).append(',')
              .append(csvCell(r.stateName())).append(',')
              .append(csvCell(r.result())).append(',').append(csvCell(r.note())).append(',')
              .append(csvCell(r.logSnippet())).append(',')
              // 耗时可能为 null（历史数据/人工判定点记 0 或不记）→ 输出空列而不是 "null"
              .append(r.elapsedMs() == null ? "" : r.elapsedMs()).append('\n');
        }
        return sb.toString();
    }

    /** HTML 转义：把 & < > 三个特殊字符替换为 HTML 实体，防止内容破坏页面结构与注入脚本。 */
    private static String esc(String s) {
        // null 统一转成空串
        if (s == null) return "";
        // 注意替换顺序：& 必须最先替换——若先替换 <，则 &lt; 里的 & 又会被二次转成 &amp;lt;
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** CSV 单元格转义（RFC 4180）：含逗号/双引号/换行的字段用双引号包裹，内部双引号翻倍。 */
    private static String csvCell(String s) {
        if (s == null) return "";
        // 仅当字段含逗号/引号/换行时才需要加引号包裹；普通字段保持原样，文件更简洁
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            // 引号翻倍是 CSV 标准转义："" 表示一个普通引号
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }
}
