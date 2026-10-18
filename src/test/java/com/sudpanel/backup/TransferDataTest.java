package com.sudpanel.backup;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sudpanel.api.ApiFileStore;
import com.sudpanel.api.ApiPlanVault;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.*;
import org.springframework.web.server.ResponseStatusException;

/** Complete transfer uses real isolated SQLite, authenticated vault envelopes and file bytes; never launches an app. */
class TransferDataTest {
    private static final String VERSION = "1.12.7", WORKSPACE = "fixture-workspace";
    @TempDir Path directory;
    private int sequence;
    private Fixture source, target;

    private record Fixture(Path db, JdbcTemplate jdbc, MaintenanceGate gate, AtomicBoolean busy,
                           MaintenanceService maintenance, TransferRestart restart, TransferService transfer,
                           ApiFileStore files, byte[] key) {}
    private record State(String database, byte[] key, TransferAssets.Inventory assets) {}

    @BeforeEach void setup() throws Exception {
        source = fixture("source", (byte)0x41, false);
        target = fixture("target", (byte)0x42, false);
        assets(source, "source"); assets(target, "target");
    }
    private Fixture fixture(String label, byte keyByte, boolean historicalOrder) throws Exception {
        Path db = directory.toRealPath().resolve(label + "/data/sudpanel.db");
        Files.createDirectories(db.getParent());
        var jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:sqlite:" + db));
        jdbc.execute(historicalOrder ? "CREATE TABLE api_env(name TEXT,id INTEGER PRIMARY KEY)" : "CREATE TABLE api_env(id INTEGER PRIMARY KEY,name TEXT)");
        jdbc.execute(historicalOrder ? "CREATE TABLE api_case(name TEXT,id INTEGER PRIMARY KEY)" : "CREATE TABLE api_case(id INTEGER PRIMARY KEY,name TEXT)");
        jdbc.execute("CREATE TABLE api_exec(id INTEGER PRIMARY KEY,metadata TEXT)");
        jdbc.execute("CREATE TABLE api_workspace(id TEXT PRIMARY KEY,payload TEXT NOT NULL)");
        jdbc.execute(historicalOrder ? "CREATE TABLE test_point(name TEXT,id INTEGER PRIMARY KEY)" : "CREATE TABLE test_point(id INTEGER PRIMARY KEY,name TEXT,steps TEXT)");
        jdbc.update("INSERT INTO api_env(id,name) VALUES(1,?)", label + " environment");
        jdbc.update("INSERT INTO api_case(id,name) VALUES(1,?)", label + " case");
        jdbc.update("INSERT INTO test_point(id,name) VALUES(1,?)", label + " point");
        byte[] key = new byte[32]; Arrays.fill(key, keyByte); Files.write(BackupFiles.key(db), key);
        jdbc.update("INSERT INTO api_workspace(id,payload) VALUES(?,?)", WORKSPACE, seal(key, "api-workspace", WORKSPACE, label + " private workspace"));
        var files = new ApiFileStore(jdbc, db);
        var gate = new MaintenanceGate(); var busy = new AtomicBoolean();
        var maintenance = new MaintenanceService(jdbc, db, gate, busy::get);
        maintenance.fileStore(files);
        var restart = mock(TransferRestart.class);
        var transfer = new TransferService(maintenance, gate, restart, VERSION);
        return new Fixture(db, jdbc, gate, busy, maintenance, restart, transfer, files, key);
    }
    private static byte[] text(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static String seal(byte[] key, String namespace, String id, String plain) throws Exception {
        byte[] nonce = new byte[12]; new java.security.SecureRandom().nextBytes(nonce);
        var cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, new javax.crypto.spec.SecretKeySpec(key, "AES"), new javax.crypto.spec.GCMParameterSpec(128, nonce));
        cipher.updateAAD(text("sudpanel-plan-v1:" + namespace + ":" + id));
        return "1." + Base64.getEncoder().encodeToString(nonce) + "." + Base64.getEncoder().encodeToString(cipher.doFinal(text(plain)));
    }
    private void assets(Fixture fixture, String label) throws Exception {
        for (String root : TransferAssets.ROOTS) {
            Path path = TransferAssets.target(fixture.db(), root).resolve(label + (root.equals("config") ? ".properties" : root.equals("screenshots") ? ".png" : ".json"));
            Files.createDirectories(path.getParent());
            Files.write(path, root.equals("screenshots") ? new byte[]{0,(byte)255,1,(byte)128,2} : text(label + " " + root));
        }
    }
    private static State state(Fixture fixture) throws Exception {
        return new State(BackupFiles.fingerprint(fixture.db()), Files.exists(BackupFiles.key(fixture.db())) ? Files.readAllBytes(BackupFiles.key(fixture.db())) : null, TransferAssets.inventory(fixture.db()));
    }
    private static void unchanged(Fixture fixture, State before) throws Exception {
        assertThat(BackupFiles.fingerprint(fixture.db())).isEqualTo(before.database());
        assertThat(Files.exists(BackupFiles.key(fixture.db())) ? Files.readAllBytes(BackupFiles.key(fixture.db())) : null).isEqualTo(before.key());
        assertThat(TransferAssets.inventory(fixture.db())).isEqualTo(before.assets());
    }
    private byte[] archive(Fixture fixture) throws Exception {
        var archive = fixture.transfer().exportArchive();
        Path path = fixture.transfer().archiveFile(archive.id());
        assertThat(Files.size(path)).isEqualTo(archive.bytes()); assertThat(BackupFiles.sha(path)).isEqualTo(archive.sha256());
        return Files.readAllBytes(path);
    }
    private static Map<String, byte[]> unzip(byte[] content) throws Exception {
        var entries = new LinkedHashMap<String, byte[]>();
        try (var input = new ZipInputStream(new ByteArrayInputStream(content))) {
            for (ZipEntry entry; (entry = input.getNextEntry()) != null;) {
                assertThat(entries.put(entry.getName(), input.readAllBytes())).isNull();
            }
        }
        return entries;
    }
    private static byte[] zip(Map<String, byte[]> entries) throws Exception {
        var buffer = new ByteArrayOutputStream();
        try (var output = new ZipOutputStream(buffer)) {
            for (var entry : entries.entrySet()) { output.putNextEntry(new ZipEntry(entry.getKey())); output.write(entry.getValue()); output.closeEntry(); }
        }
        return buffer.toByteArray();
    }
    private static void rebindOuter(Map<String, byte[]> entries) throws Exception {
        ObjectNode manifest = (ObjectNode)BackupFiles.JSON.readTree(entries.get("manifest.json"));
        for (JsonNode file : manifest.withArray("files")) {
            byte[] bytes = entries.get(file.path("path").asText());
            ((ObjectNode)file).put("bytes", bytes.length).put("sha256", TransferAssets.hash(bytes));
        }
        entries.put("manifest.json", BackupFiles.JSON.writeValueAsBytes(manifest));
    }
    private static void rebindInner(Map<String, byte[]> entries) throws Exception {
        ObjectNode manifest = (ObjectNode)BackupFiles.JSON.readTree(entries.get("manifest.json"));
        manifest.put("databaseSha256", TransferAssets.hash(entries.get("database.sqlite")));
        if (entries.containsKey("vault.key")) manifest.put("keySha256", TransferAssets.hash(entries.get("vault.key"))); else manifest.putNull("keySha256");
        entries.put("manifest.json", BackupFiles.JSON.writeValueAsBytes(manifest));
    }
    private static String operation() { return UUID.randomUUID().toString(); }
    private void rejectedWithoutChangingTarget(byte[] upload) throws Exception {
        State before = state(target);
        assertThatThrownBy(() -> target.transfer().importArchive(upload, operation())).isInstanceOf(RuntimeException.class);
        unchanged(target, before); assertThat(target.maintenance().staged()).isFalse();
        verify(target.restart(), never()).restartAfterResponse();
    }
    private static void reopen(Fixture fixture) throws Exception {
        try (var data = OfflineRestore.open(fixture.db()); var connection = data.getConnection()) { assertThat(connection.isClosed()).isFalse(); }
    }
    private static void assertSourceData(Fixture target, Fixture source, String label) throws Exception {
        assertThat(target.jdbc().queryForObject("SELECT name FROM api_case WHERE id=1", String.class)).isEqualTo(label + " case");
        assertThat(Files.readAllBytes(BackupFiles.key(target.db()))).isEqualTo(source.key());
        String sealed = target.jdbc().queryForObject("SELECT payload FROM api_workspace WHERE id=?", String.class, WORKSPACE);
        assertThat(new ApiPlanVault(target.jdbc()).decrypt("api-workspace", WORKSPACE, sealed)).isEqualTo(label + " private workspace");
        BackupFiles.validate(target.db()); BackupFiles.validatePlans(target.db(), BackupFiles.key(target.db()));
    }

    @Test void completeArchiveRestoresDatabaseKeyThreeRootsAndManagedBinaryWithAutomaticPairedBackup() throws Exception {
        byte[] originalBytes = {0,(byte)255,13,10,(byte)128,42};
        var file = source.files().upload(new ByteArrayInputStream(originalBytes), "fixture.bin", "application/octet-stream");
        State sourceState = state(source), oldTarget = state(target);
        byte[] upload = archive(source); var outer = unzip(upload);
        assertThat(outer.keySet()).contains("manifest.json", "database.zip", "assets/inventory.json", "assets/config/source.properties", "assets/screenshots/source.png", "assets/archives/source.json");
        var inner = unzip(outer.get("database.zip")); assertThat(inner.keySet()).containsExactlyInAnyOrder("manifest.json", "database.sqlite", "vault.key");
        String id = operation(); var accepted = target.transfer().importArchive(upload, id);
        assertThat(accepted.id()).isEqualTo(id); assertThat(accepted.status()).isEqualTo("RESTARTING"); assertThat(accepted.restartRequired()).isTrue();
        assertThat(accepted.backupId()).isNotBlank(); assertThat(target.transfer().archiveFile(accepted.backupId())).exists();
        var saved = unzip(Files.readAllBytes(target.transfer().archiveFile(accepted.backupId())));
        assertThat(unzip(saved.get("database.zip")).get("vault.key")).isEqualTo(oldTarget.key());
        assertThat(saved.get("assets/config/target.properties")).isEqualTo(text("target config"));
        unchanged(target, oldTarget); verify(target.restart()).restartAfterResponse();
        reopen(target); assertSourceData(target, source, "source");
        assertThat(BackupFiles.fingerprint(target.db())).isEqualTo(sourceState.database());
        assertThat(TransferAssets.inventory(target.db())).isEqualTo(sourceState.assets());
        for (String root : TransferAssets.ROOTS) assertThat(TransferAssets.target(target.db(), root).resolve("target" + (root.equals("config") ? ".properties" : root.equals("screenshots") ? ".png" : ".json"))).doesNotExist();
        try (var bytes = target.files().acquire(file.fileId())) { assertThat(bytes.bytes()).isEqualTo(originalBytes); }
        assertThat(target.transfer().status(id).get("lastResult").toString()).contains("RESTORED", id);
    }

    @Test void sameOperationAndArchiveIsIdempotentBeforeAndAfterActualRestore() throws Exception {
        byte[] upload = archive(source); String id = operation();
        var first = target.transfer().importArchive(upload, id); var second = target.transfer().importArchive(upload, id);
        assertThat(second).isEqualTo(first); verify(target.restart(), times(1)).restartAfterResponse();
        try (var paths = Files.list(BackupFiles.root(target.db()).resolve("transfers/operations"))) { assertThat(paths.count()).isEqualTo(1); }
        reopen(target); State restored = state(target);
        var after = target.transfer().importArchive(upload, id);
        assertThat(after.status()).isEqualTo("RESTORED"); assertThat(after.restartRequired()).isFalse(); assertThat(after.backupId()).isEqualTo(first.backupId());
        unchanged(target, restored); verify(target.restart(), times(1)).restartAfterResponse();
    }

    @Test void completedOperationRemainsRestoredAndIdempotentAfterLaterSuccessfulImport() throws Exception {
        byte[] archiveA = archive(source); String operationA = operation();
        var acceptedA = target.transfer().importArchive(archiveA, operationA);
        reopen(target); assertSourceData(target, source, "source");

        Fixture nextSource = fixture("next-source", (byte)0x44, false); assets(nextSource, "next-source");
        byte[] archiveB = archive(nextSource); String operationB = operation();
        target.transfer().importArchive(archiveB, operationB);
        reopen(target); assertSourceData(target, nextSource, "next-source");
        State afterB = state(target);
        assertThat(target.maintenance().restoreStatus().get("lastResult").toString()).contains("RESTORED", operationB);
        verify(target.restart(), times(2)).restartAfterResponse();

        var statusA = target.transfer().status(operationA);
        assertThat(statusA).containsEntry("staged", false);
        assertThat(statusA.get("lastResult")).isInstanceOf(Map.class);
        var resultA = (Map<?, ?>)statusA.get("lastResult");
        assertThat(resultA.get("id")).isEqualTo(operationA);
        assertThat(resultA.get("status")).isEqualTo("RESTORED");
        unchanged(target, afterB);

        var replayA = target.transfer().importArchive(archiveA, operationA);
        assertThat(replayA.id()).isEqualTo(operationA);
        assertThat(replayA.status()).isEqualTo("RESTORED");
        assertThat(replayA.restartRequired()).isFalse();
        assertThat(replayA.backupId()).isEqualTo(acceptedA.backupId());
        verify(target.restart(), times(2)).restartAfterResponse();
        verify(target.restart(), times(2)).requireSupported();
        unchanged(target, afterB);
        assertSourceData(target, nextSource, "next-source");
        assertThat(target.maintenance().restoreStatus().get("lastResult").toString()).contains("RESTORED", operationB);
    }

    @Test void sameOperationWithDifferentBytesAndAnotherPendingOperationBothRefuseWithoutChangingStage() throws Exception {
        byte[] first = archive(source), different = archive(source); String id = operation();
        target.transfer().importArchive(first, id); State before = state(target);
        assertThatThrownBy(() -> target.transfer().importArchive(different, id)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("409");
        assertThatThrownBy(() -> target.transfer().importArchive(first, operation())).isInstanceOf(ResponseStatusException.class).hasMessageContaining("409");
        assertThat(target.maintenance().restoreStatus()).containsEntry("id", id); unchanged(target, before);
        verify(target.restart(), times(1)).restartAfterResponse();
    }

    @Test void historicalColumnOrderOptionalStepsAndExtraAuxiliaryTableMigrateToFreshCurrentSchema() throws Exception {
        Fixture historical = fixture("historical", (byte)0x43, true); assets(historical, "historical");
        historical.jdbc().execute("CREATE TABLE field_comment(name TEXT,comment TEXT)"); historical.jdbc().update("INSERT INTO field_comment VALUES('fixture','preserve')");
        byte[] upload = archive(historical); byte[] inner = unzip(upload).get("database.zip");
        assertThatThrownBy(() -> target.maintenance().preview(inner)).hasMessageContaining("schema");
        target.transfer().importArchive(upload, operation()); reopen(target); assertSourceData(target, historical, "historical");
        assertThat(target.jdbc().queryForObject("SELECT comment FROM field_comment", String.class)).isEqualTo("preserve");
        assertThat(target.jdbc().queryForList("PRAGMA table_info(api_case)").getFirst().get("name")).isEqualTo("name");
    }

    @ParameterizedTest @ValueSource(strings={"database", "key", "asset"})
    void outOfBandChangesAfterStageRefuseAllReplacement(String kind) throws Exception {
        target.transfer().importArchive(archive(source), operation());
        if (kind.equals("database")) target.jdbc().update("INSERT INTO api_case(id,name) VALUES(2,'late user record')");
        else if (kind.equals("key")) { byte[] changed = target.key().clone(); changed[0]++; Files.write(BackupFiles.key(target.db()), changed); }
        else Files.writeString(TransferAssets.target(target.db(), "config").resolve("target.properties"), "late user edit");
        State changed = state(target); reopen(target); unchanged(target, changed);
        assertThat(target.maintenance().restoreStatus().get("lastResult").toString()).contains("REFUSED");
        assertThat(target.maintenance().staged()).isFalse();
    }

    @Test void changedCandidateAssetAfterStageIsRejectedBeforeCurrentDataChanges() throws Exception {
        String id = operation(); target.transfer().importArchive(archive(source), id); State before = state(target);
        Path assets = BackupFiles.root(target.db()).resolve("restores").resolve(id).resolve("transfer-assets");
        Files.writeString(assets.resolve("config/source.properties"), "tampered after staging");
        reopen(target); unchanged(target, before);
        assertThat(target.maintenance().restoreStatus().get("lastResult").toString()).contains("REFUSED");
    }

    @Test void absentIncomingRootAndEmptyIncomingRootReplaceRatherThanMergeExistingAssets() throws Exception {
        TransferAssets.deleteTree(TransferAssets.target(source.db(), "config"));
        TransferAssets.deleteTree(TransferAssets.target(source.db(), "screenshots")); Files.createDirectories(TransferAssets.target(source.db(), "screenshots"));
        var expected = TransferAssets.inventory(source.db()); assertThat(expected.roots()).containsEntry("config", false).containsEntry("screenshots", true);
        target.transfer().importArchive(archive(source), operation()); reopen(target);
        assertThat(TransferAssets.inventory(target.db())).isEqualTo(expected);
        assertThat(TransferAssets.target(target.db(), "config")).doesNotExist();
        try (var paths = Files.list(TransferAssets.target(target.db(), "screenshots"))) { assertThat(paths.count()).isZero(); }
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void interruptedDatabaseKeyAndAssetsReplacementRollsBackOriginalSetIncludingAbsentRoots(boolean absentRoots) throws Exception {
        if (absentRoots) for (String name : TransferAssets.ROOTS) TransferAssets.deleteTree(TransferAssets.target(target.db(), name));
        State before = state(target); String id = operation(); Path restore = BackupFiles.root(target.db()).resolve("restores").resolve(id); Files.createDirectory(restore);
        try (var connection = target.jdbc().getDataSource().getConnection()) { BackupFiles.snapshot(connection, restore.resolve("rollback.sqlite")); }
        Files.copy(BackupFiles.key(target.db()), restore.resolve("rollback.key")); TransferAssets.capture(target.db(), restore.resolve("rollback-assets"));
        BackupFiles.json(BackupFiles.root(target.db()).resolve("restore-in-progress.json"), Map.of("folder", id, "hadKey", true, "transferAssets", true));
        target.jdbc().update("UPDATE api_case SET name='half imported'"); Files.write(BackupFiles.key(target.db()), source.key());
        for (String name : TransferAssets.ROOTS) TransferAssets.deleteTree(TransferAssets.target(target.db(), name)); assets(target, "half");
        reopen(target); unchanged(target, before); assertSourceData(target, target, "target");
        assertThat(target.maintenance().restoreStatus().get("lastResult").toString()).contains("ROLLED_BACK", id);
        assertThat(BackupFiles.root(target.db()).resolve("restore-in-progress.json")).doesNotExist();
    }

    @Test void failedRollbackDoesNotOpenMixedDatabaseAndKeepsRecoveryJournal() throws Exception {
        String id = operation(); Path restore = BackupFiles.root(target.db()).resolve("restores").resolve(id); Files.createDirectory(restore);
        try (var connection = target.jdbc().getDataSource().getConnection()) { BackupFiles.snapshot(connection, restore.resolve("rollback.sqlite")); }
        Files.copy(BackupFiles.key(target.db()), restore.resolve("rollback.key"));
        BackupFiles.json(BackupFiles.root(target.db()).resolve("restore-in-progress.json"), Map.of("folder", id, "hadKey", true, "transferAssets", true));
        assertThatThrownBy(() -> OfflineRestore.open(target.db())).isInstanceOf(Exception.class);
        assertThat(BackupFiles.root(target.db()).resolve("restore-in-progress.json")).exists();
    }

    @Test void restartPreparationFailureUnstagesAndPreservesCurrentCompleteData() throws Exception {
        doThrow(new IllegalStateException("fixture restart failure")).when(target.restart()).restartAfterResponse();
        State before = state(target); String id = operation();
        assertThatThrownBy(() -> target.transfer().importArchive(archive(source), id)).hasMessageContaining("fixture restart failure");
        unchanged(target, before); assertThat(target.maintenance().staged()).isFalse();
        assertThat(target.transfer().status(id).get("lastResult").toString()).contains("REFUSED");
    }

    @Test void stageBlocksBusinessWritesButKeepsLocalStatusReadableAndRemoteTransferInaccessible() throws Exception {
        String id = operation(); target.transfer().importArchive(archive(source), id);
        var filter = new MaintenanceWriteFilter(target.gate(), target.maintenance());
        var response = new MockHttpServletResponse(); filter.doFilter(new MockHttpServletRequest("POST", "/api/apitest/cases"), response, (a,b) -> fail("staged mutation reached handler"));
        assertThat(response.getStatus()).isEqualTo(409);
        var read = new AtomicBoolean(); filter.doFilter(new MockHttpServletRequest("GET", "/api/maintenance/transfers/status"), new MockHttpServletResponse(), (a,b) -> { read.set(true); assertThat(target.transfer().status(id)).containsEntry("id", id); });
        assertThat(read).isTrue();
        for (String method : List.of("GET", "POST")) {
            var request = new MockHttpServletRequest(method, "/api/maintenance/transfers/export"); request.setRemoteAddr("192.0.2.1");
            response = new MockHttpServletResponse(); filter.doFilter(request, response, (a,b) -> fail("remote transfer reached handler")); assertThat(response.getStatus()).isEqualTo(403);
        }
    }

    @Test void importWaitsForInflightReadLockBeforeStagingAndDoesNotDeadlockThroughFilter() throws Exception {
        byte[] upload = archive(source); var pool = Executors.newSingleThreadExecutor(); var submitted = new CountDownLatch(1); Future<?> future;
        target.gate().lock.readLock().lock();
        try {
            future = pool.submit(() -> { submitted.countDown(); try { new MaintenanceWriteFilter(target.gate(), target.maintenance()).doFilter(new MockHttpServletRequest("POST", "/api/maintenance/transfers/import"), new MockHttpServletResponse(), (a,b) -> target.transfer().importArchive(upload, operation())); } catch (Exception e) { throw new RuntimeException(e); } });
            assertThat(submitted.await(2, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> future.get(100, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            assertThat(target.maintenance().staged()).isFalse();
        } finally { target.gate().lock.readLock().unlock(); }
        try { future.get(5, TimeUnit.SECONDS); assertThat(target.maintenance().staged()).isTrue(); }
        finally { pool.shutdownNow(); }
    }

    @Test void busyOwnerRejectsBothExportAndImportBeforeRestartOrDataChange() throws Exception {
        byte[] upload = archive(source); State before = state(target); target.busy().set(true);
        assertThatThrownBy(() -> target.transfer().exportArchive()).hasMessageContaining("收尾");
        assertThatThrownBy(() -> target.transfer().importArchive(upload, operation())).hasMessageContaining("收尾");
        unchanged(target, before); verifyNoInteractions(target.restart());
    }

    @ParameterizedTest @ValueSource(strings={"assetHash", "outerVersion", "unregistered", "traversal", "inventoryHash", "missingKey", "wrongKey", "blobCorrupt"})
    void invalidArchiveIsRejectedWithOriginalDatabaseKeyAndAssetsUnchanged(String defect) throws Exception {
        var outer = unzip(archive(source));
        switch (defect) {
            case "assetHash" -> outer.put("assets/config/source.properties", text("tamper without manifest update"));
            case "outerVersion" -> { var manifest = (ObjectNode)BackupFiles.JSON.readTree(outer.get("manifest.json")); manifest.put("version", "99.0.0"); outer.put("manifest.json", BackupFiles.JSON.writeValueAsBytes(manifest)); }
            case "unregistered" -> outer.put("assets/config/unregistered.txt", text("not in outer manifest"));
            case "traversal" -> outer.put("assets/config/../../escape", text("must not escape"));
            case "inventoryHash" -> { outer.put("assets/config/source.properties", text("outer rebound but inventory stale")); rebindOuter(outer); }
            case "missingKey", "wrongKey", "blobCorrupt" -> {
                var inner = unzip(outer.get("database.zip"));
                if (defect.equals("missingKey")) inner.remove("vault.key");
                else if (defect.equals("wrongKey")) inner.put("vault.key", target.key());
                else {
                    Path copy = directory.toRealPath().resolve("corrupt-" + (++sequence) + ".sqlite"); Files.write(copy, inner.get("database.sqlite"));
                    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + copy); var statement = connection.createStatement()) {
                        statement.execute("CREATE TABLE IF NOT EXISTS api_file(id TEXT PRIMARY KEY,kind TEXT,filename TEXT,content_type TEXT,bytes INTEGER,sha256 TEXT,created_at TEXT,payload BLOB)");
                        statement.execute("INSERT INTO api_file VALUES('00000000-0000-0000-0000-000000000001','UPLOAD','fixture.bin','application/octet-stream',1,'" + "0".repeat(64) + "','fixture',X'01')");
                    }
                    inner.put("database.sqlite", Files.readAllBytes(copy));
                }
                rebindInner(inner); outer.put("database.zip", zip(inner)); rebindOuter(outer);
            }
            default -> throw new AssertionError(defect);
        }
        rejectedWithoutChangingTarget(zip(outer)); assertThat(directory.resolve("escape")).doesNotExist();
    }

    @Test void sourceSymlinkAndUntrustedAssetNameAreRejectedWithoutReadingExternalBytes() throws Exception {
        Path external = directory.toRealPath().resolve("outside-fixture.txt"); Files.writeString(external, "outside fixture");
        Path link = TransferAssets.target(source.db(), "config").resolve("link.properties"); Files.createSymbolicLink(link, external);
        assertThatThrownBy(() -> source.transfer().exportArchive()).isInstanceOf(RuntimeException.class);
        assertThat(Files.readString(external)).isEqualTo("outside fixture");
        for (String name : List.of("../config/a", "/config/a", "config/../a", "config//a", "config/a/", "config\\a")) assertThatThrownBy(() -> TransferAssets.safeName(name)).isInstanceOf(IllegalArgumentException.class);
    }
}
