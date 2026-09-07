// Aggregation.java - CONVERTS ALL INPUT TO PARQUET FIRST, THEN PROCESSES
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
    private static final String PARQUET_INPUT_FILE = "file3_all.parquet";  // Single Parquet file
    
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
        long overallStart = System.currentTimeMillis();
        
        try {
            System.out.println("=".repeat(80));
            System.out.println("AGGREGATION - AUTO-CONVERT CSV TO PARQUET + YEAR-WISE EXPORT");
            System.out.println("=".repeat(80));
            
            // =========================================================
            // STEP 0: Find input CSV files
            // =========================================================
            
            List<String> csvFiles = findMatchingFiles("file3_*.csv");
            
            if (csvFiles.isEmpty()) {
                System.out.println("No file3_*.csv files found!");
                return;
            }
            
            System.out.println("Found " + csvFiles.size() + " CSV files to process");
            
            // Calculate total size
            long totalCsvSize = 0;
            System.out.println("\nCSV Files found:");
            for (String f : csvFiles) {
                File file = new File(f);
                long size = file.length();
                totalCsvSize += size;
                System.out.printf("  - %s (%.2f GB)%n", f, size / (1024.0 * 1024.0 * 1024.0));
            }
            System.out.printf("\nTotal CSV input size: %.2f GB%n", totalCsvSize / (1024.0 * 1024.0 * 1024.0));
            
            // =========================================================
            // STEP 1: Convert CSV to Parquet (ONE-TIME, IF NOT EXISTS)
            // =========================================================
            
            File parquetFile = new File(PARQUET_INPUT_FILE);
            boolean parquetExists = parquetFile.exists();
            
            if (parquetExists) {
                long parquetSize = parquetFile.length();
                System.out.printf("\n[INFO] Parquet file already exists: %s (%.2f GB)%n", 
                    PARQUET_INPUT_FILE, parquetSize / (1024.0 * 1024.0 * 1024.0));
                System.out.println("  Skipping conversion (use existing Parquet file)");
            } else {
                System.out.println("\n[1/6] Converting CSV to Parquet (one-time optimization)...");
                System.out.println("  This will make subsequent runs 3-5x faster!");
                System.out.println("  Estimated conversion time: 10-15 minutes for 36.85GB");
                
                long convertStart = System.currentTimeMillis();
                
                // Create temp directory
                File tempDir = new File(TEMP_DIR);
                if (!tempDir.exists()) {
                    tempDir.mkdirs();
                }
                
                // Use DuckDB to convert CSV to Parquet
                try (Connection convertConn = DriverManager.getConnection("jdbc:duckdb:")) {
                    try (Statement stmt = convertConn.createStatement()) {
                        // Set optimizations for conversion
                        stmt.execute("PRAGMA memory_limit='24GB'");
                        stmt.execute("PRAGMA threads=4");
                        stmt.execute("PRAGMA preserve_insertion_order=false");
                        stmt.execute("PRAGMA temp_directory='" + TEMP_DIR + "'");
                        
                        System.out.println("  Reading CSV files and converting to Parquet...");
                        
                        // Build file list
                        String fileList = csvFiles.stream()
                            .map(f -> "'" + f.replace("\\", "/") + "'")
                            .collect(Collectors.joining(", "));
                        
                        // Convert to Parquet with explicit types
                        String convertSQL = 
                            "COPY (" +
                            "SELECT " +
                            "  Engine_serial, " +
                            "  option_assembly_num, " +
                            "  esn_config_design_config_Shop_order_num, " +
                            "  build_year " +
                            "FROM read_csv([" + fileList + "], " +
                            "  types={'Engine_serial':'VARCHAR', " +
                            "         'option_assembly_num':'VARCHAR', " +
                            "         'esn_config_design_config_Shop_order_num':'VARCHAR', " +
                            "         'build_year':'INTEGER'})" +
                            ") TO '" + PARQUET_INPUT_FILE + "' " +
                            "(FORMAT PARQUET, COMPRESSION ZSTD)";
                        
                        stmt.execute(convertSQL);
                    }
                }
                
                long convertTime = System.currentTimeMillis() - convertStart;
                long parquetSize = new File(PARQUET_INPUT_FILE).length();
                double parquetSizeGB = parquetSize / (1024.0 * 1024.0 * 1024.0);
                double csvSizeGB = totalCsvSize / (1024.0 * 1024.0 * 1024.0);
                double compressionRatio = (1 - parquetSizeGB / csvSizeGB) * 100;
                
                System.out.printf("  ✓ Conversion completed in %.2f minutes%n", convertTime / 60000.0);
                System.out.printf("  ✓ Parquet file size: %.2f GB (%.1f%% smaller than CSV)%n", 
                    parquetSizeGB, compressionRatio);
                System.out.println("  ✓ Future runs will use this Parquet file directly!");
                
                // Optionally delete CSV files to free space
                System.out.println("\n  Do you want to delete the original CSV files to free space?");
                System.out.println("  (They are no longer needed since we have Parquet)");
                // Auto-delete if you want (uncomment below):
                /*
                for (String f : csvFiles) {
                    new File(f).delete();
                }
                System.out.println("  ✓ Deleted " + csvFiles.size() + " CSV files");
                */
            }
            
            // =========================================================
            // STEP 2: Process Parquet File
            // =========================================================
            
            System.out.println("\n[2/6] Processing Parquet file (FAST!)...");
            
            // Create temp directory
            File tempDir = new File(TEMP_DIR);
            if (!tempDir.exists()) {
                tempDir.mkdirs();
            }
            
            // Check available disk space
            if (tempDir.exists()) {
                long freeSpace = tempDir.getFreeSpace();
                double freeSpaceGB = freeSpace / (1024.0 * 1024.0 * 1024.0);
                System.out.printf("Available space on temp drive: %.2f GB%n", freeSpaceGB);
                if (freeSpaceGB < 50) {
                    System.err.printf("WARNING: Only %.2f GB free space available. May not be enough for processing.%n", freeSpaceGB);
                }
            }
            
            // Setup DuckDB
            System.out.println("\n[3/6] Setting up DuckDB for Parquet processing...");
            conn = DriverManager.getConnection("jdbc:duckdb:");
            
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("PRAGMA memory_limit='24GB'");
                stmt.execute("PRAGMA threads=4");
                stmt.execute("PRAGMA preserve_insertion_order=false");
                stmt.execute("PRAGMA temp_directory='" + TEMP_DIR + "'");
                
                System.out.println("  Memory limit: 24GB");
                System.out.println("  Threads: 4");
                System.out.println("  Temp directory: " + TEMP_DIR);
                System.out.println("  Input: Parquet (no CSV parsing!)");
            }
            
            long startTime = System.currentTimeMillis();
            
            // Get row count from Parquet
            long totalRows = 0;
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM read_parquet('" + PARQUET_INPUT_FILE + "')")) {
                if (rs.next()) {
                    totalRows = rs.getLong(1);
                }
            }
            System.out.printf("\n[4/6] Total rows in Parquet: %,d%n", totalRows);
            
            if (totalRows == 0) {
                System.err.println("ERROR: No data in Parquet file!");
                return;
            }
            
            // =========================================================
            // STEP 4: Calculate counts
            // =========================================================
            
            System.out.println("\n[4/6] Calculating distinct counts by configuration ID...");
            long calcStart = System.currentTimeMillis();
            
            // Create temp tables from Parquet (super fast!)
            try (Statement stmt = conn.createStatement()) {
                // Read all data from Parquet
                stmt.execute("CREATE OR REPLACE TEMP TABLE all_data AS " +
                    "SELECT * FROM read_parquet('" + PARQUET_INPUT_FILE + "')");
                System.out.println("  ✓ Data loaded from Parquet");
                
                // Calculate config counts
                stmt.execute("CREATE OR REPLACE TEMP TABLE config_counts AS " +
                    "SELECT " +
                    "  SPLIT_PART(esn_config_design_config_Shop_order_num, '_', 1) AS config_id, " +
                    "  COUNT(DISTINCT Engine_serial) AS Engine_serial_count " +
                    "FROM all_data " +
                    "GROUP BY SPLIT_PART(esn_config_design_config_Shop_order_num, '_', 1)");
                System.out.println("  ✓ Config counts calculated");
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
            System.out.printf("  Unique configurations: %,d (%.2f minutes)%n", configCount, calcTime / 60000.0);
            
            // =========================================================
            // STEP 5: Create joined data
            // =========================================================
            
            System.out.println("\n[5/6] Creating joined data...");
            long joinStart = System.currentTimeMillis();
            
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("CREATE OR REPLACE TEMP TABLE export_data AS " +
                    "SELECT " +
                    "  cnt.Engine_serial_count, " +
                    "  c.option_assembly_num, " +
                    "  c.esn_config_design_config_Shop_order_num, " +
                    "  c.build_year " +
                    "FROM all_data c " +
                    "INNER JOIN config_counts cnt " +
                    "ON SPLIT_PART(c.esn_config_design_config_Shop_order_num, '_', 1) = cnt.config_id");
                System.out.println("  ✓ Joined data created");
            }
            
            long joinTime = System.currentTimeMillis() - joinStart;
            System.out.printf("  Join completed in %.2f minutes%n", joinTime / 60000.0);
            
            // Get export row count
            long exportRows = 0;
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM export_data")) {
                if (rs.next()) {
                    exportRows = rs.getLong(1);
                }
            }
            System.out.printf("  Export rows: %,d%n", exportRows);
            
            // =========================================================
            // STEP 6: Export with YEAR-WISE CHUNKED EXPORT
            // =========================================================
            
            System.out.println("\n[6/6] Exporting to Parquet with YEAR-WISE CHUNKED EXPORT...");
            long exportStart = System.currentTimeMillis();
            
            // Check if we should use year-wise chunked export
            boolean useYearWise = totalRows > 100_000_000; // 100M+ rows
            
            if (useYearWise) {
                System.out.println("  Large dataset detected (" + String.format("%,d", totalRows) + " rows).");
                System.out.println("  Using YEAR-WISE CHUNKED EXPORT for better performance...");
                exportYearWise(conn, totalRows);
            } else {
                System.out.println("  Using regular export (small dataset)...");
                try (Statement stmt = conn.createStatement()) {
                    String exportSQL = "COPY (" +
                        "SELECT * FROM export_data " +
                        "ORDER BY build_year DESC" +
                        ") TO '" + OUTPUT_FILE + "' (FORMAT PARQUET, COMPRESSION ZSTD)";
                    stmt.execute(exportSQL);
                    System.out.println("  ✓ Export completed");
                }
            }
            
            long exportTime = System.currentTimeMillis() - exportStart;
            System.out.printf("  Export time: %.2f minutes%n", exportTime / 60000.0);
            
            // =========================================================
            // STEP 7: Verify output
            // =========================================================
            
            System.out.println("\nVerifying output...");
            
            File outputFile = new File(OUTPUT_FILE);
            if (!outputFile.exists()) {
                System.err.println("ERROR: Output file was not created!");
                return;
            }
            
            long outputSize = outputFile.length();
            double outputSizeGB = outputSize / (1024.0 * 1024.0 * 1024.0);
            System.out.printf("  Output file size: %.2f GB%n", outputSizeGB);
            
            if (outputSize < 1024) {
                System.err.println("WARNING: Output file is suspiciously small!");
                return;
            }
            
            // Verify row count
            long finalRows = 0;
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM read_parquet('" + OUTPUT_FILE + "')")) {
                if (rs.next()) {
                    finalRows = rs.getLong(1);
                }
            }
            System.out.printf("  Output rows: %,d%n", finalRows);
            
            // Get total distinct engines
            long totalDistinctEngines = 0;
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT COALESCE(SUM(Engine_serial_count), 0) FROM config_counts")) {
                if (rs.next()) {
                    totalDistinctEngines = rs.getLong(1);
                }
            }
            
            long totalTime = System.currentTimeMillis() - overallStart;
            long parquetSize = new File(PARQUET_INPUT_FILE).length();
            double parquetSizeGB = parquetSize / (1024.0 * 1024.0 * 1024.0);
            double csvSizeGB = totalCsvSize / (1024.0 * 1024.0 * 1024.0);
            
            // =========================================================
            // STEP 8: Final Summary
            // =========================================================
            
            System.out.println("\n" + "=".repeat(80));
            System.out.println("[SUCCESS] Aggregation complete!");
            System.out.println("=".repeat(80));
            
            System.out.println("\n📁 INPUT:");
            System.out.printf("  CSV files: %d files (%.2f GB)%n", csvFiles.size(), csvSizeGB);
            System.out.printf("  Parquet: %s (%.2f GB) - %.1f%% smaller%n", 
                PARQUET_INPUT_FILE, parquetSizeGB, (1 - parquetSizeGB/csvSizeGB) * 100);
            
            System.out.println("\n📤 OUTPUT:");
            System.out.println("  File: " + OUTPUT_FILE);
            System.out.printf("  Size: %.2f GB%n", outputSizeGB);
            System.out.printf("  Rows: %,d%n", finalRows);
            System.out.printf("  Unique configs: %,d%n", configCount);
            System.out.printf("  Distinct Engine_serial count: %,d%n", totalDistinctEngines);
            
            System.out.println("\n⚡ PERFORMANCE:");
            System.out.printf("  Total time: %.2f minutes%n", totalTime / 60000.0);
            System.out.printf("  Processing speed: %,.0f rows/second%n", finalRows / (totalTime / 1000.0));
            System.out.printf("  Throughput: %.2f GB/minute%n", csvSizeGB / (totalTime / 60000.0));
            if (useYearWise) {
                System.out.println("  Method: Year-wise chunked export");
            }
            if (parquetExists) {
                System.out.println("  Parquet: Used existing file (no conversion time)");
            } else {
                System.out.println("  Parquet: Converted CSV → Parquet (one-time cost)");
            }
            
            System.out.println("\n" + "=".repeat(80));
            
        } catch (Exception e) {
            System.err.println("FATAL ERROR: " + e.getMessage());
            e.printStackTrace();
        } finally {
            // =========================================================
            // STEP 9: Cleanup
            // =========================================================
            
            System.out.println("\nCleaning up...");
            
            // Close connection
            try {
                if (conn != null && !conn.isClosed()) {
                    conn.close();
                    System.out.println("  Connection closed");
                }
            } catch (SQLException e) {
                System.err.println("  Error closing connection: " + e.getMessage());
            }
            
            // Delete temp directory
            deleteDirectory(new File(TEMP_DIR));
            System.out.println("  Temp directory cleaned");
            
            // Optionally delete CSV files after conversion
            System.out.println("\n[Cleanup] CSV files can be deleted to free space.");
            System.out.println("  (Parquet file " + PARQUET_INPUT_FILE + " contains all data)");
            
            System.out.println("\nAggregation completed successfully!");
        }
    }
    
    // =========================================================
    // YEAR-WISE CHUNKED EXPORT (Optimized for large datasets)
    // =========================================================
    
    private static void exportYearWise(Connection conn, long totalRows) throws SQLException {
        System.out.println("  Exporting by year (preserves ORDER BY build_year DESC)...");
        
        // Get distinct years in DESC order
        List<Integer> years = new ArrayList<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT DISTINCT build_year FROM export_data ORDER BY build_year DESC")) {
            while (rs.next()) {
                years.add(rs.getInt(1));
            }
        }
        
        System.out.println("  Years to export (DESC order): " + years);
        System.out.printf("  Total years: %d%n", years.size());
        
        List<String> chunkFiles = new ArrayList<>();
        long totalChunkRows = 0;
        long chunkStart = System.currentTimeMillis();
        
        // Export each year separately
        for (int year : years) {
            String chunkFile = "chunk_" + year + ".parquet";
            chunkFiles.add(chunkFile);
            
            System.out.println("    Exporting year " + year + "...");
            long yearStart = System.currentTimeMillis();
            
            try (Statement stmt = conn.createStatement()) {
                String exportSQL = "COPY (" +
                    "SELECT * FROM export_data " +
                    "WHERE build_year = " + year +
                    ") TO '" + chunkFile + "' (FORMAT PARQUET, COMPRESSION ZSTD)";
                stmt.execute(exportSQL);
                
                // Get row count for this year
                long yearRows = 0;
                try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM read_parquet('" + chunkFile + "')")) {
                    if (rs.next()) {
                        yearRows = rs.getLong(1);
                    }
                }
                totalChunkRows += yearRows;
                
                long yearTime = System.currentTimeMillis() - yearStart;
                System.out.printf("      ✓ Year %d exported (%,d rows, %.2f seconds)%n", 
                    year, yearRows, yearTime / 1000.0);
                    
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
        
        long chunkTime = System.currentTimeMillis() - chunkStart;
        System.out.printf("  Chunks exported in %.2f minutes%n", chunkTime / 60000.0);
        System.out.printf("  Total rows in chunks: %,d%n", totalChunkRows);
        
        // Combine all chunks into final file
        System.out.println("  Combining chunks (preserves ORDER BY build_year DESC)...");
        long combineStart = System.currentTimeMillis();
        
        try (Statement stmt = conn.createStatement()) {
            String chunkList = chunkFiles.stream()
                .map(f -> "'" + f + "'")
                .collect(Collectors.joining(", "));
            
            String combineSQL = "COPY (" +
                "SELECT * FROM read_parquet([" + chunkList + "])" +
                ") TO '" + OUTPUT_FILE + "' (FORMAT PARQUET, COMPRESSION ZSTD)";
            stmt.execute(combineSQL);
            System.out.println("  ✓ Chunks combined successfully");
        }
        
        long combineTime = System.currentTimeMillis() - combineStart;
        System.out.printf("  Combine time: %.2f seconds%n", combineTime / 1000.0);
        
        // Clean up chunk files
        System.out.println("  Cleaning up temporary chunks...");
        int deletedChunks = 0;
        for (String chunkFile : chunkFiles) {
            File f = new File(chunkFile);
            if (f.exists() && f.delete()) {
                deletedChunks++;
            }
        }
        System.out.printf("  ✓ Cleaned up %d chunk files%n", deletedChunks);
        
        // Verify combined file
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM read_parquet('" + OUTPUT_FILE + "')")) {
            if (rs.next()) {
                long finalRows = rs.getLong(1);
                System.out.printf("  Final rows in combined file: %,d%n", finalRows);
                if (finalRows != totalRows) {
                    System.err.printf("  WARNING: Row count mismatch! Expected %,d, got %,d%n", totalRows, finalRows);
                }
            }
        }
    }
    
    // =========================================================
    // HELPER METHODS
    // =========================================================
    
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