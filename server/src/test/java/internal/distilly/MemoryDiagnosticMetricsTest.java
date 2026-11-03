package internal.distilly;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MemoryDiagnosticMetricsTest {
    @Test void missesFalseReturnsAndRankAreMeasuredWithoutCallingThemQualitySuccess(){
        var m=new MemoryDiagnosticMetrics();
        m.add(List.of("a"),List.of("extra","a"));m.add(List.of("b"),List.of());m.add(List.of(),List.of("wrong"));
        var r=m.report();assertEquals(1,r.get("tp_at_12"));assertEquals(2,r.get("fp_at_12"));assertEquals(1,r.get("fn_at_12"));
        assertEquals(0.5,r.get("recall_at_12_micro"));assertEquals(1.0/3,r.get("precision_at_12_micro"));
        assertEquals(0.25,r.get("mrr_at_12_positive_queries"));assertEquals(1,r.get("negative_queries_with_returns"));
        assertFalse(r.containsKey("quality_pass"));
    }
    @Test void top5AndTop12AndUndefinedDenominatorsStayDistinct(){
        var m=new MemoryDiagnosticMetrics();m.add(List.of("a","b"),List.of("x1","x2","x3","x4","x5","a"));
        assertEquals(0.0,m.report().get("recall_at_5_micro"));assertEquals(0.5,m.report().get("recall_at_12_micro"));
        assertEquals(1.0/6,m.report().get("mrr_at_12_positive_queries"));
        var empty=new MemoryDiagnosticMetrics();empty.add(List.of(),List.of());
        assertNull(empty.report().get("precision_at_12_micro"));assertNull(empty.report().get("mrr_at_12_positive_queries"));
    }
    @Test void duplicateOrOversizeRankingsCannotInflateScores(){
        var m=new MemoryDiagnosticMetrics();
        assertThrows(IllegalArgumentException.class,()->m.add(List.of("a"),List.of("a","a")));
        assertThrows(IllegalArgumentException.class,()->m.add(List.of("a","a"),List.of("a")));
        assertThrows(IllegalArgumentException.class,()->m.add(List.of(),java.util.stream.IntStream.range(0,13).mapToObj(String::valueOf).toList()));
    }
}
