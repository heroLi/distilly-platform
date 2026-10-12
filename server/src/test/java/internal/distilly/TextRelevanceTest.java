package internal.distilly;

import internal.distilly.common.search.TextRelevance;
import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TextRelevanceTest {
    @Test
    void englishRequiresWholeWordsAfterUnicodeNormalization() {
        var query = TextRelevance.prepare(TextRelevance.terms("CAFÉ ＣＯＦＦＥＥ quiet"));
        assertEquals(2, query.score("上海 Ｃｏｆｆｅｅ quiet coffee quiet"));
        assertEquals(0, query.score("coffees quietly cafe"));
        assertEquals(1, query.score("coffee-shop"));
        assertEquals(0, query.score("coffee_shop"));
    }

    @Test
    void mixedQueriesKeepHanMatchesAndDistinctEnglishCounts() {
        var query = TextRelevance.prepare(TextRelevance.terms("青岛海边 COFFEE"));
        assertEquals(4, query.score("我们在青岛海边喝coffee，coffee"));
        assertEquals(3, query.score("青岛海边喝coffees"));
        assertEquals(1, query.score("北京公园coffee"));
        assertEquals(2, TextRelevance.score("𠀀青岛", "𠀀青岛"));
    }

    @Test
    void emptyBodiesAndExplicitStopTermsPreserveLegacyRules() {
        var query = TextRelevance.prepare(Set.of("the", "a", "coffee", "青岛"));
        assertEquals(2, query.score("the a coffee青岛"));
        assertEquals(0, query.score(null));
        assertEquals(0, query.score("  "));
        assertEquals(0, TextRelevance.prepare(Set.of()).score("coffee青岛"));
        assertEquals(2, TextRelevance.score("青岛海边", "青岛和岛海"));
    }

    @Test
    void preparedQueryIsAnImmutableSnapshotAcrossEvidenceRows() {
        var terms = new LinkedHashSet<>(Set.of("coffee", "青岛"));
        var query = TextRelevance.prepare(terms);
        terms.clear();
        terms.add("tea");
        assertEquals(2, query.score("青岛coffee"));
        assertEquals(0, query.score("tea"));
        assertEquals(1, query.score("coffee coffee"));
    }
}
