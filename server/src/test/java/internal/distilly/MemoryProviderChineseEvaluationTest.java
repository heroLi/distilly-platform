package internal.distilly;

import internal.distilly.common.json.JsonCodec;
import internal.distilly.modules.memory.service.MemoryRetrieval;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static internal.distilly.common.validation.InputChecks.items;

/** Separate semantic diagnostics from non-negotiable source/access regression gates. */
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:memory-provider-zh-eval;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE", "platform.demo=true","platform.worker-token=memory-provider-evaluation-synthetic-32-characters"})
class MemoryProviderChineseEvaluationTest {
    @Autowired MemoryRetrieval retrieval;
    @Autowired PlatformFixture platform;
    @Autowired PersonaFixture personas;
    @Autowired JsonCodec json;
    @Autowired JdbcTemplate db;
    private String stable(String key){return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)).toString();}
    private String hash(byte[] raw)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw));}
    private Path fixture(String name){var root=Path.of("../tests/fixtures");return root.resolve(name);}
    @Test void measureFrozenChineseCorpusWithoutConvertingMissesIntoSemanticSuccess() throws Exception {
        var bytes=Files.readAllBytes(fixture("memory_provider_zh_v1.json"));
        var lock=json.decode(Files.readString(fixture("memory_provider_zh_v1.lock.json")));
        assertEquals(lock.get("sha256"),hash(bytes));
        var data=json.decode(new String(bytes,StandardCharsets.UTF_8));
        String project=platform.createProject("owner","合成中文独立记忆评测").get("id").toString();
        platform.member("owner",project,"viewer","VIEWER");
        String person=personas.create("owner",project,Map.of("name","合成评测人物","family","relationship","origin","original")).get("id").toString();
        String other=personas.create("owner",project,Map.of("name","合成隔离人物","family","relationship","origin","original")).get("id").toString();
        var rows=new LinkedHashMap<String,Map<String,Object>>();
        int index=0;
        for(var m:items(data.get("memories"))){
            String key=m.get("key").toString(),id=stable("memory:"+key),source=stable("source:"+key),conversation=stable("conversation:"+key),version=stable("version:"+key);
            String actor="other_user".equals(m.get("scope"))?"owner":"viewer",p="other_persona".equals(m.get("scope"))?other:person;
            db.update("INSERT INTO persona_version(id,persona_id,status,content,creator,created_at) VALUES (?,?,'APPROVED','{}','owner',1)",version,p);
            db.update("INSERT INTO persona_conversation(id,persona_id,version_id,user_id,title,emotion,created_at) VALUES (?,?,?,?,'合成来源','{}',1)",conversation,p,version,actor);
            db.update("INSERT INTO persona_message(id,conversation_id,role,body,created_at) VALUES (?,?,'user',?,1)",source,conversation,m.get("body"));
            db.update("INSERT INTO persona_memory VALUES (?,?,?,?,?,?,?)",id,p,actor,m.get("body"),source,m.get("status"),1000L+index++);
            rows.put(id,m);
        }
        int count=db.queryForObject("SELECT COUNT(*) FROM persona_memory",Integer.class),tp=0,fp=0,fn=0,hits=0,positives=0,empty=0,falseEmpty=0;
        var results=new ArrayList<Map<String,Object>>();
        for(var c:items(data.get("cases"))){
            var returned=retrieval.retrieve("viewer",person,c.get("query").toString());
            assertTrue(returned.size()<=12);
            var actual=new LinkedHashSet<String>();
            for(var r:returned){
                var m=rows.get(r.get("id"));assertNotNull(m);
                assertEquals("self",m.get("scope"));assertEquals("CONFIRMED",m.get("status"));assertEquals(m.get("body"),r.get("body"));
                assertEquals(stable("source:"+m.get("key")),r.get("source_id"));actual.add(m.get("key").toString());
            }
            var expected=new LinkedHashSet<>((List<?>)c.get("expected"));
            int matched=(int)actual.stream().filter(expected::contains).count();
            boolean scored=!Boolean.FALSE.equals(c.get("score_quality"));
            if(scored){tp+=matched;fp+=actual.size()-matched;fn+=expected.size()-matched;
                if(expected.isEmpty()){empty++;if(!actual.isEmpty())falseEmpty++;}else{positives++;if(matched>0)hits++;}}
            results.add(Map.of("id",c.get("id"),"category",c.get("category"),"expected",expected,"actual",actual,"exact_match",actual.equals(expected),"scored",scored));
        }
        assertEquals(count,db.queryForObject("SELECT COUNT(*) FROM persona_memory",Integer.class));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM persona_job",Integer.class));
        String label=System.getProperty("memory.provider.evaluation.label","current");assertTrue(label.matches("[a-zA-Z0-9_-]{1,30}"));
        var report=new LinkedHashMap<String,Object>();
        report.put("dataset_sha256",hash(bytes));report.put("safety_gate","PASS: scope/current canonical source/bounded read only");
        report.put("quality_status",fp==0&&fn==0?"ALL_FROZEN_EXPECTATIONS_MET":"UNRESOLVED");
        report.put("provider","sql_lexical");report.put("hindsight_actual_quality","NOT_RUN");report.put("real_provider_calls",0);
        report.put("confirmed_self_memories",200);report.put("results",results);
        report.put("metrics",Map.of("tp",tp,"fp",fp,"fn",fn,"positive_hit_at_12",hits,"positive_queries",positives,"empty_expected_queries",empty,"empty_false_returns",falseEmpty,
                "precision",tp+fp==0?0.0:(double)tp/(tp+fp),"recall",tp+fn==0?0.0:(double)tp/(tp+fn)));
        Files.writeString(Path.of("target/memory-provider-zh-"+label+".json"),json.encode(report));
    }
}
