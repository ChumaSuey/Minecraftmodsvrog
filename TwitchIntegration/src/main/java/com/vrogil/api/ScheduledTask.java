package com.vrogil.api;

/**
 * Handle for an action queued with {@link ChatContext#afterTicks},
 * {@link ChatContext#afterSeconds} or the delayed command helpers.
 *
 * <p>Cancelling only prevents an action that has not started yet; it cannot stop
 * one that is already running.
 */
public interface ScheduledTask {
	void cancel();

	boolean isCancelled();
}
