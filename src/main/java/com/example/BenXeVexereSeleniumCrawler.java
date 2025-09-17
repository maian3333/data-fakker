package com.example;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.openqa.selenium.*;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

public class BenXeVexereSeleniumCrawler {

    private static final String BASE = "https://vexere.com/vi-VN/";
    private static final Duration IMPLICIT = Duration.ofSeconds(6);
    private static final Duration EXPL_WAIT = Duration.ofSeconds(12);
    private static final int PAGE_SAFETY_MAX = 20;

    public static void main(String[] args) throws Exception {
        String slugFile = args.length > 0 ? args[0] : "ben_xe.txt";
        String outputFile = args.length > 1 ? args[1] : "routes_benxe.txt";

        List<String> slugs = readSlugs(slugFile);

        ChromeOptions opts = new ChromeOptions();
        opts.addArguments("--headless=new", "--disable-gpu", "--no-sandbox");
        opts.addArguments("--disable-blink-features=AutomationControlled");
        opts.setExperimentalOption("excludeSwitches", List.of("enable-automation"));
        opts.setExperimentalOption("useAutomationExtension", false);
        opts.addArguments(
                "user-agent=Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
        opts.addArguments("--window-size=1366,900");

        WebDriver driver = new ChromeDriver(opts);
        driver.manage().timeouts().implicitlyWait(IMPLICIT);

        try (BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(outputFile), StandardCharsets.UTF_8))) {

            for (String slug : slugs) {
                if (slug.isBlank() || slug.startsWith("#"))
                    continue;

                String url = BASE + slug.trim();
                System.out.println("Crawling: " + url);

                List<String> lines = Collections.emptyList();
                try {
                    lines = crawlBenXe(driver, url);
                    System.out.println("Found " + lines.size() + " lines for " + slug);
                } catch (Exception e) {
                    System.err.println("Skip due to error on " + slug + ": " + e.getMessage());
                }

                if (lines.isEmpty()) {
                    writer.write("[" + slug + "] Không tìm thấy tuyến đường");
                    writer.newLine();
                } else {
                    for (String line : lines) {
                        writer.write("[" + slug + "] " + line);
                        writer.newLine();
                    }
                }
                writer.flush();
            }
        } finally {
            driver.quit();
        }

        System.out.println("Done. Saved to " + outputFile);
    }

    /**
     * Returns lines with the first line = "Địa chỉ: <addr>" (if found), followed by
     * route rows.
     */
    static List<String> crawlBenXe(WebDriver driver, String url) throws Exception {
        LinkedHashSet<String> rows = new LinkedHashSet<>();
        String addressLine = null;

        driver.get(url);
        Thread.sleep(1200);

        safeClickBangGia(driver);
        waitForTable(driver);

        // Parse full DOM once
        String html = driver.getPageSource();
        Document doc = Jsoup.parse(html, url);

        // ---- Address (prefer meta[itemprop=address], fallback to visible "Trụ sở:"
        // text)
        String address = extractAddress(doc);
        if (!address.isEmpty()) {
            addressLine = "Địa chỉ: " + address;
        }

        // ---- Try Jsoup first (captures hidden rows on the current DOM)
        int jsoupCount = extractRowsFromJsoup(doc, rows);
        System.out.println("Jsoup extracted rows: " + jsoupCount);

        if (jsoupCount == 0) {
            // Fallback: paginate visible footable rows with Selenium
            resetToFirstPage(driver);
            int guard = PAGE_SAFETY_MAX;
            while (guard-- > 0) {
                extractRowsFromSeleniumVisible(driver, rows);
                if (!goToNextPage(driver))
                    break;
            }
        }

        // Build final output list (address first if present)
        List<String> out = new ArrayList<>();
        if (addressLine != null)
            out.add(addressLine);
        out.addAll(rows);
        return out;
    }

    // --------------------- Address extraction ---------------------

    private static String extractAddress(Document doc) {
        // Primary: meta itemprop="address"
        Element metaAddr = doc.selectFirst("meta[itemprop=address]");
        if (metaAddr != null) {
            String c = metaAddr.attr("content").trim();
            if (!c.isEmpty())
                return c; // e.g. "43 Lý Nam Đế, phường Trà Bá, Pleiku, province_vi Gia Lai"
        }

        // Fallback 1: any <p> that has the map-marker icon, strip "Trụ sở:" prefix
        for (Element p : doc.select("p:has(.glyphicon-map-marker)")) {
            String t = p.text().trim();
            if (!t.isEmpty()) {
                t = t.replaceFirst("(?i)^.*?Trụ\\s*sở\\s*:?", "").trim();
                if (!t.isEmpty())
                    return t;
            }
        }

        // Fallback 2: mobile header line
        Element h5 = doc.selectFirst("div.visible-xs.visible-sm h5:has(.glyphicon-map-marker)");
        if (h5 != null) {
            String t = h5.text().trim();
            t = t.replaceFirst("(?i)^.*?Trụ\\s*sở\\s*:?", "").trim();
            if (!t.isEmpty())
                return t;
        }

        return "";
    }

    // --------------------- Jsoup extraction (route | operator | price)
    // ---------------------

    private static int extractRowsFromJsoup(Document doc, Set<String> out) {
        int added = 0;
        for (Element tr : doc.select("table.table-banggia tbody tr")) {
            String route = textOrEmpty(tr.selectFirst("a.text-blue"));
            if (route.isEmpty())
                continue;

            String operator = textOrEmpty(tr.selectFirst("td.hidden-xs a.text-route"));
            if (operator.isEmpty())
                operator = textOrEmpty(tr.selectFirst(".visible-xs a.text-route"));

            // price column can be text-left on these pages
            String price = textOrEmpty(tr.selectFirst("td.hidden-xs.text-right .price .now"));
            if (price.isEmpty())
                price = textOrEmpty(tr.selectFirst("td.hidden-xs.text-left .price .now"));
            if (price.isEmpty())
                price = textOrEmpty(tr.selectFirst(".visible-xs .price .now"));

            String line = joinTriple(route, operator, price);
            if (out.add(line))
                added++;
        }
        return added;
    }

    // --------------------- Selenium extraction for visible rows
    // ---------------------

    private static void extractRowsFromSeleniumVisible(WebDriver driver, Set<String> out) {
        List<WebElement> rows = driver.findElements(By.cssSelector("table.table-banggia tbody tr"));
        for (WebElement row : rows) {
            if (!row.isDisplayed())
                continue;

            WebElement a = firstOrNull(row, By.cssSelector("a.text-blue"));
            if (a == null || !a.isDisplayed())
                continue;
            String route = safeText(a);
            if (route.isEmpty())
                continue;

            String operator = safeText(firstOrNull(row, By.cssSelector("td.hidden-xs a.text-route")));
            if (operator.isEmpty())
                operator = safeText(firstOrNull(row, By.cssSelector(".visible-xs a.text-route")));

            String price = safeText(firstOrNull(row, By.cssSelector("td.hidden-xs.text-right .price .now")));
            if (price.isEmpty())
                price = safeText(firstOrNull(row, By.cssSelector("td.hidden-xs.text-left .price .now")));
            if (price.isEmpty())
                price = safeText(firstOrNull(row, By.cssSelector(".visible-xs .price .now")));

            out.add(joinTriple(route, operator, price));
        }
    }

    // --------------------- Pagination helpers ---------------------

    private static void resetToFirstPage(WebDriver driver) {
        WebElement firstBtn = firstOrNull(driver, By.cssSelector("div.pagination ul li a[data-page='first']"));
        if (firstBtn != null) {
            try {
                ((JavascriptExecutor) driver).executeScript("arguments[0].click();", firstBtn);
                Thread.sleep(800);
            } catch (Exception ignore) {
            }
        }
    }

    private static boolean goToNextPage(WebDriver driver) {
        WebElement nextBtn = firstOrNull(driver, By.cssSelector("div.pagination ul li a[data-page='next']"));
        if (nextBtn == null)
            return false;
        WebElement parentLi = nextBtn.findElement(By.xpath("./parent::li"));
        String cls = (parentLi.getAttribute("class") + "").toLowerCase();
        if (cls.contains("disabled"))
            return false;

        try {
            ((JavascriptExecutor) driver).executeScript("arguments[0].click();", nextBtn);
            Thread.sleep(900);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // --------------------- Tab & waits ---------------------

    private static void safeClickBangGia(WebDriver driver) {
        WebElement tab = firstOrNull(driver,
                By.xpath("//ul[contains(@class,'nav-tabs')]//a[contains(normalize-space(.),'Bảng giá')]"));
        if (tab == null)
            tab = firstOrNull(driver, By.cssSelector("ul.bus-station-tabs a[href='#banggia']"));
        if (tab != null) {
            try {
                ((JavascriptExecutor) driver).executeScript("arguments[0].click();", tab);
                Thread.sleep(400);
            } catch (Exception ignore) {
            }
        }
    }

    private static void waitForTable(WebDriver driver) {
        try {
            new WebDriverWait(driver, EXPL_WAIT).until(
                    ExpectedConditions.presenceOfElementLocated(By.cssSelector("table.table-banggia")));
        } catch (TimeoutException ignore) {
        }
    }

    // --------------------- Utils ---------------------

    private static String joinTriple(String route, String operator, String price) {
        route = route == null ? "" : route.trim();
        operator = operator == null ? "" : operator.trim();
        price = price == null ? "" : price.trim();
        if (operator.isEmpty() && price.isEmpty())
            return route;
        if (price.isEmpty())
            return route + " | " + operator;
        if (operator.isEmpty())
            return route + " | " + price;
        return route + " | " + operator + " | " + price;
    }

    private static String textOrEmpty(Element el) {
        return el == null ? "" : el.text().trim();
    }

    private static String safeText(WebElement el) {
        if (el == null)
            return "";
        String t = el.getText();
        return t == null ? "" : t.trim();
    }

    private static WebElement firstOrNull(SearchContext ctx, By by) {
        List<WebElement> list = ctx.findElements(by);
        return list.isEmpty() ? null : list.get(0);
    }

    private static List<String> readSlugs(String filePath) throws IOException {
        List<String> slugs = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(new FileInputStream(filePath), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                String s = line.trim();
                if (!s.isEmpty() && !s.startsWith("#"))
                    slugs.add(s);
            }
        }
        return slugs;
    }
}
