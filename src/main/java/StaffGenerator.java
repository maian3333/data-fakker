import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Generates CSVs with sequential Long IDs starting from 1500.
 * IDs are stable across files within a run via a key→ID registry.
 */
public class StaffGenerator {
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String OUTPUT_DIR = "csv_output";

    // === ID allocation (sequential Longs, stable per key) ===
    private static final class IdRegistry {
        private final Map<String, Long> map = new LinkedHashMap<>();
        private long next = 1500L;

        synchronized long getId(String key) {
            return map.computeIfAbsent(key, k -> next++);
        }
    }

    private final IdRegistry ids = new IdRegistry();

    public static void main(String[] args) {
        try {
            StaffGenerator generator = new StaffGenerator();
            generator.generateAdditionalStaff();
            System.out.println("Additional staff data generated successfully!");
        } catch (IOException e) {
            System.err.println("Error generating staff data: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void ensureOutputDir() throws IOException {
        File dir = new File(OUTPUT_DIR);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("Cannot create output dir: " + OUTPUT_DIR);
        }
    }

    private static void writeHeaderIfNew(File file, String header) throws IOException {
        if (!file.exists() || file.length() == 0) {
            try (PrintWriter w = new PrintWriter(new FileWriter(file, false))) {
                w.println(header);
            }
        }
    }

    public void generateAdditionalStaff() throws IOException {
        ensureOutputDir();

        writeHeaderIfNew(new File(OUTPUT_DIR, "staff.csv"),
                "id,name,age,gender,phone_number,status,created_at,updated_at,is_deleted,deleted_at,deleted_by");

        writeHeaderIfNew(new File(OUTPUT_DIR, "driver.csv"),
                "id,staff_id,license_class,years_experience,created_at,updated_at,is_deleted,deleted_at,deleted_by");

        writeHeaderIfNew(new File(OUTPUT_DIR, "attendant.csv"),
                "id,staff_id,created_at,updated_at,is_deleted,deleted_at,deleted_by");

        writeHeaderIfNew(new File(OUTPUT_DIR, "vehicle.csv"),
                "id,seat_map_id,type,type_factor,plate_number,brand,description,status,created_at,updated_at,is_deleted,deleted_at,deleted_by");

        generateAdditionalStaffCsv();
        generateAdditionalDriverCsv();
        generateAdditionalAttendantCsv();
        generateAdditionalVehicleCsv();
    }

    private void generateAdditionalStaffCsv() throws IOException {
        try (PrintWriter writer = new PrintWriter(new FileWriter(OUTPUT_DIR + "/staff.csv", true))) {
            String[][] additionalStaff = {
                    { "Do Van G", "33", "MALE", "0945678901", "ACTIVE" },
                    { "Bui Thi H", "29", "FEMALE", "0956789012", "ACTIVE" },
                    { "Ngo Van I", "37", "MALE", "0967890123", "ACTIVE" },
                    { "Dang Thi J", "32", "FEMALE", "0978901234", "ACTIVE" },
                    { "Vu Van K", "41", "MALE", "0989012345", "ACTIVE" },
                    { "Cao Thi L", "27", "FEMALE", "0990123456", "ACTIVE" },
                    { "Ly Van M", "36", "MALE", "0901234567", "ACTIVE" },
                    { "Truong Thi N", "30", "FEMALE", "0912345678", "ACTIVE" },
                    { "Dinh Van O", "44", "MALE", "0923456789", "ACTIVE" },
                    { "Mai Thi P", "25", "FEMALE", "0934567890", "ACTIVE" },
                    { "Tong Van Q", "38", "MALE", "0945678901", "ACTIVE" },
                    { "Lam Thi R", "31", "FEMALE", "0956789012", "ACTIVE" },
                    { "Huynh Van S", "43", "MALE", "0967890123", "ACTIVE" },
                    { "Chau Thi T", "28", "FEMALE", "0978901234", "ACTIVE" },
                    { "Quach Van U", "35", "MALE", "0989012345", "ACTIVE" },
                    { "Duong Thi V", "29", "FEMALE", "0990123456", "ACTIVE" },
                    { "Phan Van W", "40", "MALE", "0901234567", "ACTIVE" },
                    { "Tang Thi X", "26", "FEMALE", "0912345678", "ACTIVE" },
                    { "Luu Van Y", "39", "MALE", "0923456789", "ACTIVE" },
                    { "Hoa Thi Z", "33", "FEMALE", "0934567890", "ACTIVE" }
            };

            String now = LocalDateTime.now().format(TIMESTAMP_FORMAT);
            for (String[] person : additionalStaff) {
                String name = person[0];
                String age = person[1];
                String gender = person[2];
                String phone = person[3];
                String status = person[4];

                long staffId = ids.getId("staff:" + name + ":" + phone);

                // 11 columns:
                // id,name,age,gender,phone_number,status,created_at,updated_at,is_deleted,deleted_at,deleted_by
                writer.printf("%d,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s%n",
                        staffId, name, age, gender, phone, status,
                        now, "", "false", "", "");
            }
        }
    }

    private void generateAdditionalDriverCsv() throws IOException {
        try (PrintWriter writer = new PrintWriter(new FileWriter(OUTPUT_DIR + "/driver.csv", true))) {
            String[] driverNames = {
                    "Do Van G", "Ngo Van I", "Vu Van K", "Ly Van M", "Dinh Van O",
                    "Tong Van Q", "Huynh Van S", "Quach Van U", "Phan Van W", "Luu Van Y"
            };
            String[] driverPhones = {
                    "0945678901", "0967890123", "0989012345", "0901234567", "0923456789",
                    "0945678901", "0967890123", "0989012345", "0901234567", "0923456789"
            };
            String[] licenseClasses = { "D", "E", "D", "E", "D", "E", "D", "E", "D", "E" };
            String[] experiences = { "12", "18", "9", "14", "16", "11", "13", "17", "10", "15" };

            String now = LocalDateTime.now().format(TIMESTAMP_FORMAT);
            for (int i = 0; i < driverNames.length; i++) {
                String name = driverNames[i];
                String phone = driverPhones[i];

                long staffId = ids.getId("staff:" + name + ":" + phone); // must match staff.csv
                long driverId = ids.getId("driver:" + name + ":" + phone); // driver row id

                // 9 columns:
                // id,staff_id,license_class,years_experience,created_at,updated_at,is_deleted,deleted_at,deleted_by
                writer.printf("%d,%d,%s,%s,%s,%s,%s,%s,%s%n",
                        driverId, staffId, licenseClasses[i], experiences[i],
                        now, "", "false", "", "");
            }
        }
    }

    private void generateAdditionalAttendantCsv() throws IOException {
        try (PrintWriter writer = new PrintWriter(new FileWriter(OUTPUT_DIR + "/attendant.csv", true))) {
            String[] attendantNames = {
                    "Bui Thi H", "Dang Thi J", "Cao Thi L", "Truong Thi N", "Mai Thi P",
                    "Lam Thi R", "Chau Thi T", "Duong Thi V", "Tang Thi X", "Hoa Thi Z"
            };
            String[] attendantPhones = {
                    "0956789012", "0978901234", "0990123456", "0912345678", "0934567890",
                    "0956789012", "0978901234", "0990123456", "0912345678", "0934567890"
            };

            String now = LocalDateTime.now().format(TIMESTAMP_FORMAT);
            for (int i = 0; i < attendantNames.length; i++) {
                String name = attendantNames[i];
                String phone = attendantPhones[i];

                long staffId = ids.getId("staff:" + name + ":" + phone); // must match staff.csv
                long attendantId = ids.getId("attendant:" + name + ":" + phone); // attendant row id

                // 7 columns: id,staff_id,created_at,updated_at,is_deleted,deleted_at,deleted_by
                writer.printf("%d,%d,%s,%s,%s,%s,%s%n",
                        attendantId, staffId, now, "", "false", "", "");
            }
        }
    }

    private void generateAdditionalVehicleCsv() throws IOException {
        try (PrintWriter writer = new PrintWriter(new FileWriter(OUTPUT_DIR + "/vehicle.csv", true))) {
            String[][] additionalVehicles = {
                    { "STANDARD_BUS_NORMAL", "1.0", "34A-44444", "Hyundai", "Standard bus route 6" },
                    { "LIMOUSINE", "1.5", "35A-55555", "Mercedes", "Luxury bus route 7" },
                    { "STANDARD_BUS_VIP", "1.2", "36A-66666", "Thaco", "VIP bus route 8" },
                    { "STANDARD_BUS_NORMAL", "1.0", "37A-77777", "Hyundai", "Standard bus route 9" },
                    { "LIMOUSINE", "1.8", "38A-88888", "Scania", "Luxury sleeper route 10" },
                    { "STANDARD_BUS_NORMAL", "1.0", "39A-99999", "Hyundai", "Standard bus route 11" },
                    { "LIMOUSINE", "1.5", "40A-00000", "Mercedes", "Luxury bus route 12" },
                    { "STANDARD_BUS_VIP", "1.2", "41A-11111", "Thaco", "VIP bus route 13" },
                    { "STANDARD_BUS_NORMAL", "1.0", "42A-22222", "Hyundai", "Standard bus route 14" },
                    { "LIMOUSINE", "1.8", "43A-33333", "Scania", "Luxury sleeper route 15" }
            };

            String now = LocalDateTime.now().format(TIMESTAMP_FORMAT);
            for (int i = 0; i < additionalVehicles.length; i++) {
                String type = additionalVehicles[i][0];
                String typeFactor = additionalVehicles[i][1];
                String plate = additionalVehicles[i][2];
                String brand = additionalVehicles[i][3];
                String description = additionalVehicles[i][4];

                long vehicleId = ids.getId("vehicle:" + plate + ":" + brand);
                long seatMapId = ids.getId("seatmap:" + type + ":" + (i + 1)); // stable by type+index

                String status = switch (i % 3) {
                    case 1 -> "MAINTENANCE";
                    case 2 -> "RETIRED";
                    default -> "ACTIVE";
                };

                // 13 columns:
                // id,seat_map_id,type,type_factor,plate_number,brand,description,status,created_at,updated_at,is_deleted,deleted_at,deleted_by
                writer.printf("%d,%d,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s%n",
                        vehicleId, seatMapId, type, typeFactor, plate, brand, description, status,
                        now, "", "false", "", "");
            }
        }
    }
}
