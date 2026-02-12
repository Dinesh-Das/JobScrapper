package org.dinesh.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.dinesh.config.Config;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

public class ConnectionPool {
    private static HikariDataSource dataSource;
    
    static {
        try {
            HikariConfig config = new HikariConfig();
            config.setJdbcUrl(Config.getDbUrl());
            config.setUsername(Config.getDbUser());
            config.setPassword(Config.getDbPassword());
            config.setMaximumPoolSize(Config.getDbPoolSize());
            config.setConnectionTimeout(Config.getDbConnectionTimeout());
            config.setIdleTimeout(600000);
            config.setMaxLifetime(1800000);
            config.setConnectionTestQuery("SELECT 1 FROM DUAL");
            config.setPoolName("JobScraperPool");
            
            dataSource = new HikariDataSource(config);
            System.out.println("Connection pool initialized successfully");
        } catch (Exception e) {
            System.err.println("Failed to initialize connection pool: " + e.getMessage());
            throw new RuntimeException("Connection pool initialization failed", e);
        }
    }
    
    public static Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }
    
    public static void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            System.out.println("Connection pool closed");
        }
    }
    
    public static DataSource getDataSource() {
        return dataSource;
    }
}
