package com.goldan.mbox;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class MainActivity extends Activity {
    private static final int REQUEST_LOCAL_NETWORK = 701;
    private static final int REQUEST_PICK_GAME = 702;
    private static final String LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK";

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ScheduledExecutorService ticker = Executors.newScheduledThreadPool(2);
    private FrameLayout root;
    private WebView webView;
    private TextView countdownOverlay;
    private LanHub hub;
    private LanClient client;
    private boolean displayGame = false;
    private boolean countdownPending = false;
    private boolean permissionActionIsDisplay = false;
    private String pendingCode = "";
    private String pendingName = "بازیکن";
    private String currentInput = "{}";
    private String lastControllerStatus = "";
    private boolean controllerConnected = false;

    private boolean isDisplay() { return "display".equals(BuildConfig.APP_ROLE); }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        if (isDisplay()) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);

        root = new FrameLayout(this);
        webView = new WebView(this);
        configureWebView();
        root.addView(webView, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        webView.addJavascriptInterface(new NativeBridge(), "MBox");
        webView.loadUrl("file:///android_asset/" + (isDisplay() ? "display.html" : "controller.html"));

        if (isDisplay()) {
            startDisplayTicker();
        } else {
            String id = getSharedPreferences("mbox", MODE_PRIVATE).getString("device_id", null);
            if (id == null) {
                id = UUID.randomUUID().toString();
                getSharedPreferences("mbox", MODE_PRIVATE).edit().putString("device_id", id).apply();
            }
            client = new LanClient(this, id);
            startControllerTicker();
        }
    }

    private void configureWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(false);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        webView.setBackgroundColor(android.graphics.Color.rgb(7, 13, 35));
        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (displayGame && countdownPending) {
                    countdownPending = false;
                    showCountdown();
                }
            }
        });
    }

    private boolean hasLocalNetworkPermission() {
        return Build.VERSION.SDK_INT < 37 || checkSelfPermission(LOCAL_NETWORK_PERMISSION) == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    private void requestNetworkPermission(boolean forDisplay) {
        permissionActionIsDisplay = forDisplay;
        if (Build.VERSION.SDK_INT >= 37 && !hasLocalNetworkPermission()) {
            requestPermissions(new String[]{LOCAL_NETWORK_PERMISSION}, REQUEST_LOCAL_NETWORK);
        } else {
            continueAfterPermission();
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_LOCAL_NETWORK) {
            if (hasLocalNetworkPermission()) continueAfterPermission();
            else evaluate("window.onPermissionResult && window.onPermissionResult(false, 'دسترسی شبکه محلی داده نشد. از تنظیمات برنامه آن را فعال کنید.');");
        }
    }

    private void continueAfterPermission() {
        if (permissionActionIsDisplay) startDisplayHost();
        else connectController();
    }

    private void startDisplayHost() {
        try {
            if (hub == null) hub = new LanHub(this);
            hub.start();
            evaluate("window.onDisplayStarted && window.onDisplayStarted(" + hub.getPublicState().toString() + ");");
        } catch (Exception e) {
            evaluate("window.onDisplayError && window.onDisplayError(" + JSONObject.quote("راه‌اندازی سرور محلی انجام نشد: " + e.getMessage()) + ");");
        }
    }

    private void connectController() {
        if (client == null) return;
        evaluate("window.onConnecting && window.onConnecting();");
        client.connect(pendingCode, pendingName, (ok, message, data) -> runOnUiThread(() -> {
            if (!ok) {
                evaluate("window.onConnectResult && window.onConnectResult(false," + JSONObject.quote(message) + ",null);");
                return;
            }
            JSONObject snapshot = client.getSnapshot();
            evaluate("window.onConnectResult && window.onConnectResult(true," + JSONObject.quote(message) + "," + snapshot.toString() + ");");
            applyControllerOrientation(snapshot);
        }));
    }

    private void applyControllerOrientation(JSONObject snapshot) {
        String status = snapshot.optString("status", "");
        if ("active".equals(status) && !controllerConnected) {
            controllerConnected = true;
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        }
    }

    private void startDisplayTicker() {
        ticker.scheduleAtFixedRate(() -> {
            if (hub != null && hub.isRunning()) {
                String path = hub.consumeLaunchPath();
                if (path != null) runOnUiThread(() -> launchGame(path));
            }
        }, 250, 250, TimeUnit.MILLISECONDS);

        ticker.scheduleAtFixedRate(() -> {
            if (displayGame && hub != null && hub.isRunning()) {
                String json = hub.getInputSnapshot().toString();
                runOnUiThread(() -> {
                    if (displayGame && webView != null) {
                        String script = "window.MBOX_INPUT=" + json + ";window.dispatchEvent(new CustomEvent('mboxinput',{detail:window.MBOX_INPUT}));";
                        webView.evaluateJavascript(script, null);
                    }
                });
            }
        }, 0, 33, TimeUnit.MILLISECONDS);
    }

    private void startControllerTicker() {
        ticker.scheduleAtFixedRate(() -> {
            if (client == null) return;
            client.poll();
            JSONObject snapshot = client.getSnapshot();
            String status = snapshot.optString("status", "");
            if ("active".equals(status)) {
                applyControllerOrientation(snapshot);
            }
            lastControllerStatus = status;
            runOnUiThread(() -> evaluate("window.onControllerState && window.onControllerState(" + snapshot.toString() + ");"));
        }, 500, 900, TimeUnit.MILLISECONDS);

        ticker.scheduleAtFixedRate(() -> {
            if (client != null && controllerConnected) client.sendInput(currentInput);
        }, 0, 33, TimeUnit.MILLISECONDS);
    }

    private void launchGame(String path) {
        if (path == null || !new File(path).isFile()) return;
        displayGame = true;
        countdownPending = true;
        webView.loadUrl(Uri.fromFile(new File(path)).toString());
    }

    private void showCountdown() {
        if (countdownOverlay != null) root.removeView(countdownOverlay);
        countdownOverlay = new TextView(this);
        countdownOverlay.setTextColor(android.graphics.Color.WHITE);
        countdownOverlay.setTextSize(74);
        countdownOverlay.setGravity(Gravity.CENTER);
        countdownOverlay.setTypeface(android.graphics.Typeface.create("sans-serif-black", android.graphics.Typeface.BOLD));
        countdownOverlay.setBackgroundColor(0xB30A1235);
        root.addView(countdownOverlay, new FrameLayout.LayoutParams(-1, -1));
        final int[] value = {5};
        Runnable tick = new Runnable() {
            @Override public void run() {
                if (countdownOverlay == null || !displayGame) return;
                if (value[0] > 0) {
                    countdownOverlay.setText(String.valueOf(value[0]));
                    value[0]--;
                    mainHandler.postDelayed(this, 1000);
                } else {
                    countdownOverlay.setText("شروع!");
                    mainHandler.postDelayed(() -> {
                        if (countdownOverlay != null) { root.removeView(countdownOverlay); countdownOverlay = null; }
                        evaluate("window.dispatchEvent(new Event('mboxstart'));");
                    }, 500);
                }
            }
        };
        tick.run();
    }

    private void exitGame() {
        displayGame = false;
        countdownPending = false;
        if (countdownOverlay != null) { root.removeView(countdownOverlay); countdownOverlay = null; }
        webView.loadUrl("file:///android_asset/display.html");
        webView.postDelayed(() -> {
            if (hub != null && hub.isRunning()) evaluate("window.onDisplayStarted && window.onDisplayStarted(" + hub.getPublicState().toString() + ");");
        }, 500);
    }

    private void evaluate(String javascript) {
        if (webView != null) webView.evaluateJavascript(javascript, null);
    }

    private void chooseGameFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/zip", "application/octet-stream"});
        startActivityForResult(intent, REQUEST_PICK_GAME);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_PICK_GAME || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        String name = displayName(uri);
        if (name == null || !name.toLowerCase().endsWith(".123t")) {
            evaluate("window.onActionResult && window.onActionResult('upload',false,'فقط فایل با پسوند .123T را انتخاب کنید');");
            return;
        }
        new Thread(() -> {
            File copy = null;
            try {
                copy = new File(getCacheDir(), "mbox-upload-" + System.currentTimeMillis() + ".123t");
                try (InputStream in = getContentResolver().openInputStream(uri); FileOutputStream out = new FileOutputStream(copy)) {
                    if (in == null) throw new IllegalStateException("فایل قابل خواندن نیست");
                    byte[] buffer = new byte[32 * 1024]; int count; long total = 0;
                    while ((count = in.read(buffer)) != -1) {
                        total += count;
                        if (total > 100L * 1024L * 1024L) throw new IllegalStateException("حداکثر حجم فایل ۱۰۰ مگابایت است");
                        out.write(buffer, 0, count);
                    }
                }
                File finalCopy = copy;
                client.uploadGame(finalCopy, (ok, message, result) -> runOnUiThread(() -> {
                    evaluate("window.onActionResult && window.onActionResult('upload'," + ok + "," + JSONObject.quote(message) + ");");
                    if (finalCopy.exists()) finalCopy.delete();
                }));
            } catch (Exception e) {
                if (copy != null) copy.delete();
                runOnUiThread(() -> evaluate("window.onActionResult && window.onActionResult('upload',false," + JSONObject.quote("خواندن فایل ناموفق بود: " + e.getMessage()) + ");"));
            }
        }).start();
    }

    private String displayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) return cursor.getString(index);
            }
        } catch (Exception ignored) { }
        String last = uri.getLastPathSegment();
        return last == null ? null : last;
    }

    private final class NativeBridge {
        @JavascriptInterface public void startDisplay() { runOnUiThread(() -> requestNetworkPermission(true)); }

        @JavascriptInterface public String getDisplayState() {
            return hub == null ? "{}" : hub.getPublicState().toString();
        }

        @JavascriptInterface public void connect(String code, String name) {
            pendingCode = code == null ? "" : code.trim();
            pendingName = name == null ? "بازیکن" : name;
            runOnUiThread(() -> requestNetworkPermission(false));
        }

        @JavascriptInterface public void setInput(String json) { currentInput = json == null ? "{}" : json; }
        @JavascriptInterface public String getControllerState() { return client == null ? "{}" : client.getSnapshot().toString(); }
        @JavascriptInterface public void pickGame() { runOnUiThread(MainActivity.this::chooseGameFile); }
        @JavascriptInterface public void approve(String playerId) {
            if (client != null) client.approve(playerId, (ok, message, data) -> runOnUiThread(() -> evaluate("window.onActionResult && window.onActionResult('approve'," + ok + "," + JSONObject.quote(message) + ");")));
        }
        @JavascriptInterface public void reject(String playerId) {
            if (client != null) client.reject(playerId, (ok, message, data) -> runOnUiThread(() -> evaluate("window.onActionResult && window.onActionResult('reject'," + ok + "," + JSONObject.quote(message) + ");")));
        }
        @JavascriptInterface public void startGame(String name) {
            if (client != null) client.startGame(name, (ok, message, data) -> runOnUiThread(() -> evaluate("window.onActionResult && window.onActionResult('start'," + ok + "," + JSONObject.quote(message) + ");")));
        }
        @JavascriptInterface public void continueToMenu() { if (hub != null) hub.setMenuReady(true); }
        @JavascriptInterface public void returnToRoom() { if (hub != null) hub.setMenuReady(false); }
        @JavascriptInterface public void exitGame() { runOnUiThread(MainActivity.this::exitGame); }
    }

    @Override public void onBackPressed() {
        if (isDisplay() && displayGame) { exitGame(); return; }
        if (!isDisplay() && controllerConnected) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
            controllerConnected = false;
            evaluate("window.onControllerDisconnected && window.onControllerDisconnected();");
            return;
        }
        super.onBackPressed();
    }

    @Override protected void onDestroy() {
        ticker.shutdownNow();
        if (hub != null) hub.stop();
        if (client != null) client.close();
        if (webView != null) { webView.removeJavascriptInterface("MBox"); webView.destroy(); }
        super.onDestroy();
    }
}
