package net.wei.toozlie;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Item;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class ActionHelper {
    org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger("Toozlie.ActionHelper");

    // Action file
    private File actionFile;
    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
    private BukkitTask actionTask;

    public static final List<String> SupportActions = List.of("action.server.stop",
            "action.server.reload", "action.item.clear");

    public static class Action {
        public String name;
        public Timestamp addTime;
        public Timestamp latestExecuteTime;
        public boolean doOnce = true;
        public int executeIntervalSec = 600;
        public boolean displayRemainingTime = false;
        public boolean displayStartMessage;
        public boolean displayAtActionBar = false;
        public String targetActionName = "";
        public long latestCountdownRemainSec = -1;

        public Action() {
        }

        public Action(String name, Timestamp addTime, Timestamp latestExecuteTime, boolean doOnce, boolean displayStartMessage,
                         int executeIntervalSec, boolean displayRemainingTime, boolean displayAtActionBar, String targetActionName) {
            this.name = name;
            this.addTime = addTime;
            this.latestExecuteTime = latestExecuteTime;
            this.doOnce = doOnce;
            this.displayStartMessage = displayStartMessage;
            this.executeIntervalSec = executeIntervalSec;
            this.displayRemainingTime = displayRemainingTime;
            this.displayAtActionBar = displayAtActionBar;
            if (!SupportActions.contains(targetActionName)) {
                throw new IllegalArgumentException("Unsupported action name: "+targetActionName);
            } else {
                this.targetActionName = targetActionName;
            }
        }
    }

    public static class Actions {
        private List<Action> actions = new ArrayList<>();

        public Actions() {
        }

        // Getter & Setter
        public List<Action> getActions() { return actions; }
        public void setActions(List<Action> actions) {
            this.actions = actions == null ? new ArrayList<>() : actions;
        }
    }
    private final Toozlie toozlie;
    public Actions actions;

    public ActionHelper(Toozlie toozlie) {
        this.toozlie = toozlie;
    }

    private void loadAllActionsFromDisk() {
        try {
            actionFile = new File(toozlie.getDataFolder(), "actions.yml");

            if (!toozlie.getDataFolder().exists() && !toozlie.getDataFolder().mkdirs()) {
                logger.warn("Failed to create plugin data folder: {}", toozlie.getDataFolder());
            }

            if (!actionFile.exists() || actionFile.length() == 0) {
                actions = new ActionHelper.Actions();
                saveAllactions();
                return;
            }

            actions = yamlMapper.readValue(actionFile, ActionHelper.Actions.class);
            if (actions == null) {
                actions = new ActionHelper.Actions();
            }
            if (actions.getActions() == null) {
                actions.setActions(new ArrayList<>());
            }
        } catch (Exception exception) {
            logger.error("An error occurred while reading broadcast file. Exec: {}", exception.toString());
            actions = new ActionHelper.Actions();
        }
    }

    private Toozlie.Result<Boolean> saveAllactions() {
        try {
            if (actionFile == null) {
                actionFile = new File(toozlie.getDataFolder(), "actions.yml");
            }
            yamlMapper.writeValue(actionFile, actions);
            return Toozlie.Result.success(true);
        } catch (Exception exception) {
            logger.error("An error occurred while saving broadcast file. Exec: {}", exception.toString());
            return Toozlie.Result.error("Failed to save broadcast file.");
        }
    }

    public void addAction(String name, Timestamp latestExecuteTime, boolean doOnce, boolean displayRemainingTime,
                          int executeIntervalSec, boolean displayAtActionBar, String targetActionName) {
        ensureActionsLoaded();
        Action action = new Action(name, Timestamp.from(Instant.now()), latestExecuteTime, doOnce, displayRemainingTime,
                executeIntervalSec, displayRemainingTime, displayAtActionBar, targetActionName);
        actions.getActions().add(action);
        saveAllactions();
    }

    public void startActionScheduler() {
        loadAllActionsFromDisk();

        if (actionTask != null) {
            actionTask.cancel();
        }

        actionTask = Bukkit.getScheduler().runTaskTimer(toozlie, () -> {
            ensureActionsLoaded();

            Timestamp now = Timestamp.from(Instant.now());
            boolean changed = false;
            Iterator<Action> iterator = actions.getActions().iterator();

            while (iterator.hasNext()) {
                Action action = iterator.next();
                if (action == null) {
                    iterator.remove();
                    changed = true;
                    continue;
                }
                if (normalizeAction(action)) {
                    changed = true;
                }

                if (broadcastCountdownIfNeeded(action, now)) {
                    changed = true;
                }

                if (!isDue(action, now)) {
                    continue;
                }

                if (action.displayRemainingTime && !"action.item.clear".equals(action.targetActionName)) {
                    broadcastStartMessage(action);
                }

                executeAction(action);
                changed = true;

                if (action.doOnce) {
                    iterator.remove();
                } else {
                    action.latestExecuteTime = Timestamp.from(now.toInstant().plusSeconds(effectiveExecuteIntervalSec(action)));
                    action.latestCountdownRemainSec = -1;
                }
            }

            if (changed) {
                saveAllactions();
            }
        }, 20L, 20L);
    }

    public void stopActionScheduler() {
        if (actionTask != null) {
            actionTask.cancel();
            actionTask = null;
        }
    }

    public Toozlie.Result<Boolean> removeAction(int index) {
        ensureActionsLoaded();
        if (actions.getActions().isEmpty()) {
            return Toozlie.Result.error("Action list are empty.");
        }

        if (index < 0 || actions.getActions().size() < index+1) {
            return Toozlie.Result.error("Action index out of range.");
        }

        actions.getActions().remove(index);

        return saveAllactions();
    }

    private static final int PAGE_SIZE = 5;
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    public void addActionOnCommand(CommandSender sender, String name, String action,
                                   int executeIntervalMinute, boolean doOnce,
                                   boolean displayRemainingTime, boolean displayAtActionBar) {
        if (!ActionHelper.SupportActions.contains(action)) {
            sender.sendMessage(toozlie.messages.get(sender, "action.unsupported", "action", action));
            return;
        }

        if (executeIntervalMinute < 0) {
            sender.sendMessage(toozlie.messages.get(sender, "action.invalid-time"));
            return;
        }

        int minimumIntervalMinute = minimumExecuteIntervalMinute(action);
        if (!doOnce && executeIntervalMinute < minimumIntervalMinute) {
            sender.sendMessage(toozlie.messages.get(
                    sender,
                    "action.invalid-interval",
                    "minutes",
                    String.valueOf(minimumIntervalMinute)
            ));
            return;
        }

        startActionScheduler();

        Instant executeAt = Instant.now().plus(executeIntervalMinute, ChronoUnit.MINUTES);
        Timestamp actionTimestamp = Timestamp.from(executeAt);
        int executeIntervalSec = Math.max(1, executeIntervalMinute * 60);
        addAction(
                name,
                actionTimestamp,
                doOnce,
                displayRemainingTime,
                executeIntervalSec,
                displayAtActionBar,
                action
        );
        sender.sendMessage(toozlie.messages.get(
                sender,
                "action.scheduled",
                "action",
                action,
                "minutes",
                String.valueOf(executeIntervalMinute),
                "mode",
                doOnce ? "once" : "repeat",
                "displayRemainingTime",
                String.valueOf(displayRemainingTime)
        ));
    }

    public boolean list(CommandSender sender, int page) {
        ensureActionsLoaded();

        if (actions.getActions().isEmpty()) {
            sender.sendMessage(toozlie.messages.get(sender, "action.none"));
            return true;
        }

        int total = actions.getActions().size();
        int maxPage = Math.max(1, (int) Math.ceil(total / (double) PAGE_SIZE));
        page = Math.min(Math.max(page, 1), maxPage);

        int start = (page - 1) * PAGE_SIZE;
        int end = Math.min(start + PAGE_SIZE, total);

        sender.sendMessage(toozlie.messages.get(
                sender,
                "action.header",
                "page",
                String.valueOf(page),
                "maxPage",
                String.valueOf(maxPage),
                "total",
                String.valueOf(total)
        ));

        Timestamp now = Timestamp.from(Instant.now());
        for (int i = start; i < end; i++) {
            sendOne(sender, actions.getActions().get(i), i + 1, now);
        }

        return true;
    }

    public void listSupport(CommandSender sender) {
        sender.sendMessage(toozlie.messages.get(
                sender,
                "action.support.header",
                "total",
                String.valueOf(SupportActions.size())
        ));

        for (String action : SupportActions) {
            sender.sendMessage(Toozlie.deserializeText(toozlie.messages.get(
                    sender,
                    "action.support.entry",
                    "action",
                    action
            )));
        }
    }

    private void sendOne(CommandSender sender, Action action, int index, Timestamp now) {
        String message = toozlie.messages.get(
                sender,
                "action.entry",
                "index",
                String.valueOf(index),
                "name",
                nullToEmpty(action.name),
                "action",
                nullToEmpty(action.targetActionName),
                "mode",
                action.doOnce ? "once" : "repeat",
                "nextTime",
                formatNextExecuteTime(action, now),
                "interval",
                formatInterval(action.executeIntervalSec),
                "displayRemainingTime",
                String.valueOf(action.displayRemainingTime)
        );
        Component line = Toozlie.deserializeText(message);
        sender.sendMessage(line);
    }

    private String formatNextExecuteTime(Action action, Timestamp now) {
        Instant next = nextExecuteTime(action, now);
        if (next == null) {
            return "unknown";
        }
        if (!next.isAfter(now.toInstant())) {
            return "due";
        }
        return TS_FMT.format(next);
    }

    private Instant nextExecuteTime(Action action, Timestamp now) {
        if (action.latestExecuteTime == null) {
            return action.addTime == null ? now.toInstant() : action.addTime.toInstant();
        }

        return action.latestExecuteTime.toInstant();
    }

    private void ensureActionsLoaded() {
        if (actionFile == null) {
            loadAllActionsFromDisk();
        }
        if (actions == null) {
            actions = new Actions();
        }
        if (actions.getActions() == null) {
            actions.setActions(new ArrayList<>());
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private boolean isDue(Action action, Timestamp now) {
        if (action.latestExecuteTime == null) {
            action.latestExecuteTime = action.addTime == null ? now : action.addTime;
        }

        Instant target = action.latestExecuteTime.toInstant();
        return !target.isAfter(now.toInstant());
    }

    private boolean broadcastCountdownIfNeeded(Action action, Timestamp now) {
        Instant nextRunTime = nextExecuteTime(action, now);
        if (!action.displayRemainingTime || countdownMessageKey(action) == null || nextRunTime == null) {
            return false;
        }

        long remainingMillis = nextRunTime.toEpochMilli() - now.toInstant().toEpochMilli();
        if (remainingMillis <= 0) {
            return false;
        }

        long remainingSeconds = Math.max(1, (remainingMillis + 999) / 1000);
        if (action.latestCountdownRemainSec == remainingSeconds || !isCountdownMilestone(action, remainingSeconds)) {
            return false;
        }

        action.latestCountdownRemainSec = remainingSeconds;
        if (action.displayAtActionBar) {
            broadcastAtActionBar(countdownMessageKey(action), "time", formatRemainingTime(remainingSeconds));
        } else {
            broadcastNow(countdownMessageKey(action), "time", formatRemainingTime(remainingSeconds));
        }
        return true;
    }

    private String countdownMessageKey(Action action) {
        return switch (action.targetActionName) {
            case "action.server.stop" -> "action.server.stop.countdown";
            case "action.item.clear" -> "action.item.clear.countdown";
            default -> null;
        };
    }

    private boolean isCountdownMilestone(Action action, long remainingSeconds) {
        if ("action.server.stop".equals(action.targetActionName)) {
            return remainingSeconds == 3600
                    || remainingSeconds == 1800
                    || remainingSeconds == 600
                    || remainingSeconds == 300
                    || remainingSeconds == 60
                    || remainingSeconds == 30
                    || remainingSeconds <= 10;
        }

        return (remainingSeconds >= 60 && remainingSeconds % 60 == 0)
                || remainingSeconds == 30
                || remainingSeconds == 10;
    }

    private String formatRemainingTime(long remainingSeconds) {
        if (remainingSeconds >= 3600 && remainingSeconds % 3600 == 0) {
            return (remainingSeconds / 3600) + "h";
        }
        if (remainingSeconds >= 60 && remainingSeconds % 60 == 0) {
            return (remainingSeconds / 60) + "m";
        }
        return remainingSeconds + "s";
    }

    private boolean normalizeAction(Action action) {
        if (action.doOnce) {
            return false;
        }

        int minimumIntervalSec = minimumExecuteIntervalMinute(action.targetActionName) * 60;
        if (action.executeIntervalSec >= minimumIntervalSec) {
            return false;
        }

        action.executeIntervalSec = minimumIntervalSec;
        return true;
    }

    private int effectiveExecuteIntervalSec(Action action) {
        return Math.max(minimumExecuteIntervalMinute(action.targetActionName) * 60, action.executeIntervalSec);
    }

    private int minimumExecuteIntervalMinute(String actionName) {
        return 1;
    }

    private String formatInterval(int intervalSec) {
        if (intervalSec % 60 == 0) {
            return (intervalSec / 60) + "m";
        }
        return intervalSec + "s";
    }

    private void executeAction(Action action) {
        switch (action.targetActionName) {
            case "action.server.stop" -> {
                logger.info("Executing scheduled server stop action '{}'.", action.name);
                Bukkit.shutdown();
            }
            case "action.server.reload" -> {
                logger.info("Executing scheduled server reload action '{}'.", action.name);
                Bukkit.reload();
            }
            case "action.item.clear" -> {
                int removed = clearDroppedItems();
                logger.info("Executing scheduled item clear action '{}'. Removed {} dropped items.", action.name, removed);
                if (action.displayRemainingTime) {
                    if (action.displayAtActionBar) {
                        broadcastAtActionBar("action.item.clear.completed", "count", String.valueOf(removed));
                    } else {
                        broadcastNow("action.item.clear.completed", "count", String.valueOf(removed));
                    }
                }
            }
            default -> logger.warn("Skipping unsupported scheduled action '{}': {}", action.name, action.targetActionName);
        }
    }

    private int clearDroppedItems() {
        int removed = 0;
        for (World world : Bukkit.getWorlds()) {
            for (Item item : world.getEntitiesByClass(Item.class)) {
                item.remove();
                removed++;
            }
        }
        return removed;
    }

    private void broadcastStartMessage(Action action) {
        if (action.displayAtActionBar) {
            broadcastAtActionBar(
                    "action.started",
                    "name",
                    action.name
            );
        } else {
            broadcastNow(
                    "action.started",
                    "name",
                    action.name
            );
        }
    }

    private void broadcastAtActionBar(String key, String... replacements) {
        Bukkit.getOnlinePlayers().forEach(player ->
                player.sendActionBar(Toozlie.deserializeText(toozlie.messages.get(player, key, replacements)))
        );
    }

    private void broadcastNow(String key, String... replacements) {
        Bukkit.getOnlinePlayers().forEach(player ->
                player.sendMessage(Toozlie.deserializeText(toozlie.messages.get(player, key, replacements)))
        );
        Bukkit.getConsoleSender().sendMessage(Toozlie.deserializeText(
                toozlie.messages.get(toozlie.messages.defaultLocale(), key, replacements)
        ));
    }
}
