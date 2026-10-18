package com.sudpanel.perf;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.sudpanel.config.AppProperties;
import com.sudpanel.web.PerfController;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PerfRosterLimitsTest {
    @Test void totalCountUsesCombinedBoundAndDoesNotOverflow() {
        AiPlayers.requireValidCounts(0, 1);
        AiPlayers.requireValidCounts(1, 63);
        AiPlayers.requireValidCounts(64, 0);
        for (int[] counts : new int[][] {{0, 0}, {1, 64}, {64, 64}, {-1, 4}, {Integer.MAX_VALUE, Integer.MAX_VALUE}}) {
            assertThatThrownBy(() -> AiPlayers.requireValidCounts(counts[0], counts[1]))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("1～64");
        }
        assertThat(AiPlayers.withRoster("{}", new AiPlayers.RosterSpec(Integer.MAX_VALUE,
                Integer.MAX_VALUE, null, null)).applied()).isFalse();
    }

    @Test void customBodyHonorsTheSameCountBoundWithoutRewritingIt() {
        AiPlayers.requireValidBodyCount("{\"data\":{\"user_infos\":[{\"uid\":\"{{uid}}\"}]}}");
        assertThatThrownBy(() -> AiPlayers.requireValidBodyCount("{\"data\":{\"user_infos\":[]}}"))
                .isInstanceOf(IllegalStateException.class);
        String oversized = "{\"data\":{\"user_infos\":[" + "{},".repeat(64) + "{}]}}";
        assertThatThrownBy(() -> AiPlayers.requireValidBodyCount(oversized)).isInstanceOf(IllegalStateException.class);
    }

    @Test void savingAnExplicitAiCountIncludesDefaultHumanAndGlobalInheritance() {
        PerfRepository repo = mock(PerfRepository.class);
        when(repo.getGame(1L)).thenReturn(Map.of("id", 1L));
        PerfController controller = new PerfController(mock(PerfRunService.class), repo,
                mock(PerfReportService.class), mock(AppProperties.class));
        assertThatThrownBy(() -> controller.updateGameIds(1, config(null, 64)))
                .isInstanceOf(IllegalStateException.class);
        when(repo.getSetting(PerfRepository.SETTING_AI_COUNT)).thenReturn("63");
        assertThatThrownBy(() -> controller.updateGameIds(1, config(2, null)))
                .isInstanceOf(IllegalStateException.class);
        controller.updateGameIds(1, config(1, null));
    }

    @Test void globalAiCountWithDefaultOneHumanCannotExceedTotal64() {
        PerfController controller = new PerfController(mock(PerfRunService.class), mock(PerfRepository.class),
                mock(PerfReportService.class), mock(AppProperties.class));
        assertThatThrownBy(() -> controller.saveAiRoomConfig(new PerfController.AiRoomConfigRequest(
                null, null, null, null, "64", null))).isInstanceOf(IllegalStateException.class);
    }

    @Test void globalAiCountRetainsPositiveOnlyContract() {
        PerfController controller = new PerfController(mock(PerfRunService.class), mock(PerfRepository.class),
                mock(PerfReportService.class), mock(AppProperties.class));
        assertThatThrownBy(() -> controller.saveAiRoomConfig(new PerfController.AiRoomConfigRequest(
                null, null, null, null, "0", null))).isInstanceOf(IllegalStateException.class).hasMessageContaining("大于 0");
    }

    private PerfController.GameIdsRequest config(Integer humans, Integer ais) {
        return new PerfController.GameIdsRequest(null, null, null, humans, ais, null, null, null, null);
    }
}
