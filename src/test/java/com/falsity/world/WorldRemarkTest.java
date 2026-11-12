package com.falsity.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.falsity.actions.IntentRecognizer;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * A remark that happens to contain the words of a question is still a remark.
 * It has to reach the conversation, where Falsity can answer it as itself,
 * instead of being answered with the time or the weather.
 */
class WorldRemarkTest {
	private static String kind(String message) {
		return WorldQueryRecognizer.recognize(message).map(query -> query.kind().name().toLowerCase()).orElse("-");
	}

	@Test
	void remarksAboutTheTimeTheWeatherOrThePlaceAreConversation() {
		for (String remark : new String[] {
			"I like this time of day", "this is my favourite time of day", "the time of day does not matter", "it is. I like this time of day",
			"I hate weather like this", "I love weather like this", "we never get weather like that at home",
			"let me look around", "I'll look around the cave first", "we should look around", "I want to look around a bit",
			"let's look around", "lets look around before dark", "I might look around later",
			"what a nice biome", "what an odd biome this is"}) {
			assertEquals("-", kind(remark), remark);
			assertTrue(IntentRecognizer.recognize(remark).isEmpty(), "and nothing else may take it for an order: " + remark);
		}
	}

	@Test
	void theQuestionsThoseWordsCameFromStillWork() {
		Map<String, String> expected = new LinkedHashMap<>();
		expected.put("what time of day is it", "time");
		expected.put("tell me the time of day", "time");
		expected.put("do you know the time of day", "time");
		expected.put("what's the time of day", "time");
		expected.put("time of day", "time");
		expected.put("what time is it", "time");
		expected.put("is it night", "time");
		expected.put("can we sleep?", "time");
		expected.put("what's the weather like", "weather");
		expected.put("what is the weather like today", "weather");
		expected.put("what will the weather be like", "weather");
		expected.put("how's the weather", "weather");
		expected.put("is it raining", "weather");
		expected.put("weather", "weather");
		expected.put("look around", "surroundings");
		expected.put("look around for me", "surroundings");
		expected.put("can you look around", "surroundings");
		expected.put("please look around", "surroundings");
		expected.put("Falsity, look around", "surroundings");
		expected.put("what do you see", "surroundings");
		expected.put("what biome is this", "biome");
		expected.put("which biome are we in", "biome");
		expected.put("tell me the biome", "biome");
		expected.put("biome", "biome");
		for (Map.Entry<String, String> entry : expected.entrySet()) {
			assertEquals(entry.getValue(), kind(entry.getKey()), entry.getKey());
		}
	}
}
