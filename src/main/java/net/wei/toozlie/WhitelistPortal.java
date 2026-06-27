package net.wei.toozlie;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

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


public final class WhitelistPortal {
    private HttpServer server;
    org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger("Toozlie.WhitelistPortal");

    // Very simple per-minute limiter (good enough for localhost automation)
    private final ConcurrentHashMap<String, AtomicInteger> minuteCounter = new ConcurrentHashMap<>();
    private volatile long currentMinute = System.currentTimeMillis() / 60000L;

    private Object floodgateApi;

    private final Toozlie toozlie;

    // Config
    private File configFile;

    public WhitelistPortal(Toozlie toozlie) {
        this.toozlie = toozlie;
    }

    public static final List<String> SupportHTTPMethod = List.of("POST", "GET");

    public void startWhitelist() {
        try {
            if (toozlie.config.whitelistPortal.enableWhitelistServer) {
                startHttp();
                logger.info("WhitelistPortal listening on http://{}:{}", toozlie.config.whitelistPortal.host, toozlie.config.whitelistPortal.port);
            }
        } catch (Exception e) {
            logger.error("Failed to start HTTP server: {}", e.getMessage());
            return;
        }

        hookFloodgate();
    }

    public void stopWhitelist() {
        if (server != null) {
            server.stop(0);
        }
    }

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

        server.createContext("/whitelist/on", ex -> handleAuthed(ex, () ->
                runSyncAndRespond(ex, () -> {
                    Bukkit.setWhitelist(true);
                    logger.info("Whitelist is enabled.");
                    return jsonOk("whitelist enabled");
                })
        ));

        server.createContext("/whitelist/off", ex -> handleAuthed(ex, () ->
                runSyncAndRespond(ex, () -> {
                    Bukkit.setWhitelist(false);
                    logger.info("Whitelist is disabled.");
                    return jsonOk("whitelist disabled");
                })
        ));

        server.createContext("/whitelist/add", ex -> handleAuthed(ex, () -> {
            String body = null;
            try {
                body = readBodyOnce(ex);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            Map<String, String> payload = parseTinyJson(body);
            runSyncAndRespond(ex, () -> {
                OfflinePlayer p = resolveOfflinePlayer(payload);
                if (p == null) return jsonFail("missing name or uuid");
                p.setWhitelisted(true);
                logger.info("Player " + display(p) + " is added to whitelist.");
                return jsonOk("whitelisted: " + display(p));
            });
        }));

        server.createContext("/whitelist/remove", ex -> handleAuthed(ex, () -> {
            String body = null;
            try {
                body = readBodyOnce(ex);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            Map<String, String> payload = parseTinyJson(body);
            runSyncAndRespond(ex, () -> {
                OfflinePlayer p = resolveOfflinePlayer(payload);
                if (p == null) return jsonFail("missing name or uuid");
                p.setWhitelisted(false);
                logger.info("Player {} is removed from whitelist.", display(p));
                return jsonOk("removed from whitelist: " + display(p));
            });
        }));

        server.createContext("/whitelist/list", ex -> handleAuthed(ex, () ->
                runSyncAndRespond(ex, () -> {
                    Set<OfflinePlayer> set = Bukkit.getWhitelistedPlayers();
                    StringBuilder sb = new StringBuilder();
                    sb.append("{\"ok\":true,\"players\":[");
                    boolean first = true;
                    for (OfflinePlayer p : set) {
                        if (!first) sb.append(",");
                        first = false;
                        sb.append("{\"uuid\":\"").append(p.getUniqueId()).append("\",");
                        sb.append("\"name\":").append(p.getName() == null ? "null" : "\"" + escape(p.getName()) + "\"").append("}");
                    }
                    sb.append("]}");
                    return sb.toString();
                })
        ));

        server.createContext("/whitelist/status", ex -> handleAuthed(ex, () ->
                runSyncAndRespond(ex, this::getWhitelistStatus)
        ));

        server.createContext("/player/online", ex -> handleAuthed(ex, () ->
                runSyncAndRespond(ex, this::buildOnlinePlayersJson)
        ));

        // Thread pool for HTTP handling (Bukkit actions still run sync)
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();
    }

    private String getWhitelistStatus() {
        return "{\"ok\":true,\"whitelist\":\"" +
                Bukkit.hasWhitelist() + "\"}";
    }

    private void hookFloodgate() {
        try {
            if (!Bukkit.getPluginManager().isPluginEnabled("floodgate")) {
                floodgateApi = null;
                logger.info("Floodgate API not available");
                return;
            }

            Class<?> apiClass = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
            floodgateApi = apiClass.getMethod("getInstance").invoke(null);
            logger.info("Floodgate API hooked via FloodgateApi.getInstance()");
        } catch (Throwable e) {
            floodgateApi = null;
            logger.info("Floodgate API not available");
        }
    }

    private String buildOnlinePlayersJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"ok\":true,\"players\":[");

        boolean first = true;
        for (var p : Bukkit.getOnlinePlayers()) {
            if (!first) sb.append(",");
            first = false;

            UUID uuid = p.getUniqueId();
            boolean isFloodgate = isFloodgatePlayer(uuid);

            sb.append("{");
            sb.append("\"name\":\"").append(escape(p.getName())).append("\",");
            sb.append("\"uuid\":\"").append(uuid).append("\",");
            sb.append("\"isFloodgate\":").append(isFloodgate).append(",");

            if (isFloodgate) {
                sb.append("\"floodgateUuid\":\"").append(uuid).append("\"");
            } else {
                sb.append("\"floodgateUuid\":null");
            }

            sb.append("}");
        }

        sb.append("]}");
        return sb.toString();
    }

    private boolean isFloodgatePlayer(UUID uuid) {
        if (floodgateApi == null) return false;
        if (Bukkit.getPlayer(uuid) == null) return false;

        try {
            return (boolean) floodgateApi.getClass()
                    .getMethod("isFloodgatePlayer", UUID.class)
                    .invoke(floodgateApi, uuid);
        } catch (Throwable e) {
            logger.debug("Unable to check Floodgate player {}: {}", uuid, e.toString());
            return false;
        }
    }

    private String getFloodgateXuid(UUID uuid) {
        if (floodgateApi == null) return null;
        if (Bukkit.getPlayer(uuid) == null) return null;

        try {
            Object floodgatePlayer = floodgateApi.getClass()
                    .getMethod("getPlayer", UUID.class)
                    .invoke(floodgateApi, uuid);

            if (floodgatePlayer == null) return null;

            // FloodgatePlayer#getXuid()
            return (String) floodgatePlayer.getClass()
                    .getMethod("getXuid")
                    .invoke(floodgatePlayer);

        } catch (Throwable e) {
            logger.debug("Unable to get Floodgate XUID for {}: {}", uuid, e.toString());
            return null;
        }
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

