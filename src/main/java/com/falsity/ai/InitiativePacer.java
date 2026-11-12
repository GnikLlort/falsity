package com.falsity.ai;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Decides when Falsity may say something without having been spoken to.
 *
 * <p>A companion that never breaks a silence is only an answering machine; one
 * that talks all the time is a nuisance. The rules here keep it rare and
 * polite:
 * <ul>
 *   <li>never in the first moments after it first sees the player;</li>
 *   <li>never while a conversation is still going, and not soon after one;</li>
 *   <li>at most once per interval, with some slack so it is not clockwork;</li>
 *   <li>never more than {@link #MAX_UNANSWERED} times running without the
 *       player doing anything in return. A player who has walked away from the
 *       keyboard is left in peace, and no requests are spent on them.</li>
 * </ul>
 *
 * <p>The class only keeps time. Whether there is anything to say with (a
 * working key, room under the rate limit) is the caller's business.
 */
public final class InitiativePacer {
	/** Nothing is volunteered while words have been exchanged within this long. */
	public static final long QUIET_MILLIS = 90_000L;

	/** How many remarks in a row may go unanswered before Falsity stops volunteering. */
	public static final int MAX_UNANSWERED = 2;

	private final Random random;
	private final Map<UUID, State> states = new HashMap<>();

	private static final class State {
		private long nextAtMillis;
		private long lastSpokeMillis;
		private int unanswered;
	}

	public InitiativePacer() {
		this(new Random());
	}

	/** Visible for tests: injects the source of the slack between remarks. */
	public InitiativePacer(Random random) {
		this.random = random;
	}

	/**
	 * Whether Falsity may speak first to this player now. When the answer is
	 * yes the next moment is scheduled at once, so whatever becomes of this
	 * remark there cannot be another before a full interval has passed.
	 *
	 * @param player             the player
	 * @param nowMillis          the current time
	 * @param intervalMillis     the least time between two remarks
	 * @param lastExchangeMillis when words last passed between the two, either way, or 0 for never
	 * @param lastSeenMillis     when the player last did anything with Falsity (talked, petted), or 0 for never
	 */
	public synchronized boolean claim(
		UUID player, long nowMillis, long intervalMillis, long lastExchangeMillis, long lastSeenMillis
	) {
		if (player == null || intervalMillis <= 0L) {
			return false;
		}
		State state = this.states.get(player);
		if (state == null) {
			// First sight of this player: wait a good while before the first remark.
			state = new State();
			state.nextAtMillis = nowMillis + intervalMillis / 2L + slack(intervalMillis);
			this.states.put(player, state);
			return false;
		}
		if (lastSeenMillis > state.lastSpokeMillis) {
			// The player has talked or petted since the last remark: it was not ignored.
			state.unanswered = 0;
		}
		if (nowMillis < state.nextAtMillis) {
			return false;
		}
		if (lastExchangeMillis > 0L && nowMillis - lastExchangeMillis < Math.max(QUIET_MILLIS, intervalMillis / 2L)) {
			return false;
		}
		if (state.unanswered >= MAX_UNANSWERED) {
			return false;
		}
		state.unanswered++;
		state.lastSpokeMillis = nowMillis;
		state.nextAtMillis = nowMillis + intervalMillis + slack(intervalMillis);
		return true;
	}

	/** How many remarks to this player have gone unanswered in a row. Visible for tests. */
	public synchronized int unanswered(UUID player) {
		State state = this.states.get(player);
		return state == null ? 0 : state.unanswered;
	}

	/** Forgets a player, as if they had never been seen. */
	public synchronized void forget(UUID player) {
		this.states.remove(player);
	}

	/** Up to half an interval of extra waiting, so remarks do not arrive on the dot. */
	private long slack(long intervalMillis) {
		long half = Math.max(1L, intervalMillis / 2L);
		return (long) (this.random.nextDouble() * half);
	}
}
