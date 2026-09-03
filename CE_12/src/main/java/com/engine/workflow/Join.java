// Join.java
package com.engine.workflow;

import org.duckdb.DuckDBConnection;
import java.sql.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Ultra-fast join with automatic filtering & CSV conversion (No OOM)
 */
public class Join {
    
    private static final String[] REQUIRED_FILES = {"file4_aggregated.parquet", "file5.parquet"};
    private static final String FILTERED_FILE = "child_part_filtered.parquet";
    private static final String FILE5_FILTERED = "file5_filtered.parquet";
    private static final String OUTPUT_FILE = "file6.csv";
    
    public static void main(String[] args) {
        try {
            long startTime = System.currentTimeMillis();
            
            // Check required files
            for (String file : REQUIRED_FILES) {
                if (!new File(file).exists()) {
                    System.err.println("ERROR: " + file + " not found!");
                    System.exit(1);
                }
            }
            
            // Handle child_part file - MUST convert CSV to Parquet with proper headers
            String childPartSource = null;
            String optionColumnName = null;
            List<String> allColumns = new ArrayList<>();
            
            // Delete old parquet if it exists (to ensure fresh conversion)
            if (new File("child_part_option.parquet").exists()) {
                System.out.println("[INFO] Deleting existing child_part_option.parquet to re-convert with headers...");
                new File("child_part_option.parquet").delete();
            }
            
            if (new File("child_part_option.csv").exists()) {
                System.out.println("[CONVERT] Converting child_part_option.csv to Parquet (with headers)...");
                try (Connection conn = DriverManager.getConnection("jdbc:duckdb:engine_data.db")) {
                    try (Statement stmt = conn.createStatement()) {
                        // Read with header=true to preserve column names
                        stmt.execute("COPY (SELECT * FROM read_csv_auto('child_part_option.csv', header=true)) " +
                            "TO 'child_part_option.parquet' (FORMAT PARQUET, COMPRESSION ZSTD)");
                    }
                    System.out.println("[OK] Successfully converted CSV to child_part_option.parquet");
                    childPartSource = "child_part_option.parquet";
                }
            } else {
                System.err.println("ERROR: child_part_option.csv not found!");
                System.exit(1);
            }
            
            // ***** INSPECT COLUMNS TO GET EXACT NAMES *****
            try (Connection conn = DriverManager.getConnection("jdbc:duckdb:engine_data.db")) {
                System.out.println("  Inspecting child_part_option.parquet columns...");
                try (Statement stmt = conn.createStatement();
                     ResultSet rs = stmt.executeQuery("DESCRIBE SELECT * FROM read_parquet('" + childPartSource + "')")) {
                    // Clear the list before adding new items
                    allColumns.clear();
                    while (rs.next()) {
                        String colName = rs.getString(1);
                        allColumns.add(colName);
                        System.out.println("    Found column: '" + colName + "'");
                    }
                    
                    // Find the option column
                    for (String col : allColumns) {
                        String cleanCol = col.replace("\ufeff", "").trim();
                        if (cleanCol.equalsIgnoreCase("Option_Number") || 
                            cleanCol.equalsIgnoreCase("option_assembly_num") || 
                            cleanCol.equalsIgnoreCase("option_number") ||
                            cleanCol.equalsIgnoreCase("option") ||
                            cleanCol.equalsIgnoreCase("option_num")) {
                            optionColumnName = cleanCol;
                            break;
                        }
                    }
                }
                
                if (optionColumnName == null) {
                    System.err.println("ERROR: Could not find the option column in child_part_option.parquet!");
                    System.err.println("Available columns: " + allColumns);
                    System.exit(1);
                } else {
                    System.out.println("[OK] Using column: '" + optionColumnName + "' for join");
                }
            }
            
            // ***** MAIN JOIN LOGIC *****
            try (Connection conn = DriverManager.getConnection("jdbc:duckdb:engine_data.db")) {
                // Set optimal settings
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("PRAGMA memory_limit='16GB'");
                    stmt.execute("PRAGMA threads=2");
                    stmt.execute("PRAGMA preserve_insertion_order=false");
                    stmt.execute("PRAGMA temp_directory='./duckdb_temp'");
                }
                
                System.out.println("=".repeat(60));
                System.out.println("STEP 1: Filtering child_part_option to only needed options...");
                System.out.println("=".repeat(60));
                
                if (new File(FILTERED_FILE).exists()) {
                    System.out.println("  [OK] Filtered file already exists: " + FILTERED_FILE);
                    childPartSource = FILTERED_FILE;
                } else {
                    System.out.println("  Creating filtered child_part...");
                    
                    // Get unique options from file4
                    System.out.println("    Reading unique options from file4...");
                    try (Statement stmt = conn.createStatement()) {
                        stmt.execute("CREATE OR REPLACE TEMP TABLE file4_options AS " +
                            "SELECT DISTINCT option_assembly_num " +
                            "FROM read_parquet('file4_aggregated.parquet')");
                    }
                    
                    long optionCount = 0;
                    try (Statement stmt = conn.createStatement();
                         ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM file4_options")) {
                        if (rs.next()) {
                            optionCount = rs.getLong(1);
                        }
                    }
                    System.out.printf("    Found %,d unique options in file4%n", optionCount);
                    
                    // Filter child_part and save
                    try (Statement stmt = conn.createStatement()) {
                        stmt.execute("COPY (" +
                            "SELECT cp.* " +
                            "FROM read_parquet('" + childPartSource + "') cp " +
                            "INNER JOIN file4_options fo " +
                            "ON cp.\"" + optionColumnName + "\" = fo.option_assembly_num" +
                            ") TO '" + FILTERED_FILE + "' (FORMAT PARQUET, COMPRESSION ZSTD)");
                    }
                    
                    long filteredCount = 0;
                    try (Statement stmt = conn.createStatement();
                         ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM read_parquet('" + FILTERED_FILE + "')")) {
                        if (rs.next()) {
                            filteredCount = rs.getLong(1);
                        }
                    }
                    double filteredSize = new File(FILTERED_FILE).length() / (1024.0 * 1024.0 * 1024.0);
                    System.out.printf("    [OK] Saved %,d rows (%.2f GB) to %s%n", filteredCount, filteredSize, FILTERED_FILE);
                    childPartSource = FILTERED_FILE;
                    
                    // Clean up temp table
                    try (Statement stmt = conn.createStatement()) {
                        stmt.execute("DROP TABLE IF EXISTS file4_options");
                    }
                }
                
                System.out.println("\n" + "=".repeat(60));
                System.out.println("STEP 2: Pre-filtering file5 to only needed orders...");
                System.out.println("=".repeat(60));
                
                String file5Source = FILE5_FILTERED;
                
                if (new File(FILE5_FILTERED).exists()) {
                    System.out.println("  [OK] Filtered file5 already exists: " + FILE5_FILTERED);
                } else {
                    System.out.println("  Creating filtered file5...");
                    try (Statement stmt = conn.createStatement()) {
                        stmt.execute("COPY (" +
                            "SELECT f5.* " +
                            "FROM read_parquet('file5.parquet') f5 " +
                            "WHERE EXISTS (" +
                            "SELECT 1 " +
                            "FROM read_parquet('file4_aggregated.parquet') f4 " +
                            "WHERE f4.esn_config_design_config_Shop_order_num = f5.esn_config_design_config_Shop_order_num" +
                            ")" +
                            ") TO '" + FILE5_FILTERED + "' (FORMAT PARQUET, COMPRESSION ZSTD)");
                    }
                    
                    long filteredCount = 0;
                    try (Statement stmt = conn.createStatement();
                         ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM read_parquet('" + FILE5_FILTERED + "')")) {
                        if (rs.next()) {
                            filteredCount = rs.getLong(1);
                        }
                    }
                    double filteredSize = new File(FILE5_FILTERED).length() / (1024.0 * 1024.0 * 1024.0);
                    System.out.printf("    [OK] Saved %,d rows (%.2f GB) to %s%n", filteredCount, filteredSize, FILE5_FILTERED);
                }
                
                System.out.println("\n" + "=".repeat(60));
                System.out.println("STEP 3: Running optimized join query...");
                System.out.println("=".repeat(60));
                
                // Create intermediate table for the first join (file4 + file5)
                System.out.println("  Creating intermediate join table (file4 + file5)...");
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("CREATE OR REPLACE TEMP TABLE file4_file5_join AS " +
                        "SELECT DISTINCT " +
                        "f4.esn_config_design_config_Shop_order_num, " +
                        "f4.option_assembly_num " +
                        "FROM read_parquet('file4_aggregated.parquet') f4 " +
                        "INNER JOIN read_parquet('" + file5Source + "') f5 " +
                        "ON f4.esn_config_design_config_Shop_order_num = f5.esn_config_design_config_Shop_order_num");
                }
                
                long intermediateCount = 0;
                try (Statement stmt = conn.createStatement();
                     ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM file4_file5_join")) {
                    if (rs.next()) {
                        intermediateCount = rs.getLong(1);
                    }
                }
                System.out.printf("    [OK] Created intermediate table with %,d rows%n", intermediateCount);
                
                // Now join with child_part and include all file5 columns
                System.out.println("  Performing final join with child parts...");
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("COPY (" +
                        "SELECT " +
                        "f5.Engine_serial_count, " +
                        "f5.option_assembly_num, " +
                        "f5.esn_config_design_config_Shop_order_num, " +
                        "f5.build_year, " +
                        "cp.Child_Part_Number " +
                        "FROM read_parquet('" + file5Source + "') f5 " +
                        "INNER JOIN file4_file5_join ij " +
                        "ON f5.esn_config_design_config_Shop_order_num = ij.esn_config_design_config_Shop_order_num " +
                        "INNER JOIN read_parquet('" + childPartSource + "') cp " +
                        "ON ij.option_assembly_num = cp.\"" + optionColumnName + "\" " +
                        "ORDER BY f5.build_year DESC, f5.Engine_serial_count DESC" +
                        ") TO '" + OUTPUT_FILE + "' (HEADER, DELIMITER ',')");
                }
                
                // Clean up temp table
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("DROP TABLE IF EXISTS file4_file5_join");
                }
                
                // Get count from output
                long resultCount = 0;
                try (Statement stmt = conn.createStatement();
                     ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM read_csv_auto('" + OUTPUT_FILE + "')")) {
                    if (rs.next()) {
                        resultCount = rs.getLong(1);
                    }
                }
                
                long endTime = System.currentTimeMillis();
                
                System.out.println("\n" + "=".repeat(60));
                System.out.println("RESULTS");
                System.out.println("=".repeat(60));
                System.out.printf("Total processing time: %.2f seconds%n", (endTime - startTime) / 1000.0);
                System.out.printf("Total output records: %,d%n", resultCount);
                System.out.println("\nOutput saved to: " + OUTPUT_FILE);
                
                // Preview without loading all data
                System.out.println("\nPreview (first 5 rows):");
                try (Statement stmt = conn.createStatement();
                     ResultSet rs = stmt.executeQuery("SELECT * FROM read_csv_auto('" + OUTPUT_FILE + "') LIMIT 5")) {
                    int columnCount = rs.getMetaData().getColumnCount();
                    // Print header
                    for (int i = 1; i <= columnCount; i++) {
                        System.out.print(rs.getMetaData().getColumnName(i) + (i < columnCount ? "\t" : "\n"));
                    }
                    // Print rows
                    while (rs.next()) {
                        for (int i = 1; i <= columnCount; i++) {
                            System.out.print(rs.getString(i) + (i < columnCount ? "\t" : "\n"));
                        }
                    }
                }
                
            } catch (SQLException e) {
                System.err.println("\nERROR: " + e.getMessage());
                e.printStackTrace();
                System.exit(1);
            }
            
        } catch (Exception e) {
            System.err.println("\nERROR: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}