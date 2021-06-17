package regencwg;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class ThreadUtils {

	private static final Executor EXECUTOR = new ThreadPoolExecutor(0, 4,
			1000L, TimeUnit.MILLISECONDS,
			new LinkedBlockingQueue<>(),
			r -> {
				Thread thread = new Thread(r);
				thread.setName("ReGenCWG background thread");
				thread.setDaemon(true);
				return thread;
			});

	public static Executor backgroundExecutor() {
		return EXECUTOR;
	}
}
