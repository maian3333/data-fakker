import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

public class MergedRouteProcessor {
    
    private static final String BENXE_INPUT_FILE = "tickets_benxe.csv";
    private static final String NHAXE_INPUT_FILE = "tickets_nhaxe.csv";
    private static final String STATION_FILE = "csv_output/station.csv";
    private static final String ADDRESS_FILE = "csv_output/address.csv";
    private static final String DISTRICT_FILE = "csv_output/district.csv";
    private static final String PROVINCE_FILE = "csv_output/province.csv";
    private static final String OUTPUT_DIR = "csv_output";
    private static final String ROUTE_OUTPUT = OUTPUT_DIR + "/route.csv";

    private Map<String, Long> stationNameToId = new HashMap<>();
    private Map<String, Long> provinceToStationId = new HashMap<>();
    private Map<String, List<Long>> provinceToAllStationIds = new HashMap<>();
    private Map<Long, String> addressIdToAddress = new HashMap<>();
    private Map<Long, Long> stationIdToAddressId = new HashMap<>();
    
    // New mappings for district/province lookup (for nhaxe)
    private Map<String, Long> districtNameToId = new HashMap<>();
    private Map<String, Long> provinceNameToId = new HashMap<>();
    private Map<Long, Long> districtToProvinceId = new HashMap<>();
    private Random random = new Random();
    
    public static void main(String[] args) {
        try {
            MergedRouteProcessor processor = new MergedRouteProcessor();
            processor.processRoutes();
            System.out.println("Merged Route CSV file generated successfully!");
        } catch (Exception e) {
            System.err.println("Error processing merged routes: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    public void processRoutes() throws IOException {
        // Create output directory if it doesn't exist
        Files.createDirectories(Paths.get(OUTPUT_DIR));
        
        // Load all mappings
        loadStationMappings();
        loadAddressMappings();
        loadDistrictMappings();
        loadProvinceMappings();
        
        // Process routes from both sources
        Set<RouteData> allRoutes = new HashSet<>();
        
        // Process benxe routes
        System.out.println("Processing benxe routes...");
        Set<RouteData> benxeRoutes = extractBenxeRoutes();
        allRoutes.addAll(benxeRoutes);
        System.out.println("Benxe routes processed: " + benxeRoutes.size());
        
        // Process nhaxe routes
        System.out.println("Processing nhaxe routes...");
        Set<RouteData> nhaxeRoutes = extractNhaxeRoutes();
        allRoutes.addAll(nhaxeRoutes);
        System.out.println("Nhaxe routes processed: " + nhaxeRoutes.size());
        
        System.out.println("Total unique routes: " + allRoutes.size());
        
        // Generate unified route CSV
        generateRouteCsv(allRoutes);
    }
    
    private void loadStationMappings() throws IOException {
        try (BufferedReader reader = new BufferedReader(new FileReader(STATION_FILE))) {
            String line = reader.readLine(); // Skip header
            
            while ((line = reader.readLine()) != null) {
                String[] fields = line.split(";");
                if (fields.length >= 4) {
                    Long stationId = Long.parseLong(fields[0]);
                    String stationName = fields[1];
                    String description = fields[3];
                    
                    // Map station name to ID
                    stationNameToId.put(stationName, stationId);
                    
                    // Extract province from description "Station in [Province]"
                    if (description.startsWith("Station in ")) {
                        String province = description.substring("Station in ".length());
                        // Use the first station found for each province as default destination
                        if (!provinceToStationId.containsKey(province)) {
                            provinceToStationId.put(province, stationId);
                        }

                        // Store all stations for each province
                        provinceToAllStationIds.computeIfAbsent(province, k -> new ArrayList<>()).add(stationId);
                    }
                }
            }
        }

        // Store station to address mapping
        try (BufferedReader reader = new BufferedReader(new FileReader(STATION_FILE))) {
            String line = reader.readLine(); // Skip header

            while ((line = reader.readLine()) != null) {
                String[] fields = line.split(";");
                if (fields.length >= 11) {
                    try {
                        Long stationId = Long.parseLong(fields[0]);
                        // Check if field 10 has address_id, otherwise assume address_id = station_id
                        Long addressId;
                        if (fields.length > 10 && !fields[10].trim().isEmpty()) {
                            addressId = Long.parseLong(fields[10]);
                        } else {
                            // Fallback: assume address_id matches station_id
                            addressId = stationId;
                        }
                        stationIdToAddressId.put(stationId, addressId);
                    } catch (NumberFormatException e) {
                        // Skip if IDs are not valid numbers
                    }
                }
            }
        }
    }

    private void loadAddressMappings() throws IOException {
        try (BufferedReader reader = new BufferedReader(new FileReader(ADDRESS_FILE))) {
            String line = reader.readLine(); // Skip header

            while ((line = reader.readLine()) != null) {
                String[] fields = line.split(";");
                if (fields.length >= 2) {
                    try {
                        Long addressId = Long.parseLong(fields[0]);
                        String address = fields[1];
                        addressIdToAddress.put(addressId, address);
                    } catch (NumberFormatException e) {
                        // Skip if address_id is not a valid number
                    }
                }
            }
        }
    }
    
    private void loadDistrictMappings() throws IOException {
        try (BufferedReader reader = new BufferedReader(new FileReader(DISTRICT_FILE))) {
            String line = reader.readLine(); // Skip header

            while ((line = reader.readLine()) != null) {
                String[] fields = line.split(";");
                if (fields.length >= 14) {
                    try {
                        Long districtId = Long.parseLong(fields[0]);
                        String districtName = fields[2]; // name field
                        Long provinceId = Long.parseLong(fields[13]); // province_id field
                        
                        districtNameToId.put(districtName, districtId);
                        districtToProvinceId.put(districtId, provinceId);
                    } catch (NumberFormatException e) {
                        // Skip if IDs are not valid numbers
                    }
                }
            }
        }
    }
    
    private void loadProvinceMappings() throws IOException {
        try (BufferedReader reader = new BufferedReader(new FileReader(PROVINCE_FILE))) {
            String line = reader.readLine(); // Skip header

            while ((line = reader.readLine()) != null) {
                String[] fields = line.split(";");
                if (fields.length >= 3) {
                    try {
                        Long provinceId = Long.parseLong(fields[0]);
                        String provinceName = fields[2]; // name field
                        
                        provinceNameToId.put(provinceName, provinceId);
                    } catch (NumberFormatException e) {
                        // Skip if IDs are not valid numbers
                    }
                }
            }
        }
    }
    
    private Set<RouteData> extractBenxeRoutes() throws IOException {
        Set<RouteData> uniqueRoutes = new HashSet<>();
        Pattern routePattern = Pattern.compile("^([^|]+)\\s*\\|");
        int totalLines = 0;
        int matchedLines = 0;
        int parsedRoutes = 0;
        int skippedRoutes = 0;

        try (BufferedReader reader = new BufferedReader(new FileReader(BENXE_INPUT_FILE))) {
            String line = reader.readLine(); // Skip header

            while ((line = reader.readLine()) != null) {
                totalLines++;
                Matcher matcher = routePattern.matcher(line);
                if (matcher.find()) {
                    matchedLines++;
                    String routeInfo = matcher.group(1).trim();
                    RouteData route = parseBenxeRouteInfo(routeInfo);
                    if (route != null) {
                        uniqueRoutes.add(route);
                        parsedRoutes++;
                    } else {
                        skippedRoutes++;
                    }
                }
            }
        }

        System.out.println("=== BENXE PROCESSING SUMMARY ===");
        System.out.println("Total lines processed: " + totalLines);
        System.out.println("Lines matching pattern: " + matchedLines);
        System.out.println("Successfully parsed routes: " + parsedRoutes);
        System.out.println("Skipped routes: " + skippedRoutes);
        System.out.println("Unique routes generated: " + uniqueRoutes.size());

        return uniqueRoutes;
    }
    
    private Set<RouteData> extractNhaxeRoutes() throws IOException {
        Set<RouteData> uniqueRoutes = new HashSet<>();
        Pattern routePattern = Pattern.compile("^\\[.*?\\]\\s*([^|]+)\\s*\\|");
        int totalLines = 0;
        int matchedLines = 0;
        int parsedRoutes = 0;
        int skippedRoutes = 0;

        try (BufferedReader reader = new BufferedReader(new FileReader(NHAXE_INPUT_FILE))) {
            String line = reader.readLine(); // Skip header

            while ((line = reader.readLine()) != null) {
                totalLines++;
                Matcher matcher = routePattern.matcher(line);
                if (matcher.find()) {
                    matchedLines++;
                    String routeInfo = matcher.group(1).trim();
                    RouteData route = parseNhaxeRouteInfo(routeInfo);
                    if (route != null) {
                        uniqueRoutes.add(route);
                        parsedRoutes++;
                    } else {
                        skippedRoutes++;
                    }
                }
            }
        }

        System.out.println("=== NHAXE PROCESSING SUMMARY ===");
        System.out.println("Total lines processed: " + totalLines);
        System.out.println("Lines matching pattern: " + matchedLines);
        System.out.println("Successfully parsed routes: " + parsedRoutes);
        System.out.println("Skipped routes: " + skippedRoutes);
        System.out.println("Unique routes generated: " + uniqueRoutes.size());

        return uniqueRoutes;
    }

    private RouteData parseBenxeRouteInfo(String routeInfo) {
        // Pattern: "Origin đi Destination"
        String[] parts = routeInfo.split(" đi ");
        if (parts.length == 2) {
            String origin = parts[0].trim();
            String destination = parts[1].trim();

            Long originId = findStationIdByName(origin);
            Long destinationId = findStationIdByName(destination);

            if (originId != null && destinationId != null) {
                RouteData route = new RouteData();
                route.originName = origin;
                route.destinationName = destination;
                route.originId = originId;
                route.destinationId = destinationId;
                route.routeCode = generateRouteCode(origin, destination);
                route.source = "benxe";
                return route;
            }
        }
        return null;
    }

    private RouteData parseNhaxeRouteInfo(String routeInfo) {
        // Pattern: "District - Province đi District - Province"
        String[] parts = null;
        if (routeInfo.contains(" đi ")) {
            parts = routeInfo.split(" đi ");
        } else if (routeInfo.contains(" - ")) {
            // Handle case where there's no "đi" but just " - " separator
            String[] tempParts = routeInfo.split(" - ");
            if (tempParts.length >= 4) {
                // Assume format: District1 - Province1 - District2 - Province2
                parts = new String[2];
                parts[0] = tempParts[0] + " - " + tempParts[1];
                parts[1] = tempParts[2] + " - " + tempParts[3];
            }
        }

        if (parts != null && parts.length == 2) {
            String originInfo = parts[0].trim();
            String destinationInfo = parts[1].trim();

            Long originId = findStationIdByLocation(originInfo);
            Long destinationId = findStationIdByLocation(destinationInfo);

            if (originId != null && destinationId != null) {
                RouteData route = new RouteData();
                route.originName = originInfo;
                route.destinationName = destinationInfo;
                route.originId = originId;
                route.destinationId = destinationId;
                route.routeCode = generateRouteCode(originInfo, destinationInfo);
                route.source = "nhaxe";
                return route;
            }
        }
        return null;
    }

    private Long findStationIdByName(String stationName) {
        // Special handling for "Sài Gòn" - randomly select from Hồ Chí Minh stations
        if (stationName.equals("Sài Gòn")) {
            List<Long> hcmStations = provinceToAllStationIds.get("Hồ Chí Minh");
            if (hcmStations != null && !hcmStations.isEmpty()) {
                return hcmStations.get(random.nextInt(hcmStations.size()));
            }
        }

        // Direct station name match
        if (stationNameToId.containsKey(stationName)) {
            return stationNameToId.get(stationName);
        }

        // Try to find by province
        if (provinceToStationId.containsKey(stationName)) {
            return provinceToStationId.get(stationName);
        }

        // Check addresses for station name matches
        for (Map.Entry<Long, Long> stationToAddress : stationIdToAddressId.entrySet()) {
            Long candidateStationId = stationToAddress.getKey();
            Long addressId = stationToAddress.getValue();
            String address = addressIdToAddress.get(addressId);

            if (address != null && address.toLowerCase().contains(stationName.toLowerCase())) {
                return candidateStationId;
            }
        }

        return null;
    }

    private Long findStationIdByLocation(String locationInfo) {
        // Special handling for "Sài Gòn" - randomly select from Hồ Chí Minh stations
        if (locationInfo.equals("Sài Gòn")) {
            List<Long> hcmStations = provinceToAllStationIds.get("Hồ Chí Minh");
            if (hcmStations != null && !hcmStations.isEmpty()) {
                return hcmStations.get(random.nextInt(hcmStations.size()));
            }
        }

        // Parse "District - Province" format
        String[] locationParts = locationInfo.split(" - ");
        if (locationParts.length == 2) {
            String district = locationParts[0].trim();
            String province = locationParts[1].trim();

            // First try to find station by district name
            Long stationId = findStationByDistrict(district, province);
            if (stationId != null) {
                return stationId;
            }

            // If district not found, try to find by province
            stationId = findStationByProvince(province);
            if (stationId != null) {
                return stationId;
            }
        } else if (locationParts.length == 1) {
            // Only one part, could be district or province
            String location = locationParts[0].trim();

            // Special handling for "Sài Gòn"
            if (location.equals("Sài Gòn")) {
                List<Long> hcmStations = provinceToAllStationIds.get("Hồ Chí Minh");
                if (hcmStations != null && !hcmStations.isEmpty()) {
                    return hcmStations.get(random.nextInt(hcmStations.size()));
                }
            }

            // Try district first
            Long stationId = findStationByDistrict(location, null);
            if (stationId != null) {
                return stationId;
            }

            // Then try province
            stationId = findStationByProvince(location);
            if (stationId != null) {
                return stationId;
            }
        }

        return null;
    }

    private Long findStationByDistrict(String district, String province) {
        // Try exact district name match
        if (districtNameToId.containsKey(district)) {
            Long districtId = districtNameToId.get(district);
            Long provinceId = districtToProvinceId.get(districtId);

            // If province is specified, verify it matches
            if (province != null) {
                Long expectedProvinceId = findProvinceIdByName(province);
                if (expectedProvinceId != null && !provinceId.equals(expectedProvinceId)) {
                    return null; // District-Province mismatch
                }
            }

            // Find station in this district/province
            return findStationInProvince(provinceId);
        }

        // Try partial district name matching (handle prefixes like "Thị xã", "Huyện", etc.)
        for (Map.Entry<String, Long> entry : districtNameToId.entrySet()) {
            String fullDistrictName = entry.getKey();
            // Check if the full name contains the district name (e.g., "Thị xã Trảng Bàng" contains "Trảng Bàng")
            if (fullDistrictName.contains(district) || district.contains(fullDistrictName)) {
                Long districtId = entry.getValue();
                Long provinceId = districtToProvinceId.get(districtId);

                if (province != null) {
                    Long expectedProvinceId = findProvinceIdByName(province);
                    if (expectedProvinceId != null && !provinceId.equals(expectedProvinceId)) {
                        continue; // District-Province mismatch, try next
                    }
                }

                return findStationInProvince(provinceId);
            }
        }

        return null;
    }

    private Long findProvinceIdByName(String province) {
        // Try exact province name match
        if (provinceNameToId.containsKey(province)) {
            return provinceNameToId.get(province);
        }

        // Try partial province name matching (handle prefixes like "Tỉnh", "Thành phố", etc.)
        for (Map.Entry<String, Long> entry : provinceNameToId.entrySet()) {
            String fullProvinceName = entry.getKey();
            // Check if the full name contains the province name (e.g., "Tỉnh Tây Ninh" contains "Tây Ninh")
            if (fullProvinceName.contains(province) || province.contains(fullProvinceName)) {
                return entry.getValue();
            }
        }

        return null;
    }

    private Long findStationByProvince(String province) {
        // Direct province match from station mappings
        if (provinceToStationId.containsKey(province)) {
            return provinceToStationId.get(province);
        }

        // Try partial province matching (handle prefixes like "Tỉnh", "Thành phố", etc.)
        for (Map.Entry<String, Long> entry : provinceToStationId.entrySet()) {
            String fullProvinceName = entry.getKey();
            if (fullProvinceName.contains(province) || province.contains(fullProvinceName)) {
                return entry.getValue();
            }
        }

        // Check addresses for province name matches
        for (Map.Entry<Long, Long> stationToAddress : stationIdToAddressId.entrySet()) {
            Long candidateStationId = stationToAddress.getKey();
            Long addressId = stationToAddress.getValue();
            String address = addressIdToAddress.get(addressId);

            if (address != null && address.toLowerCase().contains(province.toLowerCase())) {
                return candidateStationId;
            }
        }

        return null;
    }

    private Long findStationInProvince(Long provinceId) {
        // Find province name by ID
        String provinceName = null;
        for (Map.Entry<String, Long> entry : provinceNameToId.entrySet()) {
            if (entry.getValue().equals(provinceId)) {
                provinceName = entry.getKey();
                break;
            }
        }

        if (provinceName != null && provinceToStationId.containsKey(provinceName)) {
            return provinceToStationId.get(provinceName);
        }

        return null;
    }

    private String generateRouteCode(String origin, String destination) {
        // Generate a simple route code based on origin and destination
        String originCode = origin.replaceAll("[^a-zA-Z0-9]", "").toUpperCase();
        String destCode = destination.replaceAll("[^a-zA-Z0-9]", "").toUpperCase();

        // Limit length to avoid very long codes
        if (originCode.length() > 10) originCode = originCode.substring(0, 10);
        if (destCode.length() > 10) destCode = destCode.substring(0, 10);

        return originCode + "_" + destCode;
    }

    private void generateRouteCsv(Set<RouteData> routes) throws IOException {
        try (PrintWriter writer = new PrintWriter(new FileWriter(ROUTE_OUTPUT))) {
            // Write header based on changelog schema
            writer.println("id;route_code;distance_km;created_at;updated_at;is_deleted;deleted_at;deleted_by;origin_id;destination_id");

            String currentTime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            long routeId = 1000; // Starting ID for merged routes

            for (RouteData route : routes) {
                writer.printf("%d;%s;%s;%s;%s;%s;%s;%s;%d;%d%n",
                    routeId++,
                    escapeForCsv(route.routeCode),
                    "", // distance_km - empty for now
                    currentTime,
                    "", // updated_at - empty
                    "false", // is_deleted
                    "", // deleted_at - empty
                    "\\N", // deleted_by - empty
                    route.originId,
                    route.destinationId
                );
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

    private static class RouteData {
        String originName;
        String destinationName;
        Long originId;
        Long destinationId;
        String routeCode;
        String source; // "benxe" or "nhaxe"

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            RouteData routeData = (RouteData) o;
            return Objects.equals(originId, routeData.originId) &&
                   Objects.equals(destinationId, routeData.destinationId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(originId, destinationId);
        }
    }
}
