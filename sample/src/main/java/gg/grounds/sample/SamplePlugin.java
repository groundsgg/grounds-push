package gg.grounds.sample;

import org.bukkit.plugin.java.JavaPlugin;

public final class SamplePlugin extends JavaPlugin {
    @Override
    public void onEnable() {
        getLogger().info("Hello from grounds-push sample");
    }
}
