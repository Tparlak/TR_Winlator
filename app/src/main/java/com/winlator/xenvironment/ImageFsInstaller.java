package com.winlator.xenvironment;

import android.content.Context;

import androidx.appcompat.app.AppCompatActivity;

import com.winlator.MainActivity;
import com.winlator.R;
import com.winlator.SettingsFragment;
import com.winlator.container.Container;
import com.winlator.container.ContainerManager;
import com.winlator.core.AppUtils;
import com.winlator.core.DownloadProgressDialog;
import com.winlator.core.FileUtils;
import com.winlator.core.PreloaderDialog;
import com.winlator.core.TarCompressorUtils;
import com.winlator.core.WineInfo;
import android.content.DialogInterface;
import androidx.appcompat.app.AlertDialog;
import android.os.Build;
import android.os.StatFs;
import android.text.Html;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

public abstract class ImageFsInstaller {
    public static final byte LATEST_VERSION = 17;

    private static void resetContainerImgVersions(Context context) {
        ContainerManager manager = new ContainerManager(context);
        for (Container container : manager.getContainers()) {
            String imgVersion = container.getExtra("imgVersion");
            String wineVersion = container.getWineVersion();
            if (!imgVersion.isEmpty() && WineInfo.isMainWineVersion(wineVersion) && Short.parseShort(imgVersion) <= 5) {
                container.putExtra("wineprefixNeedsUpdate", "t");
            }

            container.putExtra("imgVersion", null);
            container.saveData();
        }
    }

    public static void installFromAssets(final MainActivity activity) {
        if (!hasEnoughStorage(activity)) return;

        long assetSize = FileUtils.getSize(activity, "imagefs.txz");
        Log.d("TR_Winlator", "imagefs.txz asset boyutu: " + assetSize + " byte");

        if (assetSize == 0) {
            Log.e("TR_Winlator", "imagefs.txz bulunamadı (boyut 0). Assets klasörünü kontrol edin.");
            showMissingRootFSDialog(activity);
            return;
        }

        // LFS pointer ve Bozuk dosya kontrolü: gerçek dosya en az 50MB olmalı
        try {
            android.content.res.AssetFileDescriptor afd = activity.getAssets().openFd("imagefs.txz");
            long realSize = afd.getLength();
            Log.d("TR_Winlator", "imagefs.txz gerçek boyut (openFd): " + realSize + " byte");
            if (realSize < 50_000_000L) {
                Log.e("TR_Winlator", "imagefs.txz boyutu çok küçük (" + realSize + " byte). Dosya bozuk veya LFS pointer!");
                activity.runOnUiThread(() -> AppUtils.showToast(activity,
                    "HATA: Sistem dosyası (190MB+) eksik veya bozuk (" + realSize + " byte)."));
                return;
            }
        } catch (IOException e) {
            Log.e("TR_Winlator", "AssetFileDescriptor okuma hatası: " + e.getMessage());
        }

        AppUtils.keepScreenOn(activity);
        ImageFs imageFs = ImageFs.find(activity);
        final File rootDir = imageFs.getRootDir();

        SettingsFragment.resetBox86_64Version(activity);

        final DownloadProgressDialog dialog = new DownloadProgressDialog(activity);
        dialog.show(R.string.installing_system_files);
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                clearRootDir(rootDir);
                final byte compressionRatio = 22;
                final long contentLength = (long)(assetSize * (100.0f / compressionRatio));
                AtomicLong totalSizeRef = new AtomicLong();

                Log.d("TR_Winlator", "imagefs.txz çıkarma başlıyor. Tahmini boyut: " + contentLength + " byte");

                boolean success = TarCompressorUtils.extract(TarCompressorUtils.Type.XZ, activity, "imagefs.txz", rootDir, (file, size) -> {
                    if (size > 0) {
                        long totalSize = totalSizeRef.addAndGet(size);
                        final int progress = (int)(((float)totalSize / contentLength) * 100);
                        activity.runOnUiThread(() -> dialog.setProgress(progress));
                        if (file != null) Log.d("TR_Winlator_INSTALL", "Çıkarılıyor: " + file.getName() + " | İlerleme: " + progress + "% | Boyut: " + size);
                    }
                    return file;
                });

                if (success) {
                    Log.d("TR_Winlator", "imagefs.txz başarıyla çıkarıldı.");
                    imageFs.createImgVersionFile(LATEST_VERSION);
                    resetContainerImgVersions(activity);
                    markInstallationComplete(activity);
                }
                else {
                    Log.e("TR_Winlator", "imagefs.txz çıkarılamadı. Boyut: " + assetSize + " byte");
                    activity.runOnUiThread(() -> showInstallErrorDialog(activity, "Çıkarma başarısız. Lütfen uygulamanın verilerini silip APK'yı baştan kurun."));
                }

            } catch (OutOfMemoryError oom) {
                Log.e("TR_Winlator", "BELLEK HATASI: " + oom.getMessage());
                activity.runOnUiThread(() -> showInstallErrorDialog(activity, "Yetersiz bellek (RAM). Arka plan uygulamalarını kapatıp baştan deneyin."));
            } catch (Throwable e) {
                Log.e("TR_Winlator", "BİLİNMEYEN HATA: " + e.getMessage());
                e.printStackTrace();
                activity.runOnUiThread(() -> showInstallErrorDialog(activity, "Sistem hatası: " + e.getMessage()));
            } finally {
                dialog.closeOnUiThread();
            }
        });
    }

    private static void showMissingRootFSDialog(final MainActivity activity) {
        Log.w("TR_Winlator", "showMissingRootFSDialog: imagefs.txz APK assets içinde bulunamadı.");
        String message = "<b>Sistem Dosyaları Eksik!</b><br><br>" +
                "Temel sistem dosyası (imagefs.txz) APK assets klasöründe bulunamadı.<br><br>" +
                "1. Derlemeden önce <b>imagefs.txz</b> dosyasını <i>app/src/main/assets/</i> klasörüne koyduğunuzdan emin olun.<br>" +
                "2. Veya OBB dosyasını cihazınızda <i>/sdcard/Android/obb/com.winlator/</i> konumuna yerleştirin.<br><br>" +
                "Arayüzü keşfetmek için <b>Taslak Dosya Sistemi</b> başlatmak ister misiniz? (Not: Gerçek RootFS olmadan container'lar çalışmayabilir).";

        AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                .setTitle("Kurulum Hatası")
                .setMessage(Html.fromHtml(message, Html.FROM_HTML_MODE_LEGACY))
                .setCancelable(false)
                .setPositiveButton("Taslak Oluştur", (dialog, which) -> {
                    ImageFs imageFs = ImageFs.find(activity);
                    imageFs.createImgVersionFile(LATEST_VERSION);
                    AppUtils.showToast(activity, "Taslak RootFS başlatıldı.");
                    activity.recreate();
                })
                .setNegativeButton("Çıkış", (dialog, which) -> activity.finish());
        
        builder.show();
    }

    public static void installIfNeeded(final MainActivity activity) {
        if (!isInstallationComplete(activity)) {
            clearPartialInstallation(activity);
        }
        ImageFs imageFs = ImageFs.find(activity);
        if (!imageFs.isValid() || !isInstallationComplete(activity) || imageFs.getVersion() < LATEST_VERSION) installFromAssets(activity);
    }

    public static boolean isInstallationComplete(Context context) {
        File sentinel = new File(context.getFilesDir(), "imagefs/.installation_complete");
        return sentinel.exists();
    }

    public static void markInstallationComplete(Context context) {
        try {
            File sentinel = new File(context.getFilesDir(), "imagefs/.installation_complete");
            if (sentinel.getParentFile() != null) sentinel.getParentFile().mkdirs();
            sentinel.createNewFile();
        } catch (IOException e) {
            Log.e("TR_Winlator", "Sentinel oluşturulamadı: " + e.getMessage());
        }
    }

    public static void clearPartialInstallation(Context context) {
        File imageFsDir = new File(context.getFilesDir(), "imagefs");
        if (imageFsDir.exists()) {
            FileUtils.delete(imageFsDir);
            Log.d("TR_Winlator", "Yarım kurulum temizlendi, yeniden başlatılıyor...");
        }
    }

    private static boolean hasEnoughStorage(Context context) {
        StatFs stat = new StatFs(context.getFilesDir().getPath());
        long availableBytes = stat.getAvailableBlocksLong() * stat.getBlockSizeLong();
        long requiredBytes = 2L * 1024 * 1024 * 1024; // 2GB
        Log.d("TR_Winlator", "Mevcut alan: " + (availableBytes / 1024 / 1024) + " MB");
        if (availableBytes < requiredBytes) {
            final long mb = availableBytes / 1024 / 1024;
            showInstallErrorDialog(context, "Yetersiz depolama alanı. En az 2GB boş alan gerekli. Mevcut: " + mb + " MB");
            return false;
        }
        return true;
    }

    private static void showInstallErrorDialog(Context context, String message) {
        new AlertDialog.Builder(context)
            .setTitle("Kurulum Hatası")
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton("Tamam", null)
            .show();
    }

    private static void clearOptDir(File optDir) {
        File[] files = optDir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.getName().equals("installed-wine")) continue;
                FileUtils.delete(file);
            }
        }
    }

    private static void clearRootDir(File rootDir) {
        if (rootDir.isDirectory()) {
            File[] files = rootDir.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isDirectory()) {
                        String name = file.getName();
                        if (name.equals("home")) {
                            continue;
                        }
                    }
                    FileUtils.delete(file);
                }
            }
        }
        else rootDir.mkdirs();
    }

    public static void generateCompactContainerPattern(final AppCompatActivity activity) {
        AppUtils.keepScreenOn(activity);
        PreloaderDialog preloaderDialog = new PreloaderDialog(activity);
        preloaderDialog.show(R.string.loading);
        Executors.newSingleThreadExecutor().execute(() -> {
            File[] srcFiles, dstFiles;
            File rootDir = ImageFs.find(activity).getRootDir();
            File wineSystem32Dir = new File(rootDir, "/opt/wine/lib/wine/x86_64-windows");
            File wineSysWoW64Dir = new File(rootDir, "/opt/wine/lib/wine/i386-windows");

            File containerPatternDir = new File(activity.getCacheDir(), "container_pattern");
            FileUtils.delete(containerPatternDir);
            TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, activity, "container_pattern.tzst", containerPatternDir);

            File containerSystem32Dir = new File(containerPatternDir, ".wine/drive_c/windows/system32");
            File containerSysWoW64Dir = new File(containerPatternDir, ".wine/drive_c/windows/syswow64");

            dstFiles = containerSystem32Dir.listFiles();
            srcFiles = wineSystem32Dir.listFiles();

            ArrayList<String> system32Files = new ArrayList<>();
            ArrayList<String> syswow64Files = new ArrayList<>();

            for (File dstFile : dstFiles) {
                for (File srcFile : srcFiles) {
                    if (dstFile.getName().equals(srcFile.getName())) {
                        if (FileUtils.contentEquals(srcFile, dstFile)) system32Files.add(srcFile.getName());
                        break;
                    }
                }
            }

            dstFiles = containerSysWoW64Dir.listFiles();
            srcFiles = wineSysWoW64Dir.listFiles();

            for (File dstFile : dstFiles) {
                for (File srcFile : srcFiles) {
                    if (dstFile.getName().equals(srcFile.getName())) {
                        if (FileUtils.contentEquals(srcFile, dstFile)) syswow64Files.add(srcFile.getName());
                        break;
                    }
                }
            }

            try {
                JSONObject data = new JSONObject();

                JSONArray system32JSONArray = new JSONArray();
                for (String name : system32Files) {
                    FileUtils.delete(new File(containerSystem32Dir, name));
                    system32JSONArray.put(name);
                }
                data.put("system32", system32JSONArray);

                JSONArray syswow64JSONArray = new JSONArray();
                for (String name : syswow64Files) {
                    FileUtils.delete(new File(containerSysWoW64Dir, name));
                    syswow64JSONArray.put(name);
                }
                data.put("syswow64", syswow64JSONArray);

                FileUtils.writeString(new File(activity.getCacheDir(), "common_dlls.json"), data.toString());

                File outputFile = new File(activity.getCacheDir(), "container_pattern.tzst");
                FileUtils.delete(outputFile);
                TarCompressorUtils.compress(TarCompressorUtils.Type.ZSTD, new File(containerPatternDir, ".wine"), outputFile, 22);

                FileUtils.delete(containerPatternDir);
                preloaderDialog.closeOnUiThread();
            }
            catch (JSONException e) {}
        });
    }
}