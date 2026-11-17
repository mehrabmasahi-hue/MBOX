package com.goldan.mbox;

import android.content.Context;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Client transport for the Controller APK. HTTP is used for room actions; UDP carries game input. */
public final class LanClient {
    public interface Callback { void done(boolean ok, String message, JSONObject data); }

    private final Context context;
    private final String deviceId;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile String serverIp = "";
    private volatile String roomCode = "";
    private volatile String playerName = "بازیکن";
    private volatile String sessionToken = "";
    private volatile JSONObject playerState = new JSONObject();
    private volatile JSONObject serverState = new JSONObject();
    private volatile JSONObject input = new JSONObject();
    private DatagramSocket inputSocket;
    private volatile boolean closed = false;

    public LanClient(Context context, String deviceId) {
        this.context = context.getApplicationContext();
        this.deviceId = deviceId;
        this.sessionToken = this.context.getSharedPreferences("mbox", Context.MODE_PRIVATE).getString("session_" + deviceId, "");
    }

    public String getDeviceId() { return deviceId; }

    public void connect(String code, String name, Callback callback) {
        roomCode = code == null ? "" : code.trim();
        playerName = (name == null || name.trim().isEmpty()) ? "بازیکن" : name.trim();
        worker.execute(() -> {
            try {
                String foundIp = discover(roomCode);
                if (foundIp == null) { callback.done(false, "نمایشگری با این کد در شبکه پیدا نشد. اتصال Wi-Fi و کد را بررسی کنید.", null); return; }
                serverIp = foundIp;
                if (inputSocket == null || inputSocket.isClosed()) inputSocket = new DatagramSocket();
                JSONObject body = new JSONObject();
                body.put("code", roomCode); body.put("id", deviceId); body.put("name", playerName); body.put("token", sessionToken);
                JSONObject joined = requestJson("POST", "/api/join", body);
                if (!joined.optBoolean("ok", true) && joined.has("error")) {
                    callback.done(false, joined.optString("error", "اتصال انجام نشد"), joined); return;
                }
                playerState = joined;
                sessionToken = joined.optString("sessionToken", sessionToken);
                if (!sessionToken.isEmpty()) context.getSharedPreferences("mbox", Context.MODE_PRIVATE).edit().putString("session_" + deviceId, sessionToken).apply();
                refreshStateInternal();
                callback.done(true, statusMessage(joined), getSnapshot());
            } catch (Exception e) {
                callback.done(false, "اتصال ناموفق بود: " + readableError(e), null);
            }
        });
    }

    private String discover(String code) throws Exception {
        DatagramSocket socket = new DatagramSocket();
        try {
            socket.setBroadcast(true);
            socket.setSoTimeout(1800);
            byte[] bytes = ("MBOX_DISCOVER|" + code).getBytes(StandardCharsets.UTF_8);
            sendBroadcast(socket, bytes, InetAddress.getByName("255.255.255.255"));
            try {
                Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
                while (interfaces != null && interfaces.hasMoreElements()) {
                    NetworkInterface ni = interfaces.nextElement();
                    try {
                        if (!ni.isUp() || ni.isLoopback()) continue;
                        for (java.net.InterfaceAddress ia : ni.getInterfaceAddresses()) {
                            InetAddress broadcast = ia.getBroadcast();
                            if (broadcast != null) sendBroadcast(socket, bytes, broadcast);
                        }
                    } catch (Exception ignored) { }
                }
            } catch (Exception ignored) { }
            long end = System.currentTimeMillis() + 4000;
            byte[] buffer = new byte[512];
            while (System.currentTimeMillis() < end) {
                try {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    socket.setSoTimeout((int) Math.max(300, end - System.currentTimeMillis()));
                    socket.receive(packet);
                    String response = new String(packet.getData(), packet.getOffset(), packet.getLength(), StandardCharsets.UTF_8);
                    String[] parts = response.split("\\|");
                    if (parts.length >= 4 && "MBOX_FOUND".equals(parts[0])) {
                        String ip = parts[1];
                        if (ip.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) return ip;
                        return packet.getAddress().getHostAddress();
                    }
                } catch (java.net.SocketTimeoutException timeout) { break; }
            }
            return null;
        } finally { socket.close(); }
    }

    private void sendBroadcast(DatagramSocket socket, byte[] bytes, InetAddress address) {
        try { socket.send(new DatagramPacket(bytes, bytes.length, address, LanHub.DISCOVERY_PORT)); } catch (Exception ignored) { }
    }

    public void poll() {
        if (serverIp.isEmpty() || closed) return;
        worker.execute(() -> {
            try { refreshStateInternal(); } catch (Exception e) {
                android.util.Log.w("MBOX", "Polling failed: " + e.getMessage());
            }
        });
    }

    private void refreshStateInternal() throws Exception {
        if (serverIp.isEmpty()) return;
        JSONObject me = requestJson("GET", "/api/me?id=" + encode(deviceId) + "&token=" + encode(sessionToken), null);
        JSONObject state = requestJson("GET", "/api/state", null);
        playerState = me; serverState = state;
    }

    public void sendInput(String json) {
        if (serverIp.isEmpty() || inputSocket == null || inputSocket.isClosed() || closed) return;
        try {
            JSONObject parsed = new JSONObject(json == null || json.isEmpty() ? "{}" : json);
            input = parsed;
            String message = "MBOX_INPUT|" + roomCode + "|" + deviceId + "|" + sessionToken + "|" + parsed.toString();
            byte[] data = message.getBytes(StandardCharsets.UTF_8);
            inputSocket.send(new DatagramPacket(data, data.length, InetAddress.getByName(serverIp), LanHub.INPUT_PORT));
        } catch (Exception ignored) { }
    }

    public JSONObject getSnapshot() {
        JSONObject result = new JSONObject();
        try {
            JSONObject me = playerState;
            result.put("connected", !serverIp.isEmpty());
            result.put("ip", serverIp); result.put("code", roomCode);
            result.put("id", deviceId);
            result.put("status", me.optString("status", "disconnected"));
            result.put("playerNo", me.optInt("playerNo", 0));
            result.put("admin", me.optBoolean("admin", false));
            result.put("name", me.optString("name", playerName));
            result.put("error", me.optString("error", ""));
            result.put("room", serverState);
        } catch (Exception ignored) { }
        return result;
    }

    public void approve(String playerId, Callback callback) { managerAction("/api/approve", playerId, callback); }
    public void reject(String playerId, Callback callback) { managerAction("/api/reject", playerId, callback); }

    private void managerAction(String path, String playerId, Callback callback) {
        worker.execute(() -> {
            try {
                JSONObject body = new JSONObject(); body.put("managerId", deviceId); body.put("managerToken", sessionToken); body.put("playerId", playerId);
                JSONObject result = requestJson("POST", path, body);
                refreshStateInternal();
                callback.done(result.optBoolean("ok", true), result.optString("error", "انجام شد"), result);
            } catch (Exception e) { callback.done(false, readableError(e), null); }
        });
    }

    public void startGame(String name, Callback callback) {
        worker.execute(() -> {
            try {
                JSONObject body = new JSONObject(); body.put("managerId", deviceId); body.put("managerToken", sessionToken); body.put("name", name);
                JSONObject result = requestJson("POST", "/api/game/start", body);
                callback.done(result.optBoolean("ok", false), result.optString("error", "درخواست اجرا ارسال شد"), result);
            } catch (Exception e) { callback.done(false, readableError(e), null); }
        });
    }

    public void uploadGame(File file, Callback callback) {
        worker.execute(() -> {
            HttpURLConnection connection = null;
            try {
                String url = "http://" + serverIp + ":" + LanHub.HTTP_PORT + "/api/game/upload?id=" + encode(deviceId) + "&token=" + encode(sessionToken) + "&name=" + encode(file.getName());
                connection = (HttpURLConnection) new URL(url).openConnection();
                connection.setRequestMethod("POST"); connection.setConnectTimeout(5000); connection.setReadTimeout(15000);
                connection.setDoOutput(true); connection.setRequestProperty("Content-Type", "application/octet-stream");
                connection.setFixedLengthStreamingMode(file.length());
                try (InputStream in = new BufferedInputStream(new FileInputStream(file)); OutputStream out = new BufferedOutputStream(connection.getOutputStream())) {
                    byte[] buffer = new byte[32 * 1024]; int count;
                    while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
                }
                int status = connection.getResponseCode();
                JSONObject result = readResponse(connection, status);
                if (status >= 200 && status < 300) {
                    refreshStateInternal(); callback.done(true, "بازی بارگذاری شد", result);
                } else callback.done(false, result.optString("error", "آپلود ناموفق بود"), result);
            } catch (Exception e) { callback.done(false, "آپلود انجام نشد: " + readableError(e), null); }
            finally { if (connection != null) connection.disconnect(); }
        });
    }

    private JSONObject requestJson(String method, String path, JSONObject body) throws Exception {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL("http://" + serverIp + ":" + LanHub.HTTP_PORT + path).openConnection();
            connection.setRequestMethod(method); connection.setConnectTimeout(3500); connection.setReadTimeout(5000);
            connection.setRequestProperty("Accept", "application/json");
            if (body != null) {
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                connection.setDoOutput(true); connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                connection.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream out = connection.getOutputStream()) { out.write(bytes); }
            }
            int status = connection.getResponseCode();
            return readResponse(connection, status);
        } finally { if (connection != null) connection.disconnect(); }
    }

    private JSONObject readResponse(HttpURLConnection connection, int status) throws Exception {
        InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        if (stream == null) return new JSONObject();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (InputStream in = stream) { byte[] buffer = new byte[4096]; int count; while ((count = in.read(buffer)) != -1) bytes.write(buffer, 0, count); }
        JSONObject result = new JSONObject(bytes.toString("UTF-8"));
        if (status < 200 || status >= 300) result.put("ok", false);
        return result;
    }

    private static String encode(String value) throws Exception { return URLEncoder.encode(value == null ? "" : value, "UTF-8"); }

    private String statusMessage(JSONObject data) {
        String status = data.optString("status", "pending");
        if ("active".equals(status)) return data.optBoolean("admin", false) ? "دسته مدیر فعال شد" : "دسته متصل شد";
        if ("pending".equals(status)) return "درخواست ورود برای مدیر ارسال شد";
        return data.optString("error", "در انتظار تأیید");
    }

    private String readableError(Exception e) {
        String m = e.getMessage();
        if (m == null || m.isEmpty()) return e.getClass().getSimpleName();
        return m;
    }

    public void close() {
        closed = true;
        try { if (inputSocket != null) inputSocket.close(); } catch (Exception ignored) { }
        worker.shutdownNow();
    }
}
