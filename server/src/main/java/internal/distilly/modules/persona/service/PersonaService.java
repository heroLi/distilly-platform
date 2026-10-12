package internal.distilly.modules.persona.service;

import internal.distilly.common.json.JsonCodec;
import internal.distilly.common.persistence.ResourceRepository;
import internal.distilly.modules.material.service.MaterialService;
import internal.distilly.modules.persona.repository.PersonaRepository;
import internal.distilly.modules.persona.service.PersonaAccessService;
import internal.distilly.modules.task.service.PersonaTaskService;
import internal.distilly.modules.workspace.service.AuditService;
import internal.distilly.modules.workspace.service.WorkspaceAccessService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static internal.distilly.common.support.Identifiers.id;
import static internal.distilly.common.support.Identifiers.now;
import static internal.distilly.common.validation.InputChecks.error;
import static internal.distilly.common.validation.InputChecks.text;
import static internal.distilly.modules.persona.domain.PersonaContentValidator.definition;
import static internal.distilly.modules.persona.domain.PersonaContentValidator.optional;

@Service
@org.springframework.context.annotation.DependsOn("personaMigration")
public class PersonaService {

    private final PersonaRepository repository;

    private final JsonCodec json;

    private final ResourceRepository resources;

    private final WorkspaceAccessService access;

    private final AuditService audits;

    private final PersonaAccessService personaAccess;

    private final MaterialService materials;

    private final PersonaTaskService personaTaskService;

    public PersonaService(PersonaRepository repository, JsonCodec json, ResourceRepository resources,
            WorkspaceAccessService access, AuditService audits, PersonaAccessService personaAccess,
            MaterialService materials, PersonaTaskService personaTaskService) {
        this.repository = repository;
        this.json = json;
        this.resources = resources;
        this.access = access;
        this.audits = audits;
        this.personaAccess = personaAccess;
        this.materials = materials;
        this.personaTaskService = personaTaskService;
    }

    public List<Map<String, Object>> list(String actorId, String project) {
        String role = access.role(actorId, project);
        return repository.findVirtualPeople(role, project);
    }

    @Transactional
    public Map<String, Object> create(String actorId, String project, Map<String, Object> body) {
        access.require(actorId, project, "OWNER", "EDITOR");
        var def = definition(body);
        String personaId = id();
        repository.insertPersona(personaId, project, def.get("name"), def.get("family"), json.encode(def), actorId,
                now());
        audits.record(project, actorId, "PERSONA_CREATED", personaId);
        return detail(actorId, personaId);
    }

    public Map<String, Object> detail(String actorId, String personaId) {
        var personaRow = personaAccess.persona(actorId, personaId);
        String role = access.role(actorId, (String) personaRow.get("project_id"));
        if (role.equals("VIEWER") && personaRow.get("published_id") == null)
            throw error(403, "persona_not_published");
        var out = new LinkedHashMap<>(personaRow);
        out.remove("definition");
        out.put("definition", json.decode(personaRow.get("definition")));
        if (role.equals("VIEWER"))
            out.put("definition",
                    json.decode(
                            resources.one("persona_version", (String) personaRow.get("published_id")).get("content"))
                        .get("definition"));
        if (Boolean.TRUE.equals(personaRow.get("library")))
            out.put("bindings", repository.findBindings(personaRow.get("project_id"), personaId));
        out.put("versions", repository.findVersions(role, personaId).stream().map(versionRow -> {
            versionRow.put("content", json.decode(versionRow.get("content")));
            return versionRow;
        }).toList());
        return out;
    }

    @Transactional
    public Map<String, Object> update(String actorId, String personaId, Map<String, Object> body) {
        var personaRow = personaAccess.persona(actorId, personaId);
        personaAccess.edit(actorId, personaRow);
        personaAccess.lock("persona", personaId);
        var def = definition(body);
        repository.updateDefinition(def.get("name"), def.get("family"), json.encode(def), personaId);
        audits.record((String) personaRow.get("project_id"), actorId, "PERSONA_EDITED", personaId);
        return detail(actorId, personaId);
    }

    @Transactional
    public void archive(String actorId, String personaId) {
        var personaRow = personaAccess.persona(actorId, personaId);
        access.require(actorId, (String) personaRow.get("project_id"), "OWNER");
        personaAccess.lock("persona", personaId);
        if (repository.countActiveTasks(personaId) > 0)
            throw error(409, "persona_jobs_active");
        repository.archivePersona(personaId);
        audits.record((String) personaRow.get("project_id"), actorId, "PERSONA_ARCHIVED", personaId);
    }

    public List<Map<String, Object>> archived(String actorId, String project) {
        access.require(actorId, project, "OWNER");
        return repository.findArchivedPeople(project);
    }

    @Transactional
    public void restore(String actorId, String personaId) {
        var personaRow = resources.one("persona", personaId);
        access.require(actorId, (String) personaRow.get("project_id"), "OWNER");
        repository.restorePersona(personaId);
        audits.record((String) personaRow.get("project_id"), actorId, "PERSONA_RESTORED", personaId);
    }

    @Transactional
    public Map<String, Object> generate(String actorId, String personaId, List<String> materialIds) {
        var personaRow = personaAccess.persona(actorId, personaId);
        personaAccess.edit(actorId, personaRow);
        personaAccess.lock("persona", personaId);
        personaRow = personaAccess.persona(actorId, personaId);
        if (repository.countActiveGenerations(personaId) > 0)
            throw error(409, "persona_generation_busy");
        var def = json.decode(personaRow.get("definition"));
        if (materialIds.size() > 20)
            throw error(422, "too_many_materials");
        if (!materialIds.isEmpty() && !Boolean.TRUE.equals(def.get("consent")))
            throw error(422, "material_authorization_required");
        var sources = new ArrayList<Map<String, Object>>();
        int size = 0;
        for (String materialId : new LinkedHashSet<>(materialIds)) {
            var source = materials.material(actorId, materialId);
            if (!personaRow.get("project_id").equals(source.get("project_id")))
                throw error(403, "material_project_mismatch");
            size += ((String) source.get("body")).length();
            if (size > 60000)
                throw error(422, "persona_materials_too_large");
            sources.add(Map.of("material_id", materialId, "text", source.get("body")));
        }
        var payload = new LinkedHashMap<String, Object>();
        payload.put("definition", def);
        payload.put("sources", sources);
        payload.put("base_id", personaRow.get("published_id"));
        return personaTaskService.enqueue(actorId, personaId, null, "PERSONA", payload);
    }

    @Transactional
    public Map<String, Object> revise(String actorId, String personaId, String version, Map<String, Object> body) {
        var personaRow = personaAccess.persona(actorId, personaId);
        personaAccess.edit(actorId, personaRow);
        personaAccess.lock("persona", personaId);
        personaRow = personaAccess.persona(actorId, personaId);
        var versionRow = resources.one("persona_version", version);
        if (!personaId.equals(versionRow.get("persona_id")))
            throw error(422, "version_mismatch");
        var content = json.decode(versionRow.get("content"));
        content.put("persona_markdown", text(body.get("persona_markdown"), 30000));
        content.put("correction", optional(body, "correction", 2000));
        String next = id();
        repository.insertCandidateVersion(next, personaId, personaRow.get("published_id"), json.encode(content),
                actorId, now());
        audits.record((String) personaRow.get("project_id"), actorId, "PERSONA_CORRECTED", next);
        return Map.of("version_id", next);
    }

    @Transactional
    public void review(String actorId, String personaId, String version, boolean approve) {
        var personaRow = personaAccess.persona(actorId, personaId);
        access.require(actorId, (String) personaRow.get("project_id"), "OWNER");
        personaAccess.lock("persona", personaId);
        personaRow = personaAccess.persona(actorId, personaId);
        var versionRow = resources.one("persona_version", version);
        if (!personaId.equals(versionRow.get("persona_id")) || !"CANDIDATE".equals(versionRow.get("status")))
            throw error(409, "invalid_candidate");
        if (approve && !Objects.equals(personaRow.get("published_id"), versionRow.get("base_id")))
            throw error(409, "published_version_changed");
        repository.reviewVersion(approve ? "APPROVED" : "REJECTED", version);
        if (approve)
            repository.setPublishedVersion(version, personaId);
        audits.record((String) personaRow.get("project_id"), actorId,
                approve ? "PERSONA_PUBLISHED" : "PERSONA_REJECTED", version);
    }

    @Transactional
    public void rollback(String actorId, String personaId, String version) {
        var personaRow = personaAccess.persona(actorId, personaId);
        access.require(actorId, (String) personaRow.get("project_id"), "OWNER");
        personaAccess.lock("persona", personaId);
        var versionRow = resources.one("persona_version", version);
        if (!personaId.equals(versionRow.get("persona_id")) || !"APPROVED".equals(versionRow.get("status")))
            throw error(422, "invalid_rollback_version");
        repository.setPublishedVersion(version, personaId);
        audits.record((String) personaRow.get("project_id"), actorId, "PERSONA_ROLLED_BACK", version);
    }

    public String export(String actorId, String personaId) {
        var personaRow = personaAccess.persona(actorId, personaId);
        if (personaRow.get("published_id") == null)
            throw error(409, "persona_not_published");
        var content = json
            .decode(resources.one("persona_version", (String) personaRow.get("published_id")).get("content"));
        return "---\nname: persona-" + personaId + "\ndescription: Published virtual persona\n---\n\n"
                + content.get("persona_markdown") + "\n\n此内容为虚拟人物设定，不代表真人。\n";
    }

}
