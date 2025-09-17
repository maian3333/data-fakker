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
import java.util.regex.Pattern;
import java.util.regex.Matcher;

public class NhaXeVevereTicketCrawler {

    private static final String DOMAIN = "vexere.com";
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36";
    private static final long POLITENESS_MS = 2000; // Increased delay to be more polite

    // Default date (tomorrow) if not specified
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("dd-MM-yyyy");

    private final OkHttpClient http = new OkHttpClient.Builder()
            .callTimeout(Duration.ofSeconds(30))
            .followRedirects(true)
            .build();

    public static void main(String[] args) throws Exception {
        String inputFile = args.length > 0 ? args[0] : "routes_nhaxe.txt";
        String outputFile = args.length > 1 ? args[1] : "tickets_nhaxe.csv";
        String date = args.length > 2 ? args[2] : LocalDate.now().plusDays(1).format(DATE_FORMATTER);

        new NhaXeVevereTicketCrawler().run(inputFile, outputFile, date);
    }

    public void run(String inputFile, String outputFile, String date) throws Exception {
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(new FileInputStream(inputFile), StandardCharsets.UTF_8));
                BufferedWriter csv = new BufferedWriter(
                        new OutputStreamWriter(new FileOutputStream(outputFile), StandardCharsets.UTF_8))) {

            // Write CSV header
            csv.write("route|busName|seatType|fromHour|fromPlace|toHour|toPlace|duration|price|seatAvailable|date|url");
            csv.newLine();

            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#"))
                    continue;

                String url = null;
                String routeTitle = line;

                if (line.startsWith("http")) {
                    url = addDateToUrl(line, date);
                } else if (line.contains("ve-xe-khach")) {
                    url = addDateToUrl("https://vexere.com/vi-VN/" + line, date);
                } else if (line.startsWith("[")) {
                    url = findUrlByVexereSearch(line, date);
                }

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

    private String addDateToUrl(String baseUrl, String date) {
        if (baseUrl.contains("?date=")) {
            return baseUrl; // Already has date parameter
        }
        String separator = baseUrl.contains("?") ? "&" : "?";
        return baseUrl + separator + "date=" + date + "&nation=84";
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

        // Multiple selectors to try different page layouts
        String[] ticketSelectors = {
                "div.ticket",
                "div[id^=ticket-][data-company-name]",
                ".ticket-item",
                ".bus-item",
                "[data-testid='ticket-item']"
        };

        for (String selector : ticketSelectors) {
            for (Element ticket : doc.select(selector)) {
                String ticketData = extractTicketData(ticket);
                if (!ticketData.isEmpty()) {
                    rows.add(ticketData);
                }
            }
            if (!rows.isEmpty())
                break; // Found tickets with this selector
        }

        return rows;
    }

    private String extractTicketData(Element ticket) {
        // Multiple selector patterns for different page layouts
        String[][] selectors = {
                { ".bus-name", ".company-name", "[data-testid='company-name']", ".operator-name" },
                { ".seat-type", ".bus-type", ".vehicle-type", "[data-testid='seat-type']" },
                { ".from .hour", ".departure-time", "[data-testid='departure-time']", ".time-from" },
                { ".from .place", ".departure-location", "[data-testid='departure-location']", ".location-from" },
                { ".to .hour", ".content-to-info .hour", ".arrival-time", "[data-testid='arrival-time']", ".time-to" },
                { ".to .place", ".content-to-info .place", ".arrival-location", "[data-testid='arrival-location']",
                        ".location-to" },
                { ".duration", ".trip-duration", "[data-testid='duration']", ".travel-time" },
                { ".fare div", ".price", ".ticket-price", "[data-testid='price']", ".cost" },
                { ".seat-available", ".available-seats", "[data-testid='available-seats']", ".seats-left" }
        };

        String[] values = new String[selectors.length];

        for (int i = 0; i < selectors.length; i++) {
            values[i] = "";
            for (String sel : selectors[i]) {
                Element el = ticket.selectFirst(sel);
                if (el != null && !el.text().trim().isEmpty()) {
                    values[i] = el.text().trim();
                    break;
                }
            }
        }

        // Only return if we found at least a bus name
        if (!values[0].isEmpty()) {
            return String.join("|",
                    esc(values[0]), esc(values[1]), esc(values[2]), esc(values[3]),
                    esc(values[4]), esc(values[5]), esc(values[6]), esc(values[7]), esc(values[8]));
        }

        return "";
    }

    private String findUrlByVexereSearch(String query, String date) {
        try {
            // Extract route information from query
            Pattern pattern = Pattern.compile("\\[([^\\]]+)\\]\\s*(.+)");
            Matcher matcher = pattern.matcher(query);

            if (!matcher.matches())
                return null;

            String companySlug = matcher.group(1);
            String routeDesc = matcher.group(2);

            // Try to construct URL based on VeXeRe URL patterns
            String[] parts = routeDesc.split(" đi ");
            if (parts.length == 2) {
                String from = parts[0].trim();
                String to = parts[1].trim();

                // Convert Vietnamese text to URL-friendly format
                String fromSlug = toUrlSlug(from);
                String toSlug = toUrlSlug(to);
                String companyName = companySlug; // Keep full company slug

                // VeXeRe URL format: ve-xe-khach-{company}-tu-{from}-di-{to}-{id}.html
                // We need to try different patterns since we don't know the exact IDs
                String[] urlPatterns = {
                        "https://vexere.com/vi-VN/ve-xe-khach-%s-tu-%s-di-%s",
                        "https://vexere.com/vi-VN/ve-xe-khach-%s-tu-%s-di-%s.html"
                };

                for (String urlPattern : urlPatterns) {
                    String baseUrl = String.format(urlPattern, companyName, fromSlug, toSlug);

                    // Since we don't know the exact route IDs, we'll try to find it through search
                    // or use VeXeRe's route discovery mechanism
                    String foundUrl = findExactRouteUrl(baseUrl, from, to, companyName, date);
                    if (foundUrl != null) {
                        return foundUrl;
                    }
                }

                // If direct URL construction fails, try VeXeRe's own search
                return searchOnVexere(from, to, companyName, date);
            }
        } catch (Exception e) {
            System.out.println("❌ [vexere search err] " + e.getMessage());
        }
        return null;
    }

    private String findExactRouteUrl(String basePattern, String from, String to, String companyName, String date) {
        try {
            // Try to find the exact URL by searching VeXeRe's route listing
            // First, try the search/listing page to find route IDs
            String searchUrl = "https://vexere.com/vi-VN/search";

            // For now, we'll try some common ID patterns
            // In a real implementation, you'd need to discover these IDs dynamically
            String[] commonIdPatterns = {
                    "-2656t1291-20454", // From your example
                    // "-1234t56789-123",
                    // "-9876t54321-987",
                    "" // Try without ID suffix
            };

            String fromSlug = toUrlSlug(from);
            String toSlug = toUrlSlug(to);

            for (String idSuffix : commonIdPatterns) {
                String testUrl = String.format(
                        "https://vexere.com/vi-VN/ve-xe-khach-%s-tu-%s-di-%s%s.html?date=%s&nation=84",
                        companyName, fromSlug, toSlug, idSuffix, date);

                System.out.println("🔍 Trying URL: " + testUrl);

                Document testDoc = fetch(testUrl);
                if (testDoc != null) {
                    // Check if page has tickets or is a valid route page
                    if (!testDoc.select(".ticket, [data-testid='ticket-item'], .bus-item").isEmpty() ||
                            testDoc.title().toLowerCase().contains("vé xe") ||
                            !testDoc.select(".no-trip, .no-result").isEmpty()) {
                        return testUrl;
                    }
                }

                Thread.sleep(500); // Small delay between attempts
            }

        } catch (Exception e) {
            System.out.println("❌ [exact route search err] " + e.getMessage());
        }
        return null;
    }

    private String searchOnVexere(String from, String to, String companyName, String date) {
        try {
            System.out.println("🔍 Searching on VeXeRe for: " + from + " -> " + to + " (" + companyName + ")");

            // Try VeXeRe's search functionality
            // You could implement this by analyzing their search API endpoints
            // For now, try the main route listing approach

            String fromSlug = toUrlSlug(from);
            String toSlug = toUrlSlug(to);

            // Try general route search without specific company
            String generalSearchUrl = String.format(
                    "https://vexere.com/vi-VN/ve-xe-tu-%s-di-%s?date=%s&nation=84",
                    fromSlug, toSlug, date);

            System.out.println("🔍 Trying general search: " + generalSearchUrl);

            Document searchDoc = fetch(generalSearchUrl);
            if (searchDoc != null) {
                // Look for links to specific company routes
                for (Element link : searchDoc.select("a[href*='ve-xe-khach-" + companyName + "']")) {
                    String href = link.absUrl("href");
                    if (href.contains("vexere.com")) {
                        return addDateToUrl(href, date) + "&nation=84";
                    }
                }

                // If we found any route, return the general search URL
                if (!searchDoc.select(".ticket, [data-testid='ticket-item'], .bus-item").isEmpty()) {
                    return generalSearchUrl;
                }
            }

            return null;
        } catch (Exception e) {
            System.out.println("❌ [vexere direct search err] " + e.getMessage());
        }
        return null;
    }

    private String toUrlSlug(String text) {
        // Remove province/city suffixes and clean up location names
        String cleaned = text.replaceAll("\\s*-\\s*[^-]+$", "").trim();

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

    private static String text(Element el) {
        return el == null ? "" : el.text().trim();
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