package org.dinesh.entity;

public class JobListingEntity {
    private String url;                // Primary Key
    private String title;
    private String companyName;
    private String rating;
    private String reviews;
    private String experience;
    private String salary;
    private String location;
    private String posted;
    private String openings;
    private String applications;
    private String jobDescription;
    private String role;
    private String industryType;
    private String department;
    private String employmentType;
    private String roleCategory;
    private String education;
    private String keySkills;
    private String logdate;
    private String source;

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getCompanyName() {
        return companyName;
    }

    public void setCompanyName(String companyName) {
        this.companyName = companyName;
    }

    public String getRating() {
        return rating;
    }

    public void setRating(String rating) {
        this.rating = rating;
    }

    public String getReviews() {
        return reviews;
    }

    public void setReviews(String reviews) {
        this.reviews = reviews;
    }

    public String getExperience() {
        return experience;
    }

    public void setExperience(String experience) {
        this.experience = experience;
    }

    public String getSalary() {
        return salary;
    }

    public void setSalary(String salary) {
        this.salary = salary;
    }

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }

    public String getPosted() {
        return posted;
    }

    public void setPosted(String posted) {
        this.posted = posted;
    }

    public String getOpenings() {
        return openings;
    }

    public void setOpenings(String openings) {
        this.openings = openings;
    }

    public String getApplications() {
        return applications;
    }

    public void setApplications(String applications) {
        this.applications = applications;
    }

    public String getJobDescription() {
        return jobDescription;
    }

    public void setJobDescription(String jobDescription) {
        this.jobDescription = jobDescription;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getIndustryType() {
        return industryType;
    }

    public void setIndustryType(String industryType) {
        this.industryType = industryType;
    }

    public String getDepartment() {
        return department;
    }

    public void setDepartment(String department) {
        this.department = department;
    }

    public String getEmploymentType() {
        return employmentType;
    }

    public void setEmploymentType(String employmentType) {
        this.employmentType = employmentType;
    }

    public String getRoleCategory() {
        return roleCategory;
    }

    public void setRoleCategory(String roleCategory) {
        this.roleCategory = roleCategory;
    }

    public String getEducation() {
        return education;
    }

    public void setEducation(String education) {
        this.education = education;
    }

    public String getKeySkills() {
        return keySkills;
    }

    public void setKeySkills(String keySkills) {
        this.keySkills = keySkills;
    }

    public String getLogdate() {
        return logdate;
    }

    public void setLogdate(String logdate) {
        this.logdate = logdate;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    @Override
    public String toString() {
        return "JobListingEntity{" +
                "URL='" + url + '\n' +
                ", title='" + title + '\n' +
                ", companyName='" + companyName + '\n' +
                ", rating='" + rating + '\n' +
                ", reviews='" + reviews + '\n' +
                ", experience='" + experience + '\n' +
                ", salary='" + salary + '\n' +
                ", location='" + location + '\n' +
                ", posted='" + posted + '\n' +
                ", openings='" + openings + '\n' +
                ", applications='" + applications + '\n' +
                ", jobDescription='" + jobDescription + '\n' +
                ", role='" + role + '\n' +
                ", industryType='" + industryType + '\n' +
                ", department='" + department + '\n' +
                ", employmentType='" + employmentType + '\n' +
                ", roleCategory='" + roleCategory + '\n' +
                ", education='" + education + '\n' +
                ", keySkills='" + keySkills + '\n' +
                ", logdate='" + logdate + '\n' +
                ",source='"+source+'\n'+
                '}';
    }
}
