package org.dinesh.util;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Append-only checkpoints. Completion is published only after a successful sync.
 * A single process owns these files; callers must handle storage failures.
 *
 * <ul>
 *   <li>{@code urls_checkpoint.txt} — unique job URLs from Phase 1</li>
 *   <li>{@code pages_checkpoint.txt} — search pages already collected</li>
 *   <li>{@code phase2_done.txt} — job URLs already attempted in Phase 2</li>
 * </ul>
 */
public class CheckpointStore {
    private static final Logger logger = Logger.getLogger(CheckpointStore.class.getName());

    public static final String URLS_FILE = "urls_checkpoint.txt";
    public static final String PAGES_FILE = "pages_checkpoint.txt";
    public static final String PHASE2_FILE = "phase2_done.txt";

    private final Set<String> urls = ConcurrentHashMap.newKeySet();
    private final Set<Integer> donePages = ConcurrentHashMap.newKeySet();
    private final Set<String> phase2Done = ConcurrentHashMap.newKeySet();

    private final Object urlLock = new Object();
    private final Object pageLock = new Object();
    private final Object phase2Lock = new Object();
    private final File directory;

    public CheckpointStore() {
        this(new File("."));
    }

    public CheckpointStore(File directory) {
        this.directory = directory;
    }

    public void load() {
        urls.addAll(readLines(URLS_FILE));
        phase2Done.addAll(readLines(PHASE2_FILE));
        for (String line : readLines(PAGES_FILE)) {
            try {
                donePages.add(Integer.parseInt(line.trim()));
            } catch (NumberFormatException ignored) {
                // skip junk lines
            }
        }
        logger.info("Checkpoint loaded: " + urls.size() + " URLs, "
                + donePages.size() + " pages done, "
                + phase2Done.size() + " Phase-2 attempts");
    }

    public Set<String> getUrls() {
        return urls;
    }

    public Set<Integer> getDonePages() {
        return donePages;
    }

    public boolean isPhase2Done(String url) {
        return phase2Done.contains(url);
    }

    public int addUrls(Collection<String> newUrls) {
        if (newUrls == null || newUrls.isEmpty()) {
            return 0;
        }
        synchronized (urlLock) {
            Set<String> fresh = new LinkedHashSet<String>();
            for (String url : newUrls) {
                if (url == null) {
                    continue;
                }
                String trimmed = url.trim();
                if (!trimmed.isEmpty() && !urls.contains(trimmed)) {
                    fresh.add(trimmed);
                }
            }
            if (!fresh.isEmpty()) {
                appendLines(URLS_FILE, fresh);
                urls.addAll(fresh);
            }
            return fresh.size();
        }
    }

    public void markPageDone(int page) {
        synchronized (pageLock) {
            if (!donePages.contains(page)) {
                appendLines(PAGES_FILE, Collections.singleton(String.valueOf(page)));
                donePages.add(page);
            }
        }
    }

    public void markPhase2Done(String url) {
        if (url == null) {
            return;
        }
        String trimmed = url.trim();
        synchronized (phase2Lock) {
            if (!trimmed.isEmpty() && !phase2Done.contains(trimmed)) {
                appendLines(PHASE2_FILE, Collections.singleton(trimmed));
                phase2Done.add(trimmed);
            }
        }
    }

    public int urlCount() {
        return urls.size();
    }

    public int donePageCount() {
        return donePages.size();
    }

    private Set<String> readLines(String path) {
        Set<String> lines = new LinkedHashSet<String>();
        File file = new File(directory, path);
        if (!file.exists()) {
            return lines;
        }
        try (BufferedReader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty()) {
                    lines.add(trimmed);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read checkpoint " + path, e);
        }
        return lines;
    }

    private void appendLines(String path, Collection<String> lines) {
        try (FileOutputStream stream = new FileOutputStream(new File(directory, path), true);
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(stream, StandardCharsets.UTF_8))) {
            for (String line : lines) {
                writer.write(line);
                writer.newLine();
            }
            writer.flush();
            stream.getFD().sync();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to persist checkpoint " + path, e);
        }
    }
}
