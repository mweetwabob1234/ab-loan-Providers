package zm.ablender.loanbook;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.util.Base64;
import android.util.Log;
import android.view.KeyEvent;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Single-screen shell. The dashboard interface is index.html, edited in
 * app/src/main/assets and mirrored automatically to docs/ on every push
 * (see .github/workflows/build-apk.yml) so it can be served live from
 * GitHub Pages. On launch the WebView loads that live page first — so a
 * plain HTML/CSS/JS change reaches the app within minutes with no new APK
 * build or install — falling back to the bundled asset copy for offline
 * use or if the live page can't be reached.
 *
 * Also runs a lightweight self-updater: on launch it checks the GitHub
 * repo's latest Release for a newer versionCode, and if found offers to
 * download and install it in place (see checkForUpdate() below). That path
 * is now only needed for native/Java changes, not everyday dashboard tweaks.
 */
public class MainActivity extends Activity {

    private static final String TAG = "ABLoanBook";
    private static final String RELEASES_API =
            "https://api.github.com/repos/mweetwabob1234/ab-loan-Providers/releases/latest";
    private static final String REMOTE_DASHBOARD_URL =
            "https://mweetwabob1234.github.io/ab-loan-Providers/index.html";
    private static final String LOCAL_DASHBOARD_URL = "file:///android_asset/index.html";
    private static final long REMOTE_DASHBOARD_TIMEOUT_MS = 6000;
    private static final String PREFS = "ablb_prefs";
    private static final String KEY_SKIPPED_VERSION = "skipped_version";
    private static final String KEY_ASKED_ALARM_PERM = "asked_alarm_permission";
    private static final String UPDATE_FILE_NAME = "ablb-update.apk";

    static final String CHANNEL_ID = "loan_due_reminders";
    private static final int REQUEST_NOTIFICATIONS = 2001;
    private static final long UPDATE_CHECK_MIN_INTERVAL_MS = 60_000;
    private static final long SPLASH_MS = 1300;

    private WebView web;
    private long downloadId = -1;
    private String pendingApkUrl;
    private long lastUpdateCheckAt = 0;
    private final android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private boolean awaitingRemoteDashboard = false;
    private final Runnable remoteDashboardTimeout = this::fallBackToLocalDashboard;

    private final BroadcastReceiver downloadReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
            if (id != -1 && id == downloadId) {
                installDownloadedApk();
            }
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        int mode = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        int bg = mode == Configuration.UI_MODE_NIGHT_YES ? 0xFF0F1115 : 0xFFF3F4F6;

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setLoadWithOverviewMode(false);
        s.setUseWideViewPort(false);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                super.onReceivedError(view, request, error);
                if (request.isForMainFrame()) fallBackToLocalDashboard();
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                awaitingRemoteDashboard = false;
                mainHandler.removeCallbacks(remoteDashboardTimeout);
            }
        });
        web.setOverScrollMode(WebView.OVER_SCROLL_NEVER);
        // Match the WebView background to the theme so there is no white flash.
        web.setBackgroundColor(bg);
        // Fetches the loan-register CSV natively instead of via the WebView's
        // own fetch() -- some WebView/Chromium builds refuse a cross-origin
        // request from a file:// page even with Access-Control-Allow-Origin:
        // *, and native HttpURLConnection has no concept of CORS at all, so
        // this sidesteps that class of failure entirely. See index.html's
        // syncFromSheet(), which prefers this bridge when present.
        web.addJavascriptInterface(new SheetSyncBridge(), "AndroidBridge");
        // Local dashboard data (theme, on-device edits/deletes) is bridged
        // through SharedPreferences instead of the WebView's own
        // localStorage, which is scoped per page origin -- and the
        // dashboard can now load from more than one origin (see
        // loadDashboard()). This keeps that data consistent regardless of
        // which origin last rendered the page.
        web.addJavascriptInterface(new NativeStore(), "AndroidStore");
        loadDashboard();

        // A brief splash (app icon + spinner) while checkForUpdate() below runs,
        // so the update check always gets a moment to complete before the
        // dashboard is shown -- rather than racing it silently in the background.
        showSplash(bg);
        new android.os.Handler(android.os.Looper.getMainLooper())
                .postDelayed(this::showDashboard, SPLASH_MS);

        IntentFilter filter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(downloadReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(downloadReceiver, filter);
        }

        lastUpdateCheckAt = System.currentTimeMillis();
        checkForUpdate();
        setupDueReminders();
    }

    private void showSplash(int bg){
        android.widget.LinearLayout splash = new android.widget.LinearLayout(this);
        splash.setOrientation(android.widget.LinearLayout.VERTICAL);
        splash.setGravity(android.view.Gravity.CENTER);
        splash.setBackgroundColor(bg);

        android.widget.ImageView icon = new android.widget.ImageView(this);
        icon.setImageResource(R.drawable.ic_launcher);
        int iconSize = (int) (72 * getResources().getDisplayMetrics().density);
        android.widget.LinearLayout.LayoutParams iconParams =
                new android.widget.LinearLayout.LayoutParams(iconSize, iconSize);
        iconParams.bottomMargin = (int) (20 * getResources().getDisplayMetrics().density);
        splash.addView(icon, iconParams);

        android.widget.ProgressBar spinner = new android.widget.ProgressBar(this);
        splash.addView(spinner);

        setContentView(splash);
    }

    private void showDashboard(){
        if (isFinishing() || web == null) return;
        if (web.getParent() == null) setContentView(web);
    }

    /** Tries the live GitHub Pages copy of the dashboard first (so ordinary
     *  index.html edits show up without a new APK), falling back to the
     *  bundled asset if there's no network or the page can't be reached
     *  within REMOTE_DASHBOARD_TIMEOUT_MS. */
    private void loadDashboard() {
        if (!hasActiveNetwork()) {
            web.loadUrl(LOCAL_DASHBOARD_URL);
            return;
        }
        awaitingRemoteDashboard = true;
        web.loadUrl(REMOTE_DASHBOARD_URL);
        mainHandler.postDelayed(remoteDashboardTimeout, REMOTE_DASHBOARD_TIMEOUT_MS);
    }

    private void fallBackToLocalDashboard() {
        if (!awaitingRemoteDashboard || web == null) return;
        awaitingRemoteDashboard = false;
        mainHandler.removeCallbacks(remoteDashboardTimeout);
        web.stopLoading();
        web.loadUrl(LOCAL_DASHBOARD_URL);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Returning from the "allow installs from this app" Settings screen —
        // resume the update the user asked for instead of making them tap twice.
        if (pendingApkUrl != null && canInstallPackages()) {
            String url = pendingApkUrl;
            pendingApkUrl = null;
            enqueueDownload(url);
        }
        // Also covers returning from the "Alarms & reminders" Settings screen.
        if (ReminderScheduler.canScheduleExact(this)) {
            ReminderScheduler.scheduleNext(this);
        }
        // Re-check for updates every time the app comes to the foreground, not
        // just on a cold start — Android keeps the app alive in the background
        // (e.g. after pressing Home), so onCreate alone would miss a new release
        // until the app is fully closed and relaunched. Throttled so rapidly
        // switching in and out doesn't spam the GitHub API.
        long now = System.currentTimeMillis();
        if (now - lastUpdateCheckAt > UPDATE_CHECK_MIN_INTERVAL_MS) {
            lastUpdateCheckAt = now;
            checkForUpdate();
        }
    }

    @Override
    protected void onDestroy() {
        try { unregisterReceiver(downloadReceiver); } catch (IllegalArgumentException ignored) {}
        super.onDestroy();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && web.canGoBack()) {
            web.goBack();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    // ---- Self-update: check GitHub Releases for a newer build ----

    private boolean hasActiveNetwork() {
        try {
            android.net.ConnectivityManager cm =
                    (android.net.ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return true; // can't tell, don't block on it
            android.net.Network net = cm.getActiveNetwork();
            if (net == null) return false;
            android.net.NetworkCapabilities caps = cm.getNetworkCapabilities(net);
            return caps != null && caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET);
        } catch (Exception e) {
            return true; // can't tell, don't block on it
        }
    }

    private void checkForUpdate() {
        if (!hasActiveNetwork()) {
            reportUpdateCheckFailure("device reports no active network connection");
            return;
        }
        new Thread(() -> {
            try {
                URL url = new URL(RELEASES_API);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestProperty("Accept", "application/vnd.github+json");
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                int code = conn.getResponseCode();
                if (code != 200) {
                    reportUpdateCheckFailure("HTTP " + code + " from GitHub");
                    return;
                }

                StringBuilder sb = new StringBuilder();
                BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                br.close();

                JSONObject release = new JSONObject(sb.toString());
                String tag = release.optString("tag_name", "");
                int remoteVersion = 0;
                if (tag.startsWith("v")) {
                    try { remoteVersion = Integer.parseInt(tag.substring(1)); } catch (NumberFormatException ignored) {}
                }
                // Only ever offer a strictly newer build than what is installed.
                if (remoteVersion <= BuildConfig.VERSION_CODE) return;

                SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
                if (remoteVersion <= prefs.getInt(KEY_SKIPPED_VERSION, 0)) return;

                String apkUrl = null;
                JSONArray assets = release.optJSONArray("assets");
                if (assets != null) {
                    for (int i = 0; i < assets.length(); i++) {
                        JSONObject asset = assets.getJSONObject(i);
                        String name = asset.optString("name", "");
                        if (name.toLowerCase().endsWith(".apk")) {
                            apkUrl = asset.optString("browser_download_url");
                            break;
                        }
                    }
                }
                if (apkUrl == null) return;

                String label = release.optString("name", tag);
                String finalApkUrl = apkUrl;
                int finalRemoteVersion = remoteVersion;
                runOnUiThread(() -> showUpdateDialog(finalApkUrl, label, finalRemoteVersion));
            } catch (Exception e) {
                Log.w(TAG, "Update check failed", e);
                String detail = e.getClass().getSimpleName() + (e.getMessage() != null ? ": " + e.getMessage() : "");
                reportUpdateCheckFailure(detail);
            }
        }).start();
    }

    private boolean updateCheckFailureShown = false;

    /** Surfaces the real reason once per app session, so a persistent failure
     *  is diagnosable instead of silently never offering an update. */
    private void reportUpdateCheckFailure(String detail) {
        Log.w(TAG, "Update check could not complete: " + detail);
        if (updateCheckFailureShown) return;
        updateCheckFailureShown = true;
        runOnUiThread(() -> {
            if (!isFinishing()) {
                Toast.makeText(this, "Couldn't check for app updates: " + detail, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void showUpdateDialog(String apkUrl, String versionLabel, int remoteVersion) {
        if (isFinishing()) return;
        new AlertDialog.Builder(this)
                .setTitle("Update available")
                .setMessage("A new version is available (" + versionLabel + "). Update now?")
                .setCancelable(true)
                .setPositiveButton("Update", (d, w) -> startUpdateDownload(apkUrl))
                .setNegativeButton("Later", (d, w) -> {
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                            .putInt(KEY_SKIPPED_VERSION, remoteVersion).apply();
                    d.dismiss();
                })
                .show();
    }

    private boolean canInstallPackages() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.O || getPackageManager().canRequestPackageInstalls();
    }

    private void startUpdateDownload(String apkUrl) {
        if (!canInstallPackages()) {
            pendingApkUrl = apkUrl;
            Toast.makeText(this, "Allow installs from this app, then come back — the update will continue automatically.", Toast.LENGTH_LONG).show();
            Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + getPackageName()));
            startActivity(intent);
            return;
        }
        enqueueDownload(apkUrl);
    }

    private void enqueueDownload(String apkUrl) {
        Toast.makeText(this, "Downloading update…", Toast.LENGTH_SHORT).show();
        File dest = new File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), UPDATE_FILE_NAME);
        if (dest.exists()) dest.delete();

        DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
        DownloadManager.Request req = new DownloadManager.Request(Uri.parse(apkUrl));
        req.setTitle("A&B Loan Book update");
        req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        req.setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, UPDATE_FILE_NAME);
        downloadId = dm.enqueue(req);
    }

    private void installDownloadedApk() {
        File file = new File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), UPDATE_FILE_NAME);
        if (!file.exists()) {
            Toast.makeText(this, "Update download failed — try again later.", Toast.LENGTH_LONG).show();
            return;
        }
        Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file);
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, "application/vnd.android.package-archive");
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(intent);
    }

    // ---- Native bridge: fetches the loan-register CSV for the WebView ----
    // (bypasses the WebView's own CORS-enforced fetch(); see the addJavascriptInterface
    // call in onCreate() for why.)

    private class SheetSyncBridge {
        @JavascriptInterface
        public void requestSync() {
            new Thread(() -> {
                String csvB64 = null;
                String error = null;
                try {
                    URL url = new URL(LoanSync.SHEET_CSV_URL);
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setConnectTimeout(10000);
                    conn.setReadTimeout(10000);
                    int code = conn.getResponseCode();
                    if (code == 200) {
                        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
                        java.io.InputStream in = conn.getInputStream();
                        byte[] chunk = new byte[4096];
                        int n;
                        while ((n = in.read(chunk)) != -1) buf.write(chunk, 0, n);
                        in.close();
                        csvB64 = Base64.encodeToString(buf.toByteArray(), Base64.NO_WRAP);
                    } else {
                        error = "HTTP " + code + " from Google Sheets";
                    }
                } catch (Exception e) {
                    error = e.getClass().getSimpleName() + (e.getMessage() != null ? ": " + e.getMessage() : "");
                }

                String jsArg1 = csvB64 != null ? JSONObject.quote(csvB64) : "null";
                String jsArg2 = error != null ? JSONObject.quote(error) : "null";
                String js = "window.onNativeSyncResult && window.onNativeSyncResult(" + jsArg1 + "," + jsArg2 + ")";
                runOnUiThread(() -> { if (web != null) web.evaluateJavascript(js, null); });
            }).start();
        }
    }

    // ---- Native bridge: origin-independent storage for dashboard-local data ----
    // (theme choice, on-device loan edits/deletes -- see window.__storeGet/__storeSet
    // in index.html.)

    private class NativeStore {
        @JavascriptInterface
        public String get(String key) {
            return getSharedPreferences(PREFS, MODE_PRIVATE).getString(key, null);
        }

        @JavascriptInterface
        public void set(String key, String value) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(key, value).apply();
        }
    }

    // ---- Due-date reminders: a 10am notification for each loan due that day ----

    private void setupDueReminders() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Loan due reminders", NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription("Alerts when a borrower's loan is due that day.");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS);
        }

        if (ReminderScheduler.canScheduleExact(this)) {
            ReminderScheduler.scheduleNext(this);
            return;
        }
        // Ask for "Alarms & reminders" access once — not on every launch, so a
        // user who declines isn't bounced to Settings every time they open the app.
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (!prefs.getBoolean(KEY_ASKED_ALARM_PERM, false)) {
            prefs.edit().putBoolean(KEY_ASKED_ALARM_PERM, true).apply();
            Toast.makeText(this, "Allow \"Alarms & reminders\" so due-date notifications fire on time.", Toast.LENGTH_LONG).show();
            try {
                startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception ignored) {}
        }
    }
}
