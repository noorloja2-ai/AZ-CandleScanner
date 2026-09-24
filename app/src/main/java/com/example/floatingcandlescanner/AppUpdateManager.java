package com.example.floatingcandlescanner;

import android.app.*;
import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;

/**
 * Simple self-update helper for privately distributed APK builds.
 * It checks the permanent GitHub manifest, downloads the APK with DownloadManager,
 * and then opens Android's normal package installer. Android still requires the
 * user to approve installation; silent self-installation is intentionally not used.
 */
public final class AppUpdateManager {
    private static final String UPDATE_MANIFEST_URL =
            "https://raw.githubusercontent.com/noorloja2-ai/AZ-CandleScanner/main/update.json";
    private static final String APK_MIME = "application/vnd.android.package-archive";

    private final Activity activity;
    private final TextView status;
    private long downloadId = -1L;
    private String expectedSha256 = "";
    private boolean receiverRegistered = false;

    private final BroadcastReceiver downloadReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (!DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction())) return;
            long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L);
            if (id != downloadId) return;
            openDownloadedApk(id);
        }
    };

    public AppUpdateManager(Activity activity, TextView status) {
        this.activity = activity;
        this.status = status;
    }

    public void checkForUpdate(boolean showNoUpdateMessage) {
        setStatus("Checking GitHub for app update…");
        new Thread(() -> {
            boolean ok = checkDriveManifest(showNoUpdateMessage);
            if (!ok) {
                activity.runOnUiThread(() -> {
                    setStatus("GitHub update source is unavailable. Check your internet connection and try again.");
                    if (showNoUpdateMessage) toast("Could not read the GitHub update file.");
                });
            }
        }, "app-update-check").start();
    }

    private boolean checkDriveManifest(boolean showNoUpdateMessage) {
        HttpURLConnection c = null;
        try {
            // A unique query and no-cache headers prevent GitHub/CDN from returning
            // an older update.json after a new APK has already been published.
            String freshManifestUrl = UPDATE_MANIFEST_URL + "?t=" + System.currentTimeMillis();
            c = (HttpURLConnection) new URL(freshManifestUrl).openConnection();
            c.setUseCaches(false);
            c.setDefaultUseCaches(false);
            c.setConnectTimeout(9000);
            c.setReadTimeout(9000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", "Floating-Candle-Scanner-Android");
            c.setRequestProperty("Cache-Control", "no-cache, no-store, max-age=0");
            c.setRequestProperty("Pragma", "no-cache");
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) return false;

            String body = readBody(c);
            String trimmed = body == null ? "" : body.trim();
            if (!trimmed.startsWith("{")) return false;
            JSONObject manifest = new JSONObject(trimmed);

            String latestVersion = normalizeVersion(manifest.optString("version", ""));
            int latestBuild = manifest.optInt("build", 0);
            String apkUrl = manifest.optString("apkUrl", "").trim();
            String apkFileId = manifest.optString("apkFileId", "").trim();
            String apkName = manifest.optString("apkName", "").trim();
            String notes = manifest.optString("notes", "").trim();
            String sha256 = manifest.optString("apkSha256", "").trim().toLowerCase();
            if (apkUrl.isEmpty() && !apkFileId.isEmpty()) {
                apkUrl = "https://drive.google.com/uc?export=download&id=" + apkFileId;
            }

            final String fVersion = latestVersion;
            final int fBuild = latestBuild;
            final String fUrl = apkUrl;
            final String fName = apkName;
            final String fNotes = notes;
            final String fSha256 = sha256;
            activity.runOnUiThread(() -> handleRelease(fVersion, fBuild, fUrl, fName, fNotes, fSha256, showNoUpdateMessage));
            return true;
        } catch (Exception ignored) {
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static String readBody(HttpURLConnection c) throws Exception {
        StringBuilder body = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream()))) {
            String line;
            while ((line = r.readLine()) != null) body.append(line).append('\n');
        }
        return body.toString();
    }

    private void handleRelease(String latestVersion, int latestBuild, String apkUrl, String apkName,
                               String notes, String sha256, boolean showNoUpdateMessage) {
        String current = BuildConfig.VERSION_NAME;
        int currentBuild = BuildConfig.VERSION_CODE;
        if (latestVersion.isEmpty()) {
            setStatus("Update server did not provide a version number.");
            return;
        }
        int versionCompare = compareVersions(latestVersion, current);
        boolean newer = versionCompare > 0 || (versionCompare == 0 && latestBuild > currentBuild);
        if (!newer) {
            setStatus("App is up to date — v" + current + ".");
            if (showNoUpdateMessage) toast("You already have the latest version.");
            return;
        }
        if (apkUrl.isEmpty()) {
            setStatus("v" + latestVersion + " is available, but its APK is missing from the release.");
            if (showNoUpdateMessage) toast("New version found, but no APK was attached.");
            return;
        }
        if (!sha256.matches("[0-9a-f]{64}")) {
            setStatus("v" + latestVersion + " is available, but its security checksum is missing.");
            toast("Update blocked because the APK could not be verified.");
            return;
        }

        String message = "Installed: v" + current + "\nAvailable: v" + latestVersion;
        if (!notes.isEmpty()) {
            String shortNotes = notes.length() > 500 ? notes.substring(0, 500) + "…" : notes;
            message += "\n\n" + shortNotes;
        }
        final String finalMessage = message;
        new AlertDialog.Builder(activity)
                .setTitle("App update available")
                .setMessage(finalMessage)
                .setNegativeButton("Later", null)
                .setPositiveButton("Download & Update", (d, which) ->
                        prepareDownload(apkUrl, apkName, latestVersion, sha256))
                .show();
        setStatus("Update v" + latestVersion + " is available.");
    }

    private void prepareDownload(String url, String name, String version, String sha256) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                !activity.getPackageManager().canRequestPackageInstalls()) {
            new AlertDialog.Builder(activity)
                    .setTitle("Allow app updates")
                    .setMessage("Android must allow this app to install its downloaded update. Enable “Allow from this source”, return here, then tap CHECK / UPDATE APP again.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Open Settings", (d, w) -> {
                        Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:" + activity.getPackageName()));
                        activity.startActivity(i);
                    })
                    .show();
            return;
        }
        downloadApk(url, name, version, sha256);
    }

    private void downloadApk(String url, String name, String version, String sha256) {
        try {
            String fileName = (name == null || name.trim().isEmpty())
                    ? "CandleScanner_v" + version + ".apk"
                    : name.trim();
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
            request.setTitle("CandleScanner v" + version + " update");
            request.setDescription("Downloading app update");
            request.setMimeType(APK_MIME);
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setAllowedOverMetered(true);
            request.setAllowedOverRoaming(false);
            request.setDestinationInExternalFilesDir(activity, Environment.DIRECTORY_DOWNLOADS, fileName);

            DownloadManager dm = (DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE);
            expectedSha256 = sha256;
            downloadId = dm.enqueue(request);
            ensureReceiver();
            setStatus("Downloading CandleScanner v" + version + "…");
            toast("Update download started.");
        } catch (Exception e) {
            setStatus("Could not start update download: " + safeMessage(e));
        }
    }

    private void ensureReceiver() {
        if (receiverRegistered) return;
        IntentFilter f = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        if (Build.VERSION.SDK_INT >= 33)
            activity.registerReceiver(downloadReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        else
            activity.registerReceiver(downloadReceiver, f);
        receiverRegistered = true;
    }

    private void openDownloadedApk(long id) {
        DownloadManager dm = (DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE);
        DownloadManager.Query q = new DownloadManager.Query().setFilterById(id);
        try (Cursor cursor = dm.query(q)) {
            if (cursor == null || !cursor.moveToFirst()) {
                setStatus("Update downloaded, but the file could not be opened.");
                return;
            }
            int statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS);
            int state = statusIndex >= 0 ? cursor.getInt(statusIndex) : DownloadManager.STATUS_FAILED;
            if (state != DownloadManager.STATUS_SUCCESSFUL) {
                setStatus("Update download failed. Tap CHECK / UPDATE APP to retry.");
                return;
            }
        }
        Uri uri = dm.getUriForDownloadedFile(id);
        if (uri == null) {
            setStatus("Update downloaded, but Android did not return the APK file URI.");
            return;
        }
        String actual = sha256(uri);
        if (actual.isEmpty() || !actual.equalsIgnoreCase(expectedSha256)) {
            setStatus("Update blocked: APK security checksum did not match.");
            toast("Downloaded update failed security verification and was not installed.");
            dm.remove(id);
            return;
        }
        try {
            Intent install = new Intent(Intent.ACTION_VIEW);
            install.setDataAndType(uri, APK_MIME);
            install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(install);
            setStatus("Update downloaded — approve the Android installation screen.");
        } catch (Exception e) {
            setStatus("Update downloaded, but installer could not open: " + safeMessage(e));
        }
    }

    private String sha256(Uri uri) {
        try (InputStream in = activity.getContentResolver().openInputStream(uri)) {
            if (in == null) return "";
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int count;
            while ((count = in.read(buffer)) != -1) digest.update(buffer, 0, count);
            StringBuilder out = new StringBuilder(64);
            for (byte b : digest.digest()) out.append(String.format("%02x", b & 0xff));
            return out.toString();
        } catch (Exception ignored) {
            return "";
        }
    }

    public void destroy() {
        if (receiverRegistered) {
            try { activity.unregisterReceiver(downloadReceiver); } catch (Exception ignored) {}
            receiverRegistered = false;
        }
    }

    private void setStatus(String s) {
        if (status != null) status.setText(s);
    }

    private void toast(String s) {
        Toast.makeText(activity, s, Toast.LENGTH_LONG).show();
    }

    private static String normalizeVersion(String s) {
        if (s == null) return "";
        s = s.trim();
        while (s.startsWith("v") || s.startsWith("V")) s = s.substring(1);
        int dash = s.indexOf('-');
        if (dash > 0) s = s.substring(0, dash);
        return s.trim();
    }

    static int compareVersions(String a, String b) {
        String[] pa = normalizeVersion(a).split("\\.");
        String[] pb = normalizeVersion(b).split("\\.");
        int n = Math.max(pa.length, pb.length);
        for (int i = 0; i < n; i++) {
            int ai = i < pa.length ? numberPart(pa[i]) : 0;
            int bi = i < pb.length ? numberPart(pb[i]) : 0;
            if (ai != bi) return Integer.compare(ai, bi);
        }
        return 0;
    }

    private static int numberPart(String s) {
        try {
            String digits = s.replaceAll("[^0-9].*$", "");
            return digits.isEmpty() ? 0 : Integer.parseInt(digits);
        } catch (Exception e) { return 0; }
    }

    private static String safeMessage(Exception e) {
        String m = e.getMessage();
        return (m == null || m.trim().isEmpty()) ? e.getClass().getSimpleName() : m;
    }
}
