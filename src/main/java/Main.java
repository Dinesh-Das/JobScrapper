import org.dinesh.config.Config;
import org.dinesh.database.ResilientConnectionPool;
import org.dinesh.database.BatchDatabaseHandler;
import org.dinesh.scrapper.TwoPhaseNaukriScraper;
import org.dinesh.util.MetricsTracker;
import org.dinesh.process.ChromeDriverManager;
import io.github.bonigarcia.wdm.WebDriverManager;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.LogManager;
import java.util.logging.Logger;

public class Main {
    private static final Logger logger = Logger.getLogger(Main.class.getName());
    private static volatile boolean shutdownRequested = false;
    
    static {
        // Load logger configuration
        try (InputStream is = Main.class.getClassLoader().getResourceAsStream("logger.properties")) {
            if (is != null) {
                LogManager.getLogManager().readConfiguration(is);
            } else {
                System.err.println("Warning: logger.properties not found, using default logging configuration");
            }
        } catch (IOException e) {
            System.err.println("Warning: Failed to load logger.properties: " + e.getMessage());
        }
    }
    
    public static void main(String[] args) {
        // Register shutdown hook for graceful termination
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("\n=== Shutdown signal received ===");
            shutdownRequested = true;
            logger.info("Waiting for threads to complete...");
            
            // Cleanup managers
            try {
                ChromeDriverManager.shutdown();
                ResilientConnectionPool.close();
            } catch (Exception e) {
                logger.warning("Error during shutdown: " + e.getMessage());
            }
        }));
        
        long startTime = System.currentTimeMillis();
        
        // Setup WebDriverManager once at startup
        logger.info("Setting up ChromeDriver...");
        WebDriverManager.chromedriver().setup();
        logger.info("ChromeDriver setup complete");
        
        // Check database health
        logger.info("Checking database connection...");
        if (!ResilientConnectionPool.isHealthy()) {
            logger.severe("Database connection failed! Exiting.");
            System.exit(1);
        }
        logger.info("Database connection healthy");
        
        // Initialize URL cache for duplicate detection
        if (Config.isCheckDuplicates()) {
            logger.info("Initializing URL cache for duplicate detection...");
            BatchDatabaseHandler.initializeUrlCache();
            logger.info("URL cache initialized");
        }
        
        int totalPages = Config.getTotalPages();
        int pageOffset = Config.getPageOffset();
        int threadPoolSize = Config.getScraperThreads();
        
        // Safety check for large page counts
        if (totalPages > 1000) {
            logger.warning("WARNING: Attempting to scrape " + totalPages + " pages!");
            logger.warning("This will take many hours. Consider running in smaller batches.");
            logger.warning("Recommended: 100-500 pages per run");
            // Uncomment to enforce limit:
            // throw new RuntimeException("Page count too high. Please run in batches.");
        }
        
        logger.info("=== Two-Phase Job Scraper Started ===");
        logger.info("Total pages: " + totalPages);
        logger.info("Thread pool size: " + threadPoolSize);
        logger.info("Batch size: " + Config.getBatchSize());
        logger.info("Headless mode: " + Config.isBrowserHeadless());
        logger.info("Starting from page: " + pageOffset);
        logger.info("Connection pool: " + ResilientConnectionPool.getStats());
        
        // PHASE 1: Collect all URLs from search pages (fast, lightweight)
        logger.info("\n=== PHASE 1: Collecting URLs from search pages ===");
        Set<String> allUrls = ConcurrentHashMap.newKeySet();
        ExecutorService phase1Executor = Executors.newFixedThreadPool(threadPoolSize);
        
        // Track pages processed per thread for driver restart
        java.util.concurrent.atomic.AtomicInteger pagesProcessedCount = new java.util.concurrent.atomic.AtomicInteger(0);
        
        for (int i = 0; i < totalPages; i++) {
            final int pageNumber = pageOffset + i;
            
            if (shutdownRequested) {
                logger.info("Shutdown requested, stopping URL collection");
                break;
            }
            
            phase1Executor.submit(() -> {
                try {
                    if (shutdownRequested) return;
                    
                    TwoPhaseNaukriScraper scraper = new TwoPhaseNaukriScraper();
                    Set<String> pageUrls = scraper.collectUrlsFromPage(pageNumber);
                    allUrls.addAll(pageUrls);
                    logger.info("Page " + pageNumber + ": Collected " + pageUrls.size() + " URLs");
                    MetricsTracker.incrementPagesProcessed(); // Track Phase 1 pages
                    
                    // Restart driver every 100 pages to prevent crashes
                    int processed = pagesProcessedCount.incrementAndGet();
                    if (processed % 100 == 0) {
                        logger.info("Restarting driver after " + processed + " pages...");
                        TwoPhaseNaukriScraper.cleanupThreadDriver();
                    }
                    
                } catch (Exception e) {
                    logger.severe("Failed to collect URLs from page " + pageNumber + ": " + e.getMessage());
                    MetricsTracker.incrementErrors();
                }
            });
        }
        
        phase1Executor.shutdown();
        try {
            // Increase timeout for large page counts (10 hours for 7100 pages)
            long timeoutHours = Math.max(2, (totalPages / 100) + 1);
            logger.info("Phase 1 timeout set to " + timeoutHours + " hours");
            
            if (!phase1Executor.awaitTermination(timeoutHours, TimeUnit.HOURS)) {
                logger.severe("Phase 1 timeout after " + timeoutHours + " hours, forcing shutdown...");
                phase1Executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            logger.severe("Phase 1 interrupted");
            phase1Executor.shutdownNow();
        }
        
        // Cleanup all Phase 1 drivers
        logger.info("Cleaning up Phase 1 drivers...");
        TwoPhaseNaukriScraper.cleanupAllThreadDrivers();
        
        logger.info("Phase 1 complete: Collected " + allUrls.size() + " total URLs");
        
        // Filter out URLs already in database (if duplicate check enabled)
        Set<String> urlsToScrape = allUrls;
        if (Config.isCheckDuplicates()) {
            Set<String> existingUrls = BatchDatabaseHandler.getExistingUrls(new ArrayList<>(allUrls));
            urlsToScrape = new HashSet<>(allUrls);
            
            if (Config.isUpsertDuplicates()) {
                // Keep all URLs for upsert
                logger.info("UPSERT mode: Will update " + existingUrls.size() + " existing + insert " + 
                           (urlsToScrape.size() - existingUrls.size()) + " new jobs");
            } else {
                // Remove existing URLs
                urlsToScrape.removeAll(existingUrls);
                logger.info("Filtered out " + existingUrls.size() + " existing URLs, " + 
                           urlsToScrape.size() + " new URLs to scrape");
            }
        }
        
        if (urlsToScrape.isEmpty()) {
            logger.info("No URLs to scrape, exiting");
            ChromeDriverManager.shutdown();
            ResilientConnectionPool.close();
            return;
        }
        
        // PHASE 2: Scrape job details for unique URLs (no redundant work!)
        logger.info("\n=== PHASE 2: Scraping job details for " + urlsToScrape.size() + " unique URLs ===");
        
        // Create thread pool with custom thread factory for cleanup
        final java.util.concurrent.atomic.AtomicInteger threadCounter = new java.util.concurrent.atomic.AtomicInteger(0);
        ExecutorService phase2Executor = Executors.newFixedThreadPool(threadPoolSize, runnable -> {
            Thread thread = new Thread(runnable);
            thread.setName("Phase2-Worker-" + threadCounter.incrementAndGet());
            return thread;
        });
        
        List<String> urlList = new ArrayList<>(urlsToScrape);
        
        // Create a shared scraper instance to accumulate batches
        TwoPhaseNaukriScraper sharedScraper = new TwoPhaseNaukriScraper();
        
        // Progress tracking
        java.util.concurrent.atomic.AtomicInteger processedCount = new java.util.concurrent.atomic.AtomicInteger(0);
        java.util.concurrent.atomic.AtomicInteger urlsProcessedCount = new java.util.concurrent.atomic.AtomicInteger(0);
        int totalUrls = urlList.size();
        
        for (String url : urlList) {
            if (shutdownRequested) {
                logger.info("Shutdown requested, stopping job detail scraping");
                break;
            }
            
            phase2Executor.submit(() -> {
                try {
                    if (shutdownRequested) return;
                    
                    sharedScraper.scrapeJobDetails(url);
                    
                    int processed = processedCount.incrementAndGet();
                    if (processed % 50 == 0 || processed == totalUrls) {
                        logger.info("Phase 2 progress: " + processed + "/" + totalUrls + " URLs processed (" + 
                                   String.format("%.1f", (processed * 100.0 / totalUrls)) + "%)");
                    }
                    
                    // Restart driver every 200 URLs to prevent crashes
                    int urlsProcessed = urlsProcessedCount.incrementAndGet();
                    if (urlsProcessed % 200 == 0) {
                        logger.info("Restarting driver after " + urlsProcessed + " URLs...");
                        TwoPhaseNaukriScraper.cleanupThreadDriver();
                    }
                    
                } catch (Exception e) {
                    logger.warning("Failed to scrape job details for " + url + ": " + e.getMessage());
                }
            });
        }
        
        phase2Executor.shutdown();
        try {
            // Increase timeout for large URL counts (estimate 3 seconds per URL)
            long estimatedMinutes = (totalUrls * 3) / 60;
            long timeoutHours = Math.max(2, estimatedMinutes / 60 + 1);
            logger.info("Phase 2 timeout set to " + timeoutHours + " hours (estimated " + estimatedMinutes + " minutes)");
            
            if (!phase2Executor.awaitTermination(timeoutHours, TimeUnit.HOURS)) {
                logger.severe("Phase 2 timeout after " + timeoutHours + " hours, forcing shutdown...");
                phase2Executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            logger.severe("Phase 2 interrupted");
            phase2Executor.shutdownNow();
        }
        
        // Cleanup all thread-local drivers after executor shutdown
        logger.info("Cleaning up thread-local drivers...");
        TwoPhaseNaukriScraper.cleanupAllThreadDrivers();
        
        // Flush any remaining jobs in the batch
        logger.info("Flushing final batch...");
        sharedScraper.flushFinalBatch();
        
        // Cleanup
        logger.info("Cleaning up resources...");
        ChromeDriverManager.shutdown();
        ResilientConnectionPool.close();
        
        long endTime = System.currentTimeMillis();
        long duration = (endTime - startTime) / 1000;
        
        logger.info("\n=== Scraping Completed ===");
        logger.info("Total time: " + duration + " seconds (" + (duration / 60) + " minutes)");
        logger.info("Phase 1: Collected " + allUrls.size() + " total URLs");
        logger.info("Phase 2: Scraped " + urlsToScrape.size() + " unique URLs");
        
        // Show performance metrics
        MetricsTracker.printMetrics();
        
        // Final pool stats
        logger.info("Final pool stats: " + ResilientConnectionPool.getStats());
    }
}
