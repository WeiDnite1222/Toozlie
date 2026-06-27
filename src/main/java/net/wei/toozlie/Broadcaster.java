package net.wei.toozlie;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.bukkit.command.CommandSender;
import org.bukkit.scheduler.BukkitTask;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


public final class Broadcaster {
    private HttpServer server;
    org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger("Toozlie.Broadcaster");

    // Very simple per-minute limiter (good enough for localhost automation)
    private final ConcurrentHashMap<String, AtomicInteger> minuteCounter = new ConcurrentHashMap<>();
    private volatile long currentMinute = System.currentTimeMillis() / 60000L;

    private final Toozlie toozlie;

    // Broadcast file
    private File broadcastFile;
    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
    private final ObjectMapper jsonMapper = new ObjectMapper();

    public static class Broadcast {
        public String name;
        public Timestamp addTime;
        public Timestamp latestBroadcastTime;
        public String content;
        public boolean display;
        public int displayIntervalSec = 600;
        public boolean displayAtActionBar = false;

        public Broadcast() {
        }

        public Broadcast(String name, Timestamp addTime, Timestamp latestBroadcastTime, String content, boolean display,
                         int displayIntervalSec, boolean displayAtActionBar) {
            this.name = name;
            this.addTime = addTime;
            this.latestBroadcastTime = latestBroadcastTime;
            this.content = content;
            this.display = display;
            this.displayIntervalSec = displayIntervalSec;
            this.displayAtActionBar = displayAtActionBar;
        }
    }

    public static class Broadcasts {
        private List<Broadcast> broadcasts = new ArrayList<>();

        public Broadcasts() {
        }

        // Getter & Setter
        public List<Broadcast> getBroadcasts() { return broadcasts; }
        public void setBroadcasts(List<Broadcast> broadcasts) {
            this.broadcasts = broadcasts == null ? new ArrayList<>() : broadcasts;
        }
    }

    private Broadcasts broadcasts = new Broadcasts();

    public Broadcaster(Toozlie toozlie) {
        this.toozlie = toozlie;
    }

    public static final List<String> SupportHTTPMethod = List.of("POST", "GET");

    private BukkitTask broadcastTask;

    public void startBroadcastTask() {
        broadcastTask = Bukkit.getScheduler().runTaskTimer(toozlie, () -> {
            ensureBroadcastsLoaded();

            Timestamp now = Timestamp.from(Instant.now());

            for (Broadcast broadcast : broadcasts.getBroadcasts()) {
                if (!broadcast.display) continue;

                if (broadcast.latestBroadcastTime == null) {
                    broadcast.latestBroadcastTime = Timestamp.from(Instant.now());
                }

                Instant last = broadcast.latestBroadcastTime.toInstant();

                long elapsedSeconds = Duration.between(
                        last,
                        now.toInstant()
                ).getSeconds();

                if (elapsedSeconds >= broadcast.displayIntervalSec) {
                    if (broadcast.displayAtActionBar) {
                        broadcastActionBar(broadcast);
                    } else {
                        broadcastNow(broadcast);
                    }
                    broadcast.latestBroadcastTime = now;
                    saveAllBroadcasts();
                }
            }
        }, 20L, 20L);
    }

    public void startBroadcaster() {
        loadAllBroadcasts();
        startBroadcastTask();

        // HTTP Server is deprecated
    }

    public void stopBroadcaster() {
        if (server != null) {
            server.stop(0);
        }
        if (broadcastTask != null) {
            broadcastTask.cancel();
        }
    }

    //
    // ========== Broadcast ==========
    //
    private void loadAllBroadcasts() {
        try {
            broadcastFile = new File(toozlie.getDataFolder(), "broadcasts.yml");

            if (!toozlie.getDataFolder().exists() && !toozlie.getDataFolder().mkdirs()) {
                logger.warn("Failed to create plugin data folder: {}", toozlie.getDataFolder());
            }

            if (!broadcastFile.exists() || broadcastFile.length() == 0) {
                broadcasts = new Broadcasts();
                saveAllBroadcasts();
                return;
            }

            broadcasts = yamlMapper.readValue(broadcastFile, Broadcasts.class);
            if (broadcasts == null) {
                broadcasts = new Broadcasts();
            }
            if (broadcasts.getBroadcasts() == null) {
                broadcasts.setBroadcasts(new ArrayList<>());
            }
        } catch (Exception exception) {
            logger.error("An error occurred while reading broadcast file. Exec: {}", exception.toString());
            broadcasts = new Broadcasts();
        }
    }

    private Toozlie.Result<Boolean> saveAllBroadcasts() {
        try {
            if (broadcastFile == null) {
                broadcastFile = new File(toozlie.getDataFolder(), "broadcasts.yml");
            }
            yamlMapper.writeValue(broadcastFile, broadcasts);
            return Toozlie.Result.success(true);
        } catch (Exception exception) {
            logger.error("An error occurred while saving broadcast file. Exec: {}", exception.toString());
            return Toozlie.Result.error("Failed to save broadcast file.");
        }
    }

    public void addBroadcast(String name, String content, boolean display, int displayIntervalSec, boolean displayAtActionBar) {
        ensureBroadcastsLoaded();
        Broadcast broadcast = new Broadcast(name, Timestamp.from(Instant.now()),
                Timestamp.from(Instant.now()),  content, display, displayIntervalSec, displayAtActionBar);
        broadcasts.getBroadcasts().add(broadcast);
        saveAllBroadcasts();
    }

    public Toozlie.Result<Boolean> removeBroadcast(int index) {
        ensureBroadcastsLoaded();
        if (broadcasts.getBroadcasts().isEmpty()) {
            return Toozlie.Result.error("Broadcast list are empty.");
        }

        if (index < 0 || broadcasts.getBroadcasts().size() < index+1) {
            return Toozlie.Result.error("Broadcast index out of range.");
        }

        broadcasts.getBroadcasts().remove(index);

        return saveAllBroadcasts();
    }

    private void ensureBroadcastsLoaded() {
        if (broadcastFile == null) {
            loadAllBroadcasts();
        }
        if (broadcasts == null) {
            broadcasts = new Broadcasts();
        }
        if (broadcasts.getBroadcasts() == null) {
            broadcasts.setBroadcasts(new ArrayList<>());
        }
    }

    public static void broadcastActionBar(Broadcast broadcast) {
        Bukkit.getOnlinePlayers().forEach(player -> {
            player.sendActionBar(Toozlie.deserializeText(broadcast.content));
        });
    }

    private void broadcastNow(Broadcast broadcast) {
        Bukkit.broadcast(Toozlie.deserializeText(broadcast.content));
    }

    //
    // ========== Command ==========
    //
    private final int PAGE_SIZE = 3;

    public boolean list(CommandSender p, int page) {
        if (broadcasts.getBroadcasts().isEmpty()) {
            p.sendMessage(toozlie.messages.get(p, "broadcaster.none"));
            return true;
        }

        int total = broadcasts.broadcasts.size();
        int maxPage = Math.max(1, (int) Math.ceil(total / (double) PAGE_SIZE));
        page = Math.min(Math.max(page, 1), maxPage);

        int start = (page - 1) * PAGE_SIZE;
        int end = Math.min(start + PAGE_SIZE, total);

        p.sendMessage(toozlie.messages.get(
                p,
                "broadcaster.header",
                "page",
                String.valueOf(page),
                "maxPage",
                String.valueOf(maxPage),
                "total",
                String.valueOf(total)
        ));
        for (int i = start; i < end; i++) {
            sendOne(p, broadcasts.broadcasts.get(i), i + 1);
        }
        return true;
    }

    private static final DateTimeFormatter TS_FMT = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    private void sendOne(CommandSender p, Broadcast broadcast, int index) {
        String message = toozlie.messages.get(
                p,
                "broadcaster.entry",
                "index",
                String.valueOf(index),
                "name",
                broadcast.name,
                "content",
                broadcast.content,
                "time",
                TS_FMT.format(broadcast.addTime.toInstant())
        );
        Component line = Toozlie.deserializeText(message);
        p.sendMessage(line);
    }

    //
    // ========== HTTP Server ==========
    //
    private String readBodyOnce(HttpExchange ex) throws IOException {
        Object cached = ex.getAttribute("cachedBody");
        if (cached instanceof String) {
            return (String) cached;
        }

        InputStream is = ex.getRequestBody();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[512];
        int r;
        int total = 0;

        while ((r = is.read(buf)) != -1) {
            total += r;
            if (total > toozlie.config.whitelistPortal.security.maxBodyBytes) {
                throw new IOException("Request body too large");
            }
            baos.write(buf, 0, r);
        }

        String body = baos.toString(StandardCharsets.UTF_8);
        ex.setAttribute("cachedBody", body);
        return body;
    }

    private void startHttp() throws IOException {
        InetSocketAddress addr = new InetSocketAddress(
                toozlie.config.whitelistPortal.host,
                toozlie.config.whitelistPortal.port);
        server = HttpServer.create(addr, 0);

        server.createContext("/broadcast/list", ex -> handleAuthed(ex, () ->
                runSyncAndRespond(ex, this::buildBroadcastListJson)
        ));

        server.createContext("/broadcast/add", ex -> handleAuthed(ex, () -> {
            Map<String, Object> payload;
            try {
                payload = parseJsonObject(readBodyOnce(ex));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }

            runSyncAndRespond(ex, () -> {
                String name = stringValue(payload.get("name"));
                String content = stringValue(payload.get("content"));
                boolean display = booleanValue(payload.get("display"), true);
                int displayIntervalSec = intValue(payload.get("displayIntervalSec"), 600);
                boolean displayAtActionVar = booleanValue(payload.get("displayAtActionVar"), true);

                if (name.isBlank()) return jsonFail("missing broadcast name");
                if (content.isBlank()) return jsonFail("missing broadcast content");
                if (displayIntervalSec <= 0) return jsonFail("displayIntervalSec must be greater than zero");

                addBroadcast(name, content, display, displayIntervalSec, displayAtActionVar);
                logger.info("Broadcast '{}' is added.", name);
                return jsonOk("broadcast added");
            });
        }));

        server.createContext("/broadcast/remove", ex -> handleAuthed(ex, () -> {
            Map<String, Object> payload;
            try {
                payload = parseJsonObject(readBodyOnce(ex));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }

            runSyncAndRespond(ex, () -> {
                int index = intValue(payload.get("index"), -1);
                Toozlie.Result<Boolean> result = removeBroadcast(index);
                if (!result.isSuccess()) return jsonFail(result.getMessage());
                logger.info("Broadcast at index {} is removed.", index);
                return jsonOk("broadcast removed");
            });
        }));

        // Thread pool for HTTP handling (Bukkit actions still run sync)
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();
    }

    private String buildBroadcastListJson() {
        ensureBroadcastsLoaded();

        StringBuilder sb = new StringBuilder();
        sb.append("{\"ok\":true,\"broadcasts\":[");

        boolean first = true;
        List<Broadcast> list = broadcasts.getBroadcasts();
        for (int i = 0; i < list.size(); i++) {
            Broadcast broadcast = list.get(i);
            if (!first) sb.append(",");
            first = false;

            sb.append("{");
            sb.append("\"index\":").append(i).append(",");
            sb.append("\"name\":\"").append(escape(nullToEmpty(broadcast.name))).append("\",");
            sb.append("\"addTime\":").append(timestampJsonValue(broadcast.addTime)).append(",");
            sb.append("\"latestBroadcastTime\":").append(timestampJsonValue(broadcast.latestBroadcastTime)).append(",");
            sb.append("\"content\":\"").append(escape(nullToEmpty(broadcast.content))).append("\",");
            sb.append("\"display\":").append(broadcast.display).append(",");
            sb.append("\"displayIntervalSec\":").append(broadcast.displayIntervalSec);
            sb.append("}");
        }

        sb.append("]}");
        return sb.toString();
    }

    private void handleAuthed(HttpExchange ex, Runnable authedAction) throws IOException {
        try {
            // Method restriction
            if (!toozlie.config.whitelistPortal.security.requireMethod.equalsIgnoreCase(ex.getRequestMethod())) {
                writeJson(ex, 405, jsonFail("method not allowed"));
                return;
            }

            // Rate limit per minute per IP (localhost typically)
            if (!allowRequest(ex)) {
                writeJson(ex, 429, jsonFail("rate limited"));
                return;
            }

            // Basic path sanity
            if (!isAuthed(ex)) {
                writeJson(ex, 401, jsonFail("unauthorized"));
                return;
            }

            authedAction.run();
        } catch (Exception e) {
            logger.warn("HTTP error: {}", e.getMessage());
            writeJson(ex, 500, jsonFail("internal error"));
        }
    }

    private boolean allowRequest(HttpExchange ex) {
        long minute = System.currentTimeMillis() / 60000L;
        if (minute != currentMinute) {
            currentMinute = minute;
            minuteCounter.clear();
        }
        String key = ex.getRemoteAddress().getAddress().getHostAddress();
        AtomicInteger c = minuteCounter.computeIfAbsent(key, k -> new AtomicInteger(0));
        return c.incrementAndGet() <= toozlie.config.whitelistPortal.security.maxRequestsPerMinute;
    }

    private boolean isAuthed(HttpExchange ex) {
        Headers h = ex.getRequestHeaders();

        if ("token".equals(toozlie.config.whitelistPortal.auth.mode)) {
            String auth = h.getFirst("Authorization");
            if (auth == null) return false;
            String prefix = "Bearer ";
            if (!auth.startsWith(prefix)) return false;
            String got = auth.substring(prefix.length()).trim();
            return constantTimeEquals(got, toozlie.config.whitelistPortal.auth.token);
        }

        if ("hmac".equals(toozlie.config.whitelistPortal.auth.mode)) {
            String sig = h.getFirst("X-Signature");
            String ts = h.getFirst("X-Timestamp");
            if (sig == null || ts == null) return false;

            long now = Instant.now().getEpochSecond();
            long t;
            try { t = Long.parseLong(ts); } catch (Exception e) { return false; }
            if (Math.abs(now - t) > toozlie.config.whitelistPortal.auth.allowSkewSeconds) return false;

            // Signature over: method + "\n" + path + "\n" + timestamp + "\n" + body
            String body = "";
            try {
                body = readBody(ex); // consumes stream; store in attribute for later usage
                ex.setAttribute("cachedBody", body);
            } catch (IOException ignored) {}

            String data = ex.getRequestMethod() + "\n" + ex.getRequestURI().getPath() + "\n" + ts + "\n" + body;
            String expected = hmacHex(toozlie.config.whitelistPortal.auth.hmacSecret, data);
            return constantTimeEquals(sig.toLowerCase(Locale.ROOT), expected.toLowerCase(Locale.ROOT));
        }

        // Unknown mode -> deny
        return false;
    }

    private String readBody(HttpExchange ex) throws IOException {
        try (InputStream is = ex.getRequestBody()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private void runSyncAndRespond(HttpExchange ex, Callable<String> action) {
        try {
            // Run Bukkit actions on the main server thread and wait for the result.
            Future<String> future = Bukkit.getScheduler().callSyncMethod(toozlie, action);

            String json;
            try {
                json = future.get();
            } catch (ExecutionException ee) {
                logger.error("Sync task error: {}", String.valueOf(ee.getCause()));
                writeJson(ex, 500, jsonFail("internal error"));
                return;
            }

            writeJson(ex, 200, json);

        } catch (Exception e) {
            logger.error("HTTP handler error");
            e.printStackTrace();

            try {
                writeJson(ex, 500, jsonFail("internal error"));
            } catch (IOException io) {
                io.printStackTrace();
            }
        }
    }

    private void writeJson(HttpExchange ex, int code, String json) throws IOException {
        byte[] data = json.getBytes(StandardCharsets.UTF_8);

        Headers h = ex.getResponseHeaders();
        h.set("Content-Type", "application/json; charset=utf-8");
        h.set("Cache-Control", "no-store");
        h.set("Connection", "close");

        ex.sendResponseHeaders(code, data.length);

        try (OutputStream os = ex.getResponseBody()) {
            os.write(data);
            os.flush();
        }

        ex.close();
    }

    private OfflinePlayer resolveOfflinePlayer(Map<String, String> payload) {

        String floodgateUuid = payload.get("floodgateUuid");
        if (floodgateUuid != null && !floodgateUuid.isBlank()) {
            try {
                UUID id = UUID.fromString(floodgateUuid.trim());
                return Bukkit.getOfflinePlayer(id);
            } catch (IllegalArgumentException ignored) {}
        }

        String uuid = payload.get("uuid");
        if (uuid != null && !uuid.isBlank()) {
            try {
                UUID id = UUID.fromString(uuid.trim());
                return Bukkit.getOfflinePlayer(id);
            } catch (IllegalArgumentException ignored) {}
        }

        String name = payload.get("name");
        if (name != null && !name.isBlank()) {

            for (var p : Bukkit.getOnlinePlayers()) {
                if (p.getName().equalsIgnoreCase(name.trim())) {
                    return p;
                }
            }

            return Bukkit.getOfflinePlayer(name.trim());
        }

        return null;
    }


    // Tiny JSON parser for {"name":"x"} / {"uuid":"..."} only
    private static final Pattern KV = Pattern.compile("\"(floodgateUuid|uuid|name)\"\\s*:\\s*\"([^\"]*)\"");
    private Map<String, String> parseTinyJson(String body) {
        Map<String, String> m = new HashMap<>();
        if (body == null) return m;
        Matcher matcher = KV.matcher(body);
        while (matcher.find()) {
            m.put(matcher.group(1), matcher.group(2));
        }
        return m;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJsonObject(String body) {
        if (body == null || body.isBlank()) {
            return new HashMap<>();
        }

        try {
            Object parsed = jsonMapper.readValue(body, Map.class);
            if (parsed instanceof Map<?, ?> map) {
                Map<String, Object> result = new HashMap<>();
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (entry.getKey() != null) {
                        result.put(String.valueOf(entry.getKey()), entry.getValue());
                    }
                }
                return result;
            }
        } catch (IOException ignored) {
        }

        return new HashMap<>();
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private boolean booleanValue(Object value, boolean fallback) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String text) {
            return Boolean.parseBoolean(text.trim());
        }
        return fallback;
    }

    private int intValue(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return fallback;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String timestampJsonValue(Timestamp timestamp) {
        return timestamp == null ? "null" : "\"" + escape(timestamp.toInstant().toString()) + "\"";
    }

    private String jsonOk(String message) {
        return "{\"ok\":true,\"message\":\"" + escape(message) + "\"}";
    }

    private String jsonFail(String message) {
        return "{\"ok\":false,\"message\":\"" + escape(message) + "\"}";
    }

    private String display(OfflinePlayer p) {
        String name = p.getName();
        return (name == null ? "null" : name) + " (" + p.getUniqueId() + ")";
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        byte[] x = a.getBytes(StandardCharsets.UTF_8);
        byte[] y = b.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(x, y);
    }

    private static String hmacHex(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] out = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : out) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }
}

