package internal.distilly;

import internal.distilly.common.json.JsonCodec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static internal.distilly.common.validation.InputChecks.items;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:synthetic-cost-page-test;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "platform.demo=true", "platform.worker-token=synthetic-cost-page-worker-token-32-characters" })
@AutoConfigureMockMvc
class SyntheticCostPageTest {
    @Autowired PlatformFixture platform;
    @Autowired JsonCodec json;
    @Autowired JdbcTemplate db;
    @Autowired MockMvc mvc;
    String project;

    @BeforeEach
    void setup() {
        for (String table : List.of("synthetic_cost_event", "synthetic_cost_reservation", "audit", "member", "project"))
            db.update("DELETE FROM " + table);
        project = (String) platform.createProject("owner", "合成费用列表回归").get("id");
        platform.member("owner", project, "editor", "EDITOR");
        platform.member("owner", project, "viewer", "VIEWER");
    }
    String base() { return "/api/projects/" + project + "/synthetic-costs"; }
    String pagePath() { return base() + "/reservations"; }
    Map<String, Object> decoded(ResultActions request) throws Exception {
        return json.decode(request.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
    Map<String, Object> postBody(String path, Map<String, Object> body) throws Exception {
        return decoded(mvc.perform(post(path).header("Authorization", "Bearer demo-owner")
                .contentType(MediaType.APPLICATION_JSON).content(json.encode(body))));
    }
    String reserve(String key, int amount) throws Exception {
        return postBody(pagePath(), Map.of("request_key", key, "reserved_fen", amount)).get("id").toString();
    }
    void outcome(String reservation, String state, int actual) throws Exception {
        var body = new LinkedHashMap<String, Object>();
        body.put("callback_key", reservation + "-bill");
        body.put("outcome", state);
        body.put("evidence", switch (state) {
            case "UNCERTAIN" -> "SIMULATED_UNKNOWN";
            case "RELEASED" -> "SIMULATED_ZERO_BILL";
            default -> "SIMULATED_BILL";
        });
        if (state.equals("SETTLED")) body.put("actual_fen", actual);
        postBody(pagePath() + "/" + reservation + "/outcomes", body);
    }
    Map<String, Object> page(String status, int page, int size) throws Exception {
        return decoded(mvc.perform(get(pagePath()).param("status", status)
                .param("page", Integer.toString(page)).param("size", Integer.toString(size))
                .header("Authorization", "Bearer demo-owner")));
    }
    @SuppressWarnings("unchecked")
    Map<String, Object> summary(Map<String, Object> response) { return (Map<String, Object>) response.get("summary"); }
    long number(Map<String, Object> row, String key) { return ((Number) row.get(key)).longValue(); }
    void amounts(Map<String, Object> response, int held, int spent, int count, int unresolved) {
        var snapshot = summary(response);
        assertEquals("SYNTHETIC_CNY_LEDGER_V1", response.get("contract"));
        assertEquals(Boolean.TRUE, response.get("synthetic"));
        assertEquals(Boolean.FALSE, response.get("real_egress_authorized"));
        assertEquals("SYNTHETIC_CNY_LEDGER_V1", snapshot.get("contract"));
        assertEquals(Boolean.TRUE, snapshot.get("synthetic"));
        assertEquals(Boolean.FALSE, snapshot.get("real_egress_authorized"));
        assertEquals(1000, number(snapshot, "limit_fen"));
        assertEquals(held, number(snapshot, "held_fen"));
        assertEquals(spent, number(snapshot, "spent_fen"));
        assertEquals(1000 - held - spent, number(snapshot, "available_fen"));
        assertEquals(count, number(snapshot, "reservation_count"));
        assertEquals(unresolved, number(snapshot, "unresolved_count"));
    }
    Map<String, Long> counts() {
        var result = new LinkedHashMap<String, Long>();
        for (String table : List.of("synthetic_cost_reservation", "synthetic_cost_event", "job", "persona_job",
                "persona_message", "persona_memory", "persona_conversation", "audit"))
            result.put(table, db.queryForObject("SELECT COUNT(*) FROM " + table, Long.class));
        return result;
    }
    Map<String, String> fourStates() throws Exception {
        var result = new LinkedHashMap<String, String>();
        result.put("RESERVED", reserve("new-hold", 100));
        result.put("UNCERTAIN", reserve("unknown-hold", 200));
        outcome(result.get("UNCERTAIN"), "UNCERTAIN", 0);
        result.put("RELEASED", reserve("zero-bill", 300));
        outcome(result.get("RELEASED"), "RELEASED", 0);
        result.put("SETTLED", reserve("settled-bill", 400));
        outcome(result.get("SETTLED"), "SETTLED", 250);
        return result;
    }

    @Test
    void emptyDefaultPageIsSyntheticAndDoesNotWriteAnything() throws Exception {
        var before = counts();
        var response = decoded(mvc.perform(get(pagePath()).header("Authorization", "Bearer demo-owner"))
                .andExpect(header().string("Cache-Control", "no-store")));
        assertEquals(1, number(response, "page"));
        assertEquals(20, number(response, "size"));
        assertEquals(0, number(response, "total"));
        assertTrue(items(response.get("items")).isEmpty());
        amounts(response, 0, 0, 0, 0);
        var blankStatus = decoded(mvc.perform(get(pagePath()).param("status", "")
                .header("Authorization", "Bearer demo-owner")));
        assertEquals(response, blankStatus);
        assertEquals(before, counts());
    }

    @Test
    void defaultUnresolvedIncludesUnknownHoldAndExcludesSettledAndReleased() throws Exception {
        var states = fourStates();
        var response = decoded(mvc.perform(get(pagePath()).header("Authorization", "Bearer demo-owner"))
                .andExpect(header().string("Cache-Control", "no-store")));
        assertEquals(2, number(response, "total"));
        var rows = items(response.get("items"));
        assertEquals(java.util.Set.of(states.get("RESERVED"), states.get("UNCERTAIN")),
                rows.stream().map(row -> row.get("id")).collect(java.util.stream.Collectors.toSet()));
        assertTrue(rows.stream().allMatch(row -> row.get("actual_fen") == null));
        amounts(response, 300, 250, 4, 2);
    }

    @Test
    void eachStatusFilterHasExactRowsButSummaryAlwaysUsesWholeProject() throws Exception {
        var states = fourStates();
        for (var state : states.entrySet()) {
            var response = page(state.getKey(), 1, 20);
            assertEquals(1, number(response, "total"));
            var row = items(response.get("items")).get(0);
            assertEquals(state.getValue(), row.get("id"));
            assertEquals(state.getKey(), row.get("status"));
            if (state.getKey().equals("SETTLED")) assertEquals(250, number(row, "actual_fen"));
            if (state.getKey().equals("RELEASED")) assertEquals(0, number(row, "actual_fen"));
            amounts(response, 300, 250, 4, 2);
        }
        var all = page("ALL", 1, 20);
        assertEquals(4, number(all, "total"));
        assertEquals(4, items(all.get("items")).size());
        amounts(all, 300, 250, 4, 2);
    }

    @Test
    void paginationIsStableForEqualTimestampsAndBeyondLastPageIsEmpty() throws Exception {
        var expected = new ArrayList<String>();
        for (int i = 0; i < 5; i++) expected.add(reserve("page-" + i, 100));
        db.update("UPDATE synthetic_cost_reservation SET created_at=1000 WHERE project_id=?", project);
        expected.sort(java.util.Comparator.reverseOrder());
        var observed = new ArrayList<String>();
        for (int selectedPage = 1; selectedPage <= 3; selectedPage++) {
            var response = page("ALL", selectedPage, 2);
            assertEquals(5, number(response, "total"));
            assertEquals(selectedPage, number(response, "page"));
            assertEquals(2, number(response, "size"));
            observed.addAll(items(response.get("items")).stream().map(row -> row.get("id").toString()).toList());
            amounts(response, 500, 0, 5, 5);
        }
        assertEquals(expected, observed);
        assertEquals(items(page("ALL", 1, 2).get("items")), items(page("ALL", 1, 2).get("items")));
        var beyond = page("ALL", 4, 2);
        assertEquals(5, number(beyond, "total"));
        assertTrue(items(beyond.get("items")).isEmpty());
        amounts(beyond, 500, 0, 5, 5);
    }

    @Test
    void listReadsDoNotAppendLedgerEventsTasksMemoriesOrAudit() throws Exception {
        fourStates();
        var before = counts();
        for (String filter : List.of("ALL", "UNRESOLVED", "RESERVED", "UNCERTAIN", "SETTLED", "RELEASED"))
            page(filter, 1, 2);
        assertEquals(before, counts());
    }

    @Test
    void rolesAndWorkspaceIsolationPreventListingForeignReservations() throws Exception {
        String reservation = reserve("owner-only", 100);
        for (String actor : List.of("editor", "viewer"))
            mvc.perform(get(pagePath()).header("Authorization", "Bearer demo-" + actor)).andExpect(status().isForbidden());
        mvc.perform(get(pagePath())).andExpect(status().isUnauthorized());
        String original = project;
        project = (String) platform.createProject("owner", "另一合成空间").get("id");
        var empty = page("ALL", 1, 20);
        assertEquals(0, number(empty, "total"));
        assertTrue(items(empty.get("items")).isEmpty());
        amounts(empty, 0, 0, 0, 0);
        mvc.perform(get(pagePath()).header("Authorization", "Bearer demo-viewer")).andExpect(status().isForbidden());
        mvc.perform(get(pagePath() + "/" + reservation).header("Authorization", "Bearer demo-owner"))
                .andExpect(status().isNotFound());
        project = original;
        assertEquals(1, number(page("ALL", 1, 20), "total"));
    }

    @Test
    void boundsAndUnknownStatusAreRejectedWithoutWrites() throws Exception {
        var before = counts();
        for (String invalid : List.of("0", "-1", "1000001"))
            mvc.perform(get(pagePath()).param("page", invalid).header("Authorization", "Bearer demo-owner"))
                    .andExpect(status().isUnprocessableEntity());
        for (String invalid : List.of("0", "-1", "51"))
            mvc.perform(get(pagePath()).param("size", invalid).header("Authorization", "Bearer demo-owner"))
                    .andExpect(status().isUnprocessableEntity());
        for (String invalid : List.of("UNKNOWN", "unresolved", "RESERVED' OR 1=1"))
            mvc.perform(get(pagePath()).param("status", invalid).header("Authorization", "Bearer demo-owner"))
                    .andExpect(status().isUnprocessableEntity());
        for (String field : List.of("page", "size"))
            mvc.perform(get(pagePath()).param(field, "not-an-integer").header("Authorization", "Bearer demo-owner"))
                    .andExpect(status().isBadRequest());
        assertTrue(items(page("ALL", 1000000, 50).get("items")).isEmpty());
        assertEquals(before, counts());
    }

    <T> List<T> race(Callable<T> a, Callable<T> b) throws Exception {
        var ready = new CountDownLatch(2); var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> { ready.countDown(); assertTrue(start.await(10, TimeUnit.SECONDS)); return a.call(); });
            var second = pool.submit(() -> { ready.countDown(); assertTrue(start.await(10, TimeUnit.SECONDS)); return b.call(); });
            assertTrue(ready.await(10, TimeUnit.SECONDS)); start.countDown();
            return List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
        } finally { start.countDown(); pool.shutdownNow(); assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS)); }
    }
    @Test
    void concurrentSettlementCannotMixUnresolvedRowsAndSummaryFromDifferentSnapshots() throws Exception {
        String reservation = reserve("concurrent-snapshot", 500);
        platform.member("owner", project, "editor", "OWNER");
        var callback = Map.<String, Object>of("callback_key", "concurrent-bill", "outcome", "SETTLED",
                "evidence", "SIMULATED_BILL", "actual_fen", 220);
        var results = race(() -> page("UNRESOLVED", 1, 20), () -> decoded(mvc.perform(
                post(pagePath() + "/" + reservation + "/outcomes").header("Authorization", "Bearer demo-editor")
                        .contentType(MediaType.APPLICATION_JSON).content(json.encode(callback)))));
        var response = results.get(0);
        var rows = items(response.get("items"));
        if (rows.isEmpty()) {
            assertEquals(0, number(response, "total"));
            amounts(response, 0, 220, 1, 0);
        } else {
            assertEquals(1, rows.size());
            assertEquals(1, number(response, "total"));
            assertEquals("RESERVED", rows.get(0).get("status"));
            amounts(response, 500, 0, 1, 1);
        }
        var current = page("ALL", 1, 20);
        assertEquals("SETTLED", items(current.get("items")).get(0).get("status"));
        amounts(current, 0, 220, 1, 0);
    }
}

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:synthetic-cost-page-disabled-test;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "platform.demo=false", "platform.worker-token=synthetic-page-worker-token-32-characters",
        "platform.users=[{\"id\":\"owner\",\"token\":\"synthetic-page-only-owner-token-32-characters\"}]" })
@AutoConfigureMockMvc
class SyntheticCostPageDisabledTest {
    @Autowired MockMvc mvc;
    @Test
    void realAccountModeCannotReadSimulatedPage() throws Exception {
        mvc.perform(get("/api/projects/synthetic-project/synthetic-costs/reservations")
                .header("Authorization", "Bearer synthetic-page-only-owner-token-32-characters"))
                .andExpect(status().isNotFound());
    }
}
