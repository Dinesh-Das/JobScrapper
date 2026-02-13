package org.dinesh.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.dinesh.config.Config;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

public class ConnectionPool {
    private static volatile HikariDataSource dataSource;
    private static final Object lock = new Object();
    
    private static void initialize() {
        if (dataSource == null || dataSource.isClosed()) {
            synchronized (lock) {
                if (dataSource == null || dataSource.isClosed()) {
                    try {
                        HikariConfig config = new HikariConfig();
                        config.setJdbcUrl(Config.getDbUrl());
                        config.setUsername(Config.getDbUser());
                        config.setPassword(Config.getDbPassword());
                        config.setMaximumPoolSize(Config.getDbPoolSize());
                        config.setConnectionTimeout(Config.getDbConnectionTimeout());
                        config.setIdleTimeout(600000); // 10 minutes
                        config.setMaxLifetime(1800000); // 30 minutes
                        config.setConnectionTestQuery("SELECT 1 FROM DUAL");
                        config.setPoolName("JobScraperPool");
                        config.setLeakDetectionThreshold(0); // Disable leak detection (we handle cleanup properly)
                        
                        dataSource = new HikariDataSource(config);
                        System.out.println("Connection pool initialized successfully");
                    } catch (Exception e) {
                        System.err.println("Failed to initialize connection pool: " + e.getMessage());
                        throw new RuntimeException("Connection pool initialization failed", e);
                    }
                }
            }
        }
    }
    
    public static Connection getConnection() throws SQLException {
        if (dataSource == null || dataSource.isClosed()) {
            initialize();
        }
        return dataSource.getConnection();
    }
    
    public static void close() {
        synchronized (lock) {
            if (dataSource != null && !dataSource.isClosed()) {
                dataSource.close();
                System.out.println("Connection pool closed");
            }
        }
    }
    
    public static DataSource getDataSource() {
        if (dataSource == null || dataSource.isClosed()) {
            initialize();
        }
        return dataSource;
    }
}
