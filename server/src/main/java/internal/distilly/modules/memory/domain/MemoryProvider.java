package internal.distilly.modules.memory.domain;

import java.util.List;
import java.util.Map;
import static internal.distilly.common.support.Digests.sha256;

/** A provider proposes source references, never authoritative text or access rights. */
public interface MemoryProvider {
    record Scope(String projectId, String personaId, String userId) {
        /** Routing only, not an authentication credential. Never accepted from a client. */
        public String bankId() {
            return "distilly-" + sha256(part(projectId) + part(personaId) + part(userId));
        }
        private static String part(String value) { return value.length() + ":" + value; }
    }
    record SourceRef(String memoryId, String sourceId, String revision) { }
    record Document(SourceRef source, String body) { }

    /** Current-state fingerprint, not a monotonic revision or provider acknowledgement. */
    static SourceRef reference(Map<String,Object> row) {
        String id=(String)row.get("id"),source=(String)row.get("source_id"),body=(String)row.get("body");
        return new SourceRef(id,source,sha256(id+"\n"+source+"\n"+body));
    }

    List<SourceRef> recall(Scope scope, String query);

    /** Only provider availability/protocol errors permit local fallback; never permission/SQL errors. */
    final class Unavailable extends RuntimeException {
        public Unavailable() { super("memory_provider_unavailable"); }
    }
}
