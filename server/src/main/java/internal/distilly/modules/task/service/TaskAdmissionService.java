package internal.distilly.modules.task.service;

import internal.distilly.common.persistence.ResourceRepository;
import internal.distilly.config.PlatformProperties;
import internal.distilly.modules.task.domain.TaskSubmissionWindow;
import internal.distilly.modules.task.repository.TaskAdmissionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static internal.distilly.common.validation.InputChecks.error;

@Service
@org.springframework.context.annotation.DependsOn("personaMigration")
public class TaskAdmissionService {
    private final ResourceRepository resources;
    private final TaskAdmissionRepository repository;
    private final PlatformProperties properties;
    private final TaskSubmissionWindow window = new TaskSubmissionWindow();

    public TaskAdmissionService(ResourceRepository resources, TaskAdmissionRepository repository, PlatformProperties properties) {
        this.resources = resources;
        this.repository = repository;
        this.properties = properties;
    }

    /** The caller holds its resource lock; the creator row serializes admission across both queues. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void admit(String creator, boolean existingSlot) {
        int active = properties.getMaxActiveTasksPerUser();
        int perUser = properties.getTaskSubmissionsPerMinute();
        int total = properties.getTotalTaskSubmissionsPerMinute();
        if (active == 0 && perUser == 0 && total == 0) return;
        resources.one("app_user", creator);
        resources.lock("app_user", creator);
        if (!existingSlot && active > 0 && repository.activeTasks(creator) >= active)
            throw error(429, "user_active_task_limit");
        TaskSubmissionWindow.Ticket ticket;
        try { ticket = window.reserve(creator, perUser, total); }
        catch (TaskSubmissionWindow.LimitReached limit) {
            throw error(429, limit.isTotal() ? "platform_task_submission_rate_limit" : "user_task_submission_rate_limit");
        }
        if (ticket != null) TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                // Unknown completion conservatively retains the admission; only a known rollback refunds it.
                if (status == STATUS_ROLLED_BACK) window.refund(ticket);
            }
        });
    }
}
