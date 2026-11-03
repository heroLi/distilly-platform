package internal.distilly;

import java.util.*;

/** Test-only retrieval measurement. No quality acceptance threshold or answer scoring. */
final class MemoryDiagnosticMetrics {
    private int queries, positives, negatives, negativeReturns, predicted, goldCount, tp12, tp5, hit12, hit5;
    private double reciprocalRanks;

    void add(List<String> gold,List<String> actual) {
        if(new HashSet<>(gold).size()!=gold.size()||new HashSet<>(actual).size()!=actual.size()||actual.size()>12)
            throw new IllegalArgumentException("invalid_diagnostic_ranking");
        queries++;predicted+=actual.size();goldCount+=gold.size();
        int first=0,matched12=0,matched5=0;
        for(int n=0;n<actual.size();n++)if(gold.contains(actual.get(n))){
            matched12++;if(n<5)matched5++;if(first==0)first=n+1;
        }
        tp12+=matched12;tp5+=matched5;
        if(gold.isEmpty()){negatives++;if(!actual.isEmpty())negativeReturns++;}
        else{positives++;if(first>0){hit12++;reciprocalRanks+=1.0/first;}if(matched5>0)hit5++;}
    }
    Map<String,Object> report() {
        var out=new LinkedHashMap<String,Object>();
        out.put("queries",queries);out.put("positive_queries",positives);out.put("negative_queries",negatives);
        out.put("negative_queries_with_returns",negativeReturns);out.put("gold_sources",goldCount);
        out.put("returned_sources",predicted);out.put("tp_at_12",tp12);out.put("fp_at_12",predicted-tp12);out.put("fn_at_12",goldCount-tp12);
        out.put("positive_hits_at_5",hit5);out.put("positive_hits_at_12",hit12);
        out.put("hit_at_5",positives==0?null:(double)hit5/positives);
        out.put("hit_at_12",positives==0?null:(double)hit12/positives);
        out.put("recall_at_5_micro",goldCount==0?null:(double)tp5/goldCount);
        out.put("recall_at_12_micro",goldCount==0?null:(double)tp12/goldCount);
        out.put("precision_at_12_micro",predicted==0?null:(double)tp12/predicted);
        out.put("mrr_at_12_positive_queries",positives==0?null:reciprocalRanks/positives);
        return out;
    }
}
