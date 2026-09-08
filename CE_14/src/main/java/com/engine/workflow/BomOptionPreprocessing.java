// BomOptionPreprocessing.java
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.Collectors;
import java.nio.charset.StandardCharsets;

public class BomOptionPreprocessing {
    
    private static final String INPUT_FOLDER = "input_bom_option";
    private static final String OUTPUT_FOLDER = "data";
    private static final String OUTPUT_FILENAME_PREFIX = "Csv_bom_esn_option_";
    
    // Column mapping (old_name -> new_name)
    private static final Map<String, String> COLUMN_MAPPING = new HashMap<>();
    static {
        COLUMN_MAPPING.put("engine_serial_num", "Engine_serial");
        COLUMN_MAPPING.put("build_upfit_date", "build_year_2");
    }
    
    // Define the columns to keep with their new names
    private static final List<String> COLUMNS_TO_KEEP = Arrays.asList(
        "design_configuration",
        "shop_order_num",
        "Engine_serial",
        "option_assembly_num",
        "build_year_2"
    );
    
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
            
            // Map to store records grouped by year-month category
            Map<String, List<String[]>> groupedRecords = new HashMap<>();
            Map<String, String[]> headerMap = new HashMap<>();
            Map<String, Integer> fileCounter = new HashMap<>();
            
            // Process each CSV file
            for (int i = 0; i < csvFiles.size(); i++) {
                Path csvFile = csvFiles.get(i);
                System.out.println("[" + (i+1) + "/" + csvFiles.size() + "] Processing: " + csvFile.getFileName());
                
                try {
                    // 1. Auto-detect delimiter (pipe or comma)
                    char delimiter = detectDelimiter(csvFile);
                    System.out.println("   Detected delimiter: " + (delimiter == '|' ? "PIPE (|)" : "COMMA (,)"));
                    
                    // 2. Read the CSV file with detected delimiter
                    List<String[]> fileRecords = readCSVFile(csvFile, delimiter);
                    
                    if (fileRecords.isEmpty()) {
                        System.out.println("   File is empty. Skipping...");
                        continue;
                    }
                    
                    // 3. Get header and find column indices
                    String[] fileHeader = fileRecords.get(0);
                    Map<String, Integer> columnIndexMap = new HashMap<>();
                    for (int j = 0; j < fileHeader.length; j++) {
                        columnIndexMap.put(fileHeader[j].trim(), j);
                    }
                    
                    // 4. Rename specific columns (apply column mapping)
                    Map<String, Integer> mappedIndexMap = new HashMap<>();
                    for (Map.Entry<String, String> entry : COLUMN_MAPPING.entrySet()) {
                        String oldName = entry.getKey();
                        String newName = entry.getValue();
                        if (columnIndexMap.containsKey(oldName)) {
                            mappedIndexMap.put(newName, columnIndexMap.get(oldName));
                        }
                    }
                    
                    // Keep only the columns we want
                    Map<String, Integer> keepIndexMap = new HashMap<>();
                    List<String> existingCols = new ArrayList<>();
                    List<String> missingCols = new ArrayList<>();
                    
                    for (String col : COLUMNS_TO_KEEP) {
                        // First try the mapped name (if it was renamed)
                        Integer index = null;
                        if (mappedIndexMap.containsKey(col)) {
                            index = mappedIndexMap.get(col);
                        } 
                        // Then try the original name
                        else if (columnIndexMap.containsKey(col)) {
                            index = columnIndexMap.get(col);
                        }
                        // Try case-insensitive match
                        else {
                            for (String key : columnIndexMap.keySet()) {
                                if (key.equalsIgnoreCase(col)) {
                                    index = columnIndexMap.get(key);
                                    break;
                                }
                            }
                        }
                        
                        if (index != null) {
                            keepIndexMap.put(col, index);
                            existingCols.add(col);
                        } else {
                            missingCols.add(col);
                        }
                    }
                    
                    if (missingCols.isEmpty() && !existingCols.isEmpty()) {
                        System.out.println("   Existing columns: " + existingCols);
                    } else {
                        System.out.println("   Warning: Missing columns: " + missingCols);
                        System.out.println("   Available columns: " + String.join(", ", fileHeader));
                    }
                    
                    if (keepIndexMap.isEmpty()) {
                        System.out.println("   No columns to keep. Skipping...");
                        continue;
                    }
                    
                    // 5. Extract year and month from filename
                    String year = extractYearFromFilename(csvFile.getFileName().toString());
                    String month = extractMonthFromFilename(csvFile.getFileName().toString());
                    
                    // If year not found in filename, try to extract from data
                    if (year == null && keepIndexMap.containsKey("build_year_2")) {
                        // Try to get year from first non-null value in build_year_2
                        for (int row = 1; row < fileRecords.size(); row++) {
                            String[] rowData = fileRecords.get(row);
                            int buildYearIdx = keepIndexMap.get("build_year_2");
                            if (rowData.length > buildYearIdx && !rowData[buildYearIdx].trim().isEmpty()) {
                                String yearStr = rowData[buildYearIdx].trim();
                                Matcher yearMatcher = Pattern.compile("(19|20)\\d{2}").matcher(yearStr);
                                if (yearMatcher.find()) {
                                    year = yearMatcher.group();
                                    break;
                                }
                            }
                        }
                    }
                    
                    // If still no year, use default
                    if (year == null) {
                        year = "XXXX";
                        System.out.println("   Could not determine year, using default: " + year);
                    }
                    
                    // If no month found, use default
                    if (month == null) {
                        month = "01"; // Default to January
                        System.out.println("   Could not determine month, using default: " + month);
                    }
                    
                    // Determine the month group category
                    int monthInt = Integer.parseInt(month);
                    String monthCategory;
                    if (monthInt >= 1 && monthInt <= 6) {
                        monthCategory = "01";  // First half of year (Jan-Jun)
                    } else if (monthInt >= 7 && monthInt <= 12) {
                        monthCategory = "02";  // Second half of year (Jul-Dec)
                    } else {
                        monthCategory = "01";  // Default to first half if invalid month
                        System.out.println("   Invalid month: " + month + ", using default category: 01");
                    }
                    
                    // Create group key for this year-month category
                    String groupKey = year + "_" + monthCategory;
                    
                    // Initialize records for this group if not exists
                    if (!groupedRecords.containsKey(groupKey)) {
                        groupedRecords.put(groupKey, new ArrayList<>());
                        headerMap.put(groupKey, existingCols.toArray(new String[0]));
                        fileCounter.put(groupKey, 0);
                    }
                    
                    // Process data rows (skip header)
                    int recordsAdded = 0;
                    for (int row = 1; row < fileRecords.size(); row++) {
                        String[] rowData = fileRecords.get(row);
                        String[] newRow = new String[existingCols.size()];
                        boolean hasData = false;
                        
                        for (int colIdx = 0; colIdx < existingCols.size(); colIdx++) {
                            String colName = existingCols.get(colIdx);
                            Integer dataIdx = keepIndexMap.get(colName);
                            if (dataIdx != null && rowData.length > dataIdx) {
                                String value = rowData[dataIdx].trim();
                                newRow[colIdx] = value;
                                if (!value.isEmpty()) {
                                    hasData = true;
                                }
                            } else {
                                newRow[colIdx] = "";
                            }
                        }
                        
                        // Only add if at least one field has data
                        if (hasData) {
                            groupedRecords.get(groupKey).add(newRow);
                            recordsAdded++;
                        }
                    }
                    
                    fileCounter.put(groupKey, fileCounter.get(groupKey) + 1);
                    
                    System.out.println("   Kept columns: " + existingCols);
                    System.out.println("   Records in this file: " + recordsAdded);
                    System.out.println("   Year: " + year + ", Month: " + month + ", Category: " + monthCategory);
                    System.out.println("   Group: " + groupKey);
                    
                } catch (Exception e) {
                    System.out.println("   ERROR processing " + csvFile.getFileName() + ": " + e.getMessage());
                    e.printStackTrace();
                }
                
                System.out.println();
            }
            
            // --- Combine and Save with Append ---
            if (!groupedRecords.isEmpty()) {
                System.out.println("\n=== Combining and Saving Files ===");
                System.out.println("Found " + groupedRecords.size() + " year-month category group(s):");
                System.out.println("  - Month 01 = January-June (months 1-6)");
                System.out.println("  - Month 02 = July-December (months 7-12)\n");
                
                for (String groupKey : groupedRecords.keySet()) {
                    System.out.println("Processing group: " + groupKey);
                    
                    String outputFilename = OUTPUT_FILENAME_PREFIX + groupKey + ".csv";
                    Path outputPath = Paths.get(OUTPUT_FOLDER, outputFilename);
                    
                    // Check if output file already exists
                    List<String[]> existingRecords = new ArrayList<>();
                    boolean fileExists = Files.exists(outputPath);
                    
                    if (fileExists) {
                        System.out.println("   📁 Found existing file: " + outputFilename);
                        System.out.println("   Appending new data to existing file...");
                        
                        // Read existing file
                        char delimiter = detectDelimiter(outputPath);
                        List<String[]> existingFileRecords = readCSVFile(outputPath, delimiter);
                        
                        if (!existingFileRecords.isEmpty()) {
                            String[] existingHeader = existingFileRecords.get(0);
                            String[] currentHeader = headerMap.get(groupKey);
                            
                            // Check if headers match
                            if (Arrays.equals(existingHeader, currentHeader)) {
                                // Add existing data records (skip header)
                                for (int row = 1; row < existingFileRecords.size(); row++) {
                                    String[] rowData = existingFileRecords.get(row);
                                    if (rowData.length >= currentHeader.length) {
                                        existingRecords.add(rowData);
                                    }
                                }
                                System.out.println("   Existing records: " + existingRecords.size());
                            } else {
                                System.out.println("   ⚠️  Existing file has different format. Will overwrite.");
                                fileExists = false; // Overwrite if format differs
                            }
                        }
                    }
                    
                    // Combine: Header + Existing Records + New Records
                    List<String[]> combinedRecords = new ArrayList<>();
                    String[] header = headerMap.get(groupKey);
                    
                    // Add header
                    combinedRecords.add(header);
                    
                    // Add existing records (if file exists and format matches)
                    if (fileExists && !existingRecords.isEmpty()) {
                        combinedRecords.addAll(existingRecords);
                    }
                    
                    // Add new records from this group
                    List<String[]> newRecords = groupedRecords.get(groupKey);
                    combinedRecords.addAll(newRecords);
                    
                    // Write combined file
                    writeCSVFile(outputPath, combinedRecords);
                    
                    int totalRows = combinedRecords.size() - 1; // Excluding header
                    int newRowsAdded = newRecords.size();
                    int existingRows = fileExists ? existingRecords.size() : 0;
                    
                    System.out.println("   ✅ Saved to: " + outputFilename);
                    System.out.println("   Total rows in combined file: " + totalRows);
                    System.out.println("   Existing rows: " + existingRows);
                    System.out.println("   New rows added: " + newRowsAdded);
                    
                    // Get file size
                    long fileSize = Files.size(outputPath);
                    System.out.println("   File size: " + (fileSize / 1024) + " KB");
                }
                
                System.out.println("\n=== Summary ===");
                System.out.println("Total groups processed: " + groupedRecords.size());
                System.out.println("All files saved to: " + OUTPUT_FOLDER);
                System.out.println("\nMonth grouping rules:");
                System.out.println("  - Months 1-6 (Jan-Jun) → _01");
                System.out.println("  - Months 7-12 (Jul-Dec) → _02");
                
            } else {
                System.out.println("No data was read from any file!");
            }
            
            System.out.println("\nAll done! Check the 'data' folder for processed files.");
            
        } catch (IOException e) {
            System.err.println("Fatal error: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Extract year from filename (e.g., 2016, 2017, etc.)
     */
    private static String extractYearFromFilename(String filename) {
        // Look for 4-digit year in filename
        Matcher yearMatcher = Pattern.compile("(19|20)\\d{2}").matcher(filename);
        if (yearMatcher.find()) {
            return yearMatcher.group();
        }
        return null;
    }
    
    /**
     * Extract month from filename (e.g., 01, 02, ..., 12)
     */
    private static String extractMonthFromFilename(String filename) {
        // Look for 2-digit month after year
        // Pattern like 20260701 where 07 is month
        Matcher monthMatcher = Pattern.compile("(?:19|20)\\d{2}(\\d{2})\\d{2}").matcher(filename);
        if (monthMatcher.find()) {
            String month = monthMatcher.group(1);
            int monthInt = Integer.parseInt(month);
            if (monthInt >= 1 && monthInt <= 12) {
                return month;
            }
        }
        
        // Try pattern like 2016_01, 2016-01
        monthMatcher = Pattern.compile("(?:19|20)\\d{2}[_-](\\d{2})").matcher(filename);
        if (monthMatcher.find()) {
            String month = monthMatcher.group(1);
            int monthInt = Integer.parseInt(month);
            if (monthInt >= 1 && monthInt <= 12) {
                return month;
            }
        }
        
        // Try to find any 2-digit number that could be a month (01-12)
        monthMatcher = Pattern.compile("[_-](\\d{2})[_-]").matcher(filename);
        if (monthMatcher.find()) {
            String month = monthMatcher.group(1);
            int monthInt = Integer.parseInt(month);
            if (monthInt >= 1 && monthInt <= 12) {
                return month;
            }
        }
        
        return null;
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
                return pipeCount > commaCount ? '|' : ',';
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
                // Parse line based on delimiter (handles quoted fields)
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
                // Escape fields that contain commas, quotes, or newlines
                String[] escapedRow = new String[row.length];
                for (int i = 0; i < row.length; i++) {
                    String field = row[i] != null ? row[i] : "";
                    if (field.contains(",") || field.contains("\"") || field.contains("\n")) {
                        escapedRow[i] = "\"" + field.replace("\"", "\"\"") + "\"";
                    } else {
                        escapedRow[i] = field;
                    }
                }
                writer.write(String.join(",", escapedRow));
                writer.newLine();
            }
        }
    }
}