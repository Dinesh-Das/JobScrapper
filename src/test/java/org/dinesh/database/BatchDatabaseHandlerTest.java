package org.dinesh.database;

import com.zaxxer.hikari.HikariDataSource;
import org.dinesh.entity.JobListingEntity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Field;
import java.lang.reflect.Constructor;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class BatchDatabaseHandlerTest {
    @BeforeEach
    void initializeIsolatedCache() throws Exception {
        set("urlCache").clear();
        set("upsertLocks").clear();
        cacheInitialized(true);
    }

    @AfterEach
    void resetCache() throws Exception {
        set("urlCache").clear();
        set("upsertLocks").clear();
        cacheInitialized(false);
        Thread.interrupted();
    }

    @Test
    void retriesOnlyTheUncommittedChunkAfterATransientFailure() {
        FakeStore store = new FakeStore(1);
        List<JobListingEntity> jobs = jobs("a", "b");

        int saved = BatchDatabaseHandler.saveJobsBatch(jobs, false, 1, 0, store);

        assertEquals(2, saved);
        assertEquals(Arrays.asList("a", "b", "b"), store.calls);
        assertEquals(new HashSet<>(Arrays.asList("a", "b")), store.committed);
        assertEquals(store.committed, BatchDatabaseHandler.getExistingUrls(Arrays.asList("a", "b")));
    }

    @Test
    void exhaustedChunkKeepsAcknowledgedCommitsAndReleasesRetryableClaims() {
        FakeStore store = new FakeStore(Integer.MAX_VALUE);
        List<JobListingEntity> jobs = jobs("a", "b", "c");

        int saved = BatchDatabaseHandler.saveJobsBatch(jobs, false, 1, 0, store);

        assertEquals(1, saved, "Main must not checkpoint the whole three-job batch");
        assertEquals(Arrays.asList("a", "b", "b", "b"), store.calls);
        assertEquals(new HashSet<>(Arrays.asList("a")),
                BatchDatabaseHandler.getExistingUrls(Arrays.asList("a", "b", "c")));
        // Retry the original input: committed a stays cached, while b/c remain eligible.
        store.failuresRemaining = 0;
        assertEquals(2, BatchDatabaseHandler.saveJobsBatch(jobs, false, 1, 0, store));
        assertEquals(Arrays.asList("a", "b", "b", "b", "b", "c"), store.calls);
        assertEquals(new HashSet<>(Arrays.asList("a", "b", "c")), store.committed);
    }

    @Test
    void upsertClaimsRemainExclusiveAndAreReleasedAfterPartialFailure() {
        FakeStore store = new FakeStore(Integer.MAX_VALUE);
        List<JobListingEntity> jobs = jobs("a", "b");
        store.onFirstA = () -> assertEquals(0,
                BatchDatabaseHandler.saveJobsBatch(jobs("a"), true, 1, 0, store));

        assertEquals(1, BatchDatabaseHandler.saveJobsBatch(jobs, true, 1, 0, store));
        assertEquals(Arrays.asList("a", "b", "b", "b"), store.calls);
        store.failuresRemaining = 0;
        assertEquals(2, BatchDatabaseHandler.saveJobsBatch(jobs, true, 1, 0, store));
        assertEquals(Arrays.asList("a", "b", "b", "b", "a", "b"), store.calls);
    }

    @Test
    void unknownCommitOutcomeIsNotAutomaticallyReplayedOrAcknowledged() {
        FakeStore store = new FakeStore(0);
        BatchDatabaseHandler.SubBatchOperation uncertain = (chunk, upsert) -> {
            int count = store.save(chunk, upsert);
            if (chunk.get(0).getUrl().equals("b")) {
                // Simulate a server commit whose acknowledgement never arrived.
                throw new BatchDatabaseHandler.CommitOutcomeUnknownException(new SQLException("lost reply"));
            }
            return count;
        };

        assertEquals(1, BatchDatabaseHandler.saveJobsBatch(jobs("a", "b"), false, 1, 0, uncertain));
        assertEquals(Arrays.asList("a", "b"), store.calls);
        assertEquals(new HashSet<>(Arrays.asList("a", "b")), store.committed);
        assertEquals(new HashSet<>(Arrays.asList("a")),
                BatchDatabaseHandler.getExistingUrls(Arrays.asList("a", "b")));
    }

    @Test
    void interruptionPreservesAcknowledgedProgressAndReleasesRemainingClaims() {
        FakeStore store = new FakeStore(0);
        store.onFirstA = () -> Thread.currentThread().interrupt();

        assertEquals(1, BatchDatabaseHandler.saveJobsBatch(jobs("a", "b"), false, 1, 0, store));
        assertTrue(Thread.currentThread().isInterrupted());
        assertEquals(Arrays.asList("a"), store.calls);
        Thread.interrupted();
        assertEquals(1, BatchDatabaseHandler.saveJobsBatch(jobs("b"), false, 1, 0, store));
        assertEquals(Arrays.asList("a", "b"), store.calls);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void failedCommitOrRollbackDiscardsTheConnectionWithoutEnablingAutoCommit(boolean commit) {
        List<String> calls = new ArrayList<>();
        String operation = commit ? "commit" : "rollback";
        Connection connection = (Connection) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                    calls.add(method.getName());
                    if (method.getName().equals(operation)) throw new SQLException("Synthetic " + operation + " failure");
                    return null;
                });

        assertThrows(BatchDatabaseHandler.CommitOutcomeUnknownException.class,
                () -> BatchDatabaseHandler.finishTransaction(connection, commit));
        assertEquals(Arrays.asList(operation, "abort"), calls);
        assertFalse(calls.contains("setAutoCommit"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void uncertainPooledTransactionEvictsItsLiveProxy(boolean commit) throws Exception {
        List<String> calls = new ArrayList<>();
        String operation = commit ? "commit" : "rollback";
        Connection delegate = (Connection) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                    calls.add(method.getName());
                    if (method.getName().equals(operation)) throw new SQLException("Synthetic failure");
                    return null;
                });
        Class<?> wrapperClass = Class.forName(ResilientConnectionPool.class.getName() + "$ConnectionWrapper");
        Constructor<?> constructor = wrapperClass.getDeclaredConstructor(Connection.class, Runnable.class);
        constructor.setAccessible(true);
        Connection wrapped = (Connection) constructor.newInstance(delegate, (Runnable) () -> {});
        HikariDataSource source = new HikariDataSource() {
            @Override
            public void evictConnection(Connection connection) {
                assertSame(delegate, connection, "eviction must receive the underlying pool proxy");
                calls.add("evict");
            }
        };
        Field field = ResilientConnectionPool.class.getDeclaredField("dataSource");
        field.setAccessible(true);
        Object previous = field.get(null);
        field.set(null, source);
        try {
            assertThrows(BatchDatabaseHandler.CommitOutcomeUnknownException.class,
                    () -> BatchDatabaseHandler.finishTransaction(wrapped, commit));
            assertEquals(Arrays.asList(operation, "evict"), calls);
        } finally {
            field.set(null, previous);
            source.close(); // The test data source was never started or connected.
        }
    }

    private static List<JobListingEntity> jobs(String... urls) {
        List<JobListingEntity> jobs = new ArrayList<>();
        for (String url : urls) {
            JobListingEntity job = new JobListingEntity();
            job.setUrl(url);
            jobs.add(job);
        }
        return jobs;
    }

    // Initialize only the in-memory duplicate/claim state; never initialize Config or Oracle.
    @SuppressWarnings("unchecked")
    private static Set<String> set(String name) throws Exception {
        Field field = BatchDatabaseHandler.class.getDeclaredField(name);
        field.setAccessible(true);
        return (Set<String>) field.get(null);
    }

    private static void cacheInitialized(boolean value) throws Exception {
        Field field = BatchDatabaseHandler.class.getDeclaredField("cacheInitialized");
        field.setAccessible(true);
        field.setBoolean(null, value);
    }

    private static class FakeStore implements BatchDatabaseHandler.SubBatchOperation {
        final List<String> calls = new ArrayList<>();
        final Set<String> committed = new HashSet<>();
        int failuresRemaining;
        Runnable onFirstA;

        FakeStore(int failuresRemaining) {
            this.failuresRemaining = failuresRemaining;
        }

        @Override
        public int save(List<JobListingEntity> jobs, boolean forceUpsert) throws SQLException {
            String url = jobs.get(0).getUrl();
            calls.add(url);
            if (url.equals("b") && failuresRemaining > 0) {
                failuresRemaining--;
                throw new SQLException("Synthetic rolled-back b failure");
            }
            if (!forceUpsert && committed.contains(url)) {
                throw new SQLException("Synthetic unique URL violation for " + url);
            }
            committed.add(url); // Acknowledged fake commit; no real JDBC boundary.
            if (url.equals("a") && onFirstA != null) {
                Runnable callback = onFirstA;
                onFirstA = null;
                callback.run();
            }
            return jobs.size();
        }
    }
}
