package internal.distilly.modules.memory.service;

import internal.distilly.config.PlatformProperties;
import internal.distilly.modules.memory.domain.MemorySyncReceipt;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import static internal.distilly.common.validation.InputChecks.error;

/** Explicit bounded offline advancement; no scheduler or real transport can be enabled. */
@Service
public class MemorySyncConsumer {
    private final MemorySyncService journal;
    private final MemorySyncTarget target;
    private final PlatformProperties properties;
    public MemorySyncConsumer(MemorySyncService journal,MemorySyncTarget target,PlatformProperties properties) {this.journal=journal;this.target=target;this.properties=properties;}
    private boolean enabled(){return properties.isDemo()&&"offline".equals(properties.getMemorySyncMode());}
    private void requireEnabled(){if(!enabled())throw error(409,"memory_sync_consumer_disabled");}
    public Map<String,Object> status(String actor,String persona){return Map.of("mode",enabled()?"OFFLINE_SIMULATION":"DISABLED","real_provider_enabled",false,"items",journal.privateStatus(actor,persona));}
    public Map<String,Object> advance(String actor,String persona,String memory){
        requireEnabled();
        var unresolved=journal.unresolvedIntent(actor,persona,memory);
        if(unresolved!=null){
            receive(actor,persona,unresolved,true);
            // A superseded operation may now be terminal; latest revision still needs one bounded apply.
            var rows=journal.inspect(actor,persona,memory);
            if(!"PENDING".equals(rows.get(0).get("state")))return status(actor,persona);
        }
        if(!"PENDING".equals(journal.inspect(actor,persona,memory).get(0).get("state")))return status(actor,persona);
        var intent=journal.stageFixture(actor,persona,memory);receive(actor,persona,intent,false);return status(actor,persona);
    }
    private void receive(String actor,String persona,MemorySyncService.Intent intent,boolean reconcile){
        try{
            MemorySyncReceipt receipt=reconcile?target.reconcile(intent):target.apply(intent);
            if(receipt==null||!intent.scope().equals(receipt.scope())||!intent.memoryId().equals(receipt.memoryId())
                    ||!intent.operationId().equals(receipt.operationId())||intent.revision()!=receipt.revision()||!intent.kind().equals(receipt.kind()))
                throw new MemorySyncTarget.Unknown();
            journal.settleReceipt(actor,persona,receipt);
        }catch(ResponseStatusException denied){
            if(denied.getStatusCode().value()==401||denied.getStatusCode().value()==403||denied.getStatusCode().value()==404)throw denied;
            journal.markUnconfirmed(actor,persona,intent.operationId());
        }catch(RuntimeException unconfirmed){journal.markUnconfirmed(actor,persona,intent.operationId());}
    }
    public Map<String,Object> retry(String actor,String persona,String memory){requireEnabled();journal.retryFailed(actor,persona,memory);return status(actor,persona);}
}
