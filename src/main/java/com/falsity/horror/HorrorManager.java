package com.falsity.horror;

import com.falsity.config.FalsityConfig;
import com.falsity.memory.MemoryManager;
import com.falsity.memory.PlayerMemory;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tracks horror progression per player.
 *
 * <p>State lives in each player's memory, so progression persists across
 * sessions and is isolated per player: one player's stage never affects
 * another's. This manager only advances tension and stage; it never moves
 * entities, deals damage or plays sounds. Behaviour changes (staring, odd
 * lines) are decided by callers using {@link HorrorProgression}.
 */
public final class HorrorManager {
	private static final Logger LOGGER = LoggerFactory.getLogger(HorrorManager.class);

	private final MemoryManager memories;
	private final FalsityConfig config;

	public HorrorManager(MemoryManager memories, FalsityConfig config) {
		this.memories = memories;
		this.config = config;
	}

	/** The player's current stage. New players start at Innocence. */
	public HorrorStage stageFor(UUID playerUuid, String playerName) {
		PlayerMemory memory = this.memories.getOrCreate(playerUuid, playerName);
		if (memory.horror == null) {
			memory.horror = new PlayerMemory.HorrorState();
		}
		return HorrorStage.fromOrdinal(memory.horror.stage);
	}

	/** The player's accumulated tension. */
	public double tensionFor(UUID playerUuid, String playerName) {
		PlayerMemory memory = this.memories.getOrCreate(playerUuid, playerName);
		if (memory.horror == null) {
			memory.horror = new PlayerMemory.HorrorState();
		}
		return memory.horror.tension;
	}

	/**
	 * Records one interaction towards progression. Returns the stage after
	 * the interaction, so callers can react to a change.
	 */
	public HorrorStage recordInteraction(UUID playerUuid, String playerName) {
		if (!this.config.horror.enabled || this.config.horror.progressionSpeed <= 0.0) {
			return stageFor(playerUuid, playerName);
		}

		PlayerMemory memory = this.memories.getOrCreate(playerUuid, playerName);
		if (memory.horror == null) {
			memory.horror = new PlayerMemory.HorrorState();
		}

		memory.horror.interactionCount++;
		memory.horror.tension += HorrorProgression.tensionGainForInteraction(this.config.horror.progressionSpeed);

		HorrorStage next = HorrorProgression.stageForTension(memory.horror.tension, this.config.horror.maxStage);
		if (next.ordinal() != memory.horror.stage) {
			memory.horror.stage = next.ordinal();
			memory.horror.lastStageChangeEpochMillis = System.currentTimeMillis();
			memory.addRecent(HorrorEvent.stageChanged(System.currentTimeMillis(), next).description(), this.config.memory.maxRecentInteractions);
			LOGGER.info("Falsity's horror stage for {} is now {} (tension {}).", playerName, next.displayName(), Math.round(memory.horror.tension));
		}

		this.memories.save(playerUuid);
		return next;
	}

	/**
	 * Forces a stage (admin/testing use only). The value is still clamped to
	 * the configured maximum, so this cannot skip past the milestone cap.
	 */
	public HorrorStage setStage(UUID playerUuid, String playerName, int stageOrdinal) {
		PlayerMemory memory = this.memories.getOrCreate(playerUuid, playerName);
		if (memory.horror == null) {
			memory.horror = new PlayerMemory.HorrorState();
		}
		int capped = Math.max(0, Math.min(this.config.horror.maxStage, stageOrdinal));
		memory.horror.stage = capped;
		memory.horror.lastStageChangeEpochMillis = System.currentTimeMillis();
		this.memories.save(playerUuid);
		return HorrorStage.fromOrdinal(capped);
	}

	/** Whether Falsity may stare at this player right now. */
	public boolean shouldStare(UUID playerUuid, String playerName) {
		if (!this.config.horror.allowStaring) {
			return false;
		}
		return HorrorProgression.allowsStaring(stageFor(playerUuid, playerName));
	}

	/** Whether subtly unsettling lines are allowed for this player. */
	public boolean shouldUnsettle(UUID playerUuid, String playerName) {
		if (!this.config.horror.allowUnsettlingLines) {
			return false;
		}
		return HorrorProgression.allowsUnsettlingLines(stageFor(playerUuid, playerName));
	}
}
