import java.io.*;
import java.util.*;

/**
 * Utility to merge multiple checkpoint files and remove duplicates
 * 
 * Usage:
 *   java MergeCheckpoints.java file1.txt file2.txt file3.txt output.txt
 *   
 * Or compile and run:
 *   javac MergeCheckpoints.java
 *   java MergeCheckpoints file1.txt file2.txt file3.txt output.txt
 */
public class MergeCheckpoints {
    
    public static void main(String[] args) {
        if (args.length < 2) {
            System.out.println("Usage: java MergeCheckpoints <input_file1> <input_file2> ... <output_file>");
            System.out.println("Example: java MergeCheckpoints urls1.txt urls2.txt merged_urls.txt");
            System.exit(1);
        }
        
        // Last argument is output file
        String outputFile = args[args.length - 1];
        
        // All other arguments are input files
        String[] inputFiles = Arrays.copyOfRange(args, 0, args.length - 1);
        
        System.out.println("=== Merging Checkpoint Files ===");
        System.out.println("Input files: " + inputFiles.length);
        System.out.println("Output file: " + outputFile);
        System.out.println();
        
        // Use LinkedHashSet to preserve order and remove duplicates
        Set<String> allUrls = new LinkedHashSet<>();
        int totalLines = 0;
        int duplicatesRemoved = 0;
        
        // Read all input files
        for (String inputFile : inputFiles) {
            File file = new File(inputFile);
            if (!file.exists()) {
                System.err.println("WARNING: File not found: " + inputFile);
                continue;
            }
            
            System.out.println("Reading: " + inputFile);
            int fileLines = 0;
            
            try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (!line.isEmpty()) {
                        totalLines++;
                        fileLines++;
                        
                        // Set.add() returns false if element already exists
                        if (!allUrls.add(line)) {
                            duplicatesRemoved++;
                        }
                    }
                }
                System.out.println("  - Lines read: " + fileLines);
                
            } catch (IOException e) {
                System.err.println("ERROR reading " + inputFile + ": " + e.getMessage());
            }
        }
        
        System.out.println();
        System.out.println("=== Summary ===");
        System.out.println("Total lines read: " + totalLines);
        System.out.println("Unique URLs: " + allUrls.size());
        System.out.println("Duplicates removed: " + duplicatesRemoved);
        System.out.println();
        
        // Write merged URLs to output file
        System.out.println("Writing to: " + outputFile);
        try (PrintWriter writer = new PrintWriter(new FileWriter(outputFile))) {
            for (String url : allUrls) {
                writer.println(url);
            }
            System.out.println("✓ Successfully wrote " + allUrls.size() + " unique URLs");
            
        } catch (IOException e) {
            System.err.println("ERROR writing to " + outputFile + ": " + e.getMessage());
            System.exit(1);
        }
        
        System.out.println();
        System.out.println("=== Merge Complete ===");
        System.out.println("You can now use '" + outputFile + "' as your checkpoint file");
    }
}
