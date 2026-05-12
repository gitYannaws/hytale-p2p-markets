package me.ancap.p2pmarkets;

import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;

/**
 * P2P Markets plugin entry point.
 * <p>
 * Bootstraps the bridge poller on enable, shuts it down on disable.
 * No ECS systems registered — the integration runs as a daemon thread
 * that watches a queue file and dispatches actions.
 */
public class P2PMarketsPlugin extends JavaPlugin {

    public P2PMarketsPlugin(JavaPluginInit init) {
        super(init);
    }

    @Override
    protected void setup() {
        System.out.println("[P2PMarkets] Plugin loading (v0.1.x)");
        P2PMarketsIntegration.start();
    }

    // Hytale's JavaPlugin lifecycle doesn't currently expose a shutdown hook
    // we can override here — the poller thread is a daemon so it dies with
    // the JVM. If a teardown hook becomes available, call:
    //   P2PMarketsIntegration.stop();
}
