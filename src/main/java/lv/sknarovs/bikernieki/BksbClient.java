package lv.sknarovs.bikernieki;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Loads bksb.lv month pages. The site can be slow, so each page gets two time-limited attempts. */
final class BksbClient implements MonthSource {
    static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    private static final int ATTEMPTS = 2;
    private static final DateTimeFormatter YEAR_SLASH_MONTH = DateTimeFormatter.ofPattern("yyyy/MM");

    private final String baseUrl;
    private final Duration timeout;
    private final Duration attemptCap;
    private final Duration backoff;
    private final HttpClient client;

    BksbClient() {
        this(EventParser.BASE_URL, Duration.ofSeconds(20), Duration.ofSeconds(60), Duration.ofSeconds(2));
    }

    /**
     * @param timeout    connect timeout and the time allowed until the response headers arrive
     * @param attemptCap upper bound for a whole attempt, so a stalled response body can't hang the run
     * @param backoff    pause before the second attempt
     */
    BksbClient(String baseUrl, Duration timeout, Duration attemptCap, Duration backoff) {
        this.baseUrl = baseUrl;
        this.timeout = timeout;
        this.attemptCap = attemptCap;
        this.backoff = backoff;
        this.client = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    static String monthUrl(String baseUrl, YearMonth month) {
        return baseUrl + "/index.php/2014-01-03-13-49-44/month.calendar/" + month.format(YEAR_SLASH_MONTH) + "/01/-";
    }

    @Override
    public Optional<String> fetch(YearMonth month) {
        String url = monthUrl(baseUrl, month);
        System.out.println("[*] Fetching calendar: " + url);
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", USER_AGENT)
                .timeout(timeout)
                .GET()
                .build();
        String error = "";
        try {
            for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
                if (attempt > 1) {
                    Thread.sleep(backoff);
                }
                try {
                    return Optional.of(send(request));
                } catch (IOException | ExecutionException | TimeoutException e) {
                    error = describe(e);
                    System.out.println("[!] Attempt " + attempt + "/" + ATTEMPTS + " failed for "
                            + month.format(YEAR_SLASH_MONTH) + ": " + error);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
        System.out.println("[!] Error loading calendar for " + month.format(YEAR_SLASH_MONTH)
                + " after retries: " + error);
        return Optional.empty();
    }

    private String send(HttpRequest request)
            throws IOException, ExecutionException, TimeoutException, InterruptedException {
        CompletableFuture<HttpResponse<String>> pending =
                client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> response;
        try {
            response = pending.get(attemptCap.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            pending.cancel(true);
            throw e;
        }
        if (response.statusCode() < 200 || response.statusCode() > 299) {
            throw new IOException("HTTP " + response.statusCode());
        }
        return response.body();
    }

    private String describe(Exception e) {
        if (e instanceof TimeoutException) {
            return "no complete response within " + attemptCap.toSeconds() + " s";
        }
        Throwable cause = e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e;
        return cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
    }
}
