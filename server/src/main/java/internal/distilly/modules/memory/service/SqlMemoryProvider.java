package internal.distilly.modules.memory.service;

import internal.distilly.common.search.TextRelevance;
import internal.distilly.modules.memory.domain.MemoryProvider;
import internal.distilly.modules.memory.repository.MemoryRepository;
import java.util.*;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/** The existing streamed lexical algorithm, unchanged; default runtime provider. */
@Component
@Primary
public class SqlMemoryProvider implements MemoryProvider {
    private static final int LIMIT=12;
    private record Ranked(Map<String,Object> row,int score) { }
    private static final Comparator<Ranked> BEST_FIRST=Comparator.comparingInt(Ranked::score).reversed()
        .thenComparing(value->((Number)value.row().get("created_at")).longValue(),Comparator.reverseOrder())
        .thenComparing(value->(String)value.row().get("id"));
    private final MemoryRepository repository;
    public SqlMemoryProvider(MemoryRepository repository) { this.repository=repository; }

    @Override public List<SourceRef> recall(Scope scope,String query) {
        boolean overview=query.strip().matches("(?s)(?:请问[，,\\s]*)?(?:你记得我什么|(?:你)?记得我的哪些(?:偏好|经历|事情)?|(?:我们)?有哪些共同经历|关于我的偏好)[？?！!。\\s]*");
        var queryTerms=TextRelevance.recallTerms(query);
        if(!overview&&queryTerms.isEmpty())return List.of();
        var relevance=TextRelevance.prepare(queryTerms);
        var best=new PriorityQueue<Ranked>(LIMIT,BEST_FIRST.reversed());
        repository.scanConfirmedForRecall(scope.personaId(),scope.userId(),row->{
            int score=relevance.score((String)row.get("body"));
            if(!overview&&!TextRelevance.sufficientRecallCoverage(queryTerms,score))return;
            var candidate=new Ranked(row,score);
            if(best.size()<LIMIT)best.add(candidate);
            else if(BEST_FIRST.compare(candidate,best.peek())<0){best.poll();best.add(candidate);}
        });
        return best.stream().sorted(BEST_FIRST).map(value->MemoryProvider.reference(value.row())).toList();
    }
}
