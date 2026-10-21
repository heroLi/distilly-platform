package internal.distilly;

import internal.distilly.modules.knowledge.domain.RecordLexicalQuery;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RecordLexicalQueryTest {
    @Test void compatibilityFormsAndCaseAreMatchingOnly() {
        assertTrue(new RecordLexicalQuery("QUEUE").score("ｑｕｅｕｅ metadata") > 0);
        assertTrue(new RecordLexicalQuery("ＦＯＲＭ １２").score("form 12") > 0);
    }
    @Test void wholeLatinTokensAndIdentifiersCannotMatchSubstrings() {
        assertEquals(0, new RecordLexicalQuery("art").score("cart"));
        assertEquals(0, new RecordLexicalQuery("retry").score("retry_key"));
        assertTrue(new RecordLexicalQuery("retry_key").score("field retry_key") > 0);
    }
    @Test void genericPluralFormsAreLimitedToAsciiWords() {
        assertTrue(new RecordLexicalQuery("policies").score("policy") > 0);
        assertTrue(new RecordLexicalQuery("box").score("boxes") > 0);
        assertTrue(new RecordLexicalQuery("movie").score("movies") > 0);
        assertTrue(new RecordLexicalQuery("cats").score("cat") > 0);
        assertEquals(0, new RecordLexicalQuery("new").score("news"));
        assertEquals(0, new RecordLexicalQuery("statu").score("status"));
    }
    @Test void contentWordsAndNumbersRequireExplicitMatches() {
        assertTrue(new RecordLexicalQuery("what are the policies").score("policy") > 0);
        assertEquals(0, new RecordLexicalQuery("Orion 2032-04-08").score("Orion 2031-04-08"));
        assertEquals(0, new RecordLexicalQuery("Orion policy").score("Nova policy"));
        assertEquals(0, new RecordLexicalQuery("what is it").score("it is a policy"));
    }
    @Test void negationIsNotRewrittenAndChineseRemainsLiteral() {
        assertTrue(new RecordLexicalQuery("retry").score("Do not retry") > 0);
        assertTrue(new RecordLexicalQuery("自动重试").score("未启用自动重试") > 0);
        assertEquals(0, new RecordLexicalQuery("拍照").score("摄影"));
        assertEquals(0, new RecordLexicalQuery("咖啡").score("coffee"));
    }
}
