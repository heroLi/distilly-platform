package internal.distilly;

import internal.distilly.modules.knowledge.domain.RecordSourceFilter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

class RecordSourceFilterTest {
    @Test void inclusiveDatesAreValidRawMentionsRatherThanEventInterpretation() {
        var filter = RecordSourceFilter.parse(Map.of("date_from", "2040-02-28", "date_to", "2040-03-01"));
        assertTrue(filter.matches("记录（2040-02-28），未安排执行。"));
        assertTrue(filter.matches("取消：2040-02-29；备用 2041-01-01"));
        assertTrue(filter.matches("记录 2040-03-01 。"));
        assertFalse(filter.matches("2040/02/29、２０４０-０２-２９、2040-2-29"));
        assertFalse(filter.matches("记录 2040-03-02 或无日期"));
        assertFalse(filter.matches("记录 2040-02-29T10:00:00 或 path/2040-02-29"));
    }

    @Test void exactIdentifiersRequireAllConditionsWithConservativeConnectedBoundaries() {
        var filter = RecordSourceFilter.parse(Map.of("identifiers", List.of("JOB-042", "REV_3")));
        assertTrue(filter.matches("编号（JOB-042），修订 REV_3 。"));
        for (String source : List.of("job-042 REV_3", "JOB-0420 REV_3", "JOB-042 REV_30", "XJOB-042 REV_3",
                "JOB-042/1 REV_3", "ID:JOB-042 REV_3", "JOB-042. REV_3", "ＪＯＢ-０４２ REV_3"))
            assertFalse(filter.matches(source), source);
        assertFalse(filter.matches("JOB-042"));
        assertTrue(RecordSourceFilter.none().matches("任意正文"));
    }

    @Test void dateAndIdentifiersCannotBorrowMissingConditions() {
        var filter = RecordSourceFilter.parse(Map.of("date_from", "2000-01-01", "date_to", "2000-01-01",
                "identifiers", List.of("X7")));
        assertTrue(filter.matches("2000-01-01 X7"));
        assertFalse(filter.matches("2000-01-02 X7"));
        assertFalse(filter.matches("2000-01-01 X8"));
        assertFalse(filter.matches("X7 未记录日期"));
    }

    @Test void rejectsInvalidTypesDatesAmbiguityAndUnboundedIdentifiers() {
        for (Object input : List.of(Map.of(), List.of(), "date", Map.of("unknown", "x"),
                Map.of("date_from", "2000-01-01"), Map.of("date_from", "2000-01-01", "date_to", "1999-12-31"),
                Map.of("date_from", "1900-02-29", "date_to", "1900-03-01"),
                Map.of("date_from", "0000-01-01", "date_to", "0001-01-01"),
                Map.of("date_from", "2000-1-01", "date_to", "2000-01-01"),
                Map.of("date_from", "２０００-01-01", "date_to", "2000-01-01"),
                Map.of("identifiers", List.of()), Map.of("identifiers", "X7"),
                Map.of("identifiers", List.of("X7", "X7")), Map.of("identifiers", List.of(" X7")),
                Map.of("identifiers", List.of("X/7")), Map.of("identifiers", List.of("X".repeat(81))),
                Map.of("identifiers", List.of("A", "B", "C", "D", "E", "F")))) {
            assertEquals(422, assertThrows(ResponseStatusException.class, () -> RecordSourceFilter.parse(input))
                    .getStatusCode().value());
        }
        assertThrows(ResponseStatusException.class, () -> RecordSourceFilter.parse(null));
    }

    @Test void snapshotsDoNotAliasRequestAndSupportFullCalendarBounds() {
        var ids = new ArrayList<>(List.of("ID-01"));
        var body = new LinkedHashMap<String, Object>();
        body.put("identifiers", ids); body.put("date_from", "0001-01-01"); body.put("date_to", "9999-12-31");
        var filter = RecordSourceFilter.parse(body);
        ids.set(0, "CHANGED"); body.clear();
        assertEquals(List.of("ID-01"), filter.applied().get("identifiers"));
        assertTrue(filter.matches("0001-01-01 ID-01"));
        assertTrue(filter.matches("9999-12-31 ID-01"));
        assertThrows(UnsupportedOperationException.class, () -> filter.applied().put("date_from", "changed"));
    }
}
