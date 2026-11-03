package internal.distilly.infrastructure.migration;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.core.io.ByteArrayResource;
import internal.distilly.common.support.Digests;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

class MigrationV005RecoveryTest {
    @TempDir Path temporary;
    static final String DEFINITION="{\"synthetic\":\"preserve-definition\"}";
    static final String CONTENT="{\"synthetic\":\"preserve-version\"}";
    static String script()throws Exception {
        try(var in=new ClassPathResource("migrations/V005__personality_library.sql").getInputStream()) {
            return new String(in.readAllBytes(),StandardCharsets.UTF_8);
        }
    }
    static List<String> statements()throws Exception {
        return Arrays.stream(script().split(";")).map(String::trim).filter(s->!s.isEmpty()).toList();
    }
    static final class BoundaryStop extends RuntimeException { }
    static final class Fixture implements AutoCloseable {
        final DriverManagerDataSource source;
        final JdbcTemplate db;
        private final Connection anchor;
        Fixture(Path file)throws Exception {this(file,true);}
        Fixture(Path file,boolean lower)throws Exception {
            source=new DriverManagerDataSource("jdbc:h2:file:"+file+";DB_CLOSE_ON_EXIT=FALSE"+(lower?";DATABASE_TO_LOWER=TRUE":""),"sa","");
            anchor=source.getConnection();db=new JdbcTemplate(source);db.execute("SET WRITE_DELAY 0");
        }
        void setup() {
            new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(source);
            db.execute("CREATE TABLE persona_schema_version(version INT PRIMARY KEY,applied_at BIGINT NOT NULL)");
            String[] scripts={"V001__persona.sql","V002__reply_deltas.sql","V003__message_images.sql","V004__persona_knowledge.sql"};
            for(int i=0;i<scripts.length;i++) {
                new ResourceDatabasePopulator(new ClassPathResource("migrations/"+scripts[i])).execute(source);
                db.update("INSERT INTO persona_schema_version VALUES (?,?)",i+1,100L+i);
            }
            db.update("INSERT INTO app_user VALUES ('synthetic-user','synthetic-token-hash')");
            db.update("INSERT INTO project VALUES ('synthetic-project','合成迁移保留')");
            db.update("INSERT INTO persona(id,project_id,name,family,definition,creator,created_at) VALUES ('synthetic-persona','synthetic-project','合成人物','relationship',?,'synthetic-user',1)",DEFINITION);
            db.update("INSERT INTO persona_version(id,persona_id,status,content,creator,created_at) VALUES ('synthetic-version','synthetic-persona','APPROVED',?,'synthetic-user',1)",CONTENT);
        }
        void interruptedAfter(int completed)throws Exception {
            var ddl=statements();
            assertThrows(BoundaryStop.class,()->new TransactionTemplate(new DataSourceTransactionManager(source)).executeWithoutResult(status->{
                for(int i=0;i<completed;i++)db.execute(ddl.get(i));
                throw new BoundaryStop();
            }));
            assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM persona_schema_version WHERE version=5",Integer.class));
            if(completed>=1)db.update("UPDATE persona SET library=TRUE WHERE id='synthetic-persona'");
            if(completed>=2)db.update("UPDATE persona_version SET source_personality_id='synthetic-style' WHERE id='synthetic-version'");
            if(completed>=3)db.update("UPDATE persona_version SET source_personality_version_id='synthetic-style-version' WHERE id='synthetic-version'");
        }
        String fingerprint() {
            var text=new StringBuilder();
            for(String table:List.of("persona","persona_version","persona_schema_version"))db.query("SELECT * FROM "+table+" ORDER BY 1",rs->{
                for(int i=1;i<=rs.getMetaData().getColumnCount();i++){
                    String value=rs.getString(i);text.append(rs.getMetaData().getColumnName(i)).append(':').append(value==null?"NULL":value.length()+":"+value).append('|');
                }
            });
            text.append(db.queryForList("SELECT * FROM INFORMATION_SCHEMA.COLUMNS WHERE LOWER(TABLE_NAME) IN ('persona','persona_version','persona_schema_version') ORDER BY TABLE_SCHEMA,TABLE_NAME,ORDINAL_POSITION"));
            text.append(db.queryForList("SELECT * FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS WHERE LOWER(TABLE_NAME) IN ('persona','persona_version','persona_schema_version') ORDER BY TABLE_SCHEMA,TABLE_NAME,CONSTRAINT_NAME"));
            return Digests.sha256(text.toString());
        }
        void migrate(){new PersonaMigration(db,new DataSourceTransactionManager(source)).migrate();}
        void preserved(int previousPrefix) {
            assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM persona",Integer.class));
            assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM persona_version",Integer.class));
            assertTrue(DEFINITION.equals(db.queryForObject("SELECT definition FROM persona WHERE id='synthetic-persona'",String.class)),"synthetic definition must remain unchanged");
            assertTrue(CONTENT.equals(db.queryForObject("SELECT content FROM persona_version WHERE id='synthetic-version'",String.class)),"synthetic version must remain unchanged");
            assertEquals(previousPrefix>=1,db.queryForObject("SELECT library FROM persona WHERE id='synthetic-persona'",Boolean.class));
            assertTrue(Objects.equals(previousPrefix>=2?"synthetic-style":null,db.queryForObject("SELECT source_personality_id FROM persona_version WHERE id='synthetic-version'",String.class)),"existing source identifier must remain unchanged");
            assertTrue(Objects.equals(previousPrefix>=3?"synthetic-style-version":null,db.queryForObject("SELECT source_personality_version_id FROM persona_version WHERE id='synthetic-version'",String.class)),"existing source version identifier must remain unchanged");
        }
        @Override public void close()throws Exception {anchor.close();}
    }
    @Test void committedFirstV005DdlResumesWithoutLosingExistingValues()throws Exception {
        Path file=temporary.resolve("database");
        try(var before=new Fixture(file)){before.setup();before.interruptedAfter(1);}
        try(var after=new Fixture(file)){
            after.migrate();after.preserved(1);
            assertEquals(13,after.db.queryForObject("SELECT MAX(version) FROM persona_schema_version",Integer.class));
        }
    }

    private static void rejected(Fixture fixture,Runnable action) {
        String before=fixture.fingerprint();
        var error=assertThrows(IllegalStateException.class,action::run);
        assertTrue(error.getMessage().startsWith("migration_v005_"),"recovery must reject explicitly");
        assertEquals(before,fixture.fingerprint(),"refusal must not change synthetic rows, columns or history");
        assertEquals(0,fixture.db.queryForObject("SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE LOWER(TABLE_NAME)='job_distill_execution'",Integer.class),"later migration must not start");
    }
    @ParameterizedTest @ValueSource(ints={0,2,3})
    void allOtherCommittedPrefixesResume(int prefix)throws Exception {
        Path file=temporary.resolve("database");
        try(var before=new Fixture(file)){before.setup();before.interruptedAfter(prefix);}
        try(var after=new Fixture(file)){
            after.migrate();after.preserved(prefix);
            var history=after.db.queryForList("SELECT * FROM persona_schema_version ORDER BY version");
            after.migrate();assertEquals(history,after.db.queryForList("SELECT * FROM persona_schema_version ORDER BY version"));after.preserved(prefix);
        }
    }
    @Test void defaultUppercaseIdentifiersAlsoResume()throws Exception {
        Path file=temporary.resolve("database");
        try(var before=new Fixture(file,false)){before.setup();before.interruptedAfter(2);}
        try(var after=new Fixture(file,false)){after.migrate();after.preserved(2);}
    }
    @Test void appliedLegacyVersion5KeepsTimestampAndDoesNotInventHistoricalChecksum()throws Exception {
        try(var fixture=new Fixture(temporary.resolve("database"))){
            fixture.setup();fixture.interruptedAfter(3);fixture.db.update("INSERT INTO persona_schema_version VALUES (5,505)");
            fixture.migrate();fixture.preserved(3);fixture.migrate();
            assertEquals(505L,fixture.db.queryForObject("SELECT applied_at FROM persona_schema_version WHERE version=5",Long.class));
            assertEquals(13,fixture.db.queryForObject("SELECT COUNT(*) FROM persona_schema_version",Integer.class));
            assertEquals(2,fixture.db.queryForObject("SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME='persona_schema_version'",Integer.class));
            assertEquals(4,fixture.db.queryForObject("SELECT COUNT(*) FROM persona_schema_version WHERE version BETWEEN 1 AND 4 AND applied_at=version+99",Integer.class));
        }
    }
    static Stream<String> drift() {
        return Stream.of(
            "ALTER TABLE persona ALTER COLUMN library DROP NOT NULL",
            "ALTER TABLE persona ALTER COLUMN library SET DEFAULT TRUE",
            "ALTER TABLE persona_version ALTER COLUMN source_personality_id SET DATA TYPE VARCHAR(35)",
            "ALTER TABLE persona_version ALTER COLUMN source_personality_id SET NOT NULL",
            "ALTER TABLE persona_version ALTER COLUMN source_personality_id SET DEFAULT 'synthetic-default'",
            "ALTER TABLE persona_version ADD unexpected VARCHAR(10)",
            "ALTER TABLE persona_version ADD CONSTRAINT synthetic_unique UNIQUE(source_personality_id)",
            "ALTER TABLE persona ADD CONSTRAINT synthetic_check CHECK(library IN (TRUE,FALSE))",
            "ALTER TABLE persona_version ADD CONSTRAINT synthetic_foreign FOREIGN KEY(source_personality_id) REFERENCES persona(id)",
            "ALTER TABLE persona_version ALTER COLUMN source_personality_id SET INVISIBLE",
            "ALTER TABLE persona_version ALTER COLUMN source_personality_id SET ON UPDATE 'synthetic-persona'",
            "ALTER TABLE persona_version ALTER COLUMN source_personality_id SET DEFAULT ON NULL",
            "ALTER TABLE persona_version DROP COLUMN source_personality_id; ALTER TABLE persona_version ADD source_personality_id VARCHAR(36) GENERATED ALWAYS AS ('synthetic-persona')",
            "CREATE DOMAIN synthetic_identifier AS VARCHAR(36); ALTER TABLE persona_version DROP COLUMN source_personality_id; ALTER TABLE persona_version ADD source_personality_id synthetic_identifier",
            "ALTER TABLE persona_version DROP COLUMN created_at; ALTER TABLE persona_version ADD created_at BIGINT GENERATED BY DEFAULT AS IDENTITY",
            "ALTER TABLE persona SET REFERENTIAL_INTEGRITY FALSE",
            "SET REFERENTIAL_INTEGRITY FALSE",
            "CREATE INDEX synthetic_unexpected ON persona_version(source_personality_id)"
        );
    }
    @ParameterizedTest(name="rejects schema drift {index}") @MethodSource("drift")
    void unknownDefinitionsOrConstraintsAreNotSilentlySkipped(String mutation)throws Exception {
        try(var fixture=new Fixture(temporary.resolve("database"))){
            fixture.setup();fixture.db.execute(statements().get(0));fixture.db.execute(statements().get(1));
            fixture.db.update("UPDATE persona_version SET source_personality_id='synthetic-persona'");
            for(String ddl:mutation.split(";"))fixture.db.execute(ddl.trim());
            rejected(fixture,fixture::migrate);
        }
    }
    @ParameterizedTest @ValueSource(ints={2,4,5,6})
    void onlyOrderedDdlPrefixesMayResume(int mask)throws Exception {
        try(var fixture=new Fixture(temporary.resolve("database"))){
            fixture.setup();var ddl=statements();
            for(int i=0;i<3;i++)if((mask&(1<<i))!=0)fixture.db.execute(ddl.get(i));
            rejected(fixture,fixture::migrate);
        }
    }
    @ParameterizedTest @ValueSource(strings={"DELETE FROM persona_schema_version WHERE version=2","INSERT INTO persona_schema_version VALUES(6,606)","INSERT INTO persona_schema_version VALUES(14,1414)"})
    void historyGapsAndUnknownVersionsFailBeforeDdl(String mutation)throws Exception {
        try(var fixture=new Fixture(temporary.resolve("database"))){fixture.setup();fixture.db.execute(mutation);rejected(fixture,fixture::migrate);}
    }
    @ParameterizedTest @ValueSource(strings={"ALTER TABLE persona_version DROP COLUMN source_personality_version_id","ALTER TABLE persona ALTER COLUMN library DROP NOT NULL"})
    void aCompletedVersionWithWrongShapeIsNotAutomaticallyRepaired(String mutation)throws Exception {
        try(var fixture=new Fixture(temporary.resolve("database"))){
            fixture.setup();fixture.interruptedAfter(3);fixture.db.update("INSERT INTO persona_schema_version VALUES(5,505)");
            fixture.db.execute(mutation);rejected(fixture,fixture::migrate);
        }
    }
    @Test void sameNamedViewCannotSubstituteForTheTable()throws Exception {
        try(var fixture=new Fixture(temporary.resolve("database"))){
            fixture.setup();fixture.db.execute("ALTER TABLE persona_version RENAME TO hidden_versions");fixture.db.execute("CREATE VIEW persona_version AS SELECT * FROM hidden_versions");
            rejected(fixture,fixture::migrate);
        }
    }
    @Test void aTableInAnotherSchemaIsNotModified()throws Exception {
        try(var fixture=new Fixture(temporary.resolve("database"))){
            fixture.setup();fixture.db.execute("CREATE SCHEMA synthetic_other");fixture.db.execute("CREATE TABLE synthetic_other.persona_version AS SELECT * FROM persona_version");fixture.db.execute("DROP TABLE persona_version CASCADE");
            var before=fixture.db.queryForList("SELECT * FROM persona_schema_version ORDER BY version");
            var error=assertThrows(IllegalStateException.class,fixture::migrate);assertTrue(error.getMessage().startsWith("migration_v005_"));
            assertEquals(before,fixture.db.queryForList("SELECT * FROM persona_schema_version ORDER BY version"));
            assertTrue(CONTENT.equals(fixture.db.queryForObject("SELECT content FROM synthetic_other.persona_version",String.class)),"other-schema synthetic data must remain unchanged");
        }
    }
    @ParameterizedTest @ValueSource(strings={"\n-- synthetic changed resource\n","\nALTER TABLE persona ADD synthetic_extra INTEGER;\n"})
    void changedScriptOrFourthStatementIsRejectedBeforeDdl(String suffix)throws Exception {
        try(var fixture=new Fixture(temporary.resolve("database"))){
            fixture.setup();var changed=new ByteArrayResource((script()+suffix).getBytes(StandardCharsets.UTF_8));
            rejected(fixture,()->new H2V005Recovery(fixture.db,changed));
        }
    }
    @Test void anInterruptionDuringRecoveryCanResumeAgain()throws Exception {
        Path file=temporary.resolve("database");
        try(var before=new Fixture(file)){before.setup();before.interruptedAfter(1);}
        try(var interrupted=new Fixture(file)){
            String second=statements().get(1);
            var failing=new JdbcTemplate(interrupted.source){@Override public void execute(String sql){super.execute(sql);if(second.equals(sql))throw new BoundaryStop();}};
            assertThrows(BoundaryStop.class,()->new PersonaMigration(failing,new DataSourceTransactionManager(interrupted.source)).migrate());
            assertEquals(0,interrupted.db.queryForObject("SELECT COUNT(*) FROM persona_schema_version WHERE version=5",Integer.class));
        }
        try(var recovered=new Fixture(file)){recovered.migrate();recovered.preserved(1);}
    }
    public static final class GuardTrigger implements org.h2.api.Trigger {
        @Override public void fire(Connection connection,Object[] oldRow,Object[] newRow){throw new AssertionError("synthetic trigger must never run during refusal");}
    }
    @Test void anUnknownTriggerIsRejectedWithoutFiring()throws Exception {
        try(var fixture=new Fixture(temporary.resolve("database"))){
            fixture.setup();fixture.db.execute("CREATE TRIGGER synthetic_guard BEFORE UPDATE ON persona FOR EACH ROW CALL \"internal.distilly.infrastructure.migration.MigrationV005RecoveryTest$GuardTrigger\"");
            rejected(fixture,fixture::migrate);
        }
    }


    @Test void failureAfterAllDdlButBeforeVersionInsertResumesWithoutRepeatingDdl()throws Exception {
        Path file=temporary.resolve("database");
        try(var before=new Fixture(file)){before.setup();before.interruptedAfter(2);}
        try(var interrupted=new Fixture(file)){
            var failing=new JdbcTemplate(interrupted.source){@Override public int update(String sql,Object... args){
                if(sql.equals("INSERT INTO persona_schema_version VALUES (?,?)")&&Integer.valueOf(5).equals(args[0]))throw new BoundaryStop();
                return super.update(sql,args);
            }};
            assertThrows(BoundaryStop.class,()->new PersonaMigration(failing,new DataSourceTransactionManager(interrupted.source)).migrate());
            assertEquals(0,interrupted.db.queryForObject("SELECT COUNT(*) FROM persona_schema_version WHERE version=5",Integer.class));
            assertEquals(1,interrupted.db.queryForObject("SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME='persona_version' AND COLUMN_NAME='source_personality_version_id'",Integer.class));
        }
        try(var recovered=new Fixture(file)){recovered.migrate();recovered.preserved(2);assertEquals(1,recovered.db.queryForObject("SELECT COUNT(*) FROM persona_schema_version WHERE version=5",Integer.class));}
    }

}
