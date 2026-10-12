package internal.distilly.modules.knowledge.service;

import internal.distilly.common.json.JsonCodec;
import internal.distilly.common.persistence.ResourceRepository;
import internal.distilly.modules.knowledge.repository.PersonaKnowledgeRepository;
import internal.distilly.modules.workspace.service.AuditService;
import internal.distilly.modules.workspace.service.WorkspaceAccessService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static internal.distilly.common.support.Identifiers.id;
import static internal.distilly.common.validation.InputChecks.error;
import static internal.distilly.common.validation.InputChecks.items;

/**
 * Workspace knowledge is shared; each persona has an explicit allowlist of published
 * topics.
 */
@Service
public class PersonaKnowledgeService {

    private final PersonaKnowledgeRepository repository;

    private final ResourceRepository resources;

    private final WorkspaceAccessService access;

    private final AuditService audits;

    private final JsonCodec json;

    public PersonaKnowledgeService(PersonaKnowledgeRepository repository, ResourceRepository resources,
            WorkspaceAccessService access, AuditService audits, JsonCodec json) {
        this.repository = repository;
        this.resources = resources;
        this.access = access;
        this.audits = audits;
        this.json = json;
    }

    Map<String, Object> persona(String actorId, String personaId) {
        var personaRow = resources.one("persona", personaId);
        access.role(actorId, (String) personaRow.get("project_id"));
        if (Boolean.TRUE.equals(personaRow.get("deleted")))
            throw error(404, "persona_deleted");
        return personaRow;
    }

    public List<Map<String, Object>> configuration(String actorId, String personaId) {
        var personaRow = persona(actorId, personaId);
        String role = access.role(actorId, (String) personaRow.get("project_id"));
        if (role.equals("VIEWER") && personaRow.get("published_id") == null)
            throw error(403, "persona_not_published");
        return repository.findAvailableTopics(role, personaId, personaRow.get("project_id"));
    }

    @Transactional
    public void configure(String actorId, String personaId, List<String> ids) {
        var personaRow = persona(actorId, personaId);
        access.require(actorId, (String) personaRow.get("project_id"), "OWNER", "EDITOR");
        repository.lockPersona(personaId);
        if (ids.size() > 20 || new HashSet<>(ids).size() != ids.size())
            throw error(422, "invalid_knowledge_selection");
        for (String topicId : ids) {
            var topic = resources.one("profile", topicId);
            if (!personaRow.get("project_id").equals(topic.get("project_id")))
                throw error(403, "knowledge_workspace_mismatch");
            if (topic.get("published_id") == null)
                throw error(422, "knowledge_not_published");
            var versionRow = resources.one("profile_version", (String) topic.get("published_id"));
            if (!"APPROVED".equals(versionRow.get("status")) || !topicId.equals(versionRow.get("profile_id")))
                throw error(422, "knowledge_not_published");
        }
        repository.deleteSelections(personaId);
        for (String topicId : ids)
            repository.insertSelection(personaId, topicId);
        // Revoke any in-flight context before a changed allowlist can be used again.
        repository.cancelChatsWithRevokedKnowledge(personaId);
        audits.record((String) personaRow.get("project_id"), actorId, "PERSONA_KNOWLEDGE_CHANGED", personaId);
    }

    public List<Map<String, Object>> retrieve(String actorId, String personaId, String question) {
        var personaRow = persona(actorId, personaId);
        var rows = repository.findPublishedClaims(personaId, personaRow.get("project_id"));
        var terms = new LinkedHashSet<String>();
        String q = question.toLowerCase(Locale.ROOT);
        var words = Pattern.compile("[a-z0-9_]+|[\\p{IsHan}]+").matcher(q);
        while (words.find()) {
            String word = words.group();
            if (word.matches("[a-z0-9_]+"))
                terms.add(word);
            else
                for (int i = 0; i + 1 < word.length(); i++)
                    terms.add(word.substring(i, i + 2));
        }
        var ranked = new ArrayList<Map<String, Object>>();
        for (var row : rows)
            for (var claim : items(json.decode(row.get("content")).get("claims"))) {
                String body = (String) claim.get("text"), section = (String) claim.get("section");
                String hay = (row.get("title") + " " + section + " " + body).toLowerCase(Locale.ROOT);
                long score = terms.stream().filter(hay::contains).count();
                if (score == 0)
                    continue;
                var source = new LinkedHashMap<String, Object>();
                source.put("id", row.get("id") + "/" + row.get("version_id") + "/" + claim.get("id"));
                source.put("profile_id", row.get("id"));
                source.put("title", row.get("title"));
                source.put("version_id", row.get("version_id"));
                source.put("section", section);
                source.put("text", body);
                source.put("score", score);
                ranked.add(source);
            }
        ranked.sort(Comparator.<Map<String, Object>>comparingLong(x -> ((Number) x.get("score")).longValue())
            .reversed()
            .thenComparing(x -> x.get("id").toString()));
        var selected = new ArrayList<Map<String, Object>>();
        int size = 0;
        for (var source : ranked) {
            int length = source.get("text").toString().length();
            if (selected.size() >= 12 || size + length > 16000)
                break;
            size += length;
            source.remove("score");
            selected.add(source);
        }
        return selected;
    }

    public void validateAccess(String actorId, String personaId, List<Map<String, Object>> sources) {
        var personaRow = persona(actorId, personaId);
        for (var source : sources) {
            String topicId = (String) source.get("profile_id");
            if (repository.countAccessibleSnapshot(source.get("version_id"), personaId, topicId,
                    personaRow.get("project_id")) != 1)
                throw error(409, "knowledge_access_changed");
        }
    }

}
