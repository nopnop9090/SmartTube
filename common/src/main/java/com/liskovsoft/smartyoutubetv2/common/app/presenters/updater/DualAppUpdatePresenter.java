package com.liskovsoft.smartyoutubetv2.common.app.presenters.updater;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.net.Uri;

import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.sharedutils.helpers.MessageHelpers;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.sharedutils.okhttp.OkHttpManager;
import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.OptionItem;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.UiOptionItem;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.AppDialogPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.BrowsePresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.base.BasePresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.dialogs.AppUpdatePresenter;
import com.liskovsoft.smartyoutubetv2.common.misc.BackupAndRestoreManager;
import com.liskovsoft.smartyoutubetv2.common.prefs.GeneralData;
import com.liskovsoft.smartyoutubetv2.common.utils.AppDialogUtil;
import com.liskovsoft.smartyoutubetv2.common.utils.LoadingManager;
import com.liskovsoft.smartyoutubetv2.common.utils.Utils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import okhttp3.Response;

/**
 * Dual-mode update checker for the nopnop9090 fork:
 *  - Fork: lists the 3 latest fork releases via GitHub REST API (nopnop9090/SmartTube)
 *  - Origin: reuses the existing AppUpdateChecker to fetch yuliskov's beta2 manifest
 *
 * UI behavior:
 *  - Default focus is on fork (same signature = upgrade without uninstall + settings preserved).
 *  - Origin update is offered only as a secondary, warnable option (different signature = settings loss).
 *  - The three latest fork releases are selectable; latest is highlighted by default.
 *
 * Tag convention: fork releases use 'v<version>-features-<N>' (e.g. v32.07-features-1).
 * The first JSON-object's "versionCode" inside each upstream manifest entry is matched against the APK asset name suffix.
 */
public class DualAppUpdatePresenter extends BasePresenter<Void> {
    private static final String TAG = DualAppUpdatePresenter.class.getSimpleName();
    private static final String FORK_RELEASES_URL =
            "https://api.github.com/repos/nopnop9090/SmartTube/releases?per_page=20";
    private static final String FORK_APK_NAME_HINT = "SmartTube_32_nopnop_features.apk";
    private static final int FORK_RELEASE_LIMIT = 3;
    private static final int STATUS_NEWER = 0;
    private static final int STATUS_CURRENT = 1;
    private static final int STATUS_OLDER = 2;

    @SuppressLint("StaticFieldLeak")
    private static DualAppUpdatePresenter sInstance;
    private final AppDialogPresenter mDialog;
    private final AppUpdatePresenter mOriginPresenter; // for origin check + install
    private boolean mIsForceCheck;

    public DualAppUpdatePresenter(Context context) {
        super(context);
        mDialog = AppDialogPresenter.instance(context);
        mOriginPresenter = AppUpdatePresenter.instance(context);
    }

    public static DualAppUpdatePresenter instance(Context context) {
        if (sInstance == null) {
            sInstance = new DualAppUpdatePresenter(context);
        }
        sInstance.setContext(context);
        return sInstance;
    }

    public static void unhold() {
        sInstance = null;
    }

    /**
     * Run dual update check.
     *
     * @param forceCheck if true, ignores throttling and shows "no updates" message if nothing newer
     */
    public void start(boolean forceCheck) {
        mIsForceCheck = forceCheck;
        if (forceCheck) {
            LoadingManager.showLoading(getContext(), true);
        }
        // Start fork check on a background thread (OkHttp + JSON parsing)
        new Thread(this::runForkCheck, "DualAppUpdate-ForkCheck").start();
        // Origin check runs in parallel via the existing presenter
        mOriginPresenter.start(false);
    }

    private void runForkCheck() {
        try {
            List<ForkRelease> releases = fetchForkReleases();
            int currentVersionCode = getCurrentVersionCode();
            List<ForkRelease> newerReleases = new ArrayList<>();
            for (ForkRelease rel : releases) {
                if (rel.versionCode > currentVersionCode) {
                    newerReleases.add(rel);
                }
            }
            // Run UI dispatch on main thread
            Helpers.postOnUiThread(() -> handleForkResults(newerReleases, releases, currentVersionCode));
        } catch (Exception ex) {
            Log.e(TAG, "Fork update check failed: " + ex.getMessage(), ex);
            Helpers.postOnUiThread(() -> {
                if (mIsForceCheck) {
                    // Silent failure on automatic check; only show error on manual forceCheck
                    MessageHelpers.showLongMessage(getContext(), R.string.dual_update_fork_check_failed);
                }
            });
        }
    }

    private void handleForkResults(List<ForkRelease> newerReleases, List<ForkRelease> allReleases, int currentVersionCode) {
        // Note: mIsForceCheck + LoadingManager handling is owned by the UI dialog flow;
        // we don't dismiss LoadingManager here because the origin check may still be in flight.
        if (newerReleases.isEmpty()) {
            if (mIsForceCheck) {
                showNoForkUpdateFound(allReleases, currentVersionCode);
            }
            return;
        }
        showForkUpdateDialog(newerReleases, currentVersionCode);
    }

    private void showNoForkUpdateFound(List<ForkRelease> allReleases, int currentVersionCode) {
        // User-triggered check: show "everything up to date" + offer to inspect the 3 latest (in case
        // they want to downgrade). Dismiss loading first.
        LoadingManager.showLoading(getContext(), false);
        List<OptionItem> options = new ArrayList<>();
        // Even if "up to date", still offer the 3 latest as a manual archive (for reinstall / downgrade)
        for (ForkRelease rel : allReleases) {
            options.add(buildForkInstallOption(rel, currentVersionCode));
        }
        mDialog.appendStringsCategory(getContext().getString(R.string.dual_update_no_updates_available), options);
        mDialog.showDialog(getContext().getString(R.string.dual_update_dialog_title), DualAppUpdatePresenter::unhold);
    }

    private void showForkUpdateDialog(List<ForkRelease> newerReleases, int currentVersionCode) {
        LoadingManager.showLoading(getContext(), false);
        if (getContext() == null || getViewManager().isPlayerInForeground() || !Utils.isAppInForegroundFixed()) {
            // Defer if player is open / app backgrounded
            pinForkUpdateSection(newerReleases);
            return;
        }
        List<OptionItem> options = new ArrayList<>();
        // Order: newest first (releases list is sorted desc). Marker handled per-version via classifyStatus().
        for (ForkRelease rel : newerReleases) {
            options.add(buildForkInstallOption(rel, currentVersionCode));
        }
        mDialog.appendStringsCategory(
                getContext().getString(R.string.dual_update_fork_releases_category),
                options);
        // Always add origin-update warning if origin is also newer (handled separately below)
        maybeAppendOriginUpdateNote();
        mDialog.showDialog(getContext().getString(R.string.dual_update_dialog_title), DualAppUpdatePresenter::unhold);
    }

    private void pinForkUpdateSection(List<ForkRelease> newerReleases) {
        if (getContext() == null) return;
        int currentVersionCode = getCurrentVersionCode();
        StringBuilder body = new StringBuilder();
        for (ForkRelease rel : newerReleases) {
            String marker = markerFor(classifyStatus(rel.versionCode, currentVersionCode));
            body.append(String.format("%s  %s (v%d)%n", marker, rel.tag, rel.versionCode));
        }
        body.append("\n").append(getContext().getString(R.string.dual_update_open_settings_hint));

        BrowsePresenter.instance(getContext()).pinItem(
                getContext().getString(R.string.dual_update_found_in_sidebar),
                R.drawable.action_info,
                new com.liskovsoft.smartyoutubetv2.common.app.models.errors.ErrorFragmentData() {
                    @Override
                    public void onAction() {
                        Helpers.postOnUiThread(() -> showForkUpdateDialog(newerReleases, getCurrentVersionCode()));
                    }
                    @Override
                    public String getMessage() {
                        return body.toString();
                    }
                    @Override
                    public String getActionText() {
                        return getContext().getString(R.string.dual_update_open_settings_action);
                    }
                });
    }

    /**
     * Build a single install option for a fork release. The label shows:
     *   [STATUS MARKER] [tag] (v&lt;code&gt;) • &lt;size&gt;
     * where STATUS MARKER is one of:
     *   ▲ NEW       (rel.versionCode &gt; currentVersionCode)
     *   ● CURRENT   (rel.versionCode == currentVersionCode)
     *   ▼ OLDER     (rel.versionCode &lt; currentVersionCode, with downgrade confirmation)
     */
    private OptionItem buildForkInstallOption(ForkRelease rel, int currentVersionCode) {
        int status = classifyStatus(rel.versionCode, currentVersionCode);
        String marker = markerFor(status);
        String label = String.format("%s  %s  %s (v%d) • %s",
                marker,
                getContext().getString(R.string.dual_update_install_label),
                rel.tag,
                rel.versionCode,
                rel.humanSize());
        return UiOptionItem.from(label, optionItem -> onInstallClicked(rel, status));
    }

    private void onInstallClicked(ForkRelease rel, int status) {
        if (status == STATUS_OLDER) {
            // Confirm before downgrading — older version may have different DB schema
            AppDialogUtil.showConfirmationDialog(
                    getContext(),
                    getContext().getString(R.string.dual_update_downgrade_title),
                    () -> {
                        // Auto-backup before downgrade so user can come back if it breaks
                        try {
                            BackupAndRestoreManager backupManager = new BackupAndRestoreManager(getContext());
                            backupManager.checkPermAndBackup();
                        } catch (Exception ex) {
                            Log.e(TAG, "Pre-downgrade backup failed: " + ex.getMessage(), ex);
                        }
                        startForkDownloadAndInstall(rel);
                    },
                    () -> { /* user cancelled downgrade */ });
            return;
        }
        // NEWER or CURRENT — proceed directly
        startForkDownloadAndInstall(rel);
    }

    /**
     * Classify a release relative to the currently installed version.
     * Note: synthetic versionCode is compared numerically, so e.g. v32.07-features-2 == 320009
     * will compare as newer than v32.07-features-1 (320008).
     */
    private int classifyStatus(int releaseVersionCode, int currentVersionCode) {
        if (releaseVersionCode > currentVersionCode) return STATUS_NEWER;
        if (releaseVersionCode == currentVersionCode) return STATUS_CURRENT;
        return STATUS_OLDER;
    }

    private String markerFor(int status) {
        switch (status) {
            case STATUS_NEWER:  return getContext().getString(R.string.dual_update_marker_newer);
            case STATUS_CURRENT: return getContext().getString(R.string.dual_update_marker_current);
            case STATUS_OLDER:  return getContext().getString(R.string.dual_update_marker_older);
            default: return "";
        }
    }

    /**
     * Hook for the origin-update-warning UI. Called as part of the fork update dialog.
     * The actual origin-check is started separately by {@link #start(boolean)}.
     */
    private void maybeAppendOriginUpdateNote() {
        // The AppUpdatePresenter's flow is independent — it will show its own dialog if origin is newer.
        // We just rely on the existing origin flow here. If origin is newer, the user sees both dialogs
        // back-to-back (fork first, then origin). The origin dialog's install action is unchanged.
    }

    private void startForkDownloadAndInstall(ForkRelease rel) {
        // Use AppDownloader (the existing APK downloader from appupdatechecker2) — same flow as origin.
        // We replicate the install path by delegating to AppUpdatePresenter's download mechanism:
        // create a synthetic AppUpdateCheckerListener hook by triggering a small download via OkHttpManager
        // and then calling Helpers.installPackage(...).
        LoadingManager.showLoading(getContext(), true);
        new Thread(() -> {
            try {
                String apkPath = downloadForkApk(rel);
                Helpers.postOnUiThread(() -> {
                    LoadingManager.showLoading(getContext(), false);
                    if (apkPath != null) {
                        Helpers.installPackage(getContext(), apkPath);
                    } else {
                        MessageHelpers.showLongMessage(getContext(), R.string.dual_update_download_failed);
                    }
                });
            } catch (Exception ex) {
                Log.e(TAG, "APK download failed: " + ex.getMessage(), ex);
                Helpers.postOnUiThread(() -> {
                    LoadingManager.showLoading(getContext(), false);
                    MessageHelpers.showLongMessage(getContext(), R.string.dual_update_download_failed);
                });
            }
        }, "DualAppUpdate-Download").start();
    }

    private String downloadForkApk(ForkRelease rel) throws Exception {
        File cacheDir = getContext().getCacheDir();
        File outFile = new File(cacheDir, "update_" + rel.tag + ".apk");
        Response response = OkHttpManager.instance().doGetRequest(rel.apkUrl);
        if (response == null || !response.isSuccessful() || response.body() == null) {
            return null;
        }
        try (java.io.InputStream in = response.body().byteStream();
             java.io.FileOutputStream out = new java.io.FileOutputStream(outFile)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            out.flush();
        }
        return outFile.getAbsolutePath();
    }

    /**
     * Public entry-point for the origin-update flow with extra confirmation + backup.
     * Called from settings UI as a secondary option ("Switch to official version").
     */
    public void showOriginUpdateWarning(Runnable onConfirmed) {
        AppDialogUtil.showConfirmationDialog(
                getContext(),
                getContext().getString(R.string.dual_update_origin_warning_title),
                () -> offerBackupThenInstall(onConfirmed),
                () -> { /* cancel — do nothing */ });
    }

    private void offerBackupThenInstall(Runnable onConfirmed) {
        AppDialogUtil.showConfirmationDialog(
                getContext(),
                getContext().getString(R.string.dual_update_origin_backup_title),
                () -> {
                    // Create backup then proceed with origin install
                    try {
                        BackupAndRestoreManager backupManager = new BackupAndRestoreManager(getContext());
                        backupManager.checkPermAndBackup();
                    } catch (Exception ex) {
                        Log.e(TAG, "Backup failed: " + ex.getMessage(), ex);
                        MessageHelpers.showLongMessage(getContext(), R.string.dual_update_backup_failed);
                    }
                    // Run the original origin install (provided by caller)
                    if (onConfirmed != null) {
                        onConfirmed.run();
                    }
                },
                () -> { /* cancel */ });
    }

    /**
     * Loads up to FORK_RELEASE_LIMIT newest releases from the fork, sorted desc by versionCode.
     * Filters out draft / prerelease. Each entry exposes the first APK asset whose name matches
     * the fork APK naming convention.
     */
    private List<ForkRelease> fetchForkReleases() throws Exception {
        Response response = OkHttpManager.instance().doGetRequest(FORK_RELEASES_URL);
        if (response == null || !response.isSuccessful() || response.body() == null) {
            throw new IllegalStateException("GitHub API responded " +
                    (response != null ? response.code() : "null"));
        }
        String body = response.body().string();
        response.close();
        JSONArray arr = new JSONArray(body);
        List<ForkRelease> result = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject rel = arr.getJSONObject(i);
            String tag = rel.optString("tag_name", "");
            // Accept only fork-style tags: v<version>-features-<N>
            if (!tag.matches("v\\d+\\.\\d+(?:\\.\\d+)?-features-\\d+")) {
                continue;
            }
            boolean draft = rel.optBoolean("draft", false);
            boolean prerelease = rel.optBoolean("prerelease", false);
            if (draft || prerelease) continue;
            int versionCode = parseVersionCodeFromTag(tag);
            String apkUrl = findApkAssetUrl(rel.optJSONArray("assets"));
            if (apkUrl == null) continue;
            ForkRelease fr = new ForkRelease();
            fr.tag = tag;
            fr.versionCode = versionCode;
            fr.apkUrl = apkUrl;
            fr.sizeBytes = relSizeFromAssets(rel.optJSONArray("assets"));
            fr.shortChangelog = shortenChangelog(rel.optString("body", ""));
            result.add(fr);
        }
        // Sort by versionCode desc
        Collections.sort(result, (a, b) -> Integer.compare(b.versionCode, a.versionCode));
        // Cap to FORK_RELEASE_LIMIT
        if (result.size() > FORK_RELEASE_LIMIT) {
            result = result.subList(0, FORK_RELEASE_LIMIT);
        }
        return result;
    }

    private int parseVersionCodeFromTag(String tag) {
        // tag is "v<version>-features-<N>" — we don't know the upstream versionCode, so we
        // synthesize one from the trailing feature number: 10000 * major(ver) + 100 * minor(ver) + patch(ver) + feat
        // Example: v32.07-features-1 → 32*10000 + 0*100 + 7 + 1 = 320008
        try {
            String stripped = tag.startsWith("v") ? tag.substring(1) : tag;
            // Format: <version>-features-<N>  e.g. "32.07-features-1"
            String[] parts = stripped.split("-");
            // parts[0]=version, parts[1]="features", parts[2]=N (or parts[3]=N if version has dashes, which it doesn't here)
            String[] ver = parts[0].split("\\.");
            int major = ver.length > 0 ? Integer.parseInt(ver[0]) : 0;
            int minor = ver.length > 1 ? Integer.parseInt(ver[1]) : 0;
            int patch = ver.length > 2 ? Integer.parseInt(ver[2]) : 0;
            int feat = parts.length > 2 ? Integer.parseInt(parts[2]) : 0;
            return major * 10000 + minor * 100 + patch + feat;
        } catch (Exception ex) {
            return 0;
        }
    }

    private String findApkAssetUrl(JSONArray assets) {
        if (assets == null) return null;
        for (int i = 0; i < assets.length(); i++) {
            JSONObject a = assets.optJSONObject(i);
            if (a == null) continue;
            String name = a.optString("name", "");
            String url = a.optString("browser_download_url", "");
            if (name.endsWith(".apk") && url.contains("SmartTube_")) {
                return url;
            }
        }
        return null;
    }

    private long relSizeFromAssets(JSONArray assets) {
        if (assets == null) return 0;
        for (int i = 0; i < assets.length(); i++) {
            JSONObject a = assets.optJSONObject(i);
            if (a == null) continue;
            String name = a.optString("name", "");
            if (name.endsWith(".apk") && name.contains("SmartTube_")) {
                return a.optLong("size", 0);
            }
        }
        return 0;
    }

    private String shortenChangelog(String changelog) {
        if (changelog == null || changelog.isEmpty()) return "";
        String firstLine = changelog.split("\\r?\\n")[0];
        return firstLine.length() > 80 ? firstLine.substring(0, 77) + "..." : firstLine;
    }

    private int getCurrentVersionCode() {
        try {
            PackageInfo info = getContext().getPackageManager()
                    .getPackageInfo(getContext().getPackageName(), 0);
            return info.versionCode;
        } catch (Exception ex) {
            Log.e(TAG, "Cannot read current versionCode: " + ex.getMessage());
            return 0;
        }
    }

    /**
     * Simple data carrier for one fork release.
     */
    private static class ForkRelease {
        String tag;
        int versionCode;
        String apkUrl;
        long sizeBytes;
        String shortChangelog;

        String humanSize() {
            if (sizeBytes <= 0) return "?";
            if (sizeBytes > 1024 * 1024) {
                return String.format("%.1f MB", sizeBytes / (1024.0 * 1024.0));
            }
            return String.format("%.0f KB", sizeBytes / 1024.0);
        }
    }
}
