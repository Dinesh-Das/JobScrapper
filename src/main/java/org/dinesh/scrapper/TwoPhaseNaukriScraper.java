package org.dinesh.scrapper;

import org.dinesh.config.Config;
import org.dinesh.database.BatchDatabaseHandler;
import org.dinesh.entity.JobListingEntity;
import org.dinesh.process.ChromeDriverManager;
import org.dinesh.util.MetricsTracker;
import org.openqa.selenium.*;
import org.openqa.selenium.NoSuchElementException;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Two-Phase Scraper: 
 * Phase 1: Collect all URLs from search pages (fast)
 * Phase 2: Scrape job details for unique URLs (no redundant work)
 */
public class TwoPhaseNaukriScraper {
    private static final Logger logger = Logger.getLogger(TwoPhaseNaukriScraper.class.getName());

    private final List<JobListingEntity> jobBatch = new ArrayList<>();
    private final int batchSize;

    // ThreadLocal driver for reuse within same thread (PERFORMANCE FIX)
    private static final ThreadLocal<WebDriver> threadDriver = new ThreadLocal<>();
    private static final Set<WebDriver> allDrivers = ConcurrentHashMap.newKeySet();

    public TwoPhaseNaukriScraper() {
        this.batchSize = Config.getBatchSize();
    }

    /**
     * Get or create WebDriver for current thread (reuses driver for performance)
     */
    private WebDriver getThreadDriver() {
        WebDriver driver = threadDriver.get();
        if (driver == null) {
            logger.info("Creating new driver for thread: " + Thread.currentThread().getName());
            ChromeOptions options = buildChromeOptions();
            driver = ChromeDriverManager.createDriver(options);
            threadDriver.set(driver);
            allDrivers.add(driver);
        }
        return driver;
    }

    /**
     * Cleanup thread-local driver (call at end of thread execution)
     */
    public static void cleanupThreadDriver() {
        WebDriver driver = threadDriver.get();
        if (driver != null) {
            try {
                ChromeDriverManager.cleanupDriver(driver);
                allDrivers.remove(driver);
            } finally {
                threadDriver.remove();
            }
        }
    }
    
    /**
     * Cleanup all thread drivers (call after executor shutdown)
     */
    public static void cleanupAllThreadDrivers() {
        logger.info("Cleaning up " + allDrivers.size() + " thread drivers");
        for (WebDriver driver : allDrivers) {
            try {
                ChromeDriverManager.cleanupDriver(driver);
            } catch (Exception e) {
                logger.warning("Error cleaning up driver: " + e.getMessage());
            }
        }
        allDrivers.clear();
    }

    /**
     * PHASE 1: Collect job URLs from a search result page (lightweight, no tab opening)
     */
    public Set<String> collectUrlsFromPage(int pageNo) throws Exception {
        int maxRetries = 3;
        Exception lastException = null;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                return collectUrlsFromPageInternal(pageNo);
            } catch (Exception e) {
                lastException = e;
                logger.warning("Attempt " + attempt + " failed for page " + pageNo + ": " + e.getMessage());
                if (attempt < maxRetries) {
                    Thread.sleep(2000 * attempt); // Exponential backoff
                }
            }
        }

        throw new Exception("Failed to collect URLs from page " + pageNo + " after " + maxRetries + " attempts", lastException);
    }

    private Set<String> collectUrlsFromPageInternal(int pageNo) throws Exception {
        WebDriver driver = null;
        Set<String> urls = new HashSet<>();

        try {
            // Use thread-local driver for performance
            driver = getThreadDriver();

            // Build search URL - replace {page} placeholder with actual page number
            String searchUrl = Config.getJobSearchUrl().replace("{page}", String.valueOf(pageNo));
            driver.get(searchUrl);

            WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(Config.getBrowserWaitTimeout()));

            // Wait for job listings to load
            wait.until(ExpectedConditions.presenceOfElementLocated(
                By.cssSelector(".cust-job-tuple.layout-wrapper.lay-2.sjw__tuple")));

            Thread.sleep(1000); // Small delay for dynamic content

            // Extract all job URLs from the page
            List<WebElement> jobCards = driver.findElements(
                By.cssSelector(".cust-job-tuple.layout-wrapper.lay-2.sjw__tuple"));

            for (WebElement card : jobCards) {
                try {
                    WebElement titleLink = card.findElement(By.cssSelector("a.title"));
                    String url = titleLink.getAttribute("href");
                    if (url != null && !url.isEmpty()) {
                        urls.add(url);
                    }
                } catch (NoSuchElementException e) {
                    // Skip cards without title link
                }
            }

            logger.info("Page " + pageNo + ": Found " + urls.size() + " job URLs");
            
            // Rate limiting to avoid getting blocked
            Thread.sleep(Config.getRateLimitDelay());
            
            return urls;

        } catch (Exception e) {
            logger.severe("Error collecting URLs from page " + pageNo + ": " + e.getMessage());
            throw e;
        }
        // Note: Don't cleanup driver here - it's reused by thread
    }

    /**
     * PHASE 2: Scrape job details from a specific URL
     */
    public void scrapeJobDetails(String url) throws Exception {
        int maxRetries = 2;
        Exception lastException = null;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                scrapeJobDetailsInternal(url);
                return; // Success
            } catch (Exception e) {
                lastException = e;
                if (attempt < maxRetries) {
                    Thread.sleep(1000 * attempt);
                }
            }
        }

        logger.warning("Failed to scrape job details for " + url + " after " + maxRetries + " attempts");
        MetricsTracker.incrementErrors();
    }

    private void scrapeJobDetailsInternal(String url) throws Exception {
        try {
            // Use thread-local driver for performance (REUSE instead of creating new)
            WebDriver driver = getThreadDriver();

            driver.get(url);

            WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(Config.getBrowserWaitTimeout()));

            // Wait for job details page to load
            try {
                wait.until(ExpectedConditions.visibilityOfElementLocated(
                    By.cssSelector(".styles_jd-header-title__rZwM1")));
            } catch (Exception e) {
                logger.warning("Timeout waiting for job details: " + url);
                return;
            }

            Thread.sleep(500); // Small delay for dynamic content

            // Extract job details
            JobListingEntity job = extractJobDetails(driver, url);

            if (job != null && isValidJob(job)) {
                addToBatch(job);
            }

        } catch (Exception e) {
            logger.warning("Error scraping job details for " + url + ": " + e.getMessage());
            throw e;
        }
        // Note: Don't cleanup driver here - it's reused by thread
    }

    /**
     * Thread-safe method to add job to batch
     */
    private synchronized void addToBatch(JobListingEntity job) {
        jobBatch.add(job);
        MetricsTracker.incrementJobsScraped(1);

        // Flush batch when it reaches size limit
        if (jobBatch.size() >= batchSize) {
            flushBatch();
        }
    }

    private JobListingEntity extractJobDetails(WebDriver driver, String url) {
        JobListingEntity job = new JobListingEntity();
        job.setUrl(url);

        try {
            // Title
            String title = safeGetText(driver, By.cssSelector(".styles_jd-header-title__rZwM1"));
            job.setTitle(title);

            // Company Name
            String companyName = safeGetText(driver, By.cssSelector(".styles_jd-header-comp-name__MvqAI"));
            job.setCompanyName(cleanCompanyName(companyName));

            // Rating
            String rating = safeGetText(driver, By.cssSelector(".styles_jd-header-comp-name__MvqAI .main-2"));
            job.setRating(rating);

            // Reviews
            String reviews = safeGetText(driver, By.cssSelector(".styles_jd-header-comp-name__MvqAI a.review"));
            job.setReviews(reviews);

            // Experience
            String experience = safeGetText(driver, By.xpath("//span[contains(@class, 'exp')]/span[@class='ellipsis']"));
            job.setExperience(experience);

            // Salary
            String salary = safeGetText(driver, By.xpath("//span[contains(@class, 'salary')]/span[@class='ellipsis']"));
            job.setSalary(salary);

            // Location
            String location = safeGetText(driver, By.xpath("//span[contains(@class, 'loc')]/span[@class='ellipsis']"));
            job.setLocation(location);

            // Posted date
            String posted = safeGetText(driver, By.cssSelector(".styles_jhc__posted-date__rNYoJ"));
            job.setPosted(parsePosted(posted));

            // Openings
            String openings = safeGetText(driver, By.cssSelector(".styles_jhc__opening__SmFbh"));
            job.setOpenings(parseOpenings(openings));

            // Applications
            String applications = safeGetText(driver, By.cssSelector(".styles_jhc__applications__fDIHN"));
            job.setApplications(parseApplications(applications));

            // Job Description
            String jobDescription = safeGetText(driver, By.cssSelector(".styles_JDC__dang-inner-html__h0K4t"));
            job.setJobDescription(jobDescription);

            // Role
            String role = safeGetText(driver, By.xpath("//div[@class='styles_jhc__item__value__xrRrg' and preceding-sibling::div[contains(text(), 'Role')]]"));
            job.setRole(role);

            // Industry Type
            String industryType = safeGetText(driver, By.xpath("//div[@class='styles_jhc__item__value__xrRrg' and preceding-sibling::div[contains(text(), 'Industry Type')]]"));
            job.setIndustryType(industryType);

            // Department
            String department = safeGetText(driver, By.xpath("//div[@class='styles_jhc__item__value__xrRrg' and preceding-sibling::div[contains(text(), 'Department')]]"));
            job.setDepartment(department);

            // Employment Type
            String employmentType = safeGetText(driver, By.xpath("//div[@class='styles_jhc__item__value__xrRrg' and preceding-sibling::div[contains(text(), 'Employment Type')]]"));
            job.setEmploymentType(employmentType);

            // Role Category
            String roleCategory = safeGetText(driver, By.xpath("//div[@class='styles_jhc__item__value__xrRrrg' and preceding-sibling::div[contains(text(), 'Role Category')]]"));
            job.setRoleCategory(roleCategory);

            // Education
            String education = safeGetText(driver, By.xpath("//div[@class='styles_jhc__item__value__xrRrg' and preceding-sibling::div[contains(text(), 'Education')]]"));
            job.setEducation(education);

            // Key Skills
            String keySkills = safeGetText(driver, By.cssSelector(".styles_jhc__skills__KKLjd"));
            job.setKeySkills(keySkills);

            job.setLogdate(String.valueOf(LocalDateTime.now()));

            return job;

        } catch (Exception e) {
            logger.warning("Error extracting job details: " + e.getMessage());
            return null;
        }
    }

    private synchronized void flushBatch() {
        if (jobBatch.isEmpty()) {
            return;
        }

        List<JobListingEntity> toSave = new ArrayList<>(jobBatch);
        jobBatch.clear();

        // Determine if these are new or existing URLs
        boolean isUpsert = Config.isUpsertDuplicates();

        int saved = BatchDatabaseHandler.saveJobsBatch(toSave, isUpsert);
        MetricsTracker.incrementJobsSaved(saved);

        String action = isUpsert ? "updated/inserted" : "inserted";
        logger.info("Batch " + action + ": " + saved + " jobs");
    }

    /**
     * Flush any remaining jobs in the batch (called at end of Phase 2)
     */
    public synchronized void flushFinalBatch() {
        flushBatch();
    }

    private ChromeOptions buildChromeOptions() {
        ChromeOptions options = new ChromeOptions();

        if (Config.isBrowserHeadless()) {
            options.addArguments("--headless=new");
        }

        options.addArguments(
            "--disable-blink-features=AutomationControlled",
            "--disable-dev-shm-usage",
            "--no-sandbox",
            "--disable-gpu",
            "--window-size=1920,1080",
            "--user-agent=Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
        );

        options.setExperimentalOption("excludeSwitches", new String[]{"enable-automation"});
        options.setExperimentalOption("useAutomationExtension", false);

        return options;
    }

    private String safeGetText(WebDriver driver, By selector) {
        try {
            return driver.findElement(selector).getText().trim();
        } catch (NoSuchElementException e) {
            return "";
        }
    }

    private String safeGetText(WebElement element, By selector) {
        try {
            return element.findElement(selector).getText().trim();
        } catch (NoSuchElementException e) {
            return "";
        }
    }

    private String extractNumber(String text) {
        if (text == null) return "";
        return text.replaceAll("[^0-9]", "");
    }

    private String cleanCompanyName(String companyName) {
        if (companyName == null) return "";
        return companyName.replaceAll("\\d+\\.\\d+.*", "").trim();
    }

    private String parsePosted(String postedText) {
        return postedText != null ? postedText.replace("Posted:", "").trim() : "";
    }

    private String parseOpenings(String openingsText) {
        if (openingsText == null || openingsText.isEmpty()) return "";
        return openingsText.replace("opening", "").replace("openings", "").trim();
    }

    private String parseApplications(String applicationsText) {
        if (applicationsText == null || applicationsText.isEmpty()) return "";
        return applicationsText.replace("application", "").replace("applications", "").trim();
    }

    private boolean isValidJob(JobListingEntity job) {
        return job != null && 
               job.getUrl() != null && !job.getUrl().isEmpty() &&
               job.getTitle() != null && !job.getTitle().isEmpty();
    }
}
