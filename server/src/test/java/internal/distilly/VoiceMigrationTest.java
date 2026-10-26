package internal.distilly;

import internal.distilly.infrastructure.migration.PersonaMigration;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;

class VoiceMigrationTest {
    @Test void actualV010SchemaUpgradesWithoutReplacingExistingMemoryAndRunsTwice() {
        var source=new DriverManagerDataSource("jdbc:h2:mem:voice-migration;DATABASE_TO_LOWER=TRUE","sa","");
        // Keep the synthetic database open across the migration's connections.
        source.setUrl("jdbc:h2:mem:voice-migration;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(source);
        String[] scripts={"V001__persona.sql","V002__reply_deltas.sql","V003__message_images.sql","V004__persona_knowledge.sql","V005__personality_library.sql","V006__work_chunk_checkpoints.sql","V007__synthetic_cost_ledger.sql","V008__text_execution_budget.sql","V009__chat_submission_receipts.sql","V010__material_submission_receipts.sql"};
        var db=new JdbcTemplate(source);
        db.execute("CREATE TABLE persona_schema_version(version INT PRIMARY KEY,applied_at BIGINT NOT NULL)");
        for(int i=0;i<scripts.length;i++){
            new ResourceDatabasePopulator(new ClassPathResource("migrations/"+scripts[i])).execute(source);
            db.update("INSERT INTO persona_schema_version VALUES (?,1)",i+1);
        }
        db.update("INSERT INTO app_user VALUES ('synthetic-user','synthetic-hash')");
        db.update("INSERT INTO project VALUES ('synthetic-project','合成保留')");
        db.update("INSERT INTO persona(id,project_id,name,family,definition,creator,created_at) VALUES ('synthetic-persona','synthetic-project','合成人物','relationship','{}','synthetic-user',1)");
        db.update("INSERT INTO persona_version(id,persona_id,status,content,creator,created_at) VALUES ('synthetic-version','synthetic-persona','APPROVED','{}','synthetic-user',1)");
        db.update("INSERT INTO persona_conversation(id,persona_id,version_id,user_id,title,emotion,created_at) VALUES ('synthetic-conversation','synthetic-persona','synthetic-version','synthetic-user','合成来源','{}',1)");
        db.update("INSERT INTO persona_message(id,conversation_id,role,body,created_at) VALUES ('synthetic-source','synthetic-conversation','user','合成保留来源',1)");
        db.update("INSERT INTO persona_memory VALUES ('synthetic-memory','synthetic-persona','synthetic-user','合成保留记忆','synthetic-source','CONFIRMED',1)");
        var migration=new PersonaMigration(db,new DataSourceTransactionManager(source));
        ReflectionTestUtils.invokeMethod(migration,"migrate");ReflectionTestUtils.invokeMethod(migration,"migrate");
        assertEquals(11,db.queryForObject("SELECT MAX(version) FROM persona_schema_version",Integer.class));
        assertEquals("合成保留记忆",db.queryForObject("SELECT body FROM persona_memory WHERE id='synthetic-memory'",String.class));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM voice_profile",Integer.class));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM voice_profile_job",Integer.class));
    }
}
