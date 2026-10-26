package internal.distilly.infrastructure.memory;

import internal.distilly.modules.memory.domain.MemoryProvider;
import java.util.*;
import java.util.function.Function;

/** Official retain/recall/document argument contract only. No network implementation or Spring bean. */
public final class HindsightMemoryProvider implements MemoryProvider {
    public record Command(String operation,String bankId,Map<String,Object> arguments) { }
    private final Function<Command,Map<String,Object>> exchange;

    public HindsightMemoryProvider() { this(command->{throw new Unavailable();}); }
    /** Injected exchanges are for offline protocol fixtures in this revision. */
    public HindsightMemoryProvider(Function<Command,Map<String,Object>> exchange) { this.exchange=exchange; }

    /** Caller must persist this UUID before sending and reuse it only for the same logical operation. */
    public static Command retain(Scope scope,Document document,UUID operationId) {
        Objects.requireNonNull(operationId);
        var ref=document.source();
        return new Command("retain",scope.bankId(),Map.of("async",true,"operation_id",operationId.toString(),
                "items",List.of(Map.of("content",document.body(),"document_id",documentId(ref),"update_mode","replace","metadata",
                        Map.of("memory_id",ref.memoryId(),"source_id",ref.sourceId(),"source_version",ref.revision())))));
    }
    public static Command delete(Scope scope,SourceRef ref) {
        return new Command("delete_document",scope.bankId(),Map.of("document_id",documentId(ref)));
    }
    public static String documentId(SourceRef ref) { return "memory-"+ref.memoryId(); }

    @Override public List<SourceRef> recall(Scope scope,String query) {
        var response=exchange.apply(new Command("recall",scope.bankId(),Map.of("query",query,"types",List.of("world","experience"),"budget","low","max_tokens",2048)));
        if(response==null||!(response.get("results") instanceof List<?> results)||results.size()>100)throw new Unavailable();
        var refs=new LinkedHashSet<SourceRef>();
        for(Object raw:results){
            if(!(raw instanceof Map<?,?> result)||!(result.get("type") instanceof String type)||!Set.of("world","experience").contains(type)
                    ||!(result.get("metadata") instanceof Map<?,?> metadata))continue;
            if(!(metadata.get("memory_id") instanceof String id)||id.isBlank()||id.length()>100
                    ||!(metadata.get("source_id") instanceof String source)||source.isBlank()||source.length()>100
                    ||!(metadata.get("source_version") instanceof String revision)||!revision.matches("[0-9a-f]{64}"))continue;
            var ref=new SourceRef(id,source,revision);
            if(!documentId(ref).equals(result.get("document_id")))continue;
            refs.add(ref);if(refs.size()==12)break;
        }
        // Never consume text, chunks, reflect, observations or third-party facts as authoritative memory.
        return List.copyOf(refs);
    }
}
