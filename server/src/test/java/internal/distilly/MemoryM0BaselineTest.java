package internal.distilly;

import internal.distilly.common.json.JsonCodec;
import internal.distilly.common.search.TextRelevance;
import internal.distilly.config.PlatformProperties;
import internal.distilly.modules.conversation.service.ContextAssembler;
import internal.distilly.modules.memory.domain.MemoryProvider;
import internal.distilly.modules.memory.repository.MemoryRepository;
import internal.distilly.modules.memory.service.*;
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

/** Development-only synthetic diagnostics. Held-out questions do not exist in this batch. */
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:memory-m0-baseline;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "platform.demo=true","platform.worker-token=memory-m0-synthetic-worker-token-32-characters"})
class MemoryM0BaselineTest {
    @Autowired PlatformFixture platform;
    @Autowired PersonaFixture personas;
    @Autowired MemoryRetrieval retrieval;
    @Autowired SqlMemoryProvider provider;
    @Autowired MemoryRepository memories;
    @Autowired ContextAssembler contexts;
    @Autowired JdbcTemplate db;
    @Autowired JsonCodec json;
    record World(String project,String person,String other,String outside,String version,String conversation) {}
    record Seed(String id,String source,Map<String,Object> fixture) {}
    private String hash(byte[] raw)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw));}
    private void report(String name,Object value)throws Exception{
        Path folder=Path.of("target/memory-m0-baseline");Files.createDirectories(folder);Files.writeString(folder.resolve(name+".json"),json.encode(value));
    }
    private String person(String project,String name){
        String p=personas.create("owner",project,Map.of("name",name,"family","relationship","origin","original")).get("id").toString();
        String v=UUID.randomUUID().toString();
        db.update("INSERT INTO persona_version(id,persona_id,status,content,creator,created_at) VALUES (?,?,'APPROVED',?,'owner',1)",v,p,json.encode(Map.of("persona_markdown","合成人物：不知道就说明不足。")));
        db.update("UPDATE persona SET published_id=? WHERE id=?",v,p);return p;
    }
    private World world(){
        String project=platform.createProject("owner","合成M0开发集").get("id").toString();platform.member("owner",project,"viewer","VIEWER");
        String p=person(project,"合成开发人物"),other=person(project,"合成其他人物");
        String outsideProject=platform.createProject("owner","合成其他空间").get("id").toString();platform.member("owner",outsideProject,"viewer","VIEWER");
        String outside=person(outsideProject,"合成空间隔离人物");
        return new World(project,p,other,outside,db.queryForObject("SELECT published_id FROM persona WHERE id=?",String.class,p),personas.start("viewer",p,null).get("id").toString());
    }
    private Seed seed(World w,Map<String,Object> m,long time){
        String scope=m.get("scope").toString();
        String memoryPerson="other_persona".equals(scope)?w.other():"other_project".equals(scope)?w.outside():w.person();
        String memoryActor="other_user".equals(scope)?"owner":"viewer";
        String sourcePerson="source_other_persona".equals(scope)?w.other():memoryPerson;
        String sourceActor="source_other_user".equals(scope)?"owner":memoryActor;
        String c=personas.start(sourceActor,sourcePerson,null).get("id").toString();
        String source=UUID.randomUUID().toString(),id=UUID.randomUUID().toString();
        db.update("INSERT INTO persona_message(id,conversation_id,role,body,created_at) VALUES (?,?,'user',?,?)",source,c,m.get("body"),time);
        db.update("INSERT INTO persona_memory VALUES (?,?,?,?,?,?,?)",id,memoryPerson,memoryActor,m.get("body"),source,m.get("status"),time);
        return new Seed(id,source,m);
    }
    private Map<String,Object> reply(List<Map<String,Object>> candidates){return Map.of("answer","合成协议回应。","emotion",Map.of("label","calm","intensity",0.2,"reason","合成诊断"),"memory_candidates",candidates,"memory_citations",List.of(),"experience_citations",List.of(),"citations",List.of());}
    private List<String> keys(List<Map<String,Object>> rows,Map<String,Seed> byId){return rows.stream().map(r->byId.get(r.get("id")).fixture().get("key").toString()).toList();}

    @Test void frozenDevelopmentContractMeasuresCoverageAndActualFilteringWithoutAQualityGate()throws Exception{
        Path fixtures=Path.of("../tests/fixtures");byte[] raw=Files.readAllBytes(fixtures.resolve("memory_m0_dev_v1.json"));
        var lock=json.decode(Files.readString(fixtures.resolve("memory_m0_dev_v1.lock.json")));assertEquals(lock.get("sha256"),hash(raw));
        var data=json.decode(new String(raw,java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(Boolean.TRUE,lock.get("frozen_before_baseline_execution"));
        assertEquals(Boolean.TRUE,data.get("synthetic"));assertEquals("development",data.get("split"));assertEquals(Boolean.FALSE,data.get("blind_holdout"));assertEquals(Boolean.FALSE,data.get("quality_thresholds_adopted"));
        var legacy=(Map<?,?>)lock.get("preserved_legacy_sha256");
        for(var entry:legacy.entrySet())assertEquals(entry.getValue(),hash(Files.readAllBytes(Path.of("..").resolve(entry.getKey().toString()))));
        var families=items(data.get("families"));assertEquals(8,families.size());
        var familyIds=new HashSet<String>();var sourceGroups=new HashSet<String>();
        for(var f:families){assertEquals("development",f.get("split"));assertTrue(familyIds.add(f.get("family_id").toString()));assertTrue(sourceGroups.add(f.get("source_group_id").toString()));}
        var holdout=(Map<?,?>)data.get("future_holdout");assertEquals("NOT_AUTHORED_NOT_RUN",holdout.get("status"));assertEquals(0,((Number)holdout.get("case_count")).intValue());
        for(Object reserved:(List<?>)holdout.get("reserved_family_ids"))assertFalse(familyIds.contains(reserved));
        var w=world();var byId=new LinkedHashMap<String,Seed>();var byKey=new LinkedHashMap<String,Seed>();long time=1;
        for(var m:items(data.get("memories"))){var s=seed(w,m,time++);byId.put(s.id(),s);assertNull(byKey.put(m.get("key").toString(),s));}
        var eligible=new LinkedHashSet<String>();memories.scanConfirmedForRecall(w.person(),"viewer",row->eligible.add(row.get("id").toString()));
        assertEquals(8,eligible.size());assertEquals(7,byId.size()-eligible.size());
        var total=new MemoryDiagnosticMetrics();var byCategory=new LinkedHashMap<String,MemoryDiagnosticMetrics>();
        var rows=new ArrayList<Map<String,Object>>();var caseIds=new HashSet<String>();var familyCounts=new HashMap<String,Integer>();var positiveCounts=new HashMap<String,Integer>();
        int hardAccessReturns=0,revalidationDropped=0,budgetDropped=0;
        var lostGold=new LinkedHashMap<String,Integer>();for(String stage:List.of("sql_source_eligibility","lexical_coverage","provider_top12","canonical_revalidation","context_budget"))lostGold.put(stage,0);
        for(var c:items(data.get("cases"))){
            String family=c.get("family_id").toString(),category=c.get("category").toString();
            assertTrue(familyIds.contains(family));assertEquals("development",c.get("split"));assertTrue(caseIds.add(c.get("id").toString()));
            assertEquals(families.stream().filter(f->family.equals(f.get("family_id"))).findFirst().orElseThrow().get("source_group_id"),c.get("source_group_id"));
            familyCounts.merge(family,1,Integer::sum);
            var gold=((List<?>)c.get("expected")).stream().map(Object::toString).toList();if(!gold.isEmpty())positiveCounts.merge(family,1,Integer::sum);
            String query=c.get("query").toString();var terms=TextRelevance.recallTerms(query);
            var refs=provider.recall(new MemoryProvider.Scope(w.project(),w.person(),"viewer"),query);
            var selected=retrieval.retrieve("viewer",w.person(),query);assertTrue(selected.size()<=12);
            for(var selectedRow:selected){var s=byId.get(selectedRow.get("id"));assertNotNull(s);assertEquals("self",s.fixture().get("scope"));assertEquals("CONFIRMED",s.fixture().get("status"));assertEquals(s.source(),selectedRow.get("source_id"));assertEquals(s.fixture().get("body"),selectedRow.get("body"));}
            var actual=keys(selected,byId);total.add(gold,actual);byCategory.computeIfAbsent(category,k->new MemoryDiagnosticMetrics()).add(gold,actual);
            if("hard_access".equals(category)){assertTrue(actual.isEmpty(),"existing source/scope invariant");hardAccessReturns+=actual.size();}
            var payload=contexts.assemble(Map.of("persona_markdown","合成诊断人物"),w.version(),query,Map.of(),true,List.of(),selected,List.of(),List.of(),false);
            var kept=keys(items(payload.get("memories")),byId);revalidationDropped+=refs.size()-selected.size();budgetDropped+=selected.size()-kept.size();
            var traces=new ArrayList<Map<String,Object>>();
            for(String key:gold){var s=byKey.get(key);assertNotNull(s);assertEquals(family,s.fixture().get("family_id"));assertEquals(c.get("source_group_id"),s.fixture().get("source_group_id"));assertTrue(eligible.contains(s.id()));
                int matched=TextRelevance.score(terms,s.fixture().get("body").toString());
                boolean coverage=TextRelevance.sufficientRecallCoverage(terms,matched),providerSelected=refs.stream().anyMatch(ref->ref.memoryId().equals(s.id()));
                String firstMissing=!coverage?"lexical_coverage":!providerSelected?"provider_top12":!actual.contains(key)?"canonical_revalidation":!kept.contains(key)?"context_budget":"NONE";
                if(!"NONE".equals(firstMissing))lostGold.merge(firstMissing,1,Integer::sum);
                traces.add(Map.of("key",key,"eligible",true,"query_term_count",terms.size(),"matched_terms",matched,"existing_required_matches",(int)Math.ceil(terms.size()*0.75),"coverage_pass",coverage,"provider_selected",providerSelected,"canonical_selected",actual.contains(key),"context_selected",kept.contains(key),"first_missing_stage",firstMissing));
            }
            var row=new LinkedHashMap<String,Object>(Map.of("id",c.get("id"),"family_id",family,"category",category,"gold",gold,"actual",actual,"query_terms",terms,"gold_stages",traces,"provider_candidates",refs.size(),"canonical_candidates",selected.size(),"context_candidates",kept.size()));
            var returnedEvidence=new ArrayList<Map<String,Object>>();
            for(String key:actual)returnedEvidence.add(Map.of("key",key,"matched_terms",TextRelevance.score(terms,byKey.get(key).fixture().get("body").toString()),"query_term_count",terms.size(),"existing_required_matches",(int)Math.ceil(terms.size()*0.75),"in_gold",gold.contains(key)));
            row.put("returned_lexical_evidence",returnedEvidence);rows.add(row);
        }
        assertEquals(32,caseIds.size());for(String family:familyIds){assertEquals(4,familyCounts.get(family));assertEquals(2,positiveCounts.get(family));}
        var categories=new LinkedHashMap<String,Object>();byCategory.forEach((k,v)->categories.put(k,v.report()));
        var report=new LinkedHashMap<String,Object>();report.put("dataset_sha256",hash(raw));report.put("split","DEVELOPMENT_ONLY_NOT_BLIND");report.put("quality_acceptance","NOT_EVALUATED_NO_THRESHOLD_ADOPTED");report.put("provider","unchanged_sql_lexical");report.put("metrics",total.report());report.put("by_category",categories);report.put("cases",rows);
        report.put("filtering",Map.of("corpus_rows",byId.size(),"sql_eligible",eligible.size(),"sql_scope_status_source_excluded",byId.size()-eligible.size(),"hard_access_returns",hardAccessReturns,"stable_run_canonical_drops",revalidationDropped,"normal_context_budget_drops",budgetDropped));
        report.put("first_missing_gold_stage_counts",lostGold);
        report.put("unmeasured",List.of("future holdout not authored or run","model answer correctness/citation support","real Hindsight or dense/reranker quality","concurrent provider mutation in this stable baseline","production p95/RSS"));report.put("real_provider_calls",0);report("dev-baseline",report);
    }

    @Test void top12AndBudgetLossControlsRemainSeparateFromDevelopmentQuality()throws Exception{
        var w=world();String oldest=null;
        for(int i=0;i<13;i++){var s=seed(w,Map.of("key","rank-"+i,"scope","self","status","CONFIRMED","body","合成排序探针：玄钛定位钮，序号 "+i),i+1);if(i==0)oldest=s.id();}
        var ranked=retrieval.retrieve("viewer",w.person(),"玄钛定位钮");assertEquals(12,ranked.size());String oldestId=oldest;assertTrue(ranked.stream().noneMatch(r->oldestId.equals(r.get("id"))));
        var local=new PlatformProperties();local.setContextBudgetUnits(16384);local.setReplyOutputTokens(2048);
        var bounded=new ContextAssembler(json,local);var large=Map.<String,Object>of("id","synthetic-large","body","合成大段资料".repeat(200),"source_id","synthetic-source","origin","confirmed_interaction");
        var payload=bounded.assemble(Map.of(),"synthetic-version","合成预算探针",Map.of(),true,List.of(),List.of(large),List.of(),List.of(),false);
        assertTrue(items(payload.get("memories")).isEmpty());assertEquals(1,((Number)((Map<?,?>)payload.get("context_budget")).get("omitted_memories")).intValue());
        report("stage-probes",Map.of("top12_probe",Map.of("eligible_matching_rows",13,"returned",12,"oldest_tie_rank_excluded",true),"budget_probe",Map.of("fixture_budget_units",16384,"production_budget_changed",false,"candidate_memories",1,"included",0,"omitted",1),"not_in_dev_metrics",true,"real_provider_calls",0));
    }

    @Test void deletingMemoryStopsRecallButExistingSourceCanStillEnterActualHistoryPayload()throws Exception{
        var w=world();String fact="合成记忆：约定把玄砂风铃放在北侧木架。";
        var first=personas.chat("viewer",w.conversation(),fact);var lease=personas.claim();assertEquals(first.get("id"),lease.get("id"));
        personas.complete(first.get("id").toString(),lease.get("lease_token").toString(),reply(List.of(Map.of("body",fact,"quote",fact))));
        var pending=personas.memories("viewer",w.person());assertEquals(1,pending.size());String memory=pending.get(0).get("id").toString();
        personas.memory("viewer",w.person(),memory,Map.of("status","CONFIRMED","expected_revision",pending.get(0).get("revision")));
        String source=db.queryForObject("SELECT source_id FROM persona_memory WHERE id=?",String.class,memory);
        var held=personas.chat("viewer",w.conversation(),"玄砂风铃");var heldLease=personas.claim();assertEquals(held.get("id"),heldLease.get("id"));assertEquals(1,items(((Map<?,?>)heldLease.get("payload")).get("memories")).size());
        personas.forget("viewer",w.person(),memory);
        assertEquals("CANCELLED",personas.job("viewer",held.get("id").toString()).get("status"));
        assertEquals(409,assertThrows(ResponseStatusException.class,()->personas.complete(held.get("id").toString(),heldLease.get("lease_token").toString(),reply(List.of()))).getStatusCode().value());
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM persona_memory WHERE id=?",Integer.class,memory));assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM persona_message WHERE id=?",Integer.class,source));
        var next=personas.chat("viewer",w.conversation(),"请继续刚才的话题。");var sameLease=personas.claim();assertEquals(next.get("id"),sameLease.get("id"));
        var same=(Map<?,?>)sameLease.get("payload");assertTrue(items(same.get("memories")).isEmpty());
        boolean sameHistory=items(same.get("history")).stream().anyMatch(row->fact.equals(row.get("body")));assertTrue(sameHistory,"baseline observation, not a new deletion policy");
        assertFalse(same.get("message").toString().contains("玄砂风铃"));personas.complete(next.get("id").toString(),sameLease.get("lease_token").toString(),reply(List.of()));
        String fresh=personas.start("viewer",w.person(),null).get("id").toString();var freshJob=personas.chat("viewer",fresh,"请开始新的中性话题。");var freshLease=personas.claim();assertEquals(freshJob.get("id"),freshLease.get("id"));
        var freshPayload=(Map<?,?>)freshLease.get("payload");assertTrue(items(freshPayload.get("memories")).isEmpty());assertTrue(items(freshPayload.get("history")).isEmpty());
        personas.complete(freshJob.get("id").toString(),freshLease.get("lease_token").toString(),reply(List.of()));
        report("deletion-payload",Map.of("status","BASELINE_OBSERVATION_NOT_PRODUCT_POLICY","memory_row_removed",true,"source_message_retained",true,"held_job_cancelled",true,"late_completion_status",409,"same_conversation",Map.of("deleted_memory_selected",false,"source_text_in_history",sameHistory,"current_message_contains_fact",false),"fresh_conversation",Map.of("deleted_memory_selected",false,"source_text_in_history",false),"real_provider_calls",0,"answer_reuse","NOT_MEASURED_NO_MODEL_CALL","product_decision","Whether to suppress retained source history after memory removal remains undecided; no history was rewritten."));
    }
}
