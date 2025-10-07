package csvgenerator;

import java.io.*;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Address Processor for matching benxe addresses with ward data
 * Reads benxe_addresses.csv and matches with ward/district/province data to find ward IDs
 */
public class AddressProcessor {
    
    private static final String INPUT_FILE = "benxe_addresses.csv";
    private static final String PROVINCE_FILE = "csv_output/province.csv";
    private static final String DISTRICT_FILE = "csv_output/district.csv";
    private static final String WARD_FILE = "csv_output/ward.csv";
    private static final String OUTPUT_FILE = "benxe_addresses_with_ward_ids.csv";
    private static final String SEPARATOR = ",";
    private static final String CSV_SEPARATOR = ";";
    
    // Data structures to hold CSV data
    private Map<String, Province> provinces = new HashMap<>();
    private Map<String, District> districts = new HashMap<>();
    private Map<String, Ward> wards = new HashMap<>();
    private List<Address> addresses = new ArrayList<>();
    
    // Data classes
    static class Province {
        String id;
        String name;
        String codeName;
        
        Province(String id, String name, String codeName) {
            this.id = id;
            this.name = name;
            this.codeName = codeName;
        }
    }
    
    static class District {
        String id;
        String name;
        String codeName;
        String provinceId;
        
        District(String id, String name, String codeName, String provinceId) {
            this.id = id;
            this.name = name;
            this.codeName = codeName;
            this.provinceId = provinceId;
        }
    }
    
    static class Ward {
        String id;
        String name;
        String codeName;
        String districtId;
        
        Ward(String id, String name, String codeName, String districtId) {
            this.id = id;
            this.name = name;
            this.codeName = codeName;
            this.districtId = districtId;
        }
    }
    
    static class Address {
        String stationSlug;
        String stationName;
        String address;
        String province;
        String wardId = "";
        String matchedWard = "";
        String matchedDistrict = "";
        
        Address(String stationSlug, String stationName, String address, String province) {
            this.stationSlug = stationSlug;
            this.stationName = stationName;
            this.address = address;
            this.province = province;
        }
    }
    
    public static void main(String[] args) {
        AddressProcessor processor = new AddressProcessor();
        try {
            processor.processAddresses();
            System.out.println("Address processing completed successfully!");
            System.out.println("Output file: " + OUTPUT_FILE);
        } catch (Exception e) {
            System.err.println("Error processing addresses: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    public void processAddresses() throws IOException {
        // Load all CSV data
        loadProvinces();
        loadDistricts();
        loadWards();
        loadAddresses();
        
        // Match addresses with wards
        matchAddressesWithWards();
        
        // Generate output CSV
        generateOutputCsv();
    }
    
    private void loadProvinces() throws IOException {
        System.out.println("Loading provinces...");
        try (BufferedReader reader = new BufferedReader(new FileReader(PROVINCE_FILE))) {
            String line = reader.readLine(); // Skip header
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split(CSV_SEPARATOR);
                if (parts.length >= 7) {
                    String id = parts[0];
                    String name = parts[2];
                    String codeName = parts[6];
                    provinces.put(normalizeText(name), new Province(id, name, codeName));
                }
            }
        }
        System.out.println("Loaded " + provinces.size() + " provinces");
    }
    
    private void loadDistricts() throws IOException {
        System.out.println("Loading districts...");
        try (BufferedReader reader = new BufferedReader(new FileReader(DISTRICT_FILE))) {
            String line = reader.readLine(); // Skip header
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split(CSV_SEPARATOR);
                if (parts.length >= 14) {
                    String id = parts[0];
                    String name = parts[2];
                    String codeName = parts[6];
                    String provinceId = parts[13];
                    districts.put(normalizeText(name), new District(id, name, codeName, provinceId));
                }
            }
        }
        System.out.println("Loaded " + districts.size() + " districts");
    }
    
    private void loadWards() throws IOException {
        System.out.println("Loading wards...");
        try (BufferedReader reader = new BufferedReader(new FileReader(WARD_FILE))) {
            String line = reader.readLine(); // Skip header
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split(CSV_SEPARATOR);
                if (parts.length >= 14) {
                    String id = parts[0];
                    String name = parts[2];
                    String codeName = parts[6];
                    String districtId = parts[13];
                    wards.put(normalizeText(name), new Ward(id, name, codeName, districtId));
                }
            }
        }
        System.out.println("Loaded " + wards.size() + " wards");
    }
    
    private void loadAddresses() throws IOException {
        System.out.println("Loading addresses...");
        try (BufferedReader reader = new BufferedReader(new FileReader(INPUT_FILE))) {
            String line = reader.readLine(); // Skip header
            while ((line = reader.readLine()) != null) {
                String[] parts = parseCSVLine(line);
                if (parts.length >= 4) {
                    addresses.add(new Address(parts[0], parts[1], parts[2], parts[3]));
                }
            }
        }
        System.out.println("Loaded " + addresses.size() + " addresses");
    }
    
    private void matchAddressesWithWards() {
        System.out.println("Matching addresses with wards...");
        int matchedCount = 0;
        
        for (Address address : addresses) {
            String wardId = findWardId(address);
            if (wardId != null && !wardId.isEmpty()) {
                address.wardId = wardId;
                matchedCount++;
            }
        }
        
        System.out.println("Successfully matched " + matchedCount + " out of " + addresses.size() + " addresses");
    }
    
    private String findWardId(Address address) {
        String addressText = normalizeText(address.address);
        String provinceText = normalizeText(address.province);

        // Find matching province first
        Province matchedProvince = null;
        for (Map.Entry<String, Province> entry : provinces.entrySet()) {
            if (provinceText.contains(entry.getKey()) || entry.getKey().contains(provinceText)) {
                matchedProvince = entry.getValue();
                break;
            }
        }

        // If no province match, check if the "province" field is actually a district name
        if (matchedProvince == null) {
            matchedProvince = findProvinceByDistrict(provinceText, addressText);
        }

        if (matchedProvince == null) {
            System.out.println("No province match for: " + address.province);
            return null;
        }

        // Get all districts in this province
        List<District> provinceDistricts = new ArrayList<>();
        for (District district : districts.values()) {
            if (district.provinceId.equals(matchedProvince.id)) {
                provinceDistricts.add(district);
            }
        }

        // Step 1: Try to find ward directly in address
        String wardId = findWardDirectly(address, addressText, provinceDistricts);
        if (wardId != null) {
            return wardId;
        }

        // Step 2: Try to find district in address, then get random ward from that district
        wardId = findDistrictThenRandomWard(address, addressText, provinceDistricts);
        if (wardId != null) {
            return wardId;
        }

        // Step 3: Get random ward from any district in the province
        wardId = getRandomWardFromProvince(address, provinceDistricts);
        if (wardId != null) {
            return wardId;
        }

        return null;
    }

    private Province findProvinceByDistrict(String provinceText, String addressText) {
        // Check if the "province" field is actually a district name
        for (District district : districts.values()) {
            String districtName = normalizeText(district.name);
            // Try exact match first
            if (districtName.equals(provinceText)) {
                // Found district, now find its province
                for (Province province : provinces.values()) {
                    if (province.id.equals(district.provinceId)) {
                        System.out.println("Found province by district match: " + district.name + " -> " + province.name);
                        return province;
                    }
                }
            }
            // Try partial match (e.g., "Ninh Kiều" matches "Quận Ninh Kiều")
            if (districtName.contains(provinceText) || provinceText.contains(districtName)) {
                // Found district, now find its province
                for (Province province : provinces.values()) {
                    if (province.id.equals(district.provinceId)) {
                        System.out.println("Found province by partial district match: " + district.name + " -> " + province.name);
                        return province;
                    }
                }
            }
        }

        // Also check if any part of the address contains a known province name
        for (Map.Entry<String, Province> entry : provinces.entrySet()) {
            if (addressText.contains(entry.getKey())) {
                System.out.println("Found province in address text: " + entry.getValue().name);
                return entry.getValue();
            }
        }

        return null;
    }

    private String findWardDirectly(Address address, String addressText, List<District> provinceDistricts) {
        // Try to find any ward mentioned in the address
        for (District district : provinceDistricts) {
            for (Ward ward : wards.values()) {
                if (ward.districtId.equals(district.id)) {
                    String wardName = normalizeText(ward.name);
                    if (addressText.contains(wardName)) {
                        address.matchedWard = ward.name;
                        address.matchedDistrict = district.name;
                        System.out.println("Direct ward match: " + ward.name + " for " + address.stationName);
                        return ward.id;
                    }
                }
            }
        }
        return null;
    }

    private String findDistrictThenRandomWard(Address address, String addressText, List<District> provinceDistricts) {
        // Try to match district from address
        for (District district : provinceDistricts) {
            String districtName = normalizeText(district.name);

            // Debug output
            System.out.println("Checking district: '" + district.name + "' (normalized: '" + districtName + "') against address: '" + addressText + "'");

            // Try exact match first
            if (addressText.contains(districtName)) {
                System.out.println("Found exact district match: " + district.name);
                // Found district, now get random ward from this district
                List<Ward> districtWards = new ArrayList<>();
                for (Ward ward : wards.values()) {
                    if (ward.districtId.equals(district.id)) {
                        districtWards.add(ward);
                    }
                }

                if (!districtWards.isEmpty()) {
                    Ward randomWard = districtWards.get(new Random().nextInt(districtWards.size()));
                    address.matchedWard = randomWard.name;
                    address.matchedDistrict = district.name;
                    System.out.println("District match -> random ward: " + randomWard.name + " in " + district.name + " for " + address.stationName);
                    return randomWard.id;
                }
            }

            // Try partial match - look for meaningful district name parts
            // Remove common prefixes like "Quận", "Huyện", "Thành phố", "Thị xã"
            String cleanDistrictName = districtName.replaceAll("^(quan|huyen|thanh pho|thi xa)\\s+", "");

            // Check if the clean district name (without prefix) is in the address
            if (cleanDistrictName.length() > 3 && addressText.contains(cleanDistrictName)) {
                System.out.println("Found clean district match: " + district.name + " (matched on: " + cleanDistrictName + ")");
                // Found district, now get random ward from this district
                List<Ward> districtWards = new ArrayList<>();
                for (Ward ward : wards.values()) {
                    if (ward.districtId.equals(district.id)) {
                        districtWards.add(ward);
                    }
                }

                if (!districtWards.isEmpty()) {
                    Ward randomWard = districtWards.get(new Random().nextInt(districtWards.size()));
                    address.matchedWard = randomWard.name;
                    address.matchedDistrict = district.name;
                    System.out.println("District match -> random ward: " + randomWard.name + " in " + district.name + " for " + address.stationName);
                    return randomWard.id;
                }
            }
        }
        return null;
    }

    private String getRandomWardFromProvince(Address address, List<District> provinceDistricts) {
        // Get all wards from all districts in the province
        List<Ward> allProvinceWards = new ArrayList<>();
        Map<String, String> wardToDistrictMap = new HashMap<>();

        for (District district : provinceDistricts) {
            for (Ward ward : wards.values()) {
                if (ward.districtId.equals(district.id)) {
                    allProvinceWards.add(ward);
                    wardToDistrictMap.put(ward.id, district.name);
                }
            }
        }

        if (!allProvinceWards.isEmpty()) {
            Ward randomWard = allProvinceWards.get(new Random().nextInt(allProvinceWards.size()));
            address.matchedWard = randomWard.name;
            address.matchedDistrict = wardToDistrictMap.get(randomWard.id);
            System.out.println("Province fallback -> random ward: " + randomWard.name + " in " + address.matchedDistrict + " for " + address.stationName);
            return randomWard.id;
        }

        return null;
    }
    
    private void generateOutputCsv() throws IOException {
        System.out.println("Generating output CSV...");
        try (PrintWriter writer = new PrintWriter(new FileWriter(OUTPUT_FILE))) {
            // Write header
            writer.println("station_slug,station_name,address,province,ward_id,matched_ward,matched_district");
            
            // Write data
            for (Address address : addresses) {
                writer.println(escapeCSV(address.stationSlug) + SEPARATOR +
                              escapeCSV(address.stationName) + SEPARATOR +
                              escapeCSV(address.address) + SEPARATOR +
                              escapeCSV(address.province) + SEPARATOR +
                              escapeCSV(address.wardId) + SEPARATOR +
                              escapeCSV(address.matchedWard) + SEPARATOR +
                              escapeCSV(address.matchedDistrict));
            }
        }
        System.out.println("Output CSV generated: " + OUTPUT_FILE);
    }
    
    private String normalizeText(String text) {
        if (text == null) return "";
        return text.toLowerCase()
                   .replaceAll("à|á|ạ|ả|ã|â|ầ|ấ|ậ|ẩ|ẫ|ă|ằ|ắ|ặ|ẳ|ẵ", "a")
                   .replaceAll("è|é|ẹ|ẻ|ẽ|ê|ề|ế|ệ|ể|ễ", "e")
                   .replaceAll("ì|í|ị|ỉ|ĩ", "i")
                   .replaceAll("ò|ó|ọ|ỏ|õ|ô|ồ|ố|ộ|ổ|ỗ|ơ|ờ|ớ|ợ|ở|ỡ", "o")
                   .replaceAll("ù|ú|ụ|ủ|ũ|ư|ừ|ứ|ự|ử|ữ", "u")
                   .replaceAll("ỳ|ý|ỵ|ỷ|ỹ", "y")
                   .replaceAll("đ", "d")
                   .replaceAll("[^a-z0-9\\s]", " ")
                   .replaceAll("\\s+", " ")
                   .trim();
    }
    
    private String[] parseCSVLine(String line) {
        List<String> result = new ArrayList<>();
        boolean inQuotes = false;
        StringBuilder current = new StringBuilder();
        
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (c == ',' && !inQuotes) {
                result.add(current.toString());
                current = new StringBuilder();
            } else {
                current.append(c);
            }
        }
        result.add(current.toString());
        
        return result.toArray(new String[0]);
    }
    
    private String escapeCSV(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
