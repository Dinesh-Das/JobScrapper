import org.dinesh.scrapper.NaukriScrapper;

public class Main {
    public static void main(String[] args) {
        int totalPages = 9;
        Thread[] threads = new Thread[totalPages];

        for (int i = 0; i < totalPages; i++) {
            final int pageNumber = i* 500;

            // Define the Runnable task inline
            Runnable task = new Runnable() {
                @Override
                public void run() {
                    System.out.println("Fetching page from: " + pageNumber + " by thread " + Thread.currentThread().getName());
                    try {
//                        Thread.sleep(2000);
                        NaukriScrapper obj = new NaukriScrapper();
                        obj.Scrape(pageNumber);
                        Thread.sleep(2000); // Simulating 2 seconds of work
                    } catch (InterruptedException e) {
                        System.err.println("Thread interrupted while scraping page " + pageNumber);
                    }
                    System.out.println("Completed scraping page: " + pageNumber + " by thread " + Thread.currentThread().getName());
                }
            };

            // Create and start a thread for each task
            threads[i] = new Thread(task);
            threads[i].start();
        }

        // Wait for all threads to complete
        for (int i = 0; i < totalPages; i++) {
            try {
                threads[i].join(); // Ensure main thread waits for all threads
            } catch (InterruptedException e) {
                System.err.println("Main thread interrupted while waiting for thread " + i);
            }
        }


    }
}
