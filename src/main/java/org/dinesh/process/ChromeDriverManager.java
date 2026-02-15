package org.dinesh.process;

import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;

import java.util.concurrent.*;
import java.util.logging.Logger;

/**
 * Manages ChromeDriver lifecycle with proper cleanup.
 * No watchdog timer - relies on try-finally blocks for cleanup.
 */
public class ChromeDriverManager {
    private static final Logger logger = Logger.getLogger(ChromeDriverManager.class.getName());
    
    /**
     * Create a ChromeDriver
     */
    public static WebDriver createDriver(ChromeOptions options) {
        ChromeDriver driver = new ChromeDriver(options);
        String sessionId = driver.getSessionId().toString();
        logger.fine("Created ChromeDriver, Session: " + sessionId);
        return driver;
    }
    
    /**
     * Cleanup driver with aggressive fallback
     */
    public static void cleanupDriver(WebDriver driver) {
        if (driver == null) return;
        
        // Try normal quit with timeout
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<?> quitFuture = executor.submit(() -> {
            try {
                driver.quit();
            } catch (Exception e) {
                // Ignore
            }
        });
        
        try {
            quitFuture.get(10, TimeUnit.SECONDS);
            logger.fine("Driver quit successfully");
        } catch (TimeoutException e) {
            logger.warning("Driver quit timeout");
            quitFuture.cancel(true);
        } catch (Exception e) {
            logger.warning("Driver quit failed: " + e.getMessage());
        } finally {
            executor.shutdownNow();
        }
    }
    
    /**
     * Shutdown and cleanup
     */
    public static void shutdown() {
        logger.info("Shutting down ChromeDriverManager...");
        
        // Use kill_chrome.bat for final cleanup on Windows
        try {
            String os = System.getProperty("os.name").toLowerCase();
            if (os.contains("windows")) {
                ProcessBuilder pb = new ProcessBuilder("cmd", "/c", "kill_chrome.bat");
                Process p = pb.start();
                p.waitFor(10, TimeUnit.SECONDS);
                logger.info("Executed kill_chrome.bat for cleanup");
            }
        } catch (Exception e) {
            logger.fine("Could not run kill_chrome.bat: " + e.getMessage());
        }
        
        logger.info("ChromeDriverManager shutdown complete");
    }
}
