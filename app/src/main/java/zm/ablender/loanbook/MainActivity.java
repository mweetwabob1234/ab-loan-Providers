package zm.ablender.loanbook;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
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
import java.io.FileOutputStream;
import java.io.InputStream;
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
    private static final String KEY_ASKED_ALARM_PERM = "asked_alarm_permission";
    private static final String UPDATE_FILE_NAME = "ablb-update.apk";

    static final String CHANNEL_ID = "loan_due_reminders";
    private static final int REQUEST_NOTIFICATIONS = 2001;
    private static final long UPDATE_CHECK_MIN_INTERVAL_MS = 60_000;
    // The splash never dismisses before SPLASH_MIN_MS (so it doesn't just
    // flash on a fast connection) or after SPLASH_MAX_MS (so it can never
    // hang forever) -- in between, it waits for the dashboard to actually
    // finish loading. See showDashboard()/onPageFinished().
    private static final long SPLASH_MIN_MS = 500;
    private static final long SPLASH_MAX_MS = 8000;

    private WebView web;
    private String pendingApkUrl;
    private long lastUpdateCheckAt = 0;
    // Set when the user taps "Later", so the prompt stays away for this run of
    // the app but comes back on the next launch. Deliberately NOT persisted:
    // a stored "skip this version" silently suppressed update prompts for good.
    private int skippedVersionThisSession = 0;
    private final android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private boolean awaitingRemoteDashboard = false;
    private final Runnable remoteDashboardTimeout = this::fallBackToLocalDashboard;
    private final Runnable showDashboardSafetyNet = this::showDashboard;
    private boolean dashboardShown = false;
    private long splashStartedAt = 0;
    private android.widget.TextView splashDots;
    private int splashDotStep = 0;

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
                if (request.isForMainFrame()) {
                    String reason = error.getDescription() != null ? error.getDescription().toString() : "couldn't reach the live page";
                    fallBackToLocalDashboard(reason);
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                awaitingRemoteDashboard = false;
                mainHandler.removeCallbacks(remoteDashboardTimeout);
                showDashboard();
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

        // Splash (icon + animated loading dots) stays up until the dashboard
        // has actually finished loading -- see showDashboard(), called from
        // onPageFinished above -- with a floor so it never just flashes past
        // on a fast connection, and a ceiling so it can never hang forever
        // if something goes wrong. Previously this was a fixed ~1.3s timer
        // that showed the WebView regardless of whether the page had
        // actually finished loading yet, which on a slow connection meant
        // revealing a blank page and looking broken/frozen.
        splashStartedAt = android.os.SystemClock.elapsedRealtime();
        showSplash(bg);
        mainHandler.postDelayed(showDashboardSafetyNet, SPLASH_MAX_MS);
        loadDashboard();

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
        iconParams.bottomMargin = (int) (18 * getResources().getDisplayMetrics().density);
        splash.addView(icon, iconParams);

        int mode = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        int dotColor = mode == Configuration.UI_MODE_NIGHT_YES ? 0xFFFFFFFF : 0xFF4F46E5;

        splashDots = new android.widget.TextView(this);
        splashDots.setTextSize(28);
        splashDots.setTextColor(dotColor);
        splashDots.setText(" ");
        splash.addView(splashDots);

        setContentView(splash);
        animateSplashDots();
    }

    /** Cycles the splash's ". . . ." through 1-4 visible dots so there is
     *  always something moving on screen while the dashboard loads --
     *  rather than a static icon that looks frozen on a slow connection. */
    private void animateSplashDots(){
        if (dashboardShown || isFinishing() || splashDots == null) return;
        splashDotStep = (splashDotStep % 4) + 1;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < splashDotStep; i++) sb.append(i == 0 ? "." : "  .");
        splashDots.setText(sb.toString());
        mainHandler.postDelayed(this::animateSplashDots, 350);
    }

    private void showDashboard(){
        if (isFinishing() || web == null || dashboardShown) return;
        long elapsed = android.os.SystemClock.elapsedRealtime() - splashStartedAt;
        if (elapsed < SPLASH_MIN_MS) {
            mainHandler.postDelayed(this::showDashboard, SPLASH_MIN_MS - elapsed);
            return;
        }
        dashboardShown = true;
        mainHandler.removeCallbacks(showDashboardSafetyNet);
        if (web.getParent() == null) setContentView(web);
    }

    /** Tries the live GitHub Pages copy of the dashboard first (so ordinary
     *  index.html edits show up without a new APK), falling back to the
     *  bundled asset if there's no network or the page can't be reached
     *  within REMOTE_DASHBOARD_TIMEOUT_MS. */
    private void loadDashboard() {
        if (!hasActiveNetwork()) {
            reportDashboardFallback("no active network connection");
            web.loadUrl(LOCAL_DASHBOARD_URL);
            return;
        }
        awaitingRemoteDashboard = true;
        web.loadUrl(REMOTE_DASHBOARD_URL);
        mainHandler.postDelayed(remoteDashboardTimeout, REMOTE_DASHBOARD_TIMEOUT_MS);
    }

    private void fallBackToLocalDashboard() {
        fallBackToLocalDashboard("timed out waiting for the live page to load");
    }

    private void fallBackToLocalDashboard(String reason) {
        if (!awaitingRemoteDashboard || web == null) return;
        awaitingRemoteDashboard = false;
        mainHandler.removeCallbacks(remoteDashboardTimeout);
        reportDashboardFallback(reason);
        web.stopLoading();
        web.loadUrl(LOCAL_DASHBOARD_URL);
    }

    private boolean dashboardFallbackReported = false;

    /** Surfaces (once per session) why the live dashboard couldn't be
     *  loaded, so a persistent connectivity/GitHub problem is diagnosable
     *  from a single glance instead of just quietly showing older content. */
    private void reportDashboardFallback(String reason) {
        Log.w(TAG, "Falling back to the offline dashboard copy: " + reason);
        if (dashboardFallbackReported) return;
        dashboardFallbackReported = true;
        runOnUiThread(() -> {
            if (!isFinishing()) {
                Toast.makeText(this, "Showing the offline copy (" + reason + ") — check your internet connection.", Toast.LENGTH_LONG).show();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Returning from the "allow installs from this app" Settings screen —
        // resume the update the user asked for instead of making them tap twice.
        if (pendingApkUrl != null && canInstallPackages()) {
            String url = pendingApkUrl;
            pendingApkUrl = null;
            downloadUpdateApk(url);
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
        mainHandler.removeCallbacksAndMessages(null);
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

                if (remoteVersion <= skippedVersionThisSession) return;

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
                    skippedVersionThisSession = remoteVersion;
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
        downloadUpdateApk(apkUrl);
    }

    /**
     * Downloads the update APK with HttpURLConnection and hands it straight to
     * the installer.
     *
     * This deliberately does NOT use DownloadManager. DownloadManager reports
     * completion via an ACTION_DOWNLOAD_COMPLETE broadcast sent by
     * com.android.providers.downloads -- a privileged framework app that does
     * not run under the system UID. A context-registered receiver flagged
     * RECEIVER_NOT_EXPORTED (required from targetSdk 33) never receives
     * broadcasts from such apps, so the completion callback never fired: the
     * APK downloaded fine and then sat on disk untouched, with no error and no
     * installer prompt. That silently blocked every update on Android 13+.
     *
     * HttpURLConnection has no such dependency, and is the same mechanism
     * checkForUpdate() and SheetSyncBridge already use successfully here.
     */
    private void downloadUpdateApk(String apkUrl) {
        File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (dir == null) {
            reportUpdateFailure("no storage available for the download");
            return;
        }
        File dest = new File(dir, UPDATE_FILE_NAME);
        File part = new File(dir, UPDATE_FILE_NAME + ".part");

        buildProgressDialog().show();

        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                if (dest.exists()) dest.delete();
                if (part.exists()) part.delete();

                // Follow redirects by hand: a GitHub release asset 302s to a
                // signed URL on a different host, and HttpURLConnection will
                // not always carry a redirect across hosts on its own.
                String url = apkUrl;
                boolean connected = false;
                for (int hop = 0; hop < 5 && !connected; hop++) {
                    conn = (HttpURLConnection) new URL(url).openConnection();
                    conn.setInstanceFollowRedirects(false);
                    conn.setConnectTimeout(15000);
                    conn.setReadTimeout(30000);
                    int code = conn.getResponseCode();
                    if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                        String next = conn.getHeaderField("Location");
                        conn.disconnect();
                        conn = null;
                        if (next == null) throw new Exception("redirect with no target");
                        url = next;
                        continue;
                    }
                    if (code != 200) throw new Exception("HTTP " + code + " downloading the update");
                    connected = true;
                }
                if (!connected || conn == null) throw new Exception("too many redirects");

                int total = conn.getContentLength();
                InputStream in = conn.getInputStream();
                FileOutputStream out = new FileOutputStream(part);
                byte[] buf = new byte[16384];
                long written = 0;
                int n, lastPercent = -1;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                    written += n;
                    if (total > 0) {
                        int percent = (int) (written * 100 / total);
                        if (percent != lastPercent) {
                            lastPercent = percent;
                            final int p = percent;
                            runOnUiThread(() -> updateProgressDialog(p));
                        }
                    }
                }
                out.flush();
                out.close();
                in.close();
                conn.disconnect();

                if (written == 0) throw new Exception("the downloaded file was empty");
                if (!looksLikeApk(part)) throw new Exception("the download was not a valid app file");
                if (!part.renameTo(dest)) throw new Exception("couldn't finalise the downloaded file");

                runOnUiThread(() -> {
                    dismissProgressDialog();
                    installDownloadedApk(dest);
                });
            } catch (Exception e) {
                Log.w(TAG, "Update download failed", e);
                if (conn != null) conn.disconnect();
                part.delete();
                String detail = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                runOnUiThread(() -> {
                    dismissProgressDialog();
                    reportUpdateFailure(detail);
                });
            }
        }).start();
    }

    /** An APK is a ZIP, so it must start with "PK". Cheap guard against
     *  handing the installer an error page or a truncated file. */
    private boolean looksLikeApk(File file) {
        try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
            byte[] magic = new byte[2];
            return in.read(magic) == 2 && magic[0] == 'P' && magic[1] == 'K';
        } catch (Exception e) {
            return false;
        }
    }

    private AlertDialog progressDialog;
    private android.widget.ProgressBar progressBar;
    private android.widget.TextView progressLabel;

    private AlertDialog buildProgressDialog() {
        android.widget.LinearLayout box = new android.widget.LinearLayout(this);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad, pad, pad);

        progressLabel = new android.widget.TextView(this);
        progressLabel.setText("Downloading update…");
        progressLabel.setTextSize(15);
        box.addView(progressLabel);

        progressBar = new android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setIndeterminate(true);
        android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int) (14 * getResources().getDisplayMetrics().density);
        box.addView(progressBar, lp);

        progressDialog = new AlertDialog.Builder(this)
                .setTitle("Updating A&B Loan Book")
                .setView(box)
                .setCancelable(false)
                .create();
        return progressDialog;
    }

    private void updateProgressDialog(int percent) {
        if (progressBar == null) return;
        progressBar.setIndeterminate(false);
        progressBar.setProgress(percent);
        if (progressLabel != null) progressLabel.setText("Downloading update… " + percent + "%");
    }

    private void dismissProgressDialog() {
        if (progressDialog != null && progressDialog.isShowing() && !isFinishing()) {
            try { progressDialog.dismiss(); } catch (Exception ignored) {}
        }
        progressDialog = null;
        progressBar = null;
        progressLabel = null;
    }

    /** Never silent: an update that can't complete says why, on screen. */
    private void reportUpdateFailure(String detail) {
        Log.w(TAG, "Update could not be installed: " + detail);
        if (isFinishing()) return;
        new AlertDialog.Builder(this)
                .setTitle("Update didn't finish")
                .setMessage("Couldn't install the update: " + detail
                        + "\n\nYou can try again next time you open the app.")
                .setPositiveButton("OK", (d, w) -> d.dismiss())
                .show();
    }

    private void installDownloadedApk(File file) {
        if (file == null || !file.exists()) {
            reportUpdateFailure("the downloaded file went missing");
            return;
        }
        try {
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "application/vnd.android.package-archive");
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(intent);
            Toast.makeText(this, "Tap Install on the next screen to finish updating.", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            reportUpdateFailure(e.getClass().getSimpleName()
                    + (e.getMessage() != null ? ": " + e.getMessage() : ""));
        }
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

        /** The installed app version, shown in the dashboard footer. Without
         *  this there was no way to tell which build was actually running on a
         *  phone, which is what made a broken updater so hard to spot. */
        @JavascriptInterface
        public String appVersion() {
            return "v" + BuildConfig.VERSION_CODE;
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
