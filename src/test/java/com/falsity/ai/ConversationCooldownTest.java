package com.falsity.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.falsity.config.FalsityConfig;
import com.falsity.horror.HorrorManager;
import com.falsity.memory.MemoryManager;
import com.falsity.memory.MemoryStorage;
import com.falsity.personality.PersonalityManager;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Conversation cooldowns: per-player, expiring, and isolated. */
class ConversationCooldownTest {
	@TempDir
	Path tempDir;

	@Test
	void cooldownBlocksImmediateSecondRequest() {
		AtomicLong clock = new AtomicLong(1000L);
		ConversationManager manager = manager(clock);
		UUID player = UUID.randomUUID();

		assertTrue(manager.tryStartRequest(player, clock.get()), "First request must be accepted");
		assertFalse(manager.tryStartRequest(player, clock.get()), "Immediate second request must be on cooldown");
		assertTrue(manager.cooldownRemainingSeconds(player) > 0L);
		assertFalse(manager.canTalk(player));
	}

	@Test
	void cooldownExpiresAfterConfiguredSeconds() {
		FalsityConfig config = new FalsityConfig();
		config.conversation.cooldownSeconds = 10;
		AtomicLong clock = new AtomicLong(0L);
		ConversationManager manager = managerWith(config, clock);
		UUID player = UUID.randomUUID();

		assertTrue(manager.tryStartRequest(player, 0L));
		clock.set(5_000L);
		assertFalse(manager.tryStartRequest(player, clock.get()), "Still cooling down at 5s");
		clock.set(10_000L);
		assertTrue(manager.tryStartRequest(player, clock.get()), "Cooldown must expire at 10s");
		assertEquals(0L, manager.cooldownRemainingSeconds(player));
	}

	@Test
	void cooldownsArePerPlayer() {
		AtomicLong clock = new AtomicLong(2000L);
		ConversationManager manager = manager(clock);
		UUID first = UUID.randomUUID();
		UUID second = UUID.randomUUID();

		assertTrue(manager.tryStartRequest(first, clock.get()));
		assertFalse(manager.tryStartRequest(first, clock.get()));
		assertTrue(manager.tryStartRequest(second, clock.get()), "One player's cooldown must never block another's");
	}

	@Test
	void zeroCooldownAllowsImmediateRequests() {
		FalsityConfig config = new FalsityConfig();
		config.conversation.cooldownSeconds = 0;
		AtomicLong clock = new AtomicLong(3000L);
		ConversationManager manager = managerWith(config, clock);
		UUID player = UUID.randomUUID();

		assertTrue(manager.tryStartRequest(player, clock.get()));
		assertTrue(manager.tryStartRequest(player, clock.get()));
	}

	private ConversationManager manager(AtomicLong clock) {
		return managerWith(new FalsityConfig(), clock);
	}

	private ConversationManager managerWith(FalsityConfig config, AtomicLong clock) {
		MemoryManager memories = new MemoryManager(new MemoryStorage(this.tempDir));
		return new ConversationManager(
			config,
			new GroqClient(),
			new ApiKeyProvider(() -> ""),
			memories,
			new PersonalityManager(memories, config),
			new HorrorManager(memories, config),
			new OfflineFallback(),
			this.tempDir,
			clock::get
		);
	}
}
