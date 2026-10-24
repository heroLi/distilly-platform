package internal.distilly.modules.memory.service;

import internal.distilly.common.search.TextRelevance;
import internal.distilly.modules.memory.repository.MemoryRepository;
import internal.distilly.modules.persona.service.PersonaAccessService;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static internal.distilly.common.validation.InputChecks.error;

@Service
public class MemoryRetrieval {
    private static final int LIMIT = 12;
    private record Ranked(Map<String, Object> row, int score) { }
    private static final Comparator<Ranked> BEST_FIRST = Comparator.comparingInt(Ranked::score).reversed()
        .thenComparing(value -> ((Number) value.row().get("created_at")).longValue(), Comparator.reverseOrder())
        .thenComparing(value -> (String) value.row().get("id"));

    private final MemoryRepository repository;
    private final PersonaAccessService access;

    public MemoryRetrieval(MemoryRepository repository, PersonaAccessService access) {
        this.repository = repository;
        this.access = access;
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
        access.persona(actor, personaId);
        boolean overview = query.strip().matches("(?s)(?:请问[，,\\s]*)?(?:你记得我什么|(?:你)?记得我的哪些(?:偏好|经历|事情)?|(?:我们)?有哪些共同经历|关于我的偏好)[？?！!。\\s]*");
        var queryTerms = TextRelevance.recallTerms(query);
        if (!overview && queryTerms.isEmpty()) return List.of();
        var relevance = TextRelevance.prepare(queryTerms);
        var best = new PriorityQueue<Ranked>(LIMIT, BEST_FIRST.reversed());
        repository.scanConfirmedForRecall(personaId, actor, row -> {
            int score = relevance.score((String) row.get("body"));
            if (!overview && !TextRelevance.sufficientRecallCoverage(queryTerms, score)) return;
            var candidate = new Ranked(row, score);
            if (best.size() < LIMIT) best.add(candidate);
            else if (BEST_FIRST.compare(candidate, best.peek()) < 0) {
                best.poll();
                best.add(candidate);
            }
        });
        return best.stream().sorted(BEST_FIRST)
            .map(value -> Map.<String, Object>of("id", value.row().get("id"), "body", value.row().get("body"),
                    "source_id", value.row().get("source_id"), "origin", "confirmed_interaction"))
            .toList();
    }
}
