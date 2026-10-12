package internal.distilly.common.search;

import java.text.Normalizer;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Deterministic lexical recall, deliberately not semantic similarity. */
public final class TextRelevance {
    private static final Set<String> STOP = Set.of("我们", "你们", "他们", "什么", "怎么", "如何", "可以", "一个",
            "用户", "人物", "经历", "偏好", "共同", "关于", "我的", "你的", "曾经", "过去", "之前", "还有", "当时", "最近", "一下", "这个", "那个", "今天", "昨天", "记住", "记得", "知道", "是否", "能否", "喜欢",
            "like", "likes", "prefer", "prefers", "preference", "preferences", "where", "when", "did", "do", "does", "we", "us", "to", "in", "at", "was", "were", "which", "about", "the", "and", "what", "how", "you", "your", "my", "me", "is", "are", "can", "remember");
    private static final Pattern WORDS = Pattern.compile("[\\p{IsHan}]+|[a-z0-9_]+");
    private static final Pattern ENGLISH_WORDS = Pattern.compile("[a-z0-9_]+");
    private TextRelevance() { }

    /** Remove question framing only for recall; do not rewrite stored evidence or the model's current input. */
    public static Set<String> recallTerms(String query) {
        String value = normalize(query)
            .replaceAll("请记住|你还记得|的偏好|的经历|去哪里|在哪里|到哪里|是哪里|什么时候|哪一天|什么日期|日期|哪一年|哪儿|哪里|有哪些|怎么样|如何|怎样|怎么|什么|我们|你们|他们|我的|你的|之前|以前|曾经|过去|最近|今天|昨天|确认过|记住|记得|知道|喜欢|关于|是否|能否|可以|请问|这个|那个", " ")
            .replaceAll("[吗呢呀啊吧么][？?！!。\\s]*$", " ");
        return terms(value);
    }

    public static boolean sufficientRecallCoverage(Set<String> queryTerms, int matchedTerms) {
        return matchedTerms > 0 && matchedTerms >= Math.ceil(queryTerms.size() * 0.75);
    }

    public static Set<String> terms(String query) {
        var terms = new LinkedHashSet<String>();
        var matches = WORDS.matcher(normalize(query));
        while (matches.find()) {
            String word = matches.group();
            if (ENGLISH_WORDS.matcher(word).matches()) {
                if (word.length() > 1 && !STOP.contains(word)) terms.add(word);
            }
            else {
                int[] chars = word.codePoints().toArray();
                for (int i = 0; i + 1 < chars.length; i++) {
                    String term = new String(chars, i, 2);
                    if (!STOP.contains(term)) terms.add(term);
                }
            }
        }
        return terms;
    }

    public static int score(String query, String body) {
        return score(terms(query), body);
    }

    public static int score(Set<String> queryTerms, String body) {
        return prepare(queryTerms).score(body);
    }

    /** Snapshot and classify a query once before scanning multiple evidence rows. */
    public static PreparedQuery prepare(Set<String> queryTerms) {
        var english = new LinkedHashSet<String>();
        var other = new LinkedHashSet<String>();
        for (String term : queryTerms) {
            if (ENGLISH_WORDS.matcher(term).matches()) {
                // Body terms historically omit one-character and stop-word English tokens.
                if (term.length() > 1 && !STOP.contains(term)) english.add(term);
            }
            else other.add(term);
        }
        return new PreparedQuery(Set.copyOf(english), Set.copyOf(other));
    }

    public static final class PreparedQuery {
        private final Set<String> english;
        private final Set<String> other;

        private PreparedQuery(Set<String> english, Set<String> other) {
            this.english = english;
            this.other = other;
        }

        public int score(String body) {
            if ((english.isEmpty() && other.isEmpty()) || body == null || body.isBlank()) return 0;
            String normalized = normalize(body);
            int count = 0;
            for (String term : other) if (normalized.contains(term)) count++;
            if (!english.isEmpty()) {
                // Scan English boundaries only; Chinese body bigrams cannot match English terms.
                var matched = new LinkedHashSet<String>();
                var words = ENGLISH_WORDS.matcher(normalized);
                while (words.find()) {
                    String word = words.group();
                    if (english.contains(word)) matched.add(word);
                }
                count += matched.size();
            }
            return count;
        }
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    }
}
