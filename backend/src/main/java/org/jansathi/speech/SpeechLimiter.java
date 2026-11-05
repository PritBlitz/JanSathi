package org.jansathi.speech;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Keeps anonymous callers from using up the CPU: a sliding minute per client address, and a cap on
 * work running at once. Addresses stay in memory for a minute and are never written anywhere.
 */
@Component
class SpeechLimiter {

	private static final long WINDOW_MILLIS = 60_000;
	/** Past this many addresses, empty windows are cleared. */
	private static final int SWEEP_AT = 10_000;

	private final int perMinute;
	private final Semaphore running;
	private final Clock clock;
	private final Map<String, Deque<Long>> recent = new ConcurrentHashMap<>();

	@Autowired
	SpeechLimiter(SpeechProperties properties) {
		this(properties.perMinute(), properties.maxConcurrent(), Clock.systemUTC());
	}

	SpeechLimiter(int perMinute, int maxConcurrent, Clock clock) {
		this.perMinute = perMinute;
		this.running = new Semaphore(maxConcurrent);
		this.clock = clock;
	}

	/** Counts one request from this address; false when it is over the limit. */
	boolean allow(String address) {
		long now = clock.millis();
		if (recent.size() > SWEEP_AT) {
			recent.values().removeIf(times -> {
				synchronized (times) {
					return times.isEmpty() || times.peekLast() <= now - WINDOW_MILLIS;
				}
			});
		}
		Deque<Long> times = recent.computeIfAbsent(address, a -> new ArrayDeque<>());
		synchronized (times) {
			while (!times.isEmpty() && times.peekFirst() <= now - WINDOW_MILLIS) {
				times.pollFirst();
			}
			if (times.size() >= perMinute) {
				return false;
			}
			times.addLast(now);
			return true;
		}
	}

	boolean tryStart() {
		return running.tryAcquire();
	}

	void finish() {
		running.release();
	}
}
