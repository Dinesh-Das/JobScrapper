import org.dinesh.config.Config;
import org.dinesh.database.ConnectionPool;
import org.dinesh.scrapper.OptimizedNaukriScraper;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class Main {
    public static void main(String[] args) {
        long startTime = System.currentTimeMillis();
        
        int totalPages = Config.getTotalPages();
        int pageOffset = Config.getPageOffset();
        int threadPoolSize = Config.getScraperThreads();
        
        System.out.println("=== Job Scraper Started ===");
        System.out.println("Total pages: " + totalPages);
        System.out.println("Thread pool size: " + threadPoolSize);
        System.out.println("Batch size: " + Config.getBatchSize());
        System.out.println("Headless mode: " + Config.isBrowserHeadless());
        
        // Use ExecutorService for better thread management
        ExecutorService executor = Executors.newFixedThreadPool(threadPoolSize);
        
        for (int i = 0; i < totalPages; i++) {
            final int pageNumber = i * pageOffset;
            
            executor.submit(() -> {
                System.out.println("Thread " + Thread.currentThread().getName() + 
                                 " starting scrape from page: " + pageNumber);
                try {
                    OptimizedNaukriScraper scraper = new OptimizedNaukriScraper();
                    scraper.Scrape(pageNumber);
                    System.out.println("Thread " + Thread.currentThread().getName() + 
                                     " completed page: " + pageNumber);
                } catch (Exception e) {
                    System.err.println("Thread failed for page " + pageNumber + ": " + e.getMessage());
                    e.printStackTrace();
                }
            });
        }
        
        // Shutdown executor and wait for completion
        executor.shutdown();
        try {
            if (!executor.awaitTermination(2, TimeUnit.HOURS)) {
                System.err.println("Timeout reached, forcing shutdown...");
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            System.err.println("Main thread interrupted");
            executor.shutdownNow();
        }
        
        // Close connection pool
        ConnectionPool.close();
        
        long endTime = System.currentTimeMillis();
        long duration = (endTime - startTime) / 1000;
        
        System.out.println("=== Scraping Completed ===");
        System.out.println("Total time: " + duration + " seconds (" + (duration / 60) + " minutes)");
    }
}
