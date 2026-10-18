package com.sudpanel.perf;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class PerfLoopLogWindowTest {
    @Test void oldIdAndOldEndKeywordAreExcluded() {
        String old = "old gameRoundId=PREVIOUS\nold end-keyword\nboundary";
        PerfLoopLogWindow window = new PerfLoopLogWindow(old);
        String fresh = window.accept(old + "\nnew gameRoundId=CURRENT");
        assertThat(fresh).isEqualTo("new gameRoundId=CURRENT").doesNotContain("PREVIOUS", "end-keyword");
        assertThat(PerfRunService.extractGameRoundId(fresh)).isEqualTo("CURRENT");
    }

    @Test void missingAnchorDiscardsWholeSnapshotThenResumesOnlyNewLines() {
        PerfLoopLogWindow window = new PerfLoopLogWindow("old-boundary");
        assertThat(window.accept("other gameRoundId=STALE\nnew-boundary")).isEmpty();
        assertThat(window.incomplete()).isTrue();
        assertThat(window.accept("other gameRoundId=STALE\nnew-boundary\nfresh gameRoundId=CURRENT"))
                .isEqualTo("fresh gameRoundId=CURRENT");
    }

    @Test void failedInitialCaptureNeverAcceptsTheFirstWholeDumpAsCurrent() {
        PerfLoopLogWindow window = new PerfLoopLogWindow(null);
        assertThat(window.accept("possibly-old gameRoundId=STALE")).isEmpty();
        assertThat(window.accept("possibly-old gameRoundId=STALE\nfresh")).isEqualTo("fresh");
        assertThat(window.incomplete()).isTrue();
    }

    @Test void successfulEmptyBufferAcceptsFreshLines() {
        PerfLoopLogWindow window = new PerfLoopLogWindow("");
        assertThat(window.accept("new gameRoundId=CURRENT")).isEqualTo("new gameRoundId=CURRENT");
        assertThat(window.incomplete()).isFalse();
    }

    @Test void failedOrEmptyReadsKeepTheAnchorAndDoNotReplayLines() {
        PerfLoopLogWindow window = new PerfLoopLogWindow("boundary");
        assertThat(window.accept(null)).isEmpty();
        assertThat(window.accept("")).isEmpty();
        assertThat(window.accept("boundary\nnew")).isEqualTo("new");
        assertThat(window.accept("boundary\nnew")).isEmpty();
    }

    @Test void fivePointShortLoopHasAnInitialReadAndSupportsFinalLateId() {
        assertThat(IntStream.range(0, 5).filter(i -> PerfLoopLogWindow.shouldReadAt(i, 10)).count()).isEqualTo(1);
        PerfLoopLogWindow window = new PerfLoopLogWindow("boundary");
        assertThat(window.accept("boundary\nloading")).isEqualTo("loading");
        String finalRead = window.accept("boundary\nloading\nready gameRoundId=12345678901234567890123456789012345678");
        assertThat(PerfRunService.extractGameRoundId(finalRead)).isEqualTo("12345678901234567890123456789012345678");
    }
}
