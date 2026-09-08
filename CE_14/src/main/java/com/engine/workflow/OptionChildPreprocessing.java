// OptionChildPreprocessing.java - KEEPS ALL RECORDS (like Python)
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;
import java.nio.charset.StandardCharsets;

public class OptionChildPreprocessing {
    
    private static final String INPUT_FOLDER = "input_option_child";
    private static final String OUTPUT_FOLDER = "data";
    private static final String OUTPUT_FILENAME = "child_part_option.csv";
    
    public static void main(String[] args) {
        try {
            // Create output folder if it doesn't exist
            Files.createDirectories(Paths.get(OUTPUT_FOLDER));
            
            // Find all CSV files in input folder
            List<Path> csvFiles;
            try (var stream = Files.walk(Paths.get(INPUT_FOLDER), 1)) {
                csvFiles = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().toLowerCase().endsWith(".csv"))
                    .collect(Collectors.toList());
            }
            
            if (csvFiles.isEmpty()) {
                System.out.println("No CSV files found in '" + INPUT_FOLDER + "'!");
                return;
            }
            
            System.out.println("Found " + csvFiles.size() + " file(s) in '" + INPUT_FOLDER + "'\n");
            
            // List to store all processed data
            List<String[]> allRecords = new ArrayList<>();
            String[] header = null;
            int totalRowsAcrossFiles = 0;
            
            // Process each CSV file
            for (int i = 0; i < csvFiles.size(); i++) {
                Path csvFile = csvFiles.get(i);
                System.out.println("[" + (i+1) + "/" + csvFiles.size() + "] Processing: " + csvFile.getFileName());
                
                try {
                    // 1. Auto-detect delimiter (pipe or comma)
                    char delimiter = detectDelimiter(csvFile);
                    System.out.println("   Detected delimiter: " + (delimiter == '|' ? "PIPE (|)" : "COMMA (,)"));
                    
                    // 2. Read file with detected delimiter
                    List<String[]> fileRecords = readCSVFile(csvFile, delimiter);
                    
                    if (fileRecords.isEmpty()) {
                        System.out.println("   ⚠️  File is empty!");
                        continue;
                    }
                    
                    // 3. Handle different column name variations
                    String[] fileHeader = fileRecords.get(0);
                    int optionColIndex = -1;
                    int childColIndex = -1;
                    
                    // Look for option number column
                    for (int j = 0; j < fileHeader.length; j++) {
                        String col = fileHeader[j].trim().toLowerCase();
                        if (col.equals("rev_option_no") || col.equals("option_number") || col.equals("option_no")) {
                            optionColIndex = j;
                        }
                        if (col.equals("child_part")) {
                            childColIndex = j;
                        }
                    }
                    
                    if (optionColIndex != -1 && childColIndex != -1) {
                        // Set header from first file that has required columns
                        if (header == null) {
                            header = new String[]{"Option_Number", "Child_Part_Number"};
                            allRecords.add(header);
                        }
                        
                        // Process data rows (skip header)
                        int recordsAdded = 0;
                        for (int row = 1; row < fileRecords.size(); row++) {
                            String[] rowData = fileRecords.get(row);
                            String optionNumber = "";
                            String childPartNumber = "";
                            
                            // Safely extract values, use empty string if column doesn't exist
                            if (rowData.length > optionColIndex) {
                                optionNumber = rowData[optionColIndex].trim();
                            }
                            if (rowData.length > childColIndex) {
                                childPartNumber = rowData[childColIndex].trim();
                            }
                            
                            // KEEP ALL RECORDS - just like Python!
                            // No empty check, no filtering!
                            allRecords.add(new String[]{optionNumber, childPartNumber});
                            recordsAdded++;
                        }
                        
                        totalRowsAcrossFiles += recordsAdded;
                        System.out.println("   Kept columns: Option_Number, Child_Part_Number");
                        System.out.println("   Records in this file: " + recordsAdded);
                    } else {
                        System.out.println("   ⚠️  Required columns not found! Available: " + 
                            String.join(", ", fileHeader));
                    }
                    
                } catch (Exception e) {
                    System.out.println("   ERROR processing " + csvFile.getFileName() + ": " + e.getMessage());
                }
            }
            
            // --- Combine and Save with Merge (No deduplication) ---
            if (allRecords.size() > 1) { // More than just header
                System.out.println("\nMerging all processed records...");
                
                // Check if output file already exists
                Path outputPath = Paths.get(OUTPUT_FOLDER, OUTPUT_FILENAME);
                List<String[]> existingRecords = new ArrayList<>();
                int existingRowCount = 0;
                
                if (Files.exists(outputPath)) {
                    System.out.println("📁 Found existing file: " + OUTPUT_FILENAME);
                    System.out.println("   Merging with existing data (concatenating, no deduplication)...");
                    
                    // Read existing file
                    char delimiter = detectDelimiter(outputPath);
                    List<String[]> existingFileRecords = readCSVFile(outputPath, delimiter);
                    
                    if (!existingFileRecords.isEmpty()) {
                        // Get header from existing file
                        String[] existingHeader = existingFileRecords.get(0);
                        
                        // Check if header matches expected format
                        if (existingHeader.length >= 2 && 
                            existingHeader[0].equals("Option_Number") && 
                            existingHeader[1].equals("Child_Part_Number")) {
                            
                            // Add existing data records (skip header)
                            for (int row = 1; row < existingFileRecords.size(); row++) {
                                String[] rowData = existingFileRecords.get(row);
                                if (rowData.length >= 2) {
                                    existingRecords.add(new String[]{rowData[0], rowData[1]});
                                    existingRowCount++;
                                } else {
                                    // Handle records with insufficient columns
                                    String opt = rowData.length > 0 ? rowData[0] : "";
                                    String child = rowData.length > 1 ? rowData[1] : "";
                                    existingRecords.add(new String[]{opt, child});
                                    existingRowCount++;
                                }
                            }
                            System.out.println("   Existing records: " + existingRowCount);
                        } else {
                            System.out.println("   ⚠️  Existing file has different format. Will overwrite.");
                        }
                    }
                }
                
                // Combine: Header + Existing Records + New Records
                List<String[]> combinedRecords = new ArrayList<>();
                
                // Add header (from new data, or create if none)
                if (!allRecords.isEmpty()) {
                    combinedRecords.add(allRecords.get(0)); // Header
                } else {
                    combinedRecords.add(new String[]{"Option_Number", "Child_Part_Number"});
                }
                
                // Add existing records (NO DEDUPLICATION - just concatenate)
                if (!existingRecords.isEmpty()) {
                    combinedRecords.addAll(existingRecords);
                }
                
                // Add new records (skip header)
                for (int i = 1; i < allRecords.size(); i++) {
                    combinedRecords.add(allRecords.get(i));
                }
                
                int finalTotalRows = combinedRecords.size() - 1; // Excluding header
                int newRecordsAdded = allRecords.size() - 1;
                
                // Write combined file
                writeCSVFile(outputPath, combinedRecords);
                
                // Calculate total rows across all files (matching Python behavior)
                int totalRowsAllFiles = totalRowsAcrossFiles + existingRowCount;
                
                System.out.println("\n✅ Saved merged file to: " + outputPath);
                System.out.println("   Total rows across ALL files: " + totalRowsAllFiles);
                System.out.println("   Final combined file rows: " + finalTotalRows);
                System.out.println("   Final Columns: Option_Number, Child_Part_Number");
                System.out.println("   NO records dropped - all records kept (matching Python behavior)");
                
                // Show preview
                System.out.println("\nPreview of first 5 rows:");
                int previewCount = Math.min(6, combinedRecords.size());
                for (int i = 0; i < previewCount; i++) {
                    String[] row = combinedRecords.get(i);
                    System.out.println(String.join(", ", row));
                }
                
                System.out.println("\n⚠️  Parquet conversion requires additional libraries (not implemented)");
                System.out.println("   CSV file saved successfully");
                
            } else {
                System.out.println("No data processed!");
            }
            
            System.out.println("\nAll done!");
            
        } catch (IOException e) {
            System.err.println("Fatal error: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Detect if file is pipe or comma delimited by reading first line
     */
    private static char detectDelimiter(Path filePath) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(filePath, StandardCharsets.UTF_8)) {
            String firstLine = reader.readLine();
            if (firstLine != null) {
                long pipeCount = firstLine.chars().filter(ch -> ch == '|').count();
                long commaCount = firstLine.chars().filter(ch -> ch == ',').count();
                return pipeCount >= commaCount ? '|' : ',';
            }
        }
        return ',';
    }
    
    /**
     * Read CSV file with specified delimiter
     */
    private static List<String[]> readCSVFile(Path filePath, char delimiter) throws IOException {
        List<String[]> records = new ArrayList<>();
        
        try (BufferedReader reader = Files.newBufferedReader(filePath, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                // Parse line based on delimiter
                List<String> fields = new ArrayList<>();
                StringBuilder currentField = new StringBuilder();
                boolean inQuotes = false;
                
                for (char c : line.toCharArray()) {
                    if (c == '"') {
                        inQuotes = !inQuotes;
                    } else if (c == delimiter && !inQuotes) {
                        fields.add(currentField.toString());
                        currentField = new StringBuilder();
                    } else {
                        currentField.append(c);
                    }
                }
                fields.add(currentField.toString());
                
                records.add(fields.toArray(new String[0]));
            }
        }
        
        return records;
    }
    
    /**
     * Write CSV file with comma delimiter
     */
    private static void writeCSVFile(Path filePath, List<String[]> records) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(filePath, StandardCharsets.UTF_8)) {
            for (String[] row : records) {
                // Escape fields that contain commas or quotes
                String[] escapedRow = new String[row.length];
                for (int i = 0; i < row.length; i++) {
                    if (row[i].contains(",") || row[i].contains("\"") || row[i].contains("\n")) {
                        escapedRow[i] = "\"" + row[i].replace("\"", "\"\"") + "\"";
                    } else {
                        escapedRow[i] = row[i];
                    }
                }
                writer.write(String.join(",", escapedRow));
                writer.newLine();
            }
        }
    }
}