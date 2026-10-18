package com.sudpanel.api;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import com.sudpanel.web.ApiExecutionController;
import com.sudpanel.web.ApiTestController;
import com.sudpanel.web.GlobalExceptionHandler;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ApiBatchOverviewTest {
    @TempDir Path dir;
    ApiRepository repo;
    MockMvc mvc;
    @BeforeEach void setup() {
        repo = new ApiRepository(new JdbcTemplate(new DriverManagerDataSource("jdbc:sqlite:" + dir.resolve("batches.db"))));
        var runner = mock(ApiRunner.class);
        when(runner.progress(anyLong())).thenAnswer(call -> Map.of("batch", repo.getBatch(call.getArgument(0))));
        mvc = MockMvcBuilders.standaloneSetup(new ApiTestController(repo, runner), new ApiExecutionController(runner, repo))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }
    @Test void olderActiveBatchRemainsDiscoverableBeyondRecentHistoryLimit() throws Exception {
        var active = repo.createBatch("long-running", "ONCE", List.of(), 0, 1);
        for (int i = 0; i < 105; i++) {
            var done = repo.createBatch("recent-" + i, "ONCE", List.of(), 0, 1);
            repo.finishBatch(done.id(), "DONE");
        }
        assertThat(repo.listBatches()).hasSize(100).noneMatch(b -> b.id().equals(active.id()));
        mvc.perform(get("/api/apitest/batches").param("active", "true"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(active.id().intValue()));
        repo.finishBatch(active.id(), "STOPPED");
        mvc.perform(get("/api/apitest/batches").param("active", "true"))
                .andExpect(status().isOk()).andExpect(content().json("[]"));
    }
    @Test void missingBatchReturnsNotFoundForAllProgressAndDetailReaders() throws Exception {
        for (var suffix : List.of("", "/progress", "/executions")) {
            mvc.perform(get("/api/apitest/batches/999999" + suffix))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.message").value("批次不存在：999999"));
        }
    }
}
