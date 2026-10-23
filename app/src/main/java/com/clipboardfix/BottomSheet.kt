package com.clipboardfix

import android.app.Activity
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.ArrayList
import java.util.Locale

/**
 * 通用居中弹卡：全屏遮罩 + 中间圆角卡片。
 * - cancelable=true 时点击遮罩（含被遮住的导航栏区域）关闭卡片；
 * - cancelable=false（如首次启动「您需了解」）时仅能通过底部按钮关闭，且遮罩吃掉所有触摸，
 *   导航栏自然不可点击。
 * - 支持多层叠放（为爱发电 → 捐赠码 / 捐赠名单）。
 */
object BottomSheet {

    private val STACK: MutableList<FrameLayout> = ArrayList()

    /** 关闭最上层弹卡；若有则返回 true（供 onBackPressed 拦截）。 */
    @JvmStatic
    fun dismissTop(): Boolean {
        if (STACK.isEmpty()) return false
        val root = STACK.removeAt(STACK.size - 1)
        val card: View = root.findViewById(R.id.sheetCard)
        val scrim: View = root.findViewById(R.id.sheetScrim)
        card.animate().scaleX(0.96f).scaleY(0.96f).alpha(0f).setDuration(150).start()
        scrim.animate().alpha(0f).setDuration(180).withEndAction {
            val parent = root.parent as? ViewGroup
            parent?.removeView(root)
        }.start()
        return true
    }

    @JvmStatic
    fun hasOpen(): Boolean {
        return STACK.isNotEmpty()
    }

    /**
     * 单按钮弹卡（兼容旧调用）：actionText 非空时底部显示强调蓝填充按钮。
     *
     * @param title      标题
     * @param content    内容视图（会被放进统一滚动区）
     * @param cancelable 是否允许点击遮罩关闭
     * @param actionText 底部操作按钮文案（null 表示不显示）
     * @param onAction   操作按钮点击回调（执行后自动关闭）
     */
    @JvmStatic
    fun show(activity: Activity, title: String, content: View,
             cancelable: Boolean, actionText: String?, onAction: Runnable?) {
        var actionsView: View? = null
        if (actionText != null && onAction != null) {
            actionsView = buildSingleActionButton(activity, actionText, onAction)
        }
        showWithActions(activity, title, content, cancelable, actionsView)
    }

    /**
     * 自定义操作区弹卡：把任意视图放进底部操作区（如双按钮行）。
     *
     * @param title       标题
     * @param content     内容视图（会被放进统一滚动区）
     * @param cancelable  是否允许点击遮罩关闭
     * @param actionsView 底部操作区视图（null 表示不显示）
     */
    @JvmStatic
    fun show(activity: Activity, title: String, content: View,
             cancelable: Boolean, actionsView: View?) {
        showWithActions(activity, title, content, cancelable, actionsView)
    }

    private fun showWithActions(activity: Activity, title: String, content: View,
                               cancelable: Boolean, actionsView: View?) {
        val contentRoot = activity.findViewById<FrameLayout>(android.R.id.content)!!
        val inf = LayoutInflater.from(activity)
        val root = inf.inflate(R.layout.bottom_sheet, contentRoot, false) as FrameLayout

        val scrim: View = root.findViewById(R.id.sheetScrim)
        val card: View = root.findViewById(R.id.sheetCard)
        val tvTitle = root.findViewById<TextView>(R.id.sheetTitle)
        val body = root.findViewById<LinearLayout>(R.id.sheetBody)
        val actions = root.findViewById<LinearLayout>(R.id.sheetActions)
        val scroll = root.findViewById<ScrollView>(R.id.sheetScroll)

        tvTitle.text = title
        body.addView(content)

        if (actionsView != null) {
            actions.addView(actionsView)
            actions.visibility = View.VISIBLE
        }

        scrim.setOnClickListener { if (cancelable) dismissTop() }

        contentRoot.addView(root)
        STACK.add(root)

        // 居中淡入 + 轻微放大
        scrim.alpha = 0f
        card.alpha = 0f
        card.scaleX = 0.96f
        card.scaleY = 0.96f
        card.post {
            // 内容超高时限制滚动区高度（卡片最多占屏 80%）
            val screenH = activity.resources.displayMetrics.heightPixels
            val maxCardH = (0.80f * screenH).toInt()
            if (card.height > maxCardH) {
                val otherH = card.height - scroll.height
                val avail = maxCardH - otherH
                if (avail > 200) {
                    scroll.layoutParams.height = avail
                    scroll.requestLayout()
                }
            }
            scrim.animate().alpha(1f).setDuration(200).start()
            card.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(220).start()
        }
    }

    /** 单按钮（强调蓝填充，点按后自动关闭）。 */
    private fun buildSingleActionButton(activity: Activity, actionText: String, onAction: Runnable): View {
        val density = activity.resources.displayMetrics.density
        val btn = TextView(activity)
        btn.text = actionText
        btn.setTextColor(0xFFFFFFFF.toInt())
        btn.textSize = 16f
        btn.gravity = Gravity.CENTER
        val padV = Math.round(14 * density)
        btn.setPadding(0, padV, 0, padV)
        btn.setBackgroundResource(R.drawable.btn_accent_bg)
        btn.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        btn.setOnClickListener { onAction.run(); dismissTop() }
        return btn
    }

    /**
     * 双按钮操作行（如更新提醒的「稍后 / 立即更新」）：左侧次要纯文字、右侧强调蓝填充，
     * 等宽并排；点击后均自动关闭弹卡。leftAction 可为 null（仅关闭）。
     */
    @JvmStatic
    fun buildTwoButtonRow(activity: Activity,
                         leftText: String, leftAction: Runnable?,
                         rightText: String, rightAction: Runnable?): View {
        val density = activity.resources.displayMetrics.density
        val row = LinearLayout(activity)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER

        val padV = Math.round(14 * density)
        val gap = Math.round(8 * density)

        // 左：次要纯文字按钮
        val left = TextView(activity)
        left.text = leftText
        left.setTextColor(activity.getColor(R.color.text_tertiary))
        left.textSize = 16f
        left.gravity = Gravity.CENTER
        left.setPadding(0, padV, 0, padV)
        val leftLp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        leftLp.setMargins(0, 0, gap, 0)
        left.layoutParams = leftLp
        left.setOnClickListener { leftAction?.run(); dismissTop() }

        // 右：强调蓝填充按钮
        val right = TextView(activity)
        right.text = rightText
        right.setTextColor(0xFFFFFFFF.toInt())
        right.textSize = 16f
        right.gravity = Gravity.CENTER
        right.setPadding(0, padV, 0, padV)
        right.setBackgroundResource(R.drawable.btn_accent_bg)
        val rightLp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        rightLp.setMargins(gap, 0, 0, 0)
        right.layoutParams = rightLp
        right.setOnClickListener { rightAction?.run(); dismissTop() }

        row.addView(left)
        row.addView(right)
        return row
    }

    // ---------------- 进度型弹卡（下载更新等） ----------------

    /**
     * 进度型弹卡句柄：标题 + 副标题 + 大百分比 + MB 明细 + 强调蓝进度条。
     * 所有方法可在任意线程调用（内部切主线程），关闭后调用自动忽略。
     */
    class ProgressSheet internal constructor(
        private val tvTitle: TextView,
        private val tvSub: TextView,
        private val tvPercent: TextView,
        private val tvBytes: TextView,
        private val fill: View,
        private val spacer: View
    ) {
        private val handler = Handler(Looper.getMainLooper())
        @Volatile
        private var dismissed = false

        /** 刷新进度（percent 0-100；total<=0 表示总大小未知，显示已下载量）。 */
        fun setProgress(percent: Int, read: Long, total: Long) {
            handler.post {
                if (dismissed) return@post
                val p = Math.max(0, Math.min(100, percent))
                tvPercent.text = if (total > 0) "$p%" else "…"
                tvBytes.text = if (total > 0)
                    BottomSheet.formatMb(read) + " / " + BottomSheet.formatMb(total)
                else
                    "已下载 " + BottomSheet.formatMb(read)
                setWeight(fill, p.toFloat())
                setWeight(spacer, (100 - p).toFloat())
            }
        }

        fun setSubTitle(s: CharSequence) {
            handler.post { if (!dismissed) tvSub.text = s }
        }

        fun setTitle(s: CharSequence) {
            handler.post { if (!dismissed) tvTitle.text = s }
        }

        /** 下载完成态：满进度 + 标题切换为调起安装提示。 */
        fun showCompleted() {
            handler.post {
                if (dismissed) return@post
                tvTitle.text = tvTitle.context.getString(R.string.uc_download_done)
                tvPercent.text = "100%"
                setWeight(fill, 100f)
                setWeight(spacer, 0f)
            }
        }

        /** 关闭弹卡（幂等；仅当本卡在最上层时生效）。 */
        fun dismiss() {
            handler.post {
                if (dismissed) return@post
                dismissed = true
                BottomSheet.dismissTop()
            }
        }

        private fun setWeight(v: View, w: Float) {
            val lp = v.layoutParams as LinearLayout.LayoutParams
            if (lp.weight != w) {
                lp.weight = w
                v.layoutParams = lp
            }
        }
    }

    /** 字节数格式化为 MB（两位小数），供进度弹卡与更新检查共用。 */
    @JvmStatic
    fun formatMb(bytes: Long): String {
        return String.format(Locale.US, "%.2f MB", bytes / 1048576.0)
    }

    /**
     * 弹出进度型弹卡：不可点遮罩关闭；底部「取消下载」触发 onCancel 并关闭卡片。
     * 进度条颜色走 @color/accent、轨道走 @color/divider，深浅色自动切换。
     */
    @JvmStatic
    fun showProgress(activity: Activity, title: String, subTitle: String,
                     onCancel: Runnable?): ProgressSheet {
        val density = activity.resources.displayMetrics.density

        val content = LinearLayout(activity)
        content.orientation = LinearLayout.VERTICAL

        // 副标题（版本 / 文件大小）
        val tvSub = TextView(activity)
        tvSub.text = subTitle
        tvSub.setTextColor(activity.getColor(R.color.text_secondary))
        tvSub.textSize = 13f
        tvSub.setPadding(0, 0, 0, Math.round(14 * density))
        content.addView(tvSub)

        // 百分比 + MB 明细行（基线对齐：左大百分比、右小字明细）
        val numRow = LinearLayout(activity)
        numRow.orientation = LinearLayout.HORIZONTAL
        numRow.gravity = Gravity.BOTTOM
        val tvPercent = TextView(activity)
        tvPercent.text = "0%"
        tvPercent.setTextColor(activity.getColor(R.color.accent))
        tvPercent.textSize = 26f
        tvPercent.setTypeface(null, Typeface.BOLD)
        numRow.addView(tvPercent)
        val tvBytes = TextView(activity)
        tvBytes.setTextColor(activity.getColor(R.color.text_tertiary))
        tvBytes.textSize = 12f
        tvBytes.gravity = Gravity.END
        tvBytes.layoutParams = LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        numRow.addView(tvBytes)
        content.addView(numRow)

        // 进度条：圆角轨道 + 权重式填充，clipToOutline 裁出右端圆角
        val track = LinearLayout(activity)
        track.orientation = LinearLayout.HORIZONTAL
        val trackBg = GradientDrawable()
        trackBg.setColor(activity.getColor(R.color.divider))
        trackBg.setCornerRadius(3 * density)
        track.background = trackBg
        track.clipToOutline = true
        val trackLp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, Math.round(6 * density))
        trackLp.topMargin = Math.round(12 * density)
        track.layoutParams = trackLp

        val fill = View(activity)
        val fillBg = GradientDrawable()
        fillBg.setColor(activity.getColor(R.color.accent))
        fillBg.setCornerRadius(3 * density)
        fill.background = fillBg
        fill.layoutParams = LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.MATCH_PARENT, 0f)
        track.addView(fill)

        val spacer = View(activity)
        spacer.layoutParams = LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.MATCH_PARENT, 100f)
        track.addView(spacer)
        content.addView(track)

        // 取消按钮（次级文字按钮，居中）
        val btnCancel = TextView(activity)
        btnCancel.text = activity.getString(R.string.uc_cancel_dl)
        btnCancel.setTextColor(activity.getColor(R.color.text_secondary))
        btnCancel.textSize = 15f
        btnCancel.gravity = Gravity.CENTER
        val padV = Math.round(12 * density)
        btnCancel.setPadding(0, padV, 0, padV)

        show(activity, title, content, false, btnCancel)

        // show() 已把本卡压栈，取回标题视图构造句柄
        val root = STACK.last()
        val tvTitle = root.findViewById<TextView>(R.id.sheetTitle)
        val sheet = ProgressSheet(tvTitle, tvSub, tvPercent, tvBytes, fill, spacer)
        btnCancel.setOnClickListener { onCancel?.run(); sheet.dismiss() }
        return sheet
    }

    /** 便捷：构建一个「版本 + 日志」块（用于更新日志弹卡）。 */
    @JvmStatic
    fun buildVersionBlock(a: Activity, version: String, lines: String): View {
        val density = a.resources.displayMetrics.density
        val ll = LinearLayout(a)
        ll.orientation = LinearLayout.VERTICAL
        ll.setPadding(0, 0, 0, Math.round(16 * density))

        val tv = TextView(a)
        tv.text = version
        tv.setTextColor(a.getColor(R.color.accent))
        tv.textSize = 17f
        tv.setTypeface(null, Typeface.BOLD)
        ll.addView(tv)

        val body = TextView(a)
        body.text = lines
        body.setTextColor(a.getColor(R.color.text_primary))
        body.textSize = 15f
        body.setLineSpacing(4 * density, 1f)
        body.setPadding(0, Math.round(6 * density), 0, 0)
        ll.addView(body)
        return ll
    }
}
