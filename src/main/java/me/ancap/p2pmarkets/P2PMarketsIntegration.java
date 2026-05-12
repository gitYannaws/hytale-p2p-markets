package me.ancap.p2pmarkets;

import java.io.File;
import java.io.FileWriter;
import java.nio.file.Files;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Internal — the background poller thread that bridges the Python sidecar
 * to the in-game mod world. Other mods should NOT reference this class.
 * Use {@link P2PMarketsAPI} instead.
 *
 * Queue file format (one entry per line):
 *   CMD|<dev-console command>          e.g.  CMD|disaster tornado 1.5
 *   CFG|<config key>|<numeric value>   e.g.  CFG|weightSevere|0.3
 *   ANN|<message>                      e.g.  ANN|DAO vote passed
 *   <CUSTOM>|<param1>|<param2>|...     dispatched via P2PMarketsAPI.registerHandler
 *
 * Lines starting with # are comments.
 */
final class P2PMarketsIntegration {

    private static Thread pollerThread;
    private static volatile boolean running = false;

    private P2PMarketsIntegration() {}

    static void start() {
        if (!P2PMarketsConfig.ENABLED) {
            log("Master switch is OFF — integration not started");
            return;
        }
        running = true;
        P2PMarketsAPI.setActive(true);
        pollerThread = new Thread(P2PMarketsIntegration::pollLoop, "P2PMarkets-Poller");
        pollerThread.setDaemon(true);
        pollerThread.start();
        log("Integration started — queue=" + P2PMarketsConfig.QUEUE_FILE);
    }

    static void stop() {
        running = false;
        P2PMarketsAPI.setActive(false);
        if (pollerThread != null) pollerThread.interrupt();
        log("Integration stopped");
    }

    // ── Poll loop ────────────────────────────────────────────────────────────

    private static void pollLoop() {
        while (running) {
            try {
                processQueue();
                Thread.sleep(P2PMarketsConfig.POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log("Poll error: " + e.getMessage());
            }
        }
    }

    private static void processQueue() throws Exception {
        File f = new File(P2PMarketsConfig.QUEUE_FILE);
        if (!f.exists() || f.length() == 0) return;

        List<String> lines;
        synchronized (P2PMarketsIntegration.class) {
            lines = Files.readAllLines(f.toPath());
            new FileWriter(f, false).close(); // atomic clear
        }

        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            try {
                dispatch(line);
            } catch (Exception e) {
                log("Action error [" + line + "]: " + e.getMessage());
            }
        }
    }

    // ── Built-in handlers + custom dispatch ──────────────────────────────────

    private static void dispatch(String line) throws Exception {
        String[] parts = line.split("\\|");
        if (parts.length < 2) return;
        String type = parts[0].trim().toUpperCase();

        switch (type) {
            case "CMD":
                handleCmd(parts);
                break;
            case "CFG":
                handleCfg(parts);
                break;
            case "ANN":
                handleAnn(parts);
                break;
            default:
                // Custom type — look up in registered handlers
                ActionHandler custom = P2PMarketsAPI.getHandler(type);
                if (custom == null) {
                    log("No handler for action type: " + type);
                    return;
                }
                // Pass everything after the type as params
                String[] params = new String[parts.length - 1];
                System.arraycopy(parts, 1, params, 0, params.length);
                custom.handle(params);
                log("Custom handler dispatched: " + type);
        }
    }

    private static void handleCmd(String[] parts) throws Exception {
        if (parts.length < 2) return;
        String cmd = parts[1].trim();
        appendDevCmd(cmd);
        log("CMD dispatched: " + cmd);
    }

    private static void handleCfg(String[] parts) throws Exception {
        if (parts.length < 3) { log("CFG missing value"); return; }
        String key = parts[1].trim();
        String value = parts[2].trim();
        updateDisasterConfig(key, value);
        log("CFG updated: " + key + "=" + value);
    }

    private static void handleAnn(String[] parts) throws Exception {
        if (parts.length < 2) return;
        String msg = parts[1].trim();
        appendDevCmd("announce " + msg);
        log("ANN: " + msg);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static void appendDevCmd(String cmd) throws Exception {
        try (FileWriter fw = new FileWriter(P2PMarketsConfig.DEV_CMD_FILE, true)) {
            fw.write(cmd + "\n");
        }
    }

    /**
     * Update a numeric field in disaster_config.json via regex.
     * No JSON library needed — values are always plain numbers.
     */
    private static void updateDisasterConfig(String key, String value) throws Exception {
        File f = new File(P2PMarketsConfig.DISASTER_CONFIG);
        if (!f.exists()) {
            log("disaster_config.json not found: " + f.getPath());
            return;
        }
        String content = new String(Files.readAllBytes(f.toPath()));
        String updated = content.replaceAll(
                "\"" + Pattern.quote(key) + "\"\\s*:\\s*[0-9.]+",
                "\"" + key + "\": " + value
        );
        if (updated.equals(content)) {
            log("CFG key not found: " + key);
            return;
        }
        Files.write(f.toPath(), updated.getBytes());
    }

    private static void log(String msg) {
        System.out.println("[P2PMarkets] " + msg);
    }
}
