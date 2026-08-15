import com.aerogroup.mcpanel.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

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
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:18765/api/status")).header("Authorization", "Basic " + basic).GET().build();
        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200 || !response.body().contains("\"local\"")) throw new IllegalStateException("Remote smoke test failed");
        service.stop(); manager.shutdown(); System.out.println("remote-security-ok");
    }
}
