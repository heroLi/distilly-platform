package internal.distilly.infrastructure.migration;

import java.nio.file.*;
import java.util.concurrent.CountDownLatch;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic no-web/no-Worker file-H2 fixture; the parent kills only this owned process. */
public final class V005MigrationProcessFixture {
    public static void main(String[] args)throws Exception {
        Path root=Path.of(args[0]);String step=args[1];Files.createDirectories(root);
        try(var fixture=new MigrationV005RecoveryTest.Fixture(root.resolve("database"))) {
            if("prepare".equals(step)) {
                fixture.setup();fixture.interruptedAfter(1);
                Files.writeString(root.resolve("ready.tmp"),"COMMITTED_V005_PREFIX_1_WITHOUT_VERSION_5\n");
                Files.move(root.resolve("ready.tmp"),root.resolve("ready"),StandardCopyOption.ATOMIC_MOVE);
                new CountDownLatch(1).await();
            } else if("recover".equals(step)) {
                assertEquals(0,fixture.db.queryForObject("SELECT COUNT(*) FROM persona_schema_version WHERE version=5",Integer.class));
                fixture.migrate();fixture.preserved(1);
                var history=fixture.db.queryForList("SELECT * FROM persona_schema_version ORDER BY version");
                fixture.migrate();fixture.preserved(1);
                assertEquals(history,fixture.db.queryForList("SELECT * FROM persona_schema_version ORDER BY version"));
                assertEquals(13,history.size());
                assertEquals(4,fixture.db.queryForObject("SELECT COUNT(*) FROM persona_schema_version WHERE version BETWEEN 1 AND 4 AND applied_at=version+99",Integer.class));
                Files.writeString(root.resolve("recovered.json"),"{\"status\":\"PASS\",\"independent_jvms\":2,\"forced_kills\":1,\"version5_once\":true,\"synthetic_rows_preserved\":true,\"library_true_preserved\":true,\"old_timestamps_preserved\":true,\"no_web_or_worker\":true,\"real_provider_calls\":0}\n");
            } else throw new IllegalArgumentException("unknown_synthetic_migration_phase");
        }
    }
}
