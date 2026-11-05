package org.dinesh.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class CheckpointStoreTest {
    @TempDir Path directory;

    @Test
    void failedUrlWriteDoesNotPublishAndCanBeRetried() throws Exception {
        CheckpointStore store = new CheckpointStore(directory.toFile());
        Path blocked = Files.createDirectory(directory.resolve(CheckpointStore.URLS_FILE));
        assertThrows(UncheckedIOException.class, () -> store.addUrls(Arrays.asList("https://example.org/job")));
        assertEquals(0, store.urlCount());
        Files.delete(blocked);
        assertEquals(1, store.addUrls(Arrays.asList("https://example.org/job")));
        CheckpointStore restored = new CheckpointStore(directory.toFile());
        restored.load();
        assertTrue(restored.getUrls().contains("https://example.org/job"));
    }

    @Test
    void failedPageWriteDoesNotPublishAndCanBeRetried() throws Exception {
        CheckpointStore store = new CheckpointStore(directory.toFile());
        Path blocked = Files.createDirectory(directory.resolve(CheckpointStore.PAGES_FILE));
        assertThrows(UncheckedIOException.class, () -> store.markPageDone(3));
        assertEquals(0, store.donePageCount());
        Files.delete(blocked);
        store.markPageDone(3);
        CheckpointStore restored = new CheckpointStore(directory.toFile());
        restored.load();
        assertTrue(restored.getDonePages().contains(3));
    }

    @Test
    void failedCompletionWriteDoesNotPublishAndCanBeRetried() throws Exception {
        CheckpointStore store = new CheckpointStore(directory.toFile());
        Path blocked = Files.createDirectory(directory.resolve(CheckpointStore.PHASE2_FILE));
        assertThrows(UncheckedIOException.class, () -> store.markPhase2Done("https://example.org/job"));
        assertFalse(store.isPhase2Done("https://example.org/job"));
        Files.delete(blocked);
        store.markPhase2Done("https://example.org/job");
        CheckpointStore restored = new CheckpointStore(directory.toFile());
        restored.load();
        assertTrue(restored.isPhase2Done("https://example.org/job"));
    }

    @Test
    void unreadableExistingCheckpointFailsLoad() throws Exception {
        Files.createDirectory(directory.resolve(CheckpointStore.URLS_FILE));
        CheckpointStore store = new CheckpointStore(directory.toFile());
        assertThrows(UncheckedIOException.class, store::load);
    }

    @Test
    void duplicatesAreIdempotentAndUtf8SurvivesRestart() {
        CheckpointStore store = new CheckpointStore(directory.toFile());
        assertEquals(1, store.addUrls(Arrays.asList("https://example.org/café", "https://example.org/café")));
        assertEquals(0, store.addUrls(Arrays.asList("https://example.org/café")));
        CheckpointStore restored = new CheckpointStore(directory.toFile());
        restored.load();
        assertEquals(store.getUrls(), restored.getUrls());
    }
}
