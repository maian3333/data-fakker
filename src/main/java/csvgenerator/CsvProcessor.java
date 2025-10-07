package csvgenerator;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class CsvProcessor {
    
    private static final String INPUT_FILE = "benxe_addresses_with_ward_ids.csv";
    private static final String OUTPUT_DIR = "csv_output";
    private static final String ADDRESS_OUTPUT = OUTPUT_DIR + "/address.csv";
    private static final String STATION_OUTPUT = OUTPUT_DIR + "/station.csv";
    
    public static void main(String[] args) {
        try {
            CsvProcessor processor = new CsvProcessor();
            processor.processData();
            System.out.println("CSV files generated successfully!");
        } catch (Exception e) {
            System.err.println("Error processing CSV: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    public void processData() throws IOException {
        // Create output directory if it doesn't exist
        Files.createDirectories(Paths.get(OUTPUT_DIR));
        
        // Read input data
        List<StationData> stationDataList = readInputFile();
        
        // Generate output files
        generateAddressCsv(stationDataList);
        generateStationCsv(stationDataList);
    }
    
    private List<StationData> readInputFile() throws IOException {
        List<StationData> stationDataList = new ArrayList<>();
        
        try (BufferedReader reader = new BufferedReader(new FileReader(INPUT_FILE))) {
            String line = reader.readLine(); // Skip header
            long addressId = 1500; // Starting ID as per changelog
            long stationId = 1500; // Starting ID as per changelog
            
            while ((line = reader.readLine()) != null) {
                String[] fields = parseCSVLine(line);
                if (fields.length >= 7) {
                    StationData data = new StationData();
                    data.stationSlug = fields[0];
                    data.stationName = fields[1];
                    data.address = fields[2];
                    data.province = fields[3];
                    data.wardId = parseWardId(fields[4]);
                    data.matchedWard = fields[5];
                    data.matchedDistrict = fields[6];
                    data.addressId = addressId++;
                    data.stationId = stationId++;
                    
                    stationDataList.add(data);
                }
            }
        }
        
        return stationDataList;
    }
    
    private String[] parseCSVLine(String line) {
        List<String> fields = new ArrayList<>();
        boolean inQuotes = false;
        StringBuilder currentField = new StringBuilder();
        
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (c == ',' && !inQuotes) {
                fields.add(currentField.toString().trim());
                currentField = new StringBuilder();
            } else {
                currentField.append(c);
            }
        }
        
        fields.add(currentField.toString().trim());
        return fields.toArray(new String[0]);
    }
    
    private Long parseWardId(String wardIdStr) {
        try {
            return wardIdStr.trim().isEmpty() ? null : Long.parseLong(wardIdStr.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
    
    private void generateAddressCsv(List<StationData> stationDataList) throws IOException {
        try (PrintWriter writer = new PrintWriter(new FileWriter(ADDRESS_OUTPUT))) {
            // Write header based on changelog schema
            writer.println("id;street_address;latitude;longitude;created_at;updated_at;is_deleted;deleted_at;deleted_by;ward_id");
            
            String currentTime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            
            for (StationData data : stationDataList) {
                if (data.wardId != null) {
                    writer.printf("%d;%s;%s;%s;%s;%s;%s;%s;%s;%d%n",
                        data.addressId,
                        escapeForCsv(data.address),
                        "", // latitude - empty
                        "", // longitude - empty
                        currentTime,
                        "", // updated_at - empty
                        "false", // is_deleted
                        "", // deleted_at - empty
                        "\\N", // deleted_by - empty
                        data.wardId
                    );
                }
            }
        }
    }
    
    private void generateStationCsv(List<StationData> stationDataList) throws IOException {
        try (PrintWriter writer = new PrintWriter(new FileWriter(STATION_OUTPUT))) {
            // Write header based on changelog schema
            writer.println("id;name;phone_number;description;active;created_at;updated_at;is_deleted;deleted_at;deleted_by;address_id;station_img_id");
            
            String currentTime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            
            for (StationData data : stationDataList) {
                if (data.wardId != null) {
                    writer.printf("%d;%s;%s;%s;%s;%s;%s;%s;%s;%s;%d;%s%n",
                        data.stationId,
                        escapeForCsv(data.stationName),
                        "", // phone_number - empty
                        escapeForCsv("Station in " + data.province), // description
                        "true", // active
                        currentTime,
                        "", // updated_at - empty
                        "false", // is_deleted
                        "", // deleted_at - empty
                        "\\N", // deleted_by - empty
                        data.addressId,
                        "" // station_img_id - empty
                    );
                }
            }
        }
    }
    
    private String escapeForCsv(String value) {
        if (value == null) return "";
        // Replace quotes with double quotes and wrap in quotes if contains semicolon or quotes
        String escaped = value.replace("\"", "\"\"");
        if (escaped.contains(";") || escaped.contains("\"") || escaped.contains("\n")) {
            return "\"" + escaped + "\"";
        }
        return escaped;
    }
    
    private static class StationData {
        String stationSlug;
        String stationName;
        String address;
        String province;
        Long wardId;
        String matchedWard;
        String matchedDistrict;
        long addressId;
        long stationId;
    }
}
