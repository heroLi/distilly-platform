package internal.distilly.modules.personality.service;

import internal.distilly.common.json.JsonCodec;
import internal.distilly.common.persistence.ResourceRepository;
import internal.distilly.modules.persona.service.PersonaAccessService;
import internal.distilly.modules.persona.service.PersonaService;
import internal.distilly.modules.personality.repository.PersonalityRepository;
import internal.distilly.modules.workspace.service.AuditService;
import internal.distilly.modules.workspace.service.WorkspaceAccessService;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static internal.distilly.common.support.Identifiers.id;
import static internal.distilly.common.support.Identifiers.now;
import static internal.distilly.common.validation.InputChecks.error;

@Service
@org.springframework.context.annotation.DependsOn("personaMigration")
public class PersonalityService {

    private final PersonalityRepository repository;

    private final JsonCodec json;

    private final ResourceRepository resources;

    private final WorkspaceAccessService access;

    private final AuditService audits;

    private final PersonaAccessService personaAccess;

    private final PersonaService personaService;

    public PersonalityService(PersonalityRepository repository, JsonCodec json, ResourceRepository resources,
            WorkspaceAccessService access, AuditService audits, PersonaAccessService personaAccess,
            PersonaService personaService) {
        this.repository = repository;
        this.json = json;
        this.resources = resources;
        this.access = access;
        this.audits = audits;
        this.personaAccess = personaAccess;
        this.personaService = personaService;
    }

    public List<Map<String, Object>> personalities(String actorId, String project) {
        String role = access.role(actorId, project);
        return repository.findLibraries(role, project);
    }

    @Transactional
    public Map<String, Object> createPersonality(String actorId, String project, Map<String, Object> body) {
        var targetPersona = personaService.create(actorId, project, body);
        repository.markAsLibrary(targetPersona.get("id"));
        return personaService.detail(actorId, (String) targetPersona.get("id"));
    }

    @Transactional
    public Map<String, Object> bindPersonality(String actorId, String targetId, String sourceId, String versionId) {
        var targetPersona = personaAccess.persona(actorId, targetId);
        access.require(actorId, (String) targetPersona.get("project_id"), "OWNER");
        personaAccess.lock("persona", targetId);
        targetPersona = personaAccess.persona(actorId, targetId);
        var sourcePersona = personaAccess.persona(actorId, sourceId);
        personaAccess.lock("persona", sourceId);
        sourcePersona = personaAccess.persona(actorId, sourceId);
        if (Boolean.TRUE.equals(targetPersona.get("library")) || !Boolean.TRUE.equals(sourcePersona.get("library")))
            throw error(422, "invalid_personality_binding");
        if (!targetPersona.get("project_id").equals(sourcePersona.get("project_id")))
            throw error(403, "personality_workspace_mismatch");
        var versionRow = resources.one("persona_version", versionId);
        if (!sourceId.equals(versionRow.get("persona_id")) || !"APPROVED".equals(versionRow.get("status")))
            throw error(422, "personality_not_published");
        if (repository.countActiveGenerations(targetId) > 0)
            throw error(409, "persona_generation_busy");
        String bound = id();
        var content = json.decode(versionRow.get("content"));
        content.put("definition", json.decode(targetPersona.get("definition")));
        content.put("binding",
                Map.of("personality_id", sourceId, "version_id", versionId, "name", sourcePersona.get("name")));
        repository.insertBoundVersion(bound, targetId, targetPersona.get("published_id"), json.encode(content), actorId,
                now(), sourceId, versionId);
        repository.publishBoundVersion(bound, targetId);
        audits.record((String) targetPersona.get("project_id"), actorId, "PERSONALITY_BOUND", bound);
        return personaService.detail(actorId, targetId);
    }

}
