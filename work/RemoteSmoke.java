import com.aerogroup.mcpanel.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.net.URLEncoder;
import java.util.regex.*;

public class RemoteSmoke {
    public static void main(String[] args) throws Exception {
        ServerManager manager = new ServerManager(new ServerManager.Listener() {
            public void onConsole(String line) { }
            public void onState(boolean running, String text) { }
            public void onPlayers(List<String> players) { }
        });
        RemoteControlService service = new RemoteControlService(manager, null, new PanelConfig());
        service.createUser("smoke_admin", "strong-test-password".toCharArray(), RemoteControlService.Role.ADMIN);
        service.start(false, 18765);
        String basic = Base64.getEncoder().encodeToString("smoke_admin:strong-test-password".getBytes(StandardCharsets.UTF_8));
        HttpClient client = HttpClient.newHttpClient(); HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:18765/api/status")).header("Authorization", "Basic " + basic).GET().build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200 || !response.body().contains("\"local\"")) throw new IllegalStateException("Remote smoke test failed");
        HttpResponse<String> page = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:18765/")).header("Authorization", "Basic " + basic).GET().build(), HttpResponse.BodyHandlers.ofString());
        String csp = page.headers().firstValue("Content-Security-Policy").orElse(""); if (page.statusCode() != 200 || csp.contains("unsafe-inline") || !csp.contains("frame-ancestors 'none'")) throw new IllegalStateException("Remote CSP hardening failed");
        Matcher token = Pattern.compile("const csrf='([^']+)'").matcher(page.body()); if (!token.find()) throw new IllegalStateException("Remote CSRF token missing");
        String noToken = "provider=local&action=command&value=stop"; HttpResponse<String> csrfDenied = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:18765/api/action")).header("Authorization", "Basic " + basic).header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(noToken)).build(), HttpResponse.BodyHandlers.ofString()); if (csrfDenied.statusCode() != 403) throw new IllegalStateException("Remote CSRF request accepted");
        String critical = "_csrf=" + URLEncoder.encode(token.group(1), StandardCharsets.UTF_8) + "&provider=local&action=command&value=stop"; HttpResponse<String> commandDenied = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:18765/api/action")).header("Authorization", "Basic " + basic).header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(critical)).build(), HttpResponse.BodyHandlers.ofString()); if (commandDenied.statusCode() != 403) throw new IllegalStateException("Remote critical command accepted");
        service.stop(); manager.shutdown(); System.out.println("remote-security-ok");
    }
}
