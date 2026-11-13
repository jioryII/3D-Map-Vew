package dev.maqueta.client.world;

import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Background workers (cores minus two, low priority). Jobs are ordered by priority:
 * lower value runs first, typically the distance to the point the camera looks at.
 */
public final class Workers {
	private static final AtomicLong SEQ = new AtomicLong();
	private static final ThreadPoolExecutor EXECUTOR;

	static {
		int threads = Math.max(1, Runtime.getRuntime().availableProcessors() - 2);
		AtomicInteger n = new AtomicInteger();
		ThreadFactory factory = r -> {
			Thread t = new Thread(r, "Maqueta worker " + n.incrementAndGet());
			t.setDaemon(true);
			t.setPriority(Thread.MIN_PRIORITY + 1);
			return t;
		};
		EXECUTOR = new ThreadPoolExecutor(threads, threads, 30, TimeUnit.SECONDS, new PriorityBlockingQueue<>(), factory);
		EXECUTOR.allowCoreThreadTimeOut(true);
	}

	private Workers() {
	}

	public static void submit(double priority, Runnable job) {
		EXECUTOR.execute(new Job(priority, SEQ.getAndIncrement(), job));
	}

	public static int pending() {
		return EXECUTOR.getQueue().size();
	}

	public static void clearQueue() {
		EXECUTOR.getQueue().clear();
	}

	private record Job(double priority, long seq, Runnable task) implements Runnable, Comparable<Job> {
		@Override
		public void run() {
			try {
				task.run();
			} catch (Throwable t) {
				org.slf4j.LoggerFactory.getLogger("maqueta").warn("Worker job failed", t);
			}
		}

		@Override
		public int compareTo(Job o) {
			int c = Double.compare(priority, o.priority);
			return c != 0 ? c : Long.compare(seq, o.seq);
		}
	}
}
