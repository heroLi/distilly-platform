package internal.distilly.modules.memory.service;

import internal.distilly.modules.memory.domain.MemoryProvider;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import internal.distilly.modules.memory.repository.MemoryRepository;
import internal.distilly.modules.persona.service.PersonaAccessService;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static internal.distilly.common.validation.InputChecks.error;

@Service
public class MemoryRetrieval {
    private static final int LIMIT = 12;
    private final MemoryRepository repository;
    private final PersonaAccessService access;
    private final MemoryProvider provider;
    private final SqlMemoryProvider fallback;

    public MemoryRetrieval(MemoryRepository repository, PersonaAccessService access, MemoryProvider provider, SqlMemoryProvider fallback) {
        this.repository = repository;
        this.access = access;
        this.provider=provider;
        this.fallback=fallback;
    }

    public void validateAccess(String actor, String personaId, Object selectedIds) {
        access.persona(actor, personaId);
        if (!(selectedIds instanceof List<?> ids) || ids.size() > LIMIT)
            throw error(409, "memory_access_changed");
        for (Object id : ids)
            if (!(id instanceof String memoryId) || repository.countConfirmedSource(memoryId, personaId, actor) != 1)
                throw error(409, "memory_access_changed");
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> retrieve(String actor, String personaId, String query) {
        var persona=access.persona(actor,personaId);
        var scope=new MemoryProvider.Scope((String)persona.get("project_id"),personaId,actor);
        List<MemoryProvider.SourceRef> refs;
        try {
            refs=provider.recall(scope,query);
            if(refs==null||refs.size()>LIMIT||refs.stream().anyMatch(java.util.Objects::isNull))throw new MemoryProvider.Unavailable();
        } catch(MemoryProvider.Unavailable unavailable) { refs=fallback.recall(scope,query); }
        // Provider routing and metadata are never authorization. Recheck current membership and SQL sources.
        access.persona(actor,personaId);
        var result=new ArrayList<Map<String,Object>>();
        var seen=new LinkedHashSet<String>();
        for(var ref:refs){
            if(!seen.add(ref.memoryId()))continue;
            var rows=repository.findConfirmedSource(ref.memoryId(),personaId,actor);
            if(rows.isEmpty()||!ref.equals(MemoryProvider.reference(rows.get(0))))continue;
            var row=rows.get(0);
            result.add(Map.of("id",row.get("id"),"body",row.get("body"),"source_id",row.get("source_id"),"origin","confirmed_interaction"));
        }
        return List.copyOf(result);
    }
}
