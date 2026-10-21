package internal.distilly.modules.knowledge.domain;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Bounded orthographic/ASCII plural heuristics, not semantic inference or translation. */
public final class RecordLexicalQuery {
    private static final Pattern LATIN = Pattern.compile("[\\p{IsLatin}0-9_]+");
    private static final Pattern HAN = Pattern.compile("[\\p{IsHan}]+");
    private static final Set<String> FUNCTION_WORDS = Set.of(
            "a", "an", "the", "is", "are", "was", "were", "be", "been", "being", "do", "does", "did",
            "have", "has", "had", "what", "which", "who", "whom", "whose", "where", "when", "why", "how",
            "please", "tell", "me", "about", "and", "or", "of", "to", "in", "on", "at", "for", "from",
            "with", "it", "its", "this", "that", "these", "those", "can", "could", "will", "would",
            "should", "may", "i", "you", "your", "my", "not", "no", "never");
    private static final Set<String> INVARIANT = Set.of("news", "series", "species");
    private final List<Set<String>> latin = new ArrayList<>();
    private final Set<String> han = new LinkedHashSet<>();

    public RecordLexicalQuery(String question) {
        String normalized = normalize(question);
        var words = LATIN.matcher(normalized);
        var seen = new LinkedHashSet<String>();
        while (words.find()) {
            String token = words.group();
            if (!FUNCTION_WORDS.contains(token) && seen.add(token)) latin.add(forms(token));
        }
        var runs = HAN.matcher(normalized);
        while (runs.find()) {
            int[] points = runs.group().codePoints().toArray();
            for (int i = 0; i + 1 < points.length; i++) han.add(new String(points, i, 2));
        }
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    }

    private static Set<String> forms(String token) {
        var result = new LinkedHashSet<String>();
        result.add(token);
        if (!token.matches("[a-z]+") || INVARIANT.contains(token)) return result;
        int size = token.length();
        if (size > 4 && token.endsWith("ies")) {
            result.add(token.substring(0, size - 3) + "y");
            result.add(token.substring(0, size - 1)); // -ie nouns also take -s.
        }
        if (size > 4 && token.matches(".*(ches|shes|xes|zes|sses)$"))
            result.add(token.substring(0, size - 2));
        if (size > 3 && token.endsWith("s") && !token.endsWith("ss") && !token.endsWith("us")
                && !token.endsWith("is") && !token.endsWith("ics"))
            result.add(token.substring(0, size - 1));
        return result;
    }

    public long score(String source) {
        if (latin.isEmpty() && han.isEmpty()) return 0;
        String normalized = normalize(source);
        var sourceWords = new LinkedHashSet<String>();
        var words = LATIN.matcher(normalized);
        while (words.find()) sourceWords.addAll(forms(words.group()));
        // Explicit Latin identifiers and numeric values must all be present.
        // This intentionally prefers abstention over guessing an entity or date.
        for (var alternatives : latin)
            if (alternatives.stream().noneMatch(sourceWords::contains)) return 0;
        return latin.size() + han.stream().filter(normalized::contains).count();
    }
}
