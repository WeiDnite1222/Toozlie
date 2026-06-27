package net.wei.toozlie;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import me.clip.placeholderapi.PlaceholderAPI;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

public class JoinListener implements Listener {
    private final Toozlie toozlie;

    public JoinListener(Toozlie plugin) {
        this.toozlie = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (!toozlie.config.showModifiedJoinMessage) {
            return;
        }

        Player player = event.getPlayer();
        Component joinMessage;

        if (!player.hasPlayedBefore()) {
            joinMessage = Component.text()
                    .append(Component.text(PlaceholderAPI.setPlaceholders(player, toozlie.config.welcomeMessage)
                            , NamedTextColor.YELLOW))
                    .build();
        } else {
            joinMessage = Component.text()
                    .append(Component.text(PlaceholderAPI.setPlaceholders(player, toozlie.config.joinMessage)
                            , NamedTextColor.YELLOW))
                    .build();
        }

        event.joinMessage(joinMessage);
    }
}
