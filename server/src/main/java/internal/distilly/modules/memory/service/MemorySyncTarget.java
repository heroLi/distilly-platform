package internal.distilly.modules.memory.service;

import internal.distilly.modules.memory.domain.MemorySyncReceipt;

/** No real implementation is registered. Unknown is never proof of failure or absence. */
public interface MemorySyncTarget {
    MemorySyncReceipt apply(MemorySyncService.Intent intent);
    MemorySyncReceipt reconcile(MemorySyncService.Intent intent);
    final class Unknown extends RuntimeException {
        public Unknown() { super("memory_sync_result_unconfirmed"); }
    }
}
