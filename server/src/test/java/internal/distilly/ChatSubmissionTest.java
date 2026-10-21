package internal.distilly;

import internal.distilly.modules.conversation.service.ConversationService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:chat-submission;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE","platform.demo=true","platform.worker-token=chat-submission-synthetic-worker-token-32-characters"})
class ChatSubmissionTest {
 @Autowired PlatformFixture platform;
 @Autowired PersonaFixture personas;
 @Autowired ConversationService service;
 @Autowired JdbcTemplate db;
 String project,person,conversation,key;
 @BeforeEach void setup(){
  project=platform.createProject("owner","合成提交").get("id").toString();platform.member("owner",project,"viewer","VIEWER");
  person=personas.create("owner",project,Map.of("name","合成伙伴","family","colleague","origin","original","consent",false)).get("id").toString();
  personas.generate("owner",person,List.of());var job=personas.claim();
  var v=personas.complete(job.get("id").toString(),job.get("lease_token").toString(),Map.of("persona_markdown","合成设定","analysis",Map.of(),"evidence",List.of()));
  personas.review("owner",person,v.get("version_id").toString(),true);
  conversation=personas.start("viewer",person,null).get("id").toString();key=UUID.randomUUID().toString();
 }
 @AfterEach void stopJobs(){db.update("UPDATE persona_job SET status='CANCELLED',lease_token=NULL WHERE persona_id IN (SELECT id FROM persona WHERE project_id=?) AND status IN ('QUEUED','RUNNING')",project);}
 Map<String,Object> send(){return service.chat("viewer",conversation,"合成同一操作",null,key);}
 long messages(){return db.queryForObject("SELECT COUNT(*) FROM persona_message WHERE conversation_id=?",Long.class,conversation);}
 long jobs(){return db.queryForObject("SELECT COUNT(*) FROM persona_job WHERE conversation_id=?",Long.class,conversation);}
 @Test void duplicateAndConcurrentOperationAcceptsOneMessageAndOneTask()throws Exception{
  var gate=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
  try{var a=pool.submit(()->{gate.await();return send();});var b=pool.submit(()->{gate.await();return send();});gate.countDown();assertEquals(a.get(10,TimeUnit.SECONDS).get("id"),b.get(10,TimeUnit.SECONDS).get("id"));}
  finally{pool.shutdownNow();}
  assertEquals(1,messages());assertEquals(1,jobs());assertEquals(send().get("submission"),service.submission("viewer",conversation,key));
 }
 @Test void changedContentConflictsWithoutChangingAcceptedInput(){
  send();var error=assertThrows(ResponseStatusException.class,()->service.chat("viewer",conversation,"不同合成内容",null,key));assertEquals(409,error.getStatusCode().value());assertEquals("submission_conflict",error.getReason());assertEquals(1,messages());
 }
 @Test void cancelAndFailureReplayNeverRequeuesOrDuplicates(){
  String id=send().get("id").toString();var lease=personas.claim();personas.fail(id,lease.get("lease_token").toString(),"MODEL_CONFIGURATION_ERROR");
  assertEquals("FAILED",send().get("status"));personas.cancel("viewer",id);assertEquals("CANCELLED",send().get("status"));assertEquals(1,jobs());assertEquals(1,messages());
 }
 @Test void completedReplayKeepsOneAssistantReply(){
  String id=send().get("id").toString();var lease=personas.claim();personas.complete(id,lease.get("lease_token").toString(),Map.of("answer","合成已完成回答","emotion",Map.of("label","calm","intensity",.3,"reason","合成"),"memory_candidates",List.of()));
  assertEquals("SUCCEEDED",send().get("status"));assertEquals(2,messages());assertEquals(1,jobs());
 }
 @Test void sameKeyInOtherPrivateConversationIsIndependentAndPermissionIsCheckedFirst(){
  String first=send().get("id").toString();String other=personas.start("owner",person,null).get("id").toString();
  String second=service.chat("owner",other,"另一个用户的合成操作",null,key).get("id").toString();assertNotEquals(first,second);
  assertThrows(ResponseStatusException.class,()->service.submission("owner",conversation,key));assertThrows(ResponseStatusException.class,()->service.chat("owner",conversation,"合成同一操作",null,key));
  db.update("DELETE FROM member WHERE project_id=? AND user_id='viewer'",project);
  assertThrows(ResponseStatusException.class,this::send);assertThrows(ResponseStatusException.class,()->service.submission("viewer",conversation,key));
 }
 @Test void keysAreScopedAcrossPeopleAndReceiptsDoNotExposeBodies(){
  String otherPerson=personas.create("owner",project,Map.of("name","另一个合成人物","family","colleague","origin","original","consent",false)).get("id").toString();
  personas.generate("owner",otherPerson,List.of());var lease=personas.claim();
  var version=personas.complete(lease.get("id").toString(),lease.get("lease_token").toString(),Map.of("persona_markdown","另一个合成设定","analysis",Map.of(),"evidence",List.of()));
  personas.review("owner",otherPerson,version.get("version_id").toString(),true);
  String other=personas.start("owner",otherPerson,null).get("id").toString();
  String first=send().get("id").toString();String second=service.chat("owner",other,"另一人物合成输入",null,key).get("id").toString();assertNotEquals(first,second);
  assertEquals(4,service.submission("viewer",conversation,key).size());
  assertEquals(second,service.submission("owner",other,key).get("job_id"));
 }
 @Test void rejectedNewOperationHasNoReceiptOrPartialMessage(){
  send();String rejected=UUID.randomUUID().toString();assertThrows(ResponseStatusException.class,()->service.chat("viewer",conversation,"合成另一个操作",null,rejected));
  var missing=assertThrows(ResponseStatusException.class,()->service.submission("viewer",conversation,rejected));assertEquals("submission_not_found",missing.getReason());assertEquals(1,messages());assertEquals(1,jobs());
 }
 @Test void invalidIdsAndDeletionCannotRecoverOldReceipt(){
  assertThrows(ResponseStatusException.class,()->service.chat("viewer",conversation,"合成",null,"invalid"));assertEquals(0,messages());
  send();personas.deleteConversation("viewer",conversation);assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM chat_submission WHERE conversation_id=?",Integer.class,conversation));
  assertThrows(ResponseStatusException.class,()->service.submission("viewer",conversation,key));
 }
}
