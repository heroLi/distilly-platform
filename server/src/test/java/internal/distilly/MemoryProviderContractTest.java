package internal.distilly;

import internal.distilly.infrastructure.memory.HindsightMemoryProvider;
import internal.distilly.modules.memory.domain.MemoryProvider;
import internal.distilly.modules.memory.domain.MemoryProvider.*;
import internal.distilly.modules.memory.repository.MemoryRepository;
import internal.distilly.modules.memory.service.*;
import internal.distilly.modules.persona.service.PersonaAccessService;
import internal.distilly.common.json.JsonCodec;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:memory-provider-contract;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE", "platform.demo=true","platform.worker-token=memory-provider-contract-synthetic-32-characters"})
class MemoryProviderContractTest {
    @Autowired MemoryRepository repository;
    @Autowired SqlMemoryProvider sql;
    @Autowired PersonaAccessService access;
    @Autowired MemoryService memories;
    @Autowired PlatformFixture platform;
    @Autowired PersonaFixture personas;
    @Autowired JdbcTemplate db;
    @Autowired JsonCodec json;
    String project,person,conversation,source,memory;
    Scope scope;
    @BeforeEach void setup(){
        project=platform.createProject("owner","合成适配契约").get("id").toString();
        platform.member("owner",project,"viewer","VIEWER");
        person=personas.create("owner",project,Map.of("name","合成契约人物","family","relationship","origin","original")).get("id").toString();
        String version=UUID.randomUUID().toString();conversation=UUID.randomUUID().toString();source=UUID.randomUUID().toString();memory=UUID.randomUUID().toString();
        db.update("INSERT INTO persona_version(id,persona_id,status,content,creator,created_at) VALUES (?,?,'APPROVED','{}','owner',1)",version,person);
        db.update("INSERT INTO persona_conversation(id,persona_id,version_id,user_id,title,emotion,created_at) VALUES (?,?,?,'viewer','合成来源','{}',1)",conversation,person,version);
        db.update("INSERT INTO persona_message(id,conversation_id,role,body,created_at) VALUES (?,?,'user','青岛海边拍照',1)",source,conversation);
        db.update("INSERT INTO persona_memory VALUES (?,?,'viewer','青岛海边拍照',?,'CONFIRMED',1)",memory,person,source);
        scope=new Scope(project,person,"viewer");
    }
    SourceRef current(){return MemoryProvider.reference(repository.findConfirmedSource(memory,person,"viewer").get(0));}
    Map<String,Object> fact(SourceRef ref){return Map.of("id","external-fact","type","world","document_id",HindsightMemoryProvider.documentId(ref),"text","不可信的供应商改写，不得进入上下文","metadata",Map.of("memory_id",ref.memoryId(),"source_id",ref.sourceId(),"source_version",ref.revision()));}
    MemoryRetrieval using(MemoryProvider provider){return new MemoryRetrieval(repository,access,provider,sql);}
    @Test void validProviderReferenceReturnsOnlyCanonicalSqlBody(){
        var provider=new HindsightMemoryProvider(command->{assertEquals(scope.bankId(),command.bankId());assertEquals("recall",command.operation());return Map.of("results",List.of(fact(current())));});
        var rows=using(provider).retrieve("viewer",person,"任意合成查询");
        assertEquals(1,rows.size());assertEquals("青岛海边拍照",rows.get(0).get("body"));assertEquals(source,rows.get(0).get("source_id"));
    }
    @Test void retainUsesStableDocumentIdentityAndExplicitRetryOperationId() throws Exception {
        var document=new Document(current(),"青岛海边拍照");UUID operation=UUID.randomUUID();
        var first=HindsightMemoryProvider.retain(scope,document,operation);
        assertEquals(first,HindsightMemoryProvider.retain(scope,document,operation));
        assertEquals(operation.toString(),first.arguments().get("operation_id"));assertEquals(true,first.arguments().get("async"));
        var contract=json.decode(java.nio.file.Files.readString(java.nio.file.Path.of("../tests/fixtures/hindsight_contract_v1.json")));
        assertTrue(first.arguments().keySet().containsAll((List<?>)contract.get("retain_required")));
        var item=(Map<?,?>)((List<?>)first.arguments().get("items")).get(0);
        assertTrue(item.keySet().containsAll((List<?>)contract.get("item_required")));
        assertEquals(contract.get("update_mode"),item.get("update_mode"));
        assertEquals(contract.get("async"),first.arguments().get("async"));
        memories.memory("viewer",person,memory,Map.of("status","CONFIRMED","body","青岛海边拍照改为周六"));
        var corrected=HindsightMemoryProvider.retain(scope,new Document(current(),"青岛海边拍照改为周六"),UUID.randomUUID());
        assertNotEquals(first.arguments(),corrected.arguments());
        assertEquals(HindsightMemoryProvider.documentId(document.source()),HindsightMemoryProvider.documentId(current()));
        assertEquals(HindsightMemoryProvider.delete(scope,current()),HindsightMemoryProvider.delete(scope,current()));
    }
    @Test void lateRecallAfterCorrectionDoesNotReturnPreviousVersion(){
        var old=current();
        var provider=new HindsightMemoryProvider(command->{memories.memory("viewer",person,memory,Map.of("status","CONFIRMED","body","改为周六海边拍照"));return Map.of("results",List.of(fact(old)));});
        assertTrue(using(provider).retrieve("viewer",person,"青岛摄影").isEmpty());
    }
    @Test void withdrawnAndForgottenDocumentsAreRejectedEvenAfterLateRetainFixture(){
        var old=current();var result=Map.<String,Object>of("results",List.of(fact(old)));
        memories.memory("viewer",person,memory,Map.of("status","REJECTED"));
        assertTrue(using(new HindsightMemoryProvider(c->result)).retrieve("viewer",person,"青岛").isEmpty());
        memories.forget("viewer",person,memory);
        assertTrue(using(new HindsightMemoryProvider(c->result)).retrieve("viewer",person,"青岛").isEmpty());
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM persona_memory WHERE id=?",Integer.class,memory));
    }
    @Test void deletingSourceConversationRejectsLateReference(){
        var old=current();personas.deleteConversation("viewer",conversation);
        assertTrue(using(new HindsightMemoryProvider(c->Map.of("results",List.of(fact(old))))).retrieve("viewer",person,"青岛").isEmpty());
    }
    @Test void ownerCannotRecallAnotherUsersReferencesAndBankIsScopeSpecific(){
        var old=current();var ownerScope=new Scope(project,person,"owner");assertNotEquals(scope.bankId(),ownerScope.bankId());
        assertNotEquals(scope.bankId(),new Scope("other",person,"viewer").bankId());assertNotEquals(scope.bankId(),new Scope(project,"other","viewer").bankId());
        assertTrue(using(new HindsightMemoryProvider(c->Map.of("results",List.of(fact(old))))).retrieve("owner",person,"青岛").isEmpty());
    }
    @Test void revocationWhileProviderFixtureIsRunningIsRecheckedBeforeReturn(){
        var old=current();var provider=new HindsightMemoryProvider(c->{db.update("DELETE FROM member WHERE project_id=? AND user_id='viewer'",project);return Map.of("results",List.of(fact(old)));});
        assertEquals(403,assertThrows(ResponseStatusException.class,()->using(provider).retrieve("viewer",person,"青岛")).getStatusCode().value());
    }
    @Test void wrongSourceOrDocumentOrMissingMetadataCannotEnterContext(){
        var ref=current();
        var wrong=new HashMap<>(fact(ref));wrong.put("document_id","wrong");
        assertTrue(using(new HindsightMemoryProvider(c->Map.of("results",List.of(wrong)))).retrieve("viewer",person,"青岛").isEmpty());
        assertTrue(using((s,q)->List.of()).retrieve("viewer",person,"青岛").isEmpty());
        assertTrue(using((s,q)->List.of(new SourceRef(memory,"wrong",ref.revision()))).retrieve("viewer",person,"青岛").isEmpty());
        assertTrue(using(new HindsightMemoryProvider(c->Map.of("results",List.of(Map.of("text","无来源"))))).retrieve("viewer",person,"青岛").isEmpty());
    }
    @Test void disabledOrMalformedProviderFallsBackWithoutEnablingNetwork(){
        assertEquals(1,using(new HindsightMemoryProvider()).retrieve("viewer",person,"青岛海边拍照").size());
        assertEquals(1,using(new HindsightMemoryProvider(c->Map.of())).retrieve("viewer",person,"青岛海边拍照").size());
        assertThrows(IllegalStateException.class,()->using((s,q)->{throw new IllegalStateException("database_or_programming_error");}).retrieve("viewer",person,"青岛"));
        assertEquals(403,assertThrows(ResponseStatusException.class,()->using((s,q)->{throw new ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN);}).retrieve("viewer",person,"青岛")).getStatusCode().value());
    }
    @Test void deduplicatesFactsAndNeverUsesReflectOrObservationText(){
        var fact=fact(current());var observation=new HashMap<>(fact);observation.put("type","observation");
        var provider=new HindsightMemoryProvider(c->{assertEquals(List.of("world","experience"),c.arguments().get("types"));return Map.of("results",List.of(fact,fact,observation));});
        assertEquals(1,using(provider).retrieve("viewer",person,"青岛").size());
    }
}
