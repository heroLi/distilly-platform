package internal.distilly;

import internal.distilly.common.json.JsonCodec;
import internal.distilly.modules.memory.domain.MemoryProvider;
import internal.distilly.modules.memory.repository.MemoryRepository;
import internal.distilly.modules.memory.service.*;
import internal.distilly.modules.persona.service.PersonaAccessService;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import static internal.distilly.common.validation.InputChecks.items;
import static org.junit.jupiter.api.Assertions.*;

/** Dev comparison only. The same executor must not call this a blind holdout. */
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:memory-m0-shadow;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "platform.demo=true","platform.worker-token=memory-shadow-synthetic-worker-token-32-characters"})
class MemoryM0ShadowComparisonTest {
    @Autowired PlatformFixture platform;
    @Autowired PersonaFixture personas;
    @Autowired MemoryRepository repository;
    @Autowired PersonaAccessService access;
    @Autowired SqlMemoryProvider baseline;
    @Autowired MemoryRetrieval productionRetrieval;
    @Autowired JdbcTemplate db;
    @Autowired JsonCodec json;
    record World(String project,String person,String other,String outside) {}
    record Seed(String id,String source,Map<String,Object> fixture) {}
    private String hash(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    private void report(String name,Object value)throws Exception{Path root=Path.of("target/memory-m0-shadow");Files.createDirectories(root);Files.writeString(root.resolve(name+".json"),json.encode(value));}
    private String person(String project){
        String p=personas.create("owner",project,Map.of("name","合成shadow人物","family","relationship","origin","original")).get("id").toString();
        String v=UUID.randomUUID().toString();db.update("INSERT INTO persona_version(id,persona_id,status,content,creator,created_at) VALUES (?,?,'APPROVED',?,'owner',1)",v,p,json.encode(Map.of("persona_markdown","合成角色。")));db.update("UPDATE persona SET published_id=? WHERE id=?",v,p);return p;
    }
    private World world(){
        String project=platform.createProject("owner","合成shadow空间").get("id").toString();platform.member("owner",project,"viewer","VIEWER");
        String otherProject=platform.createProject("owner","合成shadow隔离空间").get("id").toString();platform.member("owner",otherProject,"viewer","VIEWER");
        return new World(project,person(project),person(project),person(otherProject));
    }
    private Seed seed(World w,Map<String,Object> m,long time){
        String scope=m.get("scope").toString();String p="other_persona".equals(scope)?w.other():"other_project".equals(scope)?w.outside():w.person();
        String actor="other_user".equals(scope)?"owner":"viewer",sourceActor="source_other_user".equals(scope)?"owner":actor;
        String sourcePerson="source_other_persona".equals(scope)?w.other():p;
        String conversation=personas.start(sourceActor,sourcePerson,null).get("id").toString(),source=UUID.randomUUID().toString(),id=UUID.randomUUID().toString();
        db.update("INSERT INTO persona_message(id,conversation_id,role,body,created_at) VALUES (?,?,'user',?,?)",source,conversation,m.get("body"),time);
        db.update("INSERT INTO persona_memory VALUES (?,?,?,?,?,?,?)",id,p,actor,m.get("body"),source,m.get("status"),time);
        return new Seed(id,source,m);
    }
    private List<String> checkedKeys(List<Map<String,Object>> rows,Map<String,Seed> byId){
        assertTrue(rows.size()<=12);var keys=new ArrayList<String>();
        for(var row:rows){var s=byId.get(row.get("id"));assertNotNull(s);assertEquals("self",s.fixture().get("scope"));assertEquals("CONFIRMED",s.fixture().get("status"));assertEquals(s.source(),row.get("source_id"));assertEquals(s.fixture().get("body"),row.get("body"));keys.add(s.fixture().get("key").toString());}
        return keys;
    }
    @Test void compareExactlyOneFrozenCandidateOnExposedDevelopmentData()throws Exception{
        Path root=Path.of("..");var freeze=json.decode(Files.readString(root.resolve("tests/fixtures/memory_m0_shadow_v1.freeze.json")));
        assertEquals("HAN_SPAN_ONE_BRIDGE_V1",freeze.get("candidate_id"));assertEquals(1,((Number)freeze.get("candidate_count")).intValue());assertEquals("NOT_AUTHORED_NOT_RUN",freeze.get("holdout_status"));
        for(var e:((Map<?,?>)freeze.get("source_sha256")).entrySet())assertEquals(e.getValue(),hash(Files.readAllBytes(root.resolve(e.getKey().toString()))));
        byte[] raw=Files.readAllBytes(root.resolve("tests/fixtures/memory_m0_dev_v1.json"));assertEquals(freeze.get("development_sha256"),hash(raw));var data=json.decode(new String(raw,java.nio.charset.StandardCharsets.UTF_8));
        assertEquals("development",data.get("split"));var future=(Map<?,?>)data.get("future_holdout");assertEquals(0,((Number)future.get("case_count")).intValue());
        var w=world();var byId=new LinkedHashMap<String,Seed>();long created=1;
        for(var m:items(data.get("memories"))){var s=seed(w,m,created++);byId.put(s.id(),s);}
        var shadow=new MemorySpanShadowProvider(repository,baseline);var shadowRetrieval=new MemoryRetrieval(repository,access,shadow,baseline);
        var a=new MemoryDiagnosticMetrics();var b=new MemoryDiagnosticMetrics();var categoriesA=new LinkedHashMap<String,MemoryDiagnosticMetrics>();var categoriesB=new LinkedHashMap<String,MemoryDiagnosticMetrics>();
        var cases=new ArrayList<Map<String,Object>>();int addedRelevant=0,addedNotGold=0,negativeChanged=0;
        for(var c:items(data.get("cases"))){
            String query=c.get("query").toString(),category=c.get("category").toString();var gold=((List<?>)c.get("expected")).stream().map(Object::toString).toList();
            var left=checkedKeys(productionRetrieval.retrieve("viewer",w.person(),query),byId);var right=checkedKeys(shadowRetrieval.retrieve("viewer",w.person(),query),byId);
            assertEquals(left,right.subList(0,left.size()),"the production result prefix must not be evicted or reranked");
            if("hard_access".equals(category)){assertTrue(left.isEmpty());assertTrue(right.isEmpty());}
            a.add(gold,left);b.add(gold,right);categoriesA.computeIfAbsent(category,k->new MemoryDiagnosticMetrics()).add(gold,left);categoriesB.computeIfAbsent(category,k->new MemoryDiagnosticMetrics()).add(gold,right);
            var added=right.stream().filter(key->!left.contains(key)).toList();addedRelevant+=(int)added.stream().filter(gold::contains).count();addedNotGold+=(int)added.stream().filter(key->!gold.contains(key)).count();if(gold.isEmpty()&&!left.equals(right))negativeChanged++;
            cases.add(Map.of("id",c.get("id"),"family_id",c.get("family_id"),"category",category,"gold",gold,"A",left,"B",right,"added",added,"strict_support_is_not_answer_scoring",true));
        }
        var categories=new LinkedHashMap<String,Object>();categoriesA.forEach((k,v)->categories.put(k,Map.of("A",v.report(),"B",categoriesB.get(k).report())));
        var out=new LinkedHashMap<String,Object>();out.put("candidate_id",freeze.get("candidate_id"));out.put("freeze",freeze);out.put("A",a.report());out.put("B",b.report());out.put("by_category",categories);out.put("cases",cases);
        out.put("changes",Map.of("added_relevant_sources",addedRelevant,"added_not_in_gold_sources",addedNotGold,"negative_queries_changed",negativeChanged));
        out.put("scope","EXPOSED_DEVELOPMENT_ONLY_NOT_BLIND");out.put("production_default_changed",false);out.put("quality_promotion","NONE_REGARDLESS_OF_DEVELOPMENT_GAIN");out.put("holdout","NOT_AUTHORED_NOT_RUN");out.put("real_provider_calls",0);report("development-comparison",out);
    }
    @Test void canonicalSourceDeletionRevisionAndPermissionChecksRemainAuthoritative()throws Exception{
        var results=new LinkedHashMap<String,Object>();
        for(String change:List.of("correct","delete","revoke")){
            var w=world();var s=seed(w,Map.of("key","safety","scope","self","status","CONFIRMED","body","合成记录：墨竹的岚桥。"),1);
            var shadow=new MemorySpanShadowProvider(repository,baseline);
            assertTrue(productionRetrieval.retrieve("viewer",w.person(),"墨竹岚桥").isEmpty());
            assertEquals(1,new MemoryRetrieval(repository,access,shadow,baseline).retrieve("viewer",w.person(),"墨竹岚桥").size());
            MemoryProvider changed=(scope,query)->{
                var refs=shadow.recall(scope,query);
                if("correct".equals(change))personas.memory("viewer",w.person(),s.id(),Map.of("status","CONFIRMED","body","合成已纠正的另一条内容。"));
                else if("delete".equals(change))personas.forget("viewer",w.person(),s.id());
                else db.update("DELETE FROM member WHERE project_id=? AND user_id='viewer'",w.project());
                return refs;
            };
            var guarded=new MemoryRetrieval(repository,access,changed,baseline);
            if("revoke".equals(change))assertEquals(403,assertThrows(ResponseStatusException.class,()->guarded.retrieve("viewer",w.person(),"墨竹岚桥")).getStatusCode().value());
            else assertTrue(guarded.retrieve("viewer",w.person(),"墨竹岚桥").isEmpty());
            results.put(change,"CURRENT_SQL_OR_ACCESS_REVALIDATION_PRESERVED");
        }
        report("source-safety",Map.of("results",results,"scope","synthetic test-only provider callback between recall and Java hydration; not browser or external concurrency","real_provider_calls",0,"production_changes",false));
    }
}
