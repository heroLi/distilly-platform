package internal.distilly;

import internal.distilly.config.PlatformProperties;
import internal.distilly.modules.task.domain.TaskSubmissionWindow;
import java.util.ArrayList;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TaskSubmissionWindowTest {
    @Test
    void perUserWindowsAreIndependent() {
        var window = new TaskSubmissionWindow(() -> 0);
        window.reserve("alice", 2, 10); window.reserve("alice", 2, 10);
        assertFalse(assertThrows(TaskSubmissionWindow.LimitReached.class, () -> window.reserve("alice", 2, 10)).isTotal());
        assertNotNull(window.reserve("bob", 2, 10));
    }

    @Test
    void totalWindowCountsDifferentUsers() {
        var window = new TaskSubmissionWindow(() -> 0);
        window.reserve("alice", 10, 2); window.reserve("bob", 10, 2);
        assertTrue(assertThrows(TaskSubmissionWindow.LimitReached.class, () -> window.reserve("carol", 10, 2)).isTotal());
    }

    @Test
    void exactMinuteBoundaryExpiresAdmissionsWithoutSleeping() {
        var clock = new AtomicLong(); var window = new TaskSubmissionWindow(clock::get);
        window.reserve("alice", 1, 1); clock.set(59_999_999_999L);
        assertThrows(TaskSubmissionWindow.LimitReached.class, () -> window.reserve("alice", 1, 1));
        clock.incrementAndGet(); assertNotNull(window.reserve("alice", 1, 1));
    }

    @Test
    void refundRemovesOnlyItsTicketAndIsIdempotent() {
        var window = new TaskSubmissionWindow(() -> 0);
        var first = window.reserve("alice", 2, 2); window.reserve("alice", 2, 2);
        window.refund(first); window.refund(first); window.reserve("alice", 2, 2);
        assertThrows(TaskSubmissionWindow.LimitReached.class, () -> window.reserve("alice", 2, 2));
    }

    @Test
    void individualDisabledLimitsDoNotDisableOtherGuards() {
        var window = new TaskSubmissionWindow(() -> 0);
        assertNull(window.reserve("alice", 0, 0)); window.refund(null);
        window.reserve("alice", 0, 1);
        assertTrue(assertThrows(TaskSubmissionWindow.LimitReached.class, () -> window.reserve("bob", 0, 1)).isTotal());
        var userOnly = new TaskSubmissionWindow(() -> 0);
        userOnly.reserve("alice", 1, 0);
        assertFalse(assertThrows(TaskSubmissionWindow.LimitReached.class, () -> userOnly.reserve("alice", 1, 0)).isTotal());
    }

    @Test
    void newProcessDoesNotProvidePersistentQuota() {
        var first = new TaskSubmissionWindow(() -> 0); first.reserve("alice", 1, 1);
        assertThrows(TaskSubmissionWindow.LimitReached.class, () -> first.reserve("alice", 1, 1));
        assertNotNull(new TaskSubmissionWindow(() -> 0).reserve("alice", 1, 1));
    }

    @Test
    void concurrentGlobalReservationsCannotExceedWindow() throws Exception {
        var window = new TaskSubmissionWindow(() -> 0);
        var start = new CountDownLatch(1); var executor = Executors.newFixedThreadPool(8);
        try {
            var outcomes = new ArrayList<Future<Boolean>>();
            for (int i = 0; i < 8; i++) {
                String actor = "synthetic-" + i;
                outcomes.add(executor.submit(() -> {
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    try { window.reserve(actor, 0, 3); return true; }
                    catch (TaskSubmissionWindow.LimitReached limit) { return false; }
                }));
            }
            start.countDown(); int admitted = 0;
            for (var outcome : outcomes) if (outcome.get(5, TimeUnit.SECONDS)) admitted++;
            assertEquals(3, admitted);
        }
        finally { start.countDown(); executor.shutdownNow(); }
    }

    @Test
    void boundedConfigurationRetainsExplicitDevelopmentDisable() {
        var properties = new PlatformProperties();
        assertEquals(32, properties.getMaxActiveTasksPerUser());
        assertEquals(60, properties.getTaskSubmissionsPerMinute());
        assertEquals(120, properties.getTotalTaskSubmissionsPerMinute());
        assertThrows(IllegalArgumentException.class, () -> properties.setMaxActiveTasksPerUser(-1));
        assertThrows(IllegalArgumentException.class, () -> properties.setMaxActiveTasksPerUser(1001));
        assertThrows(IllegalArgumentException.class, () -> properties.setTaskSubmissionsPerMinute(10001));
        assertThrows(IllegalArgumentException.class, () -> properties.setTotalTaskSubmissionsPerMinute(-1));
        properties.setMaxActiveTasksPerUser(0); properties.setTaskSubmissionsPerMinute(0); properties.setTotalTaskSubmissionsPerMinute(0);
        assertEquals(0, properties.getMaxActiveTasksPerUser());
    }
}
