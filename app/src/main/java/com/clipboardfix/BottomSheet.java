package com.clipboardfix;

import android.app.Activity;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * 通用居中弹卡：全屏遮罩 + 中间圆角卡片。
 * - cancelable=true 时点击遮罩（含被遮住的导航栏区域）关闭卡片；
 * - cancelable=false（如首次启动「您需了解」）时仅能通过底部按钮关闭，且遮罩吃掉所有触摸，
 *   导航栏自然不可点击。
 * - 支持多层叠放（为爱发电 → 捐赠码 / 捐赠名单）。
 */
public final class BottomSheet {

    private static final List<FrameLayout> STACK = new ArrayList<>();

    private BottomSheet() {
    }

    /** 关闭最上层弹卡；若有则返回 true（供 onBackPressed 拦截）。 */
    public static boolean dismissTop() {
        if (STACK.isEmpty()) return false;
        final FrameLayout root = STACK.remove(STACK.size() - 1);
        final View card = root.findViewById(R.id.sheetCard);
        final View scrim = root.findViewById(R.id.sheetScrim);
        card.animate().scaleX(0.96f).scaleY(0.96f).alpha(0f).setDuration(150).start();
        scrim.animate().alpha(0f).setDuration(180).withEndAction(() -> {
            ViewGroup parent = (ViewGroup) root.getParent();
            if (parent != null) parent.removeView(root);
        }).start();
        return true;
    }

    public static boolean hasOpen() {
        return !STACK.isEmpty();
    }

    /**
     * @param title      标题
     * @param content    内容视图（会被放进统一滚动区）
     * @param cancelable 是否允许点击遮罩关闭
     * @param actionText 底部操作按钮文案（null 表示不显示）
     * @param onAction   操作按钮点击回调（执行后自动关闭）
     */
    public static void show(Activity activity, String title, View content,
                            boolean cancelable, String actionText, Runnable onAction) {
        FrameLayout contentRoot = (FrameLayout) activity.findViewById(android.R.id.content);
        LayoutInflater inf = LayoutInflater.from(activity);
        FrameLayout root = (FrameLayout) inf.inflate(R.layout.bottom_sheet, contentRoot, false);

        final View scrim = root.findViewById(R.id.sheetScrim);
        final View card = root.findViewById(R.id.sheetCard);
        TextView tvTitle = root.findViewById(R.id.sheetTitle);
        LinearLayout body = root.findViewById(R.id.sheetBody);
        LinearLayout actions = root.findViewById(R.id.sheetActions);
        final ScrollView scroll = root.findViewById(R.id.sheetScroll);

        float density = activity.getResources().getDisplayMetrics().density;
        tvTitle.setText(title);
        body.addView(content);

        if (actionText != null && onAction != null) {
            TextView btn = new TextView(activity);
            btn.setText(actionText);
            btn.setTextColor(0xFFFFFFFF);
            btn.setTextSize(16f);
            btn.setGravity(Gravity.CENTER);
            int padV = Math.round(14 * density);
            btn.setPadding(0, padV, 0, padV);
            btn.setBackgroundResource(R.drawable.btn_accent_bg);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            btn.setLayoutParams(lp);
            btn.setOnClickListener(v -> {
                onAction.run();
                dismissTop();
            });
            actions.addView(btn);
            actions.setVisibility(View.VISIBLE);
        }

        scrim.setOnClickListener(v -> {
            if (cancelable) dismissTop();
        });

        contentRoot.addView(root);
        STACK.add(root);

        // 居中淡入 + 轻微放大
        scrim.setAlpha(0f);
        card.setAlpha(0f);
        card.setScaleX(0.96f);
        card.setScaleY(0.96f);
        card.post(() -> {
            // 内容超高时限制滚动区高度（卡片最多占屏 80%）
            int screenH = activity.getResources().getDisplayMetrics().heightPixels;
            int maxCardH = (int) (0.80f * screenH);
            if (card.getHeight() > maxCardH) {
                int otherH = card.getHeight() - scroll.getHeight();
                int avail = maxCardH - otherH;
                if (avail > 200) {
                    scroll.getLayoutParams().height = avail;
                    scroll.requestLayout();
                }
            }
            scrim.animate().alpha(1f).setDuration(200).start();
            card.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(220).start();
        });
    }

    /** 便捷：构建一个「版本 + 日志」块（用于更新日志弹卡）。 */
    public static View buildVersionBlock(Activity a, String version, String lines) {
        float density = a.getResources().getDisplayMetrics().density;
        LinearLayout ll = new LinearLayout(a);
        ll.setOrientation(LinearLayout.VERTICAL);
        ll.setPadding(0, 0, 0, Math.round(16 * density));

        TextView tv = new TextView(a);
        tv.setText(version);
        tv.setTextColor(a.getColor(R.color.accent));
        tv.setTextSize(17f);
        tv.setTypeface(null, Typeface.BOLD);
        ll.addView(tv);

        TextView body = new TextView(a);
        body.setText(lines);
        body.setTextColor(a.getColor(R.color.text_primary));
        body.setTextSize(15f);
        body.setLineSpacing(4 * density, 1f);
        body.setPadding(0, Math.round(6 * density), 0, 0);
        ll.addView(body);
        return ll;
    }
}
