package com.falsity.actions.items;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.falsity.actions.items.ItemPlanner.Delivery;
import com.falsity.actions.items.ItemPlanner.Limits;
import com.falsity.actions.items.ItemPlanner.Line;
import com.falsity.actions.items.ItemPlanner.Plan;
import com.falsity.actions.items.ItemPlanner.Status;
import com.falsity.testing.VanillaIds;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Turning a parsed request into a checked plan, before anything is created. */
class ItemPlannerTest {
	private static final ItemCatalog CATALOG = VanillaIds.itemCatalog();
	private static final Limits DEFAULTS = new Limits(64, 8, 128, false);

	private static Plan plan(String text) {
		return plan(text, DEFAULTS);
	}

	private static Plan plan(String text, Limits limits) {
		return ItemPlanner.plan(ItemRequestParser.parse(text), CATALOG, limits);
	}

	@Test
	void craftingTableAndSticks() {
		Plan plan = plan("a crafting table and 64 sticks");

		assertEquals(Status.READY, plan.status());
		assertEquals(List.of(new Line("crafting_table", 1), new Line("stick", 64)), plan.lines());
		assertEquals(65, plan.totalItems());
		assertFalse(plan.needsConfirmation());
		assertEquals("1 crafting table and 64 sticks", plan.describe());
		assertTrue(plan.unresolved().isEmpty());
	}

	@Test
	void threeItemsWithAStack() {
		Plan plan = plan("a diamond pickaxe, 3 iron ingots and a stack of torches");

		assertEquals(Status.READY, plan.status());
		assertEquals(
			List.of(new Line("diamond_pickaxe", 1), new Line("iron_ingot", 3), new Line("torch", 64)),
			plan.lines());
		assertEquals("1 diamond pickaxe, 3 iron ingots and 64 torches", plan.describe());
	}

	@Test
	void aStackUsesTheItemsOwnStackSize() {
		Plan plan = plan("a stack of ender pearls");
		assertEquals(List.of(new Line("ender_pearl", 16)), plan.lines());
	}

	@Test
	void materialCarriesAcrossEquipmentInOneRequest() {
		Plan plan = plan("a diamond pickaxe and a shovel");
		assertEquals(List.of(new Line("diamond_pickaxe", 1), new Line("diamond_shovel", 1)), plan.lines());

		Plan separate = plan("a shovel and a diamond pickaxe");
		assertEquals(List.of(new Line("iron_shovel", 1), new Line("diamond_pickaxe", 1)), separate.lines(),
			"a material stated later does not reach back");
	}

	@Test
	void unknownNamesAreReportedAndTheRestIsStillPlanned() {
		Plan plan = plan("a flibber and 3 torches");

		assertEquals(Status.READY, plan.status());
		assertEquals(List.of(new Line("torch", 3)), plan.lines());
		assertEquals(1, plan.unresolved().size());
		assertEquals("flibber", plan.unresolved().get(0).name());
		assertNotNull(plan.firstQuestion());
		assertTrue(ItemPlanner.unresolvedSentence(plan.unresolved()).contains("flibber"));
	}

	@Test
	void nothingKnownMeansNothingPlanned() {
		Plan plan = plan("a hug");

		assertEquals(Status.NOTHING_KNOWN, plan.status());
		assertTrue(plan.lines().isEmpty());
		assertEquals("hug", plan.unresolved().get(0).name());
	}

	@Test
	void anImpossibleMaterialExplainsItself() {
		Plan plan = plan("stone armour");

		assertEquals(Status.NOTHING_KNOWN, plan.status());
		assertTrue(plan.lines().isEmpty(), "stone armour must not become iron armour");
		assertTrue(ItemPlanner.unresolvedSentence(plan.unresolved()).contains("stone"));
	}

	@Test
	void oneItemOverTheLimitRejectsTheWholeRequest() {
		Plan plan = plan("a torch and 65 diamonds");

		assertEquals(Status.REJECTED, plan.status());
		assertTrue(plan.rejection().contains("64"));
		assertEquals(0, plan.totalItems());
	}

	@Test
	void theSameItemAskedTwiceCountsTogether() {
		Plan within = plan("32 torches and 32 torches");
		assertEquals(Status.READY, within.status());
		assertEquals(List.of(new Line("torch", 64)), within.lines());

		Plan over = plan("40 torches and 40 torches");
		assertEquals(Status.REJECTED, over.status(), "splitting an order must not get around the per-item limit");
	}

	@Test
	void tooManyKindsAreRejected() {
		Plan plan = plan("a torch, a stick, a diamond, an apple, a bow, an arrow, a bucket, a compass and a clock");
		assertEquals(Status.REJECTED, plan.status());
		assertTrue(plan.rejection().contains("8"));
	}

	@Test
	void absurdCountsAreRejected() {
		assertEquals(Status.REJECTED, plan("99999999999999999999 diamonds").status());
		assertEquals(Status.REJECTED, plan("100000 diamonds").status());
	}

	@Test
	void largeTotalsAskFirst() {
		Plan plan = plan("64 torches, 64 sticks and 64 arrows");
		assertEquals(Status.READY, plan.status());
		assertEquals(192, plan.totalItems());
		assertTrue(plan.needsConfirmation(), "192 items is above the confirmation threshold of 128");
	}

	@Test
	void restrictedItemsNeedPermission() {
		Plan refused = plan("a bedrock and a torch");
		assertEquals(List.of(new Line("torch", 1)), refused.lines());
		assertTrue(refused.unresolved().get(0).refused());
		assertNull(refused.firstQuestion(), "a refusal is not a question about what the player meant");

		Plan allowed = plan("a bedrock and a torch", new Limits(64, 8, 128, true));
		assertEquals(List.of(new Line("bedrock", 1), new Line("torch", 1)), allowed.lines());
	}

	@Test
	void kitsExpandToTheirPieces() {
		Plan plan = plan("a full set of armour and 16 bread");

		assertEquals(Status.READY, plan.status());
		assertEquals(5, plan.lines().size());
		assertEquals("iron armour and 16 bread", plan.describe());
	}

	@Test
	void deliveryIsReportedTruthfully() {
		Plan plan = plan("a crafting table and 64 sticks");
		ItemPlanner.Group table = plan.groups().get(0);
		ItemPlanner.Group sticks = plan.groups().get(1);

		assertEquals("Here you go: 1 crafting table and 64 sticks.",
			ItemPlanner.deliverySentence(List.of(new Delivery(table, 1, 1), new Delivery(sticks, 64, 64))));

		String partial = ItemPlanner.deliverySentence(List.of(new Delivery(table, 1, 1), new Delivery(sticks, 64, 10)));
		assertTrue(partial.contains("1 crafting table"), partial);
		assertTrue(partial.contains("10 of the 64 sticks"), partial);
		assertTrue(partial.contains("inventory is full"), partial);

		String none = ItemPlanner.deliverySentence(List.of(new Delivery(table, 1, 0), new Delivery(sticks, 64, 0)));
		assertFalse(none.contains("Here you go"), "nothing was given, so nothing may be claimed: " + none);
		assertTrue(none.contains("inventory is full"), none);
	}
}
