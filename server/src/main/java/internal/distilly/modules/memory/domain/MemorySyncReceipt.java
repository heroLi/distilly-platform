package internal.distilly.modules.memory.domain;

import java.util.UUID;

/** A local simulator receipt. No public endpoint accepts this object. */
public record MemorySyncReceipt(int version,String mode,MemoryProvider.Scope scope,String memoryId,
        UUID operationId,long revision,String kind,Outcome outcome,String code) {
    public enum Outcome { APPLIED, NOT_APPLIED }
}
