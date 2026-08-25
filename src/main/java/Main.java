import org.dinesh.config.Config;
import org.dinesh.database.BatchDatabaseHandler;
import org.dinesh.database.ResilientConnectionPool;
import org.dinesh.entity.JobListingEntity;
import org.dinesh.process.ChromeDriverManager;
import org.dinesh.scrapper.TwoPhaseNaukriScraper;
import org.dinesh.util.CheckpointStore;
import org.dinesh.util.MetricsTracker;
import org.dinesh.util.Phase1ProgressTracker;
import org.dinesh.util.RobotsTxtChecker;
import io.github.bonigarcia.wdm.WebDriverManager;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.LogManager;
import java.util.logging.Logger;

public class Main {
    private static final Logger logger = Logger.getLogger(Main.class.getName());
    private static volatile boolean shutdownRequested = false;
    private static volatile boolean cleanedUp = false;
    private static final CheckpointStore checkpoint = new CheckpointStore();

    static {
        try (InputStream is = Main.class.getClassLoader().getResourceAsStream("logger.properties")) {
            if (is != null) {
                LogManager.getLogManager().readConfiguration(is);
            } else {
                logger.warning("logger.properties not found, using default logging configuration");
            }
        } catch (IOException e) {
            logger.warning("Failed to load logger.properties: " + e.getMessage());
        }
    }

    public static void main(String[] args) {
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override
            public void run() {
                logger.info("=== Shutdown signal received ===");
                shutdownRequested = true;
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException ignored) {
                }
                cleanup();
            }
        }));

        long startTime = System.currentTimeMillis();

        logger.info("Setting up ChromeDriver...");
        WebDriverManager.chromedriver().setup();
        logger.info("ChromeDriver setup complete");

        logger.info("Checking database connection...");
        if (!ResilientConnectionPool.isHealthy()) {
            logger.severe("Database connection failed! Exiting.");
            System.exit(1);
        }
        logger.info("Database connection healthy");

        // Log robots.txt compliance status
        RobotsTxtChecker.logComplianceWarning();

        if (Config.isCheckDuplicates()) {
            logger.info("Initializing URL cache for duplicate detection...");
            BatchDatabaseHandler.initializeUrlCache();
            logger.info("URL cache initialized");
        }

        checkpoint.load();
        importLegacyCheckpoint();

        int totalPages = Config.getTotalPages();
        int pageOffset = Config.getPageOffset();
        int threadPoolSize = Config.getScraperThreads();

        if (totalPages > 1000) {
            logger.warning("WARNING: Attempting to scrape " + totalPages + " pages!");
            logger.warning("This will take many hours. Consider running in smaller batches.");
            logger.warning("Recommended: 100-500 pages per run");
        }

        logger.info("=== Two-Phase Job Scraper Started ===");
        logger.info("Total pages: " + totalPages);
        logger.info("Thread pool size: " + threadPoolSize);
        logger.info("Batch size: " + Config.getBatchSize());
        logger.info("Headless mode: " + Config.isBrowserHeadless());
        logger.info("Starting from page: " + pageOffset);
        logger.info("Resume URLs already collected: " + checkpoint.urlCount());
        logger.info("Resume pages already done: " + checkpoint.donePageCount());
        logger.info("Connection pool: " + ResilientConnectionPool.getStats());

        if (totalPages > 0 && !Config.isSkipPhase1()) {
            runPhase1(pageOffset, totalPages, threadPoolSize);
        } else {
            logger.info("Skipping Phase 1 ("
                    + (Config.isSkipPhase1() ? "scraper.skip.phase1=true" : "total.pages=0") + ")");
        }

        Set<String> allUrls = new LinkedHashSet<String>(checkpoint.getUrls());
        Set<String> urlsToScrape = new LinkedHashSet<String>(allUrls);

        if (Config.isCheckDuplicates()) {
            Set<String> existingUrls = BatchDatabaseHandler.getExistingUrls(new ArrayList<String>(allUrls));
            if (Config.isUpsertDuplicates()) {
                logger.info("UPSERT mode: Will update " + existingUrls.size() + " existing + insert "
                        + (urlsToScrape.size() - existingUrls.size()) + " new jobs");
            } else {
                urlsToScrape.removeAll(existingUrls);
                logger.info("Filtered out " + existingUrls.size() + " existing URLs, "
                        + urlsToScrape.size() + " new URLs to scrape");
            }
        }

        int skippedPhase2 = 0;
        Set<String> remaining = new LinkedHashSet<String>();
        for (String url : urlsToScrape) {
            if (checkpoint.isPhase2Done(url)) {
                skippedPhase2++;
            } else {
                remaining.add(url);
            }
        }
        urlsToScrape = remaining;
        if (skippedPhase2 > 0) {
            logger.info("Skipped " + skippedPhase2 + " URLs already attempted in a previous Phase 2 run");
        }

        if (urlsToScrape.isEmpty()) {
            logger.info("No URLs to scrape, exiting");
            cleanup();
            return;
        }

        runPhase2(urlsToScrape, threadPoolSize);

        long duration = (System.currentTimeMillis() - startTime) / 1000;
        logger.info("=== Scraping Completed ===");
        logger.info("Total time: " + duration + " seconds (" + (duration / 60) + " minutes)");
        logger.info("Phase 1: Collected " + allUrls.size() + " total URLs");
        logger.info("Phase 2: Attempted " + urlsToScrape.size() + " unique URLs");
        logger.info("Phase 2: Skipped " + skippedPhase2 + " previously attempted URLs");
        MetricsTracker.printMetrics();
        logger.info("Final pool stats: " + ResilientConnectionPool.getStats());

        // Detailed error summary
        if (MetricsTracker.getErrors() > 0) {
            logger.warning("Total errors encountered: " + MetricsTracker.getErrors());
            logger.warning("Check application.log for detailed error information");
        }

        cleanup();
    }

    private static void runPhase1(int pageOffset, int totalPages, int threadPoolSize) {
        int lastPage = pageOffset + totalPages - 1;
        List<Integer> remaining = new ArrayList<Integer>();
        Set<Integer> done = checkpoint.getDonePages();
        for (int page = pageOffset; page <= lastPage; page++) {
            if (!done.contains(page)) {
                remaining.add(page);
            }
        }

        if (remaining.isEmpty()) {
            logger.info("Phase 1 skipped: all " + totalPages + " pages already checkpointed");
            return;
        }

        logger.info("=== PHASE 1: Collecting URLs from search pages ===");
        logger.info("Pages remaining: " + remaining.size() + " (already done: " + done.size() + ")");

        ExecutorService executor = Executors.newFixedThreadPool(threadPoolSize);
        CompletionService<PageResult> completion = new ExecutorCompletionService<PageResult>(executor);
        final ThreadLocal<Integer> threadPageCount = ThreadLocal.withInitial(new java.util.function.Supplier<Integer>() {
            @Override
            public Integer get() {
                return 0;
            }
        });
        int nextIndex = 0;
        int nextResultIndex = 0;
        int submitted = 0;
        int completed = 0;
        int processed = 0;
        boolean stopPhase1 = false;
        Map<Integer, PageResult> completedByPage = new HashMap<Integer, PageResult>();
        Phase1ProgressTracker progressTracker = new Phase1ProgressTracker(
                Config.getEmptyPageLimit(), Config.getCircuitBreakerThreshold());
        int prime = Math.min(threadPoolSize, remaining.size());

        while (nextIndex < prime && !shutdownRequested) {
            submitPhase1Page(completion, remaining.get(nextIndex++), threadPageCount);
            submitted++;
        }

        logger.info("Phase 1 timeout budget: "
                + Math.max(2, (remaining.size() / 100) + 1) + " hours");

        while (completed < submitted && !shutdownRequested) {
            try {
                Future<PageResult> future = completion.poll(2, TimeUnit.SECONDS);
                if (future == null) {
                    continue;
                }

                PageResult result = future.get();
                completed++;
                completedByPage.put(Integer.valueOf(result.page), result);

                // CompletionService returns results out of order. Apply checkpoint and
                // consecutive-page decisions only in ascending configured page order.
                while (nextResultIndex < remaining.size()) {
                    int expectedPage = remaining.get(nextResultIndex).intValue();
                    PageResult ordered = completedByPage.remove(Integer.valueOf(expectedPage));
                    if (ordered == null) {
                        break;
                    }
                    nextResultIndex++;
                    processed++;

                    boolean confirmedEmpty = ordered.success
                            && (ordered.urls == null || ordered.urls.isEmpty());
                    Phase1ProgressTracker.Decision decision = progressTracker.record(
                            ordered.page, ordered.success, confirmedEmpty);
                    stopPhase1 = decision != Phase1ProgressTracker.Decision.CONTINUE;

                    if (ordered.success) {
                        MetricsTracker.incrementPagesProcessed();

                        if (confirmedEmpty) {
                            logger.info("Confirmed empty page " + ordered.page + " ("
                                    + progressTracker.getConsecutiveEmpty() + "/"
                                    + Config.getEmptyPageLimit() + " consecutive)");
                            if (decision == Phase1ProgressTracker.Decision.PAUSE_AFTER_EMPTY_PAGES) {
                                logger.warning("Pausing Phase 1 submission after "
                                        + progressTracker.getConsecutiveEmpty()
                                        + " confirmed consecutive no-result pages");
                            }
                        } else {
                            checkpoint.addUrls(ordered.urls);
                            checkpoint.markPageDone(ordered.page);
                        }
                    } else {
                        logger.warning("Page " + ordered.page + " was not checkpointed: " + ordered.errorMessage);
                        if (decision == Phase1ProgressTracker.Decision.PAUSE_AFTER_FAILURES) {
                            logger.severe("Pausing Phase 1 after "
                                    + progressTracker.getConsecutiveFailures()
                                    + " consecutive page failures; no failed pages were checkpointed");
                        }
                    }
                }

                if (processed > 0 && (processed % 25 == 0 || processed == remaining.size())) {
                    logger.info("Phase 1 progress: " + processed + "/" + remaining.size()
                            + " pages, " + checkpoint.urlCount() + " unique URLs");
                }

                boolean canSubmitMore = !stopPhase1
                        && !shutdownRequested
                        && nextIndex < remaining.size();
                if (canSubmitMore) {
                    submitPhase1Page(completion, remaining.get(nextIndex++), threadPageCount);
                    submitted++;
                }
            } catch (Exception e) {
                completed++;
                MetricsTracker.incrementErrors();
                logger.warning("Phase 1 task failed: " + e.getMessage());
            }
        }

        executor.shutdown();
        try {
            if (!executor.awaitTermination(2, TimeUnit.MINUTES)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        logger.info("Cleaning up Phase 1 drivers...");
        TwoPhaseNaukriScraper.cleanupAllThreadDrivers();
        logger.info("Phase 1 complete: " + checkpoint.urlCount() + " unique URLs in checkpoint");
    }

    private static void submitPhase1Page(CompletionService<PageResult> completion,
                                         final int pageNumber,
                                         final ThreadLocal<Integer> threadPageCount) {
        completion.submit(new Callable<PageResult>() {
            @Override
            public PageResult call() {
                return collectOnePage(pageNumber, threadPageCount);
            }
        });
    }

    private static PageResult collectOnePage(int pageNumber, ThreadLocal<Integer> threadPageCount) {
        PageResult result = new PageResult();
        result.page = pageNumber;
        result.success = false;
        result.urls = Collections.emptySet();
        result.errorMessage = "Unknown page failure";
        try {
            if (shutdownRequested) {
                return result;
            }
            int count = threadPageCount.get().intValue();
            int restartEvery = Math.max(20, Config.getDriverRestartEvery());
            if (count > 0 && count % restartEvery == 0) {
                logger.info(Thread.currentThread().getName() + ": Restarting driver after " + count + " pages");
                TwoPhaseNaukriScraper.cleanupThreadDriver();
            }
            threadPageCount.set(Integer.valueOf(count + 1));

            TwoPhaseNaukriScraper scraper = new TwoPhaseNaukriScraper();
            result.urls = scraper.collectUrlsFromPage(pageNumber);
            result.success = true;
            result.errorMessage = "";
        } catch (Exception e) {
            logger.severe("Failed to collect URLs from page " + pageNumber + ": " + e.getMessage());
            result.errorMessage = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            MetricsTracker.incrementErrors();
        }
        return result;
    }

    private static void runPhase2(Set<String> urlsToScrape, int threadPoolSize) {
        logger.info("=== PHASE 2: Scraping job details for " + urlsToScrape.size() + " unique URLs ===");

        final AtomicInteger threadCounter = new AtomicInteger(0);
        ExecutorService executor = Executors.newFixedThreadPool(threadPoolSize, new java.util.concurrent.ThreadFactory() {
            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable);
                thread.setName("Phase2-Worker-" + threadCounter.incrementAndGet());
                return thread;
            }
        });

        final TwoPhaseNaukriScraper scraper = new TwoPhaseNaukriScraper();
        final int totalUrls = urlsToScrape.size();
        final ThreadLocal<Integer> threadUrlCount = ThreadLocal.withInitial(new java.util.function.Supplier<Integer>() {
            @Override
            public Integer get() {
                return 0;
            }
        });
        final int restartEvery = Math.max(20, Config.getDriverRestartEvery());
        CompletionService<TwoPhaseNaukriScraper.ScrapeResult> completion =
                new ExecutorCompletionService<TwoPhaseNaukriScraper.ScrapeResult>(executor);
        int submitted = 0;

        for (final String url : urlsToScrape) {
            if (shutdownRequested) {
                logger.info("Shutdown requested, stopping job detail scraping");
                break;
            }
            completion.submit(new Callable<TwoPhaseNaukriScraper.ScrapeResult>() {
                @Override
                public TwoPhaseNaukriScraper.ScrapeResult call() {
                    try {
                        if (shutdownRequested) {
                            return TwoPhaseNaukriScraper.ScrapeResult.failed(url, "Shutdown requested");
                        }
                        int count = threadUrlCount.get().intValue();
                        if (count > 0 && count % restartEvery == 0) {
                            logger.info(Thread.currentThread().getName()
                                    + ": Restarting driver after " + count + " URLs...");
                            TwoPhaseNaukriScraper.cleanupThreadDriver();
                        }
                        threadUrlCount.set(Integer.valueOf(count + 1));
                        return scraper.scrapeJobDetails(url);
                    } catch (Exception e) {
                        MetricsTracker.incrementErrors();
                        return TwoPhaseNaukriScraper.ScrapeResult.failed(url,
                                e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                    }
                }
            });
            submitted++;
        }

        executor.shutdown();
        List<JobListingEntity> pendingJobs = new ArrayList<JobListingEntity>();
        int completed = 0;
        try {
            long estimatedMinutes = (totalUrls * 3L) / 60;
            long timeoutHours = Math.max(2, estimatedMinutes / 60 + 1);
            logger.info("Phase 2 timeout set to " + timeoutHours + " hours (estimated "
                    + estimatedMinutes + " minutes)");

            long deadline = System.nanoTime() + TimeUnit.HOURS.toNanos(timeoutHours);
            while (completed < submitted && !shutdownRequested) {
                long remainingNanos = deadline - System.nanoTime();
                if (remainingNanos <= 0L) {
                    break;
                }
                Future<TwoPhaseNaukriScraper.ScrapeResult> future =
                        completion.poll(Math.min(remainingNanos, TimeUnit.SECONDS.toNanos(2)), TimeUnit.NANOSECONDS);
                if (future == null) {
                    continue;
                }

                TwoPhaseNaukriScraper.ScrapeResult result = future.get();
                completed++;
                if (result.getStatus() == TwoPhaseNaukriScraper.ScrapeStatus.SCRAPED) {
                    pendingJobs.add(result.getJob());
                    if (pendingJobs.size() >= Config.getBatchSize()) {
                        persistAndCheckpoint(pendingJobs);
                    }
                } else if (result.getStatus() == TwoPhaseNaukriScraper.ScrapeStatus.EXPIRED) {
                    checkpoint.markPhase2Done(result.getUrl());
                } else {
                    logger.warning("Phase 2 URL left retryable: " + result.getUrl() + " — " + result.getMessage());
                }

                if (completed % 100 == 0 || completed == submitted) {
                    logger.info("Phase 2 progress: " + completed + "/" + submitted + " URLs processed ("
                            + String.format("%.1f", (completed * 100.0 / submitted)) + "%)");
                }
            }

            if (completed < submitted) {
                logger.severe("Phase 2 timeout after " + timeoutHours + " hours, forcing shutdown...");
                executor.shutdownNow();
            } else if (!executor.awaitTermination(1, TimeUnit.MINUTES)) {
                executor.shutdownNow();
            }
        } catch (Exception e) {
            logger.severe("Phase 2 coordinator failed: " + e.getMessage());
            executor.shutdownNow();
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }

        logger.info("Cleaning up thread-local drivers...");
        TwoPhaseNaukriScraper.cleanupAllThreadDrivers();
        persistAndCheckpoint(pendingJobs);
    }

    private static void persistAndCheckpoint(List<JobListingEntity> pendingJobs) {
        if (pendingJobs == null || pendingJobs.isEmpty()) {
            return;
        }

        List<JobListingEntity> toSave = new ArrayList<JobListingEntity>(pendingJobs);
        boolean isUpsert = Config.isUpsertDuplicates();
        int saved = BatchDatabaseHandler.saveJobsBatch(toSave, isUpsert);
        MetricsTracker.incrementJobsSaved(saved);
        if (!isUpsert) {
            MetricsTracker.incrementJobsInserted(saved);
        }

        if (saved == toSave.size()) {
            for (JobListingEntity job : toSave) {
                checkpoint.markPhase2Done(job.getUrl());
            }
            logger.info("Persisted and checkpointed " + saved + " jobs");
        } else {
            logger.severe("Database persisted " + saved + "/" + toSave.size()
                    + " scraped jobs; the batch was not checkpointed and will be reconciled on the next run");
        }
        pendingJobs.clear();
    }

    private static void importLegacyCheckpoint() {
        java.io.File extra = new java.io.File("1urls_checkpoint.txt");
        if (!extra.exists()) {
            return;
        }
        logger.info("Merging legacy checkpoint 1urls_checkpoint.txt");
        Set<String> extraUrls = new LinkedHashSet<String>();
        java.io.BufferedReader reader = null;
        try {
            reader = new java.io.BufferedReader(new java.io.FileReader(extra));
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty()) {
                    extraUrls.add(trimmed);
                }
            }
            int added = checkpoint.addUrls(extraUrls);
            logger.info("Imported " + added + " new URLs from 1urls_checkpoint.txt");
        } catch (Exception e) {
            logger.warning("Failed to import 1urls_checkpoint.txt: " + e.getMessage());
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static synchronized void cleanup() {
        if (cleanedUp) {
            return;
        }
        cleanedUp = true;
        logger.info("Cleaning up resources...");
        try {
            TwoPhaseNaukriScraper.cleanupAllThreadDrivers();
        } catch (Exception e) {
            logger.warning("Error cleaning drivers: " + e.getMessage());
        }
        try {
            ChromeDriverManager.shutdown();
        } catch (Exception e) {
            logger.warning("Error shutting down ChromeDriverManager: " + e.getMessage());
        }
        try {
            ResilientConnectionPool.close();
        } catch (Exception e) {
            logger.warning("Error closing connection pool: " + e.getMessage());
        }
    }

    private static class PageResult {
        int page;
        boolean success;
        Set<String> urls;
        String errorMessage;
    }
}
