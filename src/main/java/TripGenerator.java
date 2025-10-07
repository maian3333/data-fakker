import java.io.*;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

public class TripGenerator {
    private static final String BENXE_INPUT_FILE = "tickets_benxe.csv";
    private static final String NHAXE_INPUT_FILE = "tickets_nhaxe.csv";
    private static final String ROUTE_FILE = "csv_output/route.csv";
    private static final String VEHICLE_FILE = "csv_output/vehicle.csv";
    private static final String DRIVER_FILE = "csv_output/driver.csv";
    private static final String ATTENDANT_FILE = "csv_output/attendant.csv";
    private static final String OUTPUT_DIR = "csv_output";
    private static final String TRIP_OUTPUT = OUTPUT_DIR + "/trip.csv";

    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    // Data storage
    private Map<String, RouteInfo> routeCodeToInfo = new HashMap<>();
    private List<String> vehicleIds = new ArrayList<>();
    private List<String> driverIds = new ArrayList<>();
    private List<String> attendantIds = new ArrayList<>();
    private Random random = new Random();
    private Set<String> generatedTripCodes = new HashSet<>();

    public static void main(String[] args) {
        try {
            TripGenerator generator = new TripGenerator();
            generator.generateTrips();
            System.out.println("Trip CSV file generated successfully!");
        } catch (Exception e) {
            System.err.println("Error generating trip CSV: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // Generate deterministic BigInteger ID from string using SHA-256
    private static BigInteger generateBigIntId(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));

            // Take first 8 bytes to create a positive BigInteger
            byte[] idBytes = new byte[8];
            System.arraycopy(hash, 0, idBytes, 0, 8);

            // Ensure positive by clearing the sign bit
            idBytes[0] &= 0x7F;

            return new BigInteger(1, idBytes);
        } catch (Exception e) {
            // Fallback: use hashCode
            return BigInteger.valueOf(Math.abs((long) input.hashCode()));
        }
    }

    public void generateTrips() throws IOException {
        // Load reference data
        loadRouteData();
        loadVehicleData();
        loadDriverData();
        loadAttendantData();

        // Generate additional staff if needed
        ensureMinimumStaff();

        // Process tickets and generate trips
        List<TripData> trips = new ArrayList<>();

        // Process benxe tickets
        System.out.println("Processing benxe tickets...");
        trips.addAll(processBenxeTickets());

        // Process nhaxe tickets
        System.out.println("Processing nhaxe tickets...");
        trips.addAll(processNhaxeTickets());

        System.out.println("Total trips generated: " + trips.size());

        // Write trip CSV
        generateTripCsv(trips);
    }

    private void loadRouteData() throws IOException {
        try (BufferedReader reader = new BufferedReader(new FileReader(ROUTE_FILE))) {
            String line = reader.readLine(); // Skip header

            while ((line = reader.readLine()) != null) {
                String[] fields = line.split(";");
                if (fields.length >= 10) {
                    String routeId = fields[0];
                    String routeCode = fields[1];
                    String originId = fields[8];
                    String destinationId = fields[9];

                    RouteInfo info = new RouteInfo();
                    info.routeId = routeId;
                    info.routeCode = routeCode;
                    info.originId = originId;
                    info.destinationId = destinationId;

                    routeCodeToInfo.put(routeCode, info);
                }
            }
        }
        System.out.println("Loaded " + routeCodeToInfo.size() + " routes");
    }

    private void loadVehicleData() throws IOException {
        try (BufferedReader reader = new BufferedReader(new FileReader(VEHICLE_FILE))) {
            String line = reader.readLine(); // Skip header

            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty())
                    continue;

                // Handle CSV parsing with potential quotes and commas
                String[] fields = parseCsvLine(line);
                if (fields.length >= 1 && !fields[0].trim().isEmpty()) {
                    vehicleIds.add(fields[0].trim());
                }
            }
        }
        System.out.println("Loaded " + vehicleIds.size() + " vehicles");
        if (!vehicleIds.isEmpty()) {
            System.out.println("Sample vehicle IDs: " + vehicleIds.subList(0, Math.min(3, vehicleIds.size())));
        }
    }

    private void loadDriverData() throws IOException {
        try (BufferedReader reader = new BufferedReader(new FileReader(DRIVER_FILE))) {
            String line = reader.readLine(); // Skip header

            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty())
                    continue;

                String[] fields = parseCsvLine(line);
                if (fields.length >= 1 && !fields[0].trim().isEmpty()) {
                    driverIds.add(fields[0].trim());
                }
            }
        }
        System.out.println("Loaded " + driverIds.size() + " drivers");
        if (!driverIds.isEmpty()) {
            System.out.println("Sample driver IDs: " + driverIds.subList(0, Math.min(3, driverIds.size())));
        }
    }

    private void loadAttendantData() throws IOException {
        try (BufferedReader reader = new BufferedReader(new FileReader(ATTENDANT_FILE))) {
            String line = reader.readLine(); // Skip header

            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty())
                    continue;

                String[] fields = parseCsvLine(line);
                if (fields.length >= 1 && !fields[0].trim().isEmpty()) {
                    attendantIds.add(fields[0].trim());
                }
            }
        }
        System.out.println("Loaded " + attendantIds.size() + " attendants");
        if (!attendantIds.isEmpty()) {
            System.out.println("Sample attendant IDs: " + attendantIds.subList(0, Math.min(3, attendantIds.size())));
        }
    }

    // Proper CSV line parsing to handle quoted fields and commas within fields
    private String[] parseCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        boolean inQuotes = false;
        StringBuilder currentField = new StringBuilder();

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);

            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (c == ',' && !inQuotes) {
                fields.add(currentField.toString());
                currentField.setLength(0);
            } else {
                currentField.append(c);
            }
        }

        // Add the last field
        fields.add(currentField.toString());

        return fields.toArray(new String[0]);
    }

    private void ensureMinimumStaff() throws IOException {
        // This method is kept for compatibility but now focuses on validation
        // All vehicle, driver, and attendant IDs should come from csv_output files
        if (vehicleIds.isEmpty()) {
            System.err.println("Warning: No vehicles found in csv_output/vehicle.csv");
            System.err.println("Please ensure vehicle.csv exists and contains vehicle data.");
        }

        if (driverIds.isEmpty()) {
            System.err.println("Warning: No drivers found in csv_output/driver.csv");
            System.err.println("Please ensure driver.csv exists and contains driver data.");
        }

        if (attendantIds.isEmpty()) {
            System.err.println("Warning: No attendants found in csv_output/attendant.csv");
            System.err.println("Please ensure attendant.csv exists and contains attendant data.");
        }

        System.out.println("Loaded staff summary:");
        System.out.println("  Vehicles: " + vehicleIds.size());
        System.out.println("  Drivers: " + driverIds.size());
        System.out.println("  Attendants: " + attendantIds.size());
    }

    private List<TripData> processBenxeTickets() throws IOException {
        List<TripData> trips = new ArrayList<>();
        Pattern routePattern = Pattern.compile("^([^|]+)\\s*\\|");

        try (BufferedReader reader = new BufferedReader(new FileReader(BENXE_INPUT_FILE))) {
            String line = reader.readLine(); // Skip header

            while ((line = reader.readLine()) != null) {
                String[] fields = line.split("\\|");
                if (fields.length >= 13) {
                    try {
                        TripData trip = parseBenxeTicket(fields);
                        if (trip != null) {
                            trips.add(trip);
                        }
                    } catch (Exception e) {
                        // Skip malformed lines
                        continue;
                    }
                }
            }
        }

        return trips;
    }

    private List<TripData> processNhaxeTickets() throws IOException {
        List<TripData> trips = new ArrayList<>();
        Pattern routePattern = Pattern.compile("^\\[.*?\\]\\s*([^|]+)\\s*\\|");

        try (BufferedReader reader = new BufferedReader(new FileReader(NHAXE_INPUT_FILE))) {
            String line = reader.readLine(); // Skip header

            while ((line = reader.readLine()) != null) {
                String[] fields = line.split("\\|");
                if (fields.length >= 13) {
                    try {
                        TripData trip = parseNhaxeTicket(fields);
                        if (trip != null) {
                            trips.add(trip);
                        }
                    } catch (Exception e) {
                        // Skip malformed lines
                        continue;
                    }
                }
            }
        }

        return trips;
    }

    private TripData parseBenxeTicket(String[] fields) {
        try {
            // Extract route info from first field
            String routeInfo = fields[0].trim();
            String routeCode = generateRouteCodeFromInfo(routeInfo);

            RouteInfo route = findMatchingRoute(routeCode, routeInfo);
            if (route == null)
                return null;

            // Extract time and price information
            String departureTime = fields[5].trim(); // fromHour
            String arrivalTime = fields[7].trim(); // toHour
            String priceStr = fields[2].trim(); // price
            String dateStr = fields[11].trim(); // date

            // Parse price
            BigDecimal baseFare = parsePrice(priceStr);
            if (baseFare == null)
                return null;

            // Create trip data
            TripData trip = new TripData();
            trip.routeId = route.routeId;
            trip.vehicleId = getRandomVehicleId();
            trip.driverId = getRandomDriverId();
            trip.attendantId = getRandomAttendantId();
            trip.tripCode = generateUniqueTripCode();
            trip.departureTime = formatDateTime(dateStr, departureTime);
            trip.arrivalTime = formatDateTime(dateStr, arrivalTime);
            trip.baseFare = baseFare;

            return trip;
        } catch (Exception e) {
            return null;
        }
    }

    private TripData parseNhaxeTicket(String[] fields) {
        try {
            // Extract route info from first field
            String routeInfo = fields[0].trim();
            // Remove the [company] prefix
            if (routeInfo.startsWith("[")) {
                int endBracket = routeInfo.indexOf("]");
                if (endBracket != -1) {
                    routeInfo = routeInfo.substring(endBracket + 1).trim();
                }
            }

            String routeCode = generateRouteCodeFromInfo(routeInfo);
            RouteInfo route = findMatchingRoute(routeCode, routeInfo);
            if (route == null)
                return null;

            // Extract time and price information
            String departureTime = fields[4].trim(); // fromHour
            String arrivalTime = fields[6].trim(); // toHour
            String priceStr = fields[9].trim(); // price
            String dateStr = fields[11].trim(); // date

            // Parse price
            BigDecimal baseFare = parsePrice(priceStr);
            if (baseFare == null)
                return null;

            // Create trip data
            TripData trip = new TripData();
            trip.routeId = route.routeId;
            trip.vehicleId = getRandomVehicleId();
            trip.driverId = getRandomDriverId();
            trip.attendantId = getRandomAttendantId();
            trip.tripCode = generateUniqueTripCode();
            trip.departureTime = formatDateTime(dateStr, departureTime);
            trip.arrivalTime = formatDateTime(dateStr, arrivalTime);
            trip.baseFare = baseFare;

            return trip;
        } catch (Exception e) {
            return null;
        }
    }

    private String generateRouteCodeFromInfo(String routeInfo) {
        // Extract origin and destination from route info
        String[] parts = null;
        if (routeInfo.contains(" đi ")) {
            parts = routeInfo.split(" đi ");
        } else if (routeInfo.contains(" - ")) {
            // Handle nhaxe format: "District - Province đi District - Province"
            String[] tempParts = routeInfo.split(" - ");
            if (tempParts.length >= 4) {
                parts = new String[2];
                parts[0] = tempParts[0] + " - " + tempParts[1];
                parts[1] = tempParts[2] + " - " + tempParts[3];
            }
        }

        if (parts != null && parts.length == 2) {
            String origin = parts[0].trim();
            String destination = parts[1].trim();

            // Generate route code similar to MergedRouteProcessor
            String originCode = origin.replaceAll("[^a-zA-Z0-9]", "").toUpperCase();
            String destCode = destination.replaceAll("[^a-zA-Z0-9]", "").toUpperCase();

            if (originCode.length() > 10)
                originCode = originCode.substring(0, 10);
            if (destCode.length() > 10)
                destCode = destCode.substring(0, 10);

            return originCode + "_" + destCode;
        }

        return null;
    }

    private RouteInfo findMatchingRoute(String routeCode, String routeInfo) {
        // Try exact match first
        if (routeCode != null && routeCodeToInfo.containsKey(routeCode)) {
            return routeCodeToInfo.get(routeCode);
        }

        // Try partial matching
        for (Map.Entry<String, RouteInfo> entry : routeCodeToInfo.entrySet()) {
            String existingCode = entry.getKey();
            if (existingCode.contains(routeCode) || routeCode.contains(existingCode)) {
                return entry.getValue();
            }
        }

        // If no match found, use a random route
        if (!routeCodeToInfo.isEmpty()) {
            List<RouteInfo> routes = new ArrayList<>(routeCodeToInfo.values());
            return routes.get(random.nextInt(routes.size()));
        }

        return null;
    }

    private BigDecimal parsePrice(String priceStr) {
        try {
            // Remove currency symbols and formatting
            String cleanPrice = priceStr.replaceAll("[^0-9.]", "");
            if (cleanPrice.isEmpty())
                return null;
            return new BigDecimal(cleanPrice);
        } catch (Exception e) {
            return null;
        }
    }

    private String getRandomVehicleId() {
        if (vehicleIds.isEmpty()) {
            System.err.println("Warning: No vehicle IDs loaded from csv_output/vehicle.csv");
            return generateBigIntId("fallback_vehicle:" + System.nanoTime()).toString();
        }
        return vehicleIds.get(random.nextInt(vehicleIds.size()));
    }

    private String getRandomDriverId() {
        if (driverIds.isEmpty()) {
            System.err.println("Warning: No driver IDs loaded from csv_output/driver.csv");
            return generateBigIntId("fallback_driver:" + System.nanoTime()).toString();
        }
        return driverIds.get(random.nextInt(driverIds.size()));
    }

    private String getRandomAttendantId() {
        if (attendantIds.isEmpty()) {
            System.err.println("Warning: No attendant IDs loaded from csv_output/attendant.csv");
            return generateBigIntId("fallback_attendant:" + System.nanoTime()).toString();
        }
        return attendantIds.get(random.nextInt(attendantIds.size()));
    }

    private String generateUniqueTripCode() {
        String tripCode;
        do {
            tripCode = "TRIP" + String.format("%06d", random.nextInt(1000000));
        } while (generatedTripCodes.contains(tripCode));
        generatedTripCodes.add(tripCode);
        return tripCode;
    }

    private String formatDateTime(String dateStr, String timeStr) {
        try {
            // Parse date (format: dd-MM-yyyy)
            String[] dateParts = dateStr.split("-");
            if (dateParts.length != 3)
                return LocalDateTime.now().format(TIMESTAMP_FORMAT);

            String day = dateParts[0];
            String month = dateParts[1];
            String year = dateParts[2];

            // Parse time (format: HH:mm)
            String[] timeParts = timeStr.split(":");
            if (timeParts.length != 2)
                return LocalDateTime.now().format(TIMESTAMP_FORMAT);

            String hour = timeParts[0];
            String minute = timeParts[1];

            return String.format("%s-%s-%s %s:%s:00", year, month, day, hour, minute);
        } catch (Exception e) {
            return LocalDateTime.now().format(TIMESTAMP_FORMAT);
        }
    }

    private void generateTripCsv(List<TripData> trips) throws IOException {
        // Build a stable key for each trip
        Map<String, TripData> keyToTrip = new LinkedHashMap<>();
        for (TripData t : trips) {
            String key = buildTripKey(t);
            keyToTrip.put(key, t);
        }

        // Read existing trip.csv (if any) to reuse IDs and find current max
        Path tripPath = Paths.get(TRIP_OUTPUT);
        Map<String, Long> existingKeyToId = new HashMap<>();
        long maxExistingId = 0L;
        if (Files.exists(tripPath)) {
            try (BufferedReader br = Files.newBufferedReader(tripPath, StandardCharsets.UTF_8)) {
                String header = br.readLine(); // skip header
                String line;
                while ((line = br.readLine()) != null) {
                    if (line.isEmpty())
                        continue;
                    // naive split (your trip.csv uses commas and no embedded commas in our fields)
                    String[] cols = line.split(",", -1);
                    if (cols.length < 14)
                        continue;

                    // id,route_id,vehicle_id,driver_id,attendant_id,trip_code,departure_time,arrival_time,base_fare,created_at,updated_at,is_deleted,deleted_at,deleted_by
                    String idStr = cols[0].trim();
                    String routeId = cols[1].trim();
                    String tripCode = cols[5].trim();
                    String departureTime = cols[6].trim();

                    String key = routeId + "|" + tripCode + "|" + departureTime;
                    long id = parseLongSafe(idStr);
                    if (id > 0) {
                        existingKeyToId.put(key, id);
                        if (id > maxExistingId)
                            maxExistingId = id;
                    }
                }
            }
        }

        // Start sequence at max(existing, 1499)+1
        long nextId = Math.max(maxExistingId, 1499L) + 1L;

        // Assign/reuse IDs for all trips, in stable order
        List<String> keys = new ArrayList<>(keyToTrip.keySet());
        Collections.sort(keys); // stable/idempotent ordering

        Map<String, Long> finalKeyToId = new LinkedHashMap<>();
        for (String key : keys) {
            Long reused = existingKeyToId.get(key);
            if (reused != null) {
                finalKeyToId.put(key, reused);
            } else {
                finalKeyToId.put(key, nextId++);
            }
        }

        // Now write (overwrite) trip.csv
        try (PrintWriter writer = new PrintWriter(new FileWriter(TRIP_OUTPUT))) {
            writer.println(
                    "id,route_id,vehicle_id,driver_id,attendant_id,trip_code,departure_time,arrival_time,base_fare,created_at,updated_at,is_deleted,deleted_at,deleted_by");

            String currentTime = LocalDateTime.now().format(TIMESTAMP_FORMAT);

            for (String key : keys) {
                TripData trip = keyToTrip.get(key);
                long id = finalKeyToId.get(key);

                writer.printf(Locale.ROOT,
                        "%d,%s,%s,%s,%s,%s,%s,%s,%.2f,%s,,%s,,%n",
                        id,
                        trip.routeId,
                        trip.vehicleId,
                        trip.driverId,
                        trip.attendantId,
                        trip.tripCode,
                        trip.departureTime,
                        trip.arrivalTime,
                        trip.baseFare,
                        currentTime,
                        "false");
            }
        }
    }

    // ---- helpers ----
    private static String buildTripKey(TripData t) {
        // key fields that define a unique trip
        return t.routeId + "|" + t.tripCode + "|" + t.departureTime;
    }

    private static long parseLongSafe(String s) {
        try {
            return (s == null || s.isEmpty()) ? 0L : Long.parseLong(s.trim());
        } catch (Exception e) {
            return 0L;
        }
    }

    // Data classes
    private static class RouteInfo {
        String routeId;
        String routeCode;
        String originId;
        String destinationId;
    }

    private static class TripData {
        String routeId;
        String vehicleId;
        String driverId;
        String attendantId;
        String tripCode;
        String departureTime;
        String arrivalTime;
        BigDecimal baseFare;
    }
}