package net.wei.toozlie;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.*;

public final class DeathStore implements Listener {

    public record DeathPoint(
            String world,
            double x, double y, double z,
            float yaw, float pitch,
            long epochSeconds,
            String deathMessage
    ) {}

    private final Toozlie plugin;
    private final File file;
    private YamlConfiguration yaml;

    // UUID -> newest first
    private final Map<UUID, Deque<DeathPoint>> cache = new HashMap<>();

    public DeathStore(Toozlie plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "deaths.yml");
        reload();
    }

    public void reload() {
        if (!plugin.getDataFolder().exists()) plugin.getDataFolder().mkdirs();
        this.yaml = YamlConfiguration.loadConfiguration(file);
        this.cache.clear();

        // 讀回 cache
        var root = yaml.getConfigurationSection("players");
        if (root == null) return;

        for (String uuidStr : root.getKeys(false)) {
            UUID uuid;
            try { uuid = UUID.fromString(uuidStr); }
            catch (IllegalArgumentException ex) { continue; }

            var list = root.getMapList(uuidStr + ".list");
            Deque<DeathPoint> dq = new ArrayDeque<>();

            for (Map<?, ?> m : list) {
                try {
                    String world = String.valueOf(m.get("world"));
                    double x = ((Number) m.get("x")).doubleValue();
                    double y = ((Number) m.get("y")).doubleValue();
                    double z = ((Number) m.get("z")).doubleValue();
                    float yaw = ((Number) m.get("yaw")).floatValue();
                    float pitch = ((Number) m.get("pitch")).floatValue();
                    long t = ((Number) m.get("time")).longValue();
                    String msg = m.get("msg") == null ? "" : String.valueOf(m.get("msg"));
                    dq.addLast(new DeathPoint(world, x, y, z, yaw, pitch, t, msg));
                } catch (Exception ignored) {}
            }

            cache.put(uuid, dq);
        }
    }

    public List<DeathPoint> getDeaths(UUID uuid) {
        Deque<DeathPoint> dq = cache.getOrDefault(uuid, new ArrayDeque<>());
        return List.copyOf(dq);
    }

    public void clearDeaths(UUID uuid) {
        cache.remove(uuid);
        yaml.set("players." + uuid + ".list", new ArrayList<>());
        saveNow();
    }

    public void flush() {
        saveNow();
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        Player p = event.getEntity();
        Location loc = p.getLocation();

        DeathPoint dp = new DeathPoint(
                loc.getWorld().getName(),
                loc.getX(), loc.getY(), loc.getZ(),
                loc.getYaw(), loc.getPitch(),
                Instant.now().getEpochSecond(),
                event.getDeathMessage() == null ? "" : event.getDeathMessage()
        );

        Deque<DeathPoint> dq = cache.computeIfAbsent(p.getUniqueId(), k -> new ArrayDeque<>());
        // newest first：放前面
        dq.addFirst(dp);
        // 你想限制每人最多保留幾筆（避免檔案無限長）
        int maxPerPlayer = 100;
        while (dq.size() > maxPerPlayer) dq.removeLast();

        // 寫入 yaml（簡單做法：每次死亡就落盤；想更省 IO 可改成排程批次寫）
        writePlayerToYaml(p.getUniqueId(), dq);
        saveNow();
        p.sendMessage(plugin.messages.get(
                p,
                "death.saved",
                "x",
                String.format("%.0f", dp.x),
                "y",
                String.format("%.0f", dp.y),
                "z",
                String.format("%.0f", dp.z)
        ));
    }

    private void writePlayerToYaml(UUID uuid, Deque<DeathPoint> dq) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (DeathPoint dp : dq) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("world", dp.world());
            m.put("x", dp.x());
            m.put("y", dp.y());
            m.put("z", dp.z());
            m.put("yaw", dp.yaw());
            m.put("pitch", dp.pitch());
            m.put("time", dp.epochSeconds());
            m.put("msg", dp.deathMessage());
            list.add(m);
        }
        yaml.set("players." + uuid + ".list", list);
    }

    private void saveNow() {
        try {
            yaml.save(file);
        } catch (IOException e) {
            Bukkit.getLogger().warning("[DeathPoint] Failed to save deaths.yml: " + e.getMessage());
        }
    }

    public Location toLocation(DeathPoint dp) {
        var w = Bukkit.getWorld(dp.world());
        if (w == null) return null;
        Location loc = new Location(w, dp.x(), dp.y(), dp.z(), dp.yaw(), dp.pitch());
        return loc;
    }
}
