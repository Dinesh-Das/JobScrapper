# Job Listing Scrapper

A Java job-ingestion project with staged collection, retryable outcomes and restart checkpoints.

See [the interview case study](INTERVIEW.md) for architecture, failure demonstrations, verification commands and remaining evidence.

Run offline tests with JDK 17 and Maven after dependencies are available:

```sh
mvn test
```

Runtime scraping and database setup are separate from these tests. Configure service credentials locally; do not commit them.

Database retries are bounded per sub-batch. A later chunk's failure does not replay
an earlier acknowledged commit. If retries are exhausted, the method reports only
acknowledged rows, retains their duplicate-cache entries, and releases failed or
unattempted URL claims. The coordinator leaves an incomplete batch uncheckpointed
for reconciliation on the next run.

A lost commit acknowledgement or failed rollback has an unknown transaction
outcome: the connection is discarded, automatic retries stop, and the uncertain chunk
is not counted as saved. Reconcile actual database URLs before retrying that work.
This does not promise exactly-once writes across connection loss or process crashes.
Offline tests use a fault-injected store and JDBC interface proxies; they do not
validate an Oracle deployment.
