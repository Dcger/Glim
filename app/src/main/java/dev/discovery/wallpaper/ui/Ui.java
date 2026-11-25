package dev.discovery.wallpaper.ui;

import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.UUID;

public final class Ui {
    public static final int BACKGROUND = 0xff111612;
    static final int PANEL = 0xff1e2720;
    public static final int PREVIEW = 0xff29342a;
    public static final int ACCENT = 0xffd5ee92;
    public static final int TEXT = 0xfff4f5eb;
    public static final int MUTED = 0xffb5c3b3;
    public static final int OBSERVED = 0xffffd47c;
    public static final int DELETE_ACCENT = 0xffffb5aa;
    public static final String TOPIC_NOTES_HINT =
            "（可选）描述任何和主题相关的补充信息，如知识了解程度、本地周围生态、个人兴趣等";

    private final MainActivity activity;

    Ui(MainActivity activity) {
        this.activity = activity;
    }

    public void guideToButton(LinearLayout parent, String message, boolean alignRight) {
        LinearLayout guide = row();
        top(parent, guide, 2);
        if (alignRight) equal(guide, new View(activity), 0);
        LinearLayout callout = column();
        equal(guide, callout, alignRight ? 8 : 0);
        gapText(callout, "↑", 18, ACCENT, true, 0).setGravity(Gravity.CENTER);
        TextView hint = gapText(callout, message, 13, ACCENT, false, 0);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(dp(8), dp(6), dp(8), dp(6));
        hint.setBackground(rounded(PREVIEW, 10));
    }

    void guideToTab(LinearLayout header, String message, int tab, int count) {
        LinearLayout arrows = row();
        top(header, arrows, 0);
        for (int i = 0; i < count; i++) {
            TextView arrow = new TextView(activity);
            if (i == tab) arrow.setText("↑");
            arrow.setTextSize(18);
            arrow.setTextColor(ACCENT);
            arrow.setGravity(Gravity.CENTER);
            equal(arrows, arrow, i == 0 ? 0 : 6);
        }
        TextView hint = gapText(header, message, 13, ACCENT, false, 0);
        hint.setGravity(Gravity.CENTER);
    }

    public AlertDialog dialog(String title, LinearLayout fields) {
        ScrollView container = new ScrollView(activity);
        container.addView(fields);
        return new AlertDialog.Builder(activity).setTitle(title).setView(container)
                .setNegativeButton("取消", null).create();
    }

    public static boolean missing(EditText input, String message) {
        input.setError(message);
        input.requestFocus();
        return false;
    }

    public LinearLayout fields() {
        LinearLayout layout = column();
        layout.setPadding(dp(22), dp(6), dp(22), dp(8));
        return layout;
    }

    public EditText field(LinearLayout box, String hint, String value, int length, int lines) {
        EditText input = new EditText(activity);
        input.setHint(hint);
        input.setText(value);
        input.setTextSize(16);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(length)});
        input.setInputType(InputType.TYPE_CLASS_TEXT | (lines > 1
                ? InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                : InputType.TYPE_TEXT_FLAG_CAP_SENTENCES));
        input.setMinLines(lines);
        if (lines == 1) input.setSingleLine(true);
        top(box, input, 6);
        return input;
    }

    public EditText labeledField(LinearLayout box, String label, String value, int length, int lines) {
        TextView caption = gapText(box, label, 14, TEXT, true, 9);
        EditText input = field(box, "", value, length, lines);
        input.setId(View.generateViewId());
        caption.setLabelFor(input.getId());
        return input;
    }

    public static String value(EditText field) {
        return field.getText().toString().trim();
    }

    public static String uuid() {
        return UUID.randomUUID().toString();
    }

    public static String cardStatusText(boolean observed, boolean favorite) {
        if (observed) return favorite ? "已观察 · 已收藏" : "已观察";
        return favorite ? "已收藏" : "";
    }

    public LinearLayout panel(LinearLayout parent, int margin) {
        LinearLayout box = column();
        box.setBackground(rounded(PANEL, 20));
        box.setPadding(dp(16), dp(15), dp(16), dp(15));
        top(parent, box, margin);
        return box;
    }

    public TextView gapText(LinearLayout parent, String value, int sp, int color, boolean bold, int margin) {
        TextView label = new TextView(activity);
        label.setText(value);
        label.setTextColor(color);
        label.setTextSize(sp);
        label.setLineSpacing(dp(2), 1);
        label.setTypeface(Typeface.create("sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
        top(parent, label, margin);
        return label;
    }

    public Button button(String label, Runnable action, boolean primary) {
        return button(label, action, primary ? ACCENT : PREVIEW, primary ? BACKGROUND : TEXT);
    }

    public Button navigationButton(String label, int iconResource, Runnable action, boolean active) {
        Button button = button(label, action, active);
        Drawable icon = activity.getDrawable(iconResource).mutate();
        icon.setTint(active ? BACKGROUND : TEXT);
        icon.setBounds(0, 0, dp(18), dp(18));
        button.setCompoundDrawables(null, icon, null, null);
        button.setCompoundDrawablePadding(dp(3));
        button.setPadding(dp(3), dp(5), dp(3), dp(5));
        button.setSelected(active);
        return button;
    }

    public Button cardAction(String label, int ink, Runnable action) {
        Button view = button(label, action, false);
        view.setTextColor(ink);
        view.setBackground(new RippleDrawable(ColorStateList.valueOf(0x45899f78),
                rounded(Color.TRANSPARENT, 13), rounded(0xffffffff, 13)));
        return view;
    }

    public Button button(String label, Runnable action, int fill, int ink) {
        Button view = new Button(activity);
        view.setText(label);
        view.setTextSize(14);
        view.setAllCaps(false);
        view.setTextColor(new ColorStateList(new int[][]{{-android.R.attr.state_enabled}, {}},
                new int[]{fill == DELETE_ACCENT ? 0xff5c403b : 0xff6b7c6b, ink}));
        view.setMinimumHeight(dp(48));
        view.setPadding(dp(12), dp(8), dp(12), dp(8));
        view.setStateListAnimator(null);
        view.setBackground(new RippleDrawable(ColorStateList.valueOf(0x45899f78),
                rounded(fill == DELETE_ACCENT
                        ? new ColorStateList(new int[][]{{-android.R.attr.state_enabled}, {}},
                                new int[]{0xff46332e, fill}) : ColorStateList.valueOf(fill), 13), null));
        view.setOnClickListener(v -> action.run());
        return view;
    }

    public Switch toggle(String label, boolean on) {
        Switch view = new Switch(activity);
        view.setText(label);
        view.setTextSize(16);
        view.setTextColor(TEXT);
        view.setMinimumHeight(dp(48));
        view.setChecked(on);
        return view;
    }

    public GradientDrawable rounded(int color, int radius) {
        return rounded(ColorStateList.valueOf(color), radius);
    }

    public GradientDrawable rounded(ColorStateList color, int radius) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(dp(radius));
        return background;
    }

    public LinearLayout column() {
        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    public LinearLayout row() {
        return new LinearLayout(activity);
    }

    public void top(LinearLayout parent, View child, int margin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(margin);
        parent.addView(child, params);
    }

    public void equal(LinearLayout parent, View child, int margin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
        params.setMarginStart(dp(margin));
        parent.addView(child, params);
    }

    public int dp(int size) {
        return Math.round(size * activity.getResources().getDisplayMetrics().density);
    }

    public void notice(String message) {
        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show();
    }
}
