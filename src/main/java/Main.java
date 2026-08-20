import org.dinesh.config.Config;
import org.dinesh.database.BatchDatabaseHandler;
import org.dinesh.database.ResilientConnectionPool;
import org.dinesh.process.ChromeDriverManager;
import org.dinesh.scrapper.TwoPhaseNaukriScraper;
import org.dinesh.util.CheckpointStore;
import org.dinesh.util.MetricsTracker;
import org.dinesh.util.RobotsTxtChecker;
import io.github.bonigarcia.wdm.WebDriverManager;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.LogManager;
import java.util.logging.Logger;

public class Main {
    private static final Logger logger = Logger.getLogger(Main.class.getName());
    private static volatile boolean shutdownRequested = false;
    private static volatile boolean cleanedUp = false;
    private static volatile TwoPhaseNaukriScraper sharedScraper;
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
        AtomicInteger consecutiveEmpty = new AtomicInteger(0);
        AtomicBoolean stopPhase1 = new AtomicBoolean(false);
        AtomicInteger completedCount = new AtomicInteger(0);

        int nextIndex = 0;
        int submitted = 0;
        int completed = 0;
        int inFlight = 0;
        int prime = Math.min(threadPoolSize, remaining.size());

        while (nextIndex < prime && !shutdownRequested) {
            submitPhase1Page(completion, remaining.get(nextIndex++), threadPageCount);
            submitted++;
            inFlight++;
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
                inFlight--;
                int doneSoFar = completedCount.incrementAndGet();

                if (result.success) {
                    checkpoint.addUrls(result.urls);
                    checkpoint.markPageDone(result.page);
                    MetricsTracker.incrementPagesProcessed();

                    if (result.urls == null || result.urls.isEmpty()) {
                        int empty = consecutiveEmpty.incrementAndGet();
                        logger.info("Empty page " + result.page + " (" + empty + "/" + Config.getEmptyPageLimit() + " consecutive)");
                        if (empty >= Config.getEmptyPageLimit()) {
                            stopPhase1.set(true);
                            logger.warning("Stopping Phase 1 after " + empty
                                    + " consecutive empty pages (Naukri likely ran out of results)");
                        }
                    } else {
                        consecutiveEmpty.set(0);
                    }
                }

                if (doneSoFar % 25 == 0 || doneSoFar == remaining.size()) {
                    logger.info("Phase 1 progress: " + doneSoFar + "/" + remaining.size()
                            + " pages, " + checkpoint.urlCount() + " unique URLs");
                }

                boolean canSubmitMore = !stopPhase1.get()
                        && !shutdownRequested
                        && nextIndex < remaining.size();
                if (canSubmitMore) {
                    submitPhase1Page(completion, remaining.get(nextIndex++), threadPageCount);
                    submitted++;
                    inFlight++;
                }
            } catch (Exception e) {
                completed++;
                inFlight--;
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
        } catch (Exception e) {
            logger.severe("Failed to collect URLs from page " + pageNumber + ": " + e.getMessage());
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

        sharedScraper = new TwoPhaseNaukriScraper();
        final TwoPhaseNaukriScraper scraper = sharedScraper;
        final AtomicInteger processedCount = new AtomicInteger(0);
        final int totalUrls = urlsToScrape.size();
        final ThreadLocal<Integer> threadUrlCount = ThreadLocal.withInitial(new java.util.function.Supplier<Integer>() {
            @Override
            public Integer get() {
                return 0;
            }
        });
        final int restartEvery = Math.max(20, Config.getDriverRestartEvery());

        for (final String url : urlsToScrape) {
            if (shutdownRequested) {
                logger.info("Shutdown requested, stopping job detail scraping");
                break;
            }
            executor.submit(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (shutdownRequested) {
                            return;
                        }
                        int count = threadUrlCount.get().intValue();
                        if (count > 0 && count % restartEvery == 0) {
                            logger.info(Thread.currentThread().getName()
                                    + ": Restarting driver after " + count + " URLs...");
                            TwoPhaseNaukriScraper.cleanupThreadDriver();
                        }
                        threadUrlCount.set(Integer.valueOf(count + 1));

                        scraper.scrapeJobDetails(url);
                        checkpoint.markPhase2Done(url);

                        int processed = processedCount.incrementAndGet();
                        if (processed % 100 == 0 || processed == totalUrls) {
                            logger.info("Phase 2 progress: " + processed + "/" + totalUrls + " URLs processed ("
                                    + String.format("%.1f", (processed * 100.0 / totalUrls)) + "%)");
                        }
                    } catch (Exception e) {
                        logger.warning("Failed to scrape job details for " + url + ": " + e.getMessage());
                    }
                }
            });
        }

        executor.shutdown();
        try {
            long estimatedMinutes = (totalUrls * 3L) / 60;
            long timeoutHours = Math.max(2, estimatedMinutes / 60 + 1);
            logger.info("Phase 2 timeout set to " + timeoutHours + " hours (estimated "
                    + estimatedMinutes + " minutes)");
            if (!executor.awaitTermination(timeoutHours, TimeUnit.HOURS)) {
                logger.severe("Phase 2 timeout after " + timeoutHours + " hours, forcing shutdown...");
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            logger.severe("Phase 2 interrupted");
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        logger.info("Cleaning up thread-local drivers...");
        TwoPhaseNaukriScraper.cleanupAllThreadDrivers();
        logger.info("Flushing final batch...");
        scraper.flushFinalBatch();
        sharedScraper = null;
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
            if (sharedScraper != null) {
                sharedScraper.flushFinalBatch();
            }
        } catch (Exception e) {
            logger.warning("Error flushing final batch: " + e.getMessage());
        }
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
    }
}
