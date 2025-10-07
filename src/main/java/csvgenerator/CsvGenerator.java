
package csvgenerator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * CSV Generator for Vietnamese administrative divisions
 * Generates province.csv, district.csv, and ward.csv from provinces.open-api.vn.json
 */
public class CsvGenerator {
    
    private static final String INPUT_FILE = "provinces.open-api.vn.json";
    private static final String OUTPUT_DIR = "csv_output";
    private static final String SEPARATOR = ";";
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    
    private final ObjectMapper objectMapper;
    private final String currentDateTime;
    
    public CsvGenerator() {
        this.objectMapper = new ObjectMapper();
        this.currentDateTime = LocalDateTime.now().format(DATE_FORMAT);
    }
    
    public static void main(String[] args) {
        CsvGenerator generator = new CsvGenerator();
        try {
            generator.generateCsvFiles();
            System.out.println("CSV files generated successfully!");
        } catch (Exception e) {
            System.err.println("Error generating CSV files: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    public void generateCsvFiles() throws IOException {
        // Create output directory if it doesn't exist
        File outputDir = new File(OUTPUT_DIR);
        if (!outputDir.exists()) {
            outputDir.mkdirs();
        }
        
        // Read and parse JSON file
        JsonNode rootNode = objectMapper.readTree(new File(INPUT_FILE));
        
        // Generate CSV files
        generateProvincesCsv(rootNode);
        generateDistrictsCsv(rootNode);
        generateWardsCsv(rootNode);
    }
    
    private void generateProvincesCsv(JsonNode provinces) throws IOException {
        String fileName = OUTPUT_DIR + "/province.csv";
        try (PrintWriter writer = new PrintWriter(new FileWriter(fileName))) {
            // Write header based on changelog schema
            writer.println("id" + SEPARATOR + "province_code" + SEPARATOR + "name" + SEPARATOR + 
                          "name_en" + SEPARATOR + "full_name" + SEPARATOR + "full_name_en" + SEPARATOR + 
                          "code_name" + SEPARATOR + "administrative_unit_id" + SEPARATOR + 
                          "administrative_region_id" + SEPARATOR + "created_at" + SEPARATOR + 
                          "updated_at" + SEPARATOR + "is_deleted" + SEPARATOR + "deleted_at" + SEPARATOR + 
                          "deleted_by");
            
            long provinceId = 1500; // Starting ID as per changelog
            
            for (JsonNode province : provinces) {
                String provinceCode = province.get("code").asText();
                String name = escapeValue(province.get("name").asText());
                String nameEn = ""; // Not available in source data
                String fullName = escapeValue(province.get("name").asText());
                String fullNameEn = "";
                String codeName = escapeValue(province.get("codename").asText());
                String adminUnitId = ""; // Not available in source data
                String adminRegionId = ""; // Not available in source data
                
                writer.println(provinceId + SEPARATOR + provinceCode + SEPARATOR + name + SEPARATOR +
                              nameEn + SEPARATOR + fullName + SEPARATOR + fullNameEn + SEPARATOR +
                              codeName + SEPARATOR + adminUnitId + SEPARATOR + adminRegionId + SEPARATOR +
                              currentDateTime + SEPARATOR + SEPARATOR + "false" + SEPARATOR + SEPARATOR + "\\N");
                
                provinceId++;
            }
        }
        System.out.println("Generated: " + fileName);
    }
    
    private void generateDistrictsCsv(JsonNode provinces) throws IOException {
        String fileName = OUTPUT_DIR + "/district.csv";
        try (PrintWriter writer = new PrintWriter(new FileWriter(fileName))) {
            // Write header based on changelog schema
            writer.println("id" + SEPARATOR + "district_code" + SEPARATOR + "name" + SEPARATOR + 
                          "name_en" + SEPARATOR + "full_name" + SEPARATOR + "full_name_en" + SEPARATOR + 
                          "code_name" + SEPARATOR + "administrative_unit_id" + SEPARATOR + 
                          "created_at" + SEPARATOR + "updated_at" + SEPARATOR + "is_deleted" + SEPARATOR + 
                          "deleted_at" + SEPARATOR + "deleted_by" + SEPARATOR + "province_id");
            
            long districtId = 1500; // Starting ID as per changelog
            long provinceId = 1500;
            
            for (JsonNode province : provinces) {
                JsonNode districts = province.get("districts");
                if (districts != null) {
                    for (JsonNode district : districts) {
                        String districtCode = district.get("code").asText();
                        String name = escapeValue(district.get("name").asText());
                        String nameEn = ""; // Not available in source data
                        String fullName = escapeValue(district.get("name").asText());
                        String fullNameEn = "";
                        String codeName = escapeValue(district.get("codename").asText());
                        String adminUnitId = ""; // Not available in source data
                        
                        writer.println(districtId + SEPARATOR + districtCode + SEPARATOR + name + SEPARATOR +
                                      nameEn + SEPARATOR + fullName + SEPARATOR + fullNameEn + SEPARATOR +
                                      codeName + SEPARATOR + adminUnitId + SEPARATOR +
                                      currentDateTime + SEPARATOR + SEPARATOR + "false" + SEPARATOR +
                                      SEPARATOR + "\\N" + SEPARATOR + provinceId);
                        
                        districtId++;
                    }
                }
                provinceId++;
            }
        }
        System.out.println("Generated: " + fileName);
    }
    
    private void generateWardsCsv(JsonNode provinces) throws IOException {
        String fileName = OUTPUT_DIR + "/ward.csv";
        try (PrintWriter writer = new PrintWriter(new FileWriter(fileName))) {
            // Write header based on changelog schema
            writer.println("id" + SEPARATOR + "ward_code" + SEPARATOR + "name" + SEPARATOR + 
                          "name_en" + SEPARATOR + "full_name" + SEPARATOR + "full_name_en" + SEPARATOR + 
                          "code_name" + SEPARATOR + "administrative_unit_id" + SEPARATOR + 
                          "created_at" + SEPARATOR + "updated_at" + SEPARATOR + "is_deleted" + SEPARATOR + 
                          "deleted_at" + SEPARATOR + "deleted_by" + SEPARATOR + "district_id");
            
            long wardId = 1500; // Starting ID as per changelog
            long districtId = 1500;
            
            for (JsonNode province : provinces) {
                JsonNode districts = province.get("districts");
                if (districts != null) {
                    for (JsonNode district : districts) {
                        JsonNode wards = district.get("wards");
                        if (wards != null) {
                            for (JsonNode ward : wards) {
                                String wardCode = ward.get("code").asText();
                                String name = escapeValue(ward.get("name").asText());
                                String nameEn = ""; // Not available in source data
                                String fullName = escapeValue(ward.get("name").asText());
                                String fullNameEn = "";
                                String codeName = escapeValue(ward.get("codename").asText());
                                String adminUnitId = ""; // Not available in source data
                                
                                writer.println(wardId + SEPARATOR + wardCode + SEPARATOR + name + SEPARATOR +
                                              nameEn + SEPARATOR + fullName + SEPARATOR + fullNameEn + SEPARATOR +
                                              codeName + SEPARATOR + adminUnitId + SEPARATOR +
                                              currentDateTime + SEPARATOR + SEPARATOR + "false" + SEPARATOR +
                                              SEPARATOR + "\\N" + SEPARATOR + districtId);
                                
                                wardId++;
                            }
                        }
                        districtId++;
                    }
                }
            }
        }
        System.out.println("Generated: " + fileName);
    }
    
    private String escapeValue(String value) {
        if (value == null) return "";
        // Escape semicolons and quotes for CSV format
        return value.replace(SEPARATOR, "\\;").replace("\"", "\\\"");
    }
}
