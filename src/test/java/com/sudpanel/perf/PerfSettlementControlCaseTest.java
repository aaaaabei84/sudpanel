package com.sudpanel.perf;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 冻结控制用例不得被误选成只读结算查询，拒绝发生在任何真实执行之前。 */
class PerfSettlementControlCaseTest {
    @TempDir Path directory;
    @Test void rejectsAllFrozenControlCasesBeforeSendingOrRecordingQuery() throws Exception {
        var fixture = new PerfFrozenPlanTest(); fixture.dir = directory; fixture.setup();
        long run = fixture.start(), loop = fixture.repo.insertLoop(run, fixture.game, 1);
        var plan = fixture.repo.plan(run); fixture.repo.markLoopPlan(loop, plan.hash());
        var window = new PerfLoopLogWindow("boundary");
        window.accept("boundary\nnew gameRoundId=12345678901234567890123456789012345678");
        fixture.repo.markLoopRoundEvidence(loop, window);
        var evidence = new PerfEvidenceRepository(fixture.jdbc);
        var service = new PerfSettlementService(fixture.repo, evidence, fixture.api, fixture.runner);
        var rules = new PerfSettlementService.Rules("data", "id", "room", "game", "players", "uid", "time", "ISO", "code", List.of("0"), List.of("202"), List.of("404"), "EXACT", 0L);
        clearInvocations(fixture.runner);
        for (long caseId : List.of(1L, 2L, 3L, 4L)) {
            assertThatThrownBy(() -> service.check(loop, new PerfSettlementService.Request(caseId, rules)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("结算");
        }
        verifyNoInteractions(fixture.runner);
        assertThat(fixture.repo.settlementChecks(loop)).isEmpty();
        assertThat(evidence.runSteps(run)).isEmpty();
    }
}
