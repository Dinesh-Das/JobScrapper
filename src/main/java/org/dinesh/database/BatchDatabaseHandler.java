package org.dinesh.database;

import oracle.jdbc.OracleConnection;
import oracle.sql.CLOB;
import org.dinesh.entity.JobListingEntity;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

public class BatchDatabaseHandler {
    private static final Logger LOGGER = Logger.getLogger(BatchDatabaseHandler.class.getName());
    
    private static String truncate(String value, int maxLength) {
        return value != null && value.length() > maxLength ? value.substring(0, maxLength) : value;
    }
    
    /**
     * Batch insert jobs - 10-20x faster than individual inserts
     */
    public static int saveJobsBatch(List<JobListingEntity> jobs) {
        if (jobs == null || jobs.isEmpty()) {
            LOGGER.log(Level.WARNING, "No jobs to save");
            return 0;
        }
        
        String sql = "MERGE INTO JobDetails t " +
                "USING (SELECT ? AS URL FROM DUAL) s " +
                "ON (t.URL = s.URL) " +
                "WHEN MATCHED THEN " +
                "UPDATE SET " +
                "    Title = ?, CompanyName = ?, Rating = ?, Reviews = ?, " +
                "    Experience = ?, Salary = ?, Location = ?, Posted = ?, " +
                "    Openings = ?, Applications = ?, JobDescription = ?, " +
                "    Role = ?, IndustryType = ?, Department = ?, " +
                "    EmploymentType = ?, RoleCategory = ?, Education = ?, " +
                "    KeySkills = ?, Logdate = SYSTIMESTAMP " +
                "WHEN NOT MATCHED THEN " +
                "INSERT (URL, Title, CompanyName, Rating, Reviews, Experience, " +
                "        Salary, Location, Posted, Openings, Applications, " +
                "        JobDescription, Role, IndustryType, Department, " +
                "        EmploymentType, RoleCategory, Education, KeySkills, Logdate) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, SYSTIMESTAMP)";
        
        int savedCount = 0;
        
        try (Connection conn = ConnectionPool.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            
            conn.setAutoCommit(false);
            
            // Unwrap HikariCP proxy to get the real OracleConnection
            OracleConnection oracleConn = conn.unwrap(OracleConnection.class);
            
            for (JobListingEntity job : jobs) {
                if (job == null || job.getUrl() == null || job.getUrl().isEmpty()) {
                    continue;
                }
                
                try {
                    // Create CLOBs using unwrapped connection
                    CLOB jobDescClob = CLOB.createTemporary(oracleConn, true, CLOB.DURATION_SESSION);
                    jobDescClob.setString(1, job.getJobDescription() != null ? job.getJobDescription() : "");
                    
                    CLOB eduClob = CLOB.createTemporary(oracleConn, true, CLOB.DURATION_SESSION);
                    eduClob.setString(1, job.getEducation() != null ? job.getEducation() : "");
                    
                    CLOB skillsClob = CLOB.createTemporary(oracleConn, true, CLOB.DURATION_SESSION);
                    skillsClob.setString(1, job.getKeySkills() != null ? job.getKeySkills() : "");
                    
                    // UPDATE parameters
                    ps.setString(1, job.getUrl());
                    ps.setString(2, truncate(job.getTitle(), 200));
                    ps.setString(3, truncate(job.getCompanyName(), 200));
                    ps.setString(4, truncate(job.getRating(), 10));
                    ps.setString(5, truncate(job.getReviews(), 30));
                    ps.setString(6, truncate(job.getExperience(), 30));
                    ps.setString(7, truncate(job.getSalary(), 60));
                    ps.setString(8, truncate(job.getLocation(), 300));
                    ps.setString(9, truncate(job.getPosted(), 100));
                    ps.setString(10, truncate(job.getOpenings(), 100));
                    ps.setString(11, truncate(job.getApplications(), 100));
                    ps.setClob(12, jobDescClob);
                    ps.setString(13, truncate(job.getRole(), 300));
                    ps.setString(14, truncate(job.getIndustryType(), 300));
                    ps.setString(15, truncate(job.getDepartment(), 300));
                    ps.setString(16, truncate(job.getEmploymentType(), 300));
                    ps.setString(17, truncate(job.getRoleCategory(), 300));
                    ps.setClob(18, eduClob);
                    ps.setClob(19, skillsClob);
                    
                    // INSERT parameters (duplicate)
                    ps.setString(20, job.getUrl());
                    ps.setString(21, truncate(job.getTitle(), 200));
                    ps.setString(22, truncate(job.getCompanyName(), 200));
                    ps.setString(23, truncate(job.getRating(), 10));
                    ps.setString(24, truncate(job.getReviews(), 30));
                    ps.setString(25, truncate(job.getExperience(), 30));
                    ps.setString(26, truncate(job.getSalary(), 60));
                    ps.setString(27, truncate(job.getLocation(), 300));
                    ps.setString(28, truncate(job.getPosted(), 100));
                    ps.setString(29, truncate(job.getOpenings(), 100));
                    ps.setString(30, truncate(job.getApplications(), 100));
                    ps.setClob(31, jobDescClob);
                    ps.setString(32, truncate(job.getRole(), 300));
                    ps.setString(33, truncate(job.getIndustryType(), 300));
                    ps.setString(34, truncate(job.getDepartment(), 300));
                    ps.setString(35, truncate(job.getEmploymentType(), 300));
                    ps.setString(36, truncate(job.getRoleCategory(), 300));
                    ps.setClob(37, eduClob);
                    ps.setClob(38, skillsClob);
                    
                    ps.addBatch();
                    savedCount++;
                    
                } catch (SQLException e) {
                    LOGGER.log(Level.WARNING, "Failed to add job to batch: " + job.getUrl(), e);
                }
            }
            
            // Execute batch
            int[] results = ps.executeBatch();
            conn.commit();
            
            LOGGER.log(Level.INFO, "Batch saved {0} jobs successfully", savedCount);
            return savedCount;
            
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "Batch insert failed", e);
            return 0;
        }
    }
}
