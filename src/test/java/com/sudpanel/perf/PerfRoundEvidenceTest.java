package com.sudpanel.perf;
import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
class PerfRoundEvidenceTest {
 @Test void escapedJsonAndLongStringIdsArePreservedAndConflictsNeverChooseLast(){
  var window=new PerfLoopLogWindow("old gameRoundId=OLD\nboundary");String id="12345678901234567890123456789012345678";
  window.accept("old gameRoundId=OLD\nboundary\n10-14 12:00:00.123 report {\\\"game_round_id\\\":\\\""+id+"\\\"}");
  assertThat(window.reliableRoundId()).isEqualTo(id);assertThat(window.roundEvidence()).containsEntry("state","UNIQUE").containsEntry("firstLogTimestamp","10-14 12:00:00.123");
  window.accept("old gameRoundId=OLD\nboundary\n10-14 12:00:00.123 report {\\\"game_round_id\\\":\\\""+id+"\\\"}\nnew gameRoundId=OTHER");
  assertThat(window.reliableRoundId()).isNull();assertThat(window.roundEvidence()).containsEntry("state","CONFLICT");
 }
 @Test void missingIdAndFailedBoundaryHaveDifferentStructuredReasons(){
  var good=new PerfLoopLogWindow("boundary");good.accept("boundary\nloaded");assertThat(good.roundEvidence()).containsEntry("state","MISSING");
  var failed=new PerfLoopLogWindow(null);failed.accept("gameRoundId=OLD");assertThat(failed.reliableRoundId()).isNull();assertThat(failed.roundEvidence()).containsEntry("state","INCOMPLETE");
  good.accept("new-boundary gameRoundId=STALE");assertThat(good.reliableRoundId()).isNull();assertThat(good.roundEvidence()).containsEntry("state","INCOMPLETE");
 }
 @Test void oversizedIdDoesNotBecomeAuthoritative(){var evidence=new PerfRoundIdEvidence();evidence.observe("gameRoundId="+"a".repeat(5000));assertThat(evidence.reliableId(false)).isNull();assertThat(evidence.summary(false)).containsEntry("state","CONFLICT");}
}
