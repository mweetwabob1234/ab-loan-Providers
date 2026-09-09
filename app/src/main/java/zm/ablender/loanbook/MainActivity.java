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
import android.util.Log;
import android.view.KeyEvent;
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
 * Single-screen shell. The dashboard interface is the same index.html
 * shipped in app/src/main/assets — edit that file to change the app.
 *
 * Also runs a lightweight self-updater: on launch it checks the GitHub
 * repo's latest Release for a newer versionCode, and if found offers to
 * download and install it in place (see checkForUpdate() below).
 */
public class MainActivity extends Activity {

    private static final String TAG = "ABLoanBook";
    private static final String RELEASES_API =
            "https://api.github.com/repos/mweetwabob1234/ab-loan-Providers/releases/latest";
    private static final String PREFS = "ablb_prefs";
    private static final String KEY_SKIPPED_VERSION = "skipped_version";
    private static final String KEY_ASKED_ALARM_PERM = "asked_alarm_permission";
    private static final String UPDATE_FILE_NAME = "ablb-update.apk";

    static final String CHANNEL_ID = "loan_due_reminders";
    private static final int REQUEST_NOTIFICATIONS = 2001;

    private WebView web;
    private long downloadId = -1;
    private String pendingApkUrl;

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

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setLoadWithOverviewMode(false);
        s.setUseWideViewPort(false);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);

        web.setWebViewClient(new WebViewClient());
        web.setOverScrollMode(WebView.OVER_SCROLL_NEVER);

        // Match the WebView background to the theme so there is no white flash.
        int mode = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        web.setBackgroundColor(mode == Configuration.UI_MODE_NIGHT_YES ? 0xFF0F1115 : 0xFFF3F4F6);

        web.loadUrl("file:///android_asset/index.html");
        setContentView(web);

        IntentFilter filter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(downloadReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(downloadReceiver, filter);
        }

        checkForUpdate();
        setupDueReminders();
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

    private void checkForUpdate() {
        new Thread(() -> {
            try {
                URL url = new URL(RELEASES_API);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestProperty("Accept", "application/vnd.github+json");
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                if (conn.getResponseCode() != 200) return;

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
            }
        }).start();
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
