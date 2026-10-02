package com.mymods.mclauncher.auth;

import java.util.List;
import java.util.function.Consumer;

import com.mymods.mclauncher.config.LauncherConfig;
import com.mymods.mclauncher.util.Http;
import com.google.gson.JsonObject;

/**
 * Ely.by login: a Yggdrasil-compatible API at {@code https://authserver.ely.by}.
 *
 * <p>Ely requires two-factor authentication for many accounts, expressed by sending
 * {@code password:totp} in the password field. This implementation handles that handshake
 * automatically via {@link LoginRequest.NeedsMoreInput}.
 */
public final class ElyAuthenticator implements Authenticator {

    public static final String FIELD_LOGIN = "login";
    public static final String FIELD_PASSWORD = "password";
    public static final String FIELD_TOTP = "totp";

    private static final AuthProviderInfo INFO =
            new AuthProviderInfo(AuthProviderInfo.ELY, "Ely.by", true);

    private final LauncherConfig config;
    private final TokenStore tokens;
    private final String clientToken = "mclauncher";

    public ElyAuthenticator(LauncherConfig config, TokenStore tokens) {
        this.config = config;
        this.tokens = tokens;
    }

    @Override
    public AuthProviderInfo info() {
        return INFO;
    }

    @Override
    public List<String> requiredFields() {
        return List.of(FIELD_LOGIN, FIELD_PASSWORD);
    }

    @Override
    public String validateConfig(LauncherConfig cfg) {
        String root = apiRoot();
        return root.isBlank() ? "Укажите адрес Ely API в настройках" : null;
    }

    /** The configured API root without a trailing slash. Also used as the agent argument. */
    public String apiRoot() {
        String root = config.authServerUrl == null ? "" : config.authServerUrl.trim();
        while (root.endsWith("/")) {
            root = root.substring(0, root.length() - 1);
        }
        return root;
    }

    @Override
    public AuthSession login(LoginRequest request, Consumer<String> onProgress)
            throws LoginRequest.NeedsMoreInput, LoginRequest.Rejected {

        String login = require(request, FIELD_LOGIN);
        String password = require(request, FIELD_PASSWORD);
        String totp = request.fields().get(FIELD_TOTP);

        JsonObject body = new JsonObject();
        body.addProperty("username", login);
        body.addProperty("password", totp == null || totp.isBlank() ? password : password + ":" + totp.trim());
        body.addProperty("clientToken", clientToken);
        body.addProperty("requestUser", true);

        onProgress.accept("Авторизация на " + apiRoot() + " ...");

        Http.JsonResponse res;
        try {
            res = Http.postJson(apiRoot() + "/auth/authenticate", body);
        } catch (Exception e) {
            throw new LoginRequest.Rejected("Сеть недоступна: " + e.getMessage());
        }

        if (!res.ok()) {
            String message = res.errorMessage() == null ? res.describe() : res.errorMessage();
            // Ely signals 2FA with this exact pair of status and message.
            if (res.status() == 401 && message != null && message.contains("two factor")) {
                throw new LoginRequest.NeedsMoreInput(
                        "Аккаунт защищён 2FA. Введите код из приложения-аутентификатора.",
                        java.util.Map.of(FIELD_TOTP, ""));
            }
            throw new LoginRequest.Rejected(message);
        }

        return toSession(res.body(), apiRoot());
    }

    private AuthSession toSession(JsonObject body, String apiRoot) {
        if (body == null || !body.has("accessToken")) {
            throw new IllegalStateException("Ely did not return an accessToken");
        }
        JsonObject profile = body.getAsJsonObject("selectedProfile");
        String username = profile.get("name").getAsString();
        String uuid = profile.get("id").getAsString().replace("-", "");
        String accessToken = body.get("accessToken").getAsString();
        String clientToken = body.has("clientToken") ? body.get("clientToken").getAsString() : this.clientToken;

        tokens.put(AuthProviderInfo.ELY, new TokenStore.CachedSession(
                username, uuid, accessToken, clientToken, null, "mojang", apiRoot, 0L));

        return new ElySession(username, uuid, accessToken, clientToken, apiRoot);
    }

    /**
     * Exchanges a cached token for a fresh one, so the launcher can survive Ely sessions that
     * expire between launches without asking for the password again.
     */
    public AuthSession restore(Consumer<String> onProgress) throws LoginRequest.Rejected {
        TokenStore.CachedSession cached = tokens.get(AuthProviderInfo.ELY);
        if (cached == null) {
            throw new LoginRequest.Rejected("Нет сохранённого Ely-токена");
        }
        String root = cached.apiRoot() == null || cached.apiRoot().isBlank() ? apiRoot() : cached.apiRoot();

        JsonObject body = new JsonObject();
        body.addProperty("accessToken", cached.accessToken());
        body.addProperty("clientToken", cached.clientToken() == null ? clientToken : cached.clientToken());
        body.addProperty("requestUser", true);

        onProgress.accept("Обновление Ely-токена ...");
        Http.JsonResponse res;
        try {
            res = Http.postJson(root + "/auth/refresh", body);
        } catch (Exception e) {
            throw new LoginRequest.Rejected("Сеть недоступна: " + e.getMessage());
        }
        if (!res.ok()) {
            tokens.remove(AuthProviderInfo.ELY);
            throw new LoginRequest.Rejected(res.describe());
        }
        return toSession(res.body(), root);
    }

    @Override
    public void signout(AuthSession session) throws Exception {
        if (!(session instanceof ElySession ely)) {
            return;
        }
        JsonObject body = new JsonObject();
        body.addProperty("accessToken", ely.accessToken());
        body.addProperty("clientToken", ely.clientToken());
        Http.postJson(ely.apiRoot() + "/auth/invalidate", body);
        tokens.remove(AuthProviderInfo.ELY);
    }

    private static String require(LoginRequest request, String field) throws LoginRequest.Rejected {
        String value = request.fields().get(field);
        if (value == null || value.isBlank()) {
            throw new LoginRequest.Rejected("Заполните поле: " + field);
        }
        return value.trim();
    }
}