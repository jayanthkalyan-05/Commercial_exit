// Aggregation.java - EXACT SAME LOGIC AS PYTHON (Optimized with Chunked Export)
package com.engine.workflow;

import org.duckdb.DuckDBConnection;
import java.sql.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

public class Aggregation {
    
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
            
            // Set PRAGMA settings - SAME AS PYTHON (with optimization)
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("PRAGMA memory_limit='24GB'");  // Optimized: 24GB
                stmt.execute("PRAGMA temp_directory='" + TEMP_DIR + "'");
                stmt.execute("PRAGMA max_temp_directory_size='100GB'");
                stmt.execute("PRAGMA threads=12");  // Optimized: 12 threads
                stmt.execute("PRAGMA preserve_insertion_order=false");
                System.out.println("  DuckDB settings applied successfully");
                System.out.println("  Temp directory: " + TEMP_DIR);
                System.out.println("  Memory limit: 24GB");
                System.out.println("  Threads: 12");
            } catch (SQLException e) {
                System.err.println("  Warning: Some PRAGMA settings failed: " + e.getMessage());
            }
            
            long startTime = System.currentTimeMillis();
            
            // Build file list string for SQL
            String fileList = files.stream()
                .map(f -> "'" + f.replace("\\", "/") + "'")
                .collect(Collectors.joining(", "));
            
            System.out.println("\n[2/4] Calculating distinct counts (single pass)...");
            
            // ===== STEP 1: Read data once (EXACTLY LIKE PYTHON) =====
            System.out.println("  Reading data into DuckDB (this is the only read)...");
            
            try (Statement stmt = conn.createStatement()) {
                // SAME SQL AS PYTHON
                stmt.execute("CREATE OR REPLACE TEMP TABLE all_data AS " +
                    "SELECT * FROM read_csv_auto([" + fileList + "])");
                System.out.println("  ✓ Data loaded successfully");
            } catch (SQLException e) {
                System.err.println("  ✗ Failed to load data: " + e.getMessage());
                e.printStackTrace();
                return;
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
            
            // ===== STEP 2: Calculate counts (EXACTLY LIKE PYTHON) =====
            System.out.println("  Calculating distinct counts by configuration ID...");
            long calcStart = System.currentTimeMillis();
            
            try (Statement stmt = conn.createStatement()) {
                // SAME SQL AS PYTHON
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
                return;
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
            
            // ===== STEP 3: Create joined data (EXACTLY LIKE PYTHON) =====
            System.out.println("\n[3/4] Creating joined data...");
            
            try (Statement stmt = conn.createStatement()) {
                // SAME JOIN AS PYTHON - materialized once
                stmt.execute("CREATE OR REPLACE TEMP TABLE export_data AS " +
                    "SELECT " +
                    "cnt.Engine_serial_count, " +
                    "c.option_assembly_num, " +
                    "c.esn_config_design_config_Shop_order_num, " +
                    "c.build_year " +
                    "FROM all_data c " +
                    "INNER JOIN config_counts cnt " +
                    "ON SPLIT_PART(c.esn_config_design_config_Shop_order_num, '_', 1) = cnt.config_id");
                System.out.println("  ✓ Joined data created");
            } catch (SQLException e) {
                System.err.println("  ✗ Failed to create joined data: " + e.getMessage());
                e.printStackTrace();
                return;
            }
            
            // Get export row count
            long exportRows = 0;
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM export_data")) {
                if (rs.next()) {
                    exportRows = rs.getLong(1);
                }
            }
            System.out.println("  Export rows: " + String.format("%,d", exportRows));
            
            // ===== STEP 4: Export to Parquet (OPTIMIZED - Same logic, faster) =====
            System.out.println("\n[4/4] Exporting to Parquet (optimized chunked export)...");
            long exportStart = System.currentTimeMillis();
            
            // Check if we should use chunked export (for large datasets)
            boolean useChunkedExport = totalRows > 100_000_000; // 100M+ rows
            
            if (useChunkedExport) {
                System.out.println("  Large dataset detected. Using chunked export (same logic, faster)...");
                exportChunked(conn);
            } else {
                // Regular export - EXACTLY LIKE PYTHON
                System.out.println("  Using regular export (same as Python)...");
                try (Statement stmt = conn.createStatement()) {
                    String exportSQL = "COPY (" +
                        "SELECT * FROM export_data " +
                        "ORDER BY build_year DESC" +
                        ") TO '" + OUTPUT_FILE + "' (FORMAT PARQUET, COMPRESSION ZSTD)";
                    
                    System.out.println("  Executing export...");
                    stmt.execute(exportSQL);
                    System.out.println("  ✓ Export completed");
                } catch (SQLException e) {
                    System.err.println("  ✗ Export failed: " + e.getMessage());
                    e.printStackTrace();
                    
                    // Retry without compression
                    System.out.println("  Retrying export without compression...");
                    try (Statement stmt = conn.createStatement()) {
                        String exportSQL = "COPY (" +
                            "SELECT * FROM export_data " +
                            "ORDER BY build_year DESC" +
                            ") TO '" + OUTPUT_FILE + "' (FORMAT PARQUET)";
                        stmt.execute(exportSQL);
                        System.out.println("  ✓ Export completed without compression");
                    } catch (SQLException e2) {
                        System.err.println("  ✗ Export also failed: " + e2.getMessage());
                        return;
                    }
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
            
            if (outputSize < 1024) {
                System.err.println("WARNING: Output file is suspiciously small!");
                return;
            }
            
            // Get final statistics (EXACTLY LIKE PYTHON)
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
     * OPTIMIZED: Chunked export by year
     * Maintains EXACT same logic as ORDER BY build_year DESC
     * Much faster for large datasets
     */
    private static void exportChunked(Connection conn) throws SQLException {
        System.out.println("  Exporting by year (same as ORDER BY build_year DESC)...");
        
        // Get distinct years in DESC order (same as ORDER BY build_year DESC)
        List<Integer> years = new ArrayList<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT DISTINCT build_year FROM export_data ORDER BY build_year DESC")) {
            while (rs.next()) {
                years.add(rs.getInt(1));
            }
        }
        
        System.out.println("  Years to export (DESC order): " + years);
        
        List<String> chunkFiles = new ArrayList<>();
        
        // Export each year (already in DESC order)
        for (int year : years) {
            String chunkFile = "chunk_" + year + ".parquet";
            chunkFiles.add(chunkFile);
            
            System.out.println("    Exporting year " + year + "...");
            try (Statement stmt = conn.createStatement()) {
                // Each year export is already sorted (by year)
                String exportSQL = "COPY (" +
                    "SELECT * FROM export_data " +
                    "WHERE build_year = " + year +
                    ") TO '" + chunkFile + "' (FORMAT PARQUET, COMPRESSION ZSTD)";
                stmt.execute(exportSQL);
                System.out.println("      ✓ Year " + year + " exported");
            } catch (SQLException e) {
                System.err.println("      ✗ Failed to export year " + year + ": " + e.getMessage());
                // Try without compression
                try (Statement stmt = conn.createStatement()) {
                    String exportSQL = "COPY (" +
                        "SELECT * FROM export_data " +
                        "WHERE build_year = " + year +
                        ") TO '" + chunkFile + "' (FORMAT PARQUET)";
                    stmt.execute(exportSQL);
                    System.out.println("      ✓ Year " + year + " exported (no compression)");
                } catch (SQLException e2) {
                    System.err.println("      ✗ Failed to export year " + year + " even without compression");
                }
            }
        }
        
        // Combine all chunks into final file (preserves ORDER BY build_year DESC)
        System.out.println("  Combining chunks (same as ORDER BY build_year DESC)...");
        try (Statement stmt = conn.createStatement()) {
            String chunkList = chunkFiles.stream()
                .map(f -> "'" + f + "'")
                .collect(Collectors.joining(", "));
            
            // Read chunks in order (already DESC by year)
            String combineSQL = "COPY (" +
                "SELECT * FROM read_parquet([" + chunkList + "])" +
                ") TO '" + OUTPUT_FILE + "' (FORMAT PARQUET, COMPRESSION ZSTD)";
            stmt.execute(combineSQL);
            System.out.println("  ✓ Chunks combined successfully");
        }
        
        // Clean up chunk files
        System.out.println("  Cleaning up temporary chunks...");
        for (String chunkFile : chunkFiles) {
            File f = new File(chunkFile);
            if (f.exists() && !f.delete()) {
                System.err.println("  Warning: Could not delete " + chunkFile);
            }
        }
        System.out.println("  ✓ Cleanup complete");
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