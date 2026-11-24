package dev.discovery.wallpaper.backup;

import static android.app.Activity.RESULT_OK;
import static dev.discovery.wallpaper.ui.MainActivity.PICK_DATA_BACKUP;
import static dev.discovery.wallpaper.ui.MainActivity.SAVE_CARD_MEDIA;
import static dev.discovery.wallpaper.ui.MainActivity.SAVE_DATA_EXPORT;
import static dev.discovery.wallpaper.ui.Ui.TEXT;

import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.widget.LinearLayout;

import dev.discovery.wallpaper.cards.CardMediaExport;
import dev.discovery.wallpaper.lockscreen.RecentWallpapers;
import dev.discovery.wallpaper.lockscreen.RotationAlarm;
import dev.discovery.wallpaper.lockscreen.WallpaperDelivery;
import dev.discovery.wallpaper.model.Discovery;
import dev.discovery.wallpaper.sources.SourcePaths;
import dev.discovery.wallpaper.storage.Store;
import dev.discovery.wallpaper.ui.MainActivity;
import dev.discovery.wallpaper.ui.Ui;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

public final class TransferScreen {
    private String pendingMediaExport = "";

    private final MainActivity activity;
    private final Ui ui;

    public TransferScreen(MainActivity activity, Ui ui) {
        this.activity = activity;
        this.ui = ui;
    }

    public void restoreState(Bundle saved) {
        pendingMediaExport = saved.getString("mediaExport", "");
    }

    public void saveState(Bundle out) {
        out.putString("mediaExport", pendingMediaExport);
    }

    public boolean usesUri(String uri) {
        return uri.equals(pendingMediaExport);
    }

    public void onDocumentResult(int request, int result, Intent data) {
        Uri uri = result == RESULT_OK && data != null ? data.getData() : null;
        switch (request) {
            case PICK_DATA_BACKUP -> {
                if (uri != null) confirmDataRestore(uri);
            }
            case SAVE_DATA_EXPORT -> {
                if (uri != null) saveDataExport(uri);
            }
            case SAVE_CARD_MEDIA -> {
                String media = pendingMediaExport;
                pendingMediaExport = "";
                if (media.isEmpty()) return;
                if (uri == null) {
                    RecentWallpapers.discardExport(activity, media);
                } else {
                    activity.cards.restoreCardUi();
                    saveCardMedia(media, uri);
                }
            }
        }
    }

    private void confirmDataRestore(Uri uri) {
        new AlertDialog.Builder(activity).setTitle("恢复 Glim 备份")
                .setMessage("恢复会替换这台手机上当前的主题、卡片、资料、轮换设置和最近锁屏记录。"
                        + "如果这台手机上也有要保留的数据，请先导出备份。")
                .setNegativeButton("取消", null)
                .setPositiveButton("恢复", (dialog, which) -> restoreData(uri)).show();
    }

    private void restoreData(Uri uri) {
        AlertDialog restoring = new AlertDialog.Builder(activity).setMessage("正在恢复备份…")
                .setCancelable(false).show();
        Context app = activity.getApplicationContext();
        activity.ioWorker().execute(() -> {
            DataRestore.Result result;
            try {
                result = DataRestore.read(app, uri);
                RotationAlarm.sync(app, Store.read(app));
            } catch (IOException | RuntimeException error) {
                Log.w(WallpaperDelivery.TAG, "Could not restore app data", error);
                activity.runOnUiThread(() -> {
                    if (activity.isDestroyed() || activity.isFinishing()) return;
                    restoring.dismiss();
                    activity.imports.importError(error);
                });
                return;
            }
            activity.runOnUiThread(() -> {
                if (activity.isDestroyed() || activity.isFinishing()) return;
                restoring.dismiss();
                activity.cards.stopAudio();
                activity.discovery.resetPreview();
                activity.refresh(false);
                String message = "已恢复 " + result.topics() + " 个主题、" + result.cards() + " 张卡片。";
                if (result.missing() > 0) message += "备份导出时有 " + result.missing() + " 个附件未能读取。";
                new AlertDialog.Builder(activity).setTitle("备份已恢复").setMessage(message)
                        .setPositiveButton("确定", null).show();
            });
        });
    }

    public void chooseDataExport() {
        AlertDialog checking = new AlertDialog.Builder(activity).setMessage("正在检查资料路径…")
                .setCancelable(false).show();
        Context app = activity.getApplicationContext();
        activity.ioWorker().execute(() -> {
            try {
                List<Discovery.Topic> topics = new ArrayList<>(Store.read(app).topics.values());
                List<Discovery.Source> sources = new ArrayList<>();
                for (Discovery.Topic topic : topics) sources.addAll(topic.sources);
                Map<Discovery.Source, String> problems = SourcePaths.check(app, sources);
                activity.runOnUiThread(() -> {
                    if (activity.isDestroyed() || activity.isFinishing()) return;
                    checking.dismiss();
                    confirmDataExport(topics, problems);
                });
            } catch (IOException | RuntimeException error) {
                activity.runOnUiThread(() -> {
                    if (activity.isDestroyed() || activity.isFinishing()) return;
                    checking.dismiss();
                    ui.notice("无法检查资料路径，请重试。");
                });
            }
        });
    }

    private void confirmDataExport(List<Discovery.Topic> topics, Map<Discovery.Source, String> problems) {
        LinearLayout fields = ui.fields();
        String summary = problems.isEmpty() ? "备份将连同可读取的资料原文件一同导出。"
                : "有 " + problems.size() + " 份资料的路径需要检查。可点感叹号查看原因，或点资料名称编辑。"
                + "继续导出时，备份只包含可读取的原文件。";
        ui.gapText(fields, summary, 14, TEXT, false, 0);
        AlertDialog confirmation = ui.dialog("导出数据", fields);
        confirmation.setButton(AlertDialog.BUTTON_POSITIVE, problems.isEmpty() ? "继续导出" : "仍然导出",
                (dialog, which) -> createDataExport());
        for (Discovery.Topic topic : topics) {
            if (topic.sources.stream().noneMatch(problems::containsKey)) continue;
            ui.gapText(fields, topic.name, 15, TEXT, true, 12);
            for (Discovery.Source source : topic.sources) {
                String reason = problems.get(source);
                if (reason == null) continue;
                LinearLayout line = activity.sources.addSourceRow(fields, source, null, () -> {
                    confirmation.dismiss();
                    activity.sources.editSource(topic.id, source, null, this::chooseDataExport);
                });
                activity.sources.addSourceWarning(line, source, reason);
            }
        }
        confirmation.show();
    }

    private void createDataExport() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/zip")
                .addCategory(Intent.CATEGORY_OPENABLE)
                .putExtra(Intent.EXTRA_TITLE, "Glim-data-" + new java.text.SimpleDateFormat(
                        "yyyyMMdd-HHmmss", java.util.Locale.ROOT).format(new java.util.Date()) + ".zip");
        try {
            activity.startActivityForResult(intent, SAVE_DATA_EXPORT);
        } catch (ActivityNotFoundException error) {
            ui.notice("系统文件选择器不可用");
        }
    }

    private void saveDataExport(Uri destination) {
        AlertDialog saving = new AlertDialog.Builder(activity).setMessage("正在导出主题、卡片和附件…")
                .setCancelable(false).show();
        Context app = activity.getApplicationContext();
        activity.ioWorker().execute(() -> {
            String message;
            try {
                DataExport.Result result = DataExport.write(app, destination);
                message = "已导出 " + result.topics() + " 个主题、" + result.cards()
                        + " 张卡片、" + result.files() + " 个文件。";
                if (result.missing() > 0) {
                    message += "\n有 " + result.missing() + " 个原文件无法读取，详情见 ZIP 中的 manifest.json。";
                }
            } catch (IOException | RuntimeException error) {
                Log.w(WallpaperDelivery.TAG, "Could not export app data", error);
                message = "导出失败，请检查可用空间和保存位置后重试。";
            }
            String summary = message;
            activity.runOnUiThread(() -> {
                if (activity.isDestroyed() || activity.isFinishing()) return;
                saving.dismiss();
                new AlertDialog.Builder(activity).setTitle("数据导出").setMessage(summary)
                        .setPositiveButton("确定", null).show();
            });
        });
    }

    public void exportCardMedia(String title, String media, boolean photo) {
        if (media.isEmpty()) return;
        try {
            Intent intent = CardMediaExport.destination(activity, title, media, photo);
            pendingMediaExport = media;
            activity.startActivityForResult(intent, SAVE_CARD_MEDIA);
        } catch (ActivityNotFoundException error) {
            pendingMediaExport = "";
            RecentWallpapers.discardExport(activity, media);
            ui.notice("系统文件选择器不可用");
        } catch (RuntimeException error) {
            pendingMediaExport = "";
            RecentWallpapers.discardExport(activity, media);
            ui.notice("无法读取卡片媒体。");
        }
    }

    public void exportRecentLock(Discovery.LockEntry entry) {
        AlertDialog preparing = new AlertDialog.Builder(activity).setMessage("正在准备锁屏图片…")
                .setCancelable(false).show();
        Context app = activity.getApplicationContext();
        activity.ioWorker().execute(() -> {
            try {
                String image = RecentWallpapers.prepareExport(app, entry).toString();
                activity.runOnUiThread(() -> {
                    if (activity.isDestroyed() || activity.isFinishing()) {
                        RecentWallpapers.discardExport(app, image);
                        return;
                    }
                    preparing.dismiss();
                    exportCardMedia(entry.title() + "-锁屏", image, true);
                });
            } catch (IOException | RuntimeException error) {
                Log.w(WallpaperDelivery.TAG, "Could not prepare lock wallpaper export", error);
                activity.runOnUiThread(() -> {
                    if (activity.isDestroyed()) return;
                    preparing.dismiss();
                    ui.notice("无法保存这条记录的锁屏图片");
                });
            }
        });
    }

    private void saveCardMedia(String media, Uri destination) {
        AlertDialog saving = new AlertDialog.Builder(activity).setMessage("正在保存媒体…")
                .setCancelable(false).show();
        Context app = activity.getApplicationContext();
        activity.ioWorker().execute(() -> {
            String result = "已保存到所选位置";
            try {
                CardMediaExport.copy(app, media, destination);
            } catch (IOException | RuntimeException error) {
                Log.w(WallpaperDelivery.TAG, "Could not export card media", error);
                result = "无法保存媒体，请检查文件和保存位置。";
            } finally {
                RecentWallpapers.discardExport(app, media);
            }
            String message = result;
            activity.runOnUiThread(() -> {
                if (activity.isDestroyed()) return;
                saving.dismiss();
                ui.notice(message);
            });
        });
    }
}
