package com.sudpanel.runner;

import com.sudpanel.adb.AdbClient;
import com.sudpanel.config.AppProperties;
import com.sudpanel.filter.LogFilter;
import com.sudpanel.locator.UiLocator;
import com.sudpanel.model.*;
import com.sudpanel.perf.ButtonGroupRepository;
import com.sudpanel.point.TestPointRepository;
import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RunServiceOwnershipTest {
    @TempDir java.nio.file.Path temporary;

    @Test void stoppedDatabaseStateRetainsOwnerUntilBackgroundHasFinished() throws Exception {
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:sqlite:" + temporary.resolve("owner.db")));
        AdbClient adb = mock(AdbClient.class);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        doAnswer(call -> { entered.countDown(); assertThat(release.await(5, TimeUnit.SECONDS)).isTrue(); return null; })
                .when(adb).logcatClear("fake-device");
        TestPointRepository points = mock(TestPointRepository.class);
        when(points.findEnabledByDirection(Direction.APPTOMG)).thenReturn(List.of(new TestPoint(1L,
                Direction.APPTOMG, "fake", 1, "fake-state", "fake-key", "fake point", "{}",
                TriggerType.AUTO, "fake", "", JudgeMode.AUTO, false, true, 1)));
        RunService service = new RunService(adb, mock(UiLocator.class), new LogFilter(), points,
                jdbc, mock(AppProperties.class), mock(ButtonGroupRepository.class));
        var executorField = RunService.class.getDeclaredField("executor"); executorField.setAccessible(true);
        ExecutorService executor = (ExecutorService) executorField.get(service);
        try {
            long id = ((Number) service.start(Direction.APPTOMG, "fake-device", List.of(1L), null).get("runId")).longValue();
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
            service.stop(id);
            assertThat(jdbc.queryForObject("SELECT status FROM test_run WHERE id=?", String.class, id)).isEqualTo("STOPPED");
            assertThat(service.hasExecutionOwner()).isTrue();
            release.countDown(); executor.shutdown(); assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
            assertThat(service.hasExecutionOwner()).isFalse();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM test_result WHERE run_id=?", Integer.class, id)).isEqualTo(1);
        } finally { release.countDown(); executor.shutdownNow(); }
    }

    @Test void rejectedSubmissionDoesNotLeakOwnershipOrRunningStatus() throws Exception {
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:sqlite:" + temporary.resolve("reject.db")));
        TestPointRepository points = mock(TestPointRepository.class);
        TestPoint point = mock(TestPoint.class); when(point.id()).thenReturn(1L);
        when(points.findEnabledByDirection(Direction.APPTOMG)).thenReturn(List.of(point));
        RunService service = new RunService(mock(AdbClient.class), mock(UiLocator.class), new LogFilter(), points,
                jdbc, mock(AppProperties.class), mock(ButtonGroupRepository.class));
        var field = RunService.class.getDeclaredField("executor"); field.setAccessible(true);
        ((ExecutorService) field.get(service)).shutdown();
        assertThatThrownBy(() -> service.start(Direction.APPTOMG, "fake-device", List.of(1L), null))
                .isInstanceOf(RejectedExecutionException.class);
        assertThat(service.hasExecutionOwner()).isFalse();
        assertThat(jdbc.queryForObject("SELECT status FROM test_run", String.class)).isEqualTo("ERROR");
    }
}
