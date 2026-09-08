// UnifiedWorkflow.java - Orchestrator that calls DataPreprocessingWorkflow and MainWorkflow
package com.engine.workflow;

import java.io.*;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.lang.reflect.InvocationTargetException;

public class UnifiedWorkflow {
    
    private static final DateTimeFormatter DATE_FORMAT = 
        DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    private static final String LOG_FOLDER = "logs";
    
    private String logFile;
    private PrintWriter logWriter;

    public static void main(String[] args) {
        String configFile = "workflow_config.json";
        if (args.length > 0) {
            configFile = args[0];
        }

        UnifiedWorkflow workflow = new UnifiedWorkflow();
        boolean success = workflow.run(configFile);
        System.exit(success ? 0 : 1);
    }
    
    public UnifiedWorkflow() {
        this.logFile = LOG_FOLDER + "/unified_workflow_" + 
            LocalDateTime.now().format(DATE_FORMAT) + ".log";
        createDirectories();
    }
    
    private void createDirectories() {
        try {
            Files.createDirectories(Paths.get(LOG_FOLDER));
        } catch (IOException e) {
            System.err.println("Error creating directories: " + e.getMessage());
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
    
    public boolean run(String configFile) {
        long overallStart = System.currentTimeMillis();
        
        try {
            logMessage("Starting Unified Workflow...", "INFO");
            logMessage("Config file: " + configFile, "INFO");
            logMessage("Log file: " + logFile, "INFO");
            logMessage("========================================", "INFO");

            // 1. Run DataPreprocessingWorkflow via Reflection
            logMessage("PHASE 1: Running DataPreprocessingWorkflow...", "INFO");
            if (!runDataPreprocessing()) {
                return false;
            }
            logMessage("✅ Preprocessing completed successfully.", "INFO");
            logMessage("========================================", "INFO");

            // 2. Run MainWorkflow directly (Maven provides the full classpath)
            logMessage("PHASE 2: Running MainWorkflow...", "INFO");
            if (!runMainWorkflow(configFile)) {
                return false;
            }
            logMessage("✅ Main workflow completed successfully.", "INFO");

            return true;

        } catch (Exception e) {
            logMessage("❌ FATAL ERROR: " + e.getMessage(), "ERROR");
            e.printStackTrace();
            return false;
        } finally {
            long totalTime = (System.currentTimeMillis() - overallStart) / 1000;
            logMessage("Unified Workflow finished. Total time: " + formatDuration(totalTime), "INFO");
            if (logWriter != null) {
                logWriter.close();
            }
        }
    }
    
    // Reflection call for DataPreprocessingWorkflow
    private boolean runDataPreprocessing() {
        PrintStream oldOut = System.out;
        try {
            Class<?> clazz = Class.forName("DataPreprocessingWorkflow");
            java.lang.reflect.Method mainMethod = clazz.getMethod("main", String[].class);
            
            try {
                mainMethod.invoke(null, (Object) new String[0]);
            } catch (InvocationTargetException e) {
                Throwable cause = e.getCause();
                if (cause != null && cause.getMessage() != null && cause.getMessage().contains("exit")) {
                    logMessage("❌ Preprocessing triggered an error exit code!", "ERROR");
                    return false;
                }
                logMessage("Note: Preprocessing invoked System.exit (normal termination).", "INFO");
            }
            
            return true;
        } catch (Exception e) {
            logMessage("❌ Error invoking DataPreprocessingWorkflow: " + e.getMessage(), "ERROR");
            return false;
        } finally {
            System.setOut(oldOut);
        }
    }
    
    // Direct call to MainWorkflow
    private boolean runMainWorkflow(String configFile) {
        PrintStream oldOut = System.out;
        try {
            // Call MainWorkflow directly
            MainWorkflow.main(new String[]{configFile});
            return true;
        } catch (Exception e) {
            logMessage("❌ Error running MainWorkflow: " + e.getMessage(), "ERROR");
            return false;
        } finally {
            System.setOut(oldOut);
        }
    }
    
    private String formatDuration(long seconds) {
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long secs = seconds % 60;
        
        if (hours > 0) {
            return String.format("%dh %dm %ds", hours, minutes, secs);
        } else if (minutes > 0) {
            return String.format("%dm %ds", minutes, secs);
        } else {
            return String.format("%ds", secs);
        }
    }
}