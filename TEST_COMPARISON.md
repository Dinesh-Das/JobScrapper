# Performance Comparison Test

## How to Compare Old vs New Implementation

### Test 1: Small Scale (1 page, ~20 jobs)

#### Old Version
```properties
# config.properties
scraper.total.pages=1
scraper.threads=1
```

```java
// Main.java - Change line 20 to:
NaukriScrapperChrome scraper = new NaukriScrapperChrome();
```

Run and note:
- Total time: _____ seconds
- Memory usage: Check Task Manager
- Jobs scraped: _____

#### New Version
```properties
# config.properties (same settings)
scraper.total.pages=1
scraper.threads=1
scraper.batch.size=20
```

```java
// Main.java - Change line 20 to:
OptimizedNaukriScraper scraper = new OptimizedNaukriScraper();
```

Run and note:
- Total time: _____ seconds
- Memory usage: Check Task Manager
- Jobs scraped: _____

**Expected Result:** New version should be 2-3x faster due to batch inserts

---

### Test 2: Medium Scale (5 pages, ~100 jobs)

#### Old Version
```properties
scraper.total.pages=5
scraper.threads=5
```

Run and note:
- Total time: _____ seconds
- Peak memory: _____
- Jobs scraped: _____

#### New Version
```properties
scraper.total.pages=5
scraper.threads=5
scraper.batch.size=50
```

Run and note:
- Total time: _____ seconds
- Peak memory: _____
- Jobs scraped: _____

**Expected Result:** New version should be 5-10x faster

---

### Test 3: Full Scale (15 pages, ~300 jobs)

#### Old Version
```properties
scraper.total.pages=15
scraper.threads=15  # Old default
```

Run and note:
- Total time: _____ seconds
- Peak memory: _____
- Jobs scraped: _____
- System stability: Stable / Unstable

#### New Version (Conservative)
```properties
scraper.total.pages=15
scraper.threads=5
scraper.batch.size=50
browser.headless=false
```

Run and note:
- Total time: _____ seconds
- Peak memory: _____
- Jobs scraped: _____
- System stability: Stable / Unstable

#### New Version (Aggressive)
```properties
scraper.total.pages=15
scraper.threads=8
scraper.batch.size=100
browser.headless=true
browser.disable.images=true
```

Run and note:
- Total time: _____ seconds
- Peak memory: _____
- Jobs scraped: _____
- System stability: Stable / Unstable

**Expected Results:**
- Conservative: 10-15x faster than old, very stable
- Aggressive: 20-30x faster than old, stable

---

## Verification Checklist

After each test, verify:

### Data Quality
```sql
-- Check record count
SELECT COUNT(*) FROM JobDetails;

-- Check for duplicates
SELECT URL, COUNT(*) 
FROM JobDetails 
GROUP BY URL 
HAVING COUNT(*) > 1;

-- Check for null values
SELECT COUNT(*) 
FROM JobDetails 
WHERE Title IS NULL OR CompanyName IS NULL;

-- Compare data between runs
SELECT * FROM JobDetails 
WHERE LOGDATE > SYSDATE - 1/24  -- Last hour
ORDER BY LOGDATE DESC;
```

### Performance Metrics
- [ ] Total execution time
- [ ] Jobs per second
- [ ] Peak memory usage
- [ ] CPU usage
- [ ] System stability

### Functionality
- [ ] All jobs scraped successfully
- [ ] No duplicate URLs
- [ ] All fields populated
- [ ] No errors in console
- [ ] Database connections closed properly

---

## Sample Results Template

```
=== TEST RESULTS ===

Test Date: __________
Test Type: Small / Medium / Full
Version: Old / New

Configuration:
- Total Pages: ____
- Threads: ____
- Batch Size: ____ (new only)
- Headless: Yes / No
- Disable Images: Yes / No

Results:
- Total Time: ____ seconds
- Jobs Scraped: ____
- Jobs/Second: ____
- Peak Memory: ____ MB
- CPU Usage: ____% average
- Errors: ____

Database:
- Records Inserted: ____
- Duplicates: ____
- Null Fields: ____

System Stability:
- Stable / Unstable
- Notes: ________________

Conclusion:
- Performance vs Old: __x faster
- Recommendation: Use / Don't Use
```

---

## Quick Comparison Commands

### Check Database Performance
```sql
-- Before test
SELECT COUNT(*) as before_count FROM JobDetails;

-- After test
SELECT COUNT(*) as after_count FROM JobDetails;

-- Jobs inserted this run
SELECT after_count - before_count as jobs_inserted;
```

### Monitor System Resources (Windows)
```powershell
# CPU and Memory usage
Get-Process java | Select-Object CPU, WorkingSet

# Or use Task Manager
taskmgr
```

### Calculate Performance Improvement
```
Improvement = (Old Time - New Time) / Old Time × 100%

Example:
Old: 300 seconds
New: 30 seconds
Improvement = (300 - 30) / 300 × 100% = 90% faster
```

---

## Troubleshooting Test Issues

### Old Version Crashes
- Reduce threads to 5
- Add more memory to JVM: `java -Xmx2g`

### New Version Slower Than Expected
- Check batch size (should be 50-100)
- Enable headless mode
- Verify connection pool is working (check logs)

### Different Job Counts
- Naukri.com content changes frequently
- Run tests close together in time
- Compare percentage improvement, not absolute numbers

### Database Errors
- Check connection pool size
- Verify Oracle DB has enough connections available
- Check tablespace has enough space

---

## Recommended Test Sequence

1. **Day 1: Small Scale Test**
   - Verify both versions work
   - Confirm data quality
   - Get baseline metrics

2. **Day 2: Medium Scale Test**
   - Test with realistic load
   - Monitor system resources
   - Tune configuration

3. **Day 3: Full Scale Test**
   - Production-like scenario
   - Test different configurations
   - Choose optimal settings

4. **Day 4: Stress Test**
   - Push limits
   - Find breaking points
   - Establish safe operating range

---

## Success Criteria

The new version is successful if:

- ✅ At least 5x faster than old version
- ✅ Uses less memory
- ✅ More stable (no crashes)
- ✅ Same or better data quality
- ✅ Easy to configure
- ✅ Works on different systems

If any criteria fails, check:
- Configuration settings
- System resources
- Database performance
- Network connectivity
