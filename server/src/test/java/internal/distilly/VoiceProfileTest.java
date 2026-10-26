package internal.distilly;

import internal.distilly.modules.media.service.VoiceProfileService;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:voice-profile;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE", "platform.demo=true", "platform.worker-token=voice-profile-synthetic-worker-32-characters"})
class VoiceProfileTest {
    @Autowired VoiceProfileService voices;
    @Autowired PersonaFixture personas;
    @Autowired PlatformFixture platform;
    @Autowired JdbcTemplate db;
    String project, persona;

    @BeforeEach void setup() {
        db.update("DELETE FROM voice_profile_job");
        db.update("DELETE FROM voice_profile");
        db.update("DELETE FROM persona_job WHERE kind IN ('VOICE_CLONE','VOICE_PREVIEW')");
        project=platform.createProject("owner","合成声音范围").get("id").toString();
        platform.member("owner",project,"editor","EDITOR");
        platform.member("owner",project,"viewer","VIEWER");
        persona=personas.create("owner",project,Map.of("name","合成人物","family","relationship","origin","original")).get("id").toString();
    }
    Map<String,Object> request(String name) { return Map.of("name",name,"synthetic_consent",true,"request_id",UUID.randomUUID().toString()); }
    Map<String,Object> create() { return voices.create("owner",persona,request("合成音色")); }
    Map<String,Object> completeCreate(Map<String,Object> profile) {
        var lease=personas.claim();
        assertEquals("VOICE_CLONE",lease.get("kind"));
        return personas.complete(lease.get("id").toString(),lease.get("lease_token").toString(),Map.of("provider","mock","synthetic",true,"profile_ref","mock-"+profile.get("id")));
    }
    void status(int code,Runnable action) { assertEquals(code,assertThrows(ResponseStatusException.class,action::run).getStatusCode().value()); }

    @Test void explicitSyntheticConsentAndNoRecordingAreRequired() {
        status(422,()->voices.create("owner",persona,Map.of("name","合成","request_id",UUID.randomUUID().toString())));
        var body=new HashMap<>(request("合成")); body.put("audio","rejected-synthetic");
        status(422,()->voices.create("owner",persona,body));
        assertTrue(voices.list("owner",persona).isEmpty());
    }
    @Test void repeatedCreateReturnsOneProfileAndOneJob() {
        var body=request("合成"); var first=voices.create("owner",persona,body);
        assertEquals(first.get("id"),voices.create("owner",persona,body).get("id"));
        assertEquals(1,voices.list("owner",persona).size());
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM voice_profile_job WHERE profile_id=?",Integer.class,first.get("id")));
        var conflict=new HashMap<>(body); conflict.put("name","不同合成名");
        status(409,()->voices.create("owner",persona,conflict));
    }
    @Test void createPreviewAndDuplicateCallbackUseExistingLeases() {
        var profile=create(); completeCreate(profile);
        String id=profile.get("id").toString();
        var body=Map.<String,Object>of("request_id",UUID.randomUUID().toString());
        var preview=voices.preview("owner",persona,id,body);
        assertEquals(preview.get("id"),voices.preview("owner",persona,id,body).get("id"));
        var lease=personas.claim();
        var output=Map.<String,Object>of("provider","mock","synthetic",true,"audio","data:audio/wav;base64,UklGRgAAAABXQVZF");
        var first=personas.complete(lease.get("id").toString(),lease.get("lease_token").toString(),output);
        assertEquals(first,personas.complete(lease.get("id").toString(),lease.get("lease_token").toString(),output));
        assertEquals(2,((List<?>)voices.get("owner",persona,id).get("jobs")).size());
    }
    @Test void previewCannotRunBeforeCreationOrAcceptCustomAudioOrText() {
        String id=create().get("id").toString();
        status(409,()->voices.preview("owner",persona,id,Map.of("request_id",UUID.randomUUID().toString())));
        status(422,()->voices.preview("owner",persona,id,Map.of("request_id",UUID.randomUUID().toString(),"text","rejected")));
    }
    @Test void profilesAndJobsRemainPrivateEvenToOtherEditors() {
        var profile=voices.create("editor",persona,request("合成私有"));
        assertTrue(voices.list("owner",persona).isEmpty());
        status(403,()->voices.get("owner",persona,profile.get("id").toString()));
        status(403,()->voices.list("viewer",persona));
        String job=((Map<?,?>)((List<?>)profile.get("jobs")).get(0)).get("id").toString();
        status(403,()->personas.job("owner",job));
        assertTrue(personas.jobs("owner",persona).isEmpty());
    }
    @Test void revokeCancelsQueuedAndRejectsReuseOfOldRequest() {
        var body=request("合成");var profile=voices.create("owner",persona,body);String id=profile.get("id").toString();
        voices.close("owner",persona,id,false);
        assertNull(personas.claim());
        status(409,()->voices.create("owner",persona,body));
        status(409,()->voices.preview("owner",persona,id,Map.of("request_id",UUID.randomUUID().toString())));
        assertEquals("REVOKED",voices.get("owner",persona,id).get("authorization_status"));
        assertFalse(personas.jobs("owner",persona).isEmpty());
    }
    @Test void revokeInFlightPreventsLateSuccessAndClearsCachedPreview() {
        var profile=create();completeCreate(profile);String id=profile.get("id").toString();
        voices.preview("owner",persona,id,Map.of("request_id",UUID.randomUUID().toString()));
        var lease=personas.claim(); voices.close("owner",persona,id,false);
        status(409,()->personas.complete(lease.get("id").toString(),lease.get("lease_token").toString(),Map.of("provider","mock","synthetic",true)));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM persona_job j JOIN voice_profile_job v ON j.id=v.job_id WHERE v.profile_id=? AND (j.result IS NOT NULL OR j.lease_token IS NOT NULL OR j.payload<>'{}')",Integer.class,id));
    }
    @Test void deleteIsIdempotentTombstoneWithoutNameOrAudio() {
        var profile=create();String id=profile.get("id").toString();
        voices.close("owner",persona,id,true);voices.close("owner",persona,id,true);voices.close("owner",persona,id,false);
        assertTrue(voices.list("owner",persona).isEmpty());
        var row=voices.get("owner",persona,id);assertEquals("DELETED",row.get("authorization_status"));assertEquals("",row.get("name"));
        assertNotNull(row.get("authorization_id"));assertNotNull(row.get("closed_at"));
    }
    @Test void realProviderCallbackAndWrongReferenceAreRejected() {
        var profile=create();var lease=personas.claim();
        status(422,()->personas.complete(lease.get("id").toString(),lease.get("lease_token").toString(),Map.of("provider","real","synthetic",false)));
        status(422,()->personas.complete(lease.get("id").toString(),lease.get("lease_token").toString(),Map.of("provider","mock","synthetic",true,"profile_ref","wrong")));
    }
    @Test void downgradeOrRemovalBeforeClaimCannotExecuteVoice() {
        var profile=voices.create("editor",persona,request("合成"));
        platform.member("owner",project,"editor","VIEWER");
        assertNull(personas.claim());
        assertTrue(personas.jobs("editor",persona).isEmpty());
        assertEquals("CANCELLED",db.queryForObject("SELECT status FROM persona_job WHERE persona_id=?",String.class,persona));
    }
    @Test void concurrentSameOperationCreatesOnlyOneProfileAndTask() throws Exception {
        var body=request("合成并发");var threads=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var first=threads.submit(()->voices.create("owner",persona,body));
            var second=threads.submit(()->voices.create("owner",persona,body));
            assertEquals(first.get(10,java.util.concurrent.TimeUnit.SECONDS).get("id"),second.get(10,java.util.concurrent.TimeUnit.SECONDS).get("id"));
            assertEquals(1,voices.list("owner",persona).size());
            assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM persona_job WHERE persona_id=?",Integer.class,persona));
        } finally { threads.shutdownNow(); }
    }
    @Test void sameRequestKeyInAnotherUsersScopeDoesNotExposeOrMergeProfiles() {
        var body=request("合成各自档案");var owner=voices.create("owner",persona,body);var editor=voices.create("editor",persona,body);
        assertNotEquals(owner.get("id"),editor.get("id"));
        assertEquals(1,voices.list("owner",persona).size());assertEquals(1,voices.list("editor",persona).size());
        status(403,()->voices.close("owner",persona,editor.get("id").toString(),true));
    }
}
