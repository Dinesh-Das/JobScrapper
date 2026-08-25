package org.dinesh.scrapper;

import org.dinesh.entity.JobListingEntity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class ScrapeResultTest {
    @Test
    void scrapedOutcomeCarriesTheExtractedJob() {
        JobListingEntity job = new JobListingEntity();
        job.setUrl("https://example.test/job");

        TwoPhaseNaukriScraper.ScrapeResult result =
                TwoPhaseNaukriScraper.ScrapeResult.scraped(job.getUrl(), job);

        assertEquals(TwoPhaseNaukriScraper.ScrapeStatus.SCRAPED, result.getStatus());
        assertSame(job, result.getJob());
    }

    @Test
    void failedOutcomeKeepsTheUrlRetryable() {
        TwoPhaseNaukriScraper.ScrapeResult result =
                TwoPhaseNaukriScraper.ScrapeResult.failed("https://example.test/job", "blocked");

        assertEquals(TwoPhaseNaukriScraper.ScrapeStatus.FAILED, result.getStatus());
        assertEquals("blocked", result.getMessage());
    }
}
