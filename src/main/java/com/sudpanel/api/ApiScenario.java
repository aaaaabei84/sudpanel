package com.sudpanel.api;

import java.util.List;

/** 数组顺序就是执行顺序。independent 步骤可在前置 STOP 后独立执行。 */
public record ApiScenario(Long id, String name, List<Step> steps) {
    public record Step(String key, String name, Long caseId, Long envId,
                       String onFailure, boolean cleanup, List<ApiExtractor> extractors, boolean independent, Long delayMs, Poll poll) {
        public Step(String key, String name, Long caseId, Long envId, String onFailure, boolean cleanup, List<ApiExtractor> extractors, boolean independent) {
            this(key, name, caseId, envId, onFailure, cleanup, extractors, independent, null, null);
        }
        public Step(String key, String name, Long caseId, Long envId, String onFailure,
                    boolean cleanup, List<ApiExtractor> extractors) {
            this(key, name, caseId, envId, onFailure, cleanup, extractors, false);
        }
    }
    public record Poll(Integer maxAttempts, Long intervalMs, Long timeoutMs, String untilAsserts) {}
}
