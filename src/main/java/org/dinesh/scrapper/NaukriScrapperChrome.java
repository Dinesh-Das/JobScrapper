package org.dinesh.scrapper;


import org.dinesh.database.DatabaseHandler;
import org.dinesh.entity.JobListingEntity;
import org.openqa.selenium.*;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

public class NaukriScrapperChrome implements Scraper {
    private List<JobListingEntity> jobListingEntityList = new ArrayList<>();
    private Set<Integer> set = new LinkedHashSet<>();

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
        // ChromeDriver setup
        System.setProperty("webdriver.chrome.driver", "src\\main\\resources\\chromedriver.exe");

        ChromeOptions options = new ChromeOptions();

        // HEADLESS CONFIGURATION (Uncomment to enable)
        // options.addArguments("--headless=new");
        // options.addArguments("--disable-gpu");
        // options.addArguments("--no-sandbox");
        // options.addArguments("--disable-dev-shm-usage");
        // options.addArguments("--window-size=1920,1080");

        // PERFORMANCE OPTIMIZATIONS
        options.addArguments("--disable-extensions");
        options.addArguments("--disable-plugins");
        // options.addArguments("--disable-images"); // Uncomment for faster loading
        // options.addArguments("--disable-javascript"); // Be careful with this

        // OTHER OPTIONS
        options.addArguments("--no-first-run");
        options.addArguments("--disable-popup-blocking");
        options.addArguments("--disable-infobars");
        options.addArguments("--remote-allow-origins=*");

        // Additional Chrome-specific options
        options.addArguments("--disable-blink-features=AutomationControlled");
        options.addArguments("--user-agent=Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");

        WebDriver driver = new ChromeDriver(options);
        try {
            driver.get("https://www.naukri.com/software-developer-jobs-in-india-"+pageNo);
            int pageCnt = 0;
            while (true) {
                pageCnt++;
                set.add(pageCnt + pageNo);
                WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(10));
                WebElement element = wait.until(ExpectedConditions.visibilityOfElementLocated(By.cssSelector(".title")));
                List<WebElement> jobListings = driver.findElements(By.cssSelector(".srp-jobtuple-wrapper"));
                int elements = 0;
                int idx = 1;

                for (WebElement job : jobListings) {
                    elements++;
                    try {
                        try {
                            WebElement titleElement = job.findElement(By.cssSelector(".title"));
                            String url = titleElement.getAttribute("href");

                            if (titleElement.isEnabled()) {
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
                        } catch(Exception e) {
                            try {
                                System.out.println("Promotion hai ");
                                idx++;
                                wait = new WebDriverWait(driver, Duration.ofSeconds(10));

                                WebElement jobClick = driver.findElement(By.xpath("./html/body/div/div/main/div[1]/div[2]/div[2]/div/div[" + idx + "]/div/div[1]/a"));
                                WebElement titleElement = job.findElement(By.cssSelector(".title"));
                                String url = titleElement.getAttribute("href");
                                System.out.println(url);

                                if (jobClick.isEnabled()) {
                                    ((JavascriptExecutor) driver).executeScript("arguments[0].click();", jobClick);
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
                            } catch (Exception e1) {
                                System.out.println("Promotion hai ");
                                idx++;
                                wait = new WebDriverWait(driver, Duration.ofSeconds(10));

                                WebElement jobClick = driver.findElement(By.xpath("./html/body/div/div/main/div[1]/div[2]/div[2]/div/div[" + idx + "]/div/div[1]/a"));
                                WebElement titleElement = job.findElement(By.cssSelector(".title"));
                                String url = titleElement.getAttribute("href");
                                System.out.println(url);

                                if (jobClick.isEnabled()) {
                                    ((JavascriptExecutor) driver).executeScript("arguments[0].click();", jobClick);
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
                            }
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                        System.out.println("Error extracting job details2: " + e.getMessage());
                    }
                    idx++;
                }

                // Find the "Next" button
                WebElement nextButton = driver.findElement(By.xpath("//a[@class='styles_btn-secondary__2AsIP'][span[text()='Next']]"));
                if (nextButton.isEnabled()) {
                    ((JavascriptExecutor) driver).executeScript("arguments[0].click();", nextButton);
                    Thread.sleep(250);

                    WebDriverWait pageLoadWait = new WebDriverWait(driver, Duration.ofSeconds(10));
                    pageLoadWait.until(ExpectedConditions.stalenessOf(jobListings.get(0)));
                } else {
                    System.out.println("No more pages to scrape.");
                    break;
                }
                if(set.size() == 1000) break;
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            driver.quit();
        }
        System.out.println("SIZE OF SET : " + set.size());
        System.out.println("Elements :" + set);
    }

    private void extractJobDetails(WebDriver driver, String url) {
        try {
            List<WebElement> jobDescriptions = driver.findElements(By.cssSelector(".styles_left-section-container__btAcB"));
            for (WebElement jd : jobDescriptions) {
                try {
                    String title = safeGetText(jd, By.cssSelector(".styles_jd-header-title__rZwM1"));
                    String companyRaw = safeGetText(jd, By.cssSelector(".styles_jd-header-comp-name__MvqAI a"));
                    String company = cleanCompanyName(companyRaw);
                    String rating = safeGetText(jd, By.cssSelector(".styles_amb-rating__4UyFL"));
                    String reviewsRaw = safeGetText(jd, By.cssSelector(".styles_amb-reviews__0J1e3"));
                    String reviews = extractNumber(reviewsRaw);
                    String exp = safeGetText(jd, By.cssSelector(".styles_jhc__exp__k_giM span"));
                    String salary = safeGetText(jd, By.cssSelector(".styles_jhc__salary__jdfEC span"));
                    String location = safeGetText(jd, By.cssSelector(".styles_jhc__loc___Du2H span"));
                    
                    // Extract and parse posted/openings/applications
                    String postedRaw = "";
                    String openingsRaw = "";
                    String applicationRaw = "";
                    
                    try {
                        List<WebElement> stats = jd.findElements(By.cssSelector(".styles_jhc__jd-stats__KrId0 .styles_jhc__stat__PgY67"));
                        for (WebElement stat : stats) {
                            String statText = stat.getText();
                            if (statText.contains("Posted:") || statText.contains("days ago") || statText.contains("Just now")) {
                                postedRaw = statText;
                            } else if (statText.contains("Openings:")) {
                                openingsRaw = statText;
                            } else if (statText.contains("Applicants:") || statText.contains("Less than")) {
                                applicationRaw = statText;
                            }
                        }
                    } catch (Exception e) {
                        // Fallback
                        String bottomText = safeGetText(jd, By.cssSelector(".styles_jhc__bottom__DrTmB"));
                        postedRaw = bottomText;
                        openingsRaw = bottomText;
                        applicationRaw = bottomText;
                    }
                    
                    // Parse and clean the values
                    String posted = parsePosted(postedRaw);
                    String openings = parseOpenings(openingsRaw);
                    String application = parseApplications(applicationRaw);
                    
                    String jobDesc = safeGetText(jd, By.cssSelector(".styles_JDC__dang-inner-html__h0K4t"));
                    
                    // Extract role details separately
                    String role = "";
                    String industry = "";
                    String depart = "";
                    String employmentType = "";
                    String roleCat = "";
                    try {
                        List<WebElement> details = jd.findElements(By.cssSelector(".styles_other-details__oEN4O .styles_details__Y424J"));
                        for (WebElement detail : details) {
                            String labelText = safeGetText(detail, By.cssSelector("label"));
                            String valueText = detail.getText().replace(labelText, "").trim();
                            
                            if (labelText.contains("Role:")) {
                                role = valueText;
                            } else if (labelText.contains("Industry Type:")) {
                                industry = valueText;
                            } else if (labelText.contains("Department:")) {
                                depart = valueText;
                            } else if (labelText.contains("Employment Type:")) {
                                employmentType = valueText;
                            } else if (labelText.contains("Role Category:")) {
                                roleCat = valueText;
                            }
                        }
                    } catch (Exception e) {
                        // Fallback
                        String otherDetails = safeGetText(jd, By.cssSelector(".styles_other-details__oEN4O"));
                        role = otherDetails;
                        industry = otherDetails;
                        depart = otherDetails;
                        employmentType = otherDetails;
                        roleCat = otherDetails;
                    }
                    
                    // Extract education
                    String edu = "";
                    try {
                        StringBuilder education = new StringBuilder();
                        List<WebElement> eduDetails = jd.findElements(By.cssSelector(".styles_education__KXFkO .styles_details__Y424J"));
                        for (WebElement eduDetail : eduDetails) {
                            String eduText = eduDetail.getText();
                            if (!eduText.isEmpty()) {
                                education.append(eduText).append("\n");
                            }
                        }
                        edu = education.toString().trim();
                    } catch (Exception e) {
                        edu = safeGetText(jd, By.cssSelector(".styles_education__KXFkO"));
                    }
                    
                    String keySkill = "";
                    List<WebElement> keySkills = driver.findElements(By.cssSelector(".styles_key-skill__GIPn_ a.styles_chip__7YCfG span"));
                    for (WebElement skill : keySkills) {
                        String skillText = skill.getText();
                        if (!skillText.isEmpty()) {
                            keySkill += skillText + ",";
                        }
                    }

                    JobListingEntity obj = new JobListingEntity();
                    obj.setUrl(url);
                    obj.setTitle(title);
                    obj.setCompanyName(company);
                    obj.setRating(rating);
                    obj.setReviews(reviews);
                    obj.setExperience(exp);
                    obj.setSalary(salary);
                    obj.setLocation(location);
                    obj.setPosted(posted);
                    obj.setOpenings(openings);
                    obj.setApplications(application);
                    obj.setJobDescription(jobDesc);
                    obj.setRole(role);
                    obj.setIndustryType(industry);
                    obj.setDepartment(depart);
                    obj.setEmploymentType(employmentType);
                    obj.setRoleCategory(roleCat);
                    obj.setEducation(edu);
                    obj.setKeySkills(keySkill);
                    obj.setSource("Naukri");
                    obj.setLogdate(LocalDateTime.now().toString());

                    DatabaseHandler db = new DatabaseHandler();
                    DatabaseHandler.saveJob(obj);
                } catch (Exception e) {
                    System.out.println("Error In New Page Maybe Layout Change: " + e.getMessage());
                    e.printStackTrace();
                }
            }
        } catch(Exception e) {
            System.out.println("Some different layout");
        }
    }
}