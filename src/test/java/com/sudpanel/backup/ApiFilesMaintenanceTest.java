package com.sudpanel.backup;

import static org.assertj.core.api.Assertions.*;
import com.sudpanel.api.*;
import com.sudpanel.api.configarchive.ApiConfigArchive;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.time.Instant;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.*;

class ApiFilesMaintenanceTest {
    @TempDir Path dir;Path db;JdbcTemplate jdbc;ApiRepository repo;ApiFileStore files;MaintenanceService maintenance;MaintenanceGate gate;
    final byte[] bytes={0,(byte)255,1,2,3,(byte)128};
    @BeforeEach void setup(){db=dir.resolve("test.db");jdbc=new JdbcTemplate(new DriverManagerDataSource("jdbc:sqlite:"+db));repo=new ApiRepository(jdbc);files=new ApiFileStore(jdbc,db);gate=new MaintenanceGate();maintenance=new MaintenanceService(jdbc,db,gate,()->false);maintenance.fileStore(files);}
    ApiSpec spec(ApiFileStore.FileInfo f){return new ApiSpec(null,"file template","POST","https://example.invalid/never-send","{}",null,null,"200","",1000,false,true,null,null,new ApiRequestOptions("BINARY",null,null,null,null,null,true,new ApiRequestOptions.FileBody(f.fileId(),null,null),null));}
    ApiFileStore.FileInfo response(){return files.save("RESPONSE","binary.bin","application/octet-stream",bytes);}
    void history(ApiFileStore.FileInfo f){jdbc.update("INSERT INTO api_exec(case_name,round_no,seq,method,url,verdict,elapsed_ms,ts,metadata) VALUES('fixture',1,1,'GET','https://example.invalid','PASS',1,'2020-01-01T00:00:00Z',?)","{\"responseBody\":{\"file\":{\"fileId\":\""+f.fileId()+"\"}}}");}
    @Test void completeBackupContainsBlobAndRestoresMatchingBytesDespiteCacheDrift()throws Exception{
        var f=files.upload(new ByteArrayInputStream(bytes),"original.bin","application/octet-stream");repo.createCase(spec(f));var b=maintenance.backup();assertThat(b.counts()).containsEntry("api_file",1L);
        jdbc.update("UPDATE api_file SET payload=?,sha256=?,bytes=? WHERE id=?",new byte[]{8},ApiFileStore.sha(new byte[]{8}),1,f.fileId());
        var preview=maintenance.preview(Files.readAllBytes(maintenance.backupFile(b.id())));maintenance.stage(preview.id(),preview.sha256());
        try(var restored=OfflineRestore.open(db)){
            var rjdbc=new JdbcTemplate(restored);var rfiles=new ApiFileStore(rjdbc,db);try(var lease=rfiles.acquire(f.fileId())){assertThat(lease.bytes()).isEqualTo(bytes);assertThat(lease.info().sha256()).isEqualTo(ApiFileStore.sha(bytes));}
        }
        assertThat(maintenance.restoreStatus().toString()).contains("RESTORED");
    }
    @Test void invalidBlobHashRejectsBackupBeforeAnyRestore()throws Exception{
        var f=response();jdbc.update("UPDATE api_file SET sha256=? WHERE id=?","0".repeat(64),f.fileId());assertThatThrownBy(()->maintenance.backup()).hasMessageContaining("SHA256");assertThat(maintenance.staged()).isFalse();
    }
    @Test void cleanupPreviewIncludesFileBytesArchiveAndAtomicGc()throws Exception{
        var f=response();history(f);var p=maintenance.cleanupPreview(new MaintenanceService.CleanupRequest("API","2021-01-01T00:00:00Z",true));assertThat(p.counts()).containsEntry("api_file",1L).containsEntry("api_exec",1L);
        var cleaned=maintenance.cleanupCommit(p.token());assertThat(cleaned.counts()).containsEntry("api_file",1L);assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM api_file",Integer.class)).isZero();assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM api_exec",Integer.class)).isZero();
        var archive=new ObjectMapper().readTree(Files.readString(maintenance.archiveFile(cleaned.archiveId())));assertThat(archive.path("tables").path("api_file").get(0).path("payload").binaryValue()).isEqualTo(bytes);
    }
    @Test void remainingHistoryReferenceAndLeaseKeepOriginalFiles()throws Exception{
        var f=response();history(f);history(f);jdbc.update("UPDATE api_exec SET ts=? WHERE id=(SELECT MAX(id) FROM api_exec)",Instant.now().toString());var p=maintenance.cleanupPreview(new MaintenanceService.CleanupRequest("API","2021-01-01T00:00:00Z",true));var cleaned=maintenance.cleanupCommit(p.token());assertThat(cleaned.counts()).containsEntry("api_file",0L);assertThat(files.info(f.fileId()).bytes()).isEqualTo(bytes.length);
        jdbc.update("DELETE FROM api_exec");try(var lease=files.acquire(f.fileId())){assertThat(files.cleanupOrphans()).isZero();}assertThat(files.cleanupOrphans()).isEqualTo(1);
    }
    @Test void configExportHasNoBytesAndCrossMachinePreviewNamesMissingReferences()throws Exception{
        var f=files.upload(new ByteArrayInputStream(bytes),"fake.bin",null);repo.createCase(spec(f));var configs=new ApiConfigArchive(repo,jdbc);String content=new ObjectMapper().writeValueAsString(configs.export(false));assertThat(content).doesNotContain(Base64.getEncoder().encodeToString(bytes),"payload").contains("不包含文件原字节");
        jdbc.update("DELETE FROM api_file");var p=configs.preview(content);assertThat(p.valid()).isTrue();assertThat(p.warnings()).anyMatch(w->w.contains(f.fileId())&&w.contains("待补传"));
    }
    @Test void remoteClientsCannotReadOrUploadPrivateManagedFiles()throws Exception{
        var filter=new MaintenanceWriteFilter(gate,maintenance);for(String method:List.of("GET","POST","DELETE")){var req=new MockHttpServletRequest(method,"/api/apitest/files");req.setRemoteAddr("192.0.2.11");var resp=new MockHttpServletResponse();filter.doFilter(req,resp,(a,b)->fail("remote file access must be blocked"));assertThat(resp.getStatus()).isEqualTo(403);}
    }
}
