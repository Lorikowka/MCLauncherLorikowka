package com.mymods.mclauncher.util;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Thin wrapper over {@link HttpClient} with helpers used across auth and download flows. */
public final class Http {

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private Http() {}

    public static HttpClient client() {
        return CLIENT;
    }

    public static HttpRequest.Builder request(String url) {
        return HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(5))
                .header("User-Agent", "MCLauncher/0.1 (custom launcher)");
    }

    public static JsonObject getJson(String url) throws IOException, InterruptedException {
        HttpRequest req = request(url).GET().build();
        HttpResponse<String> res = CLIENT.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (res.statusCode() / 100 != 2) {
            throw new IOException("GET " + url + " -> HTTP " + res.statusCode() + ": " + trim(res.body()));
        }
        return JsonParser.parseString(res.body()).getAsJsonObject();
    }

    /** GET and parse a top-level JSON array, e.g. the Adoptium assets endpoint. */
    public static JsonArray getJsonArray(String url) throws IOException, InterruptedException {
        HttpRequest req = request(url).GET().build();
        HttpResponse<String> res = CLIENT.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (res.statusCode() / 100 != 2) {
            throw new IOException("GET " + url + " -> HTTP " + res.statusCode() + ": " + trim(res.body()));
        }
        return JsonParser.parseString(res.body()).getAsJsonArray();
    }

    /**
     * GET and return the body as text, for endpoints that are XML rather than JSON.
     *
     * <p>Forge publishes its full version list as maven metadata, which has no JSON form. Parsing it
     * as JSON would throw something unhelpful, so the caller gets the raw text and its own parser.
     */
    public static String getText(String url) throws IOException, InterruptedException {
        HttpRequest req = request(url).GET().build();
        HttpResponse<String> res = CLIENT.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (res.statusCode() / 100 != 2) {
            throw new IOException("GET " + url + " -> HTTP " + res.statusCode() + ": " + trim(res.body()));
        }
        return res.body();
    }

    /** Sends a prepared request and parses the JSON reply without throwing on non-2xx. */
    public static JsonResponse send(HttpRequest request, String what) throws IOException, InterruptedException {
        HttpResponse<String> res = CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        JsonObject parsed = null;
        if (res.body() != null && !res.body().isBlank()) {
            try {
                parsed = JsonParser.parseString(res.body()).getAsJsonObject();
            } catch (RuntimeException e) {
                parsed = null;
            }
        }
        return new JsonResponse(res.statusCode(), parsed, res.body());
    }

    public static JsonResponse postJson(String url, JsonObject body) throws IOException, InterruptedException {
        HttpRequest req = request(url)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> res = CLIENT.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        JsonObject parsed = null;
        if (res.body() != null && !res.body().isBlank()) {
            try {
                parsed = JsonParser.parseString(res.body()).getAsJsonObject();
            } catch (RuntimeException e) {
                parsed = null;
            }
        }
        return new JsonResponse(res.statusCode(), parsed, res.body());
    }

    /** POST x-www-form-urlencoded, used by the OAuth token endpoints. */
    public static JsonResponse postForm(String url, Map<String, String> form) throws IOException, InterruptedException {
        String encoded = encodeForm(form);
        HttpRequest req = request(url)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(encoded, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> res = CLIENT.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        JsonObject parsed = null;
        if (res.body() != null && !res.body().isBlank()) {
            try {
                parsed = JsonParser.parseString(res.body()).getAsJsonObject();
            } catch (RuntimeException e) {
                parsed = null;
            }
        }
        return new JsonResponse(res.statusCode(), parsed, res.body());
    }

    public static String encodeForm(Map<String, String> form) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : form.entrySet()) {
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(urlEncode(e.getKey())).append('=').append(urlEncode(e.getValue()));
        }
        return sb.toString();
    }

    public static String urlEncode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /** Downloads to a temp file next to the target, then moves it into place. */
    public static void downloadToFile(String url, Path target) throws IOException, InterruptedException {
        Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling(target.getFileName() + ".part");
        HttpRequest req = request(url).GET().build();
        HttpResponse<Path> res = CLIENT.send(req, HttpResponse.BodyHandlers.ofFile(tmp));
        if (res.statusCode() / 100 != 2) {
            Files.deleteIfExists(tmp);
            throw new IOException("GET " + url + " -> HTTP " + res.statusCode());
        }
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
    }

    public static InputStream openStream(String url) throws IOException, InterruptedException {
        HttpRequest req = request(url).GET().build();
        HttpResponse<InputStream> res = CLIENT.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (res.statusCode() / 100 != 2) {
            res.body().close();
            throw new IOException("GET " + url + " -> HTTP " + res.statusCode());
        }
        return res.body();
    }

    public static boolean exists(String url) {
        try {
            HttpRequest req = request(url)
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .build();
            HttpResponse<Void> res = CLIENT.send(req, HttpResponse.BodyHandlers.discarding());
            return res.statusCode() / 100 == 2;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    private static String trim(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= 300 ? s : s.substring(0, 300) + "...";
    }

    /** JSON reply with status code, since auth APIs signal errors inside the body. */
    public record JsonResponse(int status, JsonObject body, String raw) {

        public boolean ok() {
            return status / 100 == 2;
        }

        public String error() {
            if (body != null && body.has("error")) {
                return body.get("error").getAsString();
            }
            return null;
        }

        public String errorMessage() {
            if (body != null && body.has("errorMessage")) {
                return body.get("errorMessage").getAsString();
            }
            if (body != null && body.has("error_description")) {
                return body.get("error_description").getAsString();
            }
            return null;
        }

        public String describe() {
            String message = errorMessage();
            String code = error();
            if (code != null && message != null && !code.equals(message)) {
                return code + ": " + message;
            }
            if (message != null) {
                return message;
            }
            return code != null ? code : "HTTP " + status;
        }
    }
}