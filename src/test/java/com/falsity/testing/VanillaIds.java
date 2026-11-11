package com.falsity.testing;

import com.falsity.actions.items.ItemCatalog;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The real vanilla ids of the Minecraft version this mod targets, loaded from
 * plain lists in {@code src/test/resources/vanilla}. Tests resolve names
 * against these so that "does this item exist" means the same in a unit test
 * as it does in the game, without starting one.
 */
public final class VanillaIds {
	private static final Set<String> ITEMS = load("items.txt");
	private static final Set<String> STRUCTURES = load("structures.txt");
	private static final Set<String> BLOCKS = load("blocks.txt");

	private VanillaIds() {
	}

	public static Set<String> items() {
		return ITEMS;
	}

	public static Set<String> structures() {
		return STRUCTURES;
	}

	public static Set<String> blocks() {
		return BLOCKS;
	}

	/** An item catalog over the real item list, with realistic stack sizes. */
	public static ItemCatalog itemCatalog() {
		return new ItemCatalog() {
			@Override
			public boolean exists(String id) {
				return ITEMS.contains(id);
			}

			@Override
			public int maxStackSize(String id) {
				if (id.endsWith("_pickaxe") || id.endsWith("_axe") || id.endsWith("_shovel") || id.endsWith("_hoe")
					|| id.endsWith("_sword") || id.endsWith("_helmet") || id.endsWith("_chestplate")
					|| id.endsWith("_leggings") || id.endsWith("_boots") || id.equals("bow") || id.endsWith("_bucket")
					|| id.endsWith("_bed") || id.equals("shield")) {
					return 1;
				}
				if (id.equals("ender_pearl") || id.equals("egg") || id.equals("snowball") || id.endsWith("_sign")
					|| id.equals("bucket")) {
					return 16;
				}
				return 64;
			}
		};
	}

	private static Set<String> load(String name) {
		Set<String> ids = new LinkedHashSet<>();
		try (InputStream stream = VanillaIds.class.getResourceAsStream("/vanilla/" + name)) {
			if (stream == null) {
				throw new IllegalStateException("Missing test resource vanilla/" + name);
			}
			BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
			String line;
			while ((line = reader.readLine()) != null) {
				String id = line.trim();
				if (!id.isEmpty()) {
					ids.add(id);
				}
			}
		} catch (IOException exception) {
			throw new UncheckedIOException(exception);
		}
		return ids;
	}
}
