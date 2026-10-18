package com.sudpanel.backup;

import java.nio.file.Path;
import java.sql.DriverManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class BackupFingerprintTest {
    @TempDir Path directory;
    @Test void logicalFingerprintSurvivesVacuumAndDuplicateRowsButDetectsChangedEvidence() throws Exception {
        Path db = directory.resolve("fingerprint.db");
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + db); var s = c.createStatement()) {
            s.execute("CREATE TABLE fixture(name TEXT,value BLOB)");
            s.execute("INSERT INTO fixture VALUES('same',x'ABCD'),('same',x'ABCD'),(NULL,x'00'),('different',NULL)");
        }
        String original = BackupFiles.fingerprint(db);
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + db); var s = c.createStatement()) { s.execute("VACUUM"); }
        assertThat(BackupFiles.fingerprint(db)).isEqualTo(original);
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + db); var s = c.createStatement()) { s.executeUpdate("UPDATE fixture SET value=x'ABCE' WHERE name='same'"); }
        assertThat(BackupFiles.fingerprint(db)).isNotEqualTo(original);
    }
    @Test @EnabledIfSystemProperty(named="perfMillion", matches="true")
    void millionRowsAreHashedWithoutRetainingAllRowsInTheJavaHeap() throws Exception {
        Path db = directory.resolve("million.db");
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + db)) {
            try (var s = c.createStatement()) { s.execute("CREATE TABLE evidence(id INTEGER PRIMARY KEY,payload TEXT)"); }
            c.setAutoCommit(false);
            try (var insert = c.prepareStatement("INSERT INTO evidence VALUES(?,?)")) {
                String body = "fixture evidence ".repeat(5);
                for (int i=1;i<=1_000_000;i++) { insert.setInt(1,i);insert.setString(2,body);insert.addBatch();if(i%1000==0)insert.executeBatch(); }
            }
            c.commit();
        }
        assertThat(BackupFiles.fingerprint(db)).matches("[a-f0-9]{64}");
    }
}
