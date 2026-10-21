package internal.distilly.modules.knowledge.domain;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import static internal.distilly.common.validation.InputChecks.error;

/** Explicit raw-record mention filters, independent of normalized lexical scoring. */
public final class RecordSourceFilter {
    public static final String CONTRACT = "RECORD_LITERAL_FILTERS_V1";
    private static final String CONNECTED = "A-Za-z0-9_./:-";
    private static final Pattern DATE_SHAPE = Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}");
    private static final Pattern SOURCE_DATE = Pattern.compile(
            "(?<![" + CONNECTED + "])[0-9]{4}-[0-9]{2}-[0-9]{2}(?![" + CONNECTED + "])");
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,79}");
    private static final RecordSourceFilter NONE = new RecordSourceFilter(null, null, List.of());
    private final LocalDate from;
    private final LocalDate to;
    private final List<String> identifiers;
    private final List<Pattern> identifierPatterns;

    private RecordSourceFilter(LocalDate from, LocalDate to, List<String> identifiers) {
        this.from = from;
        this.to = to;
        this.identifiers = List.copyOf(identifiers);
        this.identifierPatterns = identifiers.stream().map(value -> Pattern.compile(
                "(?<![" + CONNECTED + "])" + Pattern.quote(value) + "(?![" + CONNECTED + "])"))
                .toList();
    }

    public static RecordSourceFilter none() {
        return NONE;
    }

    /** Called only when the optional filters key is present; null/empty is invalid. */
    public static RecordSourceFilter parse(Object input) {
        if (!(input instanceof Map<?, ?> filters) || filters.isEmpty()
                || !Set.of("date_from", "date_to", "identifiers").containsAll(filters.keySet()))
            throw error(422, "invalid_record_filters");
        LocalDate from = null, to = null;
        if (filters.containsKey("date_from") || filters.containsKey("date_to")) {
            from = requiredDate(filters.get("date_from"));
            to = requiredDate(filters.get("date_to"));
            if (from.isAfter(to))
                throw error(422, "invalid_record_date");
        }
        var identifiers = new ArrayList<String>();
        if (filters.containsKey("identifiers")) {
            if (!(filters.get("identifiers") instanceof List<?> values) || values.isEmpty() || values.size() > 5)
                throw error(422, "invalid_record_identifier");
            for (Object value : values) {
                if (!(value instanceof String id) || !IDENTIFIER.matcher(id).matches() || identifiers.contains(id))
                    throw error(422, "invalid_record_identifier");
                identifiers.add(id);
            }
        }
        return new RecordSourceFilter(from, to, identifiers);
    }

    private static LocalDate requiredDate(Object input) {
        LocalDate date = input instanceof String value ? literalDate(value) : null;
        if (date == null)
            throw error(422, "invalid_record_date");
        return date;
    }

    private static LocalDate literalDate(String value) {
        if (!DATE_SHAPE.matcher(value).matches())
            return null;
        try {
            LocalDate date = LocalDate.parse(value);
            return date.getYear() >= 1 && date.getYear() <= 9999 ? date : null;
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    public boolean matches(String rawText) {
        if (from != null) {
            var dates = SOURCE_DATE.matcher(rawText);
            boolean found = false;
            while (dates.find()) {
                LocalDate date = literalDate(dates.group());
                if (date != null && !date.isBefore(from) && !date.isAfter(to)) {
                    found = true;
                    break;
                }
            }
            if (!found)
                return false;
        }
        for (Pattern identifier : identifierPatterns)
            if (!identifier.matcher(rawText).find())
                return false;
        return true;
    }

    public Map<String, Object> applied() {
        var result = new LinkedHashMap<String, Object>();
        if (from != null) {
            result.put("date_from", from.toString());
            result.put("date_to", to.toString());
        }
        if (!identifiers.isEmpty())
            result.put("identifiers", identifiers);
        return Map.copyOf(result);
    }
}
