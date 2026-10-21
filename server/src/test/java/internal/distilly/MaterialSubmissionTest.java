package internal.distilly;

import internal.distilly.modules.material.service.MaterialService;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:material-submission;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE","platform.demo=true","platform.worker-token=material-submission-synthetic-worker-token-32-characters"})
@AutoConfigureMockMvc
class MaterialSubmissionTest {
 @Autowired PlatformFixture platform;
 @Autowired MaterialService materials;
 @Autowired JdbcTemplate db;
 @Autowired MockMvc mvc;
 String project,key;
 @BeforeEach void setup(){project=platform.createProject("owner","合成上传操作").get("id").toString();platform.member("owner",project,"editor","EDITOR");platform.member("owner",project,"viewer","VIEWER");key=UUID.randomUUID().toString();}
 Map<String,Object> send(){return materials.upload("owner",project,"synthetic.txt","合成原始资料",key);}
 long rows(String table){return db.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE project_id=?",Long.class,project);}
 void rejected(Runnable action,int code){assertEquals(code,assertThrows(ResponseStatusException.class,action::run).getStatusCode().value());}
 @Test void duplicateAndConcurrentUploadAcceptOneMaterialReceiptAndAudit()throws Exception{
  var gate=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
  try{var a=pool.submit(()->{gate.await();return send();});var b=pool.submit(()->{gate.await();return send();});gate.countDown();assertEquals(a.get(10,TimeUnit.SECONDS).get("id"),b.get(10,TimeUnit.SECONDS).get("id"));}finally{pool.shutdownNow();}
  assertEquals(1,rows("material"));assertEquals(1,rows("material_submission"));assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM audit WHERE project_id=? AND action='MATERIAL_UPLOADED'",Integer.class,project));
  assertEquals(send().get("submission"),materials.submission("owner",project,key));
 }
 @Test void differentContentWithSameFilenameIsNeverMerged(){
  String first=send().get("id").toString();rejected(()->materials.upload("owner",project,"synthetic.txt","不同合成内容",key),409);
  String next=materials.upload("owner",project,"synthetic.txt","不同合成内容",UUID.randomUUID().toString()).get("id").toString();assertNotEquals(first,next);assertEquals(2,rows("material"));
 }
 @Test void rawWhitespaceAndFilenameChangesConflictEvenIfStoredTextDigestWouldMatch(){
  send();rejected(()->materials.upload("owner",project,"synthetic.txt"," 合成原始资料 ",key),409);rejected(()->materials.upload("owner",project,"renamed.txt","合成原始资料",key),409);assertEquals(1,rows("material"));
 }
 @Test void distinctExplicitOperationDoesNotSilentlyContentDedupe(){
  String first=send().get("id").toString();String second=materials.upload("owner",project,"synthetic.txt","合成原始资料",UUID.randomUUID().toString()).get("id").toString();assertNotEquals(first,second);assertEquals(2,rows("material"));
 }
 @Test void actorAndWorkspaceKeysAreIndependentAndReceiptsArePrivate(){
  String first=send().get("id").toString();rejected(()->materials.submission("editor",project,key),404);
  String editor=materials.upload("editor",project,"synthetic.txt","编辑者合成内容",key).get("id").toString();assertNotEquals(first,editor);assertEquals(editor,materials.submission("editor",project,key).get("material_id"));
  String other=platform.createProject("owner","另一个合成空间").get("id").toString();assertNotEquals(first,materials.upload("owner",other,"synthetic.txt","合成原始资料",key).get("id"));
  rejected(()->materials.submission("editor",other,key),403);assertEquals(3,materials.submission("owner",project,key).size());
 }
 @Test void replayAndReadRecheckCurrentMembershipAndRole(){
  send();rejected(()->materials.submission("viewer",project,key),403);platform.member("owner",project,"editor","OWNER");platform.member("editor",project,"owner","VIEWER");
  rejected(this::send,403);rejected(()->materials.submission("owner",project,key),403);assertEquals(1,rows("material"));
  db.update("DELETE FROM member WHERE project_id=? AND user_id='owner'",project);rejected(this::send,403);
 }
 @Test void originalImportValidationRemainsAndRejectedKeyCanLaterAcceptValidTxt()throws Exception{
  for(var file:new MockMultipartFile[]{new MockMultipartFile("file","invalid.txt","text/plain",new byte[]{(byte)255}),new MockMultipartFile("file","binary.txt","text/plain",new byte[]{65,0,66}),new MockMultipartFile("file","invalid.pdf","text/plain",new byte[]{65})})
   mvc.perform(multipart("/api/projects/"+project+"/materials").file(file).param("request_id",key).header("Authorization","Bearer demo-owner")).andExpect(status().isUnprocessableEntity());
  mvc.perform(multipart("/api/projects/"+project+"/materials").file(new MockMultipartFile("file","large.txt","text/plain",new byte[1048577])).param("request_id",key).header("Authorization","Bearer demo-owner")).andExpect(status().isPayloadTooLarge());
  assertEquals(0,rows("material"));rejected(()->materials.submission("owner",project,key),404);send();assertEquals(1,rows("material"));
 }
 @Test void concurrentDifferentPayloadsWithOneKeyAcceptExactlyOne()throws Exception{
  var gate=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
  try{var a=pool.submit(()->{gate.await();try{materials.upload("owner",project,"same.txt","合成甲",key);return 200;}catch(ResponseStatusException e){return e.getStatusCode().value();}});var b=pool.submit(()->{gate.await();try{materials.upload("owner",project,"same.txt","合成乙",key);return 200;}catch(ResponseStatusException e){return e.getStatusCode().value();}});gate.countDown();assertEquals(java.util.Set.of(200,409),java.util.Set.of(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS)));}finally{pool.shutdownNow();}
  assertEquals(1,rows("material"));assertEquals(1,rows("material_submission"));
 }
 @Test void receiptInsertFailureRollsBackMaterialAndAudit(){
  long audits=rows("audit");db.execute("ALTER TABLE material_submission ADD CONSTRAINT synthetic_reject_receipt CHECK(request_id <> '"+key+"')");
  try{assertThrows(org.springframework.dao.DataIntegrityViolationException.class,this::send);}finally{db.execute("ALTER TABLE material_submission DROP CONSTRAINT synthetic_reject_receipt");}
  assertEquals(0,rows("material"));assertEquals(audits,rows("audit"));rejected(()->materials.submission("owner",project,key),404);send();assertEquals(1,rows("material"));
 }
 @Test void invalidOperationIdCannotMutateAnything(){rejected(()->materials.upload("owner",project,"synthetic.txt","合成","invalid"),422);assertEquals(0,rows("material"));}
}
