// UE3.java - EXACTLY MATCHES PYTHON
package com.engine.workflow;

import java.sql.*;
import java.io.*;

/**
 * UE_3 - Pure SQL Streaming Version (Matches Python)
 */
public class UE3 {
    
    private static final String INPUT_FILE = "file6.csv";
    private static final String OUTPUT_FILE = "file7.csv";
    
    public static void main(String[] args) {
        long startTime = System.currentTimeMillis();
        
        File input = new File(INPUT_FILE);
        if (!input.exists()) {
            System.err.println("[ERROR] " + INPUT_FILE + " not found!");
            System.exit(1);
        }
        
        System.out.println("Reading: " + INPUT_FILE);
        
        // Use in-memory connection to match Python
        try (Connection conn = DriverManager.getConnection("jdbc:duckdb::memory:")) {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("PRAGMA memory_limit='4GB'");
                stmt.execute("PRAGMA threads=8");
            }
            
            System.out.println("Finding best row per Child_Part_Number...");
            
            // Step 1: Create a table with just the columns we need
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("CREATE OR REPLACE TABLE best_per_part AS " +
                    "WITH ranked AS (" +
                    "SELECT " +
                    "Engine_serial_count, " +
                    "option_assembly_num, " +
                    "esn_config_design_config_Shop_order_num, " +
                    "build_year, " +
                    "Child_Part_Number, " +
                    "ROW_NUMBER() OVER (" +
                    "PARTITION BY Child_Part_Number " +
                    "ORDER BY CAST(build_year AS INTEGER) DESC, " +
                    "CAST(Engine_serial_count AS INTEGER) DESC" +
                    ") AS rn " +
                    "FROM read_csv_auto('" + INPUT_FILE + "', " +
                    "all_varchar=true, " +
                    "strict_mode=false, " +  // ← ADDED: matches Python
                    "ignore_errors=true" +
                    ")" +
                    ")" +
                    "SELECT " +
                    "Engine_serial_count, " +
                    "option_assembly_num, " +
                    "esn_config_design_config_Shop_order_num, " +
                    "build_year, " +
                    "Child_Part_Number " +
                    "FROM ranked " +
                    "WHERE rn = 1");
            }
            
            // Step 2: Export the results
            System.out.println("Exporting results...");
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("COPY best_per_part TO '" + OUTPUT_FILE + "' (HEADER, DELIMITER ',')");
            }
            
            long resultCount = 0;
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM best_per_part")) {
                if (rs.next()) {
                    resultCount = rs.getLong(1);
                }
            }
            
            long endTime = System.currentTimeMillis();
            
            System.out.printf("%nTotal processing time: %.2f seconds%n", (endTime - startTime) / 1000.0);
            System.out.printf("Total unique Child Part Numbers: %,d%n", resultCount);
            System.out.println("\nOutput saved to: " + OUTPUT_FILE);
            
        } catch (SQLException e) {
            System.err.println("[ERROR] " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}