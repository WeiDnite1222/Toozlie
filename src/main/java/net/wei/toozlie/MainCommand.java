package net.wei.toozlie;

import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.incendo.cloud.description.CommandDescription;
import org.incendo.cloud.minecraft.extras.AudienceProvider;
import org.incendo.cloud.paper.LegacyPaperCommandManager;
import org.incendo.cloud.minecraft.extras.MinecraftHelp;
import org.incendo.cloud.parser.standard.BooleanParser;
import org.incendo.cloud.parser.standard.IntegerParser;
import org.incendo.cloud.parser.standard.StringParser;

import java.util.Map;

public class MainCommand {
    private final Toozlie toozlie;
    private final Messages messages;

    public MainCommand(LegacyPaperCommandManager<CommandSender> manager, Toozlie plugin) {
        toozlie = plugin;
        messages = plugin.messages;
        manager.command(
                manager.commandBuilder("tze")
                        .literal("reload")
                        .permission("tze.admin")
                        .handler(ctx -> {
                            toozlie.reload();
                            ctx.sender().sendMessage(messages.get(ctx.sender(), "command.reload"));
                        }).commandDescription(
                                CommandDescription.commandDescription(
                                        messages.get(messages.defaultLocale(), "description.tze.reload")
                                )
                        )
        );
        manager.command(
                manager.commandBuilder("tze")
                        .literal("version")
                        .permission("tze.admin")
                        .handler(ctx -> {
                            ctx.sender().sendMessage(Component.text(messages.get(
                                    ctx.sender(),
                                    "command.version",
                                    "version",
                                    toozlie.getPluginMeta().getVersion()
                            )));
                        }).commandDescription(
                                CommandDescription.commandDescription(
                                        messages.get(messages.defaultLocale(), "description.tze.version")
                                ))
        );

        // Help command
        MinecraftHelp<CommandSender> help = MinecraftHelp.<CommandSender>builder()
                .commandManager(manager)
                .audienceProvider(AudienceProvider.nativeAudience())
                .commandPrefix("/help")
                .messageProvider(this::helpMessage)
                .build();

        manager.command(
                manager.commandBuilder("help")
                        .optional(
                                "query",
                                StringParser.greedyStringParser()
                        )
                        .handler(ctx -> {

                            String query =
                                    ctx.getOrDefault(
                                            "query",
                                            ""
                                    );

                            help.queryCommands(
                                    query,
                                    ctx.sender()
                            );
                        }).commandDescription(
                                CommandDescription.commandDescription(
                                        messages.get(messages.defaultLocale(), "description.help")
                                ))
        );

        // DeathSaver
        manager.command(
                manager.commandBuilder("deathpos")
                        .literal("list")
                        .permission("tze.deathpos.list")
                        .handler(ctx -> {
                            CommandSender sender = ctx.sender();

                            if (!ensureDeathSaverEnabled(sender)) return;

                            if (sender instanceof Player player) {
                                toozlie.deathSaver.cmd.list(player, 1);
                            } else {
                                sender.sendMessage(messages.get(sender, "generic.players-only"));
                            }
                        }).commandDescription(
                                CommandDescription.commandDescription(
                                        messages.get(messages.defaultLocale(), "description.deathpos.list")
                                ))
        );

        manager.command(
                manager.commandBuilder("deathpos")
                        .literal("latest")
                        .permission("tze.deathpos.latest")
                        .handler(ctx -> {
                            CommandSender sender = ctx.sender();

                            if (!ensureDeathSaverEnabled(sender)) return;

                            if (sender instanceof Player player) {
                                toozlie.deathSaver.cmd.showLast(player);
                            } else {
                                sender.sendMessage(messages.get(sender, "generic.players-only"));
                            }
                        }).commandDescription(
                                CommandDescription.commandDescription(
                                        messages.get(messages.defaultLocale(), "description.deathpos.latest")
                                ))
        );

        manager.command(
                manager.commandBuilder("deathpos")
                        .literal("tp")
                        .permission("tze.deathpos.tp")
                        .required("index", IntegerParser.integerParser())
                        .handler(ctx -> {
                            CommandSender sender = ctx.sender();

                            if (!ensureDeathSaverEnabled(sender)) return;

                            int index = ctx.get("index");
                            if (sender instanceof Player player) {
                                toozlie.deathSaver.cmd.tp(player, index);
                            } else {
                                sender.sendMessage(messages.get(sender, "generic.players-only"));
                            }
                        }).commandDescription(
                                CommandDescription.commandDescription(
                                        messages.get(messages.defaultLocale(), "description.deathpos.tp")
                                ))
        );

        manager.command(
                manager.commandBuilder("deathpos")
                        .literal("clear")
                        .permission("tze.deathpos.clear")
                        .required("index", IntegerParser.integerParser())
                        .handler(ctx -> {
                            CommandSender sender = ctx.sender();

                            if (!ensureDeathSaverEnabled(sender)) return;

                            if (sender instanceof Player player) {
                                toozlie.deathSaver.store.clearDeaths(player.getUniqueId());
                                player.sendMessage(messages.get(player, "death.cleared"));
                            } else {
                                sender.sendMessage(messages.get(sender, "generic.players-only"));
                            }
                        }).commandDescription(
                                CommandDescription.commandDescription(
                                        messages.get(messages.defaultLocale(), "description.deathpos.clear")
                                ))
        );

        // Broadcaster
        manager.command(
                manager.commandBuilder("broadcaster")
                        .literal("list")
                        .permission("tze.broadcaster.list")
                        .optional("index", IntegerParser.integerParser())
                        .handler(ctx -> {
                            CommandSender sender = ctx.sender();

                            if (!ensureBroadcasterEnabled(sender)) return;

                            int index = ctx.getOrDefault("index", 1);
                            toozlie.broadcaster.list(sender, index);
                        }).commandDescription(
                                CommandDescription.commandDescription(
                                        messages.get(messages.defaultLocale(), "description.broadcaster.list")
                                ))
        );

        manager.command(
                manager.commandBuilder("broadcaster")
                        .literal("add")
                        .permission("tze.broadcaster.add")
                        .required("name", StringParser.stringParser())
                        .required("content", StringParser.quotedStringParser())
                        .optional("display", BooleanParser.booleanParser())
                        .optional("displayIntervalSecond", IntegerParser.integerParser())
                        .optional("displayAtActionBar", BooleanParser.booleanParser())
                        .handler(ctx -> {
                            CommandSender sender = ctx.sender();

                            if (!ensureBroadcasterEnabled(sender)) return;

                            String name = ctx.get("name");
                            String content = ctx.get("content");
                            boolean display = ctx.getOrDefault("display", false);
                            int displayIntervalSecond = ctx.getOrDefault("displayIntervalSecond", 600);
                            boolean displayAtActionBar = ctx.getOrDefault("displayAtActionBar", false);
                            toozlie.broadcaster.addBroadcast(name, content,
                                    display, displayIntervalSecond, displayAtActionBar);
                            sender.sendMessage(messages.get(sender, "broadcaster.added", "name", name));
                        }).commandDescription(
                                CommandDescription.commandDescription(
                                        messages.get(messages.defaultLocale(), "description.broadcaster.add")
                                ))
        );

        manager.command(
                manager.commandBuilder("broadcaster")
                        .literal("remove")
                        .permission("tze.broadcaster.list")
                        .required("index", IntegerParser.integerParser())
                        .handler(ctx -> {
                            CommandSender sender = ctx.sender();

                            if (!ensureBroadcasterEnabled(sender)) return;

                            int index = ctx.get("index");
                            Toozlie.Result<Boolean> result = toozlie.broadcaster.removeBroadcast(index-1);  // convert it back to 0-based index
                            if (result.isSuccess()) {
                                sender.sendMessage(messages.get(sender, "broadcaster.deleted"));
                            } else {
                                sender.sendMessage(messages.get(sender, "generic.error", "message", result.getMessage()));
                            }
                        }).commandDescription(
                                CommandDescription.commandDescription(
                                        messages.get(messages.defaultLocale(), "description.broadcaster.remove")
                                ))
        );

        // Server
        manager.command(
                manager.commandBuilder("info")
                        .permission("tze.server.info")
                        .handler(ctx -> {
                            CommandSender sender = ctx.sender();
                            toozlie.showServerInfo(sender);
                        }).commandDescription(
                                CommandDescription.commandDescription(
                                        messages.get(messages.defaultLocale(), "server.info.commandDescription")
                                ))
        );

        manager.command(
                manager.commandBuilder("tze")
                        .literal("schedule")
                        .literal("listsupport")
                        .permission("tze.schedule.listsupport")
                        .handler(ctx -> {
                            CommandSender sender = ctx.sender();
                            toozlie.actionHelper.listSupport(sender);
                        }).commandDescription(
                                CommandDescription.commandDescription(
                                        messages.get(messages.defaultLocale(), "description.tze.schedule.listsupport")
                                ))
        );

        manager.command(
                manager.commandBuilder("tze")
                        .literal("schedule")
                        .literal("list")
                        .permission("tze.schedule.list")
                        .optional("page", IntegerParser.integerParser())
                        .handler(ctx -> {
                            CommandSender sender = ctx.sender();
                            int page = ctx.getOrDefault("page", 1);
                            toozlie.actionHelper.list(sender, page);
                        }).commandDescription(
                                CommandDescription.commandDescription(
                                        messages.get(messages.defaultLocale(), "description.tze.schedule.list")
                                ))
        );

        manager.command(
                manager.commandBuilder("tze")
                        .literal("schedule")
                        .literal("delete")
                        .permission("tze.schedule.delete")
                        .required("index", IntegerParser.integerParser())
                        .handler(ctx -> {
                            CommandSender sender = ctx.sender();
                            int index = ctx.get("index");
                            Toozlie.Result<Boolean> result = toozlie.actionHelper.removeAction(index - 1);
                            if (result.isSuccess()) {
                                sender.sendMessage(messages.get(sender, "action.deleted"));
                            } else {
                                sender.sendMessage(messages.get(sender, "generic.error", "message", result.getMessage()));
                            }
                        }).commandDescription(
                                CommandDescription.commandDescription(
                                        messages.get(messages.defaultLocale(), "description.tze.schedule.delete")
                                ))
        );

        manager.command(
                manager.commandBuilder("tze")
                        .literal("schedule")
                        .required("name", StringParser.stringParser())
                        .required("action", StringParser.stringParser())
                        .required("executeIntervalMinute", IntegerParser.integerParser())
                        .optional("doOnce", BooleanParser.booleanParser())
                        .optional("displayRemainingTime", BooleanParser.booleanParser())
                        .optional("displayAtActionBar", BooleanParser.booleanParser())
                        .handler(ctx -> {
                            CommandSender sender = ctx.sender();
                            String name = ctx.get("name");
                            String action = ctx.get("action");
                            int executeIntervalMinute = ctx.get("executeIntervalMinute");
                            boolean doOnce = ctx.getOrDefault("doOnce", true);
                            boolean displayRemainingTime = ctx.getOrDefault(
                                    "displayRemainingTime",
                                    defaultDisplayRemainingTime(action)
                            );
                            boolean displayAtActionBar = ctx.getOrDefault("displayAtActionBar", false);
                            toozlie.actionHelper.addActionOnCommand(sender, name, action, executeIntervalMinute, doOnce,
                                    displayRemainingTime, displayAtActionBar);
                        }).commandDescription(
                                CommandDescription.commandDescription(
                                        messages.get(messages.defaultLocale(), "description.tze.schedule")
                                ))
        );
    }

    private boolean ensureBroadcasterEnabled(CommandSender sender) {
        if (toozlie.broadcaster == null) {
            sender.sendMessage(messages.get(sender, "feature.broadcaster.disabled"));
            return false;
        }
        return true;
    }

    private boolean defaultDisplayRemainingTime(String action) {
        return "action.server.stop".equals(action) || "action.item.clear".equals(action);
    }

    private Component helpMessage(CommandSender sender, String key, Map<String, String> placeholders) {
        String message = messages.raw(sender, "cloud.help.minecraft." + key);
        if (message == null) {
            message = key;
        }

        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            message = message.replace("<" + entry.getKey() + ">", entry.getValue());
        }

        return Component.text(message);
    }

    private boolean ensureDeathSaverEnabled(CommandSender sender) {
        if (toozlie.deathSaver == null) {
            sender.sendMessage(messages.get(sender, "feature.death-saver.disabled"));
            return false;
        }
        return true;
    }

    public Toozlie getToozlie() {
        return toozlie;
    }
}
