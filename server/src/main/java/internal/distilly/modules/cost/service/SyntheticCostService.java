package internal.distilly.modules.cost.service;

import internal.distilly.config.PlatformProperties;
import internal.distilly.modules.cost.repository.SyntheticCostRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static internal.distilly.common.support.Identifiers.id;
import static internal.distilly.common.support.Identifiers.now;
import static internal.distilly.common.validation.InputChecks.error;

/** Simulated receipts only; deliberately has no Worker/provider/task integration. */
@Service
@org.springframework.context.annotation.DependsOn("personaMigration")
public class SyntheticCostService {
    private static final int LIMIT_FEN = 1000;
    private final PlatformProperties properties;
    private final SyntheticCostRepository repository;
    public SyntheticCostService(PlatformProperties properties, SyntheticCostRepository repository) {
        this.properties = properties;
        this.repository = repository;
    }
    private void authorize(String actor, String project) {
        if (!properties.isDemo()) throw error(404, "not_found");
        repository.lockOwnerAndProject(actor, project);
    }
    private static void fields(Map<String, Object> body, String... keys) {
        if (body == null || !body.keySet().equals(Set.of(keys))) throw error(422, "invalid_synthetic_cost_request");
    }
    private static String key(Object raw) {
        if (!(raw instanceof String value) || !value.matches("[A-Za-z0-9_-]{1,80}"))
            throw error(422, "invalid_idempotency_key");
        return value;
    }
    private static int money(Object raw, int minimum, int maximum) {
        // No floating point, decimal, string coercion or caller-controlled currency.
        if (!(raw instanceof Integer value) || value < minimum || value > maximum)
            throw error(422, "invalid_fen");
        return value;
    }
    private static Map<String, Object> view(Map<String, Object> row) {
        var result = new LinkedHashMap<>(row);
        result.put("contract", "SYNTHETIC_CNY_LEDGER_V1");
        result.put("synthetic", true);
        result.put("real_egress_authorized", false);
        return result;
    }
    @Transactional
    public Map<String, Object> summary(String actor, String project) {
        authorize(actor, project);
        var totals = new LinkedHashMap<>(repository.totals(project));
        long available = LIMIT_FEN - ((Number) totals.get("held_fen")).longValue() - ((Number) totals.get("spent_fen")).longValue();
        if (available < 0) throw error(409, "synthetic_ledger_inconsistent");
        totals.put("contract", "SYNTHETIC_CNY_LEDGER_V1");
        totals.put("synthetic", true);
        totals.put("currency", "CNY");
        totals.put("unit", "fen");
        totals.put("limit_fen", LIMIT_FEN);
        totals.put("available_fen", available);
        totals.put("real_egress_authorized", false);
        return totals;
    }
    @Transactional
    public Map<String, Object> page(String actor, String project, int page, int size, String state) {
        authorize(actor, project);
        if (page < 1 || page > 1000000 || size < 1 || size > 50
                || !Set.of("UNRESOLVED", "ALL", "RESERVED", "UNCERTAIN", "SETTLED", "RELEASED").contains(state))
            throw error(422, "invalid_ledger_page");
        // The project lock remains held across summary, count and items, so the
        // displayed totals and rows describe the same ledger state.
        return view(Map.of("page", page, "size", size, "total", repository.count(project, state),
                "items", repository.page(project, state, page, size), "summary", summary(actor, project)));
    }
    @Transactional
    public Map<String, Object> detail(String actor, String project, String reservation) {
        authorize(actor, project);
        var result = new LinkedHashMap<>(repository.reservation(project, reservation));
        result.put("events", repository.events(reservation));
        return view(result);
    }
    @Transactional
    public Map<String, Object> reserve(String actor, String project, Map<String, Object> body) {
        authorize(actor, project);
        fields(body, "request_key", "reserved_fen");
        String request = key(body.get("request_key"));
        int amount = money(body.get("reserved_fen"), 1, LIMIT_FEN);
        var existing = repository.byRequest(project, request);
        if (!existing.isEmpty()) {
            var row = existing.get(0);
            if (((Number) row.get("reserved_fen")).intValue() != amount) throw error(409, "request_key_conflict");
            return view(row);
        }
        var totals = repository.totals(project);
        long used = ((Number) totals.get("held_fen")).longValue() + ((Number) totals.get("spent_fen")).longValue();
        if (used + amount > LIMIT_FEN) throw error(409, "synthetic_budget_exceeded");
        String reservation = id();
        long time = now();
        repository.reserve(reservation, project, request, amount, time);
        repository.event(id(), project, reservation, null, "RESERVED", "SIMULATED_RESERVATION", null, time);
        return view(repository.reservation(project, reservation));
    }
    @Transactional
    public Map<String, Object> outcome(String actor, String project, String reservation, Map<String, Object> body) {
        authorize(actor, project);
        String outcome = body == null ? "" : Objects.toString(body.get("outcome"), "");
        boolean settled = outcome.equals("SETTLED");
        if (!Set.of("SETTLED", "RELEASED", "UNCERTAIN").contains(outcome)) throw error(422, "invalid_outcome");
        if (settled) fields(body, "callback_key", "outcome", "evidence", "actual_fen");
        else fields(body, "callback_key", "outcome", "evidence");
        String callback = key(body.get("callback_key"));
        String evidence = switch (outcome) {
            case "SETTLED" -> "SIMULATED_BILL";
            case "RELEASED" -> "SIMULATED_ZERO_BILL";
            default -> "SIMULATED_UNKNOWN";
        };
        if (!evidence.equals(body.get("evidence"))) throw error(422, "synthetic_evidence_required");
        Integer actual = null;
        if (settled) actual = money(body.get("actual_fen"), 0, Integer.MAX_VALUE);
        else if (outcome.equals("RELEASED")) actual = 0;
        var row = repository.reservation(project, reservation);
        var callbacks = repository.callback(project, callback);
        if (!callbacks.isEmpty()) {
            var prior = callbacks.get(0);
            if (!reservation.equals(prior.get("reservation_id")) || !outcome.equals(prior.get("outcome"))
                    || !evidence.equals(prior.get("evidence")) || !Objects.equals(actual, prior.get("actual_fen")))
                throw error(409, "callback_key_conflict");
            return view(row);
        }
        String status = row.get("status").toString();
        if (!Set.of("RESERVED", "UNCERTAIN").contains(status) || (status.equals("UNCERTAIN") && outcome.equals("UNCERTAIN")))
            throw error(409, "reservation_already_resolved");
        if (settled && actual > ((Number) row.get("reserved_fen")).intValue())
            throw error(409, "settlement_exceeds_reservation");
        long time = now();
        repository.transition(reservation, outcome, actual, time);
        repository.event(id(), project, reservation, callback, outcome, evidence, actual, time);
        return view(repository.reservation(project, reservation));
    }
}
