package com.falsity.personality;

import com.falsity.config.FalsityConfig;
import java.util.ArrayList;
import java.util.List;

/**
 * Falsity's personality towards one player: nine traits, each 0 to 100.
 *
 * <p>Values start from the configured defaults and then evolve gradually
 * with interactions (see {@link #evolve(Event, double)}). They are stored
 * inside the player's {@code PlayerMemory}, so they persist across sessions
 * and differ per relationship: Falsity can be warmly attached to its owner
 * while staying wary of strangers.
 *
 * <p>This class is pure (no Minecraft types) so the evolution rules can be
 * unit tested. Hostility is tracked for future milestones but is never
 * allowed to act in this one: nothing here can make Falsity attack.
 */
public final class PersonalityProfile {
	/** Wants to explore, ask questions and investigate. */
	public double curiosity = 70.0;
	/** Warmth, leaning in, enjoying company. */
	public double affection = 60.0;
	/** How strongly Falsity wants to stay near this player. */
	public double attachment = 40.0;
	/** Playfulness: hops, ear perks, teasing. */
	public double playfulness = 50.0;
	/** Discomfort when others approach its player. Tracked, never acted on yet. */
	public double jealousy = 10.0;
	/** Wariness of strangers and odd behaviour. */
	public double suspicion = 5.0;
	/** How much Falsity misses this player when they are away. */
	public double loneliness = 20.0;
	/** Startle and shrinking. */
	public double fear = 5.0;
	/** Tracked for future milestones. Never causes aggression in this one. */
	public double hostility = 0.0;

	/** Interaction events that nudge personality values. */
	public enum Event {
		BONDED,
		PETTED,
		CONVERSATION,
		ORDER_GIVEN,
		OWNER_RETURNED,
		OWNER_ABSENT,
		STRANGER_NEAR,
		STARTLED
	}

	public PersonalityProfile() {
	}

	/** Creates a profile from the configured starting values. */
	public static PersonalityProfile fromConfig(FalsityConfig.Personality config) {
		PersonalityProfile profile = new PersonalityProfile();
		profile.curiosity = config.curiosity;
		profile.affection = config.affection;
		profile.attachment = config.attachment;
		profile.playfulness = config.playfulness;
		profile.jealousy = config.jealousy;
		profile.suspicion = config.suspicion;
		profile.loneliness = config.loneliness;
		profile.fear = config.fear;
		profile.hostility = config.hostility;
		profile.clamp();
		return profile;
	}

	/**
	 * Applies one interaction event. Deltas are small and scaled by the
	 * configured evolution rate, so personality drifts over many interactions
	 * instead of flipping in a single conversation.
	 */
	public void evolve(Event event, double evolutionRate) {
		if (event == null || evolutionRate <= 0.0) {
			return;
		}
		double rate = evolutionRate;

		switch (event) {
			case BONDED -> {
				affection += 8.0 * rate;
				attachment += 10.0 * rate;
				curiosity += 5.0 * rate;
				loneliness -= 5.0 * rate;
			}
			case PETTED -> {
				affection += 2.0 * rate;
				playfulness += 1.0 * rate;
				loneliness -= 2.0 * rate;
			}
			case CONVERSATION -> {
				curiosity += 2.0 * rate;
				affection += 1.0 * rate;
				attachment += 1.0 * rate;
				loneliness -= 1.0 * rate;
			}
			case ORDER_GIVEN -> {
				attachment += 1.0 * rate;
			}
			case OWNER_RETURNED -> {
				affection += 3.0 * rate;
				attachment += 2.0 * rate;
				loneliness -= 6.0 * rate;
				curiosity += 1.0 * rate;
			}
			case OWNER_ABSENT -> {
				loneliness += 3.0 * rate;
				attachment += 1.0 * rate;
			}
			case STRANGER_NEAR -> {
				jealousy += 2.0 * rate;
				suspicion += 1.0 * rate;
			}
			case STARTLED -> {
				fear += 4.0 * rate;
				suspicion += 1.0 * rate;
			}
		}

		clamp();
	}

	/** Brings every trait back into the 0 to 100 range. */
	public void clamp() {
		this.curiosity = clampValue(this.curiosity);
		this.affection = clampValue(this.affection);
		this.attachment = clampValue(this.attachment);
		this.playfulness = clampValue(this.playfulness);
		this.jealousy = clampValue(this.jealousy);
		this.suspicion = clampValue(this.suspicion);
		this.loneliness = clampValue(this.loneliness);
		this.fear = clampValue(this.fear);
		this.hostility = clampValue(this.hostility);
	}

	/** The three strongest traits, strongest first. Used for prompts and UI. */
	public List<String> dominantTraits() {
		List<Trait> traits = new ArrayList<>();
		traits.add(new Trait("curiosity", this.curiosity));
		traits.add(new Trait("affection", this.affection));
		traits.add(new Trait("attachment", this.attachment));
		traits.add(new Trait("playfulness", this.playfulness));
		traits.add(new Trait("jealousy", this.jealousy));
		traits.add(new Trait("suspicion", this.suspicion));
		traits.add(new Trait("loneliness", this.loneliness));
		traits.add(new Trait("fear", this.fear));
		traits.add(new Trait("hostility", this.hostility));
		traits.sort((a, b) -> Double.compare(b.value(), a.value()));

		List<String> result = new ArrayList<>();
		for (int i = 0; i < Math.min(3, traits.size()); i++) {
			result.add(traits.get(i).name());
		}
		return result;
	}

	/**
	 * Short natural-language description for prompts, e.g.
	 * "very curious, warmly affectionate and playful".
	 */
	public String describe() {
		List<String> parts = new ArrayList<>();
		// The thresholds match the mood thresholds in EmotionalState, so a
		// default Falsity describes itself the way it actually behaves.
		addIfStrong(parts, "very curious", this.curiosity, 70.0);
		addIfStrong(parts, "warmly affectionate", this.affection, 65.0);
		addIfStrong(parts, "deeply attached", this.attachment, 75.0);
		addIfStrong(parts, "playful", this.playfulness, 70.0);
		addIfStrong(parts, "jealous", this.jealousy, 60.0);
		addIfStrong(parts, "suspicious", this.suspicion, 60.0);
		addIfStrong(parts, "lonely without you", this.loneliness, 60.0);
		addIfStrong(parts, "easily frightened", this.fear, 70.0);

		if (parts.isEmpty()) {
			return "quiet and reserved";
		}
		if (parts.size() == 1) {
			return parts.get(0);
		}
		if (parts.size() == 2) {
			return parts.get(0) + " and " + parts.get(1);
		}
		return parts.get(0) + ", " + parts.get(1) + " and " + parts.get(2);
	}

	private static void addIfStrong(List<String> parts, String phrase, double value, double threshold) {
		if (value >= threshold) {
			parts.add(phrase);
		}
	}

	private static double clampValue(double value) {
		if (Double.isNaN(value) || Double.isInfinite(value)) {
			return 0.0;
		}
		return Math.max(0.0, Math.min(100.0, value));
	}

	private record Trait(String name, double value) {
	}
}
