package internal.distilly;

import internal.distilly.modules.memory.domain.MemoryProvider;
import internal.distilly.modules.memory.repository.MemoryRepository;
import java.util.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MemorySpanShadowProviderTest {
    @Test void oneInteriorBridgeCanJoinOrderedExactSpansWithoutAWordDictionary(){
        var evidence=MemorySpanShadowProvider.evidence("墨竹岚桥","合成记录：墨竹的岚桥。");
        assertTrue(evidence.eligible());assertEquals(1,evidence.absentHanTerms());assertEquals(2,evidence.matchedHanTerms());assertEquals(4,evidence.relevantHanPositions());
    }
    @Test void baselineMatchesAreNotReinterpretedAsSupplements(){
        assertFalse(MemorySpanShadowProvider.evidence("墨竹岚桥","墨竹岚桥").eligible());
    }
    @Test void missingEndpointOrReversedBodySpansCannotUseBagOfCharacters(){
        assertFalse(MemorySpanShadowProvider.evidence("墨竹岚桥","墨竹岚").eligible());
        assertFalse(MemorySpanShadowProvider.evidence("墨竹岚桥","岚桥以及墨竹").eligible());
        assertFalse(MemorySpanShadowProvider.evidence("墨竹岚桥","墨竹和别的内容").eligible());
    }
    @Test void englishIdentifiersKeepExactTokenBoundariesAndNoTranslationIsAdded(){
        assertFalse(MemorySpanShadowProvider.evidence("墨竹岚桥 part9","墨竹的岚桥 part90").eligible());
        // With the exact English token this already passes baseline coverage; no supplement needed.
        assertFalse(MemorySpanShadowProvider.evidence("墨竹岚桥 PART9","墨竹的岚桥 part9").eligible());
        assertFalse(MemorySpanShadowProvider.evidence("paper folding","合成折叠记录").eligible());
    }
    @Test void moreThanOneMissingBridgeIsRejectedAndSupplementDoesNotJudgeTruth(){
        assertFalse(MemorySpanShadowProvider.evidence("墨竹岚桥松塔","墨竹的岚桥与松塔").eligible());
        // Exact spans remain lexical evidence even inside a negated statement. No entailment claim.
        assertTrue(MemorySpanShadowProvider.evidence("墨竹岚桥","并非墨竹的岚桥").eligible());
    }
    @Test void baselinePrefixAndTop12ArePreserved(){
        var repository=mock(MemoryRepository.class);MemoryProvider baseline=scopeBaseline(11);
        doAnswer(call->{
            Consumer<Map<String,Object>> accept=call.getArgument(2);
            for(int i=0;i<3;i++)accept.accept(Map.of("id","extra"+i,"body","墨竹的岚桥","source_id","source"+i,"created_at",(long)i));
            return null;
        }).when(repository).scanConfirmedForRecall(eq("persona"),eq("actor"),any());
        var scope=new MemoryProvider.Scope("project","persona","actor");var base=baseline.recall(scope,"墨竹岚桥");
        var result=new MemorySpanShadowProvider(repository,baseline).recall(scope,"墨竹岚桥");
        assertEquals(12,result.size());assertEquals(base,result.subList(0,11));assertEquals("extra2",result.get(11).memoryId());
        var full=new MemorySpanShadowProvider(repository,scopeBaseline(12));clearInvocations(repository);assertEquals(12,full.recall(scope,"墨竹岚桥").size());verifyNoInteractions(repository);
    }
    private MemoryProvider scopeBaseline(int count){return (scope,query)->java.util.stream.IntStream.range(0,count).mapToObj(i->new MemoryProvider.SourceRef("base"+i,"source"+i,"fingerprint"+i)).toList();}
}
