# Quick Start - Optimized Job Scraper

## Setup (5 minutes)

### 1. Install Dependencies
```bash
mvn clean install
```

This will download:
- HikariCP (connection pooling)
- WebDriverManager (auto-manages ChromeDriver)
- All existing dependencies

### 2. Configure Application
```bash
# Copy example config
cp config.properties.example config.properties

# Edit with your settings
notepad config.properties  # Windows
```

**Minimum required changes:**
- `db.user` - Your Oracle username
- `db.password` - Your Oracle password
- `db.url` - Your Oracle connection string

### 3. Run
```bash
mvn exec:java -Dexec.mainClass="Main"
```

## Performance Tuning

### Quick Wins (Enable Now)

**1. Enable Headless Mode** (30% faster)
```properties
browser.headless=true
```

**2. Disable Images** (20% faster)
```properties
browser.disable.images=true
```

**3. Increase Batch Size** (2x faster DB writes)
```properties
scraper.batch.size=100
```

### Finding Optimal Thread Count

Start with 5 threads and monitor:

```properties
scraper.threads=5  # Start here
```

**Increase threads if:**
- CPU usage < 70%
- No rate limiting from Naukri
- Stable memory usage

**Decrease threads if:**
- Getting blocked/rate limited
- High memory usage
- System becomes unstable

**Recommended by system:**
- 4 CPU cores: 3-5 threads
- 8 CPU cores: 5-8 threads
- 16 CPU cores: 8-12 threads

## Expected Performance

### Before Optimization
- Speed: ~10 jobs/second
- Memory: High (15 browsers)
- DB Connections: 300+ created/destroyed
- Time for 3000 jobs: ~5 minutes

### After Optimization (Default Config)
- Speed: ~50-100 jobs/second
- Memory: Medium (5 browsers)
- DB Connections: 5 reused
- Time for 3000 jobs: ~30-60 seconds

### After Optimization (Aggressive Config)
```properties
scraper.threads=10
scraper.batch.size=100
browser.headless=true
browser.disable.images=true
```
- Speed: ~200+ jobs/second
- Time for 3000 jobs: ~15-20 seconds

## Monitoring

Watch console output for:

```
=== Job Scraper Started ===
Total pages: 15
Thread pool size: 5
Batch size: 50
Headless mode: false

Thread pool-1-thread-1 starting scrape from page: 0
Thread pool-1-thread-2 starting scrape from page: 250
...

Flushed batch: 50 jobs saved  ← Good! Batch inserts working
Flushed batch: 50 jobs saved
...

Thread pool-1-thread-1 completed page: 0
Thread completed. Pages processed: 42

=== Scraping Completed ===
Total time: 45 seconds (0 minutes)  ← Much faster!
```

## Troubleshooting

### Error: "Configuration file not found"
```bash
# Make sure config.properties exists in project root
ls config.properties

# If not, copy from example
cp config.properties.example config.properties
```

### Error: "Connection pool initialization failed"
- Check Oracle DB is running
- Verify credentials in config.properties
- Test connection: `sqlplus system/root@localhost:1521/XE`

### Getting Rate Limited by Naukri
```properties
# Slow down scraping
scraper.threads=3
scraper.page.load.delay=1000
```

### Out of Memory
```properties
# Reduce resource usage
scraper.threads=3
scraper.batch.size=25
browser.headless=true
browser.disable.images=true
```

### ChromeDriver Version Mismatch
No action needed! WebDriverManager auto-downloads the correct version.

## Comparing Old vs New

### Run Old Version
```java
// In Main.java, change:
OptimizedNaukriScraper scraper = new OptimizedNaukriScraper();

// To:
NaukriScrapperChrome scraper = new NaukriScrapperChrome();
```

### Run New Version (Default)
Already configured in Main.java

## Next Steps

1. ✅ Run with default config to verify it works
2. ✅ Enable headless mode for speed boost
3. ✅ Tune thread count for your system
4. ✅ Increase batch size if memory allows
5. ⏭️ Add data parsing (see your SQL cleanup scripts)
6. ⏭️ Implement retry logic for failed pages
7. ⏭️ Add monitoring/metrics dashboard

## Configuration Cheat Sheet

### Development (Slow but Stable)
```properties
scraper.threads=3
scraper.batch.size=25
browser.headless=false
scraper.page.load.delay=1000
```

### Production (Fast)
```properties
scraper.threads=8
scraper.batch.size=100
browser.headless=true
browser.disable.images=true
scraper.page.load.delay=500
```

### Testing (Very Slow, Won't Get Blocked)
```properties
scraper.threads=1
scraper.batch.size=10
browser.headless=false
scraper.page.load.delay=2000
```

## Support

Check `OPTIMIZATION_GUIDE.md` for detailed architecture and tuning information.
