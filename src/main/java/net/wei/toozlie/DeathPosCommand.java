package net.wei.toozlie;

import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

public final class DeathPosCommand implements CommandExecutor, TabCompleter {

    private final DeathStore store;
    private final Toozlie toozlie;

    private static final int PAGE_SIZE = 5;

    private static final DateTimeFormatter TS_FMT = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    public DeathPosCommand(DeathSaver plugin, DeathStore store) {
        this.store = store;
        this.toozlie = plugin.getToozlie();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage(toozlie.messages.get(sender, "generic.players-only"));
            return true;
        }
        if (!p.hasPermission("deathpos.use")) {
            p.sendMessage(toozlie.messages.get(p, "generic.no-permission"));
            return true;
        }

        if (args.length == 0) {
            return list(p, 1);
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "list" -> {
                int page = 1;
                if (args.length >= 2) page = parseIntOr(args[1], 1);
                return list(p, page);
            }
            case "last" -> {
                return showLast(p);
            }
            case "tp" -> {
                if (!p.hasPermission("deathpos.tp")) {
                    p.sendMessage(toozlie.messages.get(p, "generic.no-permission"));
                    return true;
                }
                if (args.length < 2) {
                    p.sendMessage(toozlie.messages.get(p, "death.usage.tp"));
                    return true;
                }
                int idx = parseIntOr(args[1], -1);
                return tp(p, idx);
            }
            case "clear" -> {
                if (!p.hasPermission("deathpos.clear")) {
                    p.sendMessage(toozlie.messages.get(p, "generic.no-permission"));
                    return true;
                }
                store.clearDeaths(p.getUniqueId());
                p.sendMessage(toozlie.messages.get(p, "death.cleared"));
                return true;
            }
            default -> {
                p.sendMessage(toozlie.messages.get(p, "death.usage"));
                return true;
            }
        }
    }

    public boolean showLast(Player p) {
        var deaths = store.getDeaths(p.getUniqueId());
        if (deaths.isEmpty()) {
            p.sendMessage(toozlie.messages.get(p, "death.none"));
            return true;
        }
        sendOne(p, deaths.get(0), 1);
        return true;
    }

    public boolean list(Player p, int page) {
        var deaths = store.getDeaths(p.getUniqueId());
        if (deaths.isEmpty()) {
            p.sendMessage(toozlie.messages.get(p, "death.none"));
            return true;
        }

        int total = deaths.size();
        int maxPage = Math.max(1, (int) Math.ceil(total / (double) PAGE_SIZE));
        page = Math.min(Math.max(page, 1), maxPage);

        int start = (page - 1) * PAGE_SIZE;
        int end = Math.min(start + PAGE_SIZE, total);

        p.sendMessage(toozlie.messages.get(
                p,
                "death.header",
                "page",
                String.valueOf(page),
                "maxPage",
                String.valueOf(maxPage),
                "total",
                String.valueOf(total)
        ));
        for (int i = start; i < end; i++) {
            sendOne(p, deaths.get(i), i + 1);
        }
        p.sendMessage(toozlie.messages.get(p, "death.tip"));
        return true;
    }

    private void sendOne(Player p, DeathStore.DeathPoint dp, int index1Based) {
        String time = TS_FMT.format(Instant.ofEpochSecond(dp.epochSeconds()));
        String deathMessage = dp.deathMessage().isBlank()
                ? ""
                : toozlie.messages.get(p, "death.entry.message", "message", dp.deathMessage());
        p.sendMessage(toozlie.messages.get(
                p,
                "death.entry",
                "index",
                String.valueOf(index1Based),
                "time",
                time,
                "world",
                dp.world(),
                "xyz",
                formatXYZ(dp.x(), dp.y(), dp.z()),
                "message",
                deathMessage
        ));
    }

    public boolean tp(Player p, int index1Based) {
        var deaths = store.getDeaths(p.getUniqueId());
        if (index1Based < 1 || index1Based > deaths.size()) {
            p.sendMessage(toozlie.messages.get(p, "death.invalid-index"));
            return true;
        }

        DeathStore.DeathPoint dp = deaths.get(index1Based - 1);
        Location loc = store.toLocation(dp);
        if (loc == null || loc.getWorld() == null) {
            p.sendMessage(toozlie.messages.get(p, "death.world-not-loaded", "world", dp.world()));
            return true;
        }

        // 可選：避免卡方塊，傳送到方塊上方
        loc.setX(Math.floor(loc.getX()) + 0.5);
        loc.setY(Math.floor(loc.getY()) + 0.2);
        loc.setZ(Math.floor(loc.getZ()) + 0.5);

        p.teleport(loc);
        p.sendMessage(toozlie.messages.get(p, "death.teleported", "index", String.valueOf(index1Based)));
        return true;
    }

    private static int parseIntOr(String s, int def) {
        try { return Integer.parseInt(s); } catch (Exception e) { return def; }
    }

    private static String formatXYZ(double x, double y, double z) {
        return "(" + (int)Math.floor(x) + ", " + (int)Math.floor(y) + ", " + (int)Math.floor(z) + ")";
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!(sender instanceof Player)) return List.of();

        if (args.length == 1) {
            return partial(args[0], List.of("list", "last", "tp", "clear"));
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("tp")) {
            // 不掃實際筆數（避免 IO），給一些常用
            return partial(args[1], List.of("1", "2", "3", "4", "5"));
        }
        return List.of();
    }

    private static List<String> partial(String token, List<String> options) {
        String t = token.toLowerCase(Locale.ROOT);
        ArrayList<String> out = new ArrayList<>();
        for (String o : options) {
            if (o.toLowerCase(Locale.ROOT).startsWith(t)) out.add(o);
        }
        return out;
    }
}
