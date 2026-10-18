package com.sudpanel.api;

/** 显式步骤延迟与有限轮询边界。未配置poll绝不重复发送。 */
public final class ApiStepPolicy {
    private ApiStepPolicy() {}
    public static void validate(ApiScenario.Step step) {
        if (step.delayMs() != null && (step.delayMs() < 0 || step.delayMs() > 3_600_000)) throw new IllegalArgumentException("步骤等待须为0～3600000毫秒");
        var poll = step.poll(); if (poll == null) return;
        if (poll.maxAttempts() == null || poll.maxAttempts() < 1 || poll.maxAttempts() > 100) throw new IllegalArgumentException("轮询次数须为1～100");
        if (poll.intervalMs() == null || poll.intervalMs() < 0 || poll.intervalMs() > 60000) throw new IllegalArgumentException("轮询间隔须为0～60000毫秒");
        if (poll.timeoutMs() == null || poll.timeoutMs() < 1 || poll.timeoutMs() > 3_600_000) throw new IllegalArgumentException("轮询总时限须为1～3600000毫秒");
        if (poll.untilAsserts() == null || ApiBodyAssert.parse(poll.untilAsserts()).isEmpty()) throw new IllegalArgumentException("轮询必须配置满足条件的断言");
        ApiBodyAssert.validateRules(poll.untilAsserts());
    }
}
