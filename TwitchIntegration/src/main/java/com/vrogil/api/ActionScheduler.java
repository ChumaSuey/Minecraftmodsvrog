package com.vrogil.api;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tick-driven queue for delayed extension actions.
 *
 * <p>Driven by the server tick loop, so delays pause with the game and never run
 * after the world has stopped. Work happens on the server thread, exactly like
 * the callbacks that scheduled it.
 */
final class ActionScheduler {
	private static final Logger LOG = LoggerFactory.getLogger("twitchintegration");
	static final ActionScheduler INSTANCE = new ActionScheduler();

	private static final ScheduledTask REJECTED = new ScheduledTask() {
		@Override
		public void cancel() {
		}

		@Override
		public boolean isCancelled() {
			return true;
		}
	};

	private static final Comparator<Entry> ORDER = Comparator
			.<Entry>comparingLong(entry -> entry.dueTick)
			.thenComparingLong(entry -> entry.sequence);

	private final PriorityQueue<Entry> queue = new PriorityQueue<>(ORDER);
	private final Map<String, Integer> pendingByOwner = new ConcurrentHashMap<>();

	private long sequence;
	private long tick;

	private ActionScheduler() {
	}

	ScheduledTask schedule(
			String owner,
			int delayTicks,
			int maxDelayTicks,
			int maxPending,
			int maxPendingPerOwner,
			Runnable action
	) {
		String key = normalizeOwner(owner);
		int limit = Math.max(0, maxDelayTicks);
		int delay = Math.max(0, Math.min(delayTicks, limit));

		synchronized (this) {
			if (queue.size() >= Math.max(1, maxPending)) {
				LOG.warn("[twitch] delayed action rejected: queue is full ({} pending)", queue.size());
				return REJECTED;
			}

			int forOwner = pendingByOwner.merge(key, 1, Integer::sum);
			if (forOwner > Math.max(1, maxPendingPerOwner)) {
				releaseOwner(key);
				LOG.debug("[twitch] delayed action rejected for '{}': too many pending", owner);
				return REJECTED;
			}

			Entry entry = new Entry(tick + delay, sequence++, key, action);
			queue.add(entry);
			return new Task(entry);
		}
	}

	/** Called once per server tick by the mod. */
	void tick() {
		List<Entry> due;

		synchronized (this) {
			tick++;
			due = new ArrayList<>();
			while (!queue.isEmpty() && queue.peek().dueTick <= tick) {
				due.add(queue.poll());
			}
			for (Entry entry : due) {
				releaseOwner(entry.owner);
			}
		}

		for (Entry entry : due) {
			if (entry.cancelled) {
				continue;
			}
			try {
				entry.action.run();
			} catch (RuntimeException e) {
				LOG.warn("[twitch] a delayed action threw", e);
			}
		}
	}

	synchronized void clear() {
		queue.clear();
		pendingByOwner.clear();
	}

	synchronized int pendingCount() {
		return queue.size();
	}

	private synchronized boolean cancel(Entry entry) {
		if (entry.cancelled) {
			return false;
		}
		entry.cancelled = true;
		if (queue.remove(entry)) {
			releaseOwner(entry.owner);
			return true;
		}
		return false;
	}

	private void releaseOwner(String owner) {
		pendingByOwner.computeIfPresent(owner, (key, value) -> value <= 1 ? null : value - 1);
	}

	private static String normalizeOwner(String owner) {
		return owner == null || owner.isBlank() ? "?" : owner.toLowerCase(Locale.ROOT);
	}

	private final class Task implements ScheduledTask {
		private final Entry entry;

		private Task(Entry entry) {
			this.entry = entry;
		}

		@Override
		public void cancel() {
			ActionScheduler.this.cancel(entry);
		}

		@Override
		public boolean isCancelled() {
			return entry.cancelled;
		}
	}

	private static final class Entry {
		private final long dueTick;
		private final long sequence;
		private final String owner;
		private final Runnable action;
		private volatile boolean cancelled;

		private Entry(long dueTick, long sequence, String owner, Runnable action) {
			this.dueTick = dueTick;
			this.sequence = sequence;
			this.owner = owner;
			this.action = action;
		}
	}
}
