package internal.distilly.modules.memory.service;

import internal.distilly.common.persistence.ResourceRepository;
import internal.distilly.modules.memory.repository.MemoryRepository;
import internal.distilly.modules.persona.service.PersonaAccessService;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static internal.distilly.common.support.Identifiers.id;
import static internal.distilly.common.validation.InputChecks.error;
import static internal.distilly.common.validation.InputChecks.text;

@Service
@org.springframework.context.annotation.DependsOn("personaMigration")
public class MemoryService {

    private final MemoryRepository repository;

    private final ResourceRepository resources;

    private final PersonaAccessService personaAccess;

    public MemoryService(MemoryRepository repository, ResourceRepository resources,
            PersonaAccessService personaAccess) {
        this.repository = repository;
        this.resources = resources;
        this.personaAccess = personaAccess;
    }

    public List<Map<String, Object>> memories(String actorId, String id) {
        personaAccess.persona(actorId, id);
        return repository.findByPersonaAndUser(id, actorId);
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
        String status = text(body.get("status"), 12);
        if (!Set.of("CONFIRMED", "REJECTED").contains(status))
            throw error(422, "invalid_memory_status");
        repository.updateStatusAndBody(status, text(body.getOrDefault("body", memoryRow.get("body")), 1000), memoryId);
        invalidatePendingChats(actorId, id);
    }

    @Transactional
    public void forget(String actorId, String id, String memoryId) {
        personaAccess.persona(actorId, id);
        var memoryRow = resources.one("persona_memory", memoryId);
        if (!actorId.equals(memoryRow.get("user_id")) || !id.equals(memoryRow.get("persona_id")))
            throw error(403, "private_memory");
        personaAccess.lock("persona", id);
        repository.deleteById(memoryId);
        invalidatePendingChats(actorId, id);
    }

}
