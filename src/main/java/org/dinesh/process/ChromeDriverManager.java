package org.dinesh.process;

import org.dinesh.config.Config;
import org.openqa.selenium.PageLoadStrategy;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;

import java.time.Duration;
import java.util.concurrent.*;
import java.util.logging.Logger;

/**
 * Manages ChromeDriver lifecycle with timeouts and cleanup that will not
 * kill the user's own Chrome windows.
 */
public class ChromeDriverManager {
    private static final Logger logger = Logger.getLogger(ChromeDriverManager.class.getName());

    public static WebDriver createDriver(ChromeOptions options) {
        if (options.getCapability("pageLoadStrategy") == null) {
            options.setPageLoadStrategy(PageLoadStrategy.EAGER);
        }
        ChromeDriver driver = new ChromeDriver(options);
        int pageLoadTimeout = Config.getPageLoadTimeout();
        driver.manage().timeouts().pageLoadTimeout(Duration.ofSeconds(pageLoadTimeout));
        driver.manage().timeouts().scriptTimeout(Duration.ofSeconds(15));
        driver.manage().timeouts().implicitlyWait(Duration.ofSeconds(0));
        logger.fine("Created ChromeDriver, Session: " + driver.getSessionId());
        return driver;
    }

    public static void cleanupDriver(WebDriver driver) {
        if (driver == null) {
            return;
        }

        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<?> quitFuture = executor.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    driver.quit();
                } catch (Exception ignored) {
                }
            }
        });

        try {
            quitFuture.get(8, TimeUnit.SECONDS);
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
     * Only kill leftover chromedriver processes. Never taskkill chrome.exe —
     * that would close the user's real browser.
     */
    public static void shutdown() {
        logger.info("Shutting down ChromeDriverManager...");
        try {
            String os = System.getProperty("os.name").toLowerCase();
            if (os.contains("windows")) {
                ProcessBuilder pb = new ProcessBuilder("cmd", "/c", "taskkill /F /IM chromedriver.exe /T");
                Process p = pb.start();
                p.waitFor(8, TimeUnit.SECONDS);
                logger.info("Cleaned leftover chromedriver processes");
            }
        } catch (Exception e) {
            logger.fine("Could not clean chromedriver processes: " + e.getMessage());
        }
        logger.info("ChromeDriverManager shutdown complete");
    }
}
