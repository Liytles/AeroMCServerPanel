import com.aerogroup.mcpanel.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.*;
import java.util.*;
import java.net.URLEncoder;
import java.util.regex.*;
import javax.net.ssl.*;

public class RemoteSmoke {
    public static void main(String[] args) throws Exception {
        Path testHome = Files.createTempDirectory("aeromc-remote-smoke-");
        System.setProperty("user.home", testHome.toString());
        ServerManager manager = new ServerManager(new ServerManager.Listener() {
            public void onConsole(String line) { }
            public void onState(boolean running, String text) { }
            public void onPlayers(List<String> players) { }
        });
        RemoteControlService service = new RemoteControlService(manager, null, new PanelConfig());
        service.createUser("smoke_admin", "strong-test-password".toCharArray(), RemoteControlService.Role.ADMIN);
        service.start(false, 18765);
        if (!service.getAddress().startsWith("https://") || service.getCertificateFingerprint().split(":").length != 32) throw new IllegalStateException("Remote TLS identity missing");
        String firstFingerprint = service.getCertificateFingerprint();
        String basic = Base64.getEncoder().encodeToString("smoke_admin:strong-test-password".getBytes(StandardCharsets.UTF_8));
        HttpClient client = trustedClient(service.getCertificateFile()); HttpRequest request = HttpRequest.newBuilder(URI.create("https://127.0.0.1:18765/api/status")).header("Authorization", "Basic " + basic).GET().build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        String protocol = response.sslSession().orElseThrow().getProtocol();
        if (response.statusCode() != 200 || !response.body().contains("\"local\"") || !(protocol.equals("TLSv1.3") || protocol.equals("TLSv1.2"))) throw new IllegalStateException("Remote TLS smoke test failed");
        boolean plaintextRejected = false; try { HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:18765/api/status")).timeout(java.time.Duration.ofSeconds(3)).GET().build(), HttpResponse.BodyHandlers.discarding()); } catch (java.io.IOException expected) { plaintextRejected = true; }
        if (!plaintextRejected) throw new IllegalStateException("Remote service accepted plaintext HTTP");
        HttpResponse<String> page = client.send(HttpRequest.newBuilder(URI.create("https://127.0.0.1:18765/")).header("Authorization", "Basic " + basic).GET().build(), HttpResponse.BodyHandlers.ofString());
        String csp = page.headers().firstValue("Content-Security-Policy").orElse(""); String permissions = page.headers().firstValue("Permissions-Policy").orElse(""); if (page.statusCode() != 200 || csp.contains("unsafe-inline") || !csp.contains("frame-ancestors 'none'") || !permissions.contains("camera=()")) throw new IllegalStateException("Remote browser hardening failed");
        HttpResponse<String> secondPage = client.send(HttpRequest.newBuilder(URI.create("https://127.0.0.1:18765/")).header("Authorization", "Basic " + basic).GET().build(), HttpResponse.BodyHandlers.ofString());
        if (csp.equals(secondPage.headers().firstValue("Content-Security-Policy").orElse(""))) throw new IllegalStateException("CSP nonce was reused between responses");
        Matcher token = Pattern.compile("const csrf='([^']+)'").matcher(page.body()); if (!token.find()) throw new IllegalStateException("Remote CSRF token missing");
        String noToken = "provider=local&action=command&value=stop"; HttpResponse<String> csrfDenied = client.send(HttpRequest.newBuilder(URI.create("https://127.0.0.1:18765/api/action")).header("Authorization", "Basic " + basic).header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(noToken)).build(), HttpResponse.BodyHandlers.ofString()); if (csrfDenied.statusCode() != 403) throw new IllegalStateException("Remote CSRF request accepted");
        String critical = "_csrf=" + URLEncoder.encode(token.group(1), StandardCharsets.UTF_8) + "&provider=local&action=command&value=stop"; HttpResponse<String> commandDenied = client.send(HttpRequest.newBuilder(URI.create("https://127.0.0.1:18765/api/action")).header("Authorization", "Basic " + basic).header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(critical)).build(), HttpResponse.BodyHandlers.ofString()); if (commandDenied.statusCode() != 403) throw new IllegalStateException("Remote critical command accepted");
        String invalidProvider = "_csrf=" + URLEncoder.encode(token.group(1), StandardCharsets.UTF_8) + "&provider=typo&action=start&value="; HttpResponse<String> providerDenied = client.send(HttpRequest.newBuilder(URI.create("https://127.0.0.1:18765/api/action")).header("Authorization", "Basic " + basic).header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(invalidProvider)).build(), HttpResponse.BodyHandlers.ofString()); if (providerDenied.statusCode() != 400) throw new IllegalStateException("Unknown provider fell back to local server");
        HttpResponse<String> contentTypeDenied = client.send(HttpRequest.newBuilder(URI.create("https://127.0.0.1:18765/api/action")).header("Authorization", "Basic " + basic).header("Content-Type", "text/plain").POST(HttpRequest.BodyPublishers.ofString(invalidProvider)).build(), HttpResponse.BodyHandlers.ofString()); if (contentTypeDenied.statusCode() != 415) throw new IllegalStateException("Unsafe action content type accepted");
        String duplicate = "_csrf=" + URLEncoder.encode(token.group(1), StandardCharsets.UTF_8) + "&provider=local&provider=exaroton&action=start"; HttpResponse<String> duplicateDenied = client.send(HttpRequest.newBuilder(URI.create("https://127.0.0.1:18765/api/action")).header("Authorization", "Basic " + basic).header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(duplicate)).build(), HttpResponse.BodyHandlers.ofString()); if (duplicateDenied.statusCode() != 400) throw new IllegalStateException("Duplicate form field accepted");
        HttpResponse<String> methodDenied = client.send(HttpRequest.newBuilder(URI.create("https://127.0.0.1:18765/api/action")).header("Authorization", "Basic " + basic).GET().build(), HttpResponse.BodyHandlers.ofString()); if (methodDenied.statusCode() != 405) throw new IllegalStateException("Wrong HTTP method accepted");
        service.stop(); service.start(false, 18765); if (!firstFingerprint.equals(service.getCertificateFingerprint())) throw new IllegalStateException("Remote TLS identity was not persisted");
        service.stop(); manager.shutdown(); System.out.println("remote-tls-security-ok");
    }

    private static HttpClient trustedClient(Path certificateFile) throws Exception {
        X509Certificate certificate; try (var input = Files.newInputStream(certificateFile)) { certificate = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(input); }
        KeyStore trust = KeyStore.getInstance(KeyStore.getDefaultType()); trust.load(null, null); trust.setCertificateEntry("aeromc", certificate);
        TrustManagerFactory managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()); managers.init(trust);
        SSLContext context = SSLContext.getInstance("TLS"); context.init(null, managers.getTrustManagers(), null);
        return HttpClient.newBuilder().sslContext(context).build();
    }
}
