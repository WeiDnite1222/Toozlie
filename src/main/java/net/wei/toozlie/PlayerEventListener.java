package net.wei.toozlie;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerRespawnEvent;

import java.util.Random;

import static java.lang.Math.abs;

public class PlayerEventListener implements Listener {
    private final Toozlie toozlie;

    public PlayerEventListener(Toozlie plugin) {
        this.toozlie = plugin;
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();

        if (this.toozlie.config.enableRandomRespawn) {
            // Ensure doing below process if player don't have a bed
            if (player.getRespawnLocation() != null) {
                return;
            }

            World world = Bukkit.getWorld("world");

            Random random = new Random();
            double r = abs(toozlie.config.radiusOfRandomRespawn);
            double centerX = toozlie.config.randomRespawnCenterX;
            double centerY = toozlie.config.randomRespawnCenterZ;

            if (r == 0) {
                toozlie.getLogger().warning("Config \"radiusOfRandomRespawn\" is illegal. Failback to 10.");
                r = 10.0;
            }

            double angle = random.nextDouble() * Math.PI * 2;
            double nr = Math.sqrt(random.nextDouble()) * abs(r);

            double x = centerX + nr * Math.cos(angle);
            double z = centerY + nr * Math.sin(angle);

            assert world != null;
            int y = world.getHighestBlockYAt((int) x, (int) z);

            player.sendMessage(toozlie.messages.get(
                    player,
                    "respawn.random",
                    "x",
                    String.format("%.2f", x),
                    "y",
                    String.valueOf(y),
                    "z",
                    String.format("%.2f", z)
            ));

            Location respawn = new Location(world, x + 0.5, y + 1, z + 0.5);

            event.setRespawnLocation(respawn);
        }
    }
}
