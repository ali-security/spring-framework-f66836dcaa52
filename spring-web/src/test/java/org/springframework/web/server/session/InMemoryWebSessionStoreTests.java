/*
 * Copyright 2002-2024 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.web.server.session;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import reactor.core.scheduler.Schedulers;

import org.springframework.beans.DirectFieldAccessor;
import org.springframework.lang.Nullable;
import org.springframework.util.ReflectionUtils;
import org.springframework.web.server.WebSession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/**
 * Tests for {@link InMemoryWebSessionStore}.
 *
 * @author Rob Winch
 */
class InMemoryWebSessionStoreTests {

	private static final Duration TIMEOUT = Duration.ofSeconds(5);


	private InMemoryWebSessionStore store = new InMemoryWebSessionStore();


	@Test
	void startsSessionExplicitly() {
		WebSession session = this.store.createWebSession().block();
		assertThat(session).isNotNull();
		session.start();
		assertThat(session.isStarted()).isTrue();
	}

	@Test
	void startsSessionImplicitly() {
		WebSession session = this.store.createWebSession().block();
		assertThat(session).isNotNull();
		session.start();
		session.getAttributes().put("foo", "bar");
		assertThat(session.isStarted()).isTrue();
	}

	@Test // gh-24027, gh-26958
	public void createSessionDoesNotBlock() {
		this.store.createWebSession()
				.doOnNext(session -> assertThat(Schedulers.isInNonBlockingThread()).isTrue())
				.block();
	}

	@Test
	void retrieveExpiredSession() {
		WebSession session = this.store.createWebSession().block();
		assertThat(session).isNotNull();
		session.getAttributes().put("foo", "bar");
		session.save().block();

		String id = session.getId();
		WebSession retrieved = this.store.retrieveSession(id).block();
		assertThat(retrieved).isNotNull();
		assertThat(retrieved).isSameAs(session);

		// Fast-forward 31 minutes
		this.store.setClock(Clock.offset(this.store.getClock(), Duration.ofMinutes(31)));
		WebSession retrievedAgain = this.store.retrieveSession(id).block();
		assertThat(retrievedAgain).isNull();
	}

	@Test
	void lastAccessTimeIsUpdatedOnRetrieve() {
		WebSession session1 = this.store.createWebSession().block();
		assertThat(session1).isNotNull();
		String id = session1.getId();
		Instant time1 = session1.getLastAccessTime();
		session1.start();
		session1.save().block();

		// Fast-forward a few seconds
		this.store.setClock(Clock.offset(this.store.getClock(), Duration.ofSeconds(5)));

		WebSession session2 = this.store.retrieveSession(id).block();
		assertThat(session2).isNotNull();
		assertThat(session2).isSameAs(session1);
		Instant time2 = session2.getLastAccessTime();
		assertThat(time1.isBefore(time2)).isTrue();
	}

	@Test // SPR-17051
	public void sessionInvalidatedBeforeSave() {
		// Request 1 creates session
		WebSession session1 = this.store.createWebSession().block();
		assertThat(session1).isNotNull();
		String id = session1.getId();
		session1.start();
		session1.save().block();

		// Request 2 retrieves session
		WebSession session2 = this.store.retrieveSession(id).block();
		assertThat(session2).isNotNull();
		assertThat(session2).isSameAs(session1);

		// Request 3 retrieves and invalidates
		WebSession session3 = this.store.retrieveSession(id).block();
		assertThat(session3).isNotNull();
		assertThat(session3).isSameAs(session1);
		session3.invalidate().block();

		// Request 2 saves session after invalidated
		session2.save().block();

		// Session should not be present
		WebSession session4 = this.store.retrieveSession(id).block();
		assertThat(session4).isNull();
	}

	@Test
	void expirationCheckPeriod() {

		DirectFieldAccessor accessor = new DirectFieldAccessor(this.store);
		Map<?,?> sessions = (Map<?, ?>) accessor.getPropertyValue("sessions");
		assertThat(sessions).isNotNull();

		// Create 100 sessions
		IntStream.range(0, 100).forEach(i -> insertSession());
		assertThat(sessions).hasSize(100);

		// Force a new clock (31 min later), don't use setter which would clean expired sessions
		accessor.setPropertyValue("clock", Clock.offset(this.store.getClock(), Duration.ofMinutes(31)));
		assertThat(sessions).hasSize(100);

		// Create 1 more which forces a time-based check (clock moved forward)
		insertSession();
		assertThat(sessions).hasSize(1);
	}

	@Test
	void maxSessions() {

		IntStream.range(0, 10000).forEach(i -> insertSession());
		assertThatIllegalStateException().isThrownBy(
				this::insertSession)
			.withMessage("Max sessions limit reached: 10000");
	}

	@Test
	void changeSessionId() {
		WebSession session = insertSession();
		String oldId = session.getId();

		session.changeSessionId().block(TIMEOUT);

		assertSessionIdChanged(session, oldId);
	}

	@Test // CVE-2026-41839
	void saveWithStaleIdConcurrentWithChangeSessionId() throws Exception {
		TrappingSessionMap sessions = installTrappingSessionMap();
		WebSession session = this.store.createWebSession().block();
		assertThat(session).isNotNull();
		session.start();
		String oldId = session.getId();

		// Request 1 saves the new session, and is suspended on its first access to the store
		SaveThread saveThread = new SaveThread(session);
		sessions.trapFirstAccessFrom(saveThread);
		saveThread.start();
		sessions.awaitTrapped();

		// Request 2 changes the session id (e.g. on login)
		session.changeSessionId().block(TIMEOUT);

		// Request 1 resumes with the session id it read before the change
		sessions.release();
		saveThread.awaitCompletion();

		assertSessionIdChanged(session, oldId);
	}

	@Test // CVE-2026-41839
	void saveOfStoredSessionWithStaleIdConcurrentWithChangeSessionId() throws Exception {
		TrappingSessionMap sessions = installTrappingSessionMap();
		WebSession session = insertSession();
		String oldId = session.getId();

		// Request 1 retrieves the stored session and saves it, suspended on its first access to the store
		WebSession retrieved = this.store.retrieveSession(oldId).block();
		assertThat(retrieved).isSameAs(session);
		SaveThread saveThread = new SaveThread(retrieved);
		sessions.trapFirstAccessFrom(saveThread);
		saveThread.start();
		sessions.awaitTrapped();

		// Request 2 changes the session id (e.g. on login)
		session.changeSessionId().block(TIMEOUT);

		// Request 1 resumes with the session id it read before the change
		sessions.release();
		saveThread.awaitCompletion();

		assertSessionIdChanged(session, oldId);
	}

	@Test // CVE-2026-41839
	void saveConcurrentWithChangeSessionIdInProgress() throws Exception {
		TrappingSessionMap sessions = installTrappingSessionMap();
		WebSession session = insertSession();
		String oldId = session.getId();

		// Request 2 changes the session id, and is suspended right after the old id was removed
		sessions.trapRemovalOf(oldId);
		CompletableFuture<Void> changeSessionId = session.changeSessionId().toFuture();
		sessions.awaitTrapped();

		// Request 1 saves the session while the change is in progress
		SaveThread saveThread = new SaveThread(session);
		saveThread.start();
		saveThread.awaitBlockedOrCompleted();

		sessions.release();
		changeSessionId.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
		saveThread.awaitCompletion();

		assertSessionIdChanged(session, oldId);
	}

	private WebSession insertSession() {
		WebSession session = this.store.createWebSession().block();
		assertThat(session).isNotNull();
		session.start();
		session.save().block();
		return session;
	}

	private TrappingSessionMap installTrappingSessionMap() {
		TrappingSessionMap sessions = new TrappingSessionMap();
		Field field = ReflectionUtils.findField(InMemoryWebSessionStore.class, "sessions");
		assertThat(field).isNotNull();
		ReflectionUtils.makeAccessible(field);
		ReflectionUtils.setField(field, this.store, sessions);
		return sessions;
	}

	private void assertSessionIdChanged(WebSession session, String oldId) {
		String newId = session.getId();
		assertThat(newId).isNotEqualTo(oldId);
		assertThat(this.store.getSessions()).containsOnlyKeys(newId);
		// The previous (possibly fixated) session id must no longer give access to the session
		assertThat(this.store.retrieveSession(oldId).block()).isNull();
		assertThat(this.store.retrieveSession(newId).block()).isSameAs(session);
	}


	/**
	 * Session map that suspends a single access to it until released,
	 * in order to reproduce a specific interleaving of concurrent requests.
	 */
	@SuppressWarnings("serial")
	private static class TrappingSessionMap extends ConcurrentHashMap<String, Object> {

		private final CountDownLatch trapped = new CountDownLatch(1);

		private final CountDownLatch released = new CountDownLatch(1);

		@Nullable
		private volatile Thread trappedThread;

		@Nullable
		private volatile Object trappedRemovalKey;

		void trapFirstAccessFrom(Thread thread) {
			this.trappedThread = thread;
		}

		void trapRemovalOf(String key) {
			this.trappedRemovalKey = key;
		}

		void awaitTrapped() throws InterruptedException {
			assertThat(this.trapped.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)).as("trapped").isTrue();
		}

		void release() {
			this.released.countDown();
		}

		@Override
		public Object get(Object key) {
			trapIfAccessedFromTrappedThread();
			return super.get(key);
		}

		@Override
		public Object put(String key, Object value) {
			trapIfAccessedFromTrappedThread();
			return super.put(key, value);
		}

		@Override
		public Object putIfAbsent(String key, Object value) {
			trapIfAccessedFromTrappedThread();
			return super.putIfAbsent(key, value);
		}

		@Override
		public Object remove(Object key) {
			Object result = super.remove(key);
			if (key.equals(this.trappedRemovalKey)) {
				this.trappedRemovalKey = null;
				suspend();
			}
			return result;
		}

		private void trapIfAccessedFromTrappedThread() {
			if (Thread.currentThread() == this.trappedThread) {
				this.trappedThread = null;
				suspend();
			}
		}

		private void suspend() {
			this.trapped.countDown();
			try {
				this.released.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
		}
	}


	/**
	 * Thread that saves a session, as a concurrent request would.
	 */
	private static class SaveThread extends Thread {

		private final WebSession session;

		@Nullable
		private volatile Throwable failure;

		SaveThread(WebSession session) {
			super("web-session-save");
			this.session = session;
			setDaemon(true);
		}

		@Override
		public void run() {
			try {
				this.session.save().block(TIMEOUT);
			}
			catch (Throwable ex) {
				this.failure = ex;
			}
		}

		void awaitBlockedOrCompleted() throws InterruptedException {
			long deadline = System.nanoTime() + TIMEOUT.toNanos();
			while (System.nanoTime() < deadline) {
				Thread.State state = getState();
				if (state == Thread.State.WAITING || state == Thread.State.TERMINATED) {
					return;
				}
				Thread.sleep(10);
			}
		}

		void awaitCompletion() throws InterruptedException {
			join(TIMEOUT.toMillis());
			assertThat(isAlive()).as("save completed").isFalse();
			assertThat(this.failure).isNull();
		}
	}

}
