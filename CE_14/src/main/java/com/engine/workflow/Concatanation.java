// Concatanation.java
package com.engine.workflow;

import org.duckdb.DuckDBConnection;
import java.sql.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Reads CSV file and creates output with concatenated column.
 * DUCKDB OPTIMIZED: 5-10x faster than pandas
 * 
 * Input columns: Engine_serial, option_assembly_num, design_config_2, shop_order_num, build_year
 * Output columns: Engine_serial, option_assembly_num, esn_config_design_config_Shop_order_num, build_year
 */
public class Concatanation {
    
    private static final int CHUNK_SIZE = 100000;
    private static final long BUFFER_SIZE = 16 * 1024 * 1024; // 16MB
    
    public static void main(String[] args) {
        String inputFile = args.length > 0 ? args[0] : "file2.csv";
        String outputFile = args.length > 1 ? args[1] : "file3.csv";
        concatenateColumns(inputFile, outputFile);
    }
    
    public static boolean concatenateColumns(String inputFile, String outputFile) {
        System.out.println("=" .repeat(70));
        System.out.println("CONCATENATION SCRIPT (DUCKDB OPTIMIZED)");
        System.out.println("  Input file: " + inputFile);
        System.out.println("  Output file: " + outputFile);
        System.out.println("  Creating: esn_config_design_config_Shop_order_num = design_config_2 + '_' + shop_order_num");
        System.out.println("=" .repeat(70));
        
        // Check if input file exists
        File input = new File(inputFile);
        if (!input.exists()) {
            System.err.println("[ERROR] " + inputFile + " not found!");
            return false;
        }
        
        // Check if input file is empty
        if (input.length() == 0) {
            System.out.println("[WARNING] " + inputFile + " is empty! Creating empty output file.");
            try (PrintWriter writer = new PrintWriter(new FileWriter(outputFile))) {
                writer.println("Engine_serial,option_assembly_num,esn_config_design_config_Shop_order_num,build_year");
            } catch (IOException e) {
                System.err.println("[ERROR] Could not create output file: " + e.getMessage());
                return false;
            }
            System.out.println("[WARNING] Created empty " + outputFile);
            return false;
        }
        
        // Get file size
        double fileSizeGB = input.length() / (1024.0 * 1024.0 * 1024.0);
        System.out.printf("\n[INFO] Input file size: %.2f GB%n", fileSizeGB);
        
        // Try DuckDB first
        try {
            return concatenateColumnsDuckDB(inputFile, outputFile);
        } catch (Exception e) {
            System.err.println("[ERROR] DuckDB processing failed: " + e.getMessage());
            System.out.println("[INFO] Falling back to CSV fallback...");
            return concatenateColumnsFallback(inputFile, outputFile);
        }
    }
    
    private static boolean concatenateColumnsDuckDB(String inputFile, String outputFile) throws SQLException {
        System.out.println("\n[INFO] Using DuckDB ultra-fast engine...");
        long startTime = System.currentTimeMillis();
        
        try (Connection conn = DriverManager.getConnection("jdbc:duckdb:")) {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("SET threads = 4");
                stmt.execute("SET memory_limit = '16GB'");
                stmt.execute("SET max_temp_directory_size = '200GB'");
                stmt.execute("SET preserve_insertion_order = false");
            }
            
            // Read and concatenate in one pass
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("CREATE TEMP TABLE input_data AS " +
                    "SELECT " +
                    "Engine_serial, " +
                    "option_assembly_num, " +
                    "design_config_2, " +
                    "shop_order_num, " +
                    "build_year " +
                    "FROM read_csv_auto('" + inputFile + "')");
            }
            
            // Get row count before writing
            long count = 0;
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM input_data")) {
                if (rs.next()) {
                    count = rs.getLong(1);
                }
            }
            System.out.printf("  Records found: %,d%n", count);
            
            // Write output with concatenated column
            System.out.println("  Writing output...");
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("COPY (" +
                    "SELECT " +
                    "Engine_serial, " +
                    "option_assembly_num, " +
                    "design_config_2 || '_' || shop_order_num AS esn_config_design_config_Shop_order_num, " +
                    "build_year " +
                    "FROM input_data" +
                    ") TO '" + outputFile + "' (HEADER, DELIMITER ',')");
            }
            
            long elapsed = System.currentTimeMillis() - startTime;
            
            // Verify output
            File output = new File(outputFile);
            if (output.exists()) {
                double outputSizeGB = output.length() / (1024.0 * 1024.0 * 1024.0);
                System.out.printf("  Output size: %.2f GB%n", outputSizeGB);
            }
            
            // Summary
            System.out.println("\n" + "=" .repeat(70));
            System.out.println("[SUCCESS] CONCATENATION COMPLETE!");
            System.out.println("=" .repeat(70));
            System.out.printf("Processed: %,d rows%n", count);
            System.out.printf("Time: %.2fs (%.2f minutes)%n", elapsed / 1000.0, elapsed / 60000.0);
            System.out.printf("Speed: %,.0f rows/second%n", count / (elapsed / 1000.0));
            System.out.println("[INFO] Output saved to: " + outputFile);
            
            // Show sample rows
            System.out.println("\n[INFO] Sample rows from output file:");
            System.out.println("-".repeat(70));
            try (BufferedReader reader = new BufferedReader(new FileReader(outputFile))) {
                String line;
                for (int i = 0; i < 6 && (line = reader.readLine()) != null; i++) {
                    System.out.println("  " + line);
                }
            } catch (IOException e) {
                System.err.println("[WARNING] Could not read sample rows: " + e.getMessage());
            }
            
            return true;
        }
    }
    
    private static boolean concatenateColumnsFallback(String inputFile, String outputFile) {
        System.out.println("[INFO] Using chunked CSV fallback...");
        long processedRows = 0;
        BufferedReader reader = null;
        PrintWriter writer = null;
        
        try {
            reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(inputFile), "UTF-8"), 
                (int) BUFFER_SIZE);
            writer = new PrintWriter(
                new OutputStreamWriter(new FileOutputStream(outputFile), "UTF-8"));
            
            // Read header
            String headerLine = reader.readLine();
            if (headerLine == null) {
                System.out.println("[WARNING] Input file is empty");
                return false;
            }
            
            String[] header = headerLine.split(",");
            Map<String, Integer> columnIndex = new HashMap<>();
            for (int i = 0; i < header.length; i++) {
                String col = header[i].trim().replace("\ufeff", "");
                columnIndex.put(col, i);
            }
            
            // Find column indices
            Integer designIdx = columnIndex.get("design_config_2");
            Integer shopIdx = columnIndex.get("shop_order_num");
            Integer engineIdx = columnIndex.get("Engine_serial");
            Integer optionIdx = columnIndex.get("option_assembly_num");
            Integer yearIdx = columnIndex.get("build_year");
            
            if (designIdx == null || shopIdx == null || engineIdx == null || 
                optionIdx == null || yearIdx == null) {
                System.err.println("[ERROR] Required column not found");
                return false;
            }
            
            // Write output header
            writer.println("Engine_serial,option_assembly_num,esn_config_design_config_Shop_order_num,build_year");
            
            // Process rows in chunks
            List<String[]> chunk = new ArrayList<>(CHUNK_SIZE);
            String line;
            
            while ((line = reader.readLine()) != null) {
                String[] row = line.split(",");
                
                String engineSerial = row.length > engineIdx ? row[engineIdx] : "";
                String optionAssembly = row.length > optionIdx ? row[optionIdx] : "";
                String designVal = row.length > designIdx ? row[designIdx] : "";
                String shopVal = row.length > shopIdx ? row[shopIdx] : "";
                String year = row.length > yearIdx ? row[yearIdx] : "";
                
                String concatenated = designVal + "_" + shopVal;
                String[] newRow = {engineSerial, optionAssembly, concatenated, year};
                chunk.add(newRow);
                processedRows++;
                
                if (chunk.size() >= CHUNK_SIZE) {
                    for (String[] rowData : chunk) {
                        writer.println(String.join(",", rowData));
                    }
                    chunk.clear();
                    
                    if (processedRows % 1_000_000 == 0) {
                        System.out.printf("   Progress: %,d rows%n", processedRows);
                    }
                }
            }
            
            // Write remaining rows
            if (!chunk.isEmpty()) {
                for (String[] rowData : chunk) {
                    writer.println(String.join(",", rowData));
                }
            }
            
            System.out.printf("Fallback completed: %,d rows%n", processedRows);
            return true;
            
        } catch (IOException e) {
            System.err.println("[ERROR] Fallback processing failed: " + e.getMessage());
            return false;
        } finally {
            try {
                if (reader != null) reader.close();
                if (writer != null) writer.close();
            } catch (IOException e) {
                // Ignore close errors
            }
        }
    }
}