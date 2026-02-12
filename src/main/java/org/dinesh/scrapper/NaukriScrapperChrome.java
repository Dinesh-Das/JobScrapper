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

    @Override
    public void Scrape(int pageNo) {
        // ChromeDriver setup
        System.setProperty("webdriver.chrome.driver", "src\main\resources\chromedriver.exe");

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
                    String company = safeGetText(jd, By.cssSelector(".styles_jd-header-comp-name__MvqAI"));
                    String rating = safeGetText(jd, By.cssSelector(".styles_amb-rating__4UyFL"));
                    String reviews = safeGetText(jd, By.cssSelector(".styles_amb-reviews__0J1e3"));
                    String exp = safeGetText(jd, By.cssSelector(".styles_jhc__exp__k_giM"));
                    String salary = safeGetText(jd, By.cssSelector(".styles_jhc__salary__jdfEC"));
                    String location = safeGetText(jd, By.cssSelector(".styles_jhc__loc___Du2H"));
                    String posted = safeGetText(jd, By.cssSelector(".styles_jhc__bottom__DrTmB"));
                    String openings = safeGetText(jd, By.cssSelector(".styles_jhc__bottom__DrTmB"));
                    String application = safeGetText(jd, By.cssSelector(".styles_jhc__bottom__DrTmB"));
                    String jobDesc = safeGetText(jd, By.cssSelector(".styles_job-desc-container__txpYf"));
                    String role = safeGetText(jd, By.cssSelector(".styles_other-details__oEN4O"));
                    String industry = safeGetText(jd, By.cssSelector(".styles_other-details__oEN4O"));
                    String depart = safeGetText(jd, By.cssSelector(".styles_other-details__oEN4O"));
                    String employmentType = safeGetText(jd, By.cssSelector(".styles_other-details__oEN4O"));
                    String roleCat = safeGetText(jd, By.cssSelector(".styles_other-details__oEN4O"));
                    String edu = safeGetText(jd, By.cssSelector(".styles_education__KXFkO"));
                    String keySkill = "";

                    List<WebElement> keySkills = driver.findElements(By.cssSelector("a.styles_chip__7YCfG span"));
                    for (WebElement skill : keySkills) {
                        keySkill += skill.getText() + ",";
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
                }
            }
        } catch(Exception e) {
            System.out.println("Some different layout");
        }
    }
}