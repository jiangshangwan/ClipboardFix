/*
 * Copyright (c) 2026 江上晚 (jiangshangwan) <liyd9745@gmail.com>
 * SPDX-License-Identifier: GPL-3.0-only
 * https://github.com/jiangshangwan/ClipboardFix
 */
package com.clipboardfix

import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import java.lang.reflect.Executable
import java.lang.reflect.Method

/**
 * 输入法切换面板的原生毛玻璃材质。spglass/2611a
 *
 * <p>切换列表由 {@code com.miui.inputmethod.InputMethodSwitchPopupView}（动态 dex）负责，
 * 它是一条独立于剪贴板面板的 PopupWindow 分支，{@code PanelColorHook} 不覆盖。
 * 面板在 {@code initPopupWindow()} 里先把 inflate 出的内容根交给 {@code setContentView}，
 * 再 {@code showAtLocation} 弹出；{@code setContentView} 时 View 尚未 attach，
 * 取不到 ViewRootImpl，故毛玻璃必须延迟到 {@code onViewAttachedToWindow} 之后套。
 *
 * <p>材质只用系统 {@code createBackgroundBlurDrawable} 的真实背景模糊 + 一层半透明圆角底，
 * 明暗取自系统 uiMode（与键盘背景同源）。输入法面板文字色跟随自身皮肤、切换主题时可能滞后，
 * 故在弹出后核对文字方向，与底色冲突时翻正，选中态强调色保留。spglass/2611s
 */
object SwitchPanelGlassHook {

    private const val POPUP_CLASS = "com.miui.inputmethod.InputMethodSwitchPopupView"
    private const val MODULE_MGR = "android.inputmethodservice.InputMethodModuleManager"
    /** 切换面板卡片圆角：与弹窗自身底形的 24dp 圆角对齐，避免角部露出下层背景。spglass/2611b */
    /** 弹窗原生背景使用 demi_big 圆角；叠色层与之同半径才能完整遮住四角。spglass/2611q */
    private const val CARD_RADIUS_DP = 24f
    /** 模糊强度：与面板主模糊基线一致。spglass/2611c */
    private const val BLUR_RADIUS = 60
    /** 表面叠色不透明度：浅色偏白、深色偏暗，均保持半透明以透出模糊。spglass/2611d */
    // 调低表面叠色 alpha，让背景模糊透出更多，观感更接近系统液体玻璃。spglass/2611m
    private val TINT_LIGHT = 0x66F7F8FA.toInt()
    private val TINT_DARK = 0x521F2024.toInt()

    @Volatile
    private var hooked = false

    @JvmStatic
    fun init(classLoader: ClassLoader?) {
        if (hooked || classLoader == null) return
        installOnDex(classLoader)
        hookLoadDex(classLoader)
        hooked = true
    }

    /** dex 已在本进程时直接尝试；类不存在则交给 loadDex 回调重试。spglass/2611e */
    private fun installOnDex(cl: ClassLoader) {
        try {
            val cls = Class.forName(POPUP_CLASS, false, cl)
            installSetContentView(cls)
        } catch (ignored: Throwable) {
            // dex 尚未落地，loadDex 回调里再试
        }
    }

    /**
     * 动态 dex 由 {@code InputMethodModuleManager.loadDex} 注入；proceed 后立刻尝试安装，
     * 这样首次弹切换面板之前 hook 已就位。spglass/2611f
     */
    private fun hookLoadDex(cl: ClassLoader) {
        try {
            val mgr = Class.forName(MODULE_MGR, false, cl)
            var loadDex: Method? = null
            for (m in mgr.declaredMethods) {
                val ps = m.parameterTypes
                if (m.name == "loadDex" && ps.size == 2
                        && ClassLoader::class.java.isAssignableFrom(ps[0])
                        && String::class.java == ps[1]) {
                    loadDex = m
                    break
                }
            }
            if (loadDex == null) return
            loadDex.isAccessible = true
            XposedInit.hook(loadDex) { chain ->
                val r = chain.proceed()
                try {
                    installOnDex(chain.getArg(0) as ClassLoader)
                } catch (ignored: Throwable) {
                    // 安装失败不影响原加载流程
                }
                r
            }
            log("loadDex hook installed")
        } catch (t: Throwable) {
            log("loadDex hook failed - $t")
        }
    }

    /** 在切换面板类上挂 setContentView；用同步标记保证只装一次。spglass/2611g */
    @Volatile
    private var setContentViewHooked = false

    private fun installSetContentView(cls: Class<*>) {
        if (setContentViewHooked) return
        val m = findMethod(cls, "setContentView", View::class.java) ?: return
        m.isAccessible = true
        XposedInit.hook(m) { chain ->
            val result = chain.proceed()
            try {
                val self = chain.thisObject ?: return@hook result
                if (cls.isInstance(self) || POPUP_CLASS == self.javaClass.name) {
                    val content = chain.getArg(0) as? View
                    if (content != null) attachGlass(content)
                }
            } catch (t: Throwable) {
                log("setContentView hook err - $t")
            }
            result
        }
        setContentViewHooked = true
        log("setContentView hook installed on " + cls.name)
    }

    /**
     * attach 后再套材质：此时 ViewRootImpl 可用，才能创建真实背景模糊。
     * 保留监听以便每次弹出都重新取样文字色校正叠色明暗（面板单例复用，主题可变）。spglass/2611h
     */
    private fun attachGlass(content: View) {
        content.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                applyGlass(v)
            }

            override fun onViewDetachedFromWindow(v: View) {}
        })
    }

    /**
     * 背景模糊 + 半透明圆角底。底色明暗锁定系统 uiMode（与键盘背景同源，切换即时），
     * 不再让滞后的输入法文字色反向决定底色；文字方向不符时另行翻正。spglass/2611i
     */
    private fun applyGlass(v: View) {
        try {
            val radius = CARD_RADIUS_DP * v.resources.displayMetrics.density
            val existing = v.background as? LayerDrawable
            val blur: Drawable
            val tint: GradientDrawable
            if (existing != null && existing.numberOfLayers == 2 &&
                    existing.getDrawable(1) is GradientDrawable) {
                blur = existing.getDrawable(0)
                tint = existing.getDrawable(1) as GradientDrawable
            } else {
                blur = createBlur(v, radius) ?: run {
                    log("blur unavailable, keep original")
                    return
                }
                tint = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = radius
                }
                v.background = LayerDrawable(arrayOf(blur, tint))
            }
            // 以 Resources.getSystem() 为准：视图 Resources 在进程缓存未刷新时可能滞后于系统主题。
            val sysNight = (android.content.res.Resources.getSystem().configuration.uiMode and
                    Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            val night = sysNight
            tint.setColor(if (night) TINT_DARK else TINT_LIGHT)
            v.invalidate()
            // 列表项在 attach 后才填充，下一帧核对文字方向并与底色对齐
            v.post {
                if (!v.isAttachedToWindow) return@post
                try {
                    val sample = textSample(v)
                    // 深底需浅字、浅底需深字；方向相反时翻正文字色（跳过选中态强调色）
                    val flipped = when {
                        sample.light > sample.dark && !night -> retintText(v, true)
                        sample.dark > sample.light && night -> retintText(v, false)
                        else -> false
                    }
                    v.invalidate()
                    // 明暗判定全量落日志：主题切换后若仍错配，据此还原 uiMode 与文字采样。cf/spdiag-1127c
                    log("tint fixed night=$night txtLight=${sample.light} txtDark=${sample.dark} " +
                            "head=${sample.head} flipped=$flipped")
                } catch (t: Throwable) {
                    log("tint fix err - $t")
                }
            }
            log("glass applied radius=${radius.toInt()}")
        } catch (t: Throwable) {
            log("applyGlass err - $t")
        }
    }

    /**
     * 翻正列表文字色以匹配底色：toDark=true 改深色字（配浅底），false 改浅色字（配深底）。
     * 只改普通文字 TextView，选中态强调蓝与透明色保留。返回是否实际改动。spglass/2611t
     */
    private fun retintText(root: View, toDark: Boolean): Boolean {
        val target = if (toDark) 0xFF1F2024.toInt() else 0xFFF2F3F5.toInt()
        var changed = false
        fun apply(v: View) {
            if (v is TextView && v.currentTextColor != 0) {
                val c = v.currentTextColor
                val r = Color.red(c)
                val g = Color.green(c)
                val b = Color.blue(c)
                // 选中态强调蓝保留
                if (!(b > r + 30 && b > g + 10) && c != target) {
                    v.setTextColor(target)
                    changed = true
                }
            }
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) apply(v.getChildAt(i))
            }
        }
        apply(root)
        return changed
    }

    /** 文字采样结果：明暗倾向与明细，供判定与诊断日志共用。cf/spdiag-1127b */
    private class TextSample(
        val preferDark: Boolean?,
        val light: Int,
        val dark: Int,
        val head: String,
    )

    /**
     * 采样面板内普通文字色亮度：浅字多则 preferDark=true，深字多则 false，无样本为 null。
     * 跳过选中态强调蓝与透明色，避免个别高亮项干扰整体判定。spglass/2611r
     */
    private fun textSample(root: View): TextSample {
        var light = 0
        var dark = 0
        var head = "-"
        val sample: (Int) -> Unit = sample@{ c ->
            if (Color.alpha(c) < 0x40) return@sample
            val r = Color.red(c)
            val g = Color.green(c)
            val b = Color.blue(c)
            if (b > r + 30 && b > g + 10) return@sample
            val lum = (0.299f * r + 0.587f * g + 0.114f * b) / 255f
            if (head == "-") head = String.format(java.util.Locale.US, "%06X", c and 0xFFFFFF)
            if (lum > 0.5f) light++ else dark++
        }
        collectTextColors(root, sample)
        val prefer = when {
            light > dark -> true
            dark > light -> false
            else -> null
        }
        return TextSample(prefer, light, dark, head)
    }

    private fun collectTextColors(v: View, out: (Int) -> Unit) {
        if (v is TextView && v.currentTextColor != 0) out(v.currentTextColor)
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) collectTextColors(v.getChildAt(i), out)
        }
    }

    /** 通过 ViewRootImpl 的隐藏接口创建真实背景模糊 drawable。spglass/2611j */
    private fun createBlur(v: View, radius: Float): Drawable? {
        val getVri = findMethod(View::class.java, "getViewRootImpl") ?: return null
        getVri.isAccessible = true
        val vri = getVri.invoke(v) ?: return null
        val create = findMethod(vri.javaClass, "createBackgroundBlurDrawable") ?: return null
        create.isAccessible = true
        val d = create.invoke(vri) as? Drawable ?: return null
        setInt(d, "setBlurRadius", BLUR_RADIUS)
        setInt(d, "setColor", Color.TRANSPARENT)
        val corners = findMethod(d.javaClass, "setCornerRadius",
                Float::class.java, Float::class.java, Float::class.java, Float::class.java)
        if (corners != null) {
            corners.isAccessible = true
            corners.invoke(d, radius, radius, radius, radius)
        } else {
            val one = findMethod(d.javaClass, "setCornerRadius", Float::class.java)
            one?.let {
                it.isAccessible = true
                it.invoke(d, radius)
            }
        }
        return d
    }

    private fun setInt(target: Any, name: String, value: Int) {
        val m = findMethod(target.javaClass, name, Int::class.java) ?: return
        m.isAccessible = true
        m.invoke(target, value)
    }

    private fun findMethod(clazz: Class<*>, name: String, vararg types: Class<*>): Method? {
        var c: Class<*>? = clazz
        while (c != null) {
            try {
                return c.getDeclaredMethod(name, *types)
            } catch (ignored: Throwable) {
                c = c.superclass
            }
        }
        return null
    }

    private fun log(msg: String) = XposedInit.log("[SwitchGlass] $msg")
}
