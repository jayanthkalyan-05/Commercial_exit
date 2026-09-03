// Custom_CE_workflow.java
package com.engine.workflow;

import com.google.gson.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.sql.*;

/**
 * Workflow runner for Engine Data Processing Pipeline
 * Modified to call Java classes directly instead of Python scripts
 */
public class Custom_CE_workflow {
    
    private static final DateTimeFormatter DATE_FORMAT = 
        DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    
    private String configFile;
    private JsonObject config;
    private List<ScriptResult> results = new ArrayList<>();
    private String logFile;
    private String commonFolder;
    private String workingDir;
    private List<String> optionFiles = new ArrayList<>();
    private boolean cleanupEnabled;
    private PrintWriter logWriter;
    
    public Custom_CE_workflow(String configFile) {
        this.configFile = configFile;
        this.workingDir = System.getProperty("user.dir");
        this.logFile = "workflow_log_" + LocalDateTime.now().format(DATE_FORMAT) + ".txt";
        loadConfig();
        this.commonFolder = config.has("common_folder") ? 
            config.get("common_folder").getAsString() : ".";
        this.cleanupEnabled = config.has("cleanup_intermediate_files") ? 
            config.get("cleanup_intermediate_files").getAsBoolean() : true;
    }
    
    private void loadConfig() {
        File configFileObj = new File(configFile);
        if (!configFileObj.exists()) {
            System.err.println("ERROR: Config file " + configFile + " not found!");
            System.exit(1);
        }
        
        try (Reader reader = new FileReader(configFileObj)) {
            Gson gson = new Gson();
            config = gson.fromJson(reader, JsonObject.class);
        } catch (IOException e) {
            System.err.println("ERROR: Could not load config: " + e.getMessage());
            System.exit(1);
        }
    }
    
    private void logMessage(String message, String level) {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        String logEntry = "[" + timestamp + "] [" + level + "] " + message;
        System.out.println(logEntry);
        
        try {
            if (logWriter == null) {
                logWriter = new PrintWriter(new FileWriter(logFile, true));
            }
            logWriter.println(logEntry);
            logWriter.flush();
        } catch (IOException e) {
            System.err.println("Could not write to log: " + e.getMessage());
        }
    }
    
    private void logMessage(String message) {
        logMessage(message, "INFO");
    }
    
    private boolean checkDiskSpace(double requiredGB) {
        try {
            File file = new File(workingDir);
            long freeSpace = file.getFreeSpace();
            double freeGB = freeSpace / (1024.0 * 1024.0 * 1024.0);
            if (freeGB < requiredGB) {
                logMessage(String.format("WARNING: Only %.2f GB free. Required: %.2f GB.", freeGB, requiredGB), "WARNING");
                return false;
            }
            return true;
        } catch (Exception e) {
            return true;
        }
    }
    
    private String findFileInCommonFolder(String filename) {
        if (filename.contains("*") || filename.contains("?")) {
            String commonPattern = commonFolder + File.separator + filename;
            List<String> matches = glob(commonPattern);
            if (!matches.isEmpty()) {
                return matches.get(0);
            }
            
            matches = glob(filename);
            if (!matches.isEmpty()) {
                return matches.get(0);
            }
            return null;
        }
        
        String commonPath = commonFolder + File.separator + filename;
        if (new File(commonPath).exists()) {
            return commonPath;
        }
        
        if (new File(filename).exists()) {
            return filename;
        }
        
        return null;
    }
    
    // ******* WINDOWS SAFE GLOB METHOD *******
    private List<String> glob(String pattern) {
        List<String> matches = new ArrayList<>();
        try {
            Path baseDir = Paths.get(".");
            String cleanPattern = pattern;
            
            // Check if the pattern has a directory prefix (e.g., "data/")
            if (pattern.contains("/") || pattern.contains("\\")) {
                // Split manually to avoid InvalidPathException on Windows
                String separator = pattern.contains("\\") ? "\\\\" : "/";
                String[] parts = pattern.split(separator);
                
                if (parts.length > 1) {
                    // Build the base directory path using the parts before the filename
                    StringBuilder baseDirStr = new StringBuilder();
                    for (int i = 0; i < parts.length - 1; i++) {
                        if (i > 0) baseDirStr.append(File.separator);
                        baseDirStr.append(parts[i]);
                    }
                    baseDir = Paths.get(baseDirStr.toString());
                    cleanPattern = parts[parts.length - 1];
                }
            }
            
            PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + cleanPattern);
            
            // Walk the base directory (which might be the "data" folder)
            Files.walk(baseDir, 1)
                .filter(Files::isRegularFile)
                .filter(p -> matcher.matches(p.getFileName()))
                .forEach(p -> matches.add(p.toString()));
                
        } catch (IOException e) {
            // Ignore
        } catch (Exception e) {
            logMessage("Error in glob: " + e.getMessage(), "ERROR");
        }
        return matches;
    }
    
    private List<String> findAllOptionFiles() {
        JsonObject filePatterns = config.getAsJsonObject("file_patterns");
        JsonObject optionPatternObj = filePatterns.getAsJsonObject("Csv_bom_esn_option");
        String optionPattern = optionPatternObj.get("pattern").getAsString();
        
        Set<String> allMatches = new HashSet<>();
        
        String commonPattern = commonFolder + File.separator + optionPattern;
        allMatches.addAll(glob(commonPattern));
        allMatches.addAll(glob(optionPattern));
        
        optionFiles = allMatches.stream()
            .map(f -> new File(f).getName())
            .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);
        
        optionFiles.sort((a, b) -> {
            String[] partsA = a.split("_");
            String[] partsB = b.split("_");
            if (partsA.length >= 2 && partsB.length >= 2) {
                try {
                    int numA = Integer.parseInt(partsA[partsA.length - 2]);
                    int numB = Integer.parseInt(partsB[partsB.length - 2]);
                    if (numA != numB) return Integer.compare(numA, numB);
                    String fileA = partsA[partsA.length - 1];
                    String fileB = partsB[partsB.length - 1];
                    int idA = Integer.parseInt(fileA.replaceAll("\\D", ""));
                    int idB = Integer.parseInt(fileB.replaceAll("\\D", ""));
                    return Integer.compare(idA, idB);
                } catch (NumberFormatException e) {
                    return a.compareTo(b);
                }
            }
            return a.compareTo(b);
        });
        
        if (!optionFiles.isEmpty()) {
            logMessage("Found " + optionFiles.size() + " option file(s):");
            for (String f : optionFiles) {
                logMessage("  - " + f);
            }
        } else {
            logMessage("No option files found matching pattern: " + optionPattern, "WARNING");
        }
        
        return optionFiles;
    }
    
    private String getOptionFileName(String filePath) {
        String base = new File(filePath).getName();
        int extIndex = base.lastIndexOf('.');
        return extIndex > 0 ? base.substring(0, extIndex) : base;
    }
    
    private boolean copyFileFromCommon(String filename) {
        String source = findFileInCommonFolder(filename);
        if (source != null) {
            try {
                Files.copy(Paths.get(source), Paths.get(filename), StandardCopyOption.REPLACE_EXISTING);
                logMessage("  Copied " + filename + " from " + source);
                return true;
            } catch (IOException e) {
                logMessage("  Failed to copy " + filename + ": " + e.getMessage(), "ERROR");
                return false;
            }
        } else {
            logMessage("  " + filename + " not found in common folder", "ERROR");
            return false;
        }
    }
    
    private boolean copyAllDataFiles() {
        logMessage("\nCopying data files from common folder...");
        logMessage("   Common folder: " + commonFolder);
        
        boolean success = true;
        JsonObject dataFiles = config.getAsJsonObject("data_files");
        for (String filename : dataFiles.keySet()) {
            if (!copyFileFromCommon(filename)) {
                JsonObject fileInfo = dataFiles.getAsJsonObject(filename);
                if (fileInfo.has("required") && fileInfo.get("required").getAsBoolean()) {
                    success = false;
                }
            }
        }
        return success;
    }
    
    private boolean checkDataFiles() {
        logMessage("\nChecking data files in common folder...");
        logMessage("   Common folder: " + commonFolder);
        
        boolean allExist = true;
        JsonObject dataFiles = config.getAsJsonObject("data_files");
        
        for (String filename : dataFiles.keySet()) {
            String filePath = findFileInCommonFolder(filename);
            JsonObject fileInfo = dataFiles.getAsJsonObject(filename);
            
            if (filePath != null) {
                long size = new File(filePath).length();
                double sizeMB = size / (1024.0 * 1024.0);
                logMessage(String.format("  [OK] %s found (%.2f MB)", filename, sizeMB));
            } else {
                logMessage("  [MISSING] " + filename + " NOT found", "ERROR");
                if (fileInfo.has("required") && fileInfo.get("required").getAsBoolean()) {
                    allExist = false;
                }
            }
        }
        
        List<String> optionFiles = findAllOptionFiles();
        if (optionFiles.isEmpty()) {
            logMessage("  [MISSING] No option files found!", "ERROR");
            allExist = false;
        }
        
        if (!allExist) {
            logMessage("\nERROR: Required data files missing!", "ERROR");
            logMessage("Please place the following files in: " + commonFolder);
            for (String filename : dataFiles.keySet()) {
                JsonObject fileInfo = dataFiles.getAsJsonObject(filename);
                if (fileInfo.has("required") && fileInfo.get("required").getAsBoolean()) {
                    String desc = fileInfo.has("description") ? fileInfo.get("description").getAsString() : "";
                    logMessage("  - " + filename + ": " + desc);
                }
            }
            logMessage("  - Csv_bom_esn_option*.csv: Multiple option files");
        }
        
        return allExist;
    }
    
    private boolean validateScripts() {
        logMessage("\nValidating Java classes...");
        
        boolean allExist = true;
        JsonArray scripts = config.getAsJsonArray("scripts");
        for (JsonElement scriptElem : scripts) {
            JsonObject script = scriptElem.getAsJsonObject();
            String scriptName = script.get("name").getAsString();
            String className = scriptName.replace(".py", "");
            
            try {
                Class.forName("com.engine.workflow." + className);
                logMessage("  [OK] " + scriptName + " found");
            } catch (ClassNotFoundException e) {
                logMessage("  [MISSING] " + scriptName + " not found", "ERROR");
                allExist = false;
            }
        }
        
        if (!allExist) {
            logMessage("\nERROR: Required Java classes missing!", "ERROR");
        }
        
        return allExist;
    }
    
    private long countRows(String filename) {
        File file = new File(filename);
        if (!file.exists()) {
            return 0;
        }
        
        try {
            if (filename.endsWith(".parquet")) {
                try (Connection conn = DriverManager.getConnection("jdbc:duckdb:")) {
                    try (Statement stmt = conn.createStatement();
                         ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM read_parquet('" + filename + "')")) {
                        if (rs.next()) {
                            return rs.getLong(1);
                        }
                    }
                } catch (SQLException e) {
                    logMessage("  WARNING: Could not count rows in " + filename + ": " + e.getMessage(), "WARNING");
                }
            } else {
                try (BufferedReader reader = new BufferedReader(new FileReader(filename))) {
                    long count = 0;
                    while (reader.readLine() != null) {
                        count++;
                    }
                    return Math.max(0, count - 1);
                }
            }
        } catch (IOException e) {
            logMessage("  WARNING: Could not count rows in " + filename + ": " + e.getMessage(), "WARNING");
        }
        return 0;
    }
    
    private Map<String, Long> getInputRecordsDetail(List<String> inputFiles) {
        Map<String, Long> details = new LinkedHashMap<>();
        
        for (String inputFile : inputFiles) {
            if (inputFile.contains("*") || inputFile.contains("?")) {
                for (String match : glob(inputFile)) {
                    if (new File(match).exists()) {
                        details.put(match, countRows(match));
                    }
                }
            } else {
                if (new File(inputFile).exists()) {
                    details.put(inputFile, countRows(inputFile));
                } else {
                    details.put(inputFile, 0L);
                }
            }
        }
        return details;
    }
    
    private String expandOutputFilename(String outputTemplate, String optionFileName) {
        if (optionFileName != null && outputTemplate.contains("{option_file_name}")) {
            return outputTemplate.replace("{option_file_name}", optionFileName);
        }
        return outputTemplate;
    }
    
    private ScriptResult runScript(JsonObject scriptInfo, String optionFile) {
        String scriptName = scriptInfo.get("name").getAsString();
        String className = scriptName.replace(".py", "");
        boolean required = scriptInfo.has("required") ? scriptInfo.get("required").getAsBoolean() : true;
        int step = scriptInfo.has("step") ? scriptInfo.get("step").getAsInt() : 0;
        int total = scriptInfo.has("total") ? scriptInfo.get("total").getAsInt() : 0;
        String runMode = scriptInfo.has("run_mode") ? scriptInfo.get("run_mode").getAsString() : "once";
        boolean cleanupAfter = scriptInfo.has("cleanup_after") ? scriptInfo.get("cleanup_after").getAsBoolean() : false;
        
        if ("per_option_file".equals(runMode) && optionFile == null) {
            logMessage("ERROR: " + scriptName + " requires option_file but none provided", "ERROR");
            return new ScriptResult(scriptName, optionFile, false, 0, null, 0, 0);
        }
        
        String optionFileName = null;
        if (optionFile != null) {
            optionFileName = getOptionFileName(optionFile);
        }
        
        List<String> inputFiles = new ArrayList<>();
        JsonArray inputFilesArray = scriptInfo.getAsJsonArray("input_files");
        if (inputFilesArray != null) {
            for (JsonElement inputElem : inputFilesArray) {
                String inputPattern = inputElem.getAsString();
                if (optionFileName != null) {
                    if (inputPattern.contains("{base_variant}")) {
                        String baseVariant = optionFileName.replace("Csv_bom_esn_option_", "");
                        inputFiles.add(inputPattern.replace("{base_variant}", baseVariant));
                    } else if (inputPattern.contains("{option_file_name}")) {
                        inputFiles.add(inputPattern.replace("{option_file_name}", optionFileName));
                    } else {
                        if (inputPattern.contains("*") || inputPattern.contains("?")) {
                            inputFiles.addAll(glob(inputPattern));
                        } else {
                            inputFiles.add(inputPattern);
                        }
                    }
                } else {
                    if (inputPattern.contains("*") || inputPattern.contains("?")) {
                        inputFiles.addAll(glob(inputPattern));
                    } else {
                        inputFiles.add(inputPattern);
                    }
                }
            }
        }
        
        String outputTemplate = scriptInfo.has("output") ? scriptInfo.get("output").getAsString() : null;
        String outputFile = null;
        if (outputTemplate != null) {
            outputFile = expandOutputFilename(outputTemplate, optionFileName);
        }
        
        Map<String, Long> inputDetails = getInputRecordsDetail(inputFiles);
        long totalInputRecords = inputDetails.values().stream().mapToLong(Long::longValue).sum();
        
        logMessage("\n" + "=".repeat(60));
        if (optionFile != null) {
            logMessage("[" + step + "/" + total + "] Running: " + scriptName + " (for " + optionFile + ")");
        } else {
            logMessage("[" + step + "/" + total + "] Running: " + scriptName);
        }
        String description = scriptInfo.has("description") ? scriptInfo.get("description").getAsString() : "";
        logMessage("   " + description);
        logMessage("   Run mode: " + runMode);
        logMessage("   Input files:");
        if (!inputDetails.isEmpty()) {
            for (Map.Entry<String, Long> entry : inputDetails.entrySet()) {
                logMessage(String.format("     - %s: %,d records", entry.getKey(), entry.getValue()));
            }
        } else {
            logMessage("     - No input files specified");
        }
        logMessage(String.format("   Total input records: %,d", totalInputRecords));
        if (outputFile != null) {
            logMessage("   Output file: " + outputFile);
        }
        logMessage("=".repeat(60));
        
        try {
            long startTime = System.currentTimeMillis();
            
            logMessage("Executing " + scriptName + "...");
            
            // Execute the Java class directly
            Class<?> clazz = Class.forName("com.engine.workflow." + className);
            
            // Build arguments for the main method
            String[] args;
            // FIXED CASING ISSUES HERE
            if ("Join2".equals(className)) {
                args = new String[]{optionFile};
            } else if ("Concatanation".equals(className)) {
                String inputFilename = outputFile.replace("file3_", "file2_");
                args = new String[]{inputFilename, outputFile};
            } else if ("UE2".equals(className) || "UE3".equals(className)) {
                if (inputFiles != null && !inputFiles.isEmpty() && outputFile != null) {
                    args = new String[]{inputFiles.get(0), outputFile};
                } else if (inputFiles != null && !inputFiles.isEmpty()) {
                    args = new String[]{inputFiles.get(0)};
                } else if (outputFile != null) {
                    args = new String[]{outputFile};
                } else {
                    args = new String[0];
                }
            } else if ("Join".equals(className) || "Aggregation".equals(className)) {
                args = new String[0];
            } else {
                args = new String[0];
            }
            
            // Invoke main method
            java.lang.reflect.Method mainMethod = clazz.getMethod("main", String[].class);
            
            // Redirect System.out to capture output
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            PrintStream ps = new PrintStream(baos);
            PrintStream oldOut = System.out;
            System.setOut(ps);
            
            try {
                mainMethod.invoke(null, (Object) args);
            } finally {
                System.out.flush();
                System.setOut(oldOut);
                String output = baos.toString();
                System.out.print(output);
            }
            
            long duration = System.currentTimeMillis() - startTime;
            
            long outputRecords = 0;
            if (outputFile != null && new File(outputFile).exists()) {
                outputRecords = countRows(outputFile);
            }
            
            logMessage(String.format("[SUCCESS] %s completed in %.2f seconds (%.2f minutes)", 
                scriptName, duration / 1000.0, duration / 60000.0));
            logMessage(String.format("   Input records: %,d", totalInputRecords));
            logMessage(String.format("   Output records: %,d", outputRecords));
            
            if (totalInputRecords > 0) {
                if (outputRecords > totalInputRecords) {
                    logMessage(String.format("   Records increased by: %,d (%.1f%%)", 
                        outputRecords - totalInputRecords, 
                        ((double) outputRecords / totalInputRecords - 1) * 100));
                } else if (outputRecords < totalInputRecords) {
                    logMessage(String.format("   Records decreased by: %,d (%.1f%%)", 
                        totalInputRecords - outputRecords, 
                        (1 - (double) outputRecords / totalInputRecords) * 100));
                } else {
                    logMessage("   Records unchanged");
                }
            }
            
            if (outputFile != null && new File(outputFile).exists()) {
                long size = new File(outputFile).length();
                double sizeMB = size / (1024.0 * 1024.0);
                double sizeGB = size / (1024.0 * 1024.0 * 1024.0);
                if (sizeGB >= 1) {
                    logMessage(String.format("   Output: %s (%.2f GB)", outputFile, sizeGB));
                } else {
                    logMessage(String.format("   Output: %s (%.2f MB)", outputFile, sizeMB));
                }
                
                if (config.has("copy_output_to_common") && 
                    config.get("copy_output_to_common").getAsBoolean()) {
                    String commonOutput = commonFolder + File.separator + outputFile;
                    try {
                        Files.copy(Paths.get(outputFile), Paths.get(commonOutput), 
                            StandardCopyOption.REPLACE_EXISTING);
                        logMessage("   Copied to common folder: " + commonOutput);
                    } catch (IOException e) {
                        logMessage("   Failed to copy output to common folder: " + e.getMessage(), "WARNING");
                    }
                }
            } else if (outputFile != null) {
                logMessage("WARNING: Output file '" + outputFile + "' not created", "WARNING");
            }
            
            if (cleanupAfter && optionFileName != null) {
                cleanupStageFiles(optionFileName, null);
            }
            
            return new ScriptResult(scriptName, optionFile, true, duration, inputDetails, 
                totalInputRecords, outputRecords);
            
        } catch (Exception e) {
            logMessage("ERROR: Error running " + scriptName + ": " + e.getMessage(), "ERROR");
            e.printStackTrace();
            return new ScriptResult(scriptName, optionFile, false, 0, inputDetails, totalInputRecords, 0);
        }
    }
    
    private void cleanupStageFiles(String optionFileName, List<String> keepPatterns) {
        if (!cleanupEnabled) {
            return;
        }
        
        logMessage("\n🧹 Cleaning up intermediate files...");
        
        List<String> patternsToDelete = new ArrayList<>();
        if (optionFileName != null) {
            patternsToDelete.add("file2_" + optionFileName + ".csv");
        } else {
            patternsToDelete.add("file2_*.csv");
        }
        
        Set<String> keepFiles = new HashSet<>();
        if (keepPatterns != null) {
            for (String pattern : keepPatterns) {
                keepFiles.addAll(glob(pattern));
            }
        } else {
            String[] defaultKeep = {
                "file3_*.csv", "file4_aggregated.parquet", "file5.parquet",
                "file6.csv", "file7.csv", "Csv_bom_esn_*.csv",
                "child_part_option.csv", "child_part_option.parquet"
            };
            for (String pattern : defaultKeep) {
                keepFiles.addAll(glob(pattern));
            }
        }
        
        int deletedCount = 0;
        double deletedSize = 0;
        
        for (String pattern : patternsToDelete) {
            for (String filePath : glob(pattern)) {
                if (!keepFiles.contains(filePath)) {
                    File file = new File(filePath);
                    if (file.exists()) {
                        double sizeGB = file.length() / (1024.0 * 1024.0 * 1024.0);
                        try {
                            Files.delete(file.toPath());
                            deletedCount++;
                            deletedSize += sizeGB;
                            logMessage("  🗑️ Deleted: " + filePath + String.format(" (%.2f GB)", sizeGB), "INFO");
                        } catch (IOException e) {
                            logMessage("  ⚠️ Failed to delete " + filePath + ": " + e.getMessage(), "WARNING");
                        }
                    }
                }
            }
        }
        
        if (deletedCount > 0) {
            logMessage(String.format("  ✅ Deleted %d files (%.2f GB freed)", deletedCount, deletedSize), "INFO");
        } else {
            logMessage("  ℹ️ No intermediate files to clean up", "INFO");
        }
    }
    
    public boolean runWorkflow() {
        logMessage("Starting workflow execution...");
        String workflowName = config.has("workflow_name") ? 
            config.get("workflow_name").getAsString() : "Unnamed Workflow";
        logMessage("Workflow: " + workflowName);
        logMessage("Common folder: " + commonFolder);
        logMessage("Working directory: " + workingDir);
        logMessage("Cleanup enabled: " + cleanupEnabled);
        
        if (!validateScripts()) {
            logMessage("ERROR: Cannot proceed due to missing classes", "ERROR");
            return false;
        }
        
        if (!checkDataFiles()) {
            logMessage("ERROR: Cannot proceed due to missing data files", "ERROR");
            return false;
        }
        
        if (!copyAllDataFiles()) {
            logMessage("ERROR: Failed to copy required data files", "ERROR");
            return false;
        }
        
        List<String> optionFiles = findAllOptionFiles();
        if (optionFiles.isEmpty()) {
            logMessage("ERROR: No option files found!", "ERROR");
            return false;
        }
        
        logMessage("\nFiles will be processed in chronological order:");
        for (String f : optionFiles) {
            logMessage("  - " + f);
        }
        
        long overallStart = System.currentTimeMillis();
        JsonArray scripts = config.getAsJsonArray("scripts");
        int totalScripts = scripts.size();
        int stepCounter = 1;
        
        for (String optionFile : optionFiles) {
            logMessage("\n" + "#".repeat(60));
            logMessage("Processing option file: " + optionFile);
            logMessage("#".repeat(60));
            
            if (!new File(optionFile).exists()) {
                copyFileFromCommon(optionFile);
            }
            
            for (JsonElement scriptElem : scripts) {
                JsonObject scriptInfo = scriptElem.getAsJsonObject();
                String runMode = scriptInfo.has("run_mode") ? 
                    scriptInfo.get("run_mode").getAsString() : "once";
                
                if ("aggregate_once".equals(runMode) || "once".equals(runMode)) {
                    continue;
                }
                
                if ("per_option_file".equals(runMode)) {
                    scriptInfo.addProperty("step", stepCounter);
                    scriptInfo.addProperty("total", totalScripts);
                    
                    if ("Concatanation".equals(scriptInfo.get("name").getAsString())) {
                        scriptInfo.addProperty("cleanup_after", true);
                    }
                    
                    ScriptResult result = runScript(scriptInfo, optionFile);
                    results.add(result);
                    stepCounter++;
                    
                    if (!result.success && scriptInfo.has("required") && 
                        scriptInfo.get("required").getAsBoolean()) {
                        logMessage("Workflow stopped at " + scriptInfo.get("name").getAsString() + 
                            " for " + optionFile, "WARNING");
                        break;
                    }
                }
            }
        }
        
        logMessage("\n" + "#".repeat(60));
        logMessage("Running aggregation and final processing...");
        logMessage("#".repeat(60));
        
        for (JsonElement scriptElem : scripts) {
            JsonObject scriptInfo = scriptElem.getAsJsonObject();
            String runMode = scriptInfo.has("run_mode") ? 
                scriptInfo.get("run_mode").getAsString() : "once";
            
            if ("per_option_file".equals(runMode)) {
                continue;
            }
            
            scriptInfo.addProperty("step", stepCounter);
            scriptInfo.addProperty("total", totalScripts);
            
            ScriptResult result = runScript(scriptInfo, null);
            results.add(result);
            stepCounter++;
            
            if (!result.success && scriptInfo.has("required") && 
                scriptInfo.get("required").getAsBoolean()) {
                logMessage("Workflow stopped at " + scriptInfo.get("name").getAsString(), "WARNING");
                break;
            }
        }
        
        logMessage("\n🧹 Final cleanup: Deleting file3_*.csv files (aggregation complete)...");
        int deletedCount = 0;
        double deletedSize = 0;
        for (String filePath : glob("file3_*.csv")) {
            File file = new File(filePath);
            if (file.exists()) {
                double sizeGB = file.length() / (1024.0 * 1024.0 * 1024.0);
                try {
                    Files.delete(file.toPath());
                    deletedCount++;
                    deletedSize += sizeGB;
                    logMessage("  🗑️ Deleted: " + filePath + String.format(" (%.2f GB)", sizeGB), "INFO");
                } catch (IOException e) {
                    logMessage("  ⚠️ Failed to delete " + filePath + ": " + e.getMessage(), "WARNING");
                }
            }
        }
        if (deletedCount > 0) {
            logMessage(String.format("  ✅ Deleted %d file3_*.csv files (%.2f GB freed)", 
                deletedCount, deletedSize), "INFO");
        }
        
        long overallEnd = System.currentTimeMillis();
        double totalDuration = (overallEnd - overallStart) / 1000.0;
        
        logMessage("\n" + "=".repeat(120));
        logMessage("WORKFLOW EXECUTION SUMMARY");
        logMessage("=".repeat(120));
        
        long successful = results.stream().filter(r -> r.success).count();
        long total = results.size();
        
        logMessage("\n" + "=".repeat(120));
        logMessage("DETAILED SCRIPT STATISTICS");
        logMessage("=".repeat(120));
        
        logMessage(String.format("%-8s %-6s %-30s %-25s %-12s %-12s %-12s", 
            "Status", "Step", "Script Name", "Option File", "Duration", "Input", "Output"));
        logMessage("-".repeat(120));
        
        double totalScriptTime = 0;
        long totalInputRecordsAll = 0;
        long totalOutputRecordsAll = 0;
        
        for (ScriptResult r : results) {
            String status = r.success ? "[OK]" : "[FAIL]";
            double duration = r.duration / 1000.0;
            totalScriptTime += duration;
            
            String durStr;
            if (duration >= 3600) {
                durStr = String.format("%.2fh", duration / 3600);
            } else if (duration >= 60) {
                durStr = String.format("%.2fm", duration / 60);
            } else {
                durStr = String.format("%.2fs", duration);
            }
            
            String optionDisplay = r.optionFile != null ? 
                r.optionFile.substring(0, Math.min(24, r.optionFile.length())) : "N/A";
            
            totalInputRecordsAll += r.totalInputRecords;
            totalOutputRecordsAll += r.outputRecords;
            
            logMessage(String.format("%-8s %-6d %-30s %-25s %-12s %,10d  %,10d", 
                status, r.step, r.scriptName, optionDisplay, durStr, 
                r.totalInputRecords, r.outputRecords));
        }
        
        logMessage("-".repeat(120));
        
        logMessage("\n" + "=".repeat(120));
        logMessage("SUMMARY STATISTICS");
        logMessage("=".repeat(120));
        logMessage("  Option files processed: " + optionFiles.size());
        logMessage(String.format("  [OK] Successful scripts: %d/%d", successful, total));
        logMessage(String.format("  [FAIL] Failed scripts: %d/%d", total - successful, total));
        logMessage(String.format("  Total input records processed: %,d", totalInputRecordsAll));
        logMessage(String.format("  Total output records generated: %,d", totalOutputRecordsAll));
        logMessage(String.format("  Total script execution time: %.2fs (%.2fm)", 
            totalScriptTime, totalScriptTime / 60));
        logMessage(String.format("  Overall workflow time: %.2fs (%.2fm)", 
            totalDuration, totalDuration / 60));
        logMessage(String.format("  Overhead time: %.2fs", totalDuration - totalScriptTime));
        
        if (!results.isEmpty()) {
            long firstInput = results.get(0).totalInputRecords;
            long lastOutput = results.get(results.size() - 1).outputRecords;
            logMessage("\n  Record count change from start to finish:");
            logMessage(String.format("    Initial input records: %,d", firstInput));
            logMessage(String.format("    Final output records: %,d", lastOutput));
            if (lastOutput > firstInput && firstInput > 0) {
                logMessage(String.format("    Net increase: %,d (%.1f%%)", 
                    lastOutput - firstInput, ((double) lastOutput / firstInput - 1) * 100));
            } else if (lastOutput < firstInput && firstInput > 0) {
                logMessage(String.format("    Net decrease: %,d (%.1f%%)", 
                    firstInput - lastOutput, (1 - (double) lastOutput / firstInput) * 100));
            } else {
                logMessage("    No net change");
            }
        }
        
        if (successful == total) {
            logMessage("\n[SUCCESS] WORKFLOW COMPLETED SUCCESSFULLY!");
        } else {
            logMessage("\n[FAIL] WORKFLOW COMPLETED WITH ERRORS");
        }
        
        logMessage("\n" + "=".repeat(120));
        logMessage("FINAL OUTPUT FILES");
        logMessage("=".repeat(120));
        
        Set<String> allOutputs = new HashSet<>();
        for (JsonElement scriptElem : scripts) {
            JsonObject script = scriptElem.getAsJsonObject();
            if (script.has("output")) {
                String outputTemplate = script.get("output").getAsString();
                if (outputTemplate.contains("{option_file_name}")) {
                    for (String optFile : optionFiles) {
                        String optionName = getOptionFileName(optFile);
                        String actualOutput = outputTemplate.replace("{option_file_name}", optionName);
                        if (new File(actualOutput).exists()) {
                            allOutputs.add(actualOutput);
                        }
                    }
                } else if (outputTemplate.contains("*")) {
                    allOutputs.addAll(glob(outputTemplate));
                } else {
                    if (new File(outputTemplate).exists()) {
                        allOutputs.add(outputTemplate);
                    }
                }
            }
        }
        
        for (String outputFile : allOutputs.stream().sorted().toArray(String[]::new)) {
            File file = new File(outputFile);
            if (file.exists()) {
                double sizeMB = file.length() / (1024.0 * 1024.0);
                double sizeGB = file.length() / (1024.0 * 1024.0 * 1024.0);
                String sizeStr = sizeGB >= 1 ? String.format("%.2f GB", sizeGB) : String.format("%.2f MB", sizeMB);
                long rowCount = countRows(outputFile);
                logMessage(String.format("  [OK] %-40s (%s) - %,d records", outputFile, sizeStr, rowCount));
            }
        }
        
        logMessage("=".repeat(120));
        logMessage("\nLog file: " + logFile);
        logMessage("=".repeat(120));
        
        if (logWriter != null) {
            logWriter.close();
        }
        
        return successful == total;
    }
    
    public static void main(String[] args) {
        String configFile = "workflow_config.json";
        if (args.length > 0) {
            configFile = args[0];
        }
        
        if (!new File(configFile).exists()) {
            System.err.println("ERROR: Configuration file '" + configFile + "' not found!");
            System.err.println("Usage: java Custom_CE_workflow [config_file.json]");
            System.exit(1);
        }
        
        Custom_CE_workflow runner = new Custom_CE_workflow(configFile);
        boolean success = runner.runWorkflow();
        System.exit(success ? 0 : 1);
    }
    
    private static class ScriptResult {
        String scriptName;
        String optionFile;
        boolean success;
        double duration;
        Map<String, Long> inputDetails;
        long totalInputRecords;
        long outputRecords;
        int step;
        
        ScriptResult(String scriptName, String optionFile, boolean success, double duration,
                     Map<String, Long> inputDetails, long totalInputRecords, long outputRecords) {
            this.scriptName = scriptName;
            this.optionFile = optionFile;
            this.success = success;
            this.duration = duration;
            this.inputDetails = inputDetails;
            this.totalInputRecords = totalInputRecords;
            this.outputRecords = outputRecords;
            this.step = 0;
        }
    }
}