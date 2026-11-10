package com.falsity.building;

/**
 * A structure request as understood from the player's words or from the AI.
 *
 * @param structure the kind of structure ("house", "wall", "bridge", ...)
 * @param material  a material keyword ("oak", "stone", "cobblestone", ...) or null
 * @param width     requested width, or null to use the structure default
 * @param length    requested length, or null to use the structure default
 * @param height    requested height, or null to use the structure default
 */
public record BuildRequest(String structure, String material, Integer width, Integer length, Integer height) {
	public String structureOrDefault() {
		return structure == null || structure.isBlank() ? "house" : structure.trim().toLowerCase();
	}

	public String materialOrDefault() {
		return material == null || material.isBlank() ? "oak" : material.trim().toLowerCase();
	}
}
