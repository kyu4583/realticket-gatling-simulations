package simulations.util;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ConcurrentLinkedQueue;

import static simulations.config.Config.DEBUG_LOGGING;

public final class AsyncLogger {

    private static final DateTimeFormatter TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private static final ConcurrentLinkedQueue<String> logQueue = new ConcurrentLinkedQueue<>();
    private static final Thread loggerThread;
    private static volatile boolean loggerRunning = true;

    static {
        loggerThread = new Thread(() -> {
            StringBuilder batch = new StringBuilder(4096);
            while (loggerRunning || !logQueue.isEmpty()) {
                String msg;
                batch.setLength(0);

                int count = 0;
                while (count < 50 && (msg = logQueue.poll()) != null) {
                    batch.append(msg).append('\n');
                    count++;
                }

                if (batch.length() > 0) {
                    System.out.print(batch);
                }

                if (logQueue.isEmpty() && loggerRunning) {
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }, "AsyncLogger");
        loggerThread.setDaemon(true);
        loggerThread.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            loggerRunning = false;
            try {
                loggerThread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
    }

    private AsyncLogger() {}

    private static String timestamp() {
        return LocalDateTime.now().format(TIMESTAMP_FORMATTER);
    }

    public static void log(String message) {
        if (DEBUG_LOGGING) {
            logQueue.offer(timestamp() + " " + message);
        }
    }

    public static void logf(String format, Object... args) {
        if (DEBUG_LOGGING) {
            logQueue.offer(timestamp() + " " + String.format(format, args));
        }
    }
}