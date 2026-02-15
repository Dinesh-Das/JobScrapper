package org.dinesh.util;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

public class MetricsTracker {
    private static final Logger logger = Logger.getLogger(MetricsTracker.class.getName());
    private static final Instant startTime = Instant.now();
    private static final AtomicInteger pagesProcessed = new AtomicInteger(0);
    private static final AtomicInteger jobsScraped = new AtomicInteger(0);
    private static final AtomicInteger jobsSaved = new AtomicInteger(0);
    private static final AtomicInteger jobsInserted = new AtomicInteger(0);
    private static final AtomicInteger jobsUpdated = new AtomicInteger(0);
    private static final AtomicInteger errors = new AtomicInteger(0);
    private static final AtomicInteger duplicatesSkipped = new AtomicInteger(0);
    private static final AtomicLong totalProcessingTime = new AtomicLong(0);
    
    public static void incrementPagesProcessed() {
        pagesProcessed.incrementAndGet();
    }
    
    public static void incrementJobsScraped(int count) {
        jobsScraped.addAndGet(count);
    }
    
    public static void incrementJobsSaved(int count) {
        jobsSaved.addAndGet(count);
    }
    
    public static void incrementJobsInserted(int count) {
        jobsInserted.addAndGet(count);
    }
    
    public static void incrementJobsUpdated(int count) {
        jobsUpdated.addAndGet(count);
    }
    
    public static void incrementErrors() {
        errors.incrementAndGet();
    }
    
    public static void incrementDuplicatesSkipped(int count) {
        duplicatesSkipped.addAndGet(count);
    }
    
    public static void addProcessingTime(long milliseconds) {
        totalProcessingTime.addAndGet(milliseconds);
    }
    
    public static void printMetrics() {
        Duration elapsed = Duration.between(startTime, Instant.now());
        long elapsedSeconds = elapsed.getSeconds();
        
        logger.info("\n=== Performance Metrics ===");
        logger.info("Runtime: " + formatDuration(elapsed));
        logger.info("Pages processed: " + pagesProcessed.get());
        logger.info("Jobs scraped: " + jobsScraped.get());
        logger.info("Jobs saved: " + jobsSaved.get());
        logger.info("  - Inserted: " + jobsInserted.get());
        logger.info("  - Updated: " + jobsUpdated.get());
        logger.info("Duplicates skipped: " + duplicatesSkipped.get());
        logger.info("Errors: " + errors.get());
        
        if (elapsedSeconds > 0) {
            double pagesPerMin = (pagesProcessed.get() * 60.0) / elapsedSeconds;
            double jobsPerMin = (jobsScraped.get() * 60.0) / elapsedSeconds;
            logger.info("\nThroughput:");
            logger.info("  Pages/minute: " + String.format("%.2f", pagesPerMin));
            logger.info("  Jobs/minute: " + String.format("%.2f", jobsPerMin));
        }
        
        if (pagesProcessed.get() > 0) {
            double avgTimePerPage = totalProcessingTime.get() / (double) pagesProcessed.get() / 1000.0;
            logger.info("  Avg time/page: " + String.format("%.2f", avgTimePerPage) + " seconds");
        }
        
        if (jobsScraped.get() > 0) {
            double successRate = (jobsSaved.get() * 100.0) / jobsScraped.get();
            logger.info("\nSuccess rate: " + String.format("%.2f", successRate) + "%");
        }
    }
    
    private static String formatDuration(Duration duration) {
        long totalSeconds = duration.getSeconds();
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        
        if (hours > 0) {
            return String.format("%dh %dm %ds", hours, minutes, seconds);
        } else if (minutes > 0) {
            return String.format("%dm %ds", minutes, seconds);
        } else {
            return String.format("%ds", seconds);
        }
    }
    
    public static int getPagesProcessed() {
        return pagesProcessed.get();
    }
    
    public static int getErrors() {
        return errors.get();
    }
}
