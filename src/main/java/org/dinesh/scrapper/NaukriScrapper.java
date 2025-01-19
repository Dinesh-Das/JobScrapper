package org.dinesh.scrapper;

import org.dinesh.database.DatabaseHandler;
import org.dinesh.entity.JobListingEntity;
import org.openqa.selenium.*;
import org.openqa.selenium.edge.EdgeDriver;
import org.openqa.selenium.edge.EdgeOptions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class NaukriScrapper implements  Scraper{
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
        System.setProperty("webdriver.edge.driver", "C:\\Users\\dines\\OneDrive\\Desktop\\Project\\JobListingScrapper\\src\\main\\resources\\msedgedriver.exe");

        WebDriver driver = new EdgeDriver();
        try {
            driver.get("https://www.naukri.com/software-developer-jobs-in-india-"+pageNo);
            int pageCnt=0;
            while (true) {
                pageCnt++;
                set.add(pageCnt+pageNo);
                WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(30));
                WebElement element = wait.until(ExpectedConditions.visibilityOfElementLocated(By.cssSelector(".title")));
                List<WebElement> jobListings = driver.findElements(By.cssSelector(".srp-jobtuple-wrapper"));
                int elements=0;
                int idx=1;
                for (WebElement job : jobListings) {
                elements++;
                    try {
                        try{
                        WebElement jobClick = driver.findElement(By.xpath("./html/body/div/div/main/div[1]/div[2]/div[2]/div/div[" + idx + "]/div/div[1]/a"));
                        WebElement titleElement = job.findElement(By.cssSelector(".title"));
                        String url = titleElement.getAttribute("href");

                        if (jobClick.isEnabled()) {
                            ((JavascriptExecutor) driver).executeScript("arguments[0].click();", jobClick);
                            Thread.sleep(1500);
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
                    }catch(Exception e){
                            try{
                                System.out.println("Promotion hai ");
                                idx++;
                                wait = new WebDriverWait(driver, Duration.ofSeconds(30));
                                //a[contains(@class, 'title')]

                                WebElement jobClick = driver.findElement(By.xpath("./html/body/div/div/main/div[1]/div[2]/div[2]/div/div[" + idx + "]/div/div[1]/a"));

                                WebElement titleElement = job.findElement(By.cssSelector(".title"));
                                String url = titleElement.getAttribute("href");
                                System.out.println(url);
                                if (jobClick.isEnabled()) {
                                    ((JavascriptExecutor) driver).executeScript("arguments[0].click();", jobClick);
                                    Thread.sleep(1500);
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
                            }catch (Exception e1){
                                System.out.println("Promotion hai ");
                                idx++;
                                wait = new WebDriverWait(driver, Duration.ofSeconds(30));
                                //a[contains(@class, 'title')]

                                WebElement jobClick = driver.findElement(By.xpath("./html/body/div/div/main/div[1]/div[2]/div[2]/div/div[" + idx + "]/div/div[1]/a"));

                                WebElement titleElement = job.findElement(By.cssSelector(".title"));
                                String url = titleElement.getAttribute("href");
                                System.out.println(url);
                                if (jobClick.isEnabled()) {
                                    ((JavascriptExecutor) driver).executeScript("arguments[0].click();", jobClick);
                                    Thread.sleep(1500);
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
//                        End of Job Click handler

                    } catch (Exception e) {
                        e.printStackTrace();
                        System.out.println("Error extracting job details2: " + e.getMessage());
                    }
//                    System.out.println("Current Page : "+ (pageCnt+pageNo) +" Element Count :"+elements +" Index : "+idx);
                    idx++;
                }
                // Find the "Next" button
                WebElement nextButton = driver.findElement(By.xpath("//a[@class='styles_btn-secondary__2AsIP'][span[text()='Next']]"));
                // Check if the "Next" button is enabled before clicking
                if (nextButton.isEnabled()) {
                    // Click the "Next" button using JavaScript
                    ((JavascriptExecutor) driver).executeScript("arguments[0].click();", nextButton);

                    Thread.sleep(1500);
                    // Wait for the next page to load
                    WebDriverWait pageLoadWait = new WebDriverWait(driver, Duration.ofSeconds(30));
                    pageLoadWait.until(ExpectedConditions.stalenessOf(jobListings.get(0)));

                } else {
                    // Exit the loop if no "Next" button is available (e.g., at the last page)
                    System.out.println("No more pages to scrape.");
                    break;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            driver.quit();
        }
        System.out.println("SIZE OF SET : "+set.size());
        System.out.println("Elements :"+set);
    }

    private void extractJobDetails(WebDriver driver,String url){
        try{
            // Extract job description details
            List<WebElement> jobDescriptions = driver.findElements(By.cssSelector(".styles_left-section-container__btAcB"));
            for (WebElement jd : jobDescriptions) {
                try {
                    String title = safeGetText(jd, By.cssSelector(".styles_jd-header-title__rZwM1"));
                    String company = safeGetText(jd, By.cssSelector(".styles_jd-header-comp-name__MvqAI"));
                    String rating = safeGetText(jd, By.cssSelector(".styles_amb-rating__4UyFL"));
                    String reviews = safeGetText(jd, By.cssSelector(".styles_amb-reviews__0J1e3"));
                    String exp = safeGetText(jd, By.cssSelector(".styles_jhc__exp__k_giM"));
                    String salary = safeGetText(jd, By.cssSelector(".styles_jhc__salary__jdfEC"));
                    String location= safeGetText(jd, By.cssSelector(".styles_jhc__loc___Du2H"));
                    String posted= safeGetText(jd, By.cssSelector(".styles_jhc__bottom__DrTmB"));
                    String openings= safeGetText(jd, By.cssSelector(".styles_jhc__bottom__DrTmB"));
                    String application= safeGetText(jd, By.cssSelector(".styles_jhc__bottom__DrTmB"));
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
                        keySkill+= skill.getText() +",";
                    }
//                    Will handle in the db query
//                    String regex = "Posted:\\s*([\\w\\s]+)\\s*Openings:\\s*(\\d+)\\s*Applicants:\\s*(\\d+)";
//                    Pattern pattern = Pattern.compile(regex);
//                    Matcher matcher = pattern.matcher(posted);
//                    if (matcher.find()) {
//                        posted = matcher.group(1).trim();
//                        openings = matcher.group(2).trim();
//                        application = matcher.group(3).trim();
//                    } else {
////                        System.out.println("No match found for posted, openings, and applicants.");
//                    }
//                    Will handle in the db query level
//                    String regex1 = "(Role):\\s*([\\w\\s-]+)\\s*(Industry Type):\\s*([\\w\\s&]+)\\s*(Department):\\s*([\\w\\s&/-]+)\\s*(Employment Type):\\s*([\\w\\s,]+)\\s*(Role Category):\\s*([\\w\\s]+)";
//                    Pattern pattern1 = Pattern.compile(regex1);
//                    Matcher matcher1 = pattern1.matcher(role);
//                    if (matcher1.find()) {
//                        role = matcher1.group(2).trim();
//                        industry = matcher1.group(4).trim();
//                        depart = matcher1.group(6).trim();
//                        employmentType = matcher1.group(8).trim();
//                        roleCat = matcher1.group(10).trim();
//                    } else {
////                        System.out.println("No match found for role, industry, department, employment type, and role category.");
//                    }

                    JobListingEntity obj= new JobListingEntity();
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
        }catch(Exception e){
            System.out.println("Some different layout");
        }
    }


}
