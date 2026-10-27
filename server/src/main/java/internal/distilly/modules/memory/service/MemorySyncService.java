package internal.distilly.modules.memory.service;

import internal.distilly.common.persistence.ResourceRepository;
import internal.distilly.modules.memory.domain.MemoryProvider;
import internal.distilly.modules.memory.domain.MemoryProvider.*;
import internal.distilly.modules.memory.repository.MemoryRepository;
import internal.distilly.modules.memory.repository.MemorySyncRepository;
import internal.distilly.modules.persona.service.PersonaAccessService;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static internal.distilly.common.support.Identifiers.id;
import static internal.distilly.common.support.Identifiers.now;
import static internal.distilly.common.validation.InputChecks.error;

/** Durable local projection intent. No HTTP route, Worker consumer or external dispatch. */
@Service
@org.springframework.context.annotation.DependsOn("personaMigration")
public class MemorySyncService {
    public record Intent(Scope scope,String memoryId,long revision,UUID operationId,String kind,Document document) { }
    private final MemorySyncRepository repository;
    private final MemoryRepository memories;
    private final PersonaAccessService access;
    private final ResourceRepository resources;
    public MemorySyncService(MemorySyncRepository repository,MemoryRepository memories,PersonaAccessService access,ResourceRepository resources) {
        this.repository=repository;this.memories=memories;this.access=access;this.resources=resources;
    }
    private Scope lock(String actor,String persona) {
        access.persona(actor,persona);access.lock("persona",persona);
        var row=access.persona(actor,persona);
        return new Scope(row.get("project_id").toString(),persona,actor);
    }
    private void owned(String actor,String persona,String memory) {
        var row=resources.one("persona_memory",memory);
        if(!actor.equals(row.get("user_id"))||!persona.equals(row.get("persona_id")))throw error(403,"private_memory");
    }
    private List<Map<String,Object>> history(Scope scope,String memory) { return repository.history(scope.personaId(),scope.userId(),memory); }
    private Map<String,Object> summary(Map<String,Object> row,boolean current,boolean blocked) {
        var out=new LinkedHashMap<String,Object>();
        for(String key:List.of("operation_id","revision","kind","state","created_at","terminal_at"))out.put(key,row.get(key));
        out.put("current",current);out.put("blocked_by_unresolved_operation",blocked);return out;
    }
    private boolean unresolved(Map<String,Object> row) { return Set.of("DISPATCHED","UNKNOWN").contains(row.get("state")); }
    private Map<String,Object> record(Scope scope,String memory,boolean withdraw) {
        var current=withdraw?List.<Map<String,Object>>of():memories.findConfirmedSource(memory,scope.personaId(),scope.userId());
        String kind=current.isEmpty()?"DELETE":"RETAIN";
        SourceRef ref=current.isEmpty()?null:MemoryProvider.reference(current.get(0));
        var rows=history(scope,memory);
        if(!rows.isEmpty()){
            var latest=rows.get(0);
            if(kind.equals(latest.get("kind"))&&Objects.equals(ref==null?null:ref.revision(),latest.get("fingerprint")))
                return summary(latest,true,rows.stream().anyMatch(this::unresolved));
        }
        long revision=rows.isEmpty()?1:((Number)rows.get(0).get("revision")).longValue()+1;
        repository.append(scope.projectId(),scope.personaId(),scope.userId(),memory,revision,id(),kind,
                ref==null?null:ref.sourceId(),ref==null?null:ref.revision(),ref==null?null:current.get(0).get("body").toString(),now());
        rows=history(scope,memory);return summary(rows.get(0),true,rows.stream().anyMatch(this::unresolved));
    }
    /** Called in the same transaction as confirmation/correction/rejection. */
    @Transactional public Map<String,Object> track(String actor,String persona,String memory) {
        var scope=lock(actor,persona);owned(actor,persona,memory);return record(scope,memory,false);
    }
    /** Must run before the source row is physically removed, in the caller's transaction. */
    @Transactional public Map<String,Object> withdraw(String actor,String persona,String memory) {
        var scope=lock(actor,persona);owned(actor,persona,memory);return record(scope,memory,true);
    }
    public List<Map<String,Object>> inspect(String actor,String persona,String memory) {
        access.persona(actor,persona);
        var rows=repository.history(persona,actor,memory);
        if(rows.isEmpty())throw error(404,"memory_sync_not_found");
        boolean blocked=rows.stream().anyMatch(this::unresolved);
        String latest=rows.get(0).get("operation_id").toString();
        return rows.stream().map(row->summary(row,latest.equals(row.get("operation_id")),blocked)).toList();
    }
    /** Offline fixture boundary only: no scheduler or transport invokes this method. */
    @Transactional public Intent stageFixture(String actor,String persona,String memory) {
        var scope=lock(actor,persona);var rows=history(scope,memory);
        if(rows.isEmpty())throw error(404,"memory_sync_not_found");
        if(rows.stream().anyMatch(this::unresolved))throw error(409,"memory_sync_result_unknown");
        var row=rows.get(0);
        if(!"PENDING".equals(row.get("state")))throw error(409,"memory_sync_not_pending");
        var current=memories.findConfirmedSource(memory,persona,actor);
        Document document=null;
        if("RETAIN".equals(row.get("kind"))){
            if(current.isEmpty()||!MemoryProvider.reference(current.get(0)).revision().equals(row.get("fingerprint")))throw error(409,"memory_sync_source_changed");
            document=new Document(MemoryProvider.reference(current.get(0)),current.get(0).get("body").toString());
        }else if(!current.isEmpty())throw error(409,"memory_sync_source_changed");
        if(repository.stage(row.get("operation_id").toString())!=1)throw error(409,"memory_sync_not_pending");
        return new Intent(scope,memory,((Number)row.get("revision")).longValue(),UUID.fromString(row.get("operation_id").toString()),row.get("kind").toString(),document);
    }
    @Transactional public void unknownFixture(String actor,String persona,UUID operation) {
        lock(actor,persona);var rows=repository.operation(persona,actor,operation.toString());
        if(rows.isEmpty())throw error(404,"memory_sync_not_found");
        if(!unresolved(rows.get(0)))throw error(409,"memory_sync_not_dispatched");
        repository.unknown(operation.toString());
    }
    /** A fixture asserts known terminal completion. This is not a provider deletion receipt. */
    @Transactional public Map<String,Object> terminalFixture(String actor,String persona,UUID operation,long revision,boolean knownTerminal) {
        var scope=lock(actor,persona);var rows=repository.operation(persona,actor,operation.toString());
        if(rows.isEmpty())throw error(404,"memory_sync_not_found");
        var row=rows.get(0);
        if(((Number)row.get("revision")).longValue()!=revision)throw error(409,"memory_sync_revision_changed");
        if(!knownTerminal)throw error(422,"memory_sync_terminal_unverified");
        if(!unresolved(row)&&!"ACKED".equals(row.get("state")))throw error(409,"memory_sync_not_dispatched");
        repository.terminal(operation.toString(),now());
        var current=history(scope,row.get("memory_id").toString());
        var resolved=repository.operation(persona,actor,operation.toString()).get(0);
        return summary(resolved,current.get(0).get("operation_id").equals(row.get("operation_id")),current.stream().anyMatch(this::unresolved));
    }
}
