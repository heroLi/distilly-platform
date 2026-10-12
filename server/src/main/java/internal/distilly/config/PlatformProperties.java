package internal.distilly.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Platform runtime settings. Never expose configured tokens through API responses or
 * logs.
 */
@ConfigurationProperties(prefix = "platform")
public class PlatformProperties {

    private boolean demo;

    private int maxActiveTasksPerUser = 32;
    private int taskSubmissionsPerMinute = 60;
    private int totalTaskSubmissionsPerMinute = 120;

    public int getMaxActiveTasksPerUser() { return maxActiveTasksPerUser; }
    public void setMaxActiveTasksPerUser(int value) {
        if (value < 0 || value > 1000) throw new IllegalArgumentException("active task limit out of range");
        maxActiveTasksPerUser = value;
    }
    public int getTaskSubmissionsPerMinute() { return taskSubmissionsPerMinute; }
    public void setTaskSubmissionsPerMinute(int value) {
        if (value < 0 || value > 10000) throw new IllegalArgumentException("submission limit out of range");
        taskSubmissionsPerMinute = value;
    }
    public int getTotalTaskSubmissionsPerMinute() { return totalTaskSubmissionsPerMinute; }
    public void setTotalTaskSubmissionsPerMinute(int value) {
        if (value < 0 || value > 10000) throw new IllegalArgumentException("total submission limit out of range");
        totalTaskSubmissionsPerMinute = value;
    }


    private String users = "[]";

    private String workerToken = "";

    private String modelMode = "gateway";

    private long leaseSeconds = 300;

    private int maxAttempts = 3;

    private int contextBudgetUnits = 65536;

    private int replyOutputTokens = 2048;

    private long taskAgingSeconds = 30;

    public long getTaskAgingSeconds() { return taskAgingSeconds; }

    public void setTaskAgingSeconds(long value) {
        if (value < 1 || value > 3600) throw new IllegalArgumentException("task aging interval out of range");
        taskAgingSeconds = value;
    }


    public int getContextBudgetUnits() { return contextBudgetUnits; }

    public void setContextBudgetUnits(int value) {
        if (value < 16384 || value > 262144) throw new IllegalArgumentException("context budget out of range");
        contextBudgetUnits = value;
    }

    public int getReplyOutputTokens() { return replyOutputTokens; }

    public void setReplyOutputTokens(int value) {
        if (value < 256 || value > 8192) throw new IllegalArgumentException("reply budget out of range");
        replyOutputTokens = value;
    }

    public boolean isDemo() {
        return demo;
    }

    public void setDemo(boolean demo) {
        this.demo = demo;
    }

    public String getUsers() {
        return users;
    }

    public void setUsers(String users) {
        this.users = users;
    }

    public String getWorkerToken() {
        return workerToken;
    }

    public void setWorkerToken(String workerToken) {
        this.workerToken = workerToken;
    }

    public String getModelMode() {
        return modelMode;
    }

    public void setModelMode(String modelMode) {
        this.modelMode = modelMode;
    }

    public long getLeaseSeconds() {
        return leaseSeconds;
    }

    public void setLeaseSeconds(long leaseSeconds) {
        if (leaseSeconds < 1)
            throw new IllegalArgumentException("leaseSeconds must be positive");
        this.leaseSeconds = leaseSeconds;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        if (maxAttempts < 1)
            throw new IllegalArgumentException("maxAttempts must be positive");
        this.maxAttempts = maxAttempts;
    }

}
