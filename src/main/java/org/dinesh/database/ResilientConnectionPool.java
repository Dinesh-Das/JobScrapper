package org.dinesh.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.dinesh.config.Config;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * Resilient connection pool with health checks, metrics, and circuit breaker
 */
public class ResilientConnectionPool {
    private static final Logger logger = Logger.getLogger(ResilientConnectionPool.class.getName());
    private static volatile HikariDataSource dataSource;
    private static final Object lock = new Object();
    
    // Health metrics
    private static final AtomicInteger failedConnectionAttempts = new AtomicInteger(0);
    private static final AtomicLong lastSuccessfulConnection = new AtomicLong(System.currentTimeMillis());
    private static final AtomicInteger activeConnections = new AtomicInteger(0);
    
    // Circuit breaker
    private static volatile boolean circuitOpen = false;
    private static final int CIRCUIT_BREAKER_THRESHOLD = 5;
    private static final long CIRCUIT_BREAKER_RESET_MS = 30000; // 30 seconds
    private static volatile long circuitOpenedAt = 0;
    
    private static void initialize() {
        if (dataSource == null || dataSource.isClosed()) {
            synchronized (lock) {
                if (dataSource == null || dataSource.isClosed()) {
                    try {
                        HikariConfig config = new HikariConfig();
                        config.setJdbcUrl(Config.getDbUrl());
                        config.setUsername(Config.getDbUser());
                        config.setPassword(Config.getDbPassword());
                        
                        // Pool sizing: threads × 1.5 for retry buffer
                        int poolSize = (int) Math.ceil(Config.getScraperThreads() * 1.5);
                        config.setMaximumPoolSize(poolSize);
                        config.setMinimumIdle(Math.max(2, poolSize / 2));
                        
                        // Timeouts
                        config.setConnectionTimeout(Config.getDbConnectionTimeout());
                        config.setIdleTimeout(600000); // 10 minutes
                        config.setMaxLifetime(1800000); // 30 minutes
                        config.setValidationTimeout(5000); // 5 seconds
                        
                        // Connection validation
                        config.setConnectionTestQuery("SELECT 1 FROM DUAL");
                        
                        // Leak detection - configurable threshold for batch operations with CLOBs
                        config.setLeakDetectionThreshold(Config.getDbLeakDetectionThreshold());
                        
                        // Pool name
                        config.setPoolName("JobScraperPool");
                        
                        // Initialization fail timeout
                        config.setInitializationFailTimeout(10000);
                        
                        // Register metrics
                        config.setRegisterMbeans(true);
                        
                        dataSource = new HikariDataSource(config);
                        
                        // Reset circuit breaker on successful init
                        circuitOpen = false;
                        failedConnectionAttempts.set(0);
                        
                        logger.info("Connection pool initialized: " + poolSize + " connections");
                        
                    } catch (Exception e) {
                        logger.severe("Failed to initialize connection pool: " + e.getMessage());
                        throw new RuntimeException("Connection pool initialization failed", e);
                    }
                }
            }
        }
    }
    
    /**
     * Get connection with circuit breaker and retry logic
     */
    public static Connection getConnection() throws SQLException {
        // Check circuit breaker
        if (circuitOpen) {
            long timeSinceOpen = System.currentTimeMillis() - circuitOpenedAt;
            if (timeSinceOpen > CIRCUIT_BREAKER_RESET_MS) {
                logger.info("Circuit breaker reset attempt");
                circuitOpen = false;
                failedConnectionAttempts.set(0);
            } else {
                throw new SQLException("Circuit breaker OPEN - database unavailable");
            }
        }
        
        if (dataSource == null || dataSource.isClosed()) {
            initialize();
        }
        
        try {
            Connection conn = dataSource.getConnection();
            
            // Validate connection
            if (!conn.isValid(5)) {
                conn.close();
                throw new SQLException("Connection validation failed");
            }
            
            // Success - reset failure counter
            failedConnectionAttempts.set(0);
            lastSuccessfulConnection.set(System.currentTimeMillis());
            activeConnections.incrementAndGet();
            
            // Wrap connection to track closure
            return new ConnectionWrapper(conn, () -> activeConnections.decrementAndGet());
            
        } catch (SQLException e) {
            int failures = failedConnectionAttempts.incrementAndGet();
            logger.warning("Connection acquisition failed (attempt " + failures + "): " + e.getMessage());
            
            // Open circuit breaker if threshold exceeded
            if (failures >= CIRCUIT_BREAKER_THRESHOLD) {
                circuitOpen = true;
                circuitOpenedAt = System.currentTimeMillis();
                logger.severe("Circuit breaker OPENED after " + failures + " failures");
            }
            
            throw e;
        }
    }
    
    /**
     * Get connection with retry logic
     */
    public static Connection getConnectionWithRetry(int maxRetries, long retryDelayMs) throws SQLException {
        SQLException lastException = null;
        
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                return getConnection();
            } catch (SQLException e) {
                lastException = e;
                
                if (attempt < maxRetries) {
                    try {
                        long delay = retryDelayMs * attempt; // Linear backoff
                        logger.info("Connection retry " + attempt + " after " + delay + "ms");
                        Thread.sleep(delay);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new SQLException("Connection retry interrupted", ie);
                    }
                }
            }
        }
        
        throw lastException;
    }
    
    /**
     * Check if pool is healthy
     */
    public static boolean isHealthy() {
        try {
            // Try to initialize if not already done
            if (dataSource == null || dataSource.isClosed()) {
                initialize();
            }
            
            if (circuitOpen) {
                logger.warning("Health check failed: Circuit breaker is OPEN");
                return false;
            }
            
            try (Connection conn = dataSource.getConnection()) {
                boolean valid = conn.isValid(5);
                if (!valid) {
                    logger.warning("Health check failed: Connection validation failed");
                }
                return valid;
            } catch (SQLException e) {
                logger.warning("Health check failed: " + e.getMessage());
                return false;
            }
        } catch (Exception e) {
            logger.severe("Health check failed during initialization: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }
    
    /**
     * Get pool statistics
     */
    public static PoolStats getStats() {
        if (dataSource == null || dataSource.isClosed()) {
            return new PoolStats(0, 0, 0, 0, false);
        }
        
        return new PoolStats(
            dataSource.getHikariPoolMXBean().getActiveConnections(),
            dataSource.getHikariPoolMXBean().getIdleConnections(),
            dataSource.getHikariPoolMXBean().getTotalConnections(),
            dataSource.getHikariPoolMXBean().getThreadsAwaitingConnection(),
            circuitOpen
        );
    }
    
    /**
     * Close pool
     */
    public static void close() {
        synchronized (lock) {
            if (dataSource != null && !dataSource.isClosed()) {
                logger.info("Closing connection pool...");
                dataSource.close();
                logger.info("Connection pool closed");
            }
        }
    }
    
    /**
     * Get data source (for legacy compatibility)
     */
    public static DataSource getDataSource() {
        if (dataSource == null || dataSource.isClosed()) {
            initialize();
        }
        return dataSource;
    }
    
    /**
     * Pool statistics
     */
    public static class PoolStats {
        public final int active;
        public final int idle;
        public final int total;
        public final int waiting;
        public final boolean circuitOpen;
        
        public PoolStats(int active, int idle, int total, int waiting, boolean circuitOpen) {
            this.active = active;
            this.idle = idle;
            this.total = total;
            this.waiting = waiting;
            this.circuitOpen = circuitOpen;
        }
        
        @Override
        public String toString() {
            return String.format("Pool[active=%d, idle=%d, total=%d, waiting=%d, circuit=%s]",
                active, idle, total, waiting, circuitOpen ? "OPEN" : "CLOSED");
        }
    }
    
    /**
     * Connection wrapper to track closure
     */
    private static class ConnectionWrapper implements Connection {
        private final Connection delegate;
        private final Runnable onClose;
        private boolean closed = false;
        
        public ConnectionWrapper(Connection connection, Runnable onClose) {
            this.delegate = connection;
            this.onClose = onClose;
        }
        
        @Override
        public void close() throws SQLException {
            if (!closed) {
                try {
                    delegate.close();
                } finally {
                    closed = true;
                    if (onClose != null) {
                        onClose.run();
                    }
                }
            }
        }
        
        @Override
        public boolean isClosed() throws SQLException {
            return closed || delegate.isClosed();
        }
        
        // Delegate all other methods
        @Override public java.sql.Statement createStatement() throws SQLException { return delegate.createStatement(); }
        @Override public PreparedStatement prepareStatement(String sql) throws SQLException { return delegate.prepareStatement(sql); }
        @Override public java.sql.CallableStatement prepareCall(String sql) throws SQLException { return delegate.prepareCall(sql); }
        @Override public String nativeSQL(String sql) throws SQLException { return delegate.nativeSQL(sql); }
        @Override public void setAutoCommit(boolean autoCommit) throws SQLException { delegate.setAutoCommit(autoCommit); }
        @Override public boolean getAutoCommit() throws SQLException { return delegate.getAutoCommit(); }
        @Override public void commit() throws SQLException { delegate.commit(); }
        @Override public void rollback() throws SQLException { delegate.rollback(); }
        @Override public java.sql.DatabaseMetaData getMetaData() throws SQLException { return delegate.getMetaData(); }
        @Override public void setReadOnly(boolean readOnly) throws SQLException { delegate.setReadOnly(readOnly); }
        @Override public boolean isReadOnly() throws SQLException { return delegate.isReadOnly(); }
        @Override public void setCatalog(String catalog) throws SQLException { delegate.setCatalog(catalog); }
        @Override public String getCatalog() throws SQLException { return delegate.getCatalog(); }
        @Override public void setTransactionIsolation(int level) throws SQLException { delegate.setTransactionIsolation(level); }
        @Override public int getTransactionIsolation() throws SQLException { return delegate.getTransactionIsolation(); }
        @Override public java.sql.SQLWarning getWarnings() throws SQLException { return delegate.getWarnings(); }
        @Override public void clearWarnings() throws SQLException { delegate.clearWarnings(); }
        @Override public java.sql.Statement createStatement(int resultSetType, int resultSetConcurrency) throws SQLException { return delegate.createStatement(resultSetType, resultSetConcurrency); }
        @Override public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency) throws SQLException { return delegate.prepareStatement(sql, resultSetType, resultSetConcurrency); }
        @Override public java.sql.CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency) throws SQLException { return delegate.prepareCall(sql, resultSetType, resultSetConcurrency); }
        @Override public java.util.Map<String,Class<?>> getTypeMap() throws SQLException { return delegate.getTypeMap(); }
        @Override public void setTypeMap(java.util.Map<String,Class<?>> map) throws SQLException { delegate.setTypeMap(map); }
        @Override public void setHoldability(int holdability) throws SQLException { delegate.setHoldability(holdability); }
        @Override public int getHoldability() throws SQLException { return delegate.getHoldability(); }
        @Override public java.sql.Savepoint setSavepoint() throws SQLException { return delegate.setSavepoint(); }
        @Override public java.sql.Savepoint setSavepoint(String name) throws SQLException { return delegate.setSavepoint(name); }
        @Override public void rollback(java.sql.Savepoint savepoint) throws SQLException { delegate.rollback(savepoint); }
        @Override public void releaseSavepoint(java.sql.Savepoint savepoint) throws SQLException { delegate.releaseSavepoint(savepoint); }
        @Override public java.sql.Statement createStatement(int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException { return delegate.createStatement(resultSetType, resultSetConcurrency, resultSetHoldability); }
        @Override public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException { return delegate.prepareStatement(sql, resultSetType, resultSetConcurrency, resultSetHoldability); }
        @Override public java.sql.CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException { return delegate.prepareCall(sql, resultSetType, resultSetConcurrency, resultSetHoldability); }
        @Override public PreparedStatement prepareStatement(String sql, int autoGeneratedKeys) throws SQLException { return delegate.prepareStatement(sql, autoGeneratedKeys); }
        @Override public PreparedStatement prepareStatement(String sql, int[] columnIndexes) throws SQLException { return delegate.prepareStatement(sql, columnIndexes); }
        @Override public PreparedStatement prepareStatement(String sql, String[] columnNames) throws SQLException { return delegate.prepareStatement(sql, columnNames); }
        @Override public java.sql.Clob createClob() throws SQLException { return delegate.createClob(); }
        @Override public java.sql.Blob createBlob() throws SQLException { return delegate.createBlob(); }
        @Override public java.sql.NClob createNClob() throws SQLException { return delegate.createNClob(); }
        @Override public java.sql.SQLXML createSQLXML() throws SQLException { return delegate.createSQLXML(); }
        @Override public boolean isValid(int timeout) throws SQLException { return delegate.isValid(timeout); }
        @Override public void setClientInfo(String name, String value) throws java.sql.SQLClientInfoException { delegate.setClientInfo(name, value); }
        @Override public void setClientInfo(java.util.Properties properties) throws java.sql.SQLClientInfoException { delegate.setClientInfo(properties); }
        @Override public String getClientInfo(String name) throws SQLException { return delegate.getClientInfo(name); }
        @Override public java.util.Properties getClientInfo() throws SQLException { return delegate.getClientInfo(); }
        @Override public java.sql.Array createArrayOf(String typeName, Object[] elements) throws SQLException { return delegate.createArrayOf(typeName, elements); }
        @Override public java.sql.Struct createStruct(String typeName, Object[] attributes) throws SQLException { return delegate.createStruct(typeName, attributes); }
        @Override public void setSchema(String schema) throws SQLException { delegate.setSchema(schema); }
        @Override public String getSchema() throws SQLException { return delegate.getSchema(); }
        @Override public void abort(java.util.concurrent.Executor executor) throws SQLException { delegate.abort(executor); }
        @Override public void setNetworkTimeout(java.util.concurrent.Executor executor, int milliseconds) throws SQLException { delegate.setNetworkTimeout(executor, milliseconds); }
        @Override public int getNetworkTimeout() throws SQLException { return delegate.getNetworkTimeout(); }
        @Override public <T> T unwrap(Class<T> iface) throws SQLException { return delegate.unwrap(iface); }
        @Override public boolean isWrapperFor(Class<?> iface) throws SQLException { return delegate.isWrapperFor(iface); }
    }
}
