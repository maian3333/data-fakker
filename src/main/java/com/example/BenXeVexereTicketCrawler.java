package com.example;

import okhttp3.*;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class BenXeVexereTicketCrawler {

    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36";
    private static final long POLITENESS_MS = 2000;
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("dd-MM-yyyy");

    private final OkHttpClient http = new OkHttpClient.Builder()
            .callTimeout(Duration.ofSeconds(30))
            .followRedirects(true)
            .build();

    public static void main(String[] args) throws Exception {
        String inputFile = args.length > 0 ? args[0] : "routes_benxe.txt";
        String outputFile = args.length > 1 ? args[1] : "tickets_benxe.csv";
        String date = args.length > 2 ? args[2] : LocalDate.now().plusDays(1).format(DATE_FORMATTER);

        new BenXeVexereTicketCrawler().run(inputFile, outputFile, date);
    }

    public void run(String inputFile, String outputFile, String date) throws Exception {
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(new FileInputStream(inputFile), StandardCharsets.UTF_8));
                BufferedWriter csv = new BufferedWriter(
                        new OutputStreamWriter(new FileOutputStream(outputFile), StandardCharsets.UTF_8))) {

            csv.write("route|busName|seatType|fromHour|fromPlace|toHour|toPlace|duration|price|seatAvailable|date|url");
            csv.newLine();

            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#"))
                    continue;

                // Expect formats like:
                // [ben-xe-vung-tau] Địa chỉ: ...
                // [ben-xe-vung-tau] Bến xe Vũng Tàu đi Hà Nội | Minh Phúc | 700.000₫
                Matcher m = Pattern.compile("\\[([^\\]]+)]\\s*(.+)").matcher(line);
                if (!m.matches())
                    continue;

                String stationSlug = m.group(1).trim(); // e.g., ben-xe-vung-tau
                String content = m.group(2).trim();

                // Skip address/info lines
                if (content.toLowerCase().startsWith("địa chỉ"))
                    continue;

                // Extract destination after "đi ..."
                Matcher md = Pattern.compile("đi\\s+([^|]+)").matcher(content.toLowerCase());
                if (!md.find()) {
                    // if line doesn't have "đi", skip
                    continue;
                }

                String to = md.group(1).trim();
                // Normalize destination (strip trailing separators like price/company etc.)
                to = to.replaceAll("\\s*\\|.*$", "").trim();

                String routeTitle = content; // keep full human-readable route line
                String url = buildAndFindStationUrl(stationSlug, to, date);

                if (url == null) {
                    System.out.println("❌ Không tìm thấy URL cho: " + routeTitle);
                    csv.write(routeTitle + "||||||||||" + date + "|");
                    csv.newLine();
                    continue;
                }

                System.out.println("🔍 Crawling " + url);
                Document doc = fetch(url);
                if (doc == null) {
                    csv.write(routeTitle + "||||||||||" + date + "|" + url);
                    csv.newLine();
                    continue;
                }

                Set<String> rows = parseTickets(doc);
                if (rows.isEmpty()) {
                    csv.write(routeTitle + "||||||||||" + date + "|" + url);
                    csv.newLine();
                } else {
                    for (String r : rows) {
                        csv.write(routeTitle + "|" + r + "|" + date + "|" + url);
                        csv.newLine();
                    }
                }
                csv.flush();
                Thread.sleep(POLITENESS_MS);
            }
        }
        System.out.println("✅ Done. Output saved to " + outputFile);
    }

    private String buildAndFindStationUrl(String stationSlug, String to, String date) {
        String toSlug = toUrlSlug(to);
        // Patterns seen on VeXeRe (your sample):
        // https://vexere.com/vi-VN/ve-xe-khach-tu-ben-xe-mien-dong-di-da-nang-2765t1151.html?date=...
        String[] basePatterns = new String[] {
                "https://vexere.com/vi-VN/ve-xe-khach-tu-%s-di-%s", // with 'khach'
                "https://vexere.com/vi-VN/ve-xe-tu-%s-di-%s" // fallback without 'khach'
        };

        // Try with common suffix combos and also without IDs
        String[] idSuffixes = new String[] {
                // keep an empty suffix first (VeXeRe often redirects to canonical with IDs)
                "",
                // known style: -<fromId>t<toId>
                "-2656t1291", "-2765t1151", // include a couple of common ones as probes (harmless if 404/redirect)
                // try double id form sometimes used: -<fromId>t<toId>-<meta>
                "-2765t1151-20454"
        };

        for (String bp : basePatterns) {
            for (String id : idSuffixes) {
                String url = String.format(bp, stationSlug, toSlug) + (id.isEmpty() ? "" : id) + ".html";
                url = addDateToUrl(url, date);
                Document d = fetch(url);
                if (d != null && looksLikeValidRoutePage(d)) {
                    return url;
                }
                // small pause between attempts
                try {
                    Thread.sleep(400);
                } catch (InterruptedException ignored) {
                }
            }
            // Also try without .html (some pages resolve)
            String noHtml = String.format(bp, stationSlug, toSlug);
            noHtml = addDateToUrl(noHtml, date);
            Document d2 = fetch(noHtml);
            if (d2 != null && looksLikeValidRoutePage(d2)) {
                return noHtml;
            }
        }

        // Fallback: try generic route listing (not station-specific) to at least get
        // results
        String general = String.format("https://vexere.com/vi-VN/ve-xe-tu-%s-di-%s", toUrlSlug(stationSlug), toSlug);
        general = addDateToUrl(general, date);
        Document dg = fetch(general);
        if (dg != null && looksLikeValidRoutePage(dg)) {
            return general;
        }

        return null;
    }

    private boolean looksLikeValidRoutePage(Document doc) {
        if (!doc.select(".ticket, [data-testid='ticket-item'], .bus-item").isEmpty())
            return true;
        String title = doc.title() == null ? "" : doc.title().toLowerCase();
        if (title.contains("vé xe"))
            return true;
        // Consider "no trips" still a valid page (we found the route)
        if (!doc.select(".no-trip, .no-result").isEmpty())
            return true;
        return false;
    }

    private String addDateToUrl(String baseUrl, String date) {
        if (baseUrl.contains("?date="))
            return baseUrl;
        String sep = baseUrl.contains("?") ? "&" : "?";
        return baseUrl + sep + "date=" + date + "&nation=84";
    }

    private Document fetch(String url) {
        try {
            Request req = new Request.Builder()
                    .url(url)
                    .header("User-Agent", UA)
                    .header("Accept-Language", "vi,en;q=0.9")
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
                    .header("Cache-Control", "no-cache")
                    .build();

            try (Response resp = http.newCall(req).execute()) {
                if (!resp.isSuccessful() || resp.body() == null) {
                    System.out.println("❌ [http] " + resp.code() + " for " + url);
                    return null;
                }
                String html = new String(resp.body().bytes(), StandardCharsets.UTF_8);
                return Jsoup.parse(html, url);
            }
        } catch (Exception e) {
            System.out.println("❌ [err] " + e.getMessage() + " for " + url);
            return null;
        }
    }

    private Set<String> parseTickets(Document doc) {
        Set<String> rows = new LinkedHashSet<>();
        String[] ticketSelectors = {
                "div.ticket",
                "div[id^=ticket-][data-company-name]",
                ".ticket-item",
                ".bus-item",
                "[data-testid='ticket-item']"
        };
        for (String sel : ticketSelectors) {
            for (Element t : doc.select(sel)) {
                String data = extractTicketData(t);
                if (!data.isEmpty())
                    rows.add(data);
            }
            if (!rows.isEmpty())
                break;
        }
        return rows;
    }

    private String extractTicketData(Element ticket) {
        String[][] sels = {
                { ".bus-name", ".company-name", "[data-testid='company-name']", ".operator-name" }, // busName
                { ".seat-type", ".bus-type", ".vehicle-type", "[data-testid='seat-type']" }, // seatType
                { ".from .hour", ".departure-time", "[data-testid='departure-time']", ".time-from" }, // fromHour
                { ".from .place", ".departure-location", "[data-testid='departure-location']", ".location-from" }, // fromPlace
                { ".to .hour", ".content-to-info .hour", ".arrival-time", "[data-testid='arrival-time']", ".time-to" }, // toHour
                { ".to .place", ".content-to-info .place", ".arrival-location", "[data-testid='arrival-location']",
                        ".location-to" }, // toPlace
                { ".duration", ".trip-duration", "[data-testid='duration']", ".travel-time" }, // duration
                { ".fare div", ".price", ".ticket-price", "[data-testid='price']", ".cost" }, // price
                { ".seat-available", ".available-seats", "[data-testid='available-seats']", ".seats-left" } // seatAvailable
        };

        String[] v = new String[sels.length];
        for (int i = 0; i < sels.length; i++) {
            v[i] = "";
            for (String s : sels[i]) {
                Element el = ticket.selectFirst(s);
                if (el != null && !el.text().trim().isEmpty()) {
                    v[i] = el.text().trim();
                    break;
                }
            }
        }
        if (!v[0].isEmpty()) {
            return String.join("|",
                    esc(v[0]), esc(v[1]), esc(v[2]), esc(v[3]),
                    esc(v[4]), esc(v[5]), esc(v[6]), esc(v[7]), esc(v[8]));
        }
        return "";
    }

    private String toUrlSlug(String text) {
        String cleaned = text.replaceAll("\\s*-\\s*[^-]+$", "").trim(); // drop trailing province if present
        return cleaned.toLowerCase()
                .replaceAll("à|á|ạ|ả|ã|â|ầ|ấ|ậ|ẩ|ẫ|ă|ằ|ắ|ặ|ẳ|ẵ", "a")
                .replaceAll("è|é|ẹ|ẻ|ẽ|ê|ề|ế|ệ|ể|ễ", "e")
                .replaceAll("ì|í|ị|ỉ|ĩ", "i")
                .replaceAll("ò|ó|ọ|ỏ|õ|ô|ồ|ố|ộ|ổ|ỗ|ơ|ờ|ớ|ợ|ở|ỡ", "o")
                .replaceAll("ù|ú|ụ|ủ|ũ|ư|ừ|ứ|ự|ử|ữ", "u")
                .replaceAll("ỳ|ý|ỵ|ỷ|ỹ", "y")
                .replaceAll("đ", "d")
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-|-$", "");
    }

    private static String esc(String s) {
        if (s == null || s.isEmpty())
            return "";
        String v = s.replace("\"", "\"\"");
        if (v.contains("|") || v.contains(",") || v.contains("\"") || v.contains("\n")) {
            return "\"" + v + "\"";
        }
        return v;
    }
}
