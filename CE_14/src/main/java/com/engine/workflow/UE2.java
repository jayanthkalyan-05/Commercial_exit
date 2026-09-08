// UE2.java - EXACTLY MATCHES PYTHON
package com.engine.workflow;

import java.sql.*;
import java.io.*;

public class UE2 {
    
    private static final String INPUT_FILE = "file4_aggregated.parquet";
    private static final String OUTPUT_FILE = "file5.parquet";
    
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
            try (Connection conn = DriverManager.getConnection("jdbc:duckdb::memory:")) {
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
        
        // EXACTLY LIKE PYTHON: in-memory connection
        try (Connection conn = DriverManager.getConnection("jdbc:duckdb::memory:")) {
            try (Statement stmt = conn.createStatement()) {
                // ===== MATCH PYTHON EXACTLY =====
                stmt.execute("PRAGMA memory_limit='8GB'");  // Changed back to 8GB
                stmt.execute("PRAGMA threads=8");
                stmt.execute("PRAGMA preserve_insertion_order=false");
                // NO temp_directory PRAGMA - let DuckDB handle it like Python
                
                System.out.println("\n=== DuckDB Configuration ===");
                System.out.println("Memory limit: 8GB (matching Python)");
                System.out.println("Threads: 8");
                System.out.println("Temp directory: DEFAULT (matching Python)");
                System.out.println("============================\n");
            }
            
            System.out.println("Processing data...");
            
            try (Statement stmt = conn.createStatement()) {
                // ===== EXACT SAME QUERY AS PYTHON =====
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
        }
    }
}