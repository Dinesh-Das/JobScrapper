package org.dinesh.scrapper;

import org.dinesh.config.Config;
import org.dinesh.database.BatchDatabaseHandler;
import org.dinesh.entity.JobListingEntity;
import org.dinesh.process.ChromeDriverManager;
import org.dinesh.util.MetricsTracker;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.NoSuchElementException;
import org.openqa.selenium.NoSuchSessionException;
import org.openqa.selenium.PageLoadStrategy;
import org.openqa.selenium.SessionNotCreatedException;
import org.openqa.selenium.TimeoutException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebDriverException;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Two-Phase Scraper:
 * Phase 1: Collect all URLs from search pages (fast)
 * Phase 2: Scrape job details for unique URLs (no redundant work)
 */
public class TwoPhaseNaukriScraper {
    private static final Logger logger = Logger.getLogger(TwoPhaseNaukriScraper.class.getName());

    private static final By[] JOB_CARD_SELECTORS = new By[] {
            By.cssSelector("[class*='cust-job-tuple']"),
            By.cssSelector("[class*='srp-jobtuple']"),
            By.cssSelector("a.title"),
            By.cssSelector("a[href*='job-listings']"),
            By.cssSelector("[data-type='job']"),
            By.cssSelector("article"),
            By.cssSelector(".job-card")
    };

    private static final By[] TITLE_SELECTORS = new By[] {
            By.cssSelector("[class*='jd-header-title']"),
            By.cssSelector("h1"),
            By.cssSelector("[data-type='job-title']"),
            By.cssSelector(".job-title"),
            By.xpath("//h1[contains(@class, 'title')]")
    };

    private static final By[] EXPIRED_SELECTORS = new By[] {
            By.cssSelector("[class*='exp-alert-message']"),
            By.cssSelector("[class*='expired']"),
            By.cssSelector(".exp-alert"),
            By.cssSelector("[data-type='expired-job']"),
            By.xpath("//*[contains(text(), 'expired') and contains(text(), 'job')]")
    };

    private final List<JobListingEntity> jobBatch = new ArrayList<JobListingEntity>();
    private final int batchSize;

    private static final ThreadLocal<WebDriver> threadDriver = new ThreadLocal<WebDriver>();
    private static final Set<WebDriver> allDrivers = ConcurrentHashMap.newKeySet();

    public TwoPhaseNaukriScraper() {
        this.batchSize = Config.getBatchSize();
    }

    private WebDriver getThreadDriver() {
        WebDriver driver = threadDriver.get();

        if (driver != null) {
            try {
                driver.getTitle();
            } catch (NoSuchSessionException e) {
                logger.warning(Thread.currentThread().getName() + ": Session invalid, recreating driver");
                cleanupAndRemoveDriver(driver);
                driver = null;
            } catch (WebDriverException e) {
                logger.warning(Thread.currentThread().getName() + ": WebDriver error ("
                        + e.getClass().getSimpleName() + "), recreating driver");
                cleanupAndRemoveDriver(driver);
                driver = null;
            } catch (Exception e) {
                logger.warning(Thread.currentThread().getName() + ": Unexpected session error, recreating driver: "
                        + e.getMessage());
                cleanupAndRemoveDriver(driver);
                driver = null;
            }
        }

        if (driver == null) {
            logger.info(Thread.currentThread().getName() + ": Creating new driver");
            driver = ChromeDriverManager.createDriver(buildChromeOptions());
            executeStealthScript(driver);
            threadDriver.set(driver);
            allDrivers.add(driver);
        }
        return driver;
    }

    private void executeStealthScript(WebDriver driver) {
        try {
            JavascriptExecutor js = (JavascriptExecutor) driver;
            js.executeScript(
                    "Object.defineProperty(navigator, 'webdriver', {get: () => undefined});"
                            + "window.navigator.chrome = {runtime: {}};"
                            + "Object.defineProperty(navigator, 'plugins', {get: () => [1, 2, 3, 4, 5]});"
                            + "Object.defineProperty(navigator, 'languages', {get: () => ['en-US', 'en']});");
        } catch (Exception e) {
            logger.fine("Could not execute stealth script: " + e.getMessage());
        }
    }

    private void dismissOverlays(WebDriver driver) {
        try {
            JavascriptExecutor js = (JavascriptExecutor) driver;
            js.executeScript(
                    "var sels = ['#onetrust-accept-btn-handler','.accept-cookies',"
                            + "'button[id*=\"accept\"]','.crossIcon','#close-popup'];"
                            + "for (var i = 0; i < sels.length; i++) {"
                            + "  var el = document.querySelector(sels[i]);"
                            + "  if (el) { try { el.click(); } catch (e) {} }"
                            + "}");
        } catch (Exception ignored) {
        }
    }

    private void safeGet(WebDriver driver, String url) {
        try {
            driver.get(url);
        } catch (TimeoutException e) {
            // EAGER + pageLoadTimeout: DOM is usually usable even when this fires
            logger.fine("Page load timeout (continuing): " + url);
        } catch (Exception e) {
            logger.warning("Unexpected error loading page " + url + ": " + e.getMessage());
            throw new RuntimeException("Failed to load page: " + url, e);
        }
    }

    private void cleanupAndRemoveDriver(WebDriver driver) {
        try {
            ChromeDriverManager.cleanupDriver(driver);
            allDrivers.remove(driver);
        } catch (Exception cleanupEx) {
            logger.fine("Error during driver cleanup: " + cleanupEx.getMessage());
        }
        threadDriver.remove();
    }

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

    public Set<String> collectUrlsFromPage(int pageNo) throws Exception {
        int maxRetries = Math.max(1, Config.getMaxRetries());
        Exception lastException = null;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                return collectUrlsFromPageInternal(pageNo);
            } catch (TimeoutException e) {
                lastException = e;
                logger.warning("Attempt " + attempt + " failed for page " + pageNo + ": Timeout");
                if (attempt < maxRetries) {
                    cleanupThreadDriver();
                    Thread.sleep(Config.getRetryDelay());
                }
            } catch (SessionNotCreatedException e) {
                lastException = e;
                logger.warning("Attempt " + attempt + " failed for page " + pageNo + ": session not created");
                cleanupThreadDriver();
                if (attempt < maxRetries) {
                    Thread.sleep(Config.getRetryDelay() * attempt);
                }
            } catch (Exception e) {
                lastException = e;
                logger.warning("Attempt " + attempt + " failed for page " + pageNo + ": " + e.getMessage());
                if (attempt < maxRetries) {
                    Thread.sleep(Config.getRetryDelay() * attempt);
                }
            }
        }

        throw new Exception("Failed to collect URLs from page " + pageNo + " after " + maxRetries + " attempts",
                lastException);
    }

    private Set<String> collectUrlsFromPageInternal(int pageNo) throws Exception {
        Set<String> urls = new HashSet<String>();
        String searchUrl = Config.getJobSearchUrl().replace("{page}", String.valueOf(pageNo));
        try {
            WebDriver driver = getThreadDriver();
            safeGet(driver, searchUrl);
            dismissOverlays(driver);

            WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(Config.getBrowserWaitTimeout()));
            try {
                wait.until(ExpectedConditions.or(
                        ExpectedConditions.presenceOfElementLocated(By.cssSelector("[class*='cust-job-tuple']")),
                        ExpectedConditions.presenceOfElementLocated(By.cssSelector("[class*='srp-jobtuple']")),
                        ExpectedConditions.presenceOfElementLocated(By.cssSelector("a.title")),
                        ExpectedConditions.presenceOfElementLocated(By.cssSelector("a[href*='job-listings']")),
                        ExpectedConditions.presenceOfElementLocated(By.cssSelector("[data-type='job']")),
                        ExpectedConditions.presenceOfElementLocated(By.cssSelector("article"))
                ));
            } catch (TimeoutException e) {
                if (detectCaptcha(driver)) {
                    logger.severe("CAPTCHA on search page " + pageNo + " — backing off 45s");
                    Thread.sleep(45000);
                    throw e;
                }
                // Check if page has any content before treating as empty
                String pageSource = driver.getPageSource();
                if (pageSource != null && pageSource.length() < 5000) {
                    logger.warning("Page " + pageNo + " appears to be empty or error page (length: " + pageSource.length() + ")");
                    throw new Exception("Page appears to be empty or error page");
                }
                logger.fine("Listing wait timed out on page " + pageNo + ", extracting whatever is present");
            }

            int settle = Math.max(100, Config.getPageLoadDelay());
            Thread.sleep(settle);

            urls.addAll(extractUrlsViaJs(driver));
            if (urls.isEmpty()) {
                urls.addAll(extractUrlsViaCss(driver));
            }

            logger.info("Page " + pageNo + ": Found " + urls.size() + " job URLs");

            long baseDelay = Config.getRateLimitDelay();
            long jitter = (long) (Math.random() * 400);
            Thread.sleep(baseDelay + jitter);
            return urls;

        } catch (SessionNotCreatedException e) {
            logger.warning("Session error for page " + pageNo + ", will recreate on retry: " + e.getMessage());
            cleanupThreadDriver();
            throw e;
        } catch (NoSuchSessionException e) {
            logger.warning("Session error for page " + pageNo + ", will recreate on retry: " + e.getMessage());
            cleanupThreadDriver();
            throw e;
        } catch (Exception e) {
            logger.severe("Error collecting URLs from page " + pageNo + " (URL: " + searchUrl + "): " + e.getMessage());
            logger.severe("Exception type: " + e.getClass().getName());
            throw e;
        }
    }

    @SuppressWarnings("unchecked")
    private Set<String> extractUrlsViaJs(WebDriver driver) {
        Set<String> urls = new HashSet<String>();
        try {
            JavascriptExecutor js = (JavascriptExecutor) driver;
            Object raw = js.executeScript(
                    "var out = []; var seen = {};"
                            + "var nodes = document.querySelectorAll('a.title[href], a[href*=\"job-listings\"]');"
                            + "for (var i = 0; i < nodes.length; i++) {"
                            + "  var h = nodes[i].href;"
                            + "  if (h && h.indexOf('job-listings') !== -1 && !seen[h]) { seen[h] = 1; out.push(h); }"
                            + "}"
                            + "return out;");
            if (raw instanceof List) {
                List<?> list = (List<?>) raw;
                for (Object item : list) {
                    if (item != null) {
                        String href = String.valueOf(item).trim();
                        if (!href.isEmpty()) {
                            urls.add(href);
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.fine("JS URL extract failed: " + e.getMessage());
        }
        return urls;
    }

    private Set<String> extractUrlsViaCss(WebDriver driver) {
        Set<String> urls = new HashSet<String>();
        for (By cardSel : JOB_CARD_SELECTORS) {
            List<WebElement> nodes = driver.findElements(cardSel);
            for (WebElement node : nodes) {
                try {
                    String href = node.getAttribute("href");
                    if (href == null || href.isEmpty()) {
                        List<WebElement> links = node.findElements(By.cssSelector("a.title, a[href*='job-listings']"));
                        if (!links.isEmpty()) {
                            href = links.get(0).getAttribute("href");
                        }
                    }
                    if (href != null && href.contains("job-listings")) {
                        urls.add(href);
                    }
                } catch (Exception ignored) {
                }
            }
            if (!urls.isEmpty()) {
                break;
            }
        }
        return urls;
    }

    public void scrapeJobDetails(String url) throws Exception {
        int maxRetries = Math.max(1, Config.getMaxRetries());
        Exception lastException = null;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                scrapeJobDetailsInternal(url);
                return;
            } catch (Exception e) {
                lastException = e;
                if (attempt < maxRetries) {
                    Thread.sleep(Config.getRetryDelay() * attempt);
                }
            }
        }

        logger.warning("Failed to scrape job details for " + url + " after " + maxRetries + " attempts"
                + (lastException != null ? ": " + lastException.getMessage() : ""));
        MetricsTracker.incrementErrors();
    }

    private void scrapeJobDetailsInternal(String url) throws Exception {
        try {
            WebDriver driver = getThreadDriver();
            safeGet(driver, url);
            dismissOverlays(driver);
            Thread.sleep(Math.max(150, Config.getPageLoadDelay()));

            // Add rate limiting before processing
            long baseDelay = Config.getRateLimitDelay();
            long jitter = (long) (Math.random() * 1000);
            Thread.sleep(baseDelay + jitter);

            if (isExpiredJob(driver)) {
                logger.fine("Skipping expired job: " + url);
                MetricsTracker.incrementExpiredJobs();
                return;
            }

            WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(Math.max(4, Config.getBrowserWaitTimeout() / 2)));
            boolean titleReady = false;
            try {
                wait.until(ExpectedConditions.or(
                        ExpectedConditions.visibilityOfElementLocated(By.cssSelector("[class*='jd-header-title']")),
                        ExpectedConditions.visibilityOfElementLocated(By.cssSelector("h1"))
                ));
                titleReady = true;
            } catch (Exception e) {
                if (isExpiredJob(driver)) {
                    logger.fine("Skipping expired job (after wait): " + url);
                    MetricsTracker.incrementExpiredJobs();
                    return;
                }
                if (detectCaptcha(driver)) {
                    logger.severe("CAPTCHA DETECTED! URL: " + url);
                    logger.severe("Pausing for 45 seconds...");
                    Thread.sleep(45000);
                    throw new Exception("CAPTCHA detected - need to slow down");
                }
            }

            JobListingEntity job = extractJobDetailsViaJs(driver, url);
            if (job == null || !isValidJob(job)) {
                job = extractJobDetails(driver, url);
            }

            if (job != null && isValidJob(job)) {
                addToBatch(job);
            } else if (!titleReady) {
                logger.fine("No job details extracted: " + url);
            }

            // Add rate limiting after processing
            long postDelay = Config.getRateLimitDelay() / 2;
            long postJitter = (long) (Math.random() * 500);
            Thread.sleep(postDelay + postJitter);

        } catch (SessionNotCreatedException e) {
            logger.warning("Session error for " + url + ", will recreate on retry: " + e.getMessage());
            cleanupThreadDriver();
            throw e;
        } catch (NoSuchSessionException e) {
            logger.warning("Session error for " + url + ", will recreate on retry: " + e.getMessage());
            cleanupThreadDriver();
            throw e;
        } catch (Exception e) {
            logger.warning("Error scraping job details for " + url + ": " + e.getMessage());
            logger.warning("Exception type: " + e.getClass().getName());
            throw e;
        }
    }

    private boolean isExpiredJob(WebDriver driver) {
        try {
            for (By sel : EXPIRED_SELECTORS) {
                List<WebElement> alerts = driver.findElements(sel);
                for (WebElement alert : alerts) {
                    try {
                        if (alert.isDisplayed()) {
                            String text = alert.getText();
                            if (text != null && text.toLowerCase().contains("expired")) {
                                return true;
                            }
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
            String source = driver.getPageSource();
            if (source == null) {
                return false;
            }
            String lower = source.toLowerCase();
            return lower.contains("job you are looking for is") && lower.contains("expired")
                    || (lower.contains("expired") && lower.contains("similar jobs below"));
        } catch (Exception e) {
            return false;
        }
    }

    private boolean detectCaptcha(WebDriver driver) {
        try {
            try {
                WebElement captchaFrame = driver.findElement(By.cssSelector("iframe[src*='recaptcha']"));
                if (captchaFrame.isDisplayed()) {
                    logger.info("CAPTCHA detected: reCAPTCHA iframe found");
                    return true;
                }
            } catch (NoSuchElementException ignored) {
            }

            String title = driver.getTitle();
            if (title != null) {
                String lower = title.toLowerCase();
                if (lower.contains("captcha") || lower.contains("verify you") || lower.contains("robot")) {
                    logger.info("CAPTCHA detected: Page title indicates CAPTCHA - " + title);
                    return true;
                }
            }

            try {
                WebElement captchaContainer = driver.findElement(By.id("captcha-container"));
                if (captchaContainer.isDisplayed()) {
                    logger.info("CAPTCHA detected: CAPTCHA container found");
                    return true;
                }
            } catch (NoSuchElementException ignored) {
            }

            String pageSource = driver.getPageSource();
            if (pageSource != null) {
                String lower = pageSource.toLowerCase();
                if ((lower.contains("access denied") || lower.contains("too many requests"))
                        && pageSource.length() < 5000) {
                    logger.info("CAPTCHA detected: Access denied or rate limit page");
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private synchronized void addToBatch(JobListingEntity job) {
        jobBatch.add(job);
        MetricsTracker.incrementJobsScraped(1);
        if (jobBatch.size() >= batchSize) {
            flushBatch();
        }
    }

    @SuppressWarnings("unchecked")
    private JobListingEntity extractJobDetailsViaJs(WebDriver driver, String url) {
        try {
            JavascriptExecutor js = (JavascriptExecutor) driver;
            Object raw = js.executeScript(
                    "function pick(root, sels) {"
                            + "  for (var i = 0; i < sels.length; i++) {"
                            + "    var el = document.querySelector(sels[i]);"
                            + "    if (el && el.innerText && el.innerText.trim()) return el.innerText.trim();"
                            + "  }"
                            + "  return '';"
                            + "}"
                            + "function byLabel(label) {"
                            + "  var nodes = document.querySelectorAll('div,span,label');"
                            + "  for (var i = 0; i < nodes.length; i++) {"
                            + "    var t = (nodes[i].childNodes[0] && nodes[i].childNodes[0].textContent || nodes[i].textContent || '').trim();"
                            + "    if (t === label || t === label + ':') {"
                            + "      var sib = nodes[i].nextElementSibling;"
                            + "      if (sib && sib.innerText) return sib.innerText.trim();"
                            + "      var parent = nodes[i].parentElement;"
                            + "      if (parent) {"
                            + "        var val = parent.querySelector('[class*=\"value\"], [class*=\"item__value\"]');"
                            + "        if (val && val.innerText) return val.innerText.trim();"
                            + "      }"
                            + "    }"
                            + "  }"
                            + "  return '';"
                            + "}"
                            + "var data = {};"
                            + "data.title = pick(document, ['[class*=\"jd-header-title\"]','h1','[data-type=\"job-title\"]','.job-title']);"
                            + "data.companyName = pick(document, ['[class*=\"jd-header-comp-name\"]','[class*=\"comp-name\"]','.company-name']);"
                            + "data.experience = pick(document, ['span[class*=\"exp\"] .ellipsis','[class*=\"expwdth\"]','[class*=\"exp\"]','.experience']);"
                            + "data.salary = pick(document, ['span[class*=\"salary\"] .ellipsis','[class*=\"sal\"]','.salary']);"
                            + "data.location = pick(document, ['span[class*=\"loc\"] .ellipsis','[class*=\"locWdth\"]','[class*=\"loc\"]','.location']);"
                            + "data.posted = pick(document, ['[class*=\"posted-date\"]','[class*=\"stat\"]','.posted-date']);"
                            + "data.openings = pick(document, ['[class*=\"jhc__opening\"]','.openings']);"
                            + "data.applications = pick(document, ['[class*=\"jhc__applications\"]','.applications']);"
                            + "data.jobDescription = pick(document, ['[class*=\"dang-inner-html\"]','[class*=\"job-desc\"]','[class*=\"JDC\"]','.job-description']);"
                            + "data.role = byLabel('Role') || pick(document, ['[class*=\"jhc__item__value\"]','.role']);"
                            + "data.industryType = byLabel('Industry Type') || pick(document, ['.industry-type']);"
                            + "data.department = byLabel('Department') || pick(document, ['.department']);"
                            + "data.employmentType = byLabel('Employment Type') || pick(document, ['.employment-type']);"
                            + "data.roleCategory = byLabel('Role Category') || pick(document, ['.role-category']);"
                            + "data.education = byLabel('Education') || pick(document, ['.education']);"
                            + "data.keySkills = pick(document, ['[class*=\"jhc__skills\"]','[class*=\"key-skill\"]','[class*=\"skills\"]','.skills']);"
                            + "data.rating = pick(document, ['[class*=\"jd-header-comp-name\"] .main-2','[class*=\"rating\"]','.rating']);"
                            + "data.reviews = pick(document, ['[class*=\"jd-header-comp-name\"] a.review','a.review']);"
                            + "return data;");
            if (!(raw instanceof Map)) {
                return null;
            }
            Map<String, Object> data = (Map<String, Object>) raw;
            String title = str(data.get("title"));
            if (title.isEmpty()) {
                return null;
            }
            JobListingEntity job = new JobListingEntity();
            job.setUrl(url);
            job.setTitle(title);
            job.setCompanyName(cleanCompanyName(str(data.get("companyName"))));
            job.setRating(str(data.get("rating")));
            job.setReviews(str(data.get("reviews")));
            job.setExperience(str(data.get("experience")));
            job.setSalary(str(data.get("salary")));
            job.setLocation(str(data.get("location")));
            job.setPosted(parsePosted(str(data.get("posted"))));
            job.setOpenings(parseOpenings(str(data.get("openings"))));
            job.setApplications(parseApplications(str(data.get("applications"))));
            job.setJobDescription(str(data.get("jobDescription")));
            job.setRole(str(data.get("role")));
            job.setIndustryType(str(data.get("industryType")));
            job.setDepartment(str(data.get("department")));
            job.setEmploymentType(str(data.get("employmentType")));
            job.setRoleCategory(str(data.get("roleCategory")));
            job.setEducation(str(data.get("education")));
            job.setKeySkills(str(data.get("keySkills")));
            job.setLogdate(String.valueOf(LocalDateTime.now()));
            return job;
        } catch (Exception e) {
            logger.fine("JS job extract failed: " + e.getMessage());
            return null;
        }
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private JobListingEntity extractJobDetails(WebDriver driver, String url) {
        JobListingEntity job = new JobListingEntity();
        job.setUrl(url);

        try {
            job.setTitle(firstText(driver, TITLE_SELECTORS));
            if (job.getTitle() == null || job.getTitle().isEmpty()) {
                logger.fine("No title found for: " + url);
                return null;
            }

            job.setCompanyName(cleanCompanyName(firstText(driver,
                    By.cssSelector("[class*='jd-header-comp-name']"),
                    By.cssSelector("[class*='comp-name']"),
                    By.cssSelector(".company-name"))));
            job.setRating(firstText(driver,
                    By.cssSelector("[class*='jd-header-comp-name'] .main-2"),
                    By.cssSelector(".rating")));
            job.setReviews(firstText(driver,
                    By.cssSelector("[class*='jd-header-comp-name'] a.review"),
                    By.cssSelector("a.review")));
            job.setExperience(firstText(driver,
                    By.xpath("//span[contains(@class, 'exp')]/span[@class='ellipsis']"),
                    By.cssSelector("[class*='expwdth']"),
                    By.cssSelector(".experience")));
            job.setSalary(firstText(driver,
                    By.xpath("//span[contains(@class, 'salary')]/span[@class='ellipsis']"),
                    By.cssSelector("[class*='sal']"),
                    By.cssSelector(".salary")));
            job.setLocation(firstText(driver,
                    By.xpath("//span[contains(@class, 'loc')]/span[@class='ellipsis']"),
                    By.cssSelector("[class*='locWdth']"),
                    By.cssSelector(".location")));
            job.setPosted(parsePosted(firstText(driver,
                    By.cssSelector("[class*='posted-date']"),
                    By.cssSelector(".posted-date"))));
            job.setOpenings(parseOpenings(firstText(driver,
                    By.cssSelector("[class*='jhc__opening']"),
                    By.cssSelector(".openings"))));
            job.setApplications(parseApplications(firstText(driver,
                    By.cssSelector("[class*='jhc__applications']"),
                    By.cssSelector(".applications"))));
            job.setJobDescription(firstText(driver,
                    By.cssSelector("[class*='dang-inner-html']"),
                    By.cssSelector("[class*='job-desc']"),
                    By.cssSelector(".job-description")));
            job.setRole(firstText(driver,
                    By.xpath("//div[contains(@class,'jhc__item__value') and preceding-sibling::div[contains(text(), 'Role')]]"),
                    By.cssSelector(".role")));
            job.setIndustryType(firstText(driver,
                    By.xpath("//div[contains(@class,'jhc__item__value') and preceding-sibling::div[contains(text(), 'Industry Type')]]"),
                    By.cssSelector(".industry-type")));
            job.setDepartment(firstText(driver,
                    By.xpath("//div[contains(@class,'jhc__item__value') and preceding-sibling::div[contains(text(), 'Department')]]"),
                    By.cssSelector(".department")));
            job.setEmploymentType(firstText(driver,
                    By.xpath("//div[contains(@class,'jhc__item__value') and preceding-sibling::div[contains(text(), 'Employment Type')]]"),
                    By.cssSelector(".employment-type")));
            job.setRoleCategory(firstText(driver,
                    By.xpath("//div[contains(@class,'jhc__item__value') and preceding-sibling::div[contains(text(), 'Role Category')]]"),
                    By.cssSelector(".role-category")));
            job.setEducation(firstText(driver,
                    By.xpath("//div[contains(@class,'jhc__item__value') and preceding-sibling::div[contains(text(), 'Education')]]"),
                    By.cssSelector(".education")));
            job.setKeySkills(firstText(driver,
                    By.cssSelector("[class*='jhc__skills']"),
                    By.cssSelector("[class*='key-skill']"),
                    By.cssSelector(".skills")));
            job.setLogdate(String.valueOf(LocalDateTime.now()));
            return job;
        } catch (Exception e) {
            logger.warning("Error extracting job details for " + url + ": " + e.getMessage());
            return null;
        }
    }

    private synchronized void flushBatch() {
        if (jobBatch.isEmpty()) {
            return;
        }

        List<JobListingEntity> toSave = new ArrayList<JobListingEntity>(jobBatch);
        jobBatch.clear();

        boolean isUpsert = Config.isUpsertDuplicates();
        int saved = BatchDatabaseHandler.saveJobsBatch(toSave, isUpsert);
        MetricsTracker.incrementJobsSaved(saved);
        if (!isUpsert) {
            MetricsTracker.incrementJobsInserted(saved);
        }

        String action = isUpsert ? "updated/inserted" : "inserted";
        logger.info("Batch " + action + ": " + saved + " jobs");
    }

    public synchronized void flushFinalBatch() {
        flushBatch();
    }

    private ChromeOptions buildChromeOptions() {
        ChromeOptions options = new ChromeOptions();
        options.setPageLoadStrategy(PageLoadStrategy.EAGER);

        if (Config.isBrowserHeadless()) {
            options.addArguments("--headless=new");
        }

        options.addArguments(
                "--disable-blink-features=AutomationControlled",
                "--disable-dev-shm-usage",
                "--no-sandbox",
                "--disable-gpu",
                "--window-size=1920,1080",
                "--disable-infobars",
                "--disable-notifications",
                "--disable-popup-blocking",
                "--disable-extensions",
                "--disable-default-apps",
                "--disable-background-timer-throttling",
                "--disable-backgrounding-occluded-windows",
                "--disable-renderer-backgrounding",
                "--disable-background-networking",
                "--disable-sync",
                "--disable-translate",
                "--mute-audio",
                "--no-first-run",
                "--no-default-browser-check",
                "--metrics-recording-only",
                "--password-store=basic"
        );

        String[] userAgents = new String[] {
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Safari/537.36",
                "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:132.0) Gecko/20100101 Firefox/132.0",
                "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.1 Safari/605.1.15",
                "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Safari/537.36"
        };
        String randomUA = userAgents[(int) (Math.random() * userAgents.length)];
        options.addArguments("--user-agent=" + randomUA);

        options.setExperimentalOption("excludeSwitches", new String[] {"enable-automation", "enable-logging"});
        options.setExperimentalOption("useAutomationExtension", false);

        Map<String, Object> prefs = new HashMap<String, Object>();
        prefs.put("credentials_enable_service", false);
        prefs.put("profile.password_manager_enabled", false);
        prefs.put("profile.default_content_setting_values.notifications", 2);
        prefs.put("profile.default_content_settings.popups", 0);
        if (Config.isDisableImages()) {
            prefs.put("profile.managed_default_content_settings.images", 2);
        }
        options.setExperimentalOption("prefs", prefs);
        return options;
    }

    private String firstText(WebDriver driver, By... selectors) {
        for (By selector : selectors) {
            String text = safeGetText(driver, selector);
            if (text != null && !text.isEmpty()) {
                return text;
            }
        }
        return "";
    }

    private String safeGetText(WebDriver driver, By selector) {
        try {
            return driver.findElement(selector).getText().trim();
        } catch (NoSuchElementException e) {
            return "";
        }
    }

    private String cleanCompanyName(String companyName) {
        if (companyName == null) {
            return "";
        }
        String cleaned = companyName.replaceAll("\\d+\\.\\d+.*", "").trim();
        // Validate company name length
        if (cleaned.length() > 200) {
            logger.fine("Company name too long, truncating: " + cleaned.substring(0, 50) + "...");
            return cleaned.substring(0, 200);
        }
        return cleaned;
    }

    private String parsePosted(String postedText) {
        if (postedText == null || postedText.isEmpty()) {
            return "";
        }
        String cleaned = postedText.replace("Posted:", "").replace("posted", "").trim();
        // Validate reasonable date formats
        if (cleaned.isEmpty() || cleaned.length() > 50) {
            logger.fine("Suspicious posted date format: " + postedText);
            return "";
        }
        return cleaned;
    }

    private String parseOpenings(String openingsText) {
        if (openingsText == null || openingsText.isEmpty()) {
            return "";
        }
        String cleaned = openingsText.replace("openings", "").replace("opening", "").trim();
        // Validate it's a reasonable number
        try {
            if (!cleaned.isEmpty()) {
                int openings = Integer.parseInt(cleaned.replaceAll("[^0-9]", ""));
                if (openings < 0 || openings > 1000) {
                    logger.fine("Suspicious openings value: " + openingsText);
                    return "";
                }
            }
        } catch (NumberFormatException e) {
            logger.fine("Could not parse openings: " + openingsText);
            return "";
        }
        return cleaned;
    }

    private String parseApplications(String applicationsText) {
        if (applicationsText == null || applicationsText.isEmpty()) {
            return "";
        }
        String cleaned = applicationsText.replace("applications", "").replace("application", "").trim();
        // Validate it's a reasonable number
        try {
            if (!cleaned.isEmpty()) {
                int apps = Integer.parseInt(cleaned.replaceAll("[^0-9]", ""));
                if (apps < 0 || apps > 10000) {
                    logger.fine("Suspicious applications value: " + applicationsText);
                    return "";
                }
            }
        } catch (NumberFormatException e) {
            logger.fine("Could not parse applications: " + applicationsText);
            return "";
        }
        return cleaned;
    }

    private boolean isValidJob(JobListingEntity job) {
        if (job == null) {
            return false;
        }
        if (job.getUrl() == null || job.getUrl().isEmpty()) {
            return false;
        }
        if (job.getTitle() == null || job.getTitle().isEmpty()) {
            return false;
        }

        // Validate URL format
        if (!job.getUrl().startsWith("http")) {
            logger.warning("Invalid URL format: " + job.getUrl());
            return false;
        }

        // Validate title is not just generic text
        if (job.getTitle().length() < 3 || job.getTitle().length() > 200) {
            logger.warning("Suspicious title length for " + job.getUrl() + ": " + job.getTitle().length());
            return false;
        }

        // Validate company name if present
        if (job.getCompanyName() != null && job.getCompanyName().length() > 200) {
            logger.warning("Company name too long for " + job.getUrl() + ", truncating");
            job.setCompanyName(job.getCompanyName().substring(0, 200));
        }

        return true;
    }
}
