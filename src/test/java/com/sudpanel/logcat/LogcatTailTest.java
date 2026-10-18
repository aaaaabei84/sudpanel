package com.sudpanel.logcat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 上报查询增量取文的纯逻辑测试（2026-10-06，需求 #60）。
 *
 * <p>这一块判错的后果很隐蔽：切多了界面出现重复行（看着像日志真的重复了），
 * 切少了**界面少几行而没人发现**（最坏的一种 —— 使用者以为看到的就是全部）。
 * 所以这里重点压三件事：锚点命中时不多不少、锚点失效时敢整段重推、末尾空行不顶掉锚点。
 */
class LogcatTailTest {

    private static final String A = "10-06 10:00:01.001  1234  1250 I SudReport: a";
    private static final String B = "10-06 10:00:02.002  1234  1250 I SudReport: b";
    private static final String C = "10-06 10:00:03.003  1234  1250 I SudReport: c";
    private static final String D = "10-06 10:00:04.004  1234  1250 I SudReport: d";

    @Test
    @DisplayName("首次取文（没有锚点）：整段给出去，并标记为替换")
    void firstFetch() {
        LogcatTail.Result r = LogcatTail.diff(A + "\n" + B + "\n", null);
        assertThat(r.lines()).containsExactly(A, B);
        assertThat(r.anchor()).isEqualTo(B);
        assertThat(r.reset()).isTrue();
        assertThat(r.total()).isEqualTo(2);
    }

    @Test
    @DisplayName("锚点命中：只给锚点之后的新行，且 reset=false（界面据此追加）")
    void anchoredIncremental() {
        LogcatTail.Result r = LogcatTail.diff(A + "\n" + B + "\n" + C + "\n" + D, B);
        assertThat(r.lines()).containsExactly(C, D);
        assertThat(r.anchor()).isEqualTo(D);
        assertThat(r.reset()).isFalse();
    }

    @Test
    @DisplayName("锚点就是最后一行：没有新行，且锚点保持原值（不能变成 null，否则下轮整段重推）")
    void noNewLinesKeepsAnchor() {
        LogcatTail.Result r = LogcatTail.diff(A + "\n" + B, B);
        assertThat(r.lines()).isEmpty();
        assertThat(r.anchor()).isEqualTo(B);
        assertThat(r.reset()).isFalse();
    }

    @Test
    @DisplayName("锚点不在了（被环形缓冲挤掉）：整段重推，reset=true")
    void anchorLostResets() {
        LogcatTail.Result r = LogcatTail.diff(C + "\n" + D, A);
        assertThat(r.lines()).containsExactly(C, D);
        assertThat(r.anchor()).isEqualTo(D);
        assertThat(r.reset()).isTrue();
    }

    @Test
    @DisplayName("★ dump 为空（或只有空白行）：不推任何内容，锚点原样保留（需求 #64 改的就是这条）")
    void emptyDump() {
        assertThat(LogcatTail.diff("", A).lines()).isEmpty();
        assertThat(LogcatTail.diff(null, A).lines()).isEmpty();
        assertThat(LogcatTail.diff("   ", A).lines()).isEmpty();
        assertThat(LogcatTail.diff("   \n\n ", A).lines()).isEmpty();
        assertThat(LogcatTail.diff("", A).total()).isZero();

        /*
         * 这里原来断言的是「锚点保持 null，下次仍按首次处理」—— 写测试时的理由是
         * 「整段重推是安全的」。那条理由在一半的情况下不成立：
         * 空 dump 往往只是**这一次取文失败**，此时「整段」是空的，
         * 界面拿到 reset=true + 空内容就会把手里的日志全清掉 ——
         * 用户看到的就是「上报缓存隔几秒被自动清掉」（需求 #64 报的正是它）。
         * 现在改成：空 dump 一律什么都不做、锚点原样保留。
         */
        assertThat(LogcatTail.diff("", A).anchor()).isEqualTo(A);
        assertThat(LogcatTail.diff("", A).reset()).isFalse();
    }

    @Test
    @DisplayName("末尾换行不产生「多出来的一行」——这是最容易踩的一处")
    void trailingNewlineDoesNotBecomeALine() {
        LogcatTail.Result r = LogcatTail.diff(A + "\n" + B + "\n", null);
        assertThat(r.lines()).containsExactly(A, B);
        // 关键：锚点必须是 B 而不是空串。若把末尾空行当成一行，锚点会变成 ""，
        // 下一轮 diff 就找不到它 → 每轮都整段重推，界面上表现为「日志反复重刷」。
        assertThat(r.anchor()).isEqualTo(B);
        assertThat(r.anchor()).isNotEqualTo("");
    }

    @Test
    @DisplayName("中间的空行要保留：那可能是多行日志的分隔，不能一并削掉")
    void keepsInnerBlankLines() {
        LogcatTail.Result r = LogcatTail.diff(A + "\n\n" + B, null);
        assertThat(r.lines()).containsExactly(A, "", B);
    }

    @Test
    @DisplayName("行尾的 \\r 要去掉：带上它会让下一轮锚点匹配不上（adb 在 Windows 上会带回车）")
    void stripsCarriageReturn() {
        LogcatTail.Result first = LogcatTail.diff(A + "\r\n" + B + "\r\n", null);
        assertThat(first.lines()).containsExactly(A, B);
        assertThat(first.anchor()).isEqualTo(B);
        // 用刚才的锚点再取一次：必须命中，若没去掉 \r 这里会判成「锚点失效」而整段重推
        LogcatTail.Result again = LogcatTail.diff(A + "\n" + B + "\n" + C, first.anchor());
        assertThat(again.reset()).isFalse();
        assertThat(again.lines()).containsExactly(C);
    }

    @Test
    @DisplayName("锚点是空白串：按首次取文处理（界面还没内容时传空串很常见）")
    void blankAnchorTreatedAsFirst() {
        assertThat(LogcatTail.diff(A + "\n" + B, "").reset()).isTrue();
        assertThat(LogcatTail.diff(A + "\n" + B, "   ").reset()).isTrue();
    }

    @Test
    @DisplayName("整行重复时取最后一次出现：不漏推后面的新行")
    void duplicateAnchorUsesLastOccurrence() {
        // 真实 logcat 行含毫秒时间戳，整行重复不会发生；这里只是把行为钉住
        LogcatTail.Result r = LogcatTail.diff(A + "\n" + B + "\n" + A + "\n" + C, A);
        assertThat(r.lines()).containsExactly(C);
        assertThat(r.reset()).isFalse();
    }

    @Test
    @DisplayName("连续两轮轮询：拼起来正好等于原文（不多不少）")
    void twoRoundsConcatenateExactly() {
        String full = String.join("\n", List.of(A, B, C, D));
        LogcatTail.Result r1 = LogcatTail.diff(A + "\n" + B, null);
        LogcatTail.Result r2 = LogcatTail.diff(full, r1.anchor());
        List<String> merged = new java.util.ArrayList<>(r1.lines());
        merged.addAll(r2.lines());
        assertThat(merged).containsExactly(A, B, C, D);
    }

    // ---------- 空 dump：绝不能当成「设备缓冲空了」（2026-10-06，需求 #64）----------

    @Test
    @DisplayName("★ 空 dump（取文失败）：什么都不推、锚点原样保留 —— 这正是「缓存被自动清掉」的病根")
    void emptyDumpKeepsAnchorAndDoesNotReset() {
        LogcatTail.Result r = LogcatTail.diff("", B);
        assertThat(r.lines()).isEmpty();
        assertThat(r.anchor()).isEqualTo(B);
        assertThat(r.reset()).isFalse();
        assertThat(r.total()).isZero();
    }

    @Test
    @DisplayName("★ 空 dump 也绝不能判成首次取文（否则界面会被替换成空）")
    void emptyDumpIsNotTreatedAsFirstFetch() {
        LogcatTail.Result r = LogcatTail.diff("", null);
        assertThat(r.reset()).isFalse();
        assertThat(r.lines()).isEmpty();
        assertThat(r.anchor()).isNull();
    }

    @Test
    @DisplayName("空 dump 之后再来正常 dump：增量照常，没被空 dump 破坏")
    void emptyDumpDoesNotBreakFollowingRounds() {
        LogcatTail.Result empty = LogcatTail.diff("   \n\n ", B);
        assertThat(empty.reset()).isFalse();
        LogcatTail.Result next = LogcatTail.diff(A + "\n" + B + "\n" + C, empty.anchor());
        assertThat(next.lines()).containsExactly(C);
        assertThat(next.reset()).isFalse();
    }

    @Test
    @DisplayName("dump 为 null 与空串行为一致（都按「什么都没取到」处理）")
    void nullDumpBehavesLikeEmpty() {
        LogcatTail.Result r = LogcatTail.diff(null, B);
        assertThat(r.lines()).isEmpty();
        assertThat(r.anchor()).isEqualTo(B);
        assertThat(r.reset()).isFalse();
    }

    @Test
    @DisplayName("★ reset 一定伴随内容：任何返回 reset=true 的结果都不会是空行表")
    void resetAlwaysCarriesContent() {
        assertThat(LogcatTail.diff(A + "\n" + B, null).reset()).isTrue();
        assertThat(LogcatTail.diff(A + "\n" + B, null).lines()).isNotEmpty();
        assertThat(LogcatTail.diff(A + "\n" + B, "没这行").reset()).isTrue();
        assertThat(LogcatTail.diff(A + "\n" + B, "没这行").lines()).isNotEmpty();
    }
}
