package org.dinesh.database;

import oracle.jdbc.OracleConnection;
import oracle.sql.CLOB;
import org.dinesh.config.Config;
import org.dinesh.entity.JobListingEntity;
import org.dinesh.util.MetricsTracker;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

public class BatchDatabaseHandler {
    private static final Logger LOGGER = Logger.getLogger(BatchDatabaseHandler.class.getName());
    private static final int MAX_DB_RETRIES = 3;
    private static final int DB_RETRY_DELAY = 2000;
    
    // URL cache for duplicate detection (thread-safe)
    private static final Set<String> urlCache = ConcurrentHashMap.newKeySet();
    private static volatile boolean cacheInitialized = false;
    
    // Track URLs currently being upserted to prevent concurrent MERGE on same URL
    private static final Set<String> upsertLocks = ConcurrentHashMap.newKeySet();
    
    private static String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        // Remove problematic characters that could cause encoding issues
        String cleaned = value.replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]", "");
        if (cleaned.length() > maxLength) {
            return cleaned.substring(0, maxLength);
        }
        return cleaned;
    }
    
    /**
     * Initialize URL cache from database (call once at startup)
     */
    public static void initializeUrlCache() {
        if (cacheInitialized) {
            return;
        }
        
        synchronized (BatchDatabaseHandler.class) {
            if (cacheInitialized) {
                return;
            }
            
            try (Connection conn = ResilientConnectionPool.getConnection();
                 PreparedStatement ps = conn.prepareStatement("SELECT URL FROM JobDetails");
                 ResultSet rs = ps.executeQuery()) {
                
                while (rs.next()) {
                    urlCache.add(rs.getString("URL"));
                }
                
                cacheInitialized = true;
                LOGGER.log(Level.INFO, "URL cache initialized with {0} URLs", urlCache.size());
                
            } catch (SQLException e) {
                LOGGER.log(Level.WARNING, "Failed to initialize URL cache, will use database queries", e);
            }
        }
    }
    
    /**
     * Check if URLs already exist (uses cache if available)
     * Thread-safe: uses ConcurrentHashMap for cache
     */
    public static Set<String> getExistingUrls(List<String> urls) {
        Set<String> existingUrls = new HashSet<>();
        
        if (urls == null || urls.isEmpty()) {
            return existingUrls;
        }
        
        // Use cache if initialized
        if (cacheInitialized) {
            for (String url : urls) {
                if (urlCache.contains(url)) {
                    existingUrls.add(url);
                }
            }
            return existingUrls;
        }
        
        // Fallback to database query
        return getExistingUrlsFromDb(urls);
    }
    
    /**
     * Check if URLs already exist in database (direct query)
     */
    private static Set<String> getExistingUrlsFromDb(List<String> urls) {
        Set<String> existingUrls = new HashSet<>();
        
        if (urls == null || urls.isEmpty()) {
            return existingUrls;
        }
        
        StringBuilder sql = new StringBuilder("SELECT URL FROM JobDetails WHERE URL IN (");
        for (int i = 0; i < urls.size(); i++) {
            sql.append("?");
            if (i < urls.size() - 1) sql.append(",");
        }
        sql.append(")");
        
        try (Connection conn = ResilientConnectionPool.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            
            for (int i = 0; i < urls.size(); i++) {
                ps.setString(i + 1, urls.get(i));
            }
            
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                existingUrls.add(rs.getString("URL"));
            }
            rs.close();
            
        } catch (SQLException e) {
            LOGGER.log(Level.WARNING, "Failed to check existing URLs", e);
        }
        
        return existingUrls;
    }
    
    /**
     * Batch insert jobs with retry logic
     * Adds URLs to cache atomically to prevent race conditions
     * 
     * @param jobs List of jobs to save
     * @param forceUpsert If true, allows updates of existing records (still uses atomic locking)
     */
    public static int saveJobsBatch(List<JobListingEntity> jobs, boolean forceUpsert) {
        if (jobs == null || jobs.isEmpty()) {
            return 0;
        }
        return saveJobsBatch(jobs, forceUpsert, Config.getDbSubBatchSize(),
                DB_RETRY_DELAY, BatchDatabaseHandler::saveSubBatch);
    }

    @FunctionalInterface
    interface SubBatchOperation {
        int save(List<JobListingEntity> jobs, boolean forceUpsert) throws SQLException;
    }

    static final class CommitOutcomeUnknownException extends SQLException {
        CommitOutcomeUnknownException(SQLException cause) {
            super("Transaction outcome is unknown; reconcile database state before retrying", cause);
        }
    }

    static void finishTransaction(Connection connection, boolean commit) throws SQLException {
        try {
            if (commit) connection.commit();
            else connection.rollback();
        } catch (SQLException failure) {
            // Do not put an uncertain transaction back into service or enable
            // auto-commit: either can accidentally commit unacknowledged work.
            try {
                ResilientConnectionPool.discardConnection(connection);
            } catch (SQLException abortFailure) {
                failure.addSuppressed(abortFailure);
            }
            throw new CommitOutcomeUnknownException(failure);
        }
    }

    static int saveJobsBatch(List<JobListingEntity> jobs, boolean forceUpsert,
                             int subBatchSize, long retryDelay, SubBatchOperation operation) {
        if (jobs == null || jobs.isEmpty()) {
            return 0;
        }
        if (subBatchSize < 1 || retryDelay < 0) {
            throw new IllegalArgumentException("Sub-batch size must be positive and retry delay nonnegative");
        }
        List<JobListingEntity> jobsToSave = new ArrayList<>();
        Set<String> previouslyExisting = new HashSet<>();
        
        if (forceUpsert) {
            // UPSERT mode: Use upsertLocks to prevent concurrent MERGE on same URL
            for (JobListingEntity job : jobs) {
                if (job != null && job.getUrl() != null && !job.getUrl().isEmpty()) {
                    // Try to acquire upsert lock - returns true if URL was not already being upserted
                    if (upsertLocks.add(job.getUrl())) {
                        jobsToSave.add(job);  // This thread won the race, proceed with MERGE
                        
                        // Check if URL exists in cache to track insert vs update
                        if (cacheInitialized && urlCache.contains(job.getUrl())) {
                            previouslyExisting.add(job.getUrl());
                        }
                    }
                    // else: Another thread is already upserting this URL, skip it
                }
            }
        } else if (cacheInitialized) {
            // INSERT mode: Use urlCache to prevent inserting URLs that already exist
            for (JobListingEntity job : jobs) {
                if (job != null && job.getUrl() != null && !job.getUrl().isEmpty()) {
                    // Try to add to cache - returns true if URL was not already present
                    if (urlCache.add(job.getUrl())) {
                        jobsToSave.add(job);  // This thread won the race, proceed with insert
                    }
                    // else: Another thread already claimed this URL, skip it
                }
            }
        } else {
            // Cache not initialized - save all jobs
            for (JobListingEntity job : jobs) {
                if (job != null && job.getUrl() != null && !job.getUrl().isEmpty()) {
                    jobsToSave.add(job);
                }
            }
        }
        
        if (jobsToSave.isEmpty()) {
            return 0;  // All URLs were already claimed by other threads
        }
        
        Set<String> acknowledged = new HashSet<>();
        int totalSaved = 0;
        try {
            for (int start = 0; start < jobsToSave.size(); start += subBatchSize) {
                List<JobListingEntity> subBatch = jobsToSave.subList(
                        start, Math.min(start + subBatchSize, jobsToSave.size()));
                boolean complete = false;
                for (int attempt = 1; attempt <= MAX_DB_RETRIES; attempt++) {
                    if (Thread.currentThread().isInterrupted()) return totalSaved;
                    try {
                        int saved = operation.save(subBatch, forceUpsert);
                        if (saved != subBatch.size()) {
                            LOGGER.severe("Sub-batch did not acknowledge every URL; reconcile before retrying");
                            return totalSaved;
                        }
                        for (JobListingEntity job : subBatch) acknowledged.add(job.getUrl());
                        totalSaved += saved;
                        if (forceUpsert) {
                            int updated = 0;
                            for (JobListingEntity job : subBatch) {
                                if (previouslyExisting.contains(job.getUrl())) updated++;
                            }
                            MetricsTracker.incrementJobsUpdated(updated);
                            MetricsTracker.incrementJobsInserted(saved - updated);
                        }
                        complete = true;
                        break;
                    } catch (CommitOutcomeUnknownException e) {
                        LOGGER.log(Level.SEVERE, "Sub-batch transaction outcome is unknown; stopping for reconciliation", e);
                        return totalSaved;
                    } catch (SQLException e) {
                        LOGGER.log(Level.WARNING, "Sub-batch save attempt " + attempt + " failed", e);
                        if (attempt < MAX_DB_RETRIES) {
                            try {
                                Thread.sleep(retryDelay * attempt);
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                                return totalSaved;
                            }
                        }
                    }
                }
                if (!complete) {
                    LOGGER.severe("Sub-batch exhausted retries; remaining URLs stay retryable");
                    return totalSaved;
                }
            }
            return totalSaved;
        } finally {
            for (JobListingEntity job : jobsToSave) {
                if (forceUpsert) {
                    upsertLocks.remove(job.getUrl());
                }
                if (cacheInitialized) {
                    if (acknowledged.contains(job.getUrl())) {
                        urlCache.add(job.getUrl());
                    } else if (!forceUpsert) {
                        urlCache.remove(job.getUrl());
                    }
                }
            }
        }
    }
    
    /**
     * Batch insert jobs with retry logic (backward compatibility)
     * Defaults to NOT forcing upsert (respects cache for new inserts)
     */
    public static int saveJobsBatch(List<JobListingEntity> jobs) {
        return saveJobsBatch(jobs, false);
    }
    
    /**
     * Save a sub-batch of jobs (internal method to keep connection time short)
     */
    private static int saveSubBatch(List<JobListingEntity> jobs, boolean forceUpsert) throws SQLException {
        if (jobs == null || jobs.isEmpty()) {
            LOGGER.log(Level.WARNING, "No jobs to save");
            return 0;
        }
        
        // Use MERGE or INSERT based on forceUpsert flag
        boolean useUpsert = forceUpsert || Config.isUpsertDuplicates();
        
        String sql;
        if (useUpsert) {
            // MERGE statement for atomic UPSERT
            sql = "MERGE INTO JobDetails t " +
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
                    "            Salary, Location, Posted, Openings, Applications, " +
                    "            JobDescription, Role, IndustryType, Department, " +
                    "            EmploymentType, RoleCategory, Education, KeySkills, Logdate) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, SYSTIMESTAMP)";
        } else {
            // Simple INSERT (duplicates already filtered by scraper)
            sql = "INSERT INTO JobDetails (URL, Title, CompanyName, Rating, Reviews, Experience, " +
                    "Salary, Location, Posted, Openings, Applications, JobDescription, " +
                    "Role, IndustryType, Department, EmploymentType, RoleCategory, " +
                    "Education, KeySkills, Logdate) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, SYSTIMESTAMP)";
        }
        
        int savedCount = 0;
        Connection conn = null;
        PreparedStatement ps = null;
        List<CLOB> clobsToFree = new ArrayList<>();
        
        try {
            conn = ResilientConnectionPool.getConnectionWithRetry(2, 1000);
            ps = conn.prepareStatement(sql);
            conn.setAutoCommit(false);
            
            // Unwrap HikariCP proxy to get the real OracleConnection
            OracleConnection oracleConn = conn.unwrap(OracleConnection.class);
            
            for (JobListingEntity job : jobs) {
                if (job == null || job.getUrl() == null || job.getUrl().isEmpty()) {
                    continue;
                }
                
                try {
                    // Create CLOB only for JobDescription (Education and KeySkills are now VARCHAR2)
                    CLOB jobDescClob = CLOB.createTemporary(oracleConn, true, CLOB.DURATION_SESSION);
                    jobDescClob.setString(1, job.getJobDescription() != null ? job.getJobDescription() : "");
                    clobsToFree.add(jobDescClob);
                    
                    // Set parameters based on SQL type
                    if (useUpsert) {
                        // MERGE parameters - ON clause + UPDATE clause
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
                        ps.setString(18, truncate(job.getEducation(), 2000));
                        ps.setString(19, truncate(job.getKeySkills(), 500));
                        
                        // MERGE parameters - INSERT clause
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
                        ps.setString(37, truncate(job.getEducation(), 2000));
                        ps.setString(38, truncate(job.getKeySkills(), 500));
                    } else {
                        // INSERT parameters
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
                        ps.setString(18, truncate(job.getEducation(), 2000));
                        ps.setString(19, truncate(job.getKeySkills(), 500));
                    }
                    
                    ps.addBatch();
                    savedCount++;
                    
                } catch (SQLException e) {
                    // Acknowledgement is for the whole chunk, so never commit a
                    // subset after silently skipping a row that failed binding.
                    throw new SQLException("Could not prepare the complete sub-batch", e);
                }
            }
            
            // Execute batch BEFORE freeing CLOBs
            if (savedCount > 0) {
                int[] results = ps.executeBatch();
                if (results.length != savedCount) {
                    throw new SQLException("Database did not acknowledge every batch statement");
                }
                for (int result : results) {
                    if (result == Statement.EXECUTE_FAILED) throw new SQLException("Batch statement failed");
                }
                finishTransaction(conn, true);
                LOGGER.log(Level.INFO, "Batch saved {0} jobs successfully", savedCount);
            }
            
            return savedCount;
            
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "Batch insert failed", e);
            // Rollback on error
            if (conn != null && !(e instanceof CommitOutcomeUnknownException)) {
                finishTransaction(conn, false);
            }
            throw e; // Re-throw for retry logic
        } finally {
            // Free CLOBs AFTER batch execution
            for (CLOB clob : clobsToFree) {
                try {
                    if (clob != null) clob.freeTemporary();
                } catch (SQLException e) {
                    // Ignore cleanup errors
                }
            }
            
            // Always close resources
            try {
                try {
                    if (ps != null) ps.close();
                } finally {
                    // Hikari resets a resolved connection when it returns to
                    // the pool. Uncertain transactions were discarded above.
                    if (conn != null) conn.close();
                }
            } catch (SQLException e) {
                LOGGER.log(Level.WARNING, "Error closing resources", e);
            }
        }
    }
}
