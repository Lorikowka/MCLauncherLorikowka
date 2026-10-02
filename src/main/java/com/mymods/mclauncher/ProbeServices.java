package com.mymods.mclauncher;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.mymods.mclauncher.auth.AuthlibInjector;
import com.mymods.mclauncher.auth.ElyAuthenticator;
import com.mymods.mclauncher.mc.JavaRuntimes;
import com.mymods.mclauncher.mc.Library;
import com.mymods.mclauncher.mc.RuntimeInstaller;

/**
 * Checks the pieces that talk to third-party services, so their current shape can be verified
 * without a full UI session.
 *
 * <p>Run with {@code gradlew probeServices}.
 */
public final class ProbeServices {

    public static void main(String[] args) throws Exception {
        boolean ok = true;

        // 1. Current OS/arch detection, which every rule and URL depends on.
        System.out.println("os=" + Library.currentOs() + " arch=" + Library.currentArch());

        // 2. Ely API root, and whether the metadata endpoint answers.
        ElyAuthenticator ely = new ElyAuthenticator(
                new com.mymods.mclauncher.config.LauncherConfig(),
                new com.mymods.mclauncher.auth.TokenStore(
                        Path.of(System.getProperty("java.io.tmpdir"), "mclauncher-probe-tokens.json")));
        String apiRoot = ely.apiRoot();
        System.out.println("ely apiRoot=" + apiRoot);

        // Ely has no /authserverinfo; that is a Mojang endpoint. The Yggdrasil way to check the
        // API is to POST /auth/authenticate with an empty body and look for a validation error,
        // which means the endpoint is alive and parsing our JSON.
        var auth = com.mymods.mclauncher.util.Http.postJson(apiRoot + "/auth/authenticate",
                new com.google.gson.JsonObject());
        boolean authOk = auth.status() == 400 && auth.raw() != null
                && auth.raw().contains("credentials");
        System.out.println("ely POST /auth/authenticate -> " + auth.status() + " " + auth.raw());
        System.out.println("ely auth endpoint -> " + (authOk ? "reachable" : "unexpected reply"));
        ok &= authOk;

        // 3. authlib-injector really downloads and is a usable jar.
        Path jar = Path.of(System.getProperty("java.io.tmpdir"), "mclauncher-probe-authlib.jar");
        Files_delete(jar);
        Path ensured = AuthlibInjector.ensure(jar, System.out::println);
        System.out.println("authlib jar: " + ensured + " bytes=" + size(ensured));
        ok &= size(ensured) > 100_000;

        // 4. Adoptium runtime installs and lands directly at bin/java, with no wrapper folder.
        String url = RuntimeInstaller.resolveUrl(21);
        System.out.println("adoptium 21 -> " + url);
        ok &= url.endsWith(".zip") || url.endsWith(".tar.gz");

        Path runtimesDir = Path.of(System.getProperty("java.io.tmpdir"), "mclauncher-probe-runtimes");
        Path home = RuntimeInstaller.ensure(21, runtimesDir, System.out::println);
        JavaRuntimes.Runtime installed = JavaRuntimes.detect(home.resolve("bin")
                .resolve(Library.currentOs().equals("windows") ? "java.exe" : "java"));
        System.out.println("installed jre: " + home + " detected=" + installed);
        ok &= installed != null && installed.majorVersion() == 21;

        // 5. Java runtime resolution reports something usable.
        var runtime = JavaRuntimes.resolve(
                new JavaRuntimes.LauncherSettings("", Path.of(System.getProperty("java.io.tmpdir"), "mclauncher-probe-runtimes")),
                17, System.out::println);
        System.out.println("runtime: " + runtime.java() + " major=" + runtime.majorVersion());
        ok &= runtime.exists();

        System.out.println("\n=== RESULT ===");
        System.out.println(ok ? "PASS" : "FAIL");
        if (!ok) {
            System.exit(1);
        }
    }

    private static long size(Path path) {
        try {
            return java.nio.file.Files.size(path);
        } catch (Exception e) {
            return -1;
        }
    }

    private static void Files_delete(Path path) {
        try {
            java.nio.file.Files.deleteIfExists(path);
        } catch (Exception ignored) {
            // A leftover temp file is harmless.
        }
    }
}
