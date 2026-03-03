/*
 * Copyright (C) 2026 The BasedOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.lineageos.updater.controller;

import android.content.Context;
import android.content.SharedPreferences;
import android.gsi.IGsiService;
import android.os.PowerManager;
import android.os.SharedMemory;
import android.os.SystemClock;
import android.os.image.DynamicSystemManager;
import android.system.ErrnoException;
import android.util.Log;
import android.util.Pair;

import androidx.preference.PreferenceManager;

import org.lineageos.updater.misc.Constants;
import org.lineageos.updater.model.Update;
import org.lineageos.updater.model.UpdateStatus;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.zip.GZIPInputStream;

class GSIUpdateInstaller {

    private static final String TAG = "GSIUpdateInstaller";

    private static final int SHARED_MEM_SIZE = 524288; // 512 KiB
    private static final int MAX_REPORT_INTERVAL_MS = 1000;
    private static final String DSU_PARTITION_NAME = "basedos";

    private static GSIUpdateInstaller sInstance = null;

    private final UpdaterController mUpdaterController;
    private final Context mContext;
    private String mDownloadId;
    private Thread mInstallThread;
    private volatile boolean mCancelled;

    private GSIUpdateInstaller(Context context, UpdaterController updaterController) {
        mUpdaterController = updaterController;
        mContext = context.getApplicationContext();
    }

    static synchronized GSIUpdateInstaller getInstance(Context context,
            UpdaterController updaterController) {
        if (sInstance == null) {
            sInstance = new GSIUpdateInstaller(context, updaterController);
        }
        return sInstance;
    }

    static synchronized boolean isInstallingUpdate(Context context) {
        SharedPreferences pref = PreferenceManager.getDefaultSharedPreferences(context);
        return pref.getString(Constants.PREF_INSTALLING_GSI_ID, null) != null;
    }

    /**
     * Clear stale install state left by a crashed or killed service.
     * If the preference says we're installing but the install thread is dead,
     * clear the preference so new installs aren't permanently blocked.
     */
    static synchronized void cleanupStaleState(Context context) {
        SharedPreferences pref = PreferenceManager.getDefaultSharedPreferences(context);
        String installingId = pref.getString(Constants.PREF_INSTALLING_GSI_ID, null);
        if (installingId == null) return;

        if (sInstance == null || sInstance.mInstallThread == null
                || !sInstance.mInstallThread.isAlive()) {
            Log.w(TAG, "Clearing stale GSI install state for: " + installingId);
            pref.edit().remove(Constants.PREF_INSTALLING_GSI_ID).apply();
        }
    }

    static synchronized boolean isInstallingUpdate(Context context, String downloadId) {
        SharedPreferences pref = PreferenceManager.getDefaultSharedPreferences(context);
        return downloadId.equals(pref.getString(Constants.PREF_INSTALLING_GSI_ID, null));
    }

    /**
     * Check if there is a pending GSI install that was deferred because the device
     * was running from DSU. After rebooting to stock, the install can proceed.
     */
    static String getPendingGSIReboot(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context)
                .getString(Constants.PREF_PENDING_GSI_REBOOT, null);
    }

    static void clearPendingGSIReboot(Context context) {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
                .remove(Constants.PREF_PENDING_GSI_REBOOT)
                .apply();
    }

    void install(String downloadId) {
        if (isInstallingUpdate(mContext)) {
            Log.e(TAG, "Already installing a GSI update");
            return;
        }

        mDownloadId = downloadId;
        mCancelled = false;

        Update update = mUpdaterController.getActualUpdate(mDownloadId);
        if (update == null) {
            Log.e(TAG, "Update not found: " + downloadId);
            return;
        }

        File file = update.getFile();
        if (file == null || !file.exists()) {
            Log.e(TAG, "Update file not found");
            update.setStatus(UpdateStatus.INSTALLATION_FAILED);
            mUpdaterController.notifyUpdateChange(mDownloadId);
            return;
        }

        // Check if device is currently running from DSU.
        DynamicSystemManager dsm = mContext.getSystemService(DynamicSystemManager.class);
        if (dsm != null && dsm.isInUse()) {
            // Cannot install a new DSU while booted from one (Android platform limitation).
            // Disable the current DSU and prompt for reboot. After rebooting to the
            // original system partition, the install will be auto-triggered.
            Log.d(TAG, "Running from DSU, disabling for reboot-to-stock");
            try {
                dsm.setEnable(false, false);
            } catch (Exception e) {
                Log.e(TAG, "Failed to disable DSU", e);
                update.setStatus(UpdateStatus.INSTALLATION_FAILED);
                mUpdaterController.notifyUpdateChange(mDownloadId);
                return;
            }

            // Save the download ID so UpdaterService auto-triggers install after reboot.
            // Also set auto-reboot flag so the second reboot is automatic too.
            PreferenceManager.getDefaultSharedPreferences(mContext).edit()
                    .putString(Constants.PREF_PENDING_GSI_REBOOT, mDownloadId)
                    .putBoolean(Constants.PREF_GSI_AUTO_REBOOT_ON_COMPLETE, true)
                    .apply();

            Log.d(TAG, "DSU disabled, auto-rebooting to stock for install");

            // The user already consented to the two-reboot process in the
            // confirmation dialog. Reboot immediately — no manual action needed.
            PowerManager pm = mContext.getSystemService(PowerManager.class);
            pm.reboot(null);
            return;
        }

        long systemSize = update.getSystemSize();
        if (systemSize <= 0) {
            // Fallback: read GZIP ISIZE footer (last 4 bytes, little-endian uint32).
            // Only accurate for images <4GB.
            systemSize = readGzipOriginalSize(file);
            if (systemSize <= 0) {
                Log.e(TAG, "Cannot determine system image size");
                update.setStatus(UpdateStatus.INSTALLATION_FAILED);
                mUpdaterController.notifyUpdateChange(mDownloadId);
                return;
            }
            Log.w(TAG, "Using GZIP ISIZE fallback: " + systemSize);
        }

        final long finalSystemSize = systemSize;

        PreferenceManager.getDefaultSharedPreferences(mContext).edit()
                .putString(Constants.PREF_INSTALLING_GSI_ID, mDownloadId)
                .apply();

        update.setStatus(UpdateStatus.INSTALLING);
        mUpdaterController.notifyUpdateChange(mDownloadId);

        mInstallThread = new Thread(() -> doInstall(file, finalSystemSize));
        mInstallThread.start();
    }

    private void doInstall(File file, long systemSize) {
        DynamicSystemManager dsm = mContext.getSystemService(DynamicSystemManager.class);
        SharedMemory sharedMemory = null;
        ByteBuffer buffer = null;

        try {
            // Clean up any existing DSU installation.
            if (dsm.isInstalled()) {
                Log.d(TAG, "Removing existing DSU installation");
                dsm.remove();
            }

            // Check available space (system image + ~512MB overhead).
            long requiredSpace = systemSize + (512L * 1024 * 1024);
            File dataDir = new File("/data");
            long freeSpace = dataDir.getFreeSpace();
            if (freeSpace < requiredSpace) {
                Log.e(TAG, "Insufficient space: need " + requiredSpace + ", have " + freeSpace);
                failInstallation("Insufficient storage space for GSI update");
                return;
            }

            if (mCancelled) {
                cancelInstallation();
                return;
            }

            // Start DSU installation.
            Log.d(TAG, "Starting DSU installation, partition: " + DSU_PARTITION_NAME);
            if (!dsm.startInstallation(DSU_PARTITION_NAME)) {
                Log.e(TAG, "Failed to start DSU installation");
                failInstallation("Failed to start DSU installation");
                return;
            }

            // Create read-only system partition. Returns Pair<status, Session>.
            Log.d(TAG, "Creating system partition, size: " + systemSize);
            Pair<Integer, DynamicSystemManager.Session> result =
                    dsm.createPartition("system", systemSize, true);
            if (result.first != IGsiService.INSTALL_OK || result.second == null) {
                Log.e(TAG, "Failed to create system partition, status: " + result.first);
                dsm.remove();
                failInstallation("Failed to create system partition");
                return;
            }
            DynamicSystemManager.Session session = result.second;

            if (mCancelled) {
                dsm.remove();
                cancelInstallation();
                return;
            }

            // Allocate shared memory for streaming.
            sharedMemory = SharedMemory.create("gsi_update", SHARED_MEM_SIZE);
            buffer = sharedMemory.mapReadWrite();

            session.setAshmem(sharedMemory.getFdDup(), SHARED_MEM_SIZE);

            // Stream the gzipped image into DSU.
            long totalWritten = 0;
            long lastProgressUpdate = 0;
            byte[] readBuf = new byte[SHARED_MEM_SIZE];

            try (InputStream fis = new FileInputStream(file);
                 GZIPInputStream gzis = new GZIPInputStream(fis, SHARED_MEM_SIZE)) {

                int bytesRead;
                while ((bytesRead = gzis.read(readBuf)) != -1) {
                    if (mCancelled) {
                        dsm.remove();
                        cancelInstallation();
                        return;
                    }

                    buffer.position(0);
                    buffer.put(readBuf, 0, bytesRead);

                    if (!session.submitFromAshmem(bytesRead)) {
                        Log.e(TAG, "Failed to submit data to DSU session");
                        dsm.remove();
                        failInstallation("Failed to write data to system partition");
                        return;
                    }

                    totalWritten += bytesRead;

                    // Throttle progress updates.
                    long now = SystemClock.elapsedRealtime();
                    if (now - lastProgressUpdate > MAX_REPORT_INTERVAL_MS) {
                        lastProgressUpdate = now;
                        int progress = (int) (totalWritten * 100 / systemSize);
                        progress = Math.min(progress, 99);
                        Update update = mUpdaterController.getActualUpdate(mDownloadId);
                        if (update != null) {
                            update.setInstallProgress(progress);
                            mUpdaterController.notifyInstallProgress(mDownloadId);
                        }
                    }
                }
            }

            Log.d(TAG, "Streamed " + totalWritten + " bytes (expected " + systemSize + ")");

            if (mCancelled) {
                dsm.remove();
                cancelInstallation();
                return;
            }

            // Finalize the partition and installation.
            if (!dsm.closePartition()) {
                Log.e(TAG, "Failed to close partition");
                dsm.remove();
                failInstallation("Failed to finalize system partition");
                return;
            }

            if (!dsm.finishInstallation()) {
                Log.e(TAG, "Failed to finish DSU installation");
                dsm.remove();
                failInstallation("Failed to finish DSU installation");
                return;
            }

            // Enable persistent DSU boot (not one-shot).
            dsm.setEnable(true, false);

            Log.d(TAG, "GSI update installed successfully via DSU");

            SharedPreferences prefs =
                    PreferenceManager.getDefaultSharedPreferences(mContext);
            boolean autoReboot = prefs.getBoolean(
                    Constants.PREF_GSI_AUTO_REBOOT_ON_COMPLETE, false);

            if (autoReboot) {
                // Two-phase flow: user already consented to both reboots.
                // Clean up and reboot directly into the new DSU image.
                prefs.edit()
                        .remove(Constants.PREF_INSTALLING_GSI_ID)
                        .remove(Constants.PREF_GSI_AUTO_REBOOT_ON_COMPLETE)
                        .apply();
                Log.d(TAG, "Auto-rebooting into new DSU image");
                PowerManager pm = mContext.getSystemService(PowerManager.class);
                pm.reboot(null);
            } else {
                // Normal (first-time) install: show reboot button.
                prefs.edit()
                        .putString(Constants.PREF_NEEDS_REBOOT_ID, mDownloadId)
                        .remove(Constants.PREF_INSTALLING_GSI_ID)
                        .apply();

                Update update = mUpdaterController.getActualUpdate(mDownloadId);
                if (update != null) {
                    update.setInstallProgress(100);
                    update.setStatus(UpdateStatus.INSTALLED);
                    mUpdaterController.notifyUpdateChange(mDownloadId);
                }
            }

        } catch (IOException e) {
            Log.e(TAG, "I/O error during GSI installation", e);
            try {
                dsm.remove();
            } catch (Exception ignored) {
            }
            failInstallation("I/O error: " + e.getMessage());
        } catch (ErrnoException e) {
            Log.e(TAG, "SharedMemory error during GSI installation", e);
            try {
                dsm.remove();
            } catch (Exception ignored) {
            }
            failInstallation("Memory error: " + e.getMessage());
        } finally {
            if (buffer != null) {
                SharedMemory.unmap(buffer);
            }
            if (sharedMemory != null) {
                sharedMemory.close();
            }
        }
    }

    private void failInstallation(String reason) {
        Log.e(TAG, "Installation failed: " + reason);
        PreferenceManager.getDefaultSharedPreferences(mContext).edit()
                .remove(Constants.PREF_INSTALLING_GSI_ID)
                .remove(Constants.PREF_GSI_AUTO_REBOOT_ON_COMPLETE)
                .apply();

        Update update = mUpdaterController.getActualUpdate(mDownloadId);
        if (update != null) {
            update.setInstallProgress(0);
            update.setStatus(UpdateStatus.INSTALLATION_FAILED);
            mUpdaterController.notifyUpdateChange(mDownloadId);
        }
    }

    private void cancelInstallation() {
        Log.d(TAG, "GSI installation cancelled");
        PreferenceManager.getDefaultSharedPreferences(mContext).edit()
                .remove(Constants.PREF_INSTALLING_GSI_ID)
                .remove(Constants.PREF_GSI_AUTO_REBOOT_ON_COMPLETE)
                .apply();

        Update update = mUpdaterController.getActualUpdate(mDownloadId);
        if (update != null) {
            update.setInstallProgress(0);
            update.setStatus(UpdateStatus.INSTALLATION_CANCELLED);
            mUpdaterController.notifyUpdateChange(mDownloadId);
        }
    }

    void cancel() {
        mCancelled = true;
    }

    /**
     * Read the GZIP ISIZE footer to determine the original uncompressed size.
     * This is the last 4 bytes of the gzip file, stored as little-endian uint32.
     * Only accurate for files <4GB (wraps for larger).
     */
    private static long readGzipOriginalSize(File gzipFile) {
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(gzipFile, "r")) {
            if (raf.length() < 4) {
                return -1;
            }
            raf.seek(raf.length() - 4);
            byte[] buf = new byte[4];
            raf.readFully(buf);
            return ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN).getInt() & 0xFFFFFFFFL;
        } catch (IOException e) {
            Log.e(TAG, "Failed to read GZIP ISIZE", e);
            return -1;
        }
    }
}
