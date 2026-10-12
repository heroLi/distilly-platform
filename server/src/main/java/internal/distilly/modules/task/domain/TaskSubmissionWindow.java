package internal.distilly.modules.task.domain;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/** Process-local admission counts, never a persistent usage or monetary ledger. */
public final class TaskSubmissionWindow {
    private static final long MINUTE = 60_000_000_000L;
    public record Ticket(String actor, long createdAt, long sequence) { }
    public static final class LimitReached extends RuntimeException {
        private final boolean total;
        private LimitReached(boolean total) { super("task submission window full"); this.total = total; }
        public boolean isTotal() { return total; }
    }

    private final LongSupplier clock;
    private final Map<String, ArrayDeque<Ticket>> users = new HashMap<>();
    private final ArrayDeque<Ticket> total = new ArrayDeque<>();
    private long sequence;

    public TaskSubmissionWindow() { this(System::nanoTime); }
    public TaskSubmissionWindow(LongSupplier clock) { this.clock = clock; }

    public synchronized Ticket reserve(String actor, int userLimit, int totalLimit) {
        if (userLimit == 0 && totalLimit == 0) return null;
        long now = clock.getAsLong();
        prune(total, now);
        var user = users.get(actor);
        if (user != null) prune(user, now);
        if (userLimit > 0 && user != null && user.size() >= userLimit) throw new LimitReached(false);
        if (totalLimit > 0 && total.size() >= totalLimit) throw new LimitReached(true);
        var ticket = new Ticket(actor, now, ++sequence);
        if (userLimit > 0) users.computeIfAbsent(actor, unused -> new ArrayDeque<>()).addLast(ticket);
        if (totalLimit > 0) total.addLast(ticket);
        return ticket;
    }

    public synchronized void refund(Ticket ticket) {
        if (ticket == null) return;
        total.remove(ticket);
        var user = users.get(ticket.actor());
        if (user != null) {
            user.remove(ticket);
            if (user.isEmpty()) users.remove(ticket.actor());
        }
    }

    private static void prune(ArrayDeque<Ticket> tickets, long now) {
        while (!tickets.isEmpty() && now - tickets.peekFirst().createdAt() >= MINUTE) tickets.removeFirst();
    }
}
