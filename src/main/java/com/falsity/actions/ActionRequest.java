package com.falsity.actions;

import java.util.Map;

/**
 * One structured action request, as produced by the AI or by local intent
 * recognition.
 *
 * <p>A request is pure data: a type plus a small map of arguments. It carries
 * no behaviour and no reference to any game object, so it can be validated,
 * logged and unit tested without a running server. The executor decides what
 * to do with it.
 */
public record ActionRequest(ActionType type, Map<String, Object> args) {
	public ActionRequest {
		if (type == null) {
			type = ActionType.NONE;
		}
		args = args == null ? Map.of() : Map.copyOf(args);
	}

	public static ActionRequest of(ActionType type) {
		return new ActionRequest(type, Map.of());
	}

	public static ActionRequest of(ActionType type, Map<String, Object> args) {
		return new ActionRequest(type, args);
	}

	public boolean has(String key) {
		return this.args.containsKey(key);
	}

	public String arg(String key) {
		Object value = this.args.get(key);
		return value == null ? null : String.valueOf(value);
	}

	public String arg(String key, String defaultValue) {
		String value = arg(key);
		return value == null || value.isBlank() ? defaultValue : value;
	}

	/**
	 * A whole-number argument. JSON has only one kind of number, so a count
	 * that arrives as {@code 32.0} (or as the text {@code "32"}) is still 32;
	 * a fraction is rounded to the nearest whole number.
	 */
	public int argInt(String key, int defaultValue) {
		double value = argDouble(key, Double.NaN);
		if (Double.isNaN(value) || Double.isInfinite(value)) {
			return defaultValue;
		}
		long rounded = Math.round(value);
		return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, rounded));
	}

	public double argDouble(String key, double defaultValue) {
		Object raw = this.args.get(key);
		if (raw instanceof Number number) {
			return number.doubleValue();
		}
		String value = arg(key);
		if (value == null || value.isBlank()) {
			return defaultValue;
		}
		try {
			return Double.parseDouble(value.trim());
		} catch (NumberFormatException malformed) {
			return defaultValue;
		}
	}

	/** A short, log-safe description. Never contains secrets. */
	public String describe() {
		if (this.type == ActionType.NONE || this.args.isEmpty()) {
			return this.type.name();
		}
		return this.type.name() + this.args;
	}
}
