package internal.distilly.infrastructure.migration;

import jakarta.annotation.PostConstruct;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Append-only platform migrations using the existing schema history. Never change an applied SQL file. */
@Component
public class PersonaMigration {

    private final JdbcTemplate db;

    private final TransactionTemplate tx;

    public PersonaMigration(JdbcTemplate db, PlatformTransactionManager manager) {
        this.db = db;
        this.tx = new TransactionTemplate(manager);
    }

    @PostConstruct
    void migrate() {
        // H2 defaults to delayed committed-log writes. Local accounting must survive
        // an immediate API process kill, so configure synchronous commit writes
        // before opening the migration transaction or accepting requests.
        boolean h2 = Boolean.TRUE.equals(db.execute((org.springframework.jdbc.core.ConnectionCallback<Boolean>)
                connection -> "H2".equals(connection.getMetaData().getDatabaseProductName())));
        if (h2) db.execute("SET WRITE_DELAY 0");
        db.execute(
                "CREATE TABLE IF NOT EXISTS persona_schema_version (version INT PRIMARY KEY, applied_at BIGINT NOT NULL)");
        tx.executeWithoutResult(status -> {
            String[] migrations = { "V001__persona.sql", "V002__reply_deltas.sql", "V003__message_images.sql",
                    "V004__persona_knowledge.sql", "V005__personality_library.sql", "V006__work_chunk_checkpoints.sql", "V007__synthetic_cost_ledger.sql",
                    "V008__text_execution_budget.sql", "V009__chat_submission_receipts.sql",
                    "V010__material_submission_receipts.sql" };
            for (int i = 0; i < migrations.length; i++) {
                int version = i + 1;
                if (db.queryForObject("SELECT COUNT(*) FROM persona_schema_version WHERE version=?", Integer.class,
                        version) == 0) {
                    new ResourceDatabasePopulator(new ClassPathResource("migrations/" + migrations[i]))
                        .execute(java.util.Objects.requireNonNull(db.getDataSource()));
                    db.update("INSERT INTO persona_schema_version VALUES (?,?)", version, System.currentTimeMillis());
                }
            }
        });
    }

}
