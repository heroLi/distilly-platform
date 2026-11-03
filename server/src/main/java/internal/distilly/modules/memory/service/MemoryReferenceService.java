package internal.distilly.modules.memory.service;

import internal.distilly.modules.memory.domain.MemoryProvider;
import internal.distilly.modules.memory.repository.MemoryReferenceRepository;
import internal.distilly.modules.persona.service.PersonaAccessService;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static internal.distilly.common.validation.InputChecks.error;
import static internal.distilly.common.validation.InputChecks.text;

/** Explicit SQL-only reference reading. No chat jobs, model calls, answer or delta channel. */
@Service
public class MemoryReferenceService {
    private final PersonaAccessService access;
    private final MemoryReferenceRepository repository;
    private final SqlMemoryProvider sql;

    public MemoryReferenceService(PersonaAccessService access, MemoryReferenceRepository repository, SqlMemoryProvider sql) {
        this.access = access; this.repository = repository; this.sql = sql;
    }

    @Transactional
    public Map<String,Object> read(String actor, String personaId, Map<String,Object> request) {
        if (request == null || !request.keySet().equals(Set.of("mode", "query"))
                || !"SOURCE_REFERENCE".equals(request.get("mode")))
            throw error(422, "invalid_memory_reference_request");
        String query = text(request.get("query"), 4000);
        var initial = access.persona(actor, personaId);
        // Same membership -> persona order as record reading. Normal memory/source mutations
        // lock persona, so the read is an authorized snapshot ordered before/after those writes.
        repository.lockMembership(actor, initial.get("project_id"));
        access.lock("persona", personaId);
        var persona = access.persona(actor, personaId);
        var selected = sql.recall(new MemoryProvider.Scope(persona.get("project_id").toString(), personaId, actor), query);
        if (selected == null || selected.size() > 12 || selected.stream().anyMatch(java.util.Objects::isNull))
            throw error(409, "memory_reference_changed");
        var references = new ArrayList<Map<String,Object>>();
        var seen = new HashSet<String>();
        for (var ref : selected) {
            if (!seen.add(ref.memoryId())) continue;
            var rows = repository.current(ref.memoryId(), personaId, actor);
            if (rows.size() != 1 || !ref.equals(MemoryProvider.reference(rows.get(0))))
                throw error(409, "memory_reference_changed");
            var row = rows.get(0);
            references.add(Map.of("memory_id", row.get("id"), "source_message_id", row.get("source_id"),
                    "record_text", row.get("body"), "record_fingerprint", ref.revision(),
                    "quote_kind", "CONFIRMED_MEMORY_RECORD"));
        }
        access.persona(actor, personaId);
        return Map.of("contract", "MEMORY_REFERENCE_V1", "mode", "SOURCE_REFERENCE",
                "support_status", "NOT_ASSESSED",
                "notice", "以下是当前已确认记忆记录原文；相关不等于支持，是否满足问题条件尚未判定。",
                "references", List.copyOf(references));
    }
}
