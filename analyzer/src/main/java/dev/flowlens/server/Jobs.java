package dev.flowlens.server;

import dev.flowlens.FlowAnalyzer;
import dev.flowlens.model.Graph;
import dev.flowlens.source.AnalysisOptions;

import java.nio.file.Path;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Background analysis jobs with progress, cancellation, and a parse cache per project. */
final class Jobs {

    static final class Job {
        final String id = UUID.randomUUID().toString();
        final AtomicBoolean cancel = new AtomicBoolean();
        volatile long finished;              // 0 while running
        volatile String state = "running";   // running | done | error | cancelled
        volatile String phase = "queued";
        volatile int done;
        volatile int total;
        volatile String error;
        volatile Graph graph;
    }

    private static final int MAX_CACHES = 4;
    private static final long KEEP_MS = 10 * 60 * 1000;

    private final java.util.concurrent.ScheduledExecutorService janitor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "flowlens-jobs-cleanup");
        t.setDaemon(true);
        return t;
    });

    Jobs() {
        janitor.scheduleWithFixedDelay(this::cleanup, 1, 1, java.util.concurrent.TimeUnit.MINUTES);
    }

    private final ExecutorService pool = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "flowlens-analysis");
        t.setDaemon(true);
        return t;
    });
    private final Map<String, Job> jobs = new ConcurrentHashMap<>();
    private final Map<String, FlowAnalyzer.ParseCache> caches = new LinkedHashMap<>(16, 0.75f, true);
    private final Map<String, Object> locks = new ConcurrentHashMap<>();

    Job start(Path root, AnalysisOptions options) {
        Job job = new Job();
        jobs.put(job.id, job);
        pool.submit(() -> run(job, root, options));
        return job;
    }

    /** Synchronous analysis (used by POST /api/analyze and the MCP server). */
    Graph analyzeNow(Path root, AnalysisOptions options) throws Exception {
        Job job = new Job();
        run(job, root, options);
        if ("error".equals(job.state)) throw new Exception(job.error);
        return job.graph;
    }

    private void run(Job job, Path root, AnalysisOptions options) {
        // One parse cache per project folder: entries are validated by content hash, so option sets
        // and git refs can share it (a cache per option set would multiply memory use).
        String key = root.toAbsolutePath().normalize().toString();
        Object lock = locks.computeIfAbsent(key, k -> new Object());
        synchronized (lock) {
            try {
                FlowAnalyzer.ParseCache cache = cacheFor(key);
                Graph g = new FlowAnalyzer().analyze(root, options, new FlowAnalyzer.Progress() {
                    @Override public void update(String phase, int done, int total) {
                        job.phase = phase;
                        job.done = done;
                        job.total = total;
                    }

                    @Override public boolean cancelled() {
                        return job.cancel.get();
                    }
                }, cache);
                job.graph = g;
                job.state = "done";
                System.out.printf("Indexed %s: %d nodes, %d edges in %d ms%n", root, g.nodes.size(), g.edges.size(), g.stats.durationMs);
            } catch (FlowAnalyzer.CancelledException e) {
                job.state = "cancelled";
            } catch (Throwable e) {
                job.error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                job.state = "error";
                System.err.println("[error] analysis failed: " + job.error);
            } finally {
                job.finished = System.currentTimeMillis();
            }
        }
    }

    private synchronized FlowAnalyzer.ParseCache cacheFor(String key) {
        FlowAnalyzer.ParseCache c = caches.get(key);
        if (c == null) {
            c = new FlowAnalyzer.ParseCache();
            caches.put(key, c);
            while (caches.size() > MAX_CACHES) {
                Iterator<String> it = caches.keySet().iterator();
                it.next();
                it.remove();
            }
        }
        return c;
    }

    Job get(String id) {
        cleanup();
        return jobs.get(id);
    }

    boolean cancel(String id) {
        Job j = jobs.get(id);
        if (j == null) return false;
        j.cancel.set(true);
        return true;
    }

    /** Drop results nobody fetched within KEEP_MS of the job finishing (running jobs are never dropped). */
    private void cleanup() {
        long now = System.currentTimeMillis();
        jobs.values().removeIf(j -> j.finished > 0 && now - j.finished > KEEP_MS);
    }
}
