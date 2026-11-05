# Interview case study

Verified upgrade: 2026-11-05. Scope: job-switch and interview readiness.

Data Engineer: durable ingestion progress and restart behavior.

## Architecture and engineering decision

A staged Java scraper tracks collected job URLs, completed search pages and phase-two outcomes in append-only checkpoint files. External scraping/database paths remain separate from offline tests.

Checkpoint writes use UTF-8 buffered I/O with explicit flush and file sync. Storage errors propagate, and in-memory completion is published only after persistence succeeds. Existing unreadable checkpoints now stop loading rather than silently appearing empty.

## Five-minute walkthrough

Replace a checkpoint target with a directory, attempt completion, and show that the operation fails without becoming complete in memory. Restore storage, retry, create a new store and load the persisted result. Also show deduplication and UTF-8 round trips.

After the repository's documented dependency setup, run these commands from its root. Use an activated Python environment where applicable; Maven offline mode requires cached dependencies.

```text
mvn -o test
```

## Recorded evidence

11 JUnit tests passed, including 5 new checkpoint tests. Local verification used Maven 3.9.10 and JDK 17; the project targets Java 8. No live website or database calls were made.

Behavioral regressions were reproduced before their fixes. Passing contract tests demonstrate the named behavior; they are not an AI accuracy rating.

## Defensible resume wording

> Implemented restartable ingestion checkpoints that persist before publishing completion and preserve retryability when storage fails.

Use this wording only after you can reproduce and explain the implementation. Add measured numbers with their dataset and environment; never substitute synthetic results for real-user impact.

## Questions to prepare

Which crash windows remain? Is file sync equivalent to a database transaction? How would multiple workers coordinate checkpoint ownership and deduplicate database writes?

## Remaining evidence

One process owns these append-only files. Multi-process coordination, atomic multi-file commits, torn-write recovery and cross-filesystem power-loss guarantees are not established. Live portal/Oracle reconciliation and extraction quality require separate evidence.
