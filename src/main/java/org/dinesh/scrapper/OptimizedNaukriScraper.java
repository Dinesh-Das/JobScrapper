package org.dinesh.scrapper;

import io.github.bonigarcia.wdm.WebDriverManager;
import org.dinesh.config.Config;
import org.dinesh.database.BatchDatabaseHandler;
import org.dinesh.entity.JobListingEntity;
import org.openqa.selenium.*;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

public class OptimizedNaukriScraper implements Scraper {
    private final List<JobListingEntity> jobBatch = Collections.synchronizedList(new ArrayList<>());
    private final Set<Integer> processedPages = Collections.synchronizedSet(new LinkedHashSet<>());
    private final int batchSize;
    
    public OptimizedNaukriScraper() {
        this.batchSize = Config.getBatchSize();
    }
    
    private String safeGetText(WebElement job, By selector) {
        try {
            WebElement element = job.findElement(selector);
            return element.getText();
        } catch (Exception e) {
            return "";
        }
    }
    
    private String extractNumber(String text) {
        if (text == null || text.isEmpty()) return "0";
        text = text.replaceAll("[^0-9]", "");
        return text.isEmpty() ? "0" : text;
    }
    
    private String cleanCompanyName(String companyName) {
        if (companyName == null || companyName.isEmpty()) return "";
        // Remove rating/reviews text like "4.5 Reviews" or "Employees' choice"
        return companyName.replaceAll("(?i)[\\n\\r]?[0-9\\.]+\\s*Reviews.*|Employees'\\s*choice.*", "").trim();
    }
    
    private String parsePosted(String postedText) {
        if (postedText == null || postedText.isEmpty()) return "0";
        if (postedText.toLowerCase().contains("just now")) return "0";
        
        // Extract number from "4 days ago" or "Posted: 4 days ago"
        String number = postedText.replaceAll("(?i)Posted:\\s*", "")
                                   .replaceAll("(?i)\\s*days?\\s*ago.*", "")
                                   .replaceAll("[^0-9+]", "");
        return number.isEmpty() ? "0" : number;
    }
    
    private String parseOpenings(String openingsText) {
        if (openingsText == null || openingsText.isEmpty()) return "1";
        
        // Extract number from "Openings: 5" or just "5"
        String number = openingsText.replaceAll("(?i)Openings:\\s*", "")
                                     .replaceAll("[^0-9]", "");
        return number.isEmpty() ? "1" : number;
    }
    
    private String parseApplications(String applicationsText) {
        if (applicationsText == null || applicationsText.isEmpty()) return "0";
        
        // Handle "Less than 50" or "Applicants: 100"
        String number = applicationsText.replaceAll("(?i)Applicants:\\s*", "")
                                        .replaceAll("(?i)Less\\s*than\\s*", "")
                                        .replaceAll("[^0-9]", "");
        return number.isEmpty() ? "0" : number;
    }
    
    @Override
    public void Scrape(int pageNo) {
        // Auto-manage ChromeDriver
        WebDriverManager.chromedriver().setup();
        
        ChromeOptions options = buildChromeOptions();
        WebDriver driver = new ChromeDriver(options);
        
        try {
            System.out.println("Thread " + Thread.currentThread().getName() + " - Loading page: " + pageNo);
            driver.get("https://www.naukri.com/software-developer-jobs-in-india-" + pageNo);
            System.out.println("Thread " + Thread.currentThread().getName() + " - Page loaded, waiting for elements");
            
            int pageCnt = 0;
            
            while (pageCnt < Config.getMaxPagesPerThread()) {
                pageCnt++;
                processedPages.add(pageCnt + pageNo);
                
                WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(Config.getBrowserWaitTimeout()));
                System.out.println("Thread " + Thread.currentThread().getName() + " - Waiting for .title elements");
                
                try {
                    wait.until(ExpectedConditions.visibilityOfElementLocated(By.cssSelector(".title")));
                } catch (Exception e) {
                    System.err.println("Thread " + Thread.currentThread().getName() + " - Timeout waiting for .title: " + e.getMessage());
                    System.err.println("Current URL: " + driver.getCurrentUrl());
                    System.err.println("Page title: " + driver.getTitle());
                    break;
                }
                
                System.out.println("Thread " + Thread.currentThread().getName() + " - Found .title elements");
                List<WebElement> jobListings = driver.findElements(By.cssSelector(".srp-jobtuple-wrapper"));
                System.out.println("Thread " + Thread.currentThread().getName() + " - Found " + jobListings.size() + " job listings");
                int idx = 1;
                
                for (WebElement job : jobListings) {
                    try {
                        processJobListing(driver, job, wait, idx);
                    } catch (Exception e) {
                        System.err.println("Error processing job at index " + idx + ": " + e.getMessage());
                    }
                    idx++;
                    
                    // Flush batch if full
                    if (jobBatch.size() >= batchSize) {
                        flushBatch();
                    }
                }
                
                // Navigate to next page
                if (!navigateToNextPage(driver, jobListings)) {
                    break;
                }
                
                Thread.sleep(Config.getPageLoadDelay());
            }
            
            // Flush remaining jobs
            flushBatch();
            
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            driver.quit();
        }
        
        System.out.println("Thread completed. Pages processed: " + processedPages.size());
    }
    
    private ChromeOptions buildChromeOptions() {
        ChromeOptions options = new ChromeOptions();
        
        // Headless mode
        if (Config.isBrowserHeadless()) {
            options.addArguments("--headless=new");
            options.addArguments("--disable-gpu");
            options.addArguments("--no-sandbox");
            options.addArguments("--disable-dev-shm-usage");
            options.addArguments("--window-size=1920,1080");
        }
        
        // Performance optimizations
        options.addArguments("--disable-extensions");
        options.addArguments("--disable-plugins");
        
        if (Config.isDisableImages()) {
            options.addArguments("--blink-settings=imagesEnabled=false");
        }
        
        // Anti-detection
        options.addArguments("--disable-blink-features=AutomationControlled");
        options.addArguments("--user-agent=Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
        options.addArguments("--no-first-run");
        options.addArguments("--disable-popup-blocking");
        options.addArguments("--disable-infobars");
        options.addArguments("--remote-allow-origins=*");
        
        return options;
    }
    
    private void processJobListing(WebDriver driver, WebElement job, WebDriverWait wait, int idx) throws Exception {
        WebElement titleElement = job.findElement(By.cssSelector(".title"));
        String url = titleElement.getAttribute("href");
        
        if (!titleElement.isEnabled()) {
            return;
        }
        
        ((JavascriptExecutor) driver).executeScript("arguments[0].click();", titleElement);
        Thread.sleep(250);
        
        String currentTab = driver.getWindowHandle();
        wait.until(d -> driver.getWindowHandles().size() > 1);
        
        for (String tab : driver.getWindowHandles()) {
            if (!tab.equals(currentTab)) {
                driver.switchTo().window(tab);
                break;
            }
        }
        
        extractJobDetails(driver, url);
        driver.close();
        driver.switchTo().window(currentTab);
    }
    
    private boolean navigateToNextPage(WebDriver driver, List<WebElement> jobListings) {
        try {
            WebElement nextButton = driver.findElement(
                By.xpath("//a[@class='styles_btn-secondary__2AsIP'][span[text()='Next']]")
            );
            
            if (nextButton.isEnabled()) {
                ((JavascriptExecutor) driver).executeScript("arguments[0].click();", nextButton);
                Thread.sleep(250);
                
                WebDriverWait pageLoadWait = new WebDriverWait(driver, Duration.ofSeconds(Config.getBrowserWaitTimeout()));
                pageLoadWait.until(ExpectedConditions.stalenessOf(jobListings.get(0)));
                return true;
            }
        } catch (Exception e) {
            System.out.println("No more pages to scrape.");
        }
        return false;
    }
    
    private void extractJobDetails(WebDriver driver, String url) {
        try {
            List<WebElement> jobDescriptions = driver.findElements(By.cssSelector(".styles_left-section-container__btAcB"));
            
            for (WebElement jd : jobDescriptions) {
                try {
                    JobListingEntity job = new JobListingEntity();
                    job.setUrl(url);
                    job.setTitle(safeGetText(jd, By.cssSelector(".styles_jd-header-title__rZwM1")));
                    
                    // Extract and clean company name
                    String companyRaw = safeGetText(jd, By.cssSelector(".styles_jd-header-comp-name__MvqAI a"));
                    job.setCompanyName(cleanCompanyName(companyRaw));
                    
                    job.setRating(safeGetText(jd, By.cssSelector(".styles_amb-rating__4UyFL")));
                    
                    // Extract and clean reviews (just the number)
                    String reviewsRaw = safeGetText(jd, By.cssSelector(".styles_amb-reviews__0J1e3"));
                    job.setReviews(extractNumber(reviewsRaw));
                    
                    job.setExperience(safeGetText(jd, By.cssSelector(".styles_jhc__exp__k_giM span")));
                    job.setSalary(safeGetText(jd, By.cssSelector(".styles_jhc__salary__jdfEC span")));
                    job.setLocation(safeGetText(jd, By.cssSelector(".styles_jhc__loc___Du2H span")));
                    
                    // Extract and parse posted/openings/applications
                    String postedRaw = "";
                    String openingsRaw = "";
                    String applicationsRaw = "";
                    
                    try {
                        List<WebElement> stats = jd.findElements(By.cssSelector(".styles_jhc__jd-stats__KrId0 .styles_jhc__stat__PgY67"));
                        for (WebElement stat : stats) {
                            String statText = stat.getText();
                            if (statText.contains("Posted:") || statText.contains("days ago") || statText.contains("Just now")) {
                                postedRaw = statText;
                            } else if (statText.contains("Openings:")) {
                                openingsRaw = statText;
                            } else if (statText.contains("Applicants:") || statText.contains("Less than")) {
                                applicationsRaw = statText;
                            }
                        }
                    } catch (Exception e) {
                        // Fallback - try to parse from combined text
                        String bottomText = safeGetText(jd, By.cssSelector(".styles_jhc__bottom__DrTmB"));
                        postedRaw = bottomText;
                        openingsRaw = bottomText;
                        applicationsRaw = bottomText;
                    }
                    
                    // Parse and clean the values
                    job.setPosted(parsePosted(postedRaw));
                    job.setOpenings(parseOpenings(openingsRaw));
                    job.setApplications(parseApplications(applicationsRaw));
                    
                    job.setJobDescription(safeGetText(jd, By.cssSelector(".styles_JDC__dang-inner-html__h0K4t")));
                    
                    // Extract role details separately
                    try {
                        List<WebElement> details = jd.findElements(By.cssSelector(".styles_other-details__oEN4O .styles_details__Y424J"));
                        for (WebElement detail : details) {
                            String labelText = safeGetText(detail, By.cssSelector("label"));
                            String valueText = detail.getText().replace(labelText, "").trim();
                            
                            if (labelText.contains("Role:")) {
                                job.setRole(valueText);
                            } else if (labelText.contains("Industry Type:")) {
                                job.setIndustryType(valueText);
                            } else if (labelText.contains("Department:")) {
                                job.setDepartment(valueText);
                            } else if (labelText.contains("Employment Type:")) {
                                job.setEmploymentType(valueText);
                            } else if (labelText.contains("Role Category:")) {
                                job.setRoleCategory(valueText);
                            }
                        }
                    } catch (Exception e) {
                        // Fallback to old method
                        String otherDetails = safeGetText(jd, By.cssSelector(".styles_other-details__oEN4O"));
                        job.setRole(otherDetails);
                        job.setIndustryType(otherDetails);
                        job.setDepartment(otherDetails);
                        job.setEmploymentType(otherDetails);
                        job.setRoleCategory(otherDetails);
                    }
                    
                    // Extract education
                    try {
                        StringBuilder education = new StringBuilder();
                        List<WebElement> eduDetails = jd.findElements(By.cssSelector(".styles_education__KXFkO .styles_details__Y424J"));
                        for (WebElement eduDetail : eduDetails) {
                            String eduText = eduDetail.getText();
                            if (!eduText.isEmpty()) {
                                education.append(eduText).append("\n");
                            }
                        }
                        job.setEducation(education.toString().trim());
                    } catch (Exception e) {
                        job.setEducation(safeGetText(jd, By.cssSelector(".styles_education__KXFkO")));
                    }
                    
                    // Extract key skills
                    StringBuilder keySkills = new StringBuilder();
                    List<WebElement> skillElements = driver.findElements(By.cssSelector(".styles_key-skill__GIPn_ a.styles_chip__7YCfG span"));
                    for (WebElement skill : skillElements) {
                        String skillText = skill.getText();
                        if (!skillText.isEmpty()) {
                            keySkills.append(skillText).append(",");
                        }
                    }
                    job.setKeySkills(keySkills.toString());
                    
                    job.setSource("Naukri");
                    job.setLogdate(LocalDateTime.now().toString());
                    
                    // Add to batch
                    jobBatch.add(job);
                    
                } catch (Exception e) {
                    System.err.println("Error extracting job details: " + e.getMessage());
                    e.printStackTrace();
                }
            }
        } catch (Exception e) {
            System.err.println("Layout error: " + e.getMessage());
        }
    }
    
    private synchronized void flushBatch() {
        if (jobBatch.isEmpty()) {
            return;
        }
        
        List<JobListingEntity> toSave = new ArrayList<>(jobBatch);
        jobBatch.clear();
        
        int saved = BatchDatabaseHandler.saveJobsBatch(toSave);
        System.out.println("Flushed batch: " + saved + " jobs saved");
    }
}
