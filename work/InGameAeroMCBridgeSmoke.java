package com.aerogroup.mcpanel;

import java.util.*;
import java.util.concurrent.*;

/** Eklentisiz oyun içi komut köprüsünün yalnız OP oyunculara cevap verdiğini doğrular. */
public final class InGameAeroMCBridgeSmoke {
    public static void main(String[] args) throws Exception {
        PanelConfig config = new PanelConfig(); config.setInGameCommandsEnabled(true);
        List<String> commands = new CopyOnWriteArrayList<>();
        InGameAeroMCBridge bridge = new InGameAeroMCBridge(config,
                ignored -> CompletableFuture.completedFuture("[{\"name\":\"Liytles\",\"level\":4},{\"name\":\"OtherOp\",\"level\":4}]"),
                ignored -> CompletableFuture.completedFuture(new InGameAeroMCBridge.Snapshot(true, 2, 20, 19.8, 42, 31, false, 12.5, 860, 4096)),
                (provider, command) -> commands.add(command), ignored -> { });
        bridge.accept(InGameAeroMCBridge.Provider.LOCAL, "[Server thread/INFO]: <Liytles> .aeromc sağlık");
        waitFor(commands, 1);
        require(commands.get(0).startsWith("tellraw Liytles "), "OP health command should use tellraw");
        require(commands.get(0).contains("Sağlık") && commands.get(0).contains("CPU") && commands.get(0).contains("Kriz"), "health summary missing");
        bridge.accept(InGameAeroMCBridge.Provider.LOCAL, "[Server thread/INFO]: <Liytles> .aeromc performans");
        Thread.sleep(3100); bridge.accept(InGameAeroMCBridge.Provider.LOCAL, "[Server thread/INFO]: <Liytles> .aeromc performans");
        waitFor(commands, 2); require(commands.get(1).contains("Gecikme") && commands.get(1).contains("860/4096 MB"), "performance response missing");
        bridge.accept(InGameAeroMCBridge.Provider.LOCAL, "[Server thread/INFO]: <NotAnOp> .aeromc health");
        Thread.sleep(80); require(commands.size() == 2, "non-OP must not receive a response");
        bridge.accept(InGameAeroMCBridge.Provider.LOCAL, "[Server thread/INFO]: <OtherOp> .aeromc duyur Sunucu 5 dakika sonra yeniden başlatılacak");
        waitFor(commands, 4); require(commands.get(2).startsWith("tellraw @a ") && commands.get(2).contains("AeroMC Duyuru"), "OP announcement must use safe broadcast tellraw");
        require(commands.get(3).startsWith("tellraw OtherOp "), "announcement acknowledgement missing");
        require(InGameAeroMCBridge.parseOperators("[{\"name\":\"Liytles\"}]").contains("liytles"), "ops parser failed");
        bridge.shutdown(); System.out.println("ingame-aeromc-bridge-ok");
    }
    private static void waitFor(List<String> values, int expected) throws InterruptedException { for (int i = 0; i < 40 && values.size() < expected; i++) Thread.sleep(25); require(values.size() >= expected, "bridge response timed out"); }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
