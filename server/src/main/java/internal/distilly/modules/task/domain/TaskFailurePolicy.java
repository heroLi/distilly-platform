package internal.distilly.modules.task.domain;

import java.util.Set;

/** Automatic repetition is reserved for transient model availability failures. */
public final class TaskFailurePolicy {
    private static final Set<String> COMMON = Set.of("MODEL_UNAVAILABLE", "MODEL_CONFIGURATION_ERROR", "MODEL_OUTPUT_LIMIT", "MODEL_BUDGET_NOT_VERIFIED", "MODEL_EXECUTION_BUDGET_UNVERIFIED", "INVALID_MODEL_OUTPUT", "INTERNAL_ERROR");
    private TaskFailurePolicy() { }

    public static String normalize(String code, Set<String> additional) {
        return code != null && (COMMON.contains(code) || additional.contains(code)) ? code : "INTERNAL_ERROR";
    }

    public static int automaticAttemptLimit(String code, int configuredMaximum) {
        return "MODEL_UNAVAILABLE".equals(code) ? configuredMaximum : 0;
    }
}
