package internal.distilly.modules.cost.service;

import internal.distilly.config.PlatformProperties;
import internal.distilly.modules.cost.repository.TextExecutionBudgetRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static internal.distilly.common.support.Identifiers.id;
import static internal.distilly.common.support.Identifiers.now;
import static internal.distilly.common.validation.InputChecks.error;

/** Single-database, synthetic execution holds. No operation authorizes real text egress. */
@Service
@org.springframework.context.annotation.DependsOn("personaMigration")
public class TextExecutionBudgetService {
    private static final long RESERVE = 1_000_000_000L;
    private static final String PRICE = "deepseek-flash-public-20261021-peak-ceiling-v1";
    private final PlatformProperties properties;
    private final TextExecutionBudgetRepository repository;
    public TextExecutionBudgetService(PlatformProperties properties, TextExecutionBudgetRepository repository) {
        this.properties = properties; this.repository = repository;
    }
    private void demo() { if (!properties.isDemo()) throw error(404, "not_found"); }
    private static void fields(Map<String, Object> body, String... names) {
        if (body == null || !body.keySet().equals(Set.of(names))) throw error(422, "invalid_execution_budget_request");
    }
    private static String key(Object raw, String pattern) {
        if (!(raw instanceof String s) || !s.matches(pattern)) throw error(422, "invalid_execution_budget_value");
        return s;
    }
    private static String uuid(Object raw) {
        return key(raw, "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }
    private static Map<String, Object> tagged(Map<String, Object> raw) {
        var result = new LinkedHashMap<>(raw);
        result.put("contract", "SYNTHETIC_TEXT_EXECUTION_BUDGET_V1");
        result.put("scope", "INSTANCE_DATABASE_GLOBAL"); result.put("currency", "CNY"); result.put("unit", "nano_cny");
        result.put("synthetic", true); result.put("is_bill", false); result.put("real_egress_authorized", false);
        return result;
    }
    private static Map<String, Object> view(Map<String, Object> row, boolean projectOnly) {
        var safe = new LinkedHashMap<String, Object>();
        for (String key : List.of("id", "stage", "status", "model", "price_review", "reserved_nano_cny",
                "actual_nano_cny", "created_at", "updated_at")) safe.put(key, row.get(key));
        if (!projectOnly)
            for (String key : List.of("queue", "job_id", "project_id", "unit_digest", "request_key", "receipt_key"))
                safe.put(key, row.get(key));
        return tagged(safe);
    }
    private void lease(Map<String, Object> task, String token) {
        if (!"RUNNING".equals(task.get("status")) || !token.equals(task.get("lease_token"))
                || !(task.get("lease_until") instanceof Number until) || until.longValue() < now())
            throw error(409, "lease_lost");
    }
    private static void stage(Map<String, Object> task, String queue, String stage) {
        String kind = Objects.toString(task.get("kind"), "");
        Set<String> allowed = queue.equals("WORK") ? switch (kind) {
            case "DISTILL" -> Set.of("distill"); case "ANSWER" -> Set.of("knowledge_answer"); default -> Set.of();
        } : switch (kind) {
            case "PERSONA" -> Set.of("persona_analysis", "persona_build"); case "CHAT" -> Set.of("default"); default -> Set.of();
        };
        if (!allowed.contains(stage)) throw error(422, "invalid_execution_budget_stage");
    }
    @Transactional
    public Map<String, Object> reserve(Map<String, Object> body) {
        demo(); fields(body, "queue", "job_id", "lease_token", "stage", "unit_digest", "request_key", "model");
        String queue = key(body.get("queue"), "WORK|PERSONA"), job = uuid(body.get("job_id")), token = uuid(body.get("lease_token"));
        String stage = key(body.get("stage"), "[a-z_]{1,24}"), digest = key(body.get("unit_digest"), "[0-9a-f]{64}");
        String request = key(body.get("request_key"), "[A-Za-z0-9_-]{1,80}");
        if (!"deepseek-flash".equals(body.get("model"))) throw error(422, "invalid_execution_budget_model");
        long limit = repository.lockGlobal();
        var task = repository.task(queue, job); lease(task, token); stage(task, queue, stage);
        var existing = repository.byRequest(request);
        if (!existing.isEmpty()) {
            var row = existing.get(0);
            if (!queue.equals(row.get("queue")) || !job.equals(row.get("job_id")) || !token.equals(row.get("lease_token"))
                    || !stage.equals(row.get("stage")) || !digest.equals(row.get("unit_digest")))
                throw error(409, "execution_budget_request_conflict");
            return view(row, false);
        }
        if (repository.pending(queue, job) > 0) throw error(409, "execution_budget_pending");
        var totals = repository.totals(null);
        long used = ((Number) totals.get("held_nano_cny")).longValue() + ((Number) totals.get("spent_nano_cny")).longValue();
        if (used > limit - RESERVE) throw error(409, "execution_budget_exceeded");
        String reservation = id(); long time = now();
        repository.reserve(reservation, task.get("project_id").toString(), queue, job, token, stage, digest, request, PRICE, time);
        repository.event(id(), reservation, "RESERVED", "SYNTHETIC_RESERVATION", null, time);
        return view(repository.reservation(reservation), false);
    }
    @Transactional
    public Map<String, Object> dispatch(String reservation, Map<String, Object> body) {
        demo(); fields(body, "lease_token"); String token = uuid(body.get("lease_token"));
        repository.lockGlobal(); var row = repository.reservation(reservation);
        if (!token.equals(row.get("lease_token"))) throw error(409, "execution_budget_lease_mismatch");
        lease(repository.task(row.get("queue").toString(), row.get("job_id").toString()), token);
        if (!"RESERVED".equals(row.get("status"))) throw error(409, "dispatch_already_claimed");
        long time = now(); repository.transition(reservation, "DISPATCHED", null, null, time);
        repository.event(id(), reservation, "DISPATCHED", "SYNTHETIC_DISPATCH", null, time);
        var result = view(repository.reservation(reservation), false); result.put("dispatch_permitted", true); return result;
    }
    @Transactional
    public Map<String, Object> outcome(String reservation, Map<String, Object> body) {
        demo(); String outcome = body == null ? "" : Objects.toString(body.get("outcome"), "");
        boolean settled = outcome.equals("SETTLED");
        if (!Set.of("SETTLED", "UNCERTAIN", "RELEASED").contains(outcome)) throw error(422, "invalid_execution_budget_outcome");
        if (settled) fields(body, "lease_token", "outcome", "evidence", "receipt_key", "actual_nano_cny");
        else fields(body, "lease_token", "outcome", "evidence");
        String token = uuid(body.get("lease_token"));
        String evidence = settled ? "SYNTHETIC_USAGE_V1" : outcome.equals("RELEASED") ? "SYNTHETIC_NOT_DISPATCHED" : "SYNTHETIC_UNKNOWN";
        if (!evidence.equals(body.get("evidence"))) throw error(422, "synthetic_evidence_required");
        Long amount = null; String receipt = null;
        if (settled) {
            Object raw = body.get("actual_nano_cny");
            if (!(raw instanceof Integer || raw instanceof Long) || ((Number) raw).longValue() < 0)
                throw error(422, "invalid_nano_cny");
            amount = ((Number) raw).longValue(); receipt = key(body.get("receipt_key"), "[A-Za-z0-9_-]{1,160}");
        } else if (outcome.equals("RELEASED")) amount = 0L;
        repository.lockGlobal(); var row = repository.reservation(reservation);
        if (!token.equals(row.get("lease_token"))) throw error(409, "execution_budget_lease_mismatch");
        String state = row.get("status").toString();
        if (settled) {
            if (amount > ((Number) row.get("reserved_nano_cny")).longValue()) throw error(409, "settlement_exceeds_reservation");
            var prior = repository.byReceipt(receipt);
            if (!prior.isEmpty()) {
                if (!reservation.equals(prior.get(0).get("id")) || !"SETTLED".equals(state)
                        || amount.longValue() != ((Number) row.get("actual_nano_cny")).longValue())
                    throw error(409, "execution_budget_receipt_conflict");
                return view(row, false);
            }
            if (!Set.of("DISPATCHED", "UNCERTAIN").contains(state)) throw error(409, "execution_budget_state_conflict");
        } else if (outcome.equals("UNCERTAIN")) {
            if (Set.of("UNCERTAIN", "SETTLED", "RELEASED").contains(state)) return view(row, false);
        } else {
            if (state.equals("RELEASED")) return view(row, false);
            if (!state.equals("RESERVED")) throw error(409, "execution_budget_state_conflict");
        }
        long time = now(); repository.transition(reservation, outcome, amount, receipt, time);
        repository.event(id(), reservation, outcome, evidence, amount, time);
        return view(repository.reservation(reservation), false);
    }
    @Transactional
    public Map<String, Object> summary(String actor, String project) {
        demo(); long limit = repository.lockGlobal();
        if (project != null) repository.owner(actor, project);
        var totals = new LinkedHashMap<>(repository.totals(project)); totals.put("limit_nano_cny", limit);
        if (project == null) {
            long available = limit - ((Number) totals.get("held_nano_cny")).longValue() - ((Number) totals.get("spent_nano_cny")).longValue();
            if (available < 0) throw error(409, "execution_budget_inconsistent");
            totals.put("available_nano_cny", available);
        }
        return tagged(Map.of("summary", tagged(totals), "items", repository.recent(project).stream().map(row -> {
            var item = view(row, project != null);
            if (project == null) item.put("events", repository.events(row.get("id").toString()));
            return item;
        }).toList()));
    }
}
