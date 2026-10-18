package com.sudpanel.perf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class PerfReportPdfPrivacyTest {
    @TempDir Path dir;

    @Test void realPdfPrintsWithoutLoadingExternalReportResources() throws Exception {
        String chrome = System.getenv().getOrDefault("SUDPANEL_CHROME",
                "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome");
        Assumptions.assumeTrue(Files.isExecutable(Path.of(chrome)), "Chrome required for PDF integration");
        var requests = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/privacy-probe", exchange -> {
            requests.incrementAndGet();
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
        try {
            var jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:sqlite:" + dir.resolve("test.db")));
            var repo = new PerfRepository(jdbc);
            String probe = "http://127.0.0.1:" + server.getAddress().getPort() + "/privacy-probe";
            long game = repo.upsertGame("<img src='" + probe + "'>", "PDF fixture");
            long run = repo.insertRun("fictional-device", "NORMAL", String.valueOf(game), 1, 5, 1L);
            String html = new PerfReportService(repo, mock(com.sudpanel.config.AppProperties.class)).html(run, game);
            assertThat(html).contains("Content-Security-Policy", "default-src 'none'");
            assertThat(html).doesNotContain("fictional-device");
            // Deliberately introduce external resource and script probes into this synthetic report:
            // the report CSP must block them even if a future renderer accidentally adds a tag.
            html = html.replace("</body>", "<img src='" + probe + "'><script>fetch('" + probe + "')</script></body>");
            byte[] pdf = PerfReportService.renderPdf(html, chrome);
            assertThat(new String(pdf, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
            assertThat(pdf.length).isGreaterThan(1000);
            assertThat(requests.get()).isZero();
        } finally {
            server.stop(0);
        }
    }
}
