package com.falsity.actions.items;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.falsity.actions.items.ItemRequestParser.Entry;
import com.falsity.actions.items.ItemRequestParser.Unit;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Splitting "a crafting table and 64 sticks" into separate items with quantities. */
class ItemRequestParserTest {
	@Test
	void splitsTwoItemsJoinedByAnd() {
		List<Entry> entries = ItemRequestParser.parse("a crafting table and 64 sticks");

		assertEquals(2, entries.size());
		assertEquals("crafting table", entries.get(0).name());
		assertEquals(1, entries.get(0).count());
		assertFalse(entries.get(0).countStated(), "'a' is not a stated quantity");
		assertEquals("sticks", entries.get(1).name());
		assertEquals(64, entries.get(1).count());
		assertTrue(entries.get(1).countStated());
	}

	@Test
	void splitsCommaListsWithAFinalAnd() {
		List<Entry> entries = ItemRequestParser.parse("a diamond pickaxe, 3 iron ingots and a stack of torches");

		assertEquals(3, entries.size());
		assertEquals("diamond pickaxe", entries.get(0).name());
		assertEquals("iron ingots", entries.get(1).name());
		assertEquals(3, entries.get(1).count());
		assertEquals("torches", entries.get(2).name());
		assertEquals(Unit.STACKS, entries.get(2).unit());
		assertEquals(64, entries.get(2).quantity(64));
	}

	@Test
	void keepsFlintAndSteelTogether() {
		List<Entry> entries = ItemRequestParser.parse("flint and steel and 2 buckets");

		assertEquals(2, entries.size());
		assertEquals("flint and steel", entries.get(0).name());
		assertEquals("buckets", entries.get(1).name());
		assertEquals(2, entries.get(1).count());
	}

	@Test
	void readsNumberWordsAndLooseQuantities() {
		assertEquals(64, ItemRequestParser.parse("sixty four torches").get(0).count());
		assertEquals(20, ItemRequestParser.parse("twenty arrows").get(0).count());
		assertEquals(12, ItemRequestParser.parse("a dozen eggs").get(0).count());
		assertEquals(2, ItemRequestParser.parse("a couple of beds").get(0).count());
		assertEquals(3, ItemRequestParser.parse("a few torches").get(0).count());
		assertEquals("eggs", ItemRequestParser.parse("a dozen eggs").get(0).name());
	}

	@Test
	void readsCountsWrittenAsMultipliers() {
		assertEquals(64, ItemRequestParser.parse("torches x64").get(0).count());
		assertEquals("torches", ItemRequestParser.parse("torches x64").get(0).name());
		assertEquals(64, ItemRequestParser.parse("64x torches").get(0).count());
		assertEquals(12, ItemRequestParser.parse("12 of the oak planks").get(0).count());
		assertEquals("oak planks", ItemRequestParser.parse("12 of the oak planks").get(0).name());
	}

	@Test
	void stacksDependOnTheItem() {
		Entry twoStacks = ItemRequestParser.parse("2 stacks of cobblestone").get(0);
		assertEquals(Unit.STACKS, twoStacks.unit());
		assertEquals(128, twoStacks.quantity(64));
		assertEquals(32, twoStacks.quantity(16), "a stack of ender pearls is 16");

		Entry half = ItemRequestParser.parse("half a stack of cobblestone").get(0);
		assertEquals(Unit.HALF_STACKS, half.unit());
		assertEquals(32, half.quantity(64));
		assertEquals(1, half.quantity(1), "half a stack of something unstackable is still one");
	}

	@Test
	void dropsCourtesyAndFiller() {
		List<Entry> entries = ItemRequestParser.parse("like 20 torches and another sword pls");

		assertEquals(2, entries.size());
		assertEquals("torches", entries.get(0).name());
		assertEquals(20, entries.get(0).count());
		assertEquals("sword", entries.get(1).name());
		assertEquals("food", ItemRequestParser.parse("some food please").get(0).name());
		assertEquals("diamonds", ItemRequestParser.parse("32 diamonds.").get(0).name());
	}

	@Test
	void vagueRequestsNameNothing() {
		assertTrue(ItemRequestParser.parse("something").isEmpty());
		assertTrue(ItemRequestParser.parse("a").isEmpty());
		assertTrue(ItemRequestParser.parse("5").isEmpty());
		assertTrue(ItemRequestParser.parse("").isEmpty());
		assertTrue(ItemRequestParser.parse(null).isEmpty());
		assertTrue(ItemRequestParser.parse("anything, stuff and things").isEmpty());
	}

	@Test
	void absurdCountsAreFlaggedNotWrapped() {
		Entry entry = ItemRequestParser.parse("99999999999999999999 diamonds").get(0);
		assertTrue(entry.count() > ItemRequestParser.MAX_COUNT, "an overflowing number must not wrap to a small one");
	}

	@Test
	void formatRoundTrips() {
		for (String text : List.of(
			"a crafting table and 64 sticks",
			"a diamond pickaxe, 3 iron ingots and a stack of torches",
			"half a stack of cobblestone and 2 stacks of dirt",
			"flint and steel and 2 buckets")) {
			List<Entry> first = ItemRequestParser.parse(text);
			List<Entry> second = ItemRequestParser.parse(ItemRequestParser.format(first));
			assertEquals(first.size(), second.size(), text);
			for (int i = 0; i < first.size(); i++) {
				assertEquals(first.get(i).name(), second.get(i).name(), text);
				assertEquals(first.get(i).unit(), second.get(i).unit(), text);
				assertEquals(first.get(i).quantity(64), second.get(i).quantity(64), text);
			}
		}
		assertEquals("1 crafting table, 64 sticks", ItemRequestParser.format(ItemRequestParser.parse("a crafting table and 64 sticks")));
	}
}
