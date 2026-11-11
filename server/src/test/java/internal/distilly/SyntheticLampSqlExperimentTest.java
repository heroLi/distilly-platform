package internal.distilly;

import internal.distilly.common.persistence.ResourceRepository;
import internal.distilly.modules.memory.domain.MemoryProvider;
import internal.distilly.modules.memory.repository.MemoryRepository;
import internal.distilly.modules.memory.repository.MemorySyncRepository;
import internal.distilly.modules.memory.service.MemoryService;
import internal.distilly.modules.memory.service.MemorySyncService;
import internal.distilly.modules.persona.service.PersonaAccessService;
import internal.distilly.modules.workspace.repository.WorkspaceAccessRepository;
import internal.distilly.modules.workspace.service.WorkspaceAccessService;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import static internal.distilly.SyntheticLampSqlExperiment.Status.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real JDBC/production service calls against fresh file H2. Not PG, HTTP, or semantic quality. */
class SyntheticLampSqlExperimentTest {
    @TempDir Path root;
    JdbcTemplate db;
    TransactionTemplate tx;
    DataSourceTransactionManager manager;
    PersonaAccessService access;
    MemoryService memories;
    SyntheticLampSqlExperiment experiment;
    String url, project, person, version, conversation, memory;
    final String actor="synthetic-owner", peer="synthetic-peer";
    final SyntheticLampSqlExperiment.Fact on=new SyntheticLampSqlExperiment.Fact("synthetic-lamp-a",true,100,200);

    void connect() {
        var data=new DriverManagerDataSource(url,"sa",""); db=new JdbcTemplate(data);
        manager=new DataSourceTransactionManager(data);tx=new TransactionTemplate(manager);
        var resources=new ResourceRepository(db);
        access=new PersonaAccessService(resources,new WorkspaceAccessService(new WorkspaceAccessRepository(db)));
        var repository=new MemoryRepository(db);
        var sync=new MemorySyncService(new MemorySyncRepository(db),repository,access,resources);
        memories=new MemoryService(repository,resources,access,sync);
        experiment=new SyntheticLampSqlExperiment(db,manager,access,true);
    }
    @BeforeEach void setup() {
        url="jdbc:h2:file:"+root.resolve("synthetic-lamp-ledger").toAbsolutePath()+";DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE";
        connect();new ResourceDatabasePopulator(new ClassPathResource("schema.sql"),
            new ClassPathResource("migrations/V001__persona.sql"),new ClassPathResource("migrations/V002__reply_deltas.sql"),
            new ClassPathResource("migrations/V004__persona_knowledge.sql"),
            new ClassPathResource("migrations/V012__memory_sync_intents.sql")).execute(db.getDataSource());
        SyntheticLampSqlExperiment.installFixtureTable(db);
        for(var entry:Map.of(actor,"1",peer,"2","synthetic-outsider","3").entrySet())
            db.update("INSERT INTO app_user VALUES (?,?)",entry.getKey(),entry.getValue().repeat(64));
        project=UUID.randomUUID().toString();db.update("INSERT INTO project VALUES (?,?)",project,"Synthetic fixture");
        for(String who:List.of(actor,peer))db.update("INSERT INTO member VALUES (?,?,'OWNER')",project,who);
        person=createPerson(project);version=db.queryForObject("SELECT published_id FROM persona WHERE id=?",String.class,person);
        conversation=createConversation(actor,person,version);memory=seed(actor,person,conversation);
        tx.executeWithoutResult(s->memories.memory(actor,person,memory,Map.of("status","CONFIRMED")));
    }
    @AfterEach void close() { if(db!=null)db.execute("SHUTDOWN"); }
    String createPerson(String workspace) {
        String p=UUID.randomUUID().toString(),v=UUID.randomUUID().toString();
        db.update("INSERT INTO persona(id,project_id,name,family,definition,creator,created_at) VALUES (?,?,'Synthetic lamp','relationship','{}',?,1)",p,workspace,actor);
        db.update("INSERT INTO persona_version(id,persona_id,status,content,creator,created_at) VALUES (?,?,'APPROVED','{}',?,1)",v,p,actor);
        db.update("UPDATE persona SET published_id=? WHERE id=?",v,p);return p;
    }
    String createConversation(String who,String p,String v) {
        String c=UUID.randomUUID().toString();db.update("INSERT INTO persona_conversation(id,persona_id,version_id,user_id,title,emotion,created_at) VALUES (?,?,?,?,'Synthetic fixture','{}',1)",c,p,v,who);return c;
    }
    String seed(String who,String p,String c) {
        String source=UUID.randomUUID().toString(),m=UUID.randomUUID().toString();
        db.update("INSERT INTO persona_message(id,conversation_id,role,body,created_at) VALUES (?,?,'user','Synthetic source; never parsed as a lamp fact.',1)",source,c);
        db.update("INSERT INTO persona_memory VALUES (?,?,?,'Synthetic explicit fixture source A',?,'PENDING',1)",m,p,who,source);return m;
    }
    String candidate() { return experiment.propose(actor,person,memory,on); }
    String confirmed() {String id=candidate();experiment.confirm(actor,person,id);return id;}
    SyntheticLampSqlExperiment.Decision read() {return experiment.decide(actor,person,"synthetic-lamp-a",true,150);}
    void error(int status,Runnable action) {assertEquals(status,assertThrows(ResponseStatusException.class,action::run).getStatusCode().value());}

    @Test void defaultClosedDoesNotAccessDatabaseOrInstallAnything() {
        var closed=new SyntheticLampSqlExperiment(null,null,null);
        assertEquals(DISABLED,closed.decide(null,null,null,true,150).status());
        error(409,()->closed.propose(null,null,null,on));
    }
    @Test void explicitIndependentConfirmationAndLineageAreRequired() {
        String id=candidate();assertEquals(UNKNOWN,read().status());experiment.confirm(actor,person,id);
        var result=read();assertEquals(SUPPORTED,result.status());assertEquals(1,result.evidence().size());
        var evidence=result.evidence().get(0);assertEquals(id,evidence.factId());assertEquals(memory,evidence.memoryId());
        assertEquals(version,evidence.sourceVersion());assertEquals(1,evidence.revision());assertNotNull(evidence.operation());
        experiment.confirm(actor,person,id);assertEquals(1,read().evidence().size());
    }
    @Test void currentConfirmedSourceCannotBeBypassedByTypedInput() {
        String pending=seed(actor,person,conversation);error(409,()->experiment.propose(actor,person,pending,on));
        db.update("UPDATE persona_message SET role='assistant' WHERE id=(SELECT source_id FROM persona_memory WHERE id=?)",memory);
        error(409,this::candidate);
    }
    @Test void sameProjectPeerCannotConfirmRevokeOrReadOwnersFact() {
        String id=confirmed();error(404,()->experiment.confirm(peer,person,id));error(404,()->experiment.revoke(peer,person,id));
        error(409,()->experiment.propose(peer,person,memory,on));
        assertEquals(UNKNOWN,experiment.decide(peer,person,"synthetic-lamp-a",true,150).status());
        assertEquals(SUPPORTED,read().status());
    }
    @Test void otherPersonaAndProjectCannotSupplyEvidence() {
        confirmed();String other=createPerson(project);
        assertEquals(UNKNOWN,experiment.decide(actor,other,"synthetic-lamp-a",true,150).status());
        error(409,()->experiment.propose(actor,other,memory,on));
        String foreign=UUID.randomUUID().toString();db.update("INSERT INTO project VALUES (?,'Synthetic foreign')",foreign);
        String foreignPerson=createPerson(foreign);error(403,()->experiment.decide(actor,foreignPerson,"synthetic-lamp-a",true,150));
    }
    @Test void absentMembershipOrArchivedPersonaRefusesWithoutEvidence() {
        confirmed();error(403,()->experiment.decide("synthetic-outsider",person,"synthetic-lamp-a",true,150));
        db.update("DELETE FROM member WHERE project_id=? AND user_id=?",project,actor);error(403,this::read);
        db.update("INSERT INTO member VALUES (?,?,'OWNER')",project,actor);db.update("UPDATE persona SET deleted=TRUE WHERE id=?",person);error(404,this::read);
    }
    @Test void sourceCorrectionAndAbaCannotReviveOldTypedConfirmation() {
        confirmed();String first=MemoryProvider.reference(db.queryForMap("SELECT * FROM persona_memory WHERE id=?",memory)).revision();
        tx.executeWithoutResult(s->memories.memory(actor,person,memory,Map.of("status","CONFIRMED","body","Synthetic source B")));
        assertEquals(UNKNOWN,read().status());
        tx.executeWithoutResult(s->memories.memory(actor,person,memory,Map.of("status","CONFIRMED","body","Synthetic explicit fixture source A")));
        assertEquals(first,MemoryProvider.reference(db.queryForMap("SELECT * FROM persona_memory WHERE id=?",memory)).revision());
        assertEquals(UNKNOWN,read().status());String fresh=confirmed();assertEquals(3,read().evidence().get(0).revision());assertEquals(fresh,read().evidence().get(0).factId());
    }
    @Test void correctionBetweenProposalAndConfirmationIsRejected() {
        String id=candidate();tx.executeWithoutResult(s->memories.memory(actor,person,memory,Map.of("status","CONFIRMED","body","Synthetic new revision")));
        error(409,()->experiment.confirm(actor,person,id));assertEquals(UNKNOWN,read().status());
    }
    @Test void rejectedForgottenOrMissingSourceNoLongerSupports() {
        confirmed();tx.executeWithoutResult(s->memories.memory(actor,person,memory,Map.of("status","REJECTED")));assertEquals(UNKNOWN,read().status());
        tx.executeWithoutResult(s->memories.memory(actor,person,memory,Map.of("status","CONFIRMED")));confirmed();assertEquals(SUPPORTED,read().status());
        String message=db.queryForObject("SELECT source_id FROM persona_memory WHERE id=?",String.class,memory);
        tx.executeWithoutResult(s->memories.forget(actor,person,memory));assertEquals(UNKNOWN,read().status());
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM persona_message WHERE id=?",Integer.class,message));
    }
    @Test void deletedSourceMemoryAndMessageCannotLeaveSupportBehind() {
        confirmed();tx.executeWithoutResult(s->{memories.forget(actor,person,memory);db.update("DELETE FROM persona_message WHERE conversation_id=?",conversation);});
        assertEquals(UNKNOWN,read().status());
    }
    @Test void sourceConversationVersionIsCheckedButPublishedPersonaVersionIsNotSubstituted() {
        confirmed();String newer=UUID.randomUUID().toString();
        db.update("INSERT INTO persona_version(id,persona_id,status,content,creator,created_at) VALUES (?,?,'APPROVED','{}',?,2)",newer,person,actor);
        db.update("UPDATE persona SET published_id=? WHERE id=?",newer,person);assertEquals(SUPPORTED,read().status());
        db.update("UPDATE persona_conversation SET version_id=? WHERE id=?",newer,conversation);assertEquals(UNKNOWN,read().status());
    }
    @Test void explicitTypedRevocationCannotBeReconfirmed() {
        String id=confirmed();experiment.revoke(actor,person,id);experiment.revoke(actor,person,id);
        assertEquals(UNKNOWN,read().status());error(409,()->experiment.confirm(actor,person,id));
    }
    @Test void polarityConflictAndHalfOpenExpiryRetainUnknownBoundary() {
        confirmed();assertEquals(REFUTED,experiment.decide(actor,person,"synthetic-lamp-a",false,150).status());
        assertEquals(UNKNOWN,experiment.decide(actor,person,"synthetic-lamp-a",false,99).status());
        assertEquals(SUPPORTED,experiment.decide(actor,person,"synthetic-lamp-a",true,100).status());
        assertEquals(UNKNOWN,experiment.decide(actor,person,"synthetic-lamp-a",false,200).status());
        String opposite=experiment.propose(actor,person,memory,new SyntheticLampSqlExperiment.Fact("synthetic-lamp-a",false,100,200));
        experiment.confirm(actor,person,opposite);assertEquals(CONFLICT,read().status());assertEquals(2,read().evidence().size());
        experiment.revoke(actor,person,opposite);assertEquals(SUPPORTED,read().status());
        assertEquals(UNKNOWN,experiment.decide(actor,person,"synthetic-lamp-b",false,150).status());
    }
    @Test void invalidDomainAndTimeCannotBecomeAClaim() {
        for(var fact:List.of(new SyntheticLampSqlExperiment.Fact("other",true,100,200),new SyntheticLampSqlExperiment.Fact("synthetic-lamp-a",true,200,200),new SyntheticLampSqlExperiment.Fact("synthetic-lamp-a",true,-1,200)))
            error(422,()->experiment.propose(actor,person,memory,fact));
        error(422,()->experiment.decide(actor,person,"synthetic-lamp-a",true,-1));
    }
    @Test void missingStructuredValuesAreRejectedWithoutNullPointerFailure() {
        error(422,()->experiment.propose(actor,person,memory,new SyntheticLampSqlExperiment.Fact(null,true,100,200)));
        error(422,()->experiment.propose(actor,person,memory,null));
        error(422,()->experiment.decide(actor,person,null,true,150));
    }
    @Test void sourceAndTypedChangesRollbackInOneRealJdbcTransaction() {
        assertThrows(IllegalStateException.class,()->tx.executeWithoutResult(s->{
            memories.memory(actor,person,memory,Map.of("status","CONFIRMED","body","Synthetic rollback revision"));
            confirmed();assertEquals(SUPPORTED,read().status());throw new IllegalStateException("synthetic rollback");
        }));
        assertEquals(UNKNOWN,read().status());assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM synthetic_lamp_fact",Integer.class));
        assertEquals(1,db.queryForObject("SELECT MAX(revision) FROM memory_sync_intent WHERE memory_id=?",Long.class,memory));
        assertEquals("Synthetic explicit fixture source A",db.queryForObject("SELECT body FROM persona_memory WHERE id=?",String.class,memory));
    }
    @Test void confirmedAndWithdrawnStatesSurviveDatabaseShutdownAndReopen() {
        String id=confirmed();db.execute("SHUTDOWN");connect();assertEquals(SUPPORTED,read().status());
        experiment.revoke(actor,person,id);db.execute("SHUTDOWN");connect();assertEquals(UNKNOWN,read().status());
        confirmed();tx.executeWithoutResult(s->memories.forget(actor,person,memory));db.execute("SHUTDOWN");connect();assertEquals(UNKNOWN,read().status());
    }
}
