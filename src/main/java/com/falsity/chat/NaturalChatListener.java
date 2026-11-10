package com.falsity.chat;

import com.falsity.FalsityMod;
import com.falsity.actions.ActionType;
import com.falsity.actions.ConfirmationManager;
import com.falsity.actions.IntentRecognizer;
import com.falsity.ai.ConversationManager;
import com.falsity.building.BuildManager;
import com.falsity.entity.FalsityEntity;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.server.level.ServerPlayer;

/**
 * Lets players talk to Falsity in ordinary chat.
 *
 * <p>A message is for Falsity when it uses its name ("Falsity, follow me") or
 * continues a conversation that is still warm. Everything else is left alone.
 * The message is never cancelled or altered; other players still see it.
 *
 * <p>Each chat message is handled once, and a message that was meant for
 * Falsity never disappears without a word: if it is too long, if Falsity is
 * too far away to hear, or if the player has to wait, the player is told.
 */
public final class NaturalChatListener {
	/** How often a player is reminded that Falsity is out of earshot. */
	private static final long OUT_OF_RANGE_NOTICE_MILLIS = 30_000L;

	/** The chat message last handled for each player, to recognise a repeat delivery of the same one. */
	private static final Map<UUID, Handled> LAST_HANDLED = new HashMap<>();
	private static final Map<UUID, Long> LAST_RANGE_NOTICE = new HashMap<>();

	private record Handled(Instant sentAt, String text) {
	}

	private NaturalChatListener() {
	}

	public static void register() {
		ServerMessageEvents.CHAT_MESSAGE.register(NaturalChatListener::onChatMessage);
	}

	private static void onChatMessage(PlayerChatMessage message, ServerPlayer sender, ChatType.Bound boundChatType) {
		if (!FalsityMod.config().chat.enabled || FalsityMod.conversations() == null) {
			return;
		}
		if (sender.isRemoved() || !sender.isAlive()) {
			return;
		}

		// The text exactly as the player typed it, before any chat decoration.
		String raw = ChatDetector.stripBroadcastPrefix(message.signedContent());
		if (raw.isEmpty() || raw.startsWith("/")) {
			return;
		}

		// One chat message, one reply: the same message delivered twice is ignored.
		synchronized (LAST_HANDLED) {
			Handled current = new Handled(message.timeStamp(), raw);
			if (current.equals(LAST_HANDLED.get(sender.getUUID()))) {
				FalsityMod.debug("Ignored a repeated delivery of the same chat message from {}.", sender.getName().getString());
				return;
			}
			LAST_HANDLED.put(sender.getUUID(), current);
		}

		Optional<FalsityEntity> companion = CompanionFinder.findBonded(
			sender.level(), sender, FalsityMod.config().chat.maxConversationDistance);
		if (companion.isEmpty()) {
			if (ChatDetector.mentions(raw, "Falsity")) {
				noticeOutOfRange(sender);
			}
			return;
		}
		FalsityEntity falsity = companion.get();
		String falsityName = falsity.getName().getString();

		// 1. Answer a pending confirmation before anything else.
		ConfirmationManager confirmations = FalsityMod.confirmations();
		if (confirmations != null && confirmations.hasPending(sender.getUUID())) {
			if (ChatDetector.isAffirmative(raw)) {
				confirmations.answer(sender.getUUID()).ifPresent(result ->
					ConversationManager.say(sender, falsity, result.message()));
				return;
			}
			if (ChatDetector.isNegative(raw)) {
				confirmations.clear(sender.getUUID());
				ConversationManager.say(sender, falsity, "All right, I won't.");
				return;
			}
		}

		// 2. Cancel an active build on request, even without being named.
		BuildManager builds = FalsityMod.builds();
		if (builds != null && builds.isActive(sender.getUUID())) {
			boolean stopRequested = ChatDetector.isNegative(raw)
				|| IntentRecognizer.recognize(raw)
					.filter(request -> request.type() == ActionType.STOP_BUILD)
					.isPresent();
			if (stopRequested) {
				ConversationManager.say(sender, falsity, builds.cancel(sender.getUUID()));
				return;
			}
		}

		// 3. Only answer when addressed by name or in a warm conversation.
		boolean addressed = ChatDetector.mentions(raw, falsityName);
		boolean contextual = FalsityMod.contexts() != null
			&& FalsityMod.contexts().isActive(sender.getUUID(), falsity.getUUID());
		if (!addressed && !contextual) {
			return;
		}

		if (raw.length() > FalsityMod.config().chat.maxMessageLength) {
			sender.sendSystemMessage(Component.translatable("message.falsity.too_long"));
			return;
		}

		String cleaned = ChatDetector.stripMention(raw, falsityName);
		if (FalsityMod.contexts() != null) {
			if (addressed) {
				FalsityMod.contexts().activate(sender.getUUID(), falsity.getUUID());
			} else {
				FalsityMod.contexts().touch(sender.getUUID());
			}
		}

		ConversationManager.Outcome outcome = FalsityMod.conversations().requestConversation(sender, falsity, cleaned);
		switch (outcome) {
			case TOO_LONG -> sender.sendSystemMessage(Component.translatable("message.falsity.too_long"));
			case ON_COOLDOWN -> sender.sendSystemMessage(Component.translatable(
				"message.falsity.cooldown", FalsityMod.conversations().cooldownRemainingSeconds(sender.getUUID())));
			case DISABLED -> {
				if (addressed) {
					sender.sendSystemMessage(Component.translatable("message.falsity.talking_disabled"));
				}
			}
			default -> {
				// ACCEPTED: answered, or on its way. QUEUED: waiting its turn; the reply follows.
			}
		}
	}

	/** Tells a player, at most every half minute, that their Falsity is too far away to hear. */
	private static void noticeOutOfRange(ServerPlayer sender) {
		long now = System.currentTimeMillis();
		synchronized (LAST_RANGE_NOTICE) {
			Long last = LAST_RANGE_NOTICE.get(sender.getUUID());
			if (last != null && now - last < OUT_OF_RANGE_NOTICE_MILLIS) {
				return;
			}
			LAST_RANGE_NOTICE.put(sender.getUUID(), now);
		}
		// Only players who already know a Falsity are told; everyone else is just chatting.
		if (FalsityMod.memories() != null) {
			var memory = FalsityMod.memories().getOrCreate(sender.getUUID(), sender.getName().getString());
			if (memory.conversationCount > 0 || memory.petCount > 0) {
				sender.sendSystemMessage(Component.translatable("message.falsity.no_companion"));
			}
		}
	}
}
