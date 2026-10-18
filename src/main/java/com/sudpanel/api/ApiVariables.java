package com.sudpanel.api;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 自定义变量：把地址、请求头与请求体里的 {@code {{名称}}} 换成各自那一组里定义的值。
 *
 * <p><b>为什么分成三组。</b>三处的来源不同、改的频率也不同：<b>基址</b>回答「这一套环境在哪」，
 * 换环境时只改它；<b>请求头变量</b>是环境信息（版本号、灰度标记）；
 * <b>请求体变量</b>是这条用例的业务数据。混在一张表里时，同名就会互相盖掉 ——
 * 比如两边都叫 {@code id}，改了一个另一个跟着变，而且不报错。
 * 分组之后「哪一组只在哪个框里生效」是明确的，同名也不会串。
 *
 * <p><b>为什么基址要单列一组。</b>地址本来就归请求头那一组管（见下），看似多此一举；
 * 但「域名 / 前缀」是换环境时唯一要改的东西，而请求头里放的是另一类环境信息 ——
 * 混在一起时，换个环境得在一堆请求头变量里翻那一行。
 * 单列之后还多出一条明确的边界：<b>基址只作用于地址，永远不会被塞进请求头</b>。
 * 同名时基址优先（它是专为地址设的）。
 *
 * <p><b>替换发生在签名之前。</b>签名覆盖的是真正发出去的那一份请求体，所以必须先把变量换完再算签名；
 * 顺序反了，服务端算出来的签名和收到的内容对不上，表现是「认证失败」，从现象上看不出线索。
 *
 * <p><b>没定义的占位符原样保留。</b>平台自己那套（{@code {{times}}}、{@code {{sign}}} 等）
 * 就是靠这一点活下来的：这里只认本组定义过的名字，其余一律不动，交给下一段处理。
 */
public final class ApiVariables {

    /**
     * 基址那一组（2026-10-04 新增）。
     *
     * <p><b>只在地址里生效</b>，不进请求头也不进请求体。地址同时也认请求头那一组的名字
     * （向后兼容：这次改动之前，地址就是跟请求头共用一组的），同名时以本组为准。
     */
    public static final String SCOPE_BASE = "BASE";

    /**
     * 请求头那一组。
     *
     * <p>地址也按这一组走：地址不属于「请求头」「请求体」两个输入框中的任何一个，总得归到一侧；
     * 而「填域名、拼地址」本来就跟请求头属于同一类环境信息。界面上会写清楚这一点，
     * 不让使用者在地址里用变量时还要猜。
     */
    public static final String SCOPE_HEADER = "HEADER";

    /** 请求体那一组。 */
    public static final String SCOPE_BODY = "BODY";

    /** 变量表里的一行。值允许是空串（表示「就让它空着」）。 */
    public record Var(String name,String value,Boolean secret) {
        public Var(String name,String value){this(name,value,false);}
    }

    /** 三组变量。列表顺序即界面上的顺序。 */
    public record VarSet(List<Var> base, List<Var> header, List<Var> body) {

        /**
         * 只有请求头与请求体两组时的写法（基址为空）。
         *
         * <p>保留这个构造器是为了让「还没有基址概念」的调用点与测试继续成立，
         * 而不是让每处都补一个 {@code List.of()}。
         */
        public VarSet(List<Var> header, List<Var> body) {
            this(List.of(), header, body);
        }

        public static VarSet empty() {
            return new VarSet(List.of(), List.of(), List.of());
        }

        public boolean isEmpty() {
            return base.isEmpty() && header.isEmpty() && body.isEmpty();
        }
    }

    /**
     * 只匹配 {@code {{名称}}}：名称里不含花括号，两侧空白归平台所有，所以 {@code {{ name }}} 也能用。
     * 一次扫描找出全部占位符，而不是「每个变量名做一次全局替换」——
     * 后者在变量多了之后是「变量数 × 文本长度」，而且文本里同时出现很多占位符时容易漏。
     */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([^{}]+?)\\s*\\}\\}");

    private ApiVariables() {
    }

    /**
     * 按作用域替换三处文本。三组互不干涉：请求头里看不到请求体那组的名字，反之亦然。
     *
     * <p>唯一一处「看得见两组」的地方是地址：基址组与请求头组都作用于它，同名时基址优先。
     * 反过来不成立 —— 基址的名字不会出现在请求头里。
     */
    public static ApiRequest apply(VarSet vars, String url, String headers, String body) {
        if (vars == null || vars.isEmpty()) {
            return new ApiRequest(url, headers, body);
        }
        Map<String, String> head = toMap(vars.header());
        Map<String, String> bodyVars = toMap(vars.body());
        // 地址专用：先铺请求头那组，再用基址覆盖过来 —— 同名时基址赢
        Map<String, String> urlVars = new LinkedHashMap<>(head);
        urlVars.putAll(toMap(vars.base()));
        return new ApiRequest(sub(url, urlVars), sub(headers, head), sub(body, bodyVars));
    }

    /**
     * 按一组覆盖值改写**请求体那一组**变量（2026-10-06，需求 #68）。
     *
     * <p><b>用途。</b>性能测试的「人机对局」模式要为每个游戏用不同的 {@code mg_id} / {@code app_id}
     * 去推送指令，而变量表里那两组只有一份全局值。挂在这里而不是去改全局变量：
     * 全局值是接口测试页面上的配置，被压测顺手改掉的话，下一次手工调接口会莫名其妙地用了别的游戏的身份。
     *
     * <p><b>只动请求体那一组。</b>基址与请求头是环境信息，与「这是哪个游戏」无关；
     * 而且地址里本来也读不到请求体那组的名字（见 {@link #apply}），改了也传不过去。
     *
     * <p><b>null 与空串不一样</b>：{@code null} 表示「这一个不覆盖」（保留表里的值），
     * 空串表示「就是要它空着」。两者混同会让「这个游戏没填 app_id」变成「把变量表里的值抹掉」。
     *
     * @param vars      原变量（可为 null，等价于三组皆空）
     * @param overrides 要覆盖的「变量名 → 值」；未在原列表里出现的名字会**追加**到末尾
     */
    public static VarSet overrideBody(VarSet vars, Map<String, String> overrides) {
        VarSet base = vars == null ? VarSet.empty() : vars;
        if (overrides == null || overrides.isEmpty()) {
            return base;
        }
        List<Var> out = new java.util.ArrayList<>();
        java.util.Set<String> done = new java.util.LinkedHashSet<>();
        // 第一遍：按原顺序走一遍，命中的换成覆盖值（保持界面上的顺序不变，便于对照）
        for (Var v : base.body()) {
            if (v == null || v.name() == null || v.name().isBlank()) {
                continue;
            }
            String key = v.name().trim();
            if (overrides.containsKey(key) && overrides.get(key) != null) {
                out.add(new Var(key, overrides.get(key),v.secret()));
                done.add(key);
            } else {
                out.add(v);
            }
        }
        // 第二遍：覆盖里有、原列表里没有的名字追加到末尾 —— 变量表还没加过这个名字时也能用
        for (Map.Entry<String, String> e : overrides.entrySet()) {
            if (e.getValue() == null || done.contains(e.getKey())) {
                continue;
            }
            String key = e.getKey() == null ? "" : e.getKey().trim();
            if (key.isEmpty() || containsName(base.body(), key)) {
                continue;
            }
            out.add(new Var(key, e.getValue()));
        }
        return new VarSet(base.base(), base.header(), out);
    }

    private static boolean containsName(List<Var> vars, String name) {
        for (Var v : vars) {
            if (v != null && v.name() != null && name.equals(v.name().trim())) {
                return true;
            }
        }
        return false;
    }

    /** 用某一组变量替换文本里的占位符；没定义的名字原样留下。 */
    static String sub(String text, Map<String, String> vars) {
        // 先做一个便宜的预判：连「{{」都没有就整段跳过。请求体常有几十 KB，能省一次全文扫描
        if (text == null || text.isEmpty() || vars.isEmpty() || !text.contains("{{")) {
            return text;
        }
        Matcher m = PLACEHOLDER.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String v = vars.get(m.group(1));
            // 不是本组定义的名字：把原样文本替换回去，等价于跳过这一段
            m.appendReplacement(sb, Matcher.quoteReplacement(v == null ? m.group() : v));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * 转成查找表。同名以最后一条为准 —— 界面上是键值对列表，真写了两个同名也该有个确定、
     * 能解释的结果，而不是看数据库返回顺序。
     */
    static Map<String, String> toMap(List<Var> vars) {
        Map<String, String> m = new LinkedHashMap<>();
        if (vars == null) {
            return m;
        }
        for (Var v : vars) {
            if (v == null || v.name() == null || v.name().isBlank()) {
                continue;
            }
            m.put(v.name().trim(), v.value() == null ? "" : v.value());
        }
        return m;
    }
}
