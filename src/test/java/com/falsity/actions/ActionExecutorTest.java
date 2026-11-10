package com.falsity.actions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** The executor routes validated requests and never lets a handler crash it. */
class ActionExecutorTest {
	/** A handler that records what it was asked to do, or throws on demand. */
	private static final class StubHandler implements ActionHandler {
		private final ActionType type;
		private boolean throwOnHandle;
		ActionContext lastContext;
		ActionRequest lastRequest;
		int calls;

		StubHandler(ActionType type) {
			this.type = type;
		}

		void throwOnHandle(boolean value) {
			this.throwOnHandle = value;
		}

		@Override
		public ActionType type() {
			return this.type;
		}

		@Override
		public ActionResult handle(ActionContext context, ActionRequest request) {
			this.calls++;
			this.lastContext = context;
			this.lastRequest = request;
			if (this.throwOnHandle) {
				throw new IllegalStateException("boom");
			}
			return ActionResult.ok("did " + this.type);
		}
	}

	@Test
	void routesToRegisteredHandler() {
		ActionExecutor executor = new ActionExecutor();
		StubHandler handler = new StubHandler(ActionType.SIT);
		executor.register(handler);

		ActionResult result = executor.execute(new ActionContext(null, null, null, null), ActionRequest.of(ActionType.SIT));

		assertTrue(result.success());
		assertEquals(1, handler.calls);
		assertEquals(ActionType.SIT, handler.lastRequest.type());
	}

	@Test
	void rejectsUnknownType() {
		ActionExecutor executor = new ActionExecutor();
		executor.register(new StubHandler(ActionType.SIT));

		ActionResult result = executor.execute(
			new ActionContext(null, null, null, null),
			new ActionRequest(ActionType.FIGHT, Map.of())
		);

		assertFalse(result.success());
		assertTrue(result.message().contains("cannot"));
	}

	@Test
	void rejectsUndeclaredArguments() {
		ActionExecutor executor = new ActionExecutor();
		executor.register(new StubHandler(ActionType.SIT));

		ActionResult result = executor.execute(
			new ActionContext(null, null, null, null),
			new ActionRequest(ActionType.SIT, Map.of("explode", true))
		);

		assertFalse(result.success());
	}

	@Test
	void turnsHandlerExceptionIntoFailure() {
		ActionExecutor executor = new ActionExecutor();
		StubHandler handler = new StubHandler(ActionType.GREET);
		handler.throwOnHandle(true);
		executor.register(handler);

		ActionResult result = executor.execute(
			new ActionContext(null, null, null, null),
			ActionRequest.of(ActionType.GREET)
		);

		assertFalse(result.success());
		assertFalse(result.pending());
		assertEquals(1, handler.calls, "the handler was still invoked");
	}

	@Test
	void noneTypeAndNullRequestFailGracefully() {
		ActionExecutor executor = new ActionExecutor();
		executor.register(new StubHandler(ActionType.SIT));

		assertFalse(executor.execute(new ActionContext(null, null, null, null), ActionRequest.of(ActionType.NONE)).success());
		assertFalse(executor.execute(new ActionContext(null, null, null, null), null).success());
	}

	@Test
	void supportsReflectsRegistration() {
		ActionExecutor executor = new ActionExecutor();
		executor.register(new StubHandler(ActionType.COME));
		assertTrue(executor.supports(ActionType.COME));
		assertFalse(executor.supports(ActionType.SIT));
		assertFalse(executor.supports(null));
	}
}
