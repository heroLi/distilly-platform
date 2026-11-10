package internal.distilly;

import internal.distilly.common.json.JsonCodec;
import internal.distilly.config.PlatformProperties;
import internal.distilly.modules.memory.domain.MemoryProvider;
import internal.distilly.modules.memory.domain.MemorySyncReceipt;
import internal.distilly.modules.memory.service.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

/** Three independent no-web JVM phases; receipts remain local simulation. */
final class MemorySyncConsumerCrashSteps {
    static void check(boolean value,String code){if(!value)throw new IllegalStateException(code);}
    static void save(JsonCodec json,Path root,String name,Map<String,Object> values)throws Exception{
        Files.writeString(root.resolve(name+".tmp"),json.encode(values));Files.move(root.resolve(name+".tmp"),root.resolve(name+".json"),StandardCopyOption.ATOMIC_MOVE);
    }
    static void run(ConfigurableApplicationContext context,Path root,String step,MemoryRecoveryObservation.Recorder observation)throws Exception{
        var db=context.getBean(JdbcTemplate.class);var json=context.getBean(JsonCodec.class);var sync=context.getBean(MemorySyncService.class);
        var memories=context.getBean(MemoryService.class);var target=context.getBean(OfflineMemorySyncTarget.class);
        var applies=new AtomicInteger();var reconciles=new AtomicInteger();
        MemorySyncTarget counted=new MemorySyncTarget(){
            public MemorySyncReceipt apply(MemorySyncService.Intent i){applies.incrementAndGet();return target.apply(i);}
            public MemorySyncReceipt reconcile(MemorySyncService.Intent i){reconciles.incrementAndGet();return target.reconcile(i);}
        };
        var consumer=new MemorySyncConsumer(sync,counted,context.getBean(PlatformProperties.class));
        if("consumer_prepare".equals(step)){
            observation.mark(MemoryRecoveryObservation.Stage.PREPARATION_BEGIN);
            var platform=context.getBean(internal.distilly.modules.workspace.service.WorkspaceService.class);
            var people=context.getBean(internal.distilly.modules.persona.service.PersonaService.class);
            String project=platform.createProject("owner","合成消费者崩溃").get("id").toString();platform.member("owner",project,"viewer","VIEWER");
            String person=people.create("owner",project,Map.of("name","合成消费者人物","family","relationship","origin","original")).get("id").toString();
            String version=UUID.randomUUID().toString(),conversation=UUID.randomUUID().toString(),source=UUID.randomUUID().toString(),memory=UUID.randomUUID().toString();
            db.update("INSERT INTO persona_version(id,persona_id,status,content,creator,created_at) VALUES (?,?,'APPROVED','{}','owner',1)",version,person);
            db.update("INSERT INTO persona_conversation(id,persona_id,version_id,user_id,title,emotion,created_at) VALUES (?,?,?,'viewer','合成来源','{}',1)",conversation,person,version);
            db.update("INSERT INTO persona_message(id,conversation_id,role,body,created_at) VALUES (?,?,'user','合成来源保留',1)",source,conversation);
            db.update("INSERT INTO persona_memory VALUES (?,?,'viewer','合成A',?,'PENDING',1)",memory,person,source);
            memories.memory("viewer",person,memory,Map.of("status","CONFIRMED","body","合成A"));var intent=sync.stageFixture("viewer",person,memory);
            observation.mark(MemoryRecoveryObservation.Stage.PREPARATION_END);
            observation.mark(MemoryRecoveryObservation.Stage.RESULT_WRITE_BEGIN);
            save(json,root,"consumer-ready-1",Map.of("project",project,"person",person,"memory",memory,"source",source,"operation",intent.operationId().toString()));
            observation.mark(MemoryRecoveryObservation.Stage.RESULT_WRITE_END);
            observation.mark(MemoryRecoveryObservation.Stage.WAITING_FOR_FORCE_KILL);
            new java.util.concurrent.CountDownLatch(1).await();return;
        }
        observation.mark(MemoryRecoveryObservation.Stage.RECOVERY_ASSERTIONS_BEGIN);
        var saved=json.decode(Files.readString(root.resolve("consumer-ready-1.json")));
        String person=saved.get("person").toString(),memory=saved.get("memory").toString();
        if("consumer_recover_before".equals(step)){
            check("DISPATCHED".equals(sync.inspect("viewer",person,memory).get(0).get("state")),"stage_not_persistent");
            consumer.advance("viewer",person,memory);var failed=sync.inspect("viewer",person,memory).get(0);
            check("FAILED".equals(failed.get("state"))&&"MOCK_FENCED".equals(failed.get("error_code"))&&applies.get()==0,"recovery_blindly_reapplied");
            var scope=new MemoryProvider.Scope(saved.get("project").toString(),person,"viewer");
            var row=context.getBean(internal.distilly.modules.memory.repository.MemoryRepository.class).findConfirmedSource(memory,person,"viewer").get(0);
            var late=new MemorySyncService.Intent(scope,memory,1,UUID.fromString(saved.get("operation").toString()),"RETAIN",new MemoryProvider.Document(MemoryProvider.reference(row),row.get("body").toString()));
            check(target.apply(late).outcome()==MemorySyncReceipt.Outcome.NOT_APPLIED,"late_apply_overrode_fence");
            consumer.retry("viewer",person,memory);consumer.advance("viewer",person,memory);
            check("ACKED".equals(sync.inspect("viewer",person,memory).get(0).get("state")),"explicit_retry_failed");
            memories.memory("viewer",person,memory,Map.of("status","CONFIRMED","body","合成B"));var intent=sync.stageFixture("viewer",person,memory);
            target.apply(intent); // Commit simulated side effect, deliberately omit the journal receipt.
            observation.mark(MemoryRecoveryObservation.Stage.RECOVERY_ASSERTIONS_END);
            observation.mark(MemoryRecoveryObservation.Stage.RESULT_WRITE_BEGIN);
            save(json,root,"consumer-ready-2",Map.of("operation",intent.operationId().toString(),"revision",intent.revision()));
            observation.mark(MemoryRecoveryObservation.Stage.RESULT_WRITE_END);
            observation.mark(MemoryRecoveryObservation.Stage.WAITING_FOR_FORCE_KILL);
            new java.util.concurrent.CountDownLatch(1).await();return;
        }
        var after=json.decode(Files.readString(root.resolve("consumer-ready-2.json")));
        consumer.advance("viewer",person,memory);var latest=sync.inspect("viewer",person,memory).get(0);
        check(after.get("operation").equals(latest.get("operation_id"))&&"ACKED".equals(latest.get("state"))&&applies.get()==0&&reconciles.get()==1,"lost_receipt_regenerated_work");
        var projection=db.queryForMap("SELECT * FROM memory_sync_mock_document WHERE persona_id=? AND user_id='viewer' AND memory_id=?",person,memory);
        check(person.equals(projection.get("persona_id"))&&"viewer".equals(projection.get("user_id"))&&memory.equals(projection.get("memory_id"))
                &&((Number)after.get("revision")).longValue()==((Number)projection.get("revision")).longValue()
                &&Objects.equals(db.queryForObject("SELECT fingerprint FROM memory_sync_intent WHERE operation_id=?",String.class,latest.get("operation_id")),projection.get("fingerprint")),"committed_projection_not_persistent");
        memories.forget("viewer",person,memory);consumer.advance("viewer",person,memory);
        check(db.queryForObject("SELECT COUNT(*) FROM memory_sync_mock_document WHERE memory_id=?",Integer.class,memory)==0,"simulated_delete_missing");
        check(db.queryForObject("SELECT COUNT(*) FROM memory_sync_intent WHERE memory_id=? AND content IS NOT NULL",Integer.class,memory)==0,"cached_text_retained");
        check(db.queryForObject("SELECT COUNT(*) FROM persona_memory WHERE id=?",Integer.class,memory)==0,"memory_revived");
        check(((List<?>)consumer.status("owner",person).get("items")).isEmpty(),"owner_observed_private_sync");
        observation.mark(MemoryRecoveryObservation.Stage.RECOVERY_ASSERTIONS_END);
        observation.mark(MemoryRecoveryObservation.Stage.RESULT_WRITE_BEGIN);
        save(json,root,"consumer-recovered",Map.of("status","PASS","independent_jvms",3,"forced_kills",2,"stage_before_apply_fenced",true,
                "late_apply_honors_fence",true,"after_apply_reconciled_without_replay",true,"committed_projection_persistent",true,"simulated_delete_completed",true,"cached_bodies",0,"real_provider_calls",0));
        observation.mark(MemoryRecoveryObservation.Stage.RESULT_WRITE_END);
    }
}
