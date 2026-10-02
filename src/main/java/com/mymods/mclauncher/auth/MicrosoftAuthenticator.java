package com.mymods.mclauncher.auth;

import java.net.http.HttpRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import com.mymods.mclauncher.config.LauncherConfig;
import com.mymods.mclauncher.util.Http;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Microsoft/Xbox login using the OAuth 2.0 device code flow, followed by the
 * Xbox Live -> XSTS -> Minecraft Services token exchange.
 *
 * <p>The device code flow needs no embedded browser: the launcher shows a short code, the user
 * types it at microsoft.com/link, and the launcher polls until consent is given.
 *
 * <p>The Azure application (client) id must belong to an app registered for the
 * "Xbox Live sign-in" delegated permission. Minecraft's own first-party app id cannot be reused,
 * so the user registers their own and puts the id in settings.
 */
public final class MicrosoftAuthenticator implements Authenticator {

    public static final String FIELD_CLIENT_ID = "clientId";

    private static final String DEVICE_CODE_URL =
            "https://login.microsoftonline.com/consumers/oauth2/v2.0/devicecode";
    private static final String TOKEN_URL =
            "https://login.microsoftonline.com/consumers/oauth2/v2.0/token";
    private static final String XBL_URL = "https://user.auth.xboxlive.com/user/authenticate";
    private static final String XSTS_URL = "https://xsts.auth.xboxlive.com/xsts/authorize";
    private static final String MC_SERVICES = "https://api.minecraftservices.com";

    private static final String SCOPE = "XboxLive.signin offline_access";
    private static final AuthProviderInfo INFO =
            new AuthProviderInfo(AuthProviderInfo.MICROSOFT, "Microsoft (Xbox)", true);

    private final LauncherConfig config;
    private final TokenStore tokens;

    public MicrosoftAuthenticator(LauncherConfig config, TokenStore tokens) {
        this.config = config;
        this.tokens = tokens;
    }

    @Override
    public AuthProviderInfo info() {
        return INFO;
    }

    @Override
    public List<String> requiredFields() {
        return List.of(FIELD_CLIENT_ID);
    }

    @Override
    public String validateConfig(LauncherConfig cfg) {
        String id = cfg.microsoftClientId == null ? "" : cfg.microsoftClientId.trim();
        return id.isBlank() ? "Нужен Azure client id для входа через Microsoft" : null;
    }

    private String clientId(LoginRequest request) {
        String fromForm = request == null ? null : request.fields().get(FIELD_CLIENT_ID);
        String value = fromForm != null && !fromForm.isBlank() ? fromForm : config.microsoftClientId;
        return value == null ? "" : value.trim();
    }

    @Override
    public AuthSession login(LoginRequest request, Consumer<String> onProgress)
            throws LoginRequest.Rejected {

        String clientId = clientId(request);
        if (clientId.isBlank()) {
            throw new LoginRequest.Rejected("Укажите Azure client id (Настройки -> Microsoft)");
        }

        Map<String, String> form = new HashMap<>();
        form.put("client_id", clientId);
        form.put("scope", SCOPE);
        Http.JsonResponse deviceCode;
        try {
            deviceCode = Http.postForm(DEVICE_CODE_URL, form);
        } catch (Exception e) {
            throw new LoginRequest.Rejected("Сеть недоступна: " + e.getMessage());
        }
        if (!deviceCode.ok() || !deviceCode.body().has("device_code")) {
            throw new LoginRequest.Rejected("Microsoft отклонил запрос: " + deviceCode.describe());
        }

        JsonObject body = deviceCode.body();
        String code = body.get("user_code").getAsString();
        String verifyUrl = body.get("verification_uri").getAsString();
        long interval = body.has("interval") ? body.get("interval").getAsLong() : 5;
        long expiresIn = body.has("expires_in") ? body.get("expires_in").getAsLong() : 900;

        onProgress.accept("Откройте " + verifyUrl);
        onProgress.accept("Введите код: " + code);

        JsonObject oauth = awaitConsent(body.get("device_code").getAsString(), clientId, interval, expiresIn, onProgress);

        String refreshToken = oauth.has("refresh_token") ? oauth.get("refresh_token").getAsString() : null;
        return exchangeForMinecraft(oauth.get("access_token").getAsString(), refreshToken, onProgress);
    }

    /** Polls the token endpoint until the user finishes signing in, or the code expires. */
    private JsonObject awaitConsent(String deviceCode, String clientId, long intervalSeconds,
                                    long expiresInSeconds, Consumer<String> onProgress)
            throws LoginRequest.Rejected {

        Instant deadline = Instant.now().plusSeconds(expiresInSeconds);
        while (Instant.now().isBefore(deadline)) {
            try {
                Thread.sleep(Math.max(1, intervalSeconds) * 1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new LoginRequest.Cancelled();
            }

            Map<String, String> form = new HashMap<>();
            form.put("client_id", clientId);
            form.put("device_code", deviceCode);
            form.put("grant_type", "urn:ietf:params:oauth:grant-type:device_code");

            Http.JsonResponse res;
            try {
                res = Http.postForm(TOKEN_URL, form);
            } catch (Exception e) {
                throw new LoginRequest.Rejected("Сеть недоступна: " + e.getMessage());
            }
            if (res.ok() && res.body().has("access_token")) {
                return res.body();
            }

            String error = res.error();
            if ("authorization_pending".equals(error)) {
                continue;
            }
            if ("slow_down".equals(error)) {
                intervalSeconds += 5;
                continue;
            }
            if ("expired_token".equals(error) || "access_denied".equals(error)) {
                throw new LoginRequest.Cancelled();
            }
            throw new LoginRequest.Rejected(res.describe());
        }
        throw new LoginRequest.Rejected("Код авторизации истёк");
    }

    /** Microsoft token -> RPS token -> XSTS token -> Minecraft profile. */
    private AuthSession exchangeForMinecraft(String msToken, String refreshToken, Consumer<String> onProgress)
            throws LoginRequest.Rejected {

        onProgress.accept("Обмен токена Xbox Live ...");
        JsonObject rpsProps = new JsonObject();
        rpsProps.addProperty("AuthMethod", "RPS");
        rpsProps.addProperty("SiteName", "user.auth.xboxlive.com");
        // RpsTicket is a single prefixed string here, not a list.
        rpsProps.addProperty("RpsTicket", "d=" + msToken);

        JsonObject rpsBody = new JsonObject();
        rpsBody.add("Properties", rpsProps);
        rpsBody.addProperty("RelyingParty", "http://auth.xboxlive.com");
        rpsBody.addProperty("TokenType", "JWT");

        Http.JsonResponse rps = post(XBL_URL, rpsBody, "Xbox Live: ");

        onProgress.accept("Запрос XSTS ...");
        JsonObject xstsProps = new JsonObject();
        JsonArray sandboxTokens = new JsonArray();
        sandboxTokens.add("d=" + rps.body().get("Token").getAsString());
        xstsProps.addProperty("SandboxId", "RETAIL");
        xstsProps.add("UserTokens", sandboxTokens);

        JsonObject xstsBody = new JsonObject();
        xstsBody.add("Properties", xstsProps);
        xstsBody.addProperty("RelyingParty", "rp://api.minecraftservices.com/");
        xstsBody.addProperty("TokenType", "JWT");

        Http.JsonResponse xsts = post(XSTS_URL, xstsBody, "XSTS: ");
        if (xsts.status() == 401) {
            throw new LoginRequest.Rejected(
                    "Аккаунт не имеет доступа к Minecraft. Нужен owning или gamepass у аккаунта Xbox.");
        }

        String mcToken = xsts.body().get("Token").getAsString();
        onProgress.accept("Загрузка профиля Minecraft ...");

        HttpRequest profileRequest = Http.request(MC_SERVICES + "/minecraft/profile")
                .header("Authorization", "Bearer " + mcToken)
                .GET()
                .build();
        Http.JsonResponse profile;
        try {
            profile = Http.send(profileRequest, "Профиль Minecraft: ");
        } catch (Exception e) {
            throw new LoginRequest.Rejected("Сеть недоступна: " + e.getMessage());
        }
        if (!profile.ok()) {
            throw new LoginRequest.Rejected(profile.describe());
        }

        String username = profile.body().get("name").getAsString();
        String uuid = profile.body().get("id").getAsString().replace("-", "");

        tokens.put(AuthProviderInfo.MICROSOFT, new TokenStore.CachedSession(
                username, uuid, mcToken, null, refreshToken, "mojang", null, 0L));

        return new MicrosoftSession(username, uuid, mcToken, refreshToken);
    }

    /** Silently re-authenticates from a cached Microsoft refresh token when one is available. */
    public AuthSession restore(Consumer<String> onProgress) throws LoginRequest.Rejected {
        TokenStore.CachedSession cached = tokens.get(AuthProviderInfo.MICROSOFT);
        if (cached == null) {
            throw new LoginRequest.Rejected("Нет сохранённого Microsoft-токена");
        }
        String clientId = config.microsoftClientId == null ? "" : config.microsoftClientId.trim();
        if (clientId.isBlank()) {
            throw new LoginRequest.Rejected("Нужен Azure client id для обновления токена");
        }

        Map<String, String> form = new HashMap<>();
        form.put("client_id", clientId);
        form.put("refresh_token", cached.refreshToken());
        form.put("grant_type", "refresh_token");
        form.put("scope", SCOPE);

        Http.JsonResponse res;
        try {
            res = Http.postForm(TOKEN_URL, form);
        } catch (Exception e) {
            throw new LoginRequest.Rejected("Сеть недоступна: " + e.getMessage());
        }
        if (!res.ok()) {
            tokens.remove(AuthProviderInfo.MICROSOFT);
            throw new LoginRequest.Rejected(res.describe());
        }
        return exchangeForMinecraft(res.body().get("access_token").getAsString(),
                res.body().get("refresh_token").getAsString(), onProgress);
    }

    @Override
    public void signout(AuthSession session) {
        tokens.remove(AuthProviderInfo.MICROSOFT);
    }

    private static Http.JsonResponse post(String url, JsonObject body, String prefix)
            throws LoginRequest.Rejected {
        try {
            Http.JsonResponse res = Http.postJson(url, body);
            if (!res.ok()) {
                throw new LoginRequest.Rejected(prefix + res.describe());
            }
            return res;
        } catch (LoginRequest.Rejected e) {
            throw e;
        } catch (Exception e) {
            throw new LoginRequest.Rejected(prefix + e.getMessage());
        }
    }

    /** How long a cached Microsoft session should be considered fresh. */
    public static Duration cacheLifetime() {
        return Duration.ofHours(12);
    }
}