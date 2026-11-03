package internal.distilly;

import internal.distilly.common.search.TextRelevance;
import internal.distilly.modules.memory.domain.MemoryProvider;
import internal.distilly.modules.memory.repository.MemoryRepository;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;

/** One frozen lexical supplement, test-classpath only. No runtime bean or configuration. */
final class MemorySpanShadowProvider implements MemoryProvider {
    private static final Pattern HAN_RUN=Pattern.compile("[\\p{IsHan}]+");
    private static final Pattern ENGLISH=Pattern.compile("[a-z0-9_]+");
    private final MemoryRepository repository;
    private final MemoryProvider baseline;
    MemorySpanShadowProvider(MemoryRepository repository,MemoryProvider baseline){this.repository=repository;this.baseline=baseline;}
    private static String normalized(String text){return Normalizer.normalize(text==null?"":text,Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);}
    record Evidence(boolean eligible,int absentHanTerms,int matchedHanTerms,int relevantHanPositions) {}
    static Evidence evidence(String query,String body){
        var terms=TextRelevance.recallTerms(query);int score=TextRelevance.score(terms,body);
        if(TextRelevance.sufficientRecallCoverage(terms,score))return new Evidence(false,0,0,0);
        String source=normalized(body),question=normalized(query);
        var han=new HashSet<String>();var matched=new HashSet<String>();int absent=0;
        for(String term:terms){
            if(ENGLISH.matcher(term).matches()){
                if(TextRelevance.score(Set.of(term),body)!=1)return new Evidence(false,0,0,0);
            }else{
                if(term.codePointCount(0,term.length())!=2)return new Evidence(false,0,0,0);
                han.add(term);if(source.contains(term))matched.add(term);else absent++;
            }
        }
        if(absent!=1||matched.size()<2)return new Evidence(false,absent,matched.size(),0);
        int positions=0;var runs=HAN_RUN.matcher(question);
        while(runs.find()){
            int[] chars=runs.group().codePoints().toArray();boolean[] required=new boolean[chars.length],covered=new boolean[chars.length];
            boolean[] edges=new boolean[Math.max(0,chars.length-1)];
            for(int i=0;i+1<chars.length;i++){
                String term=new String(chars,i,2);if(!han.contains(term))continue;
                required[i]=true;required[i+1]=true;
                if(matched.contains(term)){covered[i]=true;covered[i+1]=true;edges[i]=true;}
            }
            for(int i=0;i<chars.length;i++)if(required[i]){positions++;if(!covered[i])return new Evidence(false,absent,matched.size(),positions);}
            int cursor=0;
            for(int i=0;i<edges.length;){
                if(!edges[i]){i++;continue;}
                int start=i;while(i+1<edges.length&&edges[i+1])i++;
                String span=new String(chars,start,i-start+2);int found=source.indexOf(span,cursor);
                if(found<0)return new Evidence(false,absent,matched.size(),positions);
                cursor=found+span.length();i++;
            }
        }
        return new Evidence(positions>=4,absent,matched.size(),positions);
    }
    private record Extra(Map<String,Object> row,int score) {}
    @Override public List<SourceRef> recall(Scope scope,String query){
        var original=baseline.recall(scope,query);if(original.size()>=12)return original;
        var selected=new HashSet<String>();original.forEach(ref->selected.add(ref.memoryId()));
        var extras=new ArrayList<Extra>();var terms=TextRelevance.recallTerms(query);
        repository.scanConfirmedForRecall(scope.personaId(),scope.userId(),row->{
            if(!selected.contains(row.get("id"))&&evidence(query,row.get("body").toString()).eligible())
                extras.add(new Extra(row,TextRelevance.score(terms,row.get("body").toString())));
        });
        extras.sort(Comparator.comparingInt(Extra::score).reversed()
                .thenComparing(value->((Number)value.row().get("created_at")).longValue(),Comparator.reverseOrder())
                .thenComparing(value->value.row().get("id").toString()));
        var result=new ArrayList<>(original);
        for(var extra:extras){if(result.size()==12)break;result.add(MemoryProvider.reference(extra.row()));}
        return List.copyOf(result);
    }
}
