package com.example;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.openqa.selenium.*;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class NhaXeVexereSeleniumCrawler {

    private static final String BASE = "https://vexere.com/vi-VN/";

    public static void main(String[] args) throws Exception {
        String slugFile = "nha_xe.txt"; // chứa list slug
        String outputFile = "routes.txt"; // kết quả

        List<String> slugs = readSlugs(slugFile);

        // Headless Chrome
        ChromeOptions opts = new ChromeOptions();
        opts.addArguments("--headless=new", "--disable-gpu", "--no-sandbox");
        WebDriver driver = new ChromeDriver(opts);
        driver.manage().timeouts().implicitlyWait(Duration.ofSeconds(10));

        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(outputFile), StandardCharsets.UTF_8))) {

            for (String slug : slugs) {
                String url = BASE + slug;
                System.out.println("🔎 Crawling: " + url);

                Set<String> routes = crawlAllPages(driver, url);

                if (routes.isEmpty()) {
                    writer.write("[" + slug + "] Không tìm thấy tuyến đường");
                    writer.newLine();
                } else {
                    for (String route : routes) {
                        writer.write("[" + slug + "] " + route);
                        writer.newLine();
                    }
                }

                writer.flush();
            }
        } finally {
            driver.quit();
        }

        System.out.println("✅ Done. Saved to " + outputFile);
    }

    /** Crawl tất cả các trang cho một nhà xe */
    static Set<String> crawlAllPages(WebDriver driver, String url) throws Exception {
        Set<String> routes = new LinkedHashSet<>();

        driver.get(url);
        Thread.sleep(1000); // chờ JS render lần đầu

        // Extract company name from the page
        // Extract company name from the page (robust)
        String companyName = "";
        try {
            String html0 = driver.getPageSource();
            Document doc0 = Jsoup.parse(html0, url);

            // 1) Hero heading (ổn định nhất): div class bắt đầu bằng
            // HeroSection__Information + p class có "Heading01"
            Element heroTitle = doc0.selectFirst("div[class^=HeroSection__Information] p[class*='Heading01']");
            if (heroTitle != null && !heroTitle.text().trim().isEmpty()) {
                companyName = heroTitle.text().trim();
            }

            // 2) Fallback: breadcrumb phần cuối cùng
            if (companyName.isEmpty()) {
                Element bcLast = doc0.selectFirst(".BreadCrumb__Container-sc-9ju0a1-0 *:last-child");
                if (bcLast != null && !bcLast.text().trim().isEmpty()) {
                    companyName = bcLast.text().trim();
                }
            }

            // 3) Fallback: <title> (loại bỏ đuôi “- VeXeRe ...”)
            if (companyName.isEmpty()) {
                String title = doc0.title();
                if (title != null && !title.isBlank()) {
                    title = title.replaceFirst("\\s*-\\s*VeXeRe.*$", "").trim();
                    if (!title.isEmpty())
                        companyName = title;
                }
            }
        } catch (Exception e) {
            System.err.println("Could not extract company name: " + e.getMessage());
        }

        while (true) {
            // Lấy HTML hiện tại và parse bằng Jsoup
            String html = driver.getPageSource();
            Document doc = Jsoup.parse(html, url);

            // Lấy tất cả tuyến đường
            for (Element row : doc.select("table.table--fare tbody tr")) {
                Element a = row.selectFirst("td.route--name h3 a");
                if (a != null) {
                    String text = a.text().trim();
                    if (!text.isEmpty()) {
                        // Add company name to the route if available
                        if (!companyName.isEmpty()) {
                            routes.add(text + " | " + companyName);
                        } else {
                            routes.add(text);
                        }
                    }
                }
            }

            // Kiểm tra nút Next
            WebElement nextLi;
            try {
                nextLi = driver.findElement(By.cssSelector("ul.ant-pagination li.ant-pagination-next"));
            } catch (NoSuchElementException e) {
                break; // không có phân trang
            }
            String classes = nextLi.getAttribute("class");
            if (classes != null && classes.contains("ant-pagination-disabled")) {
                break; // hết trang
            }

            // Click Next
            WebElement nextLink = nextLi.findElement(By.cssSelector("a.ant-pagination-item-link"));
            ((JavascriptExecutor) driver).executeScript("arguments[0].click();", nextLink);

            Thread.sleep(1200); // chờ nội dung mới load
        }

        return routes;
    }

    /** Đọc slug từ file txt */
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
