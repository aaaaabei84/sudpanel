package com.sudpanel.api;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(8)
class SafeRegexTest {
    @Test void pathologicalBacktrackingFailsWithinBudgetAndKeepsActualValue() {
        String value = "a".repeat(50_000) + "!";
        long started = System.nanoTime();
        var judged = ApiBodyAssert.judgeAll(value, List.of(new ApiBodyAssert.Rule("", "regex", "(a+)+$")));
        assertThat(Duration.ofNanos(System.nanoTime() - started).toMillis()).isLessThan(1500);
        assertThat(judged.getFirst().ok()).isFalse();
        assertThat(judged.getFirst().failure().message()).contains("200ms", "实际");
        assertThat(judged.getFirst().actual()).startsWith("\"aaa");
        assertThat(judged.getFirst().actualTruncated()).isTrue();
    }

    @Test void subsequentNormalRegexAndContainsStillWork() {
        var values = ApiBodyAssert.judgeAll("a".repeat(50_000) + "!", List.of(
                new ApiBodyAssert.Rule("", "regex", "(a+)+$"),
                new ApiBodyAssert.Rule("", "regex", "a{3}"),
                new ApiBodyAssert.Rule("", "contains", "!")));
        assertThat(values).extracting(ApiBodyAssert.Judged::ok).containsExactly(false, true, true);
        assertThat(SafeRegex.find("[A-Z]{2}[0-9]{2}", "prefix AB12 suffix", SafeRegex.judgementDeadline()).matched()).isTrue();
    }

    @Test void allRegexRulesShareOneJudgementBudget() {
        var rules = new ArrayList<ApiBodyAssert.Rule>();
        for (int i = 0; i < 12; i++) rules.add(new ApiBodyAssert.Rule("", "regex", "(a+)+$"));
        long started = System.nanoTime();
        var judged = ApiBodyAssert.judgeAll("a".repeat(50_000) + "!", rules);
        assertThat(Duration.ofNanos(System.nanoTime() - started).toMillis()).isLessThan(2000);
        assertThat(judged).allMatch(result -> !result.ok());
        assertThat(judged.getLast().failure().message()).contains("总预算", "1000ms");
    }

    @Test void wildcardValuesReceiveTheOriginalBudgetRatherThanAFreshBudgetPerNode() throws Exception {
        // 模拟取单个值也消耗时间；若每个[*]节点重置总期限，则全部会假通过。
        List<JsonNode> nodes = new ArrayList<>();
        for (int i = 0; i < 6; i++) nodes.add(new TextNode("a") {
            @Override public String asText() {
                try { Thread.sleep(220); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                return "a";
            }
        });
        Method apply = ApiBodyAssert.class.getDeclaredMethod("apply", ApiBodyAssert.Rule.class, String.class,
                List.class, boolean.class, long.class);
        apply.setAccessible(true);
        long deadline = SafeRegex.judgementDeadline();
        ApiBodyAssert.Failure failure = (ApiBodyAssert.Failure) apply.invoke(null,
                new ApiBodyAssert.Rule("values[*]", "regex", "a"), "regex", nodes, true, deadline);
        assertThat(failure).isNotNull();
        assertThat(failure.message()).contains("总预算", "第 ", "个元素");
    }

    @Test void subsequencesShareTheSameExpiredBudget() {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(20);
        var budget = new SafeRegex.Budget(deadline, deadline);
        CharSequence sequence = new SafeRegex.DeadlineCharSequence("abc", 0, 3, budget).subSequence(1, 3);
        while (System.nanoTime() < deadline) Thread.onSpinWait();
        assertThatThrownBy(sequence::length).isInstanceOf(RuntimeException.class).hasMessageContaining("总预算");
    }

    @Test void longPatternsAreRejectedBeforeCompilingEvenWhenTheyContainTemplates() {
        String huge = "a".repeat(4097);
        assertThatThrownBy(() -> SafeRegex.validate(huge)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("4096");
        String raw = "[{\"op\":\"regex\",\"expect\":\"" + huge + "{{data.x}}\"}]";
        assertThatThrownBy(() -> ApiBodyAssert.validateRules(raw)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("4096");
        assertThat(SafeRegex.find(huge, "a", SafeRegex.judgementDeadline()).error()).contains("4096");
    }

    @Test void deepCompileAndMatchCannotOverflowTheCaller() {
        String nested = "(".repeat(2000) + "a" + ")".repeat(2000);
        SafeRegex.Result compiled = SafeRegex.find(nested, "a", SafeRegex.judgementDeadline());
        // 语法合法的深层分组在大栈/JIT状态下可以编译成功；不能把成功当成缺陷。
        if (!compiled.matched()) assertThat(compiled.error()).contains("正则");
        SafeRegex.Result matched = SafeRegex.find("(a|aa)+$", "a".repeat(50_000), SafeRegex.judgementDeadline());
        assertThat(matched.matched()).isFalse();
        assertThat(matched.error()).containsAnyOf("嵌套过深", "超过");
    }
    @Test void compileStackOverflowBecomesConfigurationFailureOnASmallStack() throws Exception {
        var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        Thread smallStack = new Thread(null, () -> {
            try { SafeRegex.validate("(".repeat(2000) + "a" + ")".repeat(2000)); }
            catch (Throwable error) { failure.set(error); }
        }, "regex-small-stack", 128 * 1024);
        smallStack.setDaemon(true); smallStack.start(); smallStack.join(2000);
        assertThat(smallStack.isAlive()).isFalse();
        assertThat(failure.get()).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("正则");
    }

}
