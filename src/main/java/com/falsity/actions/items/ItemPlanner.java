package com.falsity.actions.items;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns a parsed request into a plan the server can check and carry out.
 *
 * <p>Everything is decided before anything is created. Each name is resolved to
 * a real item, every quantity is checked against the configured limits, and
 * only then is the plan marked ready. A request that breaks a limit is rejected
 * as a whole, so half of an oversized order is never handed over. A name that
 * cannot be identified does not sink the rest: the known items are delivered
 * and Falsity asks about the one it did not understand.
 *
 * <p>Pure: it never touches the game, so every rule here is unit tested.
 */
public final class ItemPlanner {
	/** The limits a plan must respect, taken from the configuration. */
	public record Limits(int maxPerRequest, int maxTypesPerRequest, int confirmAboveCount, boolean allowRestricted) {
	}

	/** One real item and how many of it to create. */
	public record Line(String id, int count) {
	}

	/**
	 * One thing the player asked for, resolved.
	 *
	 * @param label what to call it ("stick", "iron armour")
	 * @param count how many the player asked for (sets, for a kit)
	 * @param kit   whether this is a named set of several items
	 * @param lines the real items it expands to
	 */
	public record Group(String label, int count, boolean kit, List<Line> lines) {
		public Group {
			lines = List.copyOf(lines);
		}

		/** "64 sticks", "1 crafting table", "iron armour", "2 sets of iron tools". */
		public String describe() {
			if (this.kit) {
				return this.count == 1 ? this.label : this.count + " sets of " + this.label;
			}
			return this.count + " " + ItemResolver.pluralise(this.label, this.count);
		}
	}

	/**
	 * Something the player asked for that will not be delivered.
	 *
	 * @param name    the player's words
	 * @param entry   the parsed request, kept so a later answer can reuse the quantity
	 * @param reason  a specific explanation ("There is no stone helmet."), or empty
	 * @param refused true when the item exists but may not be handed out
	 */
	public record Unresolved(String name, ItemRequestParser.Entry entry, String reason, boolean refused) {
	}

	/** Whether a plan can be carried out. */
	public enum Status {
		/** At least one item is ready to deliver. */
		READY,
		/** Nothing asked for could be identified. */
		NOTHING_KNOWN,
		/** A limit was broken; nothing may be delivered. */
		REJECTED
	}

	/**
	 * The checked plan.
	 *
	 * @param status            whether it can be carried out
	 * @param groups            what will be delivered, in the order asked
	 * @param unresolved        what will not be, and why
	 * @param rejection         for REJECTED: what to tell the player
	 * @param totalItems        the number of individual items in the plan
	 * @param needsConfirmation whether the total is large enough to ask first
	 */
	public record Plan(
		Status status,
		List<Group> groups,
		List<Unresolved> unresolved,
		String rejection,
		int totalItems,
		boolean needsConfirmation
	) {
		public Plan {
			groups = List.copyOf(groups);
			unresolved = List.copyOf(unresolved);
			rejection = rejection == null ? "" : rejection;
		}

		/** Every real item in the plan with its total quantity, merged by id. */
		public List<Line> lines() {
			Map<String, Integer> merged = new LinkedHashMap<>();
			for (Group group : this.groups) {
				for (Line line : group.lines()) {
					merged.merge(line.id(), line.count(), Integer::sum);
				}
			}
			List<Line> result = new ArrayList<>();
			merged.forEach((id, count) -> result.add(new Line(id, count)));
			return result;
		}

		/** "1 crafting table and 64 sticks". */
		public String describe() {
			List<String> parts = new ArrayList<>();
			for (Group group : this.groups) {
				parts.add(group.describe());
			}
			return joinList(parts);
		}

		/** The first unidentified name Falsity should ask about, or null. */
		public Unresolved firstQuestion() {
			for (Unresolved entry : this.unresolved) {
				if (!entry.refused()) {
					return entry;
				}
			}
			return null;
		}
	}

	private ItemPlanner() {
	}

	/** Builds and checks a plan for the given entries. */
	public static Plan plan(List<ItemRequestParser.Entry> entries, ItemCatalog catalog, Limits limits) {
		List<Group> groups = new ArrayList<>();
		List<Unresolved> unresolved = new ArrayList<>();
		if (entries == null || entries.isEmpty() || catalog == null || limits == null) {
			return new Plan(Status.NOTHING_KNOWN, groups, unresolved, "", 0, false);
		}

		String carriedMaterial = "";
		for (ItemRequestParser.Entry entry : entries) {
			if (entry.count() > ItemRequestParser.MAX_COUNT) {
				return rejected(groups, unresolved, tooMany(limits));
			}

			ItemResolver.Resolution resolution = ItemResolver.resolve(entry.name(), catalog, carriedMaterial);
			String stated = ItemResolver.statedMaterial(entry.name());
			if (!stated.isEmpty()) {
				carriedMaterial = stated;
			}

			if (!resolution.known()) {
				unresolved.add(new Unresolved(entry.name(), entry, resolution.reason(), false));
				continue;
			}

			boolean kit = resolution.kind() == ItemResolver.Kind.KIT;
			List<Line> lines = new ArrayList<>();
			boolean refused = false;
			for (String id : resolution.ids()) {
				if (!limits.allowRestricted() && ItemResolver.isRestricted(id)) {
					refused = true;
					break;
				}
				lines.add(new Line(id, entry.quantity(catalog.maxStackSize(id))));
			}
			if (refused) {
				unresolved.add(new Unresolved(
					entry.name(), entry, "I'm not allowed to hand out " + resolution.label() + ".", true));
				continue;
			}

			int asked = kit ? entry.count() : lines.get(0).count();
			groups.add(new Group(resolution.label(), asked, kit, lines));
		}

		Plan draft = new Plan(Status.READY, groups, unresolved, "", 0, false);
		List<Line> merged = draft.lines();

		long total = 0L;
		for (Line line : merged) {
			if (line.count() > limits.maxPerRequest()) {
				return rejected(groups, unresolved, tooMany(limits));
			}
			total += line.count();
		}
		if (merged.size() > limits.maxTypesPerRequest()) {
			return rejected(groups, unresolved,
				"That's too many different things at once. I can manage " + limits.maxTypesPerRequest() + " kinds at a time.");
		}
		if (groups.isEmpty()) {
			return new Plan(Status.NOTHING_KNOWN, groups, unresolved, "", 0, false);
		}

		int totalItems = (int) Math.min(Integer.MAX_VALUE, total);
		return new Plan(Status.READY, groups, unresolved, "", totalItems, totalItems > limits.confirmAboveCount());
	}

	private static Plan rejected(List<Group> groups, List<Unresolved> unresolved, String message) {
		return new Plan(Status.REJECTED, groups, unresolved, message, 0, false);
	}

	private static String tooMany(Limits limits) {
		return "That's more than I'm allowed to make at once (" + limits.maxPerRequest() + " of one item).";
	}

	/** What Falsity says about names it could not deliver, or an empty string. */
	public static String unresolvedSentence(List<Unresolved> unresolved) {
		if (unresolved == null || unresolved.isEmpty()) {
			return "";
		}
		List<String> sentences = new ArrayList<>();
		List<String> unknownNames = new ArrayList<>();
		for (Unresolved entry : unresolved) {
			if (!entry.reason().isEmpty()) {
				sentences.add(entry.reason());
			} else {
				unknownNames.add("'" + entry.name() + "'");
			}
		}
		if (!unknownNames.isEmpty()) {
			sentences.add("I don't know what " + joinList(unknownNames, "or") + " is. Can you say it another way?");
		}
		return String.join(" ", sentences);
	}

	/** One delivered group: what was asked and how much actually fitted. */
	public record Delivery(Group group, int requestedItems, int deliveredItems) {
		public boolean complete() {
			return this.deliveredItems >= this.requestedItems;
		}
	}

	/**
	 * The sentence reporting what was handed over. Shortfalls are stated
	 * plainly; nothing is reported as given that was not.
	 */
	public static String deliverySentence(List<Delivery> deliveries) {
		List<String> full = new ArrayList<>();
		List<String> partial = new ArrayList<>();
		List<String> none = new ArrayList<>();
		for (Delivery delivery : deliveries) {
			Group group = delivery.group();
			if (delivery.complete()) {
				full.add(group.describe());
			} else if (delivery.deliveredItems() <= 0) {
				none.add(group.kit() ? group.label() : ItemResolver.pluralise(group.label(), group.count()));
			} else if (group.kit()) {
				partial.add("part of the " + group.label());
			} else {
				partial.add(delivery.deliveredItems() + " of the " + delivery.requestedItems() + " "
					+ ItemResolver.pluralise(group.label(), delivery.requestedItems()));
			}
		}

		StringBuilder builder = new StringBuilder();
		if (!full.isEmpty()) {
			builder.append("Here you go: ").append(joinList(full)).append('.');
		}
		if (!partial.isEmpty()) {
			if (builder.length() > 0) {
				builder.append(' ');
			}
			builder.append("I could only fit ").append(joinList(partial)).append("; your inventory is full.");
		}
		if (!none.isEmpty()) {
			if (builder.length() > 0) {
				builder.append(' ');
			}
			builder.append(full.isEmpty() && partial.isEmpty()
				? "Your inventory is full; I couldn't give you the "
				: "There was no room for the ").append(joinList(none)).append('.');
		}
		return builder.toString();
	}

	/** "a", "a and b", "a, b and c". */
	public static String joinList(List<String> parts) {
		return joinList(parts, "and");
	}

	private static String joinList(List<String> parts, String conjunction) {
		if (parts.isEmpty()) {
			return "";
		}
		if (parts.size() == 1) {
			return parts.get(0);
		}
		StringBuilder builder = new StringBuilder();
		for (int i = 0; i < parts.size(); i++) {
			if (i > 0) {
				builder.append(i == parts.size() - 1 ? " " + conjunction + " " : ", ");
			}
			builder.append(parts.get(i));
		}
		return builder.toString();
	}
}
