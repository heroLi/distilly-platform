package internal.distilly.modules.media.service;

import internal.distilly.common.json.JsonCodec;
import internal.distilly.common.persistence.ResourceRepository;
import internal.distilly.modules.media.repository.VoiceProfileRepository;
import internal.distilly.modules.persona.service.PersonaAccessService;
import internal.distilly.modules.task.service.PersonaTaskService;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static internal.distilly.common.support.Identifiers.id;
import static internal.distilly.common.support.Identifiers.now;
import static internal.distilly.common.validation.InputChecks.error;
import static internal.distilly.common.validation.InputChecks.text;

/** Synthetic-only preparation; no raw recording or real clone-provider entry point. */
@Service
@org.springframework.context.annotation.DependsOn("personaMigration")
public class VoiceProfileService {
    private final VoiceProfileRepository repository;
    private final ResourceRepository resources;
    private final PersonaAccessService access;
    private final PersonaTaskService tasks;
    private final JsonCodec json;
    public VoiceProfileService(VoiceProfileRepository repository, ResourceRepository resources, PersonaAccessService access,
            PersonaTaskService tasks, JsonCodec json) {
        this.repository=repository; this.resources=resources; this.access=access; this.tasks=tasks; this.json=json;
    }
    private void editable(String actor, String persona) { access.edit(actor, access.persona(actor, persona)); }
    private Map<String, Object> owned(String actor, String persona, String profile) {
        editable(actor, persona);
        var row=resources.one("voice_profile", profile);
        if (!persona.equals(row.get("persona_id")) || !actor.equals(row.get("user_id"))) throw error(403,"private_voice_profile");
        return row;
    }
    private String request(Map<String,Object> body) {
        String value=text(body.get("request_id"),36);
        try { if (!UUID.fromString(value).toString().equals(value)) throw new IllegalArgumentException(); }
        catch (IllegalArgumentException e) { throw error(422,"invalid_voice_request_id"); }
        return value;
    }
    private void active(Map<String,Object> row) {
        if (!"ACTIVE".equals(row.get("authorization_status"))) throw error(409,"voice_authorization_closed");
    }
    public List<Map<String,Object>> list(String actor,String persona) {
        editable(actor,persona);
        return repository.list(persona,actor).stream().map(row -> view(row)).toList();
    }
    public Map<String,Object> get(String actor,String persona,String profile) { return view(owned(actor,persona,profile)); }
    private Map<String,Object> view(Map<String,Object> row) {
        var out=new LinkedHashMap<>(row);
        out.remove("request_id");
        out.put("provider","mock"); out.put("synthetic",true);
        out.put("jobs",repository.jobs(row.get("id")).stream().map(j -> {
            var job=new LinkedHashMap<>(j);
            job.remove("result");
            // Even successful cached preview results are unavailable once consent closes.
            if ("ACTIVE".equals(row.get("authorization_status")) && "SUCCEEDED".equals(j.get("status")) && j.get("result")!=null)
                job.put("result",json.decode(j.get("result")));
            return job;
        }).toList());
        return out;
    }
    @Transactional
    public Map<String,Object> create(String actor,String persona,Map<String,Object> body) {
        editable(actor,persona); access.lock("persona",persona);
        if (!Set.of("name","synthetic_consent","request_id").containsAll(body.keySet()) || !Boolean.TRUE.equals(body.get("synthetic_consent")))
            throw error(422,"synthetic_voice_consent_required");
        String name=text(body.get("name"),100), request=request(body);
        var previous=repository.byRequest(persona,actor,request);
        if (!previous.isEmpty()) {
            var row=previous.get(0); active(row);
            if (!name.equals(row.get("name"))) throw error(409,"voice_request_conflict");
            return view(row);
        }
        String profile=id();
        repository.insert(profile,persona,actor,name,request,id(),now());
        var job=tasks.enqueue(actor,persona,null,"VOICE_CLONE",Map.of("provider","mock","synthetic",true,"profile_id",profile,"operation","VOICE_CLONE"));
        repository.link(profile,job.get("id").toString(),"VOICE_CLONE",request);
        return get(actor,persona,profile);
    }
    @Transactional
    public Map<String,Object> preview(String actor,String persona,String profile,Map<String,Object> body) {
        owned(actor,persona,profile); access.lock("persona",persona);
        var row=owned(actor,persona,profile); active(row);
        if (!Set.of("request_id").containsAll(body.keySet())) throw error(422,"invalid_voice_preview");
        String request=request(body);
        var previous=repository.jobByRequest(profile,"VOICE_PREVIEW",request);
        if (!previous.isEmpty()) return tasks.job(actor,previous.get(0).get("job_id").toString());
        boolean ready=repository.jobs(profile).stream().anyMatch(j -> "VOICE_CLONE".equals(j.get("kind")) && "SUCCEEDED".equals(j.get("status")));
        if (!ready) throw error(409,"voice_profile_not_ready");
        var job=tasks.enqueue(actor,persona,null,"VOICE_PREVIEW",Map.of("provider","mock","synthetic",true,"profile_id",profile,"operation","VOICE_PREVIEW"));
        repository.link(profile,job.get("id").toString(),"VOICE_PREVIEW",request);
        return job;
    }
    @Transactional
    public void close(String actor,String persona,String profile,boolean delete) {
        owned(actor,persona,profile); access.lock("persona",persona);
        var row=owned(actor,persona,profile);
        if ("DELETED".equals(row.get("authorization_status"))) return;
        repository.close(profile,delete?"DELETED":"REVOKED",now());
    }
}
