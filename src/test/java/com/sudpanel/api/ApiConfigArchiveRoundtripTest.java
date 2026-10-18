package com.sudpanel.api;
import static org.assertj.core.api.Assertions.*;
import com.sudpanel.api.configarchive.ApiConfigArchive;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
class ApiConfigArchiveRoundtripTest {
    @TempDir Path dir;
    @Test void nativeRoundtripPreservesNullableEnvironmentStrictnessAllOptionsAndStepPolicies()throws Exception{
        var jdbc=new JdbcTemplate(new DriverManagerDataSource("jdbc:sqlite:"+dir.resolve("roundtrip.db")));var repo=new ApiRepository(jdbc);var archive=new ApiConfigArchive(repo,jdbc);var json=new ObjectMapper();
        repo.setEnvStrict(1,true);var disabled=repo.createEnv("disabled");repo.setEnvStrict(disabled.id(),false);var inherited=repo.createEnv("inherited");
        var options=new ApiRequestOptions("URLENCODED",List.of(new ApiRequestOptions.Param("repeat","a",true),new ApiRequestOptions.Param("repeat","b",false)),new ApiRequestOptions.Auth("BASIC",null,"fictional-user","fictional-roundtrip-only",null,null,null),List.of(new ApiExtractor("captured","JSON","/id",true)),List.of(new ApiRequestOptions.Param("form","special + &",true)),new ApiRequestOptions.Transport("NEVER","HTTP","127.0.0.1",12345,"ENV",4096),true);
        var c=repo.createCase(new ApiSpec(null,"full options","POST","https://example.invalid",null,"",null,"200","0",1500,false,true,null,"[]",options));
        var poll=new ApiScenario.Poll(3,20L,1000L,"[{\"path\":\"/ready\",\"op\":\"equals\",\"expect\":\"true\"}]");var scenario=repo.saveScenario(null,new ApiScenario(null,"delayed poll",List.of(new ApiScenario.Step("s1","first",c.id(),disabled.id(),"CONTINUE",false,null,true,123L,poll))));
        var imported=archive.commit(json.writeValueAsString(archive.export(true)));
        assertThat(repo.getEnv(imported.environmentIds().get(1L)).strictVariables()).isTrue();assertThat(repo.getEnv(imported.environmentIds().get(disabled.id())).strictVariables()).isFalse();assertThat(repo.getEnv(imported.environmentIds().get(inherited.id())).strictVariables()).isNull();
        assertThat(repo.getCase(imported.caseIds().get(c.id())).options()).isEqualTo(options);var step=repo.getScenario(imported.scenarioIds().get(scenario.id())).steps().getFirst();assertThat(step.delayMs()).isEqualTo(123L);assertThat(step.poll()).isEqualTo(poll);assertThat(step.independent()).isTrue();assertThat(step.extractors()).isNull();
        var old=json.valueToTree(archive.export(true));for(var env:old.path("environments"))((ObjectNode)env).remove("strictVariables");var legacy=archive.commit(old.toString());for(Long id:legacy.environmentIds().values())assertThat(repo.getEnv(id).strictVariables()).isNull();
        var invalid=json.valueToTree(archive.export(true));((ObjectNode)invalid.path("scenarios").get(0).path("steps").get(0)).put("delayMs",-1);assertThat(archive.preview(invalid.toString()).valid()).isFalse();
    }
}
