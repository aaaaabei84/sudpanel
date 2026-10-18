package com.sudpanel.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** 可持久冻结的执行输入；内部环境含凭据，必须加密保存且不能直接作为REST返回值。 */
public record ApiExecutionPlan(int schema, String kind, String name, String capturedAt,
        List<Step> steps, List<Row> rows, int repeatCount, int intervalSeconds,
        Long scenarioId, String scenarioName, Long sourceBatchId) {
    public ApiExecutionPlan {
        if (schema != 1 || steps == null || steps.isEmpty() || rows == null || rows.isEmpty())
            throw new IllegalArgumentException("执行计划损坏或不支持该版本");
        steps = List.copyOf(steps); rows = List.copyOf(rows);
    }
    public record Step(int seq, ApiSpec spec, ApiRequestEngine.Environment environment, ApiScenario.Step scenarioStep) {}
    public record Row(int dataRowIndex, int dataIteration, Map<String, String> data, List<Integer> selectedSeqs) {
        public Row { data = data == null ? Map.of() : Map.copyOf(data); selectedSeqs = selectedSeqs == null ? List.of() : List.copyOf(selectedSeqs); }
        public boolean includes(int seq) { return selectedSeqs.isEmpty() || selectedSeqs.contains(seq); }
    }
    public boolean dataDriven() { return "DATA".equals(kind) || rows.stream().anyMatch(r -> !r.data().isEmpty()); }
    public static Row emptyRow() { return new Row(1, 1, Map.of(), List.of()); }
}
