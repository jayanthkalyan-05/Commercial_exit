// Aggregation.java - COMPLETE FIXED VERSION (Same temp location as Python)
package com.engine.workflow;

import org.duckdb.DuckDBConnection;
import java.sql.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Optimized aggregation - reads data once, writes to Parquet.
 * Groups by configuration ID (left part before underscore) for counting.
 * Keeps pipeline modular (doesn't merge with other scripts).
 * 
 * FIXES APPLIED:
 * 1. Added column validation before processing
 * 2. Added detailed logging for debugging
 * 3. Fixed potential column name case sensitivity issues
 * 4. Added fallback for different column naming conventions
 * 5. Added proper connection closing and resource management
 * 6. Fixed temp directory cleanup
 * 7. FIXED: Same temp directory as Python (./duckdb_temp)
 * 8. ADDED: Free space check and reporting
 * 9. REMOVED: external_sorting (not supported in this version)
 */
public class Aggregation {
    
    // SAME AS PYTHON: Use relative path ./duckdb_temp
    private static final String TEMP_DIR = "./duckdb_temp";
    private static final String OUTPUT_FILE = "file4_aggregated.parquet";
    
    public static void main(String[] args) {
        try {
            aggregateUE1Outputs();
        } catch (Exception e) {
            System.err.println("FATAL ERROR: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
    
    public static void aggregateUE1Outputs() {
        Connection conn = null;
        try {
            // Find all file3_*.csv files
            List<String> files = findMatchingFiles("file3_*.csv");
            
            if (files.isEmpty()) {
                System.out.println("No file3_*.csv files found!");
                return;
            }
            
            System.out.println("Found " + files.size() + " files to process");
            
            // Print file list for debugging
            System.out.println("\nFiles to process:");
            long totalSize = 0;
            for (String f : files) {
                File file = new File(f);
                long size = file.length();
                totalSize += size;
                System.out.printf("  - %s (%.2f MB)%n", f, size / (1024.0 * 1024.0));
            }
            
            double totalSizeGB = totalSize / (1024.0 * 1024.0 * 1024.0);
            System.out.printf("\nTotal input size: %.2f GB%n", totalSizeGB);
            
            // Create temp directory (same as Python)
            File tempDir = new File(TEMP_DIR);
            if (!tempDir.exists()) {
                if (!tempDir.mkdirs()) {
                    System.err.println("WARNING: Could not create temp directory: " + TEMP_DIR);
                } else {
                    System.out.println("  Created temp directory: " + TEMP_DIR);
                }
            }
            
            // Check available disk space in temp directory
            if (tempDir.exists()) {
                long freeSpace = tempDir.getFreeSpace();
                double freeSpaceGB = freeSpace / (1024.0 * 1024.0 * 1024.0);
                System.out.printf("Available space on temp drive: %.2f GB%n", freeSpaceGB);
                if (freeSpaceGB < 20) {
                    System.err.printf("WARNING: Only %.2f GB free space available. May not be enough for processing.%n", freeSpaceGB);
                }
            }
            
            System.out.println("\n[1/4] Setting up DuckDB...");
            
            // Connect to DuckDB with memory limit
            conn = DriverManager.getConnection("jdbc:duckdb:");
            
            // Set PRAGMA settings - SAME AS PYTHON
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("PRAGMA memory_limit='16GB'");
                stmt.execute("PRAGMA temp_directory='" + TEMP_DIR + "'");
                stmt.execute("PRAGMA max_temp_directory_size='100GB'");
                stmt.execute("PRAGMA threads=8");
                stmt.execute("PRAGMA preserve_insertion_order=false");
                System.out.println("  DuckDB settings applied successfully");
                System.out.println("  Temp directory: " + TEMP_DIR);
                System.out.println("  Memory limit: 16GB");
                System.out.println("  Threads: 8");
            } catch (SQLException e) {
                System.err.println("  Warning: Some PRAGMA settings failed: " + e.getMessage());
                // Continue anyway - these are optimizations, not critical
            }
            
            long startTime = System.currentTimeMillis();
            
            // Build file list string for SQL
            String fileList = files.stream()
                .map(f -> "'" + f.replace("\\", "/") + "'")  // Fix Windows paths
                .collect(Collectors.joining(", "));
            
            System.out.println("\n[2/4] Validating CSV columns...");
            
            // FIRST: Check what columns exist in the CSV files
            Map<String, String> columnMapping = detectColumnMapping(conn, fileList);
            if (columnMapping.isEmpty()) {
                System.err.println("ERROR: Could not detect required columns in CSV files!");
                System.err.println("Please check that the CSV files have the expected columns.");
                return;
            }
            
            System.out.println("\n  Detected column mapping:");
            for (Map.Entry<String, String> entry : columnMapping.entrySet()) {
                System.out.println("    " + entry.getKey() + " -> " + entry.getValue());
            }
            
            System.out.println("\n[3/4] Calculating distinct counts (single pass)...");
            
            // Create a materialized table once with proper column names
            System.out.println("  Reading data into DuckDB (this is the only read)...");
            
            // Build SELECT with proper column aliases
            String selectColumns = buildSelectColumns(columnMapping);
            String createTableSQL = "CREATE OR REPLACE TEMP TABLE all_data AS " +
                "SELECT " + selectColumns + " FROM read_csv_auto([" + fileList + "])";
            
            System.out.println("  SQL: " + createTableSQL);
            
            try (Statement stmt = conn.createStatement()) {
                stmt.execute(createTableSQL);
                System.out.println("  ✓ Data loaded successfully");
            } catch (SQLException e) {
                System.err.println("  ✗ Failed to load data: " + e.getMessage());
                
                // Try fallback: load without column mapping
                System.out.println("  Attempting fallback: loading raw data...");
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("CREATE OR REPLACE TEMP TABLE all_data_raw AS SELECT * FROM read_csv_auto([" + fileList + "])");
                    System.out.println("  ✓ Raw data loaded");
                    
                    // Try to detect columns from raw table
                    Map<String, String> fallbackMapping = detectColumnsFromTable(conn, "all_data_raw");
                    if (!fallbackMapping.isEmpty()) {
                        System.out.println("  Detected columns from raw data:");
                        for (Map.Entry<String, String> entry : fallbackMapping.entrySet()) {
                            System.out.println("    " + entry.getKey() + " -> " + entry.getValue());
                        }
                        // Create all_data with proper column names
                        String fallbackSelect = buildSelectColumns(fallbackMapping);
                        try (Statement stmt2 = conn.createStatement()) {
                            stmt2.execute("CREATE OR REPLACE TEMP TABLE all_data AS SELECT " + 
                                fallbackSelect + " FROM all_data_raw");
                            System.out.println("  ✓ Created all_data with proper column names");
                        }
                    } else {
                        System.err.println("  ✗ Could not map columns from raw data");
                        return;
                    }
                } catch (SQLException e2) {
                    System.err.println("  ✗ Fallback also failed: " + e2.getMessage());
                    e2.printStackTrace();
                    return;
                }
            }
            
            // Verify data loaded
            long totalRows = 0;
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM all_data")) {
                if (rs.next()) {
                    totalRows = rs.getLong(1);
                }
            }
            System.out.println("  Total rows loaded: " + String.format("%,d", totalRows));
            
            if (totalRows == 0) {
                System.err.println("ERROR: No data loaded into all_data table!");
                return;
            }
            
            // Calculate counts from the materialized table
            System.out.println("  Calculating distinct counts by configuration ID...");
            long calcStart = System.currentTimeMillis();
            
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("CREATE OR REPLACE TEMP TABLE config_counts AS " +
                    "SELECT " +
                    "SPLIT_PART(esn_config_design_config_Shop_order_num, '_', 1) AS config_id, " +
                    "COUNT(DISTINCT Engine_serial) AS Engine_serial_count " +
                    "FROM all_data " +
                    "GROUP BY SPLIT_PART(esn_config_design_config_Shop_order_num, '_', 1)");
                System.out.println("  ✓ Config counts calculated");
            } catch (SQLException e) {
                System.err.println("  ✗ Failed to calculate config counts: " + e.getMessage());
                e.printStackTrace();
                
                // Try simplified aggregation
                System.out.println("  Attempting simplified aggregation...");
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("CREATE OR REPLACE TEMP TABLE config_counts AS " +
                        "SELECT " +
                        "esn_config_design_config_Shop_order_num AS config_id, " +
                        "COUNT(DISTINCT Engine_serial) AS Engine_serial_count " +
                        "FROM all_data " +
                        "GROUP BY esn_config_design_config_Shop_order_num");
                    System.out.println("  ✓ Simplified aggregation succeeded");
                } catch (SQLException e2) {
                    System.err.println("  ✗ Simplified aggregation also failed: " + e2.getMessage());
                    return;
                }
            }
            
            // Get config count
            int configCount = 0;
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM config_counts")) {
                if (rs.next()) {
                    configCount = rs.getInt(1);
                }
            }
            
            long calcTime = System.currentTimeMillis() - calcStart;
            System.out.printf("  Unique configurations: %,d (%.2fs)%n", configCount, calcTime / 1000.0);
            
            // Export to Parquet - SAME AS PYTHON
            System.out.println("\n[4/4] Exporting to Parquet...");
            long exportStart = System.currentTimeMillis();
            
            try (Statement stmt = conn.createStatement()) {
                String exportSQL = "COPY (" +
                    "SELECT " +
                    "cnt.Engine_serial_count, " +
                    "c.option_assembly_num, " +
                    "c.esn_config_design_config_Shop_order_num, " +
                    "c.build_year " +
                    "FROM all_data c " +
                    "INNER JOIN config_counts cnt " +
                    "ON SPLIT_PART(c.esn_config_design_config_Shop_order_num, '_', 1) = cnt.config_id " +
                    "ORDER BY c.build_year DESC" +
                    ") TO '" + OUTPUT_FILE + "' (FORMAT PARQUET, COMPRESSION ZSTD)";
                
                System.out.println("  Executing export...");
                System.out.println("  This may take 10-15 minutes...");
                stmt.execute(exportSQL);
                System.out.println("  ✓ Export completed");
            } catch (SQLException e) {
                System.err.println("  ✗ Export failed: " + e.getMessage());
                e.printStackTrace();
                
                // Try without compression (less temp space)
                System.out.println("  Retrying export without compression...");
                try (Statement stmt = conn.createStatement()) {
                    String exportSQL = "COPY (" +
                        "SELECT " +
                        "cnt.Engine_serial_count, " +
                        "c.option_assembly_num, " +
                        "c.esn_config_design_config_Shop_order_num, " +
                        "c.build_year " +
                        "FROM all_data c " +
                        "INNER JOIN config_counts cnt " +
                        "ON SPLIT_PART(c.esn_config_design_config_Shop_order_num, '_', 1) = cnt.config_id " +
                        "ORDER BY c.build_year DESC" +
                        ") TO '" + OUTPUT_FILE + "' (FORMAT PARQUET)";  // No compression
                    
                    System.out.println("  Executing export without compression...");
                    stmt.execute(exportSQL);
                    System.out.println("  ✓ Export completed without compression");
                } catch (SQLException e2) {
                    System.err.println("  ✗ Export also failed without compression: " + e2.getMessage());
                    e2.printStackTrace();
                    System.err.println("\nFATAL: Cannot export data. Please check disk space and permissions.");
                    return;
                }
            }
            
            long exportTime = System.currentTimeMillis() - exportStart;
            System.out.printf("  Export time: %.2fs%n", exportTime / 1000.0);
            
            // Verify output file
            File outputFile = new File(OUTPUT_FILE);
            if (!outputFile.exists()) {
                System.err.println("ERROR: Output file was not created!");
                return;
            }
            long outputSize = outputFile.length();
            System.out.printf("  Output file size: %.2f MB%n", outputSize / (1024.0 * 1024.0));
            
            if (outputSize < 1024) { // Less than 1KB - probably empty
                System.err.println("WARNING: Output file is suspiciously small! Export may have failed silently.");
                return;
            }
            
            // Get final statistics
            long totalDistinctEngines = 0;
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT COALESCE(SUM(Engine_serial_count), 0) FROM config_counts")) {
                if (rs.next()) {
                    totalDistinctEngines = rs.getLong(1);
                }
            }
            
            long totalTime = System.currentTimeMillis() - startTime;
            
            System.out.println("\n" + "=".repeat(60));
            System.out.println("[SUCCESS] Aggregation complete!");
            System.out.println("=".repeat(60));
            System.out.println("Output saved to: " + OUTPUT_FILE);
            System.out.printf("Total rows processed: %,d%n", totalRows);
            System.out.printf("Unique configurations: %,d%n", configCount);
            System.out.printf("Total distinct Engine_serial count: %,d%n", totalDistinctEngines);
            System.out.printf("Total time: %.2fs (%.2f minutes)%n", totalTime / 1000.0, totalTime / 60000.0);
            System.out.println("=".repeat(60));
            
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
        } finally {
            // Clean up
            try {
                if (conn != null && !conn.isClosed()) {
                    conn.close();
                }
            } catch (SQLException e) {
                System.err.println("Error closing connection: " + e.getMessage());
            }
            
            // Clean up temp directory - SAME AS PYTHON
            deleteDirectory(new File(TEMP_DIR));
        }
    }
    
    /**
     * Detect column mapping from CSV files
     */
    private static Map<String, String> detectColumnMapping(Connection conn, String fileList) {
        Map<String, String> mapping = new HashMap<>();
        
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM read_csv_auto([" + fileList + "]) LIMIT 1")) {
            
            ResultSetMetaData meta = rs.getMetaData();
            int columnCount = meta.getColumnCount();
            
            System.out.println("\n  Detected columns in CSV:");
            for (int i = 1; i <= columnCount; i++) {
                String colName = meta.getColumnName(i);
                String colType = meta.getColumnTypeName(i);
                System.out.println("    Column " + i + ": " + colName + " (" + colType + ")");
            }
            
            // Map expected columns to actual column names (case insensitive)
            String[] expectedColumns = {
                "Engine_serial", 
                "option_assembly_num", 
                "esn_config_design_config_Shop_order_num",
                "build_year"
            };
            
            // Get all column names from metadata (case insensitive)
            List<String> actualColumns = new ArrayList<>();
            for (int i = 1; i <= columnCount; i++) {
                actualColumns.add(meta.getColumnName(i));
            }
            
            for (String expected : expectedColumns) {
                String found = null;
                // Try exact match first
                if (actualColumns.contains(expected)) {
                    found = expected;
                } else {
                    // Try case insensitive match
                    for (String actual : actualColumns) {
                        if (actual.equalsIgnoreCase(expected)) {
                            found = actual;
                            break;
                        }
                    }
                }
                
                if (found != null) {
                    mapping.put(expected, found);
                    System.out.println("    Mapped " + expected + " -> " + found);
                } else {
                    System.err.println("    WARNING: Column '" + expected + "' not found in CSV!");
                }
            }
            
        } catch (SQLException e) {
            System.err.println("Error detecting columns: " + e.getMessage());
        }
        
        return mapping;
    }
    
    /**
     * Detect columns from an existing table
     */
    private static Map<String, String> detectColumnsFromTable(Connection conn, String tableName) {
        Map<String, String> mapping = new HashMap<>();
        
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM " + tableName + " LIMIT 1")) {
            
            ResultSetMetaData meta = rs.getMetaData();
            int columnCount = meta.getColumnCount();
            
            String[] expectedColumns = {
                "Engine_serial", 
                "option_assembly_num", 
                "esn_config_design_config_Shop_order_num",
                "build_year"
            };
            
            List<String> actualColumns = new ArrayList<>();
            for (int i = 1; i <= columnCount; i++) {
                actualColumns.add(meta.getColumnName(i));
            }
            
            for (String expected : expectedColumns) {
                String found = null;
                if (actualColumns.contains(expected)) {
                    found = expected;
                } else {
                    for (String actual : actualColumns) {
                        if (actual.equalsIgnoreCase(expected)) {
                            found = actual;
                            break;
                        }
                    }
                }
                
                if (found != null) {
                    mapping.put(expected, found);
                }
            }
            
        } catch (SQLException e) {
            System.err.println("Error detecting columns from table: " + e.getMessage());
        }
        
        return mapping;
    }
    
    /**
     * Build SELECT clause with proper column aliases
     */
    private static String buildSelectColumns(Map<String, String> mapping) {
        List<String> selects = new ArrayList<>();
        for (Map.Entry<String, String> entry : mapping.entrySet()) {
            selects.add(entry.getValue() + " AS " + entry.getKey());
        }
        return String.join(", ", selects);
    }
    
    /**
     * Find files matching a pattern
     */
    private static List<String> findMatchingFiles(String pattern) {
        List<String> files = new ArrayList<>();
        try {
            Path dirPath = Paths.get(".");
            PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
            Files.walk(dirPath, 1)
                .filter(Files::isRegularFile)
                .filter(p -> matcher.matches(p.getFileName()))
                .forEach(p -> files.add(p.toString()));
        } catch (IOException e) {
            System.err.println("Error finding files: " + e.getMessage());
        }
        
        // Sort files to ensure consistent processing order
        Collections.sort(files);
        return files;
    }
    
    /**
     * Delete directory recursively
     */
    private static void deleteDirectory(File dir) {
        if (dir.exists()) {
            File[] files = dir.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isDirectory()) {
                        deleteDirectory(file);
                    } else {
                        if (!file.delete()) {
                            System.err.println("Warning: Could not delete " + file.getPath());
                        }
                    }
                }
            }
            if (!dir.delete()) {
                System.err.println("Warning: Could not delete directory " + dir.getPath());
            }
        }
    }
}