package com.example;

import okhttp3.*;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;

public class BlogReviewCrawlerSimple {
    private static final String START = "https://blog.vexere.com/phuong-tien-du-lich/xe-khach/review-nha-xe/";
    // private static final String START = "https://blog.vexere.com/ben-xe-khach/";
    private static final String DOMAIN = "blog.vexere.com";
    private static final String UA = "Mozilla/5.0 (compatible; VXRBlogCrawler/1.0; +mailto:you@example.com)";
    private static final long POLITENESS_MS = 1200;
    private static final int MAX_PAGES = 94; // ✅ chỉ crawl 10 trang

    private final OkHttpClient http = new OkHttpClient.Builder()
            .callTimeout(Duration.ofSeconds(30))
            .followRedirects(true)
            .build();

    public static void main(String[] args) throws Exception {
        new BlogReviewCrawlerSimple().run();
    }

    public void run() throws Exception {
        String url = START;
        int page = 1;

        try (BufferedWriter csv = new BufferedWriter(new FileWriter("post_titles.csv"))) {
            csv.write("title,url");
            csv.newLine();

            Set<String> seen = new HashSet<>();

            while (url != null && page <= MAX_PAGES) {
                Document doc = fetch(url);
                if (doc == null)
                    break;

                System.out.println("[page] " + page + " -> " + url);

                // lấy tiêu đề trong h3.post-title > a
                for (Element a : doc.select("h3.post-title > a")) {
                    String title = a.text().trim();
                    String link = a.absUrl("href");
                    String slug = URI.create(link).getPath(); // "/xe-an-hoa-hiep-di-da-lat-tu-ca-mau/"
                    slug = slug.replaceAll("^/|/$", ""); // remove leading/trailing slash
                    String firstPart = slug.split("-di-")[0]; // cut before "-di-"
                    System.out.println(firstPart + "asd");
                    // Result: "xe-an-hoa-hiep"
                    if (title.isEmpty() || link.isEmpty())
                        continue;
                    if (!sameDomain(link))
                        continue;

                    if (seen.add(link)) {
                        csv.write(escapeCsv(firstPart));
                        csv.newLine();
                    }
                }

                // tìm link trang tiếp theo
                String next = null;
                Element nextA = doc
                        .selectFirst(".nav-links a.next, a[rel=next], .pagination a.next, .page-numbers.next");
                if (nextA != null) {
                    next = nextA.absUrl("href");
                } else {
                    next = guessNextPage(url, page + 1);
                }

                url = next;
                page++;

                Thread.sleep(POLITENESS_MS);
            }
        }

        System.out.println("Done. Saved to post_titles.csv");
    }

    // ================= helpers =================
    private Document fetch(String url) {
        try {
            Request req = new Request.Builder()
                    .url(url)
                    .header("User-Agent", UA)
                    .header("Accept-Language", "vi,en;q=0.9")
                    .build();
            try (Response resp = http.newCall(req).execute()) {
                if (!resp.isSuccessful() || resp.body() == null) {
                    System.out.println("[http] " + resp.code() + " for " + url);
                    return null;
                }
                String html = new String(resp.body().bytes(), StandardCharsets.UTF_8);
                return Jsoup.parse(html, url);
            }
        } catch (Exception e) {
            System.out.println("[err] " + e.getMessage());
            return null;
        }
    }

    private boolean sameDomain(String url) {
        try {
            return url.contains(DOMAIN);
        } catch (Exception e) {
            return false;
        }
    }

    private static String escapeCsv(String s) {
        if (s == null)
            return "";
        String v = s.replace("\"", "\"\"");
        if (v.contains(",") || v.contains("\"") || v.contains("\n")) {
            return "\"" + v + "\"";
        }
        return v;
    }

    private static String guessNextPage(String current, int nextIndex) {
        if (current.endsWith("/")) {
            return current + "page/" + nextIndex + "/";
        }
        if (current.matches(".*/page/\\d+/?$")) {
            return current.replaceAll("/page/\\d+/?$", "/page/" + nextIndex + "/");
        }
        return current + "/page/" + nextIndex + "/";
    }
}
