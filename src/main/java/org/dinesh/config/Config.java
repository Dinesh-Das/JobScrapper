package org.dinesh.config;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.Properties;

public class Config {
    private static final Properties props = new Properties();
    
    static {
        try (FileInputStream fis = new FileInputStream("config.properties")) {
            props.load(fis);
        } catch (IOException e) {
            System.err.println("Failed to load config.properties: " + e.getMessage());
            throw new RuntimeException("Configuration file not found", e);
        }
    }
    
    // Database
    public static String getDbUrl() {
        return props.getProperty("db.url");
    }
    
    public static String getDbUser() {
        return props.getProperty("db.user");
    }
    
    public static String getDbPassword() {
        return props.getProperty("db.password");
    }
    
    public static int getDbPoolSize() {
        return Integer.parseInt(props.getProperty("db.pool.size", "10"));
    }
    
    public static int getDbConnectionTimeout() {
        return Integer.parseInt(props.getProperty("db.connection.timeout", "30000"));
    }
    
    // Scraper
    public static int getScraperThreads() {
        return Integer.parseInt(props.getProperty("scraper.threads", "5"));
    }
    
    public static int getTotalPages() {
        return Integer.parseInt(props.getProperty("scraper.total.pages", "15"));
    }
    
    public static int getPageOffset() {
        return Integer.parseInt(props.getProperty("scraper.page.offset", "250"));
    }
    
    public static int getMaxPagesPerThread() {
        return Integer.parseInt(props.getProperty("scraper.max.pages.per.thread", "1000"));
    }
    
    public static int getPageLoadDelay() {
        return Integer.parseInt(props.getProperty("scraper.page.load.delay", "500"));
    }
    
    public static int getBatchSize() {
        return Integer.parseInt(props.getProperty("scraper.batch.size", "50"));
    }
    
    // Browser
    public static boolean isBrowserHeadless() {
        return Boolean.parseBoolean(props.getProperty("browser.headless", "false"));
    }
    
    public static boolean isDisableImages() {
        return Boolean.parseBoolean(props.getProperty("browser.disable.images", "false"));
    }
    
    public static int getBrowserWaitTimeout() {
        return Integer.parseInt(props.getProperty("browser.wait.timeout", "10"));
    }
}
