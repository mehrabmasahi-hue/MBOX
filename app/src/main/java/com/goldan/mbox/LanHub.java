package com.goldan.mbox;

import android.content.Context;
import android.net.wifi.WifiManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** LAN host embedded in the Display APK. No Termux process or external server is required. */
public final class LanHub {
    public static final int HTTP_PORT = 1212;
    public static final int DISCOVERY_PORT = 1213;
    public static final int INPUT_PORT = 1214;
    private static final long MAX_GAME_BYTES = 100L * 1024L * 1024L;

    private final Context context;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Map<String, Player> players = new LinkedHashMap<>();
    private final File gamesDir;
    private volatile String roomCode = "----";
    private volatile String localIp = "در حال دریافت آدرس شبکه";
    private volatile String adminId = "";
    private volatile String currentGame = "";
    private volatile boolean menuReady = false;
    private volatile String launchPath = null;
    private ServerSocket serverSocket;
    private DatagramSocket discoverySocket;
    private DatagramSocket inputSocket;
    private WifiManager.MulticastLock multicastLock;

    public LanHub(Context context) {
        this.context = context.getApplicationContext();
        this.gamesDir = new File(this.context.getFilesDir(), "games");
        if (!gamesDir.exists()) gamesDir.mkdirs();
        installSampleGame();
    }

    private void installSampleGame() {
        File sample = new File(gamesDir, "Neon-Arena.123T");
        if (sample.exists()) return;
        try (InputStream in = context.getAssets().open("default-games/Neon-Arena.123T");
             FileOutputStream out = new FileOutputStream(sample)) {
            byte[] buffer = new byte[8192]; int count;
            while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
            out.flush();
        } catch (Exception e) {
            android.util.Log.w("MBOX", "Sample game not installed: " + e.getMessage());
        }
    }

    public synchronized void start() throws Exception {
        if (running.get()) return;
        localIp = findLocalIp();
        roomCode = makeRoomCode();
        players.clear();
        adminId = "";
        currentGame = "";
        menuReady = false;
        launchPath = null;
        try {
            serverSocket = new ServerSocket(HTTP_PORT, 32, InetAddress.getByName("0.0.0.0"));
            discoverySocket = new DatagramSocket(DISCOVERY_PORT, InetAddress.getByName("0.0.0.0"));
            inputSocket = new DatagramSocket(INPUT_PORT, InetAddress.getByName("0.0.0.0"));
            try {
                WifiManager wifi = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
                if (wifi != null) {
                    multicastLock = wifi.createMulticastLock("MBOX-LAN");
                    multicastLock.setReferenceCounted(false);
                    multicastLock.acquire();
                }
            } catch (Exception ignored) { }
        } catch (Exception e) {
            try { if (serverSocket != null) serverSocket.close(); } catch (Exception ignored) { }
            try { if (discoverySocket != null) discoverySocket.close(); } catch (Exception ignored) { }
            try { if (inputSocket != null) inputSocket.close(); } catch (Exception ignored) { }
            try { if (multicastLock != null && multicastLock.isHeld()) multicastLock.release(); } catch (Exception ignored) { }
            throw e;
        }
        running.set(true);
        thread("MBOX-HTTP", this::httpLoop).start();
        thread("MBOX-Discovery", this::discoveryLoop).start();
        thread("MBOX-Input", this::inputLoop).start();
    }

    public boolean isRunning() { return running.get(); }
    public String getRoomCode() { return roomCode; }
    public String getLocalIp() { return localIp; }

    private Thread thread(String name, Runnable task) {
        Thread t = new Thread(task, name);
        t.setDaemon(true);
        return t;
    }

    private String makeRoomCode() {
        int value = 1000 + new SecureRandom().nextInt(9000);
        return String.valueOf(value);
    }

    private String findLocalIp() {
        try {
            List<String> addresses = new ArrayList<>();
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                Enumeration<InetAddress> ips = ni.getInetAddresses();
                while (ips.hasMoreElements()) {
                    InetAddress address = ips.nextElement();
                    if (address instanceof Inet4Address && address.isSiteLocalAddress()) {
                        String ip = address.getHostAddress();
                        if (ip != null && !ip.startsWith("127.")) {
                            if (ni.getName().toLowerCase().contains("wlan")) return ip;
                            addresses.add(ip);
                        }
                    }
                }
            }
            if (!addresses.isEmpty()) return addresses.get(0);
        } catch (Exception ignored) { }
        return "IP در دسترس نیست";
    }

    private void discoveryLoop() {
        byte[] buffer = new byte[512];
        while (running.get()) {
            try {
                DatagramPacket incoming = new DatagramPacket(buffer, buffer.length);
                discoverySocket.receive(incoming);
                String request = new String(incoming.getData(), incoming.getOffset(), incoming.getLength(), StandardCharsets.UTF_8);
                if (request.startsWith("MBOX_DISCOVER|") && request.substring("MBOX_DISCOVER|".length()).trim().equals(roomCode)) {
                    String reply = "MBOX_FOUND|" + localIp + "|" + HTTP_PORT + "|" + INPUT_PORT;
                    byte[] bytes = reply.getBytes(StandardCharsets.UTF_8);
                    DatagramPacket response = new DatagramPacket(bytes, bytes.length, incoming.getAddress(), incoming.getPort());
                    discoverySocket.send(response);
                }
            } catch (Exception e) {
                if (running.get()) android.util.Log.w("MBOX", "Discovery socket: " + e.getMessage());
            }
        }
    }

    private void inputLoop() {
        byte[] buffer = new byte[8192];
        while (running.get()) {
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                inputSocket.receive(packet);
                String raw = new String(packet.getData(), packet.getOffset(), packet.getLength(), StandardCharsets.UTF_8);
                if (!raw.startsWith("MBOX_INPUT|")) continue;
                int second = raw.indexOf('|', 11);
                if (second < 0) continue;
                int third = raw.indexOf('|', second + 1);
                if (third < 0) continue;
                int fourth = raw.indexOf('|', third + 1);
                if (fourth < 0) continue;
                String code = raw.substring(11, second);
                String id = raw.substring(second + 1, third);
                String token = raw.substring(third + 1, fourth);
                if (!roomCode.equals(code)) continue;
                JSONObject input = new JSONObject(raw.substring(fourth + 1));
                synchronized (players) {
                    Player player = players.get(id);
                    if (player != null && player.sessionToken.equals(token) && "active".equals(player.status)) {
                        player.input = input;
                        player.lastInputAt = System.currentTimeMillis();
                    }
                }
            } catch (Exception e) {
                if (running.get()) android.util.Log.w("MBOX", "Input socket: " + e.getMessage());
            }
        }
    }

    private void httpLoop() {
        while (running.get()) {
            try {
                Socket socket = serverSocket.accept();
                socket.setSoTimeout(15000);
                thread("MBOX-HTTP-Client", () -> handleSocket(socket)).start();
            } catch (Exception e) {
                if (running.get()) android.util.Log.w("MBOX", "HTTP accept: " + e.getMessage());
            }
        }
    }

    private void handleSocket(Socket socket) {
        try (Socket s = socket) {
            InputStream in = s.getInputStream();
            OutputStream out = new BufferedOutputStream(s.getOutputStream());
            String requestLine = readLine(in);
            if (requestLine == null || requestLine.trim().isEmpty()) return;
            String[] first = requestLine.split(" ");
            if (first.length < 2) { writeJson(out, 400, error("درخواست نامعتبر")); return; }
            String method = first[0].toUpperCase();
            String target = first[1];
            Map<String, String> headers = new LinkedHashMap<>();
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                int colon = line.indexOf(':');
                if (colon > 0) headers.put(line.substring(0, colon).trim().toLowerCase(), line.substring(colon + 1).trim());
            }
            int length = 0;
            try { length = Integer.parseInt(headers.getOrDefault("content-length", "0")); } catch (Exception ignored) { }
            if (length < 0 || length > MAX_GAME_BYTES + 1024) { writeJson(out, 413, error("حجم فایل بیش از حد مجاز است")); return; }
            String path = target.split("\\?", 2)[0];
            Map<String, String> query = parseQuery(target);

            if ("GET".equals(method) && "/ping".equals(path)) {
                JSONObject ok = new JSONObject(); ok.put("ok", true); ok.put("app", "MBOX"); writeJson(out, 200, ok); return;
            }
            if ("GET".equals(method) && "/api/state".equals(path)) { writeJson(out, 200, getPublicState()); return; }
            if ("GET".equals(method) && "/api/me".equals(path)) { writeJson(out, 200, getPlayerState(query.get("id"), query.get("token"))); return; }
            if ("GET".equals(method) && "/api/games".equals(path)) { JSONObject o = new JSONObject(); o.put("games", listGames()); writeJson(out, 200, o); return; }

            if ("POST".equals(method) && "/api/game/upload".equals(path)) {
                String id = query.get("id");
                if (!isAdminSession(id, query.get("token"))) { drain(in, length); writeJson(out, 403, error("فقط مدیر می‌تواند بازی بارگذاری کند")); return; }
                String name = safeFileName(query.get("name"));
                if (name == null || !name.toLowerCase().endsWith(".123t")) { drain(in, length); writeJson(out, 400, error("فقط فایل با پسوند .123T پذیرفته می‌شود")); return; }
                if (length <= 0 || length > MAX_GAME_BYTES) { drain(in, length); writeJson(out, 413, error("حجم فایل باید کمتر از ۱۰۰ مگابایت باشد")); return; }
                File targetFile = new File(gamesDir, name);
                File temp = new File(gamesDir, name + ".uploading");
                try (FileOutputStream fos = new FileOutputStream(temp)) {
                    copyExactly(in, fos, length);
                }
                if (targetFile.exists()) targetFile.delete();
                if (!temp.renameTo(targetFile)) { temp.delete(); writeJson(out, 500, error("ذخیره فایل انجام نشد")); return; }
                JSONObject result = new JSONObject(); result.put("ok", true); result.put("name", name); writeJson(out, 200, result); return;
            }

            byte[] body = readExactly(in, length);
            JSONObject json = body.length == 0 ? new JSONObject() : new JSONObject(new String(body, StandardCharsets.UTF_8));
            if ("POST".equals(method) && "/api/join".equals(path)) { handleJoin(json, out); return; }
            if ("POST".equals(method) && "/api/approve".equals(path)) { handleDecision(json, true, out); return; }
            if ("POST".equals(method) && "/api/reject".equals(path)) { handleDecision(json, false, out); return; }
            if ("POST".equals(method) && "/api/game/start".equals(path)) { handleGameStart(json, out); return; }
            writeJson(out, 404, error("مسیر پیدا نشد"));
        } catch (Exception e) {
            android.util.Log.e("MBOX", "HTTP request failed", e);
        }
    }

    private void handleJoin(JSONObject json, OutputStream out) throws Exception {
        String suppliedCode = json.optString("code", "");
        String id = json.optString("id", "");
        String name = json.optString("name", "بازیکن").trim();
        String suppliedToken = json.optString("token", "");
        if (!roomCode.equals(suppliedCode)) { writeJson(out, 403, error("کد اتصال اشتباه است")); return; }
        if (id.length() < 8 || id.length() > 80) { writeJson(out, 400, error("شناسه دستگاه نامعتبر است")); return; }
        if (name.isEmpty()) name = "بازیکن";
        synchronized (players) {
            Player existing = players.get(id);
            if (existing != null && !existing.sessionToken.equals(suppliedToken)) { writeJson(out, 403, error("این شناسه قبلاً ثبت شده؛ نشست دستگاه تأیید نشد")); return; }
            if (existing != null && !"rejected".equals(existing.status)) { writeJson(out, 200, playerToJson(existing, true)); return; }
            if (existing != null) players.remove(id);
            int occupied = 0;
            for (Player item : players.values()) if (!"rejected".equals(item.status)) occupied++;
            if (occupied >= 4) { writeJson(out, 409, error("ظرفیت اتاق تکمیل است؛ حداکثر ۴ بازیکن")); return; }
            Player p = new Player(); p.id = id; p.name = name; p.sessionToken = newSessionToken();
            if (adminId.isEmpty()) {
                adminId = id; p.status = "active"; p.playerNo = 1; p.admin = true;
            } else {
                p.status = "pending"; p.playerNo = 0; p.admin = false;
            }
            players.put(id, p);
            writeJson(out, 200, playerToJson(p, true));
        }
    }

    private void handleDecision(JSONObject json, boolean approve, OutputStream out) throws Exception {
        String manager = json.optString("managerId", "");
        String managerToken = json.optString("managerToken", "");
        String targetId = json.optString("playerId", "");
        if (!isAdminSession(manager, managerToken)) { writeJson(out, 403, error("این عملیات فقط برای مدیر مجاز است")); return; }
        synchronized (players) {
            Player p = players.get(targetId);
            if (p == null || !"pending".equals(p.status)) { writeJson(out, 404, error("درخواست بازیکن پیدا نشد")); return; }
            if (approve) {
                int active = 0;
                boolean[] used = new boolean[5];
                for (Player item : players.values()) if ("active".equals(item.status) && item.playerNo > 0 && item.playerNo <= 4) { active++; used[item.playerNo] = true; }
                if (active >= 4) { writeJson(out, 409, error("ظرفیت چهار بازیکن تکمیل است")); return; }
                int next = 1; while (next <= 4 && used[next]) next++;
                p.playerNo = next; p.status = "active";
            } else {
                p.status = "rejected";
            }
            writeJson(out, 200, playerToJson(p, false));
        }
    }

    private void handleGameStart(JSONObject json, OutputStream out) throws Exception {
        String manager = json.optString("managerId", "");
        String managerToken = json.optString("managerToken", "");
        String name = safeFileName(json.optString("name", ""));
        if (!isAdminSession(manager, managerToken)) { writeJson(out, 403, error("فقط مدیر می‌تواند بازی را اجرا کند")); return; }
        if (!menuReady) { writeJson(out, 409, error("ابتدا روی نمایشگر دکمه ادامه را بزنید")); return; }
        if (name == null || !name.toLowerCase().endsWith(".123t")) { writeJson(out, 400, error("نام بسته بازی معتبر نیست")); return; }
        File archive = new File(gamesDir, name);
        if (!archive.isFile()) { writeJson(out, 404, error("فایل بازی پیدا نشد")); return; }
        File output = new File(context.getFilesDir(), "active-game");
        deleteTree(output);
        if (!output.mkdirs() && !output.isDirectory()) { writeJson(out, 500, error("ساخت پوشه بازی ناموفق بود")); return; }
        try {
            unzipSafely(archive, output);
            File index = findIndex(output, 0);
            if (index == null) { deleteTree(output); writeJson(out, 422, error("در بسته بازی فایل index.html پیدا نشد")); return; }
            synchronized (this) {
                currentGame = name;
                launchPath = index.getAbsolutePath();
            }
            JSONObject result = new JSONObject(); result.put("ok", true); result.put("name", name); writeJson(out, 200, result);
        } catch (Exception e) {
            deleteTree(output);
            writeJson(out, 422, error("فایل 123T معتبر نیست: " + e.getMessage()));
        }
    }

    private void unzipSafely(File archive, File destination) throws Exception {
        long total = 0;
        byte[] buffer = new byte[8192];
        try (ZipInputStream zip = new ZipInputStream(new FileInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                File out = new File(destination, entry.getName());
                String root = destination.getCanonicalPath() + File.separator;
                if (!out.getCanonicalPath().startsWith(root)) throw new SecurityException("مسیر ناامن داخل ZIP");
                if (entry.isDirectory()) { out.mkdirs(); continue; }
                File parent = out.getParentFile(); if (parent != null) parent.mkdirs();
                try (FileOutputStream fos = new FileOutputStream(out)) {
                    int count;
                    while ((count = zip.read(buffer)) > 0) {
                        total += count;
                        if (total > MAX_GAME_BYTES * 2) throw new SecurityException("حجم استخراج بیش از حد مجاز");
                        fos.write(buffer, 0, count);
                    }
                }
                zip.closeEntry();
            }
        }
    }

    private File findIndex(File dir, int depth) {
        if (depth > 5) return null;
        File direct = new File(dir, "index.html");
        if (direct.isFile()) return direct;
        File[] children = dir.listFiles();
        if (children == null) return null;
        for (File child : children) if (child.isDirectory()) {
            File found = findIndex(child, depth + 1);
            if (found != null) return found;
        }
        return null;
    }

    public synchronized String consumeLaunchPath() {
        String value = launchPath;
        launchPath = null;
        return value;
    }


    public void setMenuReady(boolean ready) { menuReady = ready; }

    public JSONObject getPublicState() {
        JSONObject result = new JSONObject();
        try {
            JSONArray list = new JSONArray();
            int active = 0, pending = 0;
            synchronized (players) {
                for (Player player : players.values()) {
                    list.put(playerToJson(player, false));
                    if ("active".equals(player.status)) active++;
                    if ("pending".equals(player.status)) pending++;
                }
            }
            result.put("ok", true); result.put("running", running.get());
            result.put("code", roomCode); result.put("ip", localIp);
            result.put("httpPort", HTTP_PORT); result.put("inputPort", INPUT_PORT);
            result.put("activeCount", active); result.put("pendingCount", pending);
            result.put("players", list); result.put("hasAdmin", !adminId.isEmpty());
            result.put("menuReady", menuReady); result.put("currentGame", currentGame); result.put("games", listGames());
        } catch (JSONException ignored) { }
        return result;
    }

    private JSONObject getPlayerState(String id, String token) {
        synchronized (players) {
            Player player = players.get(id);
            if (player == null) return error("بازیکن هنوز در اتاق ثبت نشده است");
            if (token == null || !player.sessionToken.equals(token)) return error("نشست دستگاه معتبر نیست");
            return playerToJson(player, true);
        }
    }

    private JSONObject playerToJson(Player p, boolean includeSecret) {
        JSONObject o = new JSONObject();
        try {
            o.put("id", p.id); o.put("name", p.name); o.put("status", p.status);
            o.put("playerNo", p.playerNo); o.put("admin", p.admin);
            if (includeSecret) o.put("sessionToken", p.sessionToken);
        } catch (JSONException ignored) { }
        return o;
    }

    private boolean isAdminSession(String id, String token) {
        synchronized (players) {
            Player player = players.get(id);
            return player != null && player.admin && id != null && id.equals(adminId) && token != null && player.sessionToken.equals(token);
        }
    }

    private String newSessionToken() { return java.util.UUID.randomUUID().toString().replace("-", "") + java.util.UUID.randomUUID().toString().replace("-", ""); }

    private JSONArray listGames() {
        JSONArray array = new JSONArray();
        File[] files = gamesDir.listFiles();
        if (files == null) return array;
        for (File file : files) if (file.isFile() && file.getName().toLowerCase().endsWith(".123t") && !file.getName().endsWith(".uploading")) array.put(file.getName());
        return array;
    }

    public JSONObject getInputSnapshot() {
        JSONObject root = new JSONObject();
        JSONArray array = new JSONArray();
        try {
            synchronized (players) {
                for (Player p : players.values()) {
                    if (!"active".equals(p.status)) continue;
                    JSONObject item = new JSONObject();
                    item.put("id", p.id); item.put("name", p.name); item.put("player", p.playerNo);
                    JSONObject input = (System.currentTimeMillis() - p.lastInputAt < 1200) ? p.input : new JSONObject();
                    item.put("input", input); array.put(item);
                    root.put("player" + p.playerNo, input);
                }
            }
            root.put("players", array); root.put("count", array.length());
        } catch (JSONException ignored) { }
        return root;
    }

    private static String readLine(InputStream in) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int previous = -1;
        while (true) {
            int b = in.read();
            if (b == -1) return bytes.size() == 0 ? null : bytes.toString("UTF-8");
            if (previous == '\r' && b == '\n') {
                byte[] data = bytes.toByteArray();
                int size = Math.max(0, data.length - 1);
                return new String(data, 0, size, StandardCharsets.UTF_8);
            }
            bytes.write(b); previous = b;
            if (bytes.size() > 16 * 1024) throw new IllegalArgumentException("HTTP header too large");
        }
    }

    private static byte[] readExactly(InputStream in, int length) throws Exception {
        if (length <= 0) return new byte[0];
        byte[] data = new byte[length]; int offset = 0;
        while (offset < length) {
            int read = in.read(data, offset, length - offset);
            if (read < 0) throw new IllegalStateException("بدنه درخواست ناقص است");
            offset += read;
        }
        return data;
    }

    private static void copyExactly(InputStream in, OutputStream out, int length) throws Exception {
        byte[] buffer = new byte[16 * 1024]; int remaining = length;
        while (remaining > 0) {
            int count = in.read(buffer, 0, Math.min(buffer.length, remaining));
            if (count < 0) throw new IllegalStateException("آپلود فایل ناقص بود");
            out.write(buffer, 0, count); remaining -= count;
        }
        out.flush();
    }

    private static void drain(InputStream in, int length) {
        try { int remaining = length; byte[] b = new byte[4096]; while (remaining > 0) { int n = in.read(b, 0, Math.min(b.length, remaining)); if (n < 0) break; remaining -= n; } } catch (Exception ignored) { }
    }

    private static void writeJson(OutputStream out, int status, JSONObject json) throws Exception {
        byte[] body = json.toString().getBytes(StandardCharsets.UTF_8);
        String label = status == 200 ? "OK" : status == 400 ? "Bad Request" : status == 403 ? "Forbidden" : status == 404 ? "Not Found" : status == 409 ? "Conflict" : status == 413 ? "Payload Too Large" : status == 422 ? "Unprocessable Entity" : "Internal Server Error";
        String headers = "HTTP/1.1 " + status + " " + label + "\r\n" +
                "Content-Type: application/json; charset=utf-8\r\n" +
                "Content-Length: " + body.length + "\r\n" +
                "Access-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n";
        out.write(headers.getBytes(StandardCharsets.US_ASCII)); out.write(body); out.flush();
    }

    private static JSONObject error(String message) {
        JSONObject o = new JSONObject();
        try { o.put("ok", false); o.put("error", message); } catch (JSONException ignored) { }
        return o;
    }

    private static Map<String, String> parseQuery(String target) {
        Map<String, String> result = new LinkedHashMap<>();
        int mark = target.indexOf('?'); if (mark < 0) return result;
        for (String pair : target.substring(mark + 1).split("&")) {
            String[] parts = pair.split("=", 2);
            try { result.put(URLDecoder.decode(parts[0], "UTF-8"), parts.length > 1 ? URLDecoder.decode(parts[1], "UTF-8") : ""); } catch (Exception ignored) { }
        }
        return result;
    }

    private static String safeFileName(String input) {
        if (input == null) return null;
        String name = input.replace('\\', '/');
        int slash = name.lastIndexOf('/'); if (slash >= 0) name = name.substring(slash + 1);
        name = name.replaceAll("[^A-Za-z0-9._ -]", "_").trim();
        if (name.isEmpty() || name.length() > 100 || name.equals(".") || name.equals("..")) return null;
        return name;
    }

    private static void deleteTree(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) { File[] children = file.listFiles(); if (children != null) for (File child : children) deleteTree(child); }
        file.delete();
    }

    public synchronized void stop() {
        if (!running.getAndSet(false)) return;
        try { if (serverSocket != null) serverSocket.close(); } catch (Exception ignored) { }
        try { if (discoverySocket != null) discoverySocket.close(); } catch (Exception ignored) { }
        try { if (inputSocket != null) inputSocket.close(); } catch (Exception ignored) { }
        try { if (multicastLock != null && multicastLock.isHeld()) multicastLock.release(); } catch (Exception ignored) { }
        synchronized (players) { players.clear(); }
    }

    private static final class Player {
        String id;
        String name;
        String sessionToken;
        String status = "pending";
        int playerNo;
        boolean admin;
        JSONObject input = new JSONObject();
        long lastInputAt;
    }
}
