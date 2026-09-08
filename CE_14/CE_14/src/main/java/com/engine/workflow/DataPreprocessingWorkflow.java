// DataPreprocessingWorkflow.java
import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;

public class DataPreprocessingWorkflow {
    
    private static final String INPUT_BASE = ".";
    private static final String OUTPUT_FOLDER = "data";
    private static final String LOG_FOLDER = "logs";
    private static final String SUMMARY_FILE = "processing_summary.txt";
    
    public static void main(String[] args) {
        System.out.println("╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║         DATA PREPROCESSING WORKFLOW - STARTING              ║");
        System.out.println("╚══════════════════════════════════════════════════════════════╝");
        System.out.println();
        
        long startTime = System.currentTimeMillis();
        List<String> errors = new ArrayList<>();
        Map<String, Boolean> results = new LinkedHashMap<>();
        
        try {
            // Create necessary directories
            createDirectories();
            
            // Initialize logging
            String logFile = LOG_FOLDER + "/workflow_" + 
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + ".log";
            PrintWriter logWriter = new PrintWriter(new FileWriter(logFile, true));
            
            logWriter.println("=== WORKFLOW STARTED: " + LocalDateTime.now() + " ===");
            logWriter.println();
            
            // Step 1: Process Option Child Data
            System.out.println("📋 STEP 1: Processing Option-Child Data");
            System.out.println("─────────────────────────────────────────────────────────────");
            boolean step1Success = runPreprocessing(
                "OptionChildPreprocessing",
                "input_option_child",
                "child_part_option.csv",
                logWriter
            );
            results.put("Option-Child Preprocessing", step1Success);
            if (!step1Success) errors.add("Option-Child Preprocessing failed");
            System.out.println();
            
            // Step 2: Process BOM ESN Config Data
            System.out.println("📋 STEP 2: Processing BOM ESN Config Data");
            System.out.println("─────────────────────────────────────────────────────────────");
            boolean step2Success = runPreprocessing(
                "BomEsnConfigPreprocessing",
                "input_bom_config",
                "Csv_bom_esn_config.csv",
                logWriter
            );
            results.put("BOM ESN Config Preprocessing", step2Success);
            if (!step2Success) errors.add("BOM ESN Config Preprocessing failed");
            System.out.println();
            
            // Step 3: Process BOM Option Data
            System.out.println("📋 STEP 3: Processing BOM Option Data");
            System.out.println("─────────────────────────────────────────────────────────────");
            boolean step3Success = runPreprocessing(
                "BomOptionPreprocessing",
                "input_bom_option",
                "Csv_bom_esn_option_*.csv",
                logWriter
            );
            results.put("BOM Option Preprocessing", step3Success);
            if (!step3Success) errors.add("BOM Option Preprocessing failed");
            System.out.println();
            
            // Calculate execution time
            long endTime = System.currentTimeMillis();
            long duration = (endTime - startTime) / 1000; // seconds
            
            // Generate summary
            generateSummary(results, errors, duration, logWriter);
            
            // Print final summary
            printFinalSummary(results, errors, duration);
            
            logWriter.println();
            logWriter.println("=== WORKFLOW COMPLETED: " + LocalDateTime.now() + " ===");
            logWriter.close();
            
            // Exit with appropriate code
            if (!errors.isEmpty()) {
                System.exit(1);
            }
            
        } catch (Exception e) {
            System.err.println("❌ FATAL ERROR: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
    
    private static void createDirectories() throws IOException {
        Files.createDirectories(Paths.get(OUTPUT_FOLDER));
        Files.createDirectories(Paths.get(LOG_FOLDER));
        Files.createDirectories(Paths.get("input_option_child"));
        Files.createDirectories(Paths.get("input_bom_config"));
        Files.createDirectories(Paths.get("input_bom_option"));
    }
    
    private static boolean runPreprocessing(String className, String inputFolder, 
                                           String expectedOutput, PrintWriter logWriter) {
        try {
            logWriter.println("--- Running: " + className + " ---");
            logWriter.println("Input Folder: " + inputFolder);
            logWriter.println("Expected Output: " + expectedOutput);
            logWriter.println("Started at: " + LocalDateTime.now());
            logWriter.println();
            
            // Capture output from the preprocessing class
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            PrintStream ps = new PrintStream(baos);
            PrintStream oldOut = System.out;
            PrintStream oldErr = System.err;
            
            // Redirect output to capture logs
            System.setOut(ps);
            System.setErr(ps);
            
            // Run the preprocessing class using reflection
            Class<?> clazz = Class.forName(className);
            java.lang.reflect.Method mainMethod = clazz.getMethod("main", String[].class);
            mainMethod.invoke(null, (Object) new String[0]);
            
            // Restore original output streams
            System.out.flush();
            System.err.flush();
            System.setOut(oldOut);
            System.setErr(oldErr);
            
            // Log the output
            String output = baos.toString();
            logWriter.println(output);
            
            // Check if output file was created
            boolean success = checkOutputFiles(expectedOutput);
            
            if (success) {
                logWriter.println("✅ " + className + " completed successfully");
                System.out.println("✅ " + className + " completed successfully");
            } else {
                logWriter.println("❌ " + className + " completed but output files not found");
                System.out.println("❌ " + className + " completed but output files not found");
            }
            
            logWriter.println("Completed at: " + LocalDateTime.now());
            logWriter.println("─────────────────────────────────────────────────────────────");
            logWriter.println();
            
            return success;
            
        } catch (ClassNotFoundException e) {
            System.err.println("❌ Class not found: " + className + " - Make sure it's compiled");
            logWriter.println("❌ Class not found: " + className);
            e.printStackTrace(logWriter);
            return false;
        } catch (Exception e) {
            System.err.println("❌ Error running " + className + ": " + e.getMessage());
            logWriter.println("❌ ERROR: " + e.getMessage());
            e.printStackTrace(logWriter);
            return false;
        }
    }
    
    private static boolean checkOutputFiles(String expectedOutput) {
        try {
            Path outputPath = Paths.get(OUTPUT_FOLDER);
            
            if (expectedOutput.contains("*")) {
                // Handle wildcard pattern (like Csv_bom_esn_option_*.csv)
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(outputPath, 
                        path -> path.toString().matches(".*Csv_bom_esn_option_.*\\.csv"))) {
                    return stream.iterator().hasNext();
                }
            } else {
                // Check specific file
                Path filePath = outputPath.resolve(expectedOutput);
                return Files.exists(filePath) && Files.size(filePath) > 0;
            }
        } catch (IOException e) {
            return false;
        }
    }
    
    private static void generateSummary(Map<String, Boolean> results, 
                                       List<String> errors, 
                                       long duration, 
                                       PrintWriter logWriter) {
        logWriter.println();
        logWriter.println("═══════════════════════════════════════════════════════════════");
        logWriter.println("                    WORKFLOW SUMMARY                          ");
        logWriter.println("═══════════════════════════════════════════════════════════════");
        logWriter.println();
        
        logWriter.println("STATUS:");
        int completed = 0;
        for (Map.Entry<String, Boolean> entry : results.entrySet()) {
            String status = entry.getValue() ? "✅ SUCCESS" : "❌ FAILED";
            logWriter.println("  " + status + " - " + entry.getKey());
            if (entry.getValue()) completed++;
        }
        
        logWriter.println();
        logWriter.println("TOTAL: " + completed + "/" + results.size() + " steps completed");
        
        if (!errors.isEmpty()) {
            logWriter.println();
            logWriter.println("ERRORS:");
            for (String error : errors) {
                logWriter.println("  ❌ " + error);
            }
        }
        
        logWriter.println();
        logWriter.println("DURATION: " + duration + " seconds");
        logWriter.println("OUTPUT FOLDER: " + OUTPUT_FOLDER);
        logWriter.println();
    }
    
    private static void printFinalSummary(Map<String, Boolean> results, 
                                         List<String> errors, 
                                         long duration) {
        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║                    WORKFLOW COMPLETE                       ║");
        System.out.println("╚══════════════════════════════════════════════════════════════╝");
        System.out.println();
        
        System.out.println("📊 SUMMARY:");
        int completed = 0;
        for (Map.Entry<String, Boolean> entry : results.entrySet()) {
            String status = entry.getValue() ? "✅" : "❌";
            System.out.println("  " + status + " " + entry.getKey());
            if (entry.getValue()) completed++;
        }
        
        System.out.println();
        System.out.println("📈 Total: " + completed + "/" + results.size() + " steps completed");
        System.out.println("⏱️  Duration: " + duration + " seconds");
        System.out.println("📁 Output folder: " + OUTPUT_FOLDER);
        
        if (!errors.isEmpty()) {
            System.out.println();
            System.out.println("❌ ERRORS:");
            for (String error : errors) {
                System.out.println("  - " + error);
            }
            System.out.println();
            System.out.println("⚠️  Some steps failed. Check logs for details.");
        } else {
            System.out.println();
            System.out.println("✅ ALL STEPS COMPLETED SUCCESSFULLY!");
        }
        
        System.out.println();
        System.out.println("Logs saved to: " + LOG_FOLDER);
        System.out.println();
    }
}