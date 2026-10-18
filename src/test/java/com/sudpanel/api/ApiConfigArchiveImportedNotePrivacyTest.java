package com.sudpanel.api;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sudpanel.api.configarchive.ApiConfigArchive;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** 本机虚构配置；默认分享省略导入原文，私密迁移保留且不修改源配置。 */
class ApiConfigArchiveImportedNotePrivacyTest {
    @TempDir Path dir;
    JdbcTemplate jdbc;
    ApiRepository repo;
    ApiConfigArchive archive;
    long caseId, groupId;
    static final String LITERAL="FICTIONAL_IMPORTED_SCRIPT_LITERAL";

    @BeforeEach void setup() {
        jdbc=new JdbcTemplate(new DriverManagerDataSource("jdbc:sqlite:"+dir.resolve("notes.db")));
        repo=new ApiRepository(jdbc);archive=new ApiConfigArchive(repo,jdbc);
        String source="""
                {"info":{"name":"fictional collection","schema":"https://schema.getpostman.com/json/collection/v2.1.0/collection.json"},
                 "event":[{"listen":"prerequest","script":{"exec":["const credential='FICTIONAL_IMPORTED_SCRIPT_LITERAL';"]}}],
                 "item":[{"name":"fictional request","request":"https://example.invalid/fixture"}]}
                """;
        var result=new ApiImportService(repo,jdbc).commit(new ApiImportService.CommitRequest("POSTMAN",source,null,null,1L));
        caseId=result.caseIds().getFirst();groupId=repo.getCase(caseId).groupId();
    }

    private void edit(String note,Long group) {
        var c=repo.getCase(caseId);
        repo.updateCase(caseId,new ApiSpec(c.id(),c.name(),c.method(),c.url(),c.headers(),c.body(),note,
                c.expectStatus(),c.expectCode(),c.timeoutMs(),c.insecure(),c.enabled(),group,c.asserts(),c.options()));
    }
    private ApiSpec exported(boolean includeSecrets) {
        return archive.export(includeSecrets).cases().stream().filter(c->c.id()==caseId).findFirst().orElseThrow();
    }

    @Test void leadingWhitespaceOrUserExplanationCannotExposeImportedScript() {
        String original=repo.getCase(caseId).note();
        for(String before:List.of("\n\n","用户补充说明\n")) {
            edit(before+original,groupId);
            assertThat(exported(false).note()).doesNotContain(LITERAL,ApiInterchange.POSTMAN_NOTE_PREFIX);
            assertThat(exported(true).note()).isEqualTo(before+original);
        }
    }

    @Test void groupSourceProtectsNotesEvenAfterEditableMarkerIsRemoved() {
        String edited=repo.getCase(caseId).note().replace(ApiInterchange.POSTMAN_NOTE_PREFIX,"");
        edit(edited,groupId);
        var source=repo.getCaseGroup(groupId);
        repo.saveGroupImportInfo(groupId,source.importPath(),null);
        assertThat(exported(false).note()).doesNotContain(LITERAL);
        repo.saveGroupImportInfo(groupId,null,source.importNote());
        assertThat(exported(false).note()).doesNotContain(LITERAL);
        assertThat(exported(true).note()).isEqualTo(edited);
    }

    @Test void movingOutOfImportedGroupStillProtectsMarkerAnywhereInNote() {
        long normal=repo.createCaseGroup("fictional manual group",1L).id();
        String edited="用户新说明\n"+repo.getCase(caseId).note();
        edit(edited,normal);
        assertThat(exported(false).note()).doesNotContain(LITERAL);
        edit(edited,null);
        assertThat(exported(false).note()).doesNotContain(LITERAL);
        assertThat(exported(true).note()).isEqualTo(edited);
    }

    @Test void ordinaryManualNotesRemainInDefaultShare() {
        long normal=repo.createCaseGroup("ordinary group",1L).id();
        String note="\n人工维护说明：保留此业务说明和格式。\n";
        edit(note,normal);
        assertThat(exported(false).note()).isEqualTo(note);
        edit(note,null);
        assertThat(exported(false).note()).isEqualTo(note);
    }

    @Test void explicitPrivateRoundtripKeepsEditedRawNotesAndGroupSource() throws Exception {
        String edited="人工说明\n"+repo.getCase(caseId).note().replace(ApiInterchange.POSTMAN_NOTE_PREFIX,"");
        edit(edited,groupId);
        String content=new ObjectMapper().writeValueAsString(archive.export(true));
        assertThat(content).contains(LITERAL);
        var result=archive.commit(content);
        long newCase=result.caseIds().get(caseId),newGroup=result.groupIds().get(groupId);
        assertThat(repo.getCase(newCase).note()).isEqualTo(edited);
        assertThat(repo.getCaseGroup(newGroup).importPath()).isEqualTo(repo.getCaseGroup(groupId).importPath());
        assertThat(repo.getCaseGroup(newGroup).importNote()).isEqualTo(repo.getCaseGroup(groupId).importNote());
        assertThat(new ObjectMapper().writeValueAsString(archive.export(false))).doesNotContain(LITERAL);
    }

    @Test void sharingOnlyProjectsValuesAndNeverMutatesDatabase() {
        String original=repo.getCase(caseId).note();edit("补充\n"+original,groupId);
        Map<String,List<Map<String,Object>>> before=snapshot();
        archive.export(false);archive.export(true);
        assertThat(snapshot()).isEqualTo(before);
        assertThat(repo.getCase(caseId).note()).contains(LITERAL);
    }
    private Map<String,List<Map<String,Object>>> snapshot() {
        var all=new LinkedHashMap<String,List<Map<String,Object>>>();
        for(String table:jdbc.queryForList("SELECT name FROM sqlite_master WHERE type='table' AND name LIKE 'api_%' ORDER BY name",String.class)) {
            assertThat(table).matches("[A-Za-z0-9_]+");
            all.put(table,jdbc.queryForList("SELECT * FROM "+table+" ORDER BY rowid"));
        }
        return all;
    }
}
