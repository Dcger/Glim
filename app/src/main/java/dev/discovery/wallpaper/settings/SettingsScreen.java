package dev.discovery.wallpaper.settings;

import static dev.discovery.wallpaper.ui.Ui.ACCENT;
import static dev.discovery.wallpaper.ui.Ui.MUTED;

import android.app.AlertDialog;
import android.util.Log;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import dev.discovery.wallpaper.lockscreen.WallpaperDelivery;
import dev.discovery.wallpaper.model.Discovery;
import dev.discovery.wallpaper.storage.Store;
import dev.discovery.wallpaper.ui.MainActivity;
import dev.discovery.wallpaper.ui.Ui;

import java.io.IOException;

public final class SettingsScreen {
    private final MainActivity activity;
    private final Ui ui;
    private TextView rotationStatus;

    public SettingsScreen(MainActivity activity, Ui ui) {
        this.activity = activity;
        this.ui = ui;
    }

    public void clearViews() {
        rotationStatus = null;
    }

    public void render(LinearLayout content) {
        ui.top(content, ui.button("发现浏览顺序 · " + modeName(activity.state().randomBrowse),
                () -> chooseMode(true), false), 12);
        LinearLayout settings = ui.panel(content, 12);
        Switch automatic = ui.toggle("自动更换锁屏", activity.state().automatic);
        settings.addView(automatic);
        automatic.setOnCheckedChangeListener((control, enabled) -> {
            if (activity.commit(value -> value.configureRotation(enabled, value.minutes, System.currentTimeMillis()))) activity.refresh(true);
            else {
                control.setOnCheckedChangeListener(null);
                control.setChecked(!enabled);
                activity.refresh(true);
            }
        });
        ui.top(settings, ui.button("轮换间隔 · " + activity.state().minutes + " 分钟", this::interval, false), 8);
        ui.top(settings, ui.button("锁屏轮换方式 · " + modeName(activity.state().randomRotation), () -> chooseMode(false), false), 8);
        if (activity.state().automatic) {
            ui.gapText(settings, activity.scheduleOk() ? "省电时换图可能延后。若在最近任务清理 App 后停止，重新打开即可恢复排程。"
                    : "系统拒绝了排程。可关闭自动更换后再开启。", 13, MUTED, false, 8);
        }
        rotationStatus = ui.gapText(settings, "", 13, ACCENT, false, 8);
        showRotationStatus(activity.state());
    }

    public void updateRotationStatus() {
        if (rotationStatus == null) return;
        try {
            showRotationStatus(Store.read(activity));
        } catch (IOException error) {
            Log.w(WallpaperDelivery.TAG, "Could not read rotation status", error);
        }
    }

    private void showRotationStatus(Discovery.State latest) {
        rotationStatus.setVisibility(latest.status.isEmpty() ? View.GONE : View.VISIBLE);
        rotationStatus.setText(latest.status);
    }


    private static String modeName(boolean random) {
        return random ? "随机" : "顺序";
    }

    private void chooseMode(boolean browse) {
        boolean current = browse ? activity.state().randomBrowse : activity.state().randomRotation;
        new AlertDialog.Builder(activity).setTitle(browse ? "发现浏览顺序" : "锁屏轮换方式")
                .setSingleChoiceItems(new String[]{"顺序", "随机"}, current ? 1 : 0,
                        (dialog, index) -> {
                            boolean random = index == 1;
                            if (random == current) {
                                dialog.dismiss();
                                return;
                            }
                            if (activity.commit(latest -> {
                                if (browse) {
                                    latest.randomBrowse = random;
                                } else {
                                    latest.randomRotation = random;
                                    latest.shuffledLocks.clear();
                                }
                            })) {
                                if (browse) activity.discovery.browseModeChanged();
                                dialog.dismiss();
                                activity.refresh(true);
                            }
                        })
                .setNegativeButton("取消", null).show();
    }

    private void interval() {
        String[] items = new String[Discovery.INTERVALS.length];
        int selected = -1;
        for (int i = 0; i < items.length; i++) {
            items[i] = Discovery.INTERVALS[i] + " 分钟";
            if (Discovery.INTERVALS[i] == activity.state().minutes) selected = i;
        }
        new AlertDialog.Builder(activity).setTitle("轮换间隔")
                .setSingleChoiceItems(items, selected, (dialog, index) -> {
                    if (activity.commit(value -> value.configureRotation(value.automatic, Discovery.INTERVALS[index],
                            System.currentTimeMillis()))) {
                        dialog.dismiss();
                        activity.refresh(true);
                    }
                }).setNegativeButton("取消", null).show();
    }
}
