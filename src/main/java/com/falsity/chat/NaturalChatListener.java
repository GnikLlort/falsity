package com.falsity.chat;

import com.falsity.FalsityMod;
import com.falsity.actions.ConfirmationManager;
import com.falsity.actions.IntentRecognizer;
import com.falsity.ai.ConversationManager;
import com.falsity.building.BuildManager;
import com.falsity.entity.FalsityEntity;
import java.util.Optional;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.server.level.ServerPlayer;

/**
 * Listens to ordinary player chat and lets Falsity answer without a command.
 *
 * <p>Nothing here blocks, rewrites or forwards a message: the event fires after
 * the message is broadcast, so normal chat is completely unaffected. Falsity
 * only speaks when a message addresses it by name or continues a conversation
 * that is still warm, and only the Falsity bonded to that player within range
 * answers. Other players' chatter is never sent to Groq.
 *
 * <p>Confirmation replies and build cancellation are handled before the
 * mention check, so "yes" answers a pending question and "stop building"
 * cancels a build even when Falsity is not named.
 */
public final class NaturalChatListener {
	private NaturalChatListener() {
	}

	/** Registers the listener. Call once during mod initialisation. */
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

		String decorated = message.decoratedContent().getString();
		String raw = ChatDetector.stripBroadcastPrefix(decorated);
		if (raw.isEmpty() || raw.startsWith("/")) {
			return;
		}
		if (raw.length() > FalsityMod.config().chat.maxMessageLength) {
			return;
		}

		Optional<FalsityEntity> companion = CompanionFinder.findBonded(
			sender.level(), sender, FalsityMod.config().chat.maxConversationDistance);
		if (companion.isEmpty()) {
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
					.filter(request -> request.type() == com.falsity.actions.ActionType.STOP_BUILD)
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

		String cleaned = ChatDetector.stripMention(raw, falsityName);
		if (FalsityMod.contexts() != null) {
			if (addressed) {
				FalsityMod.contexts().activate(sender.getUUID(), falsity.getUUID());
			} else {
				FalsityMod.contexts().touch(sender.getUUID());
			}
		}

		FalsityMod.conversations().requestConversation(sender, falsity, cleaned);
	}
}
