package org.dinesh.database;

import org.dinesh.entity.JobListingEntity;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.LogManager;
import java.io.FileInputStream;
import oracle.jdbc.OracleConnection;
import oracle.sql.CLOB;

public class DatabaseHandler {

    private static final Logger LOGGER = Logger.getLogger(DatabaseHandler.class.getName());
    private static final String DB_URL = "jdbc:oracle:thin:@127.0.0.1:1521:XE"; // Update Oracle DB URL
    private static final String USER = "system";
    private static final String PASS = "admin";

    static {
        try (FileInputStream configFile = new FileInputStream("C:\\Users\\dines\\OneDrive\\Desktop\\Project\\JobListingScrapper\\src\\main\\java\\org\\dinesh\\database\\logger.properties")) {
            LogManager.getLogManager().readConfiguration(configFile);
        } catch (Exception e) {
            System.err.println("Could not set up logger configuration: " + e.getMessage());
        }
    }

    private static String truncate(String value, int maxLength) {
        return value != null && value.length() > maxLength ? value.substring(0, maxLength) : value;
    }


    public static void saveJob(JobListingEntity job) {
        if (job == null || job.getUrl() == null || job.getUrl().isEmpty()) {
            LOGGER.log(Level.WARNING, "Invalid Job entity or missing URL");
            return;
        }

        String sql = "MERGE INTO JobDetails t " +
                "USING (SELECT ? AS URL FROM DUAL) s " +
                "ON (t.URL = s.URL) " +
                "WHEN MATCHED THEN " +
                "UPDATE SET " +
                "    Title = ?, " +
                "    CompanyName = ?, " +
                "    Rating = ?, " +
                "    Reviews = ?, " +
                "    Experience = ?, " +
                "    Salary = ?, " +
                "    Location = ?, " +
                "    Posted = ?, " +
                "    Openings = ?, " +
                "    Applications = ?, " +
                "    JobDescription = ?, " +
                "    Role = ?, " +
                "    IndustryType = ?, " +
                "    Department = ?, " +
                "    EmploymentType = ?, " +
                "    RoleCategory = ?, " +
                "    Education = ?, " +
                "    KeySkills = ?, " +
                "    Logdate = SYSTIMESTAMP " +
                "WHEN NOT MATCHED THEN " +
                "INSERT (URL, Title, CompanyName, Rating, Reviews, Experience, Salary, Location, Posted, Openings, Applications, JobDescription, Role, IndustryType, Department, EmploymentType, RoleCategory, Education, KeySkills, Logdate) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, SYSTIMESTAMP)";

        try (Connection conn = DriverManager.getConnection(DB_URL, USER, PASS);
             PreparedStatement preparedStatement = conn.prepareStatement(sql)) {

            // Create OracleClob objects for CLOB fields
            CLOB jobDescriptionClob = CLOB.createTemporary((OracleConnection) conn, true, CLOB.DURATION_SESSION);
            jobDescriptionClob.setString(1, job.getJobDescription());

            CLOB educationClob = CLOB.createTemporary((OracleConnection) conn, true, CLOB.DURATION_SESSION);
            educationClob.setString(1, job.getEducation());

            CLOB keySkillsClob = CLOB.createTemporary((OracleConnection) conn, true, CLOB.DURATION_SESSION);
            keySkillsClob.setString(1, job.getKeySkills());

            // Set parameters
            preparedStatement.setString(1, job.getUrl());
            preparedStatement.setString(2, truncate(job.getTitle(), 200));
            preparedStatement.setString(3, truncate(job.getCompanyName(), 200));
            preparedStatement.setString(4, truncate(job.getRating(), 10));
            preparedStatement.setString(5, truncate(job.getReviews(), 30));
            preparedStatement.setString(6, truncate(job.getExperience(), 30));
            preparedStatement.setString(7, truncate(job.getSalary(), 60));
            preparedStatement.setString(8, truncate(job.getLocation(), 300));
            preparedStatement.setString(9, truncate(job.getPosted(), 100));
            preparedStatement.setString(10, truncate(job.getOpenings(), 100));
            preparedStatement.setString(11, truncate(job.getApplications(), 100));
            preparedStatement.setClob(12, jobDescriptionClob);
            preparedStatement.setString(13, truncate(job.getRole(), 300));
            preparedStatement.setString(14, truncate(job.getIndustryType(), 300));
            preparedStatement.setString(15, truncate(job.getDepartment(), 300));
            preparedStatement.setString(16, truncate(job.getEmploymentType(), 300));
            preparedStatement.setString(17, truncate(job.getRoleCategory(), 300));
            preparedStatement.setClob(18, educationClob);
            preparedStatement.setClob(19, keySkillsClob);

            // Duplicate for INSERT values
            preparedStatement.setString(20, job.getUrl());
            preparedStatement.setString(21, truncate(job.getTitle(), 200));
            preparedStatement.setString(22, truncate(job.getCompanyName(), 200));
            preparedStatement.setString(23, truncate(job.getRating(), 10));
            preparedStatement.setString(24, truncate(job.getReviews(), 30));
            preparedStatement.setString(25, truncate(job.getExperience(), 30));
            preparedStatement.setString(26, truncate(job.getSalary(), 60));
            preparedStatement.setString(27, truncate(job.getLocation(), 300));
            preparedStatement.setString(28, truncate(job.getPosted(), 100));
            preparedStatement.setString(29, truncate(job.getOpenings(), 100));
            preparedStatement.setString(30, truncate(job.getApplications(), 100));
            preparedStatement.setClob(31, jobDescriptionClob);
            preparedStatement.setString(32, truncate(job.getRole(), 300));
            preparedStatement.setString(33, truncate(job.getIndustryType(), 300));
            preparedStatement.setString(34, truncate(job.getDepartment(), 300));
            preparedStatement.setString(35, truncate(job.getEmploymentType(), 300));
            preparedStatement.setString(36, truncate(job.getRoleCategory(), 300));
            preparedStatement.setClob(37, educationClob);
            preparedStatement.setClob(38, keySkillsClob);

            int affectedRows = preparedStatement.executeUpdate();
            if (affectedRows > 0) {
                LOGGER.log(Level.INFO, "Job saved successfully: {0}", job.getUrl());
            }
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error saving job: {0}", e.getMessage());
        }
    }
}