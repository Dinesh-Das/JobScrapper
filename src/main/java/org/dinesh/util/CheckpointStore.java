package org.dinesh.util;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Append-only, crash-safe checkpoints so a restart never loses collected work.
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
        Set<String> fresh = new LinkedHashSet<String>();
        for (String url : newUrls) {
            if (url == null) {
                continue;
            }
            String trimmed = url.trim();
            if (!trimmed.isEmpty() && urls.add(trimmed)) {
                fresh.add(trimmed);
            }
        }
        if (!fresh.isEmpty()) {
            appendLines(URLS_FILE, fresh, urlLock);
        }
        return fresh.size();
    }

    public void markPageDone(int page) {
        if (donePages.add(page)) {
            appendLines(PAGES_FILE, Collections.singleton(String.valueOf(page)), pageLock);
        }
    }

    public void markPhase2Done(String url) {
        if (url == null) {
            return;
        }
        String trimmed = url.trim();
        if (!trimmed.isEmpty() && phase2Done.add(trimmed)) {
            appendLines(PHASE2_FILE, Collections.singleton(trimmed), phase2Lock);
        }
    }

    public int urlCount() {
        return urls.size();
    }

    public int donePageCount() {
        return donePages.size();
    }

    private static Set<String> readLines(String path) {
        Set<String> lines = new LinkedHashSet<String>();
        File file = new File(path);
        if (!file.exists()) {
            return lines;
        }
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new FileReader(file));
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty()) {
                    lines.add(trimmed);
                }
            }
        } catch (IOException e) {
            logger.log(Level.WARNING, "Failed to read " + path + ": " + e.getMessage());
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException ignored) {
                }
            }
        }
        return lines;
    }

    private static void appendLines(String path, Collection<String> lines, Object lock) {
        synchronized (lock) {
            PrintWriter writer = null;
            try {
                writer = new PrintWriter(new BufferedWriter(new FileWriter(path, true)));
                for (String line : lines) {
                    writer.println(line);
                }
                writer.flush();
            } catch (IOException e) {
                logger.log(Level.WARNING, "Failed to append " + path + ": " + e.getMessage());
            } finally {
                if (writer != null) {
                    writer.close();
                }
            }
        }
    }
}
