// BomEsnConfigPreprocessing.java - STREAMING VERSION (Fixed Import)
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;  // ← ADD THIS IMPORT
import java.nio.charset.StandardCharsets;

public class BomEsnConfigPreprocessing {
    
    private static final String INPUT_FOLDER = "input_bom_config";
    private static final String OUTPUT_FOLDER = "data";
    private static final String OUTPUT_FILENAME = "Csv_bom_esn_config.csv";
    
    public static void main(String[] args) {
        try {
            Files.createDirectories(Paths.get(OUTPUT_FOLDER));
            
            System.out.println("Processing files and combining all records");
            System.out.println("Output will be saved to: " + Paths.get(OUTPUT_FOLDER, OUTPUT_FILENAME) + "\n");
            
            // Find all CSV files
            List<Path> csvFiles = new ArrayList<>();
            try (var stream = Files.walk(Paths.get(INPUT_FOLDER), 1)) {
                csvFiles = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().toLowerCase().endsWith(".csv"))
                    .collect(Collectors.toList());  // ← Now this will work
            }
            
            if (csvFiles.isEmpty()) {
                System.out.println("No CSV files found!");
                return;
            }
            
            System.out.println("Found " + csvFiles.size() + " file(s) to process\n");
            
            Path outputPath = Paths.get(OUTPUT_FOLDER, OUTPUT_FILENAME);
            boolean fileExists = Files.exists(outputPath);
            
            // Process each CSV file using streaming (no memory issues!)
            for (int i = 0; i < csvFiles.size(); i++) {
                Path csvFile = csvFiles.get(i);
                System.out.println("[" + (i+1) + "/" + csvFiles.size() + "] Processing: " + csvFile.getFileName());
                
                try {
                    char delimiter = detectDelimiter(csvFile);
                    System.out.println("   Detected delimiter: " + (delimiter == '|' ? "PIPE (|)" : "COMMA (,)"));
                    
                    long rowsProcessed = processFileStreaming(csvFile, delimiter, outputPath, fileExists);
                    
                    System.out.println("   ✅ Processed " + rowsProcessed + " rows");
                    fileExists = true; // After first file, file exists
                    
                } catch (Exception e) {
                    System.out.println("   ERROR: " + e.getMessage());
                    e.printStackTrace();
                }
                
                System.out.println();
            }
            
            // Get file size
            long fileSizeMB = Files.size(outputPath) / (1024 * 1024);
            System.out.println("\n✅ Combined file saved: " + OUTPUT_FILENAME + " (" + fileSizeMB + " MB)");
            System.out.println("   Output folder: " + OUTPUT_FOLDER);
            System.out.println("\nAll done!");
            
        } catch (IOException e) {
            System.err.println("Fatal error: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    private static long processFileStreaming(Path inputFile, char delimiter, Path outputFile, boolean append) throws IOException {
        long rowCount = 0;
        boolean isFirstFile = !append;
        boolean headerWritten = append;
        
        try (BufferedReader reader = Files.newBufferedReader(inputFile, StandardCharsets.UTF_8);
             BufferedWriter writer = Files.newBufferedWriter(outputFile, StandardCharsets.UTF_8, 
                 append ? StandardOpenOption.APPEND : StandardOpenOption.CREATE)) {
            
            String line;
            String[] header = null;
            int engineSerialIdx = -1;
            int designConfigIdx = -1;
            int buildYearIdx = -1;
            boolean isHeader = true;
            long lineNumber = 0;
            
            System.out.println("   Streaming file (processing row by row)...");
            
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                
                // Skip empty lines
                if (line.trim().isEmpty()) continue;
                
                // Parse line
                String[] fields = parseLine(line, delimiter);
                
                if (isHeader) {
                    isHeader = false;
                    header = fields;
                    
                    // Find column indices
                    for (int j = 0; j < header.length; j++) {
                        String col = header[j].trim().toLowerCase();
                        if (col.equals("engine_serial_num")) engineSerialIdx = j;
                        else if (col.equals("design_config_num")) designConfigIdx = j;
                        else if (col.equals("build_year")) buildYearIdx = j;
                    }
                    
                    // Check if we found all required columns
                    if (engineSerialIdx == -1 || designConfigIdx == -1 || buildYearIdx == -1) {
                        throw new IOException("Required columns not found in header: " + String.join(", ", header));
                    }
                    
                    // Write header if this is the first file
                    if (isFirstFile) {
                        writer.write("Engine_serial,design_config_2,build_year");
                        writer.newLine();
                        headerWritten = true;
                        System.out.println("   Header written: Engine_serial,design_config_2,build_year");
                    }
                    continue;
                }
                
                // Process data row
                String engineSerial = "";
                String designConfig = "";
                String buildYear = "";
                
                if (engineSerialIdx >= 0 && fields.length > engineSerialIdx) {
                    engineSerial = fields[engineSerialIdx].trim();
                }
                if (designConfigIdx >= 0 && fields.length > designConfigIdx) {
                    designConfig = fields[designConfigIdx].trim();
                }
                if (buildYearIdx >= 0 && fields.length > buildYearIdx) {
                    buildYear = fields[buildYearIdx].trim();
                }
                
                // Write row (KEEP ALL RECORDS - no filtering)
                writer.write(escapeField(engineSerial) + "," + 
                            escapeField(designConfig) + "," + 
                            escapeField(buildYear));
                writer.newLine();
                
                rowCount++;
                
                // Progress indicator every 100,000 rows
                if (rowCount % 100000 == 0) {
                    System.out.println("   Progress: " + rowCount + " rows processed...");
                }
            }
        }
        
        return rowCount;
    }
    
    private static String[] parseLine(String line, char delimiter) {
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
        
        return fields.toArray(new String[0]);
    }
    
    private static String escapeField(String field) {
        if (field == null) return "";
        if (field.contains(",") || field.contains("\"") || field.contains("\n")) {
            return "\"" + field.replace("\"", "\"\"") + "\"";
        }
        return field;
    }
    
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
}