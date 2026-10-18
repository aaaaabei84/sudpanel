package com.sudpanel.perf;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 「人机对局」模式里往房间推的那批人机（2026-10-06，需求 #68）。
 *
 * <h2>它解决什么</h2>
 * 推送用的人机名单本来是写在接口用例的请求体里的（{@code data.user_infos} 数组）。
 * 但「推几个人」是要随场景调的：有时候要 8 个，有时候要 4 个对比。若每次都去改用例，
 * 用例就不再是「一条用例」而是「一堆参数的载体」—— 压测的配置散落在接口测试里，
 * 事后没人说得清某一轮到底推了几个。
 *
 * <p>所以留两条路，且**默认走用例原文**：
 * <ul>
 *   <li>没填数量 → 请求体基本不动，只保证第一条是真人（{@link #forceFirstHuman}）；</li>
 *   <li>填了数量 → 本类按数量重排 {@code data.user_infos}（{@link #withPlayerCount}）。</li>
 * </ul>
 *
 * <h2>为什么按样板复制而不是凭空造</h2>
 * 一条人机记录里有十来个个字段（头像、性别、AI 等级、分组、是否随机座位…），
 * 平台并不知道每个游戏各自认哪几个、取值范围是什么。凭空造一份「看起来对」的，
 * 到了服务端可能直接被丢掉或判非法 —— 而那种失败在压测里表现成「人机没进来」，
 * 查起来会往网络和权限方向跑。所以这里**只动必须动的字段**
 * （{@code uid} / {@code nick_name} 要唯一，{@code is_ai} 按下面的规则定，
 * {@code is_ready} 要就绪，{@code seat_index} 保持自动分配），
 * 其余一律沿用用例里那一份。样板有多条时按序轮流取，头像与等级自然有变化。
 *
 * <h2>is_ai 的规则：前 N 条真人，其余全是人机</h2>
 * 服务端按这个字段决定「这是不是人机」。使用者点名（需求 #69，#70 扩成可配）：
 * <b>推送名单的前 N 条是真人（is_ai=0），其余全是人机（is_ai=1）</b>——
 * 房间里要有真人身份在，纯人机房与真实对局的表现对不上；而「几个真人、几个人机」
 * 要随压测场景调（#70），所以 N 由使用者在游戏上自己配，不配时缺省 1（#69 的兜底）。
 * 人机条目的 {@code ai_level} 也可以按游戏配（#70）：配了就写进每个人机条目，
 * 没配沿用用例样板里的原值。名单长什么样，用 {@link #summarize}
 * 压成一句可直接看的话写进日志。
 */
public final class AiPlayers {

    /** 默认人机数量。使用者需求里的原话就是 8 个。 */
    public static final int DEFAULT_COUNT = 8;

    /** 人数上限：防止把 0 写成 10000 之后一次性造出一个巨大的请求体。 */
    public static final int MAX_COUNT = 64;

    private static final ObjectMapper JSON = new ObjectMapper();

    private AiPlayers() {
    }

    /**
     * 一次改写的结果。
     *
     * @param body    改写后的请求体；{@code applied=false} 时就是**原样**的入参
     * @param applied 是否真的改写了
     * @param detail  没改写时说明原因（要进日志与圈记录），改写时说明改成了几个人
     */
    public record Applied(String body, boolean applied, String detail) {
    }

    /**
     * 一份名单的组成（需求 #70；#71 加本机账号身份）。
     *
     * @param humans     真人条数（is_ai=0）。0 = 纯人机房（对照场景），界面上允许显式配 0
     * @param ais        人机条数（is_ai=1）
     * @param level      人机的 ai_level；null = 不动这个字段，沿用用例样板里的原值
     * @param machineUid 本机账号的 uid（#71）；null = 真人条沿用 cy 编号。
     *                   使用者要求「房间里的人全部由接口推送」——真机客户端必须发起一次
     *                   加入才能收到对局画面（游戏机制），把名单真人位直接用本机账号身份推送后，
     *                   客户端随后的加入请求被服务端幂等合并，房间账本上不再有「多出来的账号」
     */
    public record RosterSpec(int humans, int ais, Integer level, String machineUid) {
    }

    /** 后端保存与运行共用：计数使用 long，避免整数溢出后绕过人数上限。 */
    public static void requireValidCounts(int humans, int ais) {
        long total = (long) humans + ais;
        if (humans < 0 || ais < 0 || humans > MAX_COUNT || ais > MAX_COUNT
                || total < 1 || total > MAX_COUNT) {
            throw new IllegalStateException("名单总人数（真人+人机）必须为 1～" + MAX_COUNT + "，且各项不能为负");
        }
    }

    /** 自定义或原文名单也受同一总人数门槛约束；模板变量临时用 0 只为读取数组长度。 */
    public static void requireValidBodyCount(String body) {
        if (body == null || body.isBlank()) return;
        try {
            JsonNode root = JSON.readTree(body.replaceAll("\\{\\{[^}]*}}", "0"));
            JsonNode infos = root.path("data").path("user_infos");
            if (infos.isMissingNode()) return; // 非名单类请求由各自用例的契约校验。
            if (!infos.isArray() || infos.size() < 1 || infos.size() > MAX_COUNT) {
                throw new IllegalStateException("推送体 data.user_infos 的总人数必须为 1～" + MAX_COUNT);
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException malformed) {
            // 已有 JSON 准备校验负责语法，不在人数检查中改变旧模板的解析规则。
        }
    }

    /**
     * 按名单组成重排 {@code data.user_infos}（需求 #69 起，#70 扩成真人/人机/等级三项）。
     *
     * <p>生成规则：前 {@code humans} 条 is_ai=0（uid 从 cy01 起），随后 {@code ais} 条
     * is_ai=1（uid 接着编号）且 {@code level} 非空时写入 ai_level；样板条目轮流复制，
     * 其余字段（头像、分组……）沿用用例里那一份。任何一步不成立都**原样返回**并给出原因，
     * 绝不抛异常：压测正跑着，一个用例的请求体格式写得特殊一点就炸掉整轮运行，
     * 代价远大于「这次按原文推」。
     */
    public static Applied withRoster(String bodyJson, RosterSpec roster) {
        if (bodyJson == null || bodyJson.isBlank()) {
            return new Applied(bodyJson, false, "请求体为空，按原文推送");
        }
        int humans = roster.humans();
        int ais = roster.ais();
        long totalLong = (long) humans + ais;
        int total = totalLong <= Integer.MAX_VALUE ? (int) totalLong : Integer.MAX_VALUE;
        if (humans < 0 || ais < 0) {
            return new Applied(bodyJson, false, "真人数/人机数不能为负，按原文推送");
        }
        if (total <= 0) {
            return new Applied(bodyJson, false, "真人数与人机数都是 0，没有可推的人，按原文推送");
        }
        if (total > MAX_COUNT) {
            return new Applied(bodyJson, false, "名单总数（真人+人机）超过上限 " + MAX_COUNT + "，按原文推送");
        }
        ObjectNode root;
        try {
            JsonNode parsed = JSON.readTree(bodyJson);
            if (!(parsed instanceof ObjectNode obj)) {
                return new Applied(bodyJson, false, "请求体不是 JSON 对象，按原文推送");
            }
            root = obj;
        } catch (Exception e) {
            return new Applied(bodyJson, false, "请求体不是合法 JSON，按原文推送");
        }
        JsonNode dataNode = root.get("data");
        if (!(dataNode instanceof ObjectNode data)) {
            return new Applied(bodyJson, false, "请求体里没有 data 对象，按原文推送");
        }
        JsonNode infosNode = data.get("user_infos");
        if (!(infosNode instanceof ArrayNode infos) || infos.isEmpty()) {
            return new Applied(bodyJson, false, "请求体里没有非空的 data.user_infos 数组，按原文推送");
        }
        // 样板：用例里写的那些人（通常是 4 条）。用它复制，只覆盖必须动的字段。
        java.util.List<ObjectNode> samples = new java.util.ArrayList<>();
        for (JsonNode n : infos) {
            if (n instanceof ObjectNode o) {
                samples.add(o);
            }
        }
        if (samples.isEmpty()) {
            return new Applied(bodyJson, false, "data.user_infos 里没有可作样板的 JSON 对象，按原文推送");
        }
        ArrayNode rebuilt = JSON.createArrayNode();
        for (int i = 0; i < total; i++) {
            ObjectNode one = samples.get(i % samples.size()).deepCopy();
            boolean human = i < humans;
            // #71：真人首条用本机账号身份 —— 真机客户端加入时服务端视为同一账号，
            // 房间账本上全部成员都来自这份接口推送的名单
            String uid = (i == 0 && roster.machineUid() != null && !roster.machineUid().isBlank())
                    ? roster.machineUid().trim()
                    : "cy" + String.format("%02d", i + 1);
            one.put("uid", uid);
            one.put("nick_name", uid);
            // 前 humans 条是真人（0），其余是人机（1）——房间里的真人身份数量随场景配（#70）
            one.put("is_ai", human ? 0 : 1);
            if (!human && roster.level() != null) {
                // ai_level 是人机的对局强度（#70）：只写人机条目，真人条沿用样板原值
                one.put("ai_level", roster.level());
            }
            one.put("is_ready", true);
            // -1 = 让服务端自己分配座位。写死具体座位号会在多人时互相顶掉
            one.put("seat_index", -1);
            rebuilt.add(one);
        }
        data.set("user_infos", rebuilt);
        try {
            String first = roster.machineUid() != null && !roster.machineUid().isBlank()
                    ? roster.machineUid().trim() : "cy01";
            String detail = "已按数量重排名单：真人 " + humans + "（首位=" + first + "）+ 人机 " + ais
                    + (roster.level() != null ? "（ai_level=" + roster.level() + "）" : "");
            return new Applied(JSON.writerWithDefaultPrettyPrinter().writeValueAsString(root),
                    true, detail);
        } catch (Exception e) {
            return new Applied(bodyJson, false, "重排后的请求体序列化失败，按原文推送");
        }
    }

    /**
     * 只保证一条：名单第 1 条是真人（is_ai=0），其余原样不动。
     *
     * <p>「没填数量、按用例原文推」的路上也要满足同一条规则（需求 #69）——
     * 用例模板里的名单是随手复制来的，第 1 条往往就是 is_ai=1；照原样推，
     * 房间里就少了那个真人身份。与 {@link #withPlayerCount} 一样，
     * 任何一步不成立都原样返回并给原因，绝不抛异常。
     *
     * <p>第 1 条本来就是 0 时返回「没改」：语义已满足，不动请求体，
     * 免得无谓地重排一遍字段顺序。
     */
    public static Applied forceFirstHuman(String bodyJson) {
        if (bodyJson == null || bodyJson.isBlank()) {
            return new Applied(bodyJson, false, "请求体为空，按原文推送");
        }
        JsonNode parsed;
        try {
            parsed = JSON.readTree(bodyJson);
        } catch (Exception e) {
            return new Applied(bodyJson, false, "请求体不是合法 JSON，按原文推送");
        }
        JsonNode infos = parsed.path("data").path("user_infos");
        if (!infos.isArray() || infos.isEmpty() || !(infos.get(0) instanceof ObjectNode first)) {
            return new Applied(bodyJson, false, "请求体里没有非空的 data.user_infos 数组，按原文推送");
        }
        if (first.path("is_ai").asInt(-1) == 0) {
            return new Applied(bodyJson, false, "第 1 条本来就是真人，名单原样推送");
        }
        first.put("is_ai", 0);
        try {
            return new Applied(JSON.writerWithDefaultPrettyPrinter().writeValueAsString(parsed),
                    true, "已把第 1 条改成真人（is_ai=0），其余原样保留");
        } catch (Exception e) {
            return new Applied(bodyJson, false, "改写后的请求体序列化失败，按原文推送");
        }
    }

    /**
     * 把名单压成一句可直接看的话（日志与复核用，需求 #69「分不清人机还是真人」）。
     *
     * <p>形如 {@code 8 人：真人 1（cy01）+ 人机 7}。is_ai 既不是 0 也不是 1 的条目
     * 单独说出来——那往往是用例模板本身写岔了，值得被看见而不是被默默归进某一类。
     * 取不到名单返回 null（调用方就不打这行日志）。
     */
    public static String summarize(String bodyJson) {
        if (bodyJson == null || bodyJson.isBlank()) {
            return null;
        }
        try {
            JsonNode infos = JSON.readTree(bodyJson).path("data").path("user_infos");
            if (!infos.isArray() || infos.isEmpty()) {
                return null;
            }
            int humans = 0, ais = 0, others = 0;
            String firstHuman = null;
            for (JsonNode n : infos) {
                int v = n.path("is_ai").asInt(-1);
                if (v == 0) {
                    humans++;
                    if (firstHuman == null) {
                        firstHuman = n.path("uid").asText("?");
                    }
                } else if (v == 1) {
                    ais++;
                } else {
                    others++;
                }
            }
            StringBuilder sb = new StringBuilder().append(infos.size()).append(" 人：真人 ").append(humans);
            if (firstHuman != null) {
                sb.append("（").append(firstHuman).append("）");
            }
            sb.append(" + 人机 ").append(ais);
            if (others > 0) {
                sb.append(" + is_ai 取值异常 ").append(others);
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 取出请求体里的人机数量（样板条数）。
     *
     * <p>只用于**报告与日志**：让「这一轮实际推了几个」有个可直接读的数，
     * 而不是要人点开请求体去数数组元素。取不到返回 -1（不是 0，0 是个合法数量）。
     */
    public static int playerCountOf(String bodyJson) {
        if (bodyJson == null || bodyJson.isBlank()) {
            return -1;
        }
        try {
            JsonNode infos = JSON.readTree(bodyJson).path("data").path("user_infos");
            return infos.isArray() ? infos.size() : -1;
        } catch (Exception e) {
            return -1;
        }
    }
}
