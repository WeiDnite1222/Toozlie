package net.wei.toozlie;

import de.exlll.configlib.Configuration;
import de.exlll.configlib.YamlConfigurations;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import org.incendo.cloud.execution.ExecutionCoordinator;
import org.incendo.cloud.minecraft.extras.MinecraftHelp;
import org.incendo.cloud.paper.LegacyPaperCommandManager;

import java.io.File;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


public final class Toozlie extends JavaPlugin {
    public globalConfig config;
    private LegacyPaperCommandManager<CommandSender> manager;
    private File configFile;
    private WhitelistPortal whitelistPortal;
    public DeathSaver deathSaver;
    public Broadcaster broadcaster;
    public ActionHelper actionHelper;
    public Messages messages;
    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
    private static final Pattern LEGACY_HEX_COLOR = Pattern.compile("(?i)([&§])x(?:\\1[0-9a-f]){6}");
    private static final Pattern LEGACY_FORMAT_CODE = Pattern.compile("(?i)([&§])([0-9a-fk-or])");

    public static class Result<T> {

        private final boolean success;
        private final String message;
        private final T data;

        public Result(boolean success, String message, T data) {
            this.success = success;
            this.message = message;
            this.data = data;
        }

        public boolean isSuccess() {
            return success;
        }

        public String getMessage() {
            return message;
        }

        public T getData() {
            return data;
        }

        public static <T> Result<T> success(T data) {
            return new Result<>(true, null, data);
        }

        public static <T> Result<T> error(String message) {
            return new Result<>(false, message, null);
        }
    }

    private final List<String> requiredExpansions = List.of(
            "Player"
    );

    private void checkExpansions() {
        if (!Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            getLogger().warning("PlaceholderAPI not found.");
            return;
        }

        for (String expansion : requiredExpansions) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                    "papi ecloud download " + expansion
            );
        }

        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "papi reload");
    }

    @Override
    public void onEnable() {
        // Plugin startup logic

        // Check dependencies
        if (!Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            getLogger().severe("The PlaceholderAPI is not available!");
            getServer().getPluginManager().disablePlugin(this);
            return;
        } else {
            // Check PlaceholderAPI expansion
            checkExpansions();
        }

        if (!Bukkit.getPluginManager().isPluginEnabled("ConfigLib")) {
            getLogger().severe("The configlib is not available!");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // init config
        configFile = new File(getDataFolder(), "config.yml");

        this.config = YamlConfigurations.update(
                configFile.toPath(),
                globalConfig.class
        );
        this.messages = new Messages(this);

        getServer().getPluginManager().registerEvents(
                new JoinListener(this),
                this
        );

        getServer().getPluginManager().registerEvents(
                new PlayerEventListener(this),
                this
        );

        // Register command
        manager = LegacyPaperCommandManager.createNative(this,
                ExecutionCoordinator.simpleCoordinator());
        registerCloudInternationalization();

        registerCommands();

        // Start whitelistPortal
        whitelistPortal = new WhitelistPortal(this);
        whitelistPortal.startWhitelist();

        // Start DeathSaver
        if (config.enableDeathSaver) {
            deathSaver = new DeathSaver(this);
            deathSaver.startSaver();
        } else {
            getLogger().info("DeathSaver is disabled!");
        }

        // Start Brocaster
        if (config.enableBroadcaster) {
            broadcaster = new Broadcaster(this);
            broadcaster.startBroadcaster();
        } else {
            getLogger().info("Broadcaster is disabled!");
        }

        actionHelper = new ActionHelper(this);
        actionHelper.startActionScheduler();
    }

    private void registerCommands() {
        new MainCommand(manager, this);
    }

    @Override
    public void onDisable() {
        // Plugin shutdown logic

        // Save config
        if (this.configFile != null) {
            YamlConfigurations.save(
                    configFile.toPath(),
                    globalConfig.class,
                    config
            );
        }

        // Stop all workers
        whitelistPortal.stopWhitelist();

        if (config.enableDeathSaver) {
            deathSaver.stopSaver();
        }

        if (config.enableBroadcaster) {
            broadcaster.stopBroadcaster();
        }

        if (actionHelper != null) {
            actionHelper.stopActionScheduler();
        }
    }

    public void reload() {
        whitelistPortal.stopWhitelist();

        if (config.enableDeathSaver) {
            deathSaver.stopSaver();
        }

        if (config.enableBroadcaster) {
            broadcaster.stopBroadcaster();
        }

        if (actionHelper != null) {
            actionHelper.stopActionScheduler();
        }

        config = YamlConfigurations.update(
                configFile.toPath(),
                globalConfig.class
        );
        this.messages = new Messages(this);

        // Start whitelistPortal
        whitelistPortal = new WhitelistPortal(this);
        whitelistPortal.startWhitelist();

        // Start DeathSaver
        if (config.enableDeathSaver) {
            deathSaver = new DeathSaver(this);
            deathSaver.startSaver();
        }

        // Start Brocaster
        if (config.enableBroadcaster) {
            broadcaster = new Broadcaster(this);
            broadcaster.startBroadcaster();
        }

        actionHelper = new ActionHelper(this);
        actionHelper.startActionScheduler();

        getLogger().info("Reloaded!");
    }

    public void saveConfig() {
        YamlConfigurations.save(configFile.toPath(), globalConfig.class, config);
    }

    private void registerCloudInternationalization() {
        manager.captionRegistry().registerProvider(MinecraftHelp.defaultCaptionsProvider());
        manager.captionRegistry().registerProvider(messages.cloudCaptionProvider());
    }

    @Configuration
    public static class globalConfig {
        public String language = "auto";
        public boolean showModifiedJoinMessage = true;
        public String welcomeMessage = "Hi! %player_name%";
        public String joinMessage = "Welcome back! %player_name%";
        public boolean enableRandomRespawn = false;
        public int radiusOfRandomRespawn = 20;
        public int randomRespawnCenterX = 0;
        public int randomRespawnCenterZ = 0;
        public boolean enableDeathSaver = false;
        public boolean enableBroadcaster = false;
        public whitelistPortal whitelistPortal = new whitelistPortal();
        public serverInfo serverInfo = new serverInfo();

        @Configuration
        public static class whitelistPortal {
            public boolean enableWhitelistServer = false;
            public String host = "127.0.0.1";
            public int port = 1838;
            public auth auth = new auth();
            public security security = new security();

            @Configuration
            public static class auth {
                public String mode = "token";
                public String token = "YOUR-TOKEN-HERE";
                public String hmacSecret = "YOUR-HMAC-SECRET-HERE";
                public int allowSkewSeconds = 30;
            }

            @Configuration
            public static class security {
                public int maxRequestsPerMinute = 120;
                public int maxBodyBytes = 120;
                public String requireMethod = "POST";
            }
        }

        @Configuration
        public static class serverInfo {
            public String serverName = "Toozlie";
            public String description = "";
        }
    }

    // Some command functions
    public void showServerInfo(CommandSender p) {
        String description = config.serverInfo.description;
        String serverName = config.serverInfo.serverName;
        String joinedPlayerCount = Integer.toString(Bukkit.getOfflinePlayers().length);
        String onlinePlayerCount = Integer.toString(Bukkit.getOnlinePlayers().size());

        if (description == null || description.isBlank()) {
            description = messages.get(p, "server.info.defaultDescription");
        }
        if (serverName == null || serverName.isBlank()) {
            serverName = getServer().getName();
        }

        String message = String.format("%s\n%s\n%s\n%s\n%s\n%s",
                messages.get(p, "server.info.title", "serverName", serverName),
                messages.get(p, "server.info.version", "serverVersion", getServer().getVersion()),
                messages.get(p, "server.info.platform", "serverPlatform", getServer().getName()),
                messages.get(p, "server.info.onlinePlayerCount", "onlinePlayerCount", onlinePlayerCount),
                messages.get(p, "server.info.joinedPlayerCount", "joinedPlayerCount", joinedPlayerCount),
                description);

        Component deserializeMessage = deserializeText(message);

        p.sendMessage(deserializeMessage);
    }

    public static Component deserializeText(String text) {
        return MINI_MESSAGE.deserialize(convertText(text));
    }

    public static String convertText(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }

        String converted = convertLegacyHexColors(text);
        Matcher matcher = LEGACY_FORMAT_CODE.matcher(converted);
        StringBuilder result = new StringBuilder();

        while (matcher.find()) {
            matcher.appendReplacement(result, Matcher.quoteReplacement(toMiniMessageTag(matcher.group(2).charAt(0))));
        }

        matcher.appendTail(result);
        return result.toString();
    }

    private static String convertLegacyHexColors(String text) {
        Matcher matcher = LEGACY_HEX_COLOR.matcher(text);
        StringBuilder result = new StringBuilder();

        while (matcher.find()) {
            String legacyHex = matcher.group();
            String hex = legacyHex
                    .replace("&", "")
                    .replace("§", "")
                    .substring(1)
                    .toLowerCase(Locale.ROOT);
            matcher.appendReplacement(result, Matcher.quoteReplacement("<#" + hex + ">"));
        }

        matcher.appendTail(result);
        return result.toString();
    }

    private static String toMiniMessageTag(char legacyCode) {
        return switch (Character.toLowerCase(legacyCode)) {
            case '0' -> "<black>";
            case '1' -> "<dark_blue>";
            case '2' -> "<dark_green>";
            case '3' -> "<dark_aqua>";
            case '4' -> "<dark_red>";
            case '5' -> "<dark_purple>";
            case '6' -> "<gold>";
            case '7' -> "<gray>";
            case '8' -> "<dark_gray>";
            case '9' -> "<blue>";
            case 'a' -> "<green>";
            case 'b' -> "<aqua>";
            case 'c' -> "<red>";
            case 'd' -> "<light_purple>";
            case 'e' -> "<yellow>";
            case 'f' -> "<white>";
            case 'k' -> "<obfuscated>";
            case 'l' -> "<bold>";
            case 'm' -> "<strikethrough>";
            case 'n' -> "<underlined>";
            case 'o' -> "<italic>";
            case 'r' -> "<reset>";
            default -> "";
        };
    }
}
