package internal.distilly;

import internal.distilly.common.json.JsonCodec;
import internal.distilly.modules.memory.service.MemoryService;
import internal.distilly.modules.memory.service.MemorySyncService;
import java.nio.file.*;
import java.util.*;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

/** Dedicated no-web/no-Worker child JVM, killed only by MemorySyncRestartTest. */
public final class MemorySyncProcessFixture {
    public static void main(String[] args) throws Exception {
        var root=Path.of(args[0]);String step=args[1];Files.createDirectories(root);
        var app=new SpringApplication(Application.class);app.setWebApplicationType(WebApplicationType.NONE);
        try(var context=app.run("--spring.datasource.url=jdbc:h2:file:"+root.resolve("db")+";DB_CLOSE_ON_EXIT=FALSE;DATABASE_TO_LOWER=TRUE",
                "--spring.datasource.username=sa","--spring.datasource.password=","--platform.demo=true",
                "--platform.worker-token=memory-sync-process-synthetic-token-32-characters","--spring.main.banner-mode=off")){
            var db=context.getBean(JdbcTemplate.class);var json=context.getBean(JsonCodec.class);
            var memories=context.getBean(MemoryService.class);var sync=context.getBean(MemorySyncService.class);
            if("prepare".equals(step)){
                var platform=context.getBean(internal.distilly.modules.workspace.service.WorkspaceService.class);
                var people=context.getBean(internal.distilly.modules.persona.service.PersonaService.class);
                String project=platform.createProject("owner","合成进程恢复").get("id").toString();
                platform.member("owner",project,"viewer","VIEWER");
                String person=people.create("owner",project,Map.of("name","合成进程人物","family","relationship","origin","original")).get("id").toString();
                String version=UUID.randomUUID().toString(),conversation=UUID.randomUUID().toString(),source=UUID.randomUUID().toString(),memory=UUID.randomUUID().toString();
                db.update("INSERT INTO persona_version(id,persona_id,status,content,creator,created_at) VALUES (?,?,'APPROVED','{}','owner',1)",version,person);
                db.update("INSERT INTO persona_conversation(id,persona_id,version_id,user_id,title,emotion,created_at) VALUES (?,?,?,'viewer','合成来源','{}',1)",conversation,person,version);
                db.update("INSERT INTO persona_message(id,conversation_id,role,body,created_at) VALUES (?,?,'user','合成历史消息保留',1)",source,conversation);
                db.update("INSERT INTO persona_memory VALUES (?,?,'viewer','合成A',?,'PENDING',1)",memory,person,source);
                memories.memory("viewer",person,memory,Map.of("status","CONFIRMED","body","合成A"));
                var first=sync.stageFixture("viewer",person,memory);sync.unknownFixture("viewer",person,first.operationId());
                memories.memory("viewer",person,memory,Map.of("status","CONFIRMED","body","合成B"));
                memories.forget("viewer",person,memory);
                var latest=sync.inspect("viewer",person,memory).get(0);
                Files.writeString(root.resolve("ready.tmp"),json.encode(Map.of("persona",person,"memory",memory,"source",source,
                        "old_operation",first.operationId().toString(),"old_revision",first.revision(),"latest_operation",latest.get("operation_id"),"latest_revision",latest.get("revision"))));
                Files.move(root.resolve("ready.tmp"),root.resolve("ready.json"),StandardCopyOption.ATOMIC_MOVE);
                new java.util.concurrent.CountDownLatch(1).await();
            }else if("recover".equals(step)){
                var saved=json.decode(Files.readString(root.resolve("ready.json")));
                String person=saved.get("persona").toString(),memory=saved.get("memory").toString();
                var latest=sync.inspect("viewer",person,memory).get(0);
                require(saved.get("latest_operation").equals(latest.get("operation_id")),"operation_changed_across_restart");
                require("DELETE".equals(latest.get("kind"))&&"PENDING".equals(latest.get("state")),"delete_not_retained");
                boolean blocked=false;try{sync.stageFixture("viewer",person,memory);}catch(ResponseStatusException error){blocked=error.getStatusCode().value()==409;}
                require(blocked,"unknown_operation_did_not_block");
                var old=sync.terminalFixture("viewer",person,UUID.fromString(saved.get("old_operation").toString()),((Number)saved.get("old_revision")).longValue(),true);
                require(Boolean.FALSE.equals(old.get("current")),"stale_ack_became_current");
                var deletion=sync.stageFixture("viewer",person,memory);
                require(deletion.operationId().toString().equals(saved.get("latest_operation"))&&deletion.revision()==3&&deletion.document()==null,"delete_reissued_or_payload_restored");
                var terminal=sync.terminalFixture("viewer",person,deletion.operationId(),deletion.revision(),true);
                require(terminal.equals(sync.terminalFixture("viewer",person,deletion.operationId(),deletion.revision(),true)),"duplicate_ack_changed_state");
                require(db.queryForObject("SELECT COUNT(*) FROM persona_memory WHERE id=?",Integer.class,memory)==0,"memory_revived");
                require(db.queryForObject("SELECT COUNT(*) FROM memory_sync_intent WHERE memory_id=? AND content IS NOT NULL",Integer.class,memory)==0,"obsolete_snapshot_retained");
                require(db.queryForObject("SELECT COUNT(*) FROM persona_message WHERE id=?",Integer.class,saved.get("source"))==1,"history_removed");
                Files.writeString(root.resolve("recovered.json"),json.encode(Map.of("status","PASS","independent_jvms",2,"forced_kills",1,
                        "operation_id_preserved",true,"unknown_blocks_after_restart",true,"late_ack_current",false,
                        "delete_revision",3,"cached_bodies",0,"original_history_retained",true,"real_provider_calls",0)));
            }else throw new IllegalArgumentException("unknown_fixture_step");
        }
    }
    private static void require(boolean condition,String code){if(!condition)throw new IllegalStateException(code);}
}
