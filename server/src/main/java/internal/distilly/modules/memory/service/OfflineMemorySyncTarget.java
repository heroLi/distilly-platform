package internal.distilly.modules.memory.service;

import internal.distilly.modules.memory.domain.*;
import internal.distilly.modules.memory.repository.*;
import internal.distilly.modules.persona.service.PersonaAccessService;
import java.util.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Atomic local projection/fence simulation; no HTTP client, model or external service. */
@Component
@org.springframework.context.annotation.DependsOn("personaMigration")
public class OfflineMemorySyncTarget implements MemorySyncTarget {
    private final OfflineMemorySyncRepository repository;
    private final MemorySyncRepository journal;
    private final MemoryRepository memories;
    private final PersonaAccessService access;
    public OfflineMemorySyncTarget(OfflineMemorySyncRepository repository,MemorySyncRepository journal,MemoryRepository memories,PersonaAccessService access) {
        this.repository=repository;this.journal=journal;this.memories=memories;this.access=access;
    }
    private void lock(MemorySyncService.Intent intent) {
        var s=intent.scope();access.persona(s.userId(),s.personaId());access.lock("persona",s.personaId());
        var p=access.persona(s.userId(),s.personaId());
        if(!s.projectId().equals(p.get("project_id")))throw new Unknown();
    }
    private MemorySyncReceipt stored(MemorySyncService.Intent intent) {
        var rows=repository.receipt(intent.operationId().toString());if(rows.isEmpty())return null;
        var row=rows.get(0);var s=intent.scope();
        if(!s.projectId().equals(row.get("project_id"))||!s.personaId().equals(row.get("persona_id"))||!s.userId().equals(row.get("user_id"))
                ||!intent.memoryId().equals(row.get("memory_id"))||intent.revision()!=((Number)row.get("revision")).longValue()||!intent.kind().equals(row.get("kind")))throw new Unknown();
        return receipt(intent,MemorySyncReceipt.Outcome.valueOf(row.get("outcome").toString()),row.get("code").toString());
    }
    private MemorySyncReceipt receipt(MemorySyncService.Intent i,MemorySyncReceipt.Outcome outcome,String code) {
        return new MemorySyncReceipt(1,"OFFLINE_SIMULATION",i.scope(),i.memoryId(),i.operationId(),i.revision(),i.kind(),outcome,code);
    }
    @Override @Transactional public MemorySyncReceipt apply(MemorySyncService.Intent intent) {
        lock(intent);var previous=stored(intent);if(previous!=null)return previous;
        var s=intent.scope();var rows=journal.history(s.personaId(),s.userId(),intent.memoryId());
        var current=memories.findConfirmedSource(intent.memoryId(),s.personaId(),s.userId());
        boolean valid=!rows.isEmpty()&&intent.operationId().toString().equals(rows.get(0).get("operation_id"))
            &&Set.of("DISPATCHED","UNKNOWN").contains(rows.get(0).get("state"));
        if("RETAIN".equals(intent.kind()))valid=valid&&intent.document()!=null&&!current.isEmpty()
            &&intent.document().source().equals(MemoryProvider.reference(current.get(0)));
        else valid=valid&&"DELETE".equals(intent.kind())&&current.isEmpty();
        var receipt=receipt(intent,valid?MemorySyncReceipt.Outcome.APPLIED:MemorySyncReceipt.Outcome.NOT_APPLIED,valid?"NONE":"MOCK_FENCED");
        if(valid){
            if("RETAIN".equals(intent.kind()))repository.project(s.personaId(),s.userId(),intent.memoryId(),intent.revision(),intent.document().source().revision());
            else repository.remove(s.personaId(),s.userId(),intent.memoryId());
        }
        repository.receipt(receipt);return receipt;
    }
    @Override @Transactional public MemorySyncReceipt reconcile(MemorySyncService.Intent intent) {
        lock(intent);var previous=stored(intent);if(previous!=null)return previous;
        // This is a durable cancellation fence, NOT an inference that an absent remote operation is safe.
        // apply() takes the same lock and must honor this receipt if it arrives later.
        var fenced=receipt(intent,MemorySyncReceipt.Outcome.NOT_APPLIED,"MOCK_FENCED");repository.receipt(fenced);return fenced;
    }
}
