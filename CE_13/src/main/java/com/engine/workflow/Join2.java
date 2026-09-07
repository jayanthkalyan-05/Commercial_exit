// Join2.java - UPDATED to find files in common folder
package com.engine.workflow;

import org.duckdb.DuckDBConnection;
import java.sql.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

public class Join2 {
    
    private static final String COMMON_FOLDER = "data";
    
    public static void main(String[] args) {
        long startTime = System.currentTimeMillis();
        try {
            // Get the option file from command line argument
            String optionFile = args.length > 0 ? args[0] : "Csv_bom_esn_option.csv";
            
            // Extract option file name for dynamic output
            String optionName = new File(optionFile).getName().replaceAll("\\.csv$", "");
            String outputFile = "file2_" + optionName + ".csv";
            
            // Find the actual file paths
            String optionFilePath = findFile(optionFile);
            String configFilePath = findFile("Csv_bom_esn_config.csv");
            
            if (optionFilePath == null) {
                System.err.println("[ERROR] Option file not found: " + optionFile);
                System.exit(1);
            }
            if (configFilePath == null) {
                System.err.println("[ERROR] Config file not found: Csv_bom_esn_config.csv");
                System.exit(1);
            }
            
            System.out.println("Starting INNER JOIN - using option file: " + optionFilePath);
            System.out.println("Config file: " + configFilePath);
            System.out.println("Output file: " + outputFile);
            
            // ===== DEBUGGING: Check data =====
            System.out.println("\n[DEBUG] Checking data samples...");
            
            // Perform the join
            try (Connection conn = DriverManager.getConnection("jdbc:duckdb:")) {
                
                // Check Engine_serial column exists
                String optionColumns = getColumns(conn, optionFilePath);
                String configColumns = getColumns(conn, configFilePath);
                
                if (!optionColumns.contains("Engine_serial")) {
                    System.err.println("[ERROR] 'Engine_serial' not found in option file!");
                    System.err.println("Available columns: " + optionColumns);
                    System.exit(1);
                }
                
                if (!configColumns.contains("Engine_serial")) {
                    System.err.println("[ERROR] 'Engine_serial' not found in config file!");
                    System.err.println("Available columns: " + configColumns);
                    System.exit(1);
                }
                
                // Check for matching values
                Set<String> optionSerials = getSerials(conn, optionFilePath);
                Set<String> configSerials = getSerials(conn, configFilePath);
                Set<String> common = new HashSet<>(optionSerials);
                common.retainAll(configSerials);
                
                System.out.printf("Option Engine_serial sample: %s%n", 
                    optionSerials.stream().limit(5).collect(Collectors.toList()));
                System.out.printf("Config Engine_serial sample: %s%n", 
                    configSerials.stream().limit(5).collect(Collectors.toList()));
                System.out.printf("Common values found: %d%n", common.size());
                
                if (common.isEmpty()) {
                    System.out.println("\n[WARNING] No matching Engine_serial values found!");
                    System.out.println("The join will produce 0 records. Check data formats, case sensitivity, or column names.");
                    
                    // Check for case sensitivity issues
                    Set<String> optionLower = optionSerials.stream()
                        .map(String::toLowerCase)
                        .collect(Collectors.toSet());
                    Set<String> configLower = configSerials.stream()
                        .map(String::toLowerCase)
                        .collect(Collectors.toSet());
                    Set<String> commonLower = new HashSet<>(optionLower);
                    commonLower.retainAll(configLower);
                    
                    if (!commonLower.isEmpty()) {
                        System.out.printf("Found %d matches when ignoring case!%n", commonLower.size());
                        System.out.println("This suggests case sensitivity issues.");
                        
                        // Show examples
                        int count = 0;
                        for (String val : commonLower) {
                            if (count >= 3) break;
                            String optionMatch = optionSerials.stream()
                                .filter(s -> s.toLowerCase().equals(val))
                                .findFirst().orElse("");
                            String configMatch = configSerials.stream()
                                .filter(s -> s.toLowerCase().equals(val))
                                .findFirst().orElse("");
                            System.out.printf("  Option: '%s' vs Config: '%s'%n", optionMatch, configMatch);
                            count++;
                        }
                    }
                } else {
                    System.out.printf("[OK] Found %d matching Engine_serial values%n", common.size());
                }
                
                System.out.println("\n" + "=".repeat(60));
                
                // Perform the join with proper file paths
                try (Statement stmt = conn.createStatement()) {
                    // Convert Windows paths to forward slashes for DuckDB
                    String optionPath = optionFilePath.replace("\\", "/");
                    String configPath = configFilePath.replace("\\", "/");
                    
                    stmt.execute("COPY (" +
                        "SELECT " +
                        "CAST(l.Engine_serial AS VARCHAR) AS Engine_serial, " +
                        "l.option_assembly_num, " +
                        "s.design_config_2, " +
                        "l.shop_order_num, " +
                        "s.build_year " +
                        "FROM read_csv_auto('" + optionPath + "') l " +
                        "INNER JOIN read_csv_auto('" + configPath + "') s " +
                        "ON CAST(l.Engine_serial AS VARCHAR) = CAST(s.Engine_serial AS VARCHAR)" +
                        ") TO '" + outputFile + "' (HEADER, DELIMITER ',')");
                }
                
                long endTime = System.currentTimeMillis();
                System.out.printf("\n[SUCCESS] Completed in %.2f seconds%n", (endTime - startTime) / 1000.0);
                System.out.println("Output saved to: " + outputFile);
                
                // Check output file size
                File output = new File(outputFile);
                if (output.exists()) {
                    double sizeMB = output.length() / (1024.0 * 1024.0);
                    if (sizeMB > 0) {
                        System.out.printf("Output file size: %.2f MB%n", sizeMB);
                    } else {
                        System.out.println("[WARNING] Output file is empty! No matches found.");
                    }
                }
                
            } catch (SQLException e) {
                System.err.println("[ERROR] SQL Error: " + e.getMessage());
                e.printStackTrace();
                System.exit(1);
            }
            
        } catch (Exception e) {
            System.err.println("[ERROR] " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
    
    /**
     * Find file in common folder or current directory
     */
    private static String findFile(String filename) {
        // Check current directory first
        File currentFile = new File(filename);
        if (currentFile.exists()) {
            return filename;
        }
        
        // Check common folder (data/)
        String commonPath = COMMON_FOLDER + File.separator + filename;
        File commonFile = new File(commonPath);
        if (commonFile.exists()) {
            return commonPath;
        }
        
        // Check with "data/" prefix (alternative path format)
        String altPath = "data/" + filename;
        File altFile = new File(altPath);
        if (altFile.exists()) {
            return altPath;
        }
        
        // Check parent directory
        File parentFile = new File("../" + filename);
        if (parentFile.exists()) {
            return parentFile.getPath();
        }
        
        return null;
    }
    
    private static String getColumns(Connection conn, String file) throws SQLException {
        String filePath = file.replace("\\", "/");
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM read_csv_auto('" + filePath + "') LIMIT 0")) {
            int columnCount = rs.getMetaData().getColumnCount();
            StringBuilder sb = new StringBuilder();
            for (int i = 1; i <= columnCount; i++) {
                if (i > 1) sb.append(", ");
                sb.append(rs.getMetaData().getColumnName(i));
            }
            return sb.toString();
        }
    }
    
    private static Set<String> getSerials(Connection conn, String file) throws SQLException {
        Set<String> serials = new HashSet<>();
        String filePath = file.replace("\\", "/");
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT Engine_serial FROM read_csv_auto('" + filePath + "') LIMIT 1000")) {
            while (rs.next()) {
                serials.add(rs.getString(1));
            }
        }
        return serials;
    }
}