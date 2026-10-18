package com.sudpanel.api;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;

/**
 * 响应体断言（2026-10-05 新增，需求 #51）。
 *
 * <p>在这之前只能断状态码与业务返回码两件事，响应体里其他任何字段都没法表达。
 * 本类把断言扩展成「路径 + 判断条件 + 期望值」的规则列表：
 * <pre>
 *   {"path":"data.roomId",        "op":"type",   "expect":"integer"}
 *   {"path":"data.players[*].ready","op":"equals","expect":"true"}
 *   {"path":"data.players",       "op":"length", "expect":"3"}
 * </pre>
 *
 * <p>刻意做成<b>纯函数 + 静态</b>（不碰数据库、不读配置），因为断言是最需要被单测盯住的一块：
 * 它判错的方向有两个 —— 该失败的放过（假绿灯）、不该失败的拦下（假红灯），
 * 后者更气人，两个方向都得有测试压着。
 *
 * <h2>几个必须说清楚的语义</h2>
 * <ol>
 *   <li><b>判定在「原样的响应体」上做。</b>调用方必须传截断前的那一份。
 *       {@link ApiExec#truncate} 是入库时才截的，若拿截断后的文本判断，长响应会假失败。</li>
 *   <li><b>数字按数值比。</b>{@code 1.0} 与 {@code 1} 视为相等 —— 文本比较会把它们判成不等，
 *       那是会骗人的假失败。但 {@code integer} 要求原文不带小数点，{@code 1.0} 不算整数。</li>
 *   <li><b>非 JSON 的响应体按「根是一段纯文本」处理。</b>有些接口就回一个 {@code ok} 或一页 HTML：
 *       根路径上的「等于/包含/长度」仍然可用，但按路径下钻会明确报「响应体不是 JSON」。</li>
 *   <li><b>路径不存在 与 值为 null 分开报。</b>排查时这是两件事：「键没返回」和「返回了 null」
 *       指向的问题完全不同，糊成一句话会把人带偏。</li>
 *   <li><b>{@code [*]} 遇到空数组一律判失败</b>，不走「空集恒真」那套逻辑。
 *       空列表静默变绿灯，正是最容易骗人的那种「通过」。</li>
 * </ol>
 */
public final class ApiBodyAssert {

    private ApiBodyAssert() {
    }

    private static final ObjectMapper JSON = new ObjectMapper().enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    /** 单条断言规则。{@code path} 留空表示断整个响应体。 */
    public record Rule(String path, String op, String expect) {
    }

    /** 一条没过的断言。 */
    public record Failure(String path, String message) {
        /** 渲染成「路径 说明」；根路径只有说明。 */
        public String render() {
            if (path == null || path.isBlank()) {
                return message;
            }
            return path + " " + message;
        }
    }

    /**
     * 一条规则的判定结果（2026-10-05，需求 #58）。
     *
     * <p>{@code failure} 为 null 表示这条**通过**了。之所以要把「通过」也带出来：
     * 在此之前判定结果只有失败那半边会落库（进 {@code reason}），于是「这条用例配了断言、
     * 而且都过了」这件事在界面上完全看不见 —— 一个绿灯到底是因为断言过了，
     * 还是因为压根没配断言，从记录上分不出来。
     */
    public record Judged(Rule rule, Failure failure, String actual, String source, boolean actualTruncated) {
        public Judged(Rule rule, Failure failure) { this(rule, failure, null, "BODY", false); }

        public boolean ok() {
            return failure == null;
        }
    }

    /** 单条用例的断言条数上限。断言本身很便宜，防的是有人把一整页规则粘进来。 */
    public static final int MAX_RULES = 50;

    /** 报错里回显实际值时的截断长度，避免把一个几百 KB 的对象整段塞进原因里。 */
    private static final int MAX_VALUE_CHARS = 120;

    /**
     * 配置整段解析不出来时用的哨兵条件，让它明确失败而不是被当成「没设断言」悄悄放过。
     *
     * <p>值要能穿过 {@link #norm} 的归一化（它会吃掉下划线、短横、点），所以这里写的是
     * 归一化之后的样子。
     */
    private static final String OP_BROKEN = "broken";

    // ==================== 解析 ====================

    /**
     * 把库里存的 JSON 文本解析成规则列表。
     *
     * <p>不抛异常：存量脏数据、外部改坏的内容都不该让执行直接崩掉。
     * 但也没打算把它们「当成空」放过 —— 整段解析不了时返回一条哨兵规则，
     * 让它在判定阶段明确报「配置无法解析」。静默放过的断言等于没有断言。
     *
     * <p>整行都空（路径、条件、期望值全空）的规则会被丢掉：那是界面上点了「添加断言」
     * 还没填的草稿行，不该变成一条约束。
     *
     * @param assertsJson 库里存的 JSON 文本；null/空表示没有断言
     * @return 规则列表；没有断言时返回空列表
     */
    public static List<Rule> parse(String assertsJson) {
        if (assertsJson == null || assertsJson.isBlank()) {
            return List.of();
        }
        JsonNode root;
        try {
            root = JSON.readTree(assertsJson.trim());
        } catch (Exception e) {
            return List.of(new Rule(null, OP_BROKEN, ""));
        }
        if (root == null || !root.isArray() || root.size() > MAX_RULES) {
            return List.of(new Rule(null, OP_BROKEN, ""));
        }
        List<Rule> out = new ArrayList<>();
        for (JsonNode n : root) {
            if (!n.isObject()) return List.of(new Rule(null, OP_BROKEN, ""));
            String path = field(n, "path");
            String op = field(n, "op");
            String expect = field(n, "expect");
            if (path.isBlank() && op.isBlank() && expect.isBlank()) {
                continue;
            }
            out.add(new Rule(path, op, expect));
        }
        return out;
    }

    /** 保存和预览先检查配置；存量损坏配置执行时仍由哨兵规则明确失败。 */
    public static void validateRules(String text) {
        for (Rule rule : parse(text)) {
            String op = norm(rule.op());
            if (OP_BROKEN.equals(op)) throw new IllegalArgumentException("断言必须是最多 50 条对象规则的有效 JSON 数组");
            if (!isKnown(op)) throw new IllegalArgumentException("无法识别的断言条件：" + rule.op());
            String exp = rule.expect() == null ? "" : rule.expect().trim();
            if ("schema".equals(op)) {
                if (rule.path() != null && (rule.path().startsWith("header:") || rule.path().equals("$elapsedMs")))
                    throw new IllegalArgumentException("Schema 仅用于响应 JSON 正文");
                ApiJsonSchema.validateConfiguration(exp); continue;
            }
            if ("regex".equals(op) && exp.length() > SafeRegex.MAX_PATTERN_CHARS)
                throw new IllegalArgumentException("正则表达式最多 " + SafeRegex.MAX_PATTERN_CHARS + " 字符");
            // 模板在发送时按本行/本轮值再次校验，保存阶段只检查规则结构和条件名。
            if (exp.contains("{{")) continue;
            if ("regex".equals(op)) SafeRegex.validate(exp);
            if (java.util.Set.of("gt", "gte", "lt", "lte").contains(op) && parseNum(exp) == null)
                throw new IllegalArgumentException("数值断言的期望值必须是数字");
            if ("length".equals(op) && !exp.matches("[0-9]+")) throw new IllegalArgumentException("长度期望值必须是非负整数");
            if ("type".equals(op) && !java.util.Set.of("string", "number", "integer", "boolean", "object", "array", "null").contains(exp))
                throw new IllegalArgumentException("断言类型无效：" + exp);
        }
    }

    private static String field(JsonNode n, String name) {
        JsonNode v = n == null ? null : n.get(name);
        return v == null || v.isNull() ? "" : v.asText("");
    }

    // ==================== 判定 ====================

    /**
     * 逐条判定，返回**每一条**的结果（通过的也在里面）。需求 #58 的出口。
     *
     * <p>与 {@link #evaluate} 是同一套判定：后者就架在本方法上，只挑出没过的那几条。
     * 这样写的目的是**不让两个出口漂移** —— 曾经很容易发生的错法是各写一遍，
     * 于是「判定原因里说过了、展示那一栏说没过」这种自相矛盾，谁也没法一眼看出来。
     *
     * @param body  响应体原文（<b>截断前</b>的那一份）
     * @param rules 规则列表
     * @return 每条规则及其结果；规则为空时返回空列表
     */
    public static List<Judged> judgeAll(String body, List<Rule> rules) {
        return judgeAll(body, rules, java.util.Map.of(), null);
    }

    /** header:名称 和 $elapsedMs 与正文规则共用相同的条件与逐条结果。 */
    public static List<Judged> judgeAll(String body, List<Rule> rules,
            java.util.Map<String, java.util.List<String>> headers, Long elapsedMs) {
        return judgeAll(body,rules,headers,elapsedMs,java.util.Map.of(),false);
    }
    public static List<Judged> judgeAll(String body,List<Rule> rules,java.util.Map<String,java.util.List<String>> headers,Long elapsedMs,java.util.Map<String,Object> response,boolean binary) {
        long regexDeadline = SafeRegex.judgementDeadline();
        List<Judged> out = new ArrayList<>();
        if (rules == null || rules.isEmpty()) {
            return out;
        }
        String raw = body == null ? "" : body;
        boolean overLimit = ApiStructuredBody.overLimit(raw);
        boolean blank = !overLimit && raw.isBlank();
        JsonNode root = null;
        boolean isJson = false;
        boolean needsBody = rules.stream().anyMatch(r -> r.path()==null || !(r.path().startsWith("$response.") || r.path().startsWith("header:") || "$elapsedMs".equals(r.path())));
        if (needsBody && !binary && !overLimit && !blank) {
            try {
                JsonNode n = JSON.readTree(raw.trim());
                if (n != null) {
                    root = n;
                    isJson = true;
                }
            } catch (Exception ignored) {
                // 不是 JSON —— 不在这里报错，交给下面按「根是纯文本」处理
            }
        }
        for (Rule r : rules) {
            if(r.path()!=null&&r.path().startsWith("$response.")){
                String key=r.path().substring(10);Object v=response.get(key);
                if(!java.util.Set.of("bytes","sha256","contentType","classification").contains(key)||v==null)out.add(withActual(r,new Failure(r.path(),"没有此响应文件属性"),null,"FILE"));
                else {JsonNode node=JSON.valueToTree(v);out.add(withActual(r,apply(r,norm(r.op()),List.of(node),false,regexDeadline),node.toString(),"FILE"));}
            } else if (r.path() != null && r.path().startsWith("header:")) {
                String name = r.path().substring(7).trim();
                var value = headers.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(name))
                        .map(e -> String.join(", ", e.getValue())).findFirst();
                Failure failure = value.isEmpty()
                        ? ("notexists".equals(norm(r.op())) ? null : new Failure(r.path(), "响应头不存在：" + name))
                        : apply(r, norm(r.op()), List.of(TextNode.valueOf(value.get())), false, regexDeadline);
                out.add(withActual(r, failure, value.map(v -> TextNode.valueOf(v).toString()).orElse(null), "HEADER"));
            } else if ("$elapsedMs".equals(r.path())) {
                out.add(withActual(r, elapsedMs == null ? new Failure(r.path(), "没有响应耗时")
                        : apply(r, norm(r.op()), List.of(JSON.getNodeFactory().numberNode(elapsedMs)), false, regexDeadline),
                        elapsedMs == null ? null : elapsedMs.toString(), "ELAPSED"));
            } else if(binary) {
                out.add(withActual(r,new Failure(r.path(),"二进制响应不能作为文本/JSON断言；请使用$response.bytes/$response.sha256/header:Content-Type"),null,"BODY"));
            } else if(overLimit) {
                out.add(withActual(r,new Failure(r.path(),ApiStructuredBody.MESSAGE),null,"BODY"));
            } else {
                String actual = null;
                try {
                    if(!isJson && r.path()!=null && !r.path().isBlank())throw new IllegalArgumentException("响应体不是 JSON");
                    List<JsonNode> values = r.path() == null || r.path().isBlank()
                            ? List.of(isJson ? root : TextNode.valueOf(raw)) : readValues(root, r.path());
                    actual = values.size() == 1 ? values.getFirst().toString() : JSON.valueToTree(values).toString();
                } catch (Exception ignored) { /* 缺失路径与实际 null 值不同 */ }
                out.add(withActual(r, judge(raw, blank, root, isJson, r, regexDeadline), actual, "BODY"));
            }
        }
        return out;
    }

    private static Judged withActual(Rule rule, Failure failure, String actual, String source) {
        boolean truncated = actual != null && actual.length() > 4096;
        return new Judged(rule, failure, truncated ? actual.substring(0, 4096) + "…" : actual, source, truncated);
    }

    /**
     * 把判定结果序列化成落库用的 JSON 文本。
     *
     * <p>格式在这里定义一次，写入（{@code ApiRunner}）与读取（前端）都以它为准。
     * 字段名刻意短：这一列每条执行都要写，而内容本身没什么可压缩的余地。
     *
     * <pre>[{"path":"…","op":"equals","expect":"3333","ok":true,"why":null}]</pre>
     *
     * <p>{@code why} 只放那条的说明、<b>不带路径</b> —— 路径已经是同一行的独立字段，
     * 塞两遍在界面上会显得像两个不同的东西。
     */
    public static String toJson(List<Judged> judged) {
        var arr = JSON.createArrayNode();
        for (Judged j : judged) {
            var o = arr.addObject();
            o.put("path", j.rule().path() == null ? "" : j.rule().path());
            o.put("op", j.rule().op() == null ? "" : j.rule().op());
            o.put("expect", j.rule().expect() == null ? "" : j.rule().expect());
            o.put("ok", j.ok());
            if (j.actual() == null) o.putNull("actual"); else o.put("actual", j.actual());
            o.put("source", j.source()); o.put("actualTruncated", j.actualTruncated());
            if (j.failure() == null) {
                o.putNull("why");
            } else {
                o.put("why", j.failure().message());
            }
        }
        return arr.toString();
    }

    /**
     * 逐条判定，返回所有没过的原因（不是遇到第一条就停）。
     *
     * <p>全量返回是刻意的：调接口时一次就能把问题看全，比「改一条跑一次」快得多。
     *
     * @param body  响应体原文（<b>截断前</b>的那一份）
     * @param rules 规则列表
     * @return 失败原因；全部通过时为空列表
     */
    public static List<Failure> evaluate(String body, List<Rule> rules) {
        List<Failure> fails = new ArrayList<>();
        for (Judged j : judgeAll(body, rules)) {
            if (j.failure() != null) {
                fails.add(j.failure());
            }
        }
        return fails;
    }

    private static Failure judge(String raw, boolean blank, JsonNode root, boolean isJson, Rule r, long regexDeadline) {
        String op = norm(r.op());
        if (op.isEmpty()) {
            return new Failure(r.path(), "没有选择判断条件");
        }
        if (OP_BROKEN.equals(op)) {
            return new Failure(r.path(), "断言配置无法解析，请重新编辑这个用例的断言");
        }
        if (!isKnown(op)) {
            return new Failure(r.path(), "无法识别的判断条件：" + r.op());
        }
        if (blank) {
            return new Failure(r.path(), "响应体为空，无法断言");
        }
        if ("schema".equals(op) && !isJson) return new Failure(r.path(), "响应体不是完整 JSON，无法进行 Schema 校验");
        if (r.path() == null || r.path().isBlank()) {
            // 根：是 JSON 就是整个文档，不是 JSON 就是整段纯文本
            JsonNode node = isJson ? root : TextNode.valueOf(raw);
            return apply(r, op, List.of(node), false, regexDeadline);
        }
        if (!isJson) {
            return new Failure(r.path(), "响应体不是 JSON，无法按路径断言");
        }
        Resolved res = resolve(root, r.path());
        if (res.error() != null) {
            // 「不存在」允许路径取不到：键没有、父级走不到、下标越界，都算这条字段不存在。
            // 但「空数组 + [*]」这类结构性问题不在此列（absent=false），必须报出来。
            if ("notexists".equals(op) && res.absent()) {
                return null;
            }
            return new Failure(r.path(), res.error());
        }
        return apply(r, op, res.nodes(), res.nodes().size() > 1, regexDeadline);
    }

    /** 单条规则作用在一组值上：全部满足才算过（这是 {@code [*]} 的语义）。 */
    private static Failure apply(Rule r, String op, List<JsonNode> nodes, boolean many, long regexDeadline) {
        for (int i = 0; i < nodes.size(); i++) {
            String bad = check(op, nodes.get(i), r.expect(), regexDeadline);
            if (bad != null) {
                String where = many ? "第 " + (i + 1) + " 个元素不满足，" : "";
                return new Failure(r.path(), where + bad);
            }
        }
        return null;
    }

    // ==================== 单个条件的判定 ====================

    private static String check(String op, JsonNode n, String expect, long regexDeadline) {
        String exp = expect == null ? "" : expect.trim();
        switch (op) {
            case "schema":
                return ApiJsonSchema.failure(exp, n, regexDeadline);
            case "exists":
                return n.isNull() ? "字段存在，但值是 null" : null;
            case "notexists":
                return n.isNull() ? null : "字段存在（值 " + show(n) + "）";
            case "type":
                return checkType(n, exp);
            case "equals":
                return eq(n, exp) ? null : "期望 " + quote(exp) + "，实际 " + show(n);
            case "notequals":
                return eq(n, exp) ? "期望不等于 " + quote(exp) + "，但实际就是它" : null;
            case "contains":
                return text(n).contains(exp) ? null : "期望包含 " + quote(exp) + "，实际 " + show(n);
            case "regex":
                SafeRegex.Result regex = SafeRegex.find(exp, text(n), regexDeadline);
                if (regex.error() != null) return regex.error() + "；实际 " + show(n);
                return regex.matched() ? null : "期望匹配正则 " + quote(exp) + "，实际 " + show(n);
            case "gt":
            case "gte":
            case "lt":
            case "lte":
                return checkCompare(op, n, exp);
            case "length":
                return checkLength(n, exp);
            case "in":
                for (String s : exp.split("[,，]")) {
                    if (eq(n, s.trim())) {
                        return null;
                    }
                }
                return "期望属于其中之一 " + quote(exp) + "，实际 " + show(n);
            default:
                return "无法识别的判断条件：" + op;
        }
    }

    private static String checkType(JsonNode n, String exp) {
        if (exp.isBlank()) {
            return "没有选择要断言的类型";
        }
        boolean ok;
        switch (exp) {
            case "string" -> ok = n.isTextual();
            case "number" -> ok = n.isNumber();
            case "integer" -> ok = n.isIntegralNumber();
            case "boolean" -> ok = n.isBoolean();
            case "object" -> ok = n.isObject();
            case "array" -> ok = n.isArray();
            case "null" -> ok = n.isNull();
            default -> {
                return "无法识别的类型名 " + quote(exp)
                        + "（可用 string / number / integer / boolean / object / array / null）";
            }
        }
        if (ok) {
            return null;
        }
        if ("integer".equals(exp) && n.isNumber()) {
            return "期望类型 integer，实际是带小数点的 number（值 " + show(n) + "）";
        }
        return "期望类型 " + exp + "，实际是 " + typeName(n) + "（值 " + show(n) + "）";
    }

    private static String checkCompare(String op, JsonNode n, String exp) {
        BigDecimal a = num(n);
        if (a == null) {
            return "期望做数值比较，但实际不是数字（" + show(n) + "）";
        }
        BigDecimal b = parseNum(exp);
        if (b == null) {
            return "期望值要写一个数字，当前是 " + (exp.isBlank() ? "（空）" : quote(exp));
        }
        int c = a.compareTo(b);
        boolean ok = switch (op) {
            case "gt" -> c > 0;
            case "gte" -> c >= 0;
            case "lt" -> c < 0;
            default -> c <= 0;
        };
        return ok ? null : "期望 " + label(op) + " " + exp + "，实际 " + show(n);
    }

    private static String checkLength(JsonNode n, String exp) {
        Integer len = len(n);
        if (len == null) {
            return "期望取长度，但实际是 " + typeName(n) + "（只有数组和字符串有长度）";
        }
        if (exp.isBlank()) {
            return "期望值要写一个数字表示长度";
        }
        return String.valueOf(len).equals(exp) ? null : "期望长度 " + exp + "，实际 " + len;
    }

    private static String label(String op) {
        return switch (op) {
            case "gt" -> "大于";
            case "gte" -> "大于等于";
            case "lt" -> "小于";
            default -> "小于等于";
        };
    }

    /** 相等：两边都像数字就按数值比（1.0 == 1），否则按文本比。 */
    private static boolean eq(JsonNode n, String exp) {
        BigDecimal a = num(n);
        BigDecimal b = parseNum(exp);
        if (a != null && b != null) {
            return a.compareTo(b) == 0;
        }
        return text(n).equals(exp);
    }

    /** 数字取出来做比较用；不是数字返回 null。 */
    private static BigDecimal num(JsonNode n) {
        return n != null && n.isNumber() ? n.decimalValue() : null;
    }

    private static BigDecimal parseNum(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 数组长度 / 字符串长度；其他类型没有长度。 */
    private static Integer len(JsonNode n) {
        if (n == null) {
            return null;
        }
        if (n.isArray()) {
            return n.size();
        }
        if (n.isTextual()) {
            return n.asText().length();
        }
        return null;
    }

    /** 实际值的文本形态：字符串去掉 JSON 的引号包裹，其他类型用它自己的 JSON 表示。 */
    private static String text(JsonNode n) {
        if (n == null) {
            return "";
        }
        return n.isTextual() ? n.asText() : n.toString();
    }

    private static String show(JsonNode n) {
        String s = n == null ? "null" : (n.isTextual() ? quote(n.asText()) : n.toString());
        s = s.replace("\r", " ").replace("\n", " ");
        return s.length() > MAX_VALUE_CHARS ? s.substring(0, MAX_VALUE_CHARS) + "…" : s;
    }

    private static String quote(String s) {
        return "\"" + s + "\"";
    }

    /** 给报错用的类型名。数字一律叫 number（integer 是 number 的子集，细分在 checkType 里说）。 */
    private static String typeName(JsonNode n) {
        if (n == null || n.isNull()) {
            return "null";
        }
        if (n.isTextual()) {
            return "string";
        }
        if (n.isNumber()) {
            return "number";
        }
        if (n.isBoolean()) {
            return "boolean";
        }
        if (n.isObject()) {
            return "object";
        }
        if (n.isArray()) {
            return "array";
        }
        return "未知类型";
    }

    // ==================== 路径解析 ====================

    /** 路径解析结果：要么是一组值，要么是一条说明为什么走不到的错。 */
    private record Resolved(List<JsonNode> nodes, String error, boolean absent) {
        static Resolved of(JsonNode n) {
            return new Resolved(List.of(n), null, false);
        }

        /** 字段/下标取不到 —— 「不存在」可以接受这种。 */
        static Resolved missing(String why) {
            return new Resolved(List.of(), why, true);
        }

        /** 结构性问题（空数组通配、通配用在非数组上）—— 任何条件都不接受。 */
        static Resolved broken(String why) {
            return new Resolved(List.of(), why, false);
        }
    }

    /**
     * 沿路径取值。语法：点号分层，数组用 {@code [下标]} 或 {@code [*]}（全部元素）。
     *
     * <p>额外容错：路径末段写 {@code .length} 且该层本来是数组/字符串时，
     * 取它的长度。这样 {@code data.players.length} 与用「长度等于」条件写法等价，
     * 两种写法都认 —— 写惯了哪种就用哪种，不用先去看文档。若真的有个键就叫 length，
     * 以真实键为准（先找键，找不到才当长度）。
     */
    private static Resolved resolve(JsonNode root, String path) {
        if (path.startsWith("/")) {
            JsonNode value = root.at(path);
            return value.isMissingNode() ? Resolved.missing("JSON Pointer 路径不存在") : Resolved.of(value);
        }
        if ("$".equals(path)) return Resolved.of(root);
        if (path.startsWith("$.")) path = path.substring(2);
        List<JsonNode> current = List.of(root);
        for (String seg : split(path)) {
            List<JsonNode> next = new ArrayList<>();
            for (JsonNode n : current) {
                Resolved step = step(n, seg);
                if (step.error() != null) {
                    return step;
                }
                next.addAll(step.nodes());
            }
            if (next.isEmpty()) {
                return Resolved.missing("键不存在");
            }
            current = next;
        }
        return new Resolved(current, null, false);
    }

    /** 提取使用同一个路径实现，保留任意精度整数，调用方明确处理单值/多值。 */
    public static List<JsonNode> readValues(String body, String path) {
        ApiStructuredBody.require(body);
        try { return readValues(JSON.readTree(body == null ? "" : body),path); }
        catch (IllegalArgumentException e) { throw e; }
        catch (Exception e) { throw new IllegalArgumentException("响应体不是有效 JSON"); }
    }
    /** 同一判定/提取轮次复用已解析树；入口在读取原文之前统一执行预算检查。 */
    static List<JsonNode> readValues(JsonNode root,String path) {
        if (root == null) throw new IllegalArgumentException("响应体为空");
        if (path == null || path.isBlank()) return List.of(root);
        Resolved result = resolve(root, path);
        if (result.error() != null) throw new IllegalArgumentException(result.error());
        return result.nodes();
    }

    private static List<String> split(String path) {
        List<String> out = new ArrayList<>();
        for (String s : path.split("\\.")) {
            if (!s.isBlank()) {
                out.add(s.trim());
            }
        }
        return out;
    }

    /** 走一段路径：取出键名，再按方括号里的下标/通配符逐层展开。 */
    private static Resolved step(JsonNode node, String seg) {
        String name = seg;
        List<String> idx = new ArrayList<>();
        int b = seg.indexOf('[');
        if (b >= 0) {
            name = seg.substring(0, b);
            int i = b;
            while (i < seg.length()) {
                int close = seg.indexOf(']', i);
                if (close < 0) {
                    return Resolved.broken("路径写法不对，少了一个 ]：" + seg);
                }
                idx.add(seg.substring(i + 1, close).trim());
                i = close + 1;
                if (i < seg.length() && seg.charAt(i) != '[') {
                    return Resolved.broken("路径写法不对：" + seg);
                }
            }
        }

        JsonNode cur = node;
        if (!name.isBlank()) {
            JsonNode child = null;
            if (node.isObject()) {
                child = node.get(name);
            } else if ("length".equals(name) && node.isArray()) {
                child = JSON.getNodeFactory().numberNode(node.size());
            } else if ("length".equals(name) && node.isTextual()) {
                child = JSON.getNodeFactory().numberNode(node.asText().length());
            }
            if (child == null) {
                if (node.isObject()) {
                    // 父级是对象、但这个键没有 —— 最干净的一种「不存在」。
                    // 与「键在、值是 null」严格区分：排查时这是两个不同的问题。
                    return Resolved.missing("键不存在");
                }
                return Resolved.missing("路径走不到底：中途是 " + typeName(node)
                        + "，取不到键 " + quote(name));
            }
            cur = child;
        }

        List<JsonNode> out = new ArrayList<>();
        out.add(cur);
        for (String s : idx) {
            List<JsonNode> nx = new ArrayList<>();
            for (JsonNode n : out) {
                if (!n.isArray()) {
                    return Resolved.broken("[" + s + "] 只能用在数组上，这里是 " + typeName(n));
                }
                if ("*".equals(s)) {
                    if (n.isEmpty()) {
                        return Resolved.broken("数组为空，[*] 断言无法成立");
                    }
                    n.forEach(nx::add);
                    continue;
                }
                int k;
                try {
                    k = Integer.parseInt(s);
                } catch (NumberFormatException e) {
                    return Resolved.broken("数组下标要写数字或 *，当前是 [" + s + "]");
                }
                if (k < 0 || k >= n.size()) {
                    return Resolved.missing("数组下标 " + k + " 越界（实际长度 " + n.size() + "）");
                }
                nx.add(n.get(k));
            }
            if (nx.isEmpty()) {
                return Resolved.missing("键不存在");
            }
            out = nx;
        }
        return new Resolved(out, null, false);
    }

    // ==================== 条件名归一化 ====================

    /** 归一化条件名：容忍大小写与分隔符写法差异（notExists / not_exists / NOT-EXISTS 都认）。 */
    private static String norm(String op) {
        if (op == null) {
            return "";
        }
        return op.trim().toLowerCase().replace("_", "").replace("-", "").replace(".", "");
    }

    private static boolean isKnown(String op) {
        return switch (op) {
            case "exists", "notexists", "type", "equals", "notequals", "contains",
                 "gt", "gte", "lt", "lte", "length", "in", "regex", "schema" -> true;
            default -> false;
        };
    }
}
