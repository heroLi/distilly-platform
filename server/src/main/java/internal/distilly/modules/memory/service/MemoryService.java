package internal.distilly.modules.memory.service;

import internal.distilly.common.persistence.ResourceRepository;
import internal.distilly.modules.memory.repository.MemoryRepository;
import internal.distilly.modules.persona.service.PersonaAccessService;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashMap;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static internal.distilly.common.support.Identifiers.id;
import static internal.distilly.common.support.Digests.sha256;
import static internal.distilly.common.validation.InputChecks.error;
import static internal.distilly.common.validation.InputChecks.text;

@Service
@org.springframework.context.annotation.DependsOn("personaMigration")
public class MemoryService {

    private final MemoryRepository repository;

    private final ResourceRepository resources;

    private final PersonaAccessService personaAccess;
    private final MemorySyncService sync;

    public MemoryService(MemoryRepository repository, ResourceRepository resources,
            PersonaAccessService personaAccess, MemorySyncService sync) {
        this.repository = repository;
        this.resources = resources;
        this.personaAccess = personaAccess;
        this.sync = sync;
    }

    public List<Map<String, Object>> memories(String actorId, String id) {
        personaAccess.persona(actorId, id);
        var selected = repository.findByPersonaAndUser(id, actorId);
        var guarded = repository.findForPublication(id, actorId);
        if (guarded.isEmpty()) {
            personaAccess.persona(actorId, id);
            throw error(409, "private_read_changed");
        }
        var current = new LinkedHashMap<Object, Map<String, Object>>();
        for (var row : guarded) if (row.get("id") != null) {
            var copy = new LinkedHashMap<String, Object>(row);
            copy.remove("read_authorized_persona");
            current.put(row.get("id"), copy);
        }
        // New rows may arrive normally. Every selected row must still be current;
        // deleted, corrected, rejected or relinked rows cannot escape this checkpoint.
        for (var row : selected)
            if (!row.equals(current.get(row.get("id")))) throw error(409, "private_read_changed");
        return selected.stream().map(row -> {
            row.put("revision", revision(row));
            return row;
        }).toList();
    }

    private String revision(Map<String, Object> row) {
        return sha256(row.get("id") + "\n" + row.get("status") + "\n" + row.get("body"));
    }

    public Map<String, Object> source(String actorId, String id, String memoryId) {
        personaAccess.persona(actorId, id);
        var rows = repository.findSource(id, actorId, memoryId);
        if (rows.isEmpty()) throw error(404, "memory_source_unavailable");
        var current = repository.findSourceForPublication(id, actorId, memoryId);
        if (current.isEmpty()) {
            personaAccess.persona(actorId, id);
            throw error(404, "memory_source_unavailable");
        }
        if (!rows.get(0).equals(current.get(0))) throw error(409, "private_read_changed");
        var result = new LinkedHashMap<String, Object>();
        for (String key : List.of("message_id", "conversation_id", "body", "created_at", "title"))
            result.put(key, rows.get(0).get(key));
        return result;
    }

    @Transactional
    public void invalidatePendingChats(String actorId, String persona) {
        personaAccess.persona(actorId, persona);
        repository.cancelPendingChatsForUser(persona, actorId);
    }

    @Transactional
    public void memory(String actorId, String id, String memoryId, Map<String, Object> body) {
        personaAccess.persona(actorId, id);
        var memoryRow = resources.one("persona_memory", memoryId);
        if (!actorId.equals(memoryRow.get("user_id")) || !id.equals(memoryRow.get("persona_id")))
            throw error(403, "private_memory");
        personaAccess.lock("persona", id);
        personaAccess.lock("persona_memory", memoryId);
        // A status-only update must retain the body committed before this lock was acquired.
        memoryRow = resources.one("persona_memory", memoryId);
        if (body.containsKey("expected_revision") && !revision(memoryRow).equals(body.get("expected_revision")))
            throw error(409, "memory_revision_conflict");
        String status = text(body.get("status"), 12);
        if (!Set.of("CONFIRMED", "REJECTED").contains(status))
            throw error(422, "invalid_memory_status");
        repository.updateStatusAndBody(status, text(body.getOrDefault("body", memoryRow.get("body")), 1000), memoryId);
        sync.track(actorId,id,memoryId);
        invalidatePendingChats(actorId, id);
    }

    @Transactional
    public void forget(String actorId, String id, String memoryId) {
        personaAccess.persona(actorId, id);
        var memoryRow = resources.one("persona_memory", memoryId);
        if (!actorId.equals(memoryRow.get("user_id")) || !id.equals(memoryRow.get("persona_id")))
            throw error(403, "private_memory");
        personaAccess.lock("persona", id);
        sync.withdraw(actorId,id,memoryId);
        repository.deleteById(memoryId);
        invalidatePendingChats(actorId, id);
    }

    @Transactional
    public void withdrawSourceMemories(String actor,String persona,String conversation) {
        personaAccess.persona(actor,persona);personaAccess.lock("persona",persona);
        for(var memory:repository.findBySourceConversation(persona,actor,conversation))
            sync.withdraw(actor,persona,memory.get("id").toString());
    }

}
