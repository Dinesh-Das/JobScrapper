# Job Listing Scrapper

A Java job-ingestion project with staged collection, retryable outcomes and restart checkpoints.

See [the interview case study](INTERVIEW.md) for architecture, failure demonstrations, verification commands and remaining evidence.

Run offline tests with JDK 17 and Maven after dependencies are available:

```sh
mvn test
```

Runtime scraping and database setup are separate from these tests. Configure service credentials locally; do not commit them.
