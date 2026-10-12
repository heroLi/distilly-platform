package internal.distilly.modules.knowledge.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.TreeSet;
import java.util.regex.Pattern;

/** Structure-aware raw-text spans. Never normalize the text used as evidence. */
public final class MaterialChunker {

    private static final int MAX_LENGTH = 6000;
    private static final int OVERLAP = 240;
    private static final int MAX_OVERLAP = 480;
    private static final Pattern FENCE = Pattern.compile("^ {0,3}(`{3,}|~{3,})(.*)$");
    private static final Pattern HEADING = Pattern.compile("^ {0,3}#{1,6}[ \\t]+.*$");
    private static final Pattern LIST = Pattern.compile("^\\s*(?:[-+*]|[0-9]{1,9}[.)])[ \\t]+.*$");

    private record Span(int start, int end) {
        boolean contains(int offset) {
            return start < offset && offset < end;
        }
    }

    private record Line(int start, int end, String text) {
    }

    private record Boundaries(NavigableSet<Integer> sections, NavigableSet<Integer> paragraphs,
            NavigableSet<Integer> lines, NavigableSet<Integer> sentences, List<Span> blocks) {
        boolean protectedAt(int offset) {
            return blocks.stream().anyMatch(s -> s.end() - s.start() <= MAX_LENGTH && s.contains(offset));
        }
    }

    private MaterialChunker() {
    }

    public static List<Map<String, Object>> split(String materialId, String body) {
        var boundaries = boundaries(body);
        var chunks = new ArrayList<Map<String, Object>>();
        int start = 0, covered = 0;
        while (covered < body.length()) {
            int end = end(body, boundaries, start);
            // Overlap must never prevent forward progress or split an otherwise fitting block.
            if (end <= covered) {
                start = covered;
                end = end(body, boundaries, start);
            }
            chunks.add(Map.of("material_id", materialId, "chunk_id", materialId + ":" + chunks.size(),
                    "text", body.substring(start, end), "start_offset", start, "end_offset", end,
                    "chunk_strategy", "structure-overlap-v2"));
            covered = end;
            start = overlapStart(body, boundaries, start, end);
        }
        return chunks;
    }

    private static int end(String body, Boundaries boundaries, int start) {
        int limit = safeBoundary(body, Math.min(start + MAX_LENGTH, body.length()));
        if (limit == body.length())
            return limit;
        // Move a complete code block/table/list to the next chunk, even if the prefix is short.
        int blockStart = limit;
        for (Span block : boundaries.blocks())
            if (block.end() - block.start() <= MAX_LENGTH && block.contains(limit) && block.start() > start)
                blockStart = Math.min(blockStart, block.start());
        if (blockStart != limit)
            return blockStart;
        int lower = start + MAX_LENGTH / 2;
        for (var candidates : List.of(boundaries.sections(), boundaries.paragraphs(), boundaries.lines(),
                boundaries.sentences())) {
            for (Integer candidate = candidates.floor(limit); candidate != null && candidate >= lower;
                    candidate = candidates.lower(candidate))
                if (!boundaries.protectedAt(candidate))
                    return candidate;
        }
        return limit;
    }

    private static int overlapStart(String body, Boundaries boundaries, int previousStart, int end) {
        int lower = Math.max(previousStart + 1, end - MAX_OVERLAP);
        int target = Math.max(lower, end - OVERLAP);
        // Prefer a complete trailing sentence/line. Bound extra overlap to avoid repeated large blocks.
        Integer before = null, after = null;
        for (var candidates : List.of(boundaries.paragraphs(), boundaries.lines(), boundaries.sentences())) {
            Integer value = candidates.floor(target);
            if (value != null && value >= lower && (before == null || value > before))
                before = value;
            value = candidates.ceiling(target);
            if (value != null && value <= end - OVERLAP / 2 && (after == null || value < after))
                after = value;
        }
        int start = before != null ? before : after != null ? after : safeBoundary(body, target);
        for (Span block : boundaries.blocks()) {
            if (block.end() - block.start() <= MAX_LENGTH && block.contains(start))
                start = block.start() >= lower ? block.start() : block.end();
        }
        return start <= previousStart ? end : Math.min(end, start);
    }

    private static Boundaries boundaries(String body) {
        var sections = new TreeSet<Integer>();
        var paragraphs = new TreeSet<Integer>();
        var lineEnds = new TreeSet<Integer>();
        var sentences = new TreeSet<Integer>();
        var blocks = new ArrayList<Span>();
        var lines = new ArrayList<Line>();
        int start = 0;
        while (start < body.length()) {
            int newline = body.indexOf('\n', start);
            int end = newline < 0 ? body.length() : newline + 1;
            String text = body.substring(start, end).stripTrailing();
            lines.add(new Line(start, end, text));
            lineEnds.add(end);
            if (text.isBlank())
                paragraphs.add(end);
            start = end;
        }
        for (int i = 0; i < lines.size(); i++) {
            Line line = lines.get(i);
            var fence = FENCE.matcher(line.text());
            if (fence.matches() && (fence.group(1).charAt(0) != '`' || !fence.group(2).contains("`"))) {
                char marker = fence.group(1).charAt(0);
                int count = fence.group(1).length(), last = i + 1;
                for (; last < lines.size(); last++) {
                    var closing = FENCE.matcher(lines.get(last).text());
                    if (closing.matches() && closing.group(1).charAt(0) == marker
                            && closing.group(1).length() >= count && closing.group(2).isBlank())
                        break;
                }
                last = Math.min(last, lines.size() - 1);
                blocks.add(new Span(line.start(), lines.get(last).end()));
                i = last;
            }
            else if (HEADING.matcher(line.text()).matches()) {
                sections.add(line.start());
                int next = i + 1;
                while (next < lines.size() && lines.get(next).text().isBlank())
                    next++;
                // Keep the heading with at least the first content line (when it fits).
                if (next < lines.size())
                    blocks.add(new Span(line.start(), lines.get(next).end()));
            }
            else if (isTable(line.text()) || LIST.matcher(line.text()).matches()) {
                boolean table = isTable(line.text());
                int last = i;
                while (last + 1 < lines.size()) {
                    String next = lines.get(last + 1).text();
                    if (table ? !isTable(next) : !(LIST.matcher(next).matches()
                            || (!next.isBlank() && (next.startsWith("  ") || next.startsWith("\t")))))
                        break;
                    last++;
                }
                blocks.add(new Span(line.start(), lines.get(last).end()));
                i = last;
            }
        }
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if ("。！？；!?;".indexOf(c) >= 0 || (c == '.' && i + 1 < body.length()
                    && Character.isWhitespace(body.charAt(i + 1)))) {
                int end = i + 1;
                while (end < body.length() && "”’\"')）】」』".indexOf(body.charAt(end)) >= 0)
                    end++;
                sentences.add(end);
            }
        }
        // A heading may overlap the start of a fence/table/list; keep their combined span together.
        var merged = new ArrayList<Span>();
        for (Span block : blocks) {
            if (!merged.isEmpty()) {
                Span previous = merged.get(merged.size() - 1);
                if (block.start() < previous.end() && Math.max(previous.end(), block.end()) - previous.start() <= MAX_LENGTH) {
                    merged.set(merged.size() - 1, new Span(previous.start(), Math.max(previous.end(), block.end())));
                    continue;
                }
            }
            merged.add(block);
        }
        return new Boundaries(sections, paragraphs, lineEnds, sentences, merged);
    }

    private static boolean isTable(String text) {
        String stripped = text.stripLeading();
        return stripped.startsWith("|") || stripped.indexOf('|') > 0;
    }

    private static int safeBoundary(String text, int offset) {
        if (offset > 0 && offset < text.length()
                && ((Character.isHighSurrogate(text.charAt(offset - 1)) && Character.isLowSurrogate(text.charAt(offset)))
                        || (text.charAt(offset - 1) == '\r' && text.charAt(offset) == '\n')))
            return offset - 1;
        return offset;
    }
}
