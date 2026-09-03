// UE2.java - FIXED with 8GB memory limit (matching Python)
package com.engine.workflow;

import java.sql.*;
import java.io.*;

public class UE2 {
    
    private static final String INPUT_FILE = "file4_aggregated.parquet";
    private static final String OUTPUT_FILE = "file5.parquet";
    private static final String TEMP_DIR = "C:/duckdb_temp";
    
    public static void main(String[] args) {
        long startTime = System.currentTimeMillis();
        
        System.out.println("Reading: " + INPUT_FILE);
        
        File input = new File(INPUT_FILE);
        if (!input.exists()) {
            System.err.println("[ERROR] " + INPUT_FILE + " not found!");
            System.exit(1);
        }
        
        long inputSizeMB = input.length() / (1024 * 1024);
        System.out.printf("Input file size: %d MB%n", inputSizeMB);
        
        if (input.length() == 0) {
            System.out.println("[WARNING] " + INPUT_FILE + " is empty! Creating empty output file.");
            try (Connection conn = DriverManager.getConnection("jdbc:duckdb:")) {
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("COPY (" +
                        "SELECT " +
                        "NULL::INT AS Engine_serial_count, " +
                        "NULL::VARCHAR AS option_assembly_num, " +
                        "NULL::VARCHAR AS esn_config_design_config_Shop_order_num, " +
                        "NULL::INT AS build_year " +
                        "WHERE 1=0" +
                        ") TO '" + OUTPUT_FILE + "' (FORMAT PARQUET)");
                }
                System.exit(0);
            } catch (SQLException e) {
                System.err.println("[ERROR] " + e.getMessage());
                System.exit(1);
            }
        }
        
        File tempDir = new File(TEMP_DIR);
        if (!tempDir.exists()) {
            tempDir.mkdirs();
        }
        long freeSpaceGB = tempDir.getFreeSpace() / (1024 * 1024 * 1024);
        System.out.printf("Available temp space: %d GB%n", freeSpaceGB);
        
        try (Connection conn = DriverManager.getConnection("jdbc:duckdb:")) {
            try (Statement stmt = conn.createStatement()) {
                // ===== MATCH PYTHON: 8GB memory limit =====
                stmt.execute("PRAGMA memory_limit='8GB'");  // Changed from 2GB to 8GB
                stmt.execute("PRAGMA temp_directory='" + TEMP_DIR + "'");
                stmt.execute("PRAGMA threads=8");  // Changed from 2 to 8 (matching Python)
                stmt.execute("PRAGMA preserve_insertion_order=false");
                
                System.out.println("\n=== DuckDB Configuration ===");
                System.out.println("Memory limit: 8GB");
                System.out.println("Temp directory: " + TEMP_DIR);
                System.out.println("Threads: 8");
                System.out.println("============================\n");
            }
            
            System.out.println("Processing data...");
            
            try (Statement stmt = conn.createStatement()) {
                // ===== MATCH PYTHON LOGIC: ROW_NUMBER() =====
                String sql = 
                    "COPY (" +
                    "WITH ranked AS (" +
                    "  SELECT " +
                    "    Engine_serial_count, " +
                    "    option_assembly_num, " +
                    "    esn_config_design_config_Shop_order_num, " +
                    "    build_year, " +
                    "    ROW_NUMBER() OVER (" +
                    "      PARTITION BY esn_config_design_config_Shop_order_num " +
                    "      ORDER BY build_year DESC, Engine_serial_count DESC" +
                    "    ) AS rn " +
                    "  FROM read_parquet('" + INPUT_FILE + "')" +
                    ") " +
                    "SELECT " +
                    "  Engine_serial_count, " +
                    "  option_assembly_num, " +
                    "  esn_config_design_config_Shop_order_num, " +
                    "  build_year " +
                    "FROM ranked " +
                    "WHERE rn = 1 " +
                    "ORDER BY build_year DESC" +
                    ") TO '" + OUTPUT_FILE + "' (FORMAT PARQUET, COMPRESSION ZSTD)";
                
                System.out.println("Executing deduplication with ROW_NUMBER()...");
                long dedupStart = System.currentTimeMillis();
                stmt.execute(sql);
                long dedupTime = (System.currentTimeMillis() - dedupStart) / 1000;
                System.out.printf("Deduplication completed in %d seconds%n", dedupTime);
            }
            
            // Verify output record count
            System.out.println("Verifying output...");
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM read_parquet('" + OUTPUT_FILE + "')")) {
                if (rs.next()) {
                    long outputRecords = rs.getLong(1);
                    System.out.printf("Output records: %,d%n", outputRecords);
                }
            }
            
            long endTime = System.currentTimeMillis();
            double totalTime = (endTime - startTime) / 1000.0;
            
            File outputFile = new File(OUTPUT_FILE);
            if (outputFile.exists()) {
                long outputSizeMB = outputFile.length() / (1024 * 1024);
                System.out.printf("\nOutput file size: %d MB%n", outputSizeMB);
            }
            
            System.out.printf("\nTotal processing time: %.2f seconds%n", totalTime);
            System.out.println("Output saved to: " + OUTPUT_FILE);
            
        } catch (SQLException e) {
            System.err.println("[ERROR] " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        } finally {
            cleanupTempFiles();
        }
    }
    
    private static void cleanupTempFiles() {
        try {
            File tempDir = new File(TEMP_DIR);
            if (tempDir.exists() && tempDir.isDirectory()) {
                File[] files = tempDir.listFiles();
                if (files != null) {
                    int count = 0;
                    for (File file : files) {
                        if (file.getName().startsWith("duckdb_temp_") || 
                            file.getName().endsWith(".tmp") ||
                            file.getName().endsWith(".block")) {
                            if (file.delete()) {
                                count++;
                            }
                        }
                    }
                    if (count > 0) {
                        System.out.printf("Cleaned up %d temporary files%n", count);
                    }
                }
            }
        } catch (Exception e) {
            // Ignore cleanup errors
        }
    }
}