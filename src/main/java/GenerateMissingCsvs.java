import java.io.*;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Generate missing seat_map.csv, floor.csv and seat.csv from vehicle.csv.
 * - Idempotent:
 * * seat_map.id comes from vehicle.seat_map_id (kept as-is to match
 * vehicle.csv).
 * * floor.id and seat.id are sequential long values starting from 1500.
 * * If a floor/seat already exists, reuse its existing id.
 * - Appends only what's missing.
 */
public class GenerateMissingCsvs {

    // ====== CONFIG ======
    private static final String CSV_DIR = "csv_output"; // adjust if needed
    private static final String VEHICLE_CSV = "vehicle.csv";
    private static final String SEAT_MAP_CSV = "seat_map.csv";
    private static final String FLOOR_CSV = "floor.csv";
    private static final String SEAT_CSV = "seat.csv";

    private static final double FLOOR1_FACTOR = 1.00;
    private static final double FLOOR2_FACTOR = 1.10;
    private static final double SEAT_FACTOR = 1.00;

    private static final int MIN_SEATS_PER_FLOOR = 15;
    private static final int MAX_SEATS_PER_FLOOR = 20;

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private enum VehicleType {
        STANDARD_BUS_VIP, STANDARD_BUS_NORMAL, LIMOUSINE
    }

    /** Simple sequence that starts from max(existing, 1500-1)+1 */
    private static final class IdSequence {
        private long current;

        IdSequence(long startExclusive) {
            this.current = startExclusive;
        }

        long next() {
            return ++current;
        }
    }

    public static void main(String[] args) throws Exception {
        Path dir = Paths.get(CSV_DIR);
        if (!Files.isDirectory(dir)) {
            throw new IllegalStateException("CSV directory not found: " + dir.toAbsolutePath());
        }

        // 1) Read vehicle.csv -> seat_map_ids + vehicle types + plate numbers (for
        // names)
        List<Map<String, String>> vehicles = readCsv(dir.resolve(VEHICLE_CSV));
        if (vehicles.isEmpty()) {
            System.out.println("No vehicles found in " + VEHICLE_CSV);
            return;
        }

        // seat_map_id -> type (prefer first seen; stable)
        Map<String, String> seatMapType = new LinkedHashMap<>();
        // seat_map_id -> derived name (prefer plate if present)
        Map<String, String> seatMapName = new LinkedHashMap<>();

        for (Map<String, String> v : vehicles) {
            String smId = v.getOrDefault("seat_map_id", "").trim();
            if (smId.isEmpty())
                continue;

            String type = v.getOrDefault("type", "").trim();
            String plate = v.getOrDefault("plate_number", "").trim();

            seatMapType.putIfAbsent(smId, type);

            if (!seatMapName.containsKey(smId)) {
                String name;
                if (!plate.isEmpty()) {
                    name = "SM-" + plate;
                } else if (!type.isEmpty()) {
                    name = "SM-" + type;
                } else {
                    name = "SM-" + smId.substring(0, Math.min(8, smId.length()));
                }
                seatMapName.put(smId, name);
            }
        }

        // 2) Load existing
        Path seatMapCsvPath = dir.resolve(SEAT_MAP_CSV);
        Path floorCsvPath = dir.resolve(FLOOR_CSV);
        Path seatCsvPath = dir.resolve(SEAT_CSV);

        List<Map<String, String>> existingSeatMaps = Files.exists(seatMapCsvPath) ? readCsv(seatMapCsvPath)
                : new ArrayList<>();
        List<Map<String, String>> existingFloors = Files.exists(floorCsvPath) ? readCsv(floorCsvPath)
                : new ArrayList<>();
        List<Map<String, String>> existingSeats = Files.exists(seatCsvPath) ? readCsv(seatCsvPath) : new ArrayList<>();

        // Index existing seat_map ids
        Set<String> existingSeatMapIds = existingSeatMaps.stream()
                .map(r -> r.getOrDefault("id", "").toLowerCase())
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());

        // Index existing floor keys: (seat_map_id, floor_no) -> floorId
        Map<String, String> floorKeyToId = new HashMap<>();
        for (Map<String, String> r : existingFloors) {
            String key = (r.getOrDefault("seat_map_id", "") + "::" + r.getOrDefault("floor_no", "")).toLowerCase();
            String id = r.getOrDefault("id", "");
            if (!key.isEmpty() && !id.isEmpty())
                floorKeyToId.put(key, id);
        }

        // Index existing seat keys: (floor_id, seat_no) -> seatId
        Map<String, String> seatKeyToId = new HashMap<>();
        for (Map<String, String> r : existingSeats) {
            String key = (r.getOrDefault("floor_id", "") + "::" + r.getOrDefault("seat_no", "")).toLowerCase();
            String id = r.getOrDefault("id", "");
            if (!key.isEmpty() && !id.isEmpty())
                seatKeyToId.put(key, id);
        }

        // Build rows to append
        List<Map<String, String>> newSeatMaps = new ArrayList<>();
        List<Map<String, String>> newFloors = new ArrayList<>();
        List<Map<String, String>> newSeats = new ArrayList<>();

        // 2a) Prepare ID sequences (start after max existing or 1499, whichever is
        // larger)
        long maxFloorId = maxNumericId(existingFloors, "id");
        long maxSeatId = maxNumericId(existingSeats, "id");
        IdSequence floorSeq = new IdSequence(Math.max(maxFloorId, 1499));
        IdSequence seatSeq = new IdSequence(Math.max(maxSeatId, 1499));

        // 3a) Ensure seat_map rows exist (seat_map.id is the original seat_map_id
        // string)
        for (String seatMapId : seatMapType.keySet()) {
            String smIdLower = seatMapId.toLowerCase();
            boolean exists = existingSeatMapIds.contains(smIdLower)
                    || existingSeatMaps.stream().anyMatch(r -> seatMapId.equals(r.get("id")));
            if (!exists) {
                Map<String, String> sm = new LinkedHashMap<>();
                sm.put("id", seatMapId); // keep as string to match vehicle.csv
                sm.put("name", seatMapName.getOrDefault(seatMapId,
                        "SM-" + seatMapId.substring(0, Math.min(8, seatMapId.length()))));
                stampCommon(sm);
                newSeatMaps.add(sm);
                existingSeatMapIds.add(smIdLower);
            }
        }

        // 3b) Floors & seats (with numeric IDs)
        for (Map.Entry<String, String> e : seatMapType.entrySet()) {
            String seatMapId = e.getKey();
            VehicleType vt = parseVehicleType(e.getValue());

            int floors = (vt == VehicleType.LIMOUSINE) ? 1 : 2;

            for (int floorNo = 1; floorNo <= floors; floorNo++) {
                final String floorKey = (seatMapId + "::" + floorNo).toLowerCase();

                // Reuse existing floor id if present, else allocate new from sequence
                String floorId = floorKeyToId.get(floorKey);
                boolean floorExists = (floorId != null);

                if (!floorExists) {
                    floorId = String.valueOf(floorSeq.next());
                    Map<String, String> f = new LinkedHashMap<>();
                    f.put("id", floorId);
                    f.put("seat_map_id", seatMapId);
                    f.put("floor_no", String.valueOf(floorNo));
                    f.put("price_factor_floor",
                            String.format(Locale.ROOT, "%.3f", (floorNo == 2 ? FLOOR2_FACTOR : FLOOR1_FACTOR)));
                    stampCommon(f);
                    newFloors.add(f);
                    floorKeyToId.put(floorKey, floorId);
                }

                // Generate seats for this floor
                int seatCount = deterministicSeatCount(seatMapId, floorNo);
                int cols = 4;
                int rows = (int) Math.ceil(seatCount / (double) cols);
                String seatType = (vt == VehicleType.LIMOUSINE) ? "SLEEPER" : "NORMAL";

                for (int i = 0; i < seatCount; i++) {
                    int row = (i / cols) + 1;
                    int col = (i % cols) + 1;
                    String seatNo = formatSeatNo(row, col); // e.g., A01, A02, ...

                    String seatKey = (floorId + "::" + seatNo).toLowerCase();

                    // Reuse existing seat id if present, else allocate new
                    String seatId = seatKeyToId.get(seatKey);
                    boolean seatExists = (seatId != null);

                    if (!seatExists) {
                        seatId = String.valueOf(seatSeq.next());
                        Map<String, String> s = new LinkedHashMap<>();
                        s.put("id", seatId);
                        s.put("floor_id", floorId);
                        s.put("seat_no", seatNo);
                        s.put("row_no", String.valueOf(row));
                        s.put("col_no", String.valueOf(col));
                        s.put("price_factor", String.format(Locale.ROOT, "%.3f", SEAT_FACTOR));
                        s.put("seat_type", seatType);
                        stampCommon(s);
                        newSeats.add(s);
                        seatKeyToId.put(seatKey, seatId);
                    }
                }
            }
        }

        // 4) Write/append CSVs
        if (!Files.exists(seatMapCsvPath)) {
            writeCsv(seatMapCsvPath, headersSeatMap(), newSeatMaps);
            System.out.println("Created " + SEAT_MAP_CSV + " with " + newSeatMaps.size() + " rows.");
        } else if (!newSeatMaps.isEmpty()) {
            appendCsv(seatMapCsvPath, headersSeatMap(), newSeatMaps);
            System.out.println("Appended " + newSeatMaps.size() + " rows to " + SEAT_MAP_CSV + ".");
        } else {
            System.out.println("No new seat maps to append.");
        }

        if (!Files.exists(floorCsvPath)) {
            writeCsv(floorCsvPath, headersFloor(), newFloors);
            System.out.println("Created " + FLOOR_CSV + " with " + newFloors.size() + " rows.");
        } else if (!newFloors.isEmpty()) {
            appendCsv(floorCsvPath, headersFloor(), newFloors);
            System.out.println("Appended " + newFloors.size() + " rows to " + FLOOR_CSV + ".");
        } else {
            System.out.println("No new floors to append.");
        }

        if (!Files.exists(seatCsvPath)) {
            writeCsv(seatCsvPath, headersSeat(), newSeats);
            System.out.println("Created " + SEAT_CSV + " with " + newSeats.size() + " rows.");
        } else if (!newSeats.isEmpty()) {
            appendCsv(seatCsvPath, headersSeat(), newSeats);
            System.out.println("Appended " + newSeats.size() + " rows to " + SEAT_CSV + ".");
        } else {
            System.out.println("No new seats to append.");
        }
    }

    // ===== Helpers =====

    private static VehicleType parseVehicleType(String t) {
        try {
            return VehicleType.valueOf(t.trim());
        } catch (Exception ex) {
            return VehicleType.STANDARD_BUS_NORMAL;
        }
    }

    private static List<String> headersSeatMap() {
        return List.of("id", "name", "created_at", "updated_at", "is_deleted", "deleted_at", "deleted_by");
    }

    private static List<String> headersFloor() {
        return List.of("id", "seat_map_id", "floor_no", "price_factor_floor",
                "created_at", "updated_at", "is_deleted", "deleted_at", "deleted_by");
    }

    private static List<String> headersSeat() {
        return List.of("id", "floor_id", "seat_no", "row_no", "col_no", "price_factor", "seat_type",
                "created_at", "updated_at", "is_deleted", "deleted_at", "deleted_by");
    }

    private static void stampCommon(Map<String, String> r) {
        String now = LocalDateTime.now().format(TS);
        r.put("created_at", now);
        r.put("updated_at", "");
        r.put("is_deleted", "false");
        r.put("deleted_at", "");
        r.put("deleted_by", "");
    }

    // Deterministic seat count per (seatMapId, floorNo) — keeps your prior
    // variability
    private static int deterministicSeatCount(String seatMapId, int floorNo) {
        BigInteger seed = generateBigIntId("seatcount:" + seatMapId + ":" + floorNo);
        long n = Math.abs(seed.longValue());
        int span = MAX_SEATS_PER_FLOOR - MIN_SEATS_PER_FLOOR + 1;
        return (int) (n % span) + MIN_SEATS_PER_FLOOR;
    }

    // Seat number like A01..A04, B01.. etc. based on row/col
    private static String formatSeatNo(int row, int col) {
        char rowChar = (char) ('A' + (row - 1)); // A, B, C...
        return String.format("%c%02d", rowChar, col);
    }

    // Only used for deterministic seat count, not for IDs anymore
    private static BigInteger generateBigIntId(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            byte[] idBytes = new byte[8];
            System.arraycopy(hash, 0, idBytes, 0, 8);
            idBytes[0] &= 0x7F; // positive
            return new BigInteger(1, idBytes);
        } catch (Exception e) {
            return BigInteger.valueOf(Math.abs((long) input.hashCode()));
        }
    }

    // ---- CSV I/O ----

    private static List<Map<String, String>> readCsv(Path path) throws IOException {
        List<Map<String, String>> rows = new ArrayList<>();
        try (BufferedReader br = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String headerLine = br.readLine();
            if (headerLine == null)
                return rows;
            List<String> headers = splitCsv(headerLine);
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isEmpty())
                    continue;
                List<String> cols = splitCsv(line);
                Map<String, String> row = new LinkedHashMap<>();
                for (int i = 0; i < headers.size(); i++) {
                    String key = headers.get(i);
                    String val = (i < cols.size()) ? cols.get(i) : "";
                    row.put(key, val);
                }
                rows.add(row);
            }
        }
        return rows;
    }

    private static void writeCsv(Path path, List<String> headers, List<Map<String, String>> rows) throws IOException {
        try (BufferedWriter bw = Files.newBufferedWriter(path, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            bw.write(String.join(",", headers));
            bw.newLine();
            for (Map<String, String> r : rows) {
                bw.write(rowToCsv(headers, r));
                bw.newLine();
            }
        }
    }

    private static void appendCsv(Path path, List<String> headers, List<Map<String, String>> rows) throws IOException {
        try (BufferedWriter bw = Files.newBufferedWriter(path, StandardCharsets.UTF_8,
                StandardOpenOption.APPEND)) {
            for (Map<String, String> r : rows) {
                bw.write(rowToCsv(headers, r));
                bw.newLine();
            }
        }
    }

    private static String rowToCsv(List<String> headers, Map<String, String> row) {
        List<String> vals = new ArrayList<>(headers.size());
        for (String h : headers) {
            String v = row.getOrDefault(h, "");
            vals.add(escapeCsv(v));
        }
        return String.join(",", vals);
    }

    // naive CSV split/escape (no embedded newlines assumed)
    private static List<String> splitCsv(String line) {
        List<String> out = new ArrayList<>();
        boolean inQuotes = false;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (c == ',' && !inQuotes) {
                out.add(sb.toString());
                sb.setLength(0);
            } else {
                sb.append(c);
            }
        }
        out.add(sb.toString());
        return out;
    }

    private static String escapeCsv(String v) {
        if (v == null)
            return "";
        boolean needQuotes = v.contains(",") || v.contains("\"");
        String s = v.replace("\"", "\"\"");
        return needQuotes ? "\"" + s + "\"" : s;
    }

    // ---- numeric helpers ----

    private static long maxNumericId(List<Map<String, String>> rows, String key) {
        long max = 0L;
        for (Map<String, String> r : rows) {
            String v = r.getOrDefault(key, "").trim();
            long n = parseLongSafe(v);
            if (n > max)
                max = n;
        }
        return max;
    }

    private static long parseLongSafe(String s) {
        try {
            if (s == null || s.isEmpty())
                return 0L;
            return Long.parseLong(s.trim());
        } catch (Exception e) {
            return 0L;
        }
    }
}
