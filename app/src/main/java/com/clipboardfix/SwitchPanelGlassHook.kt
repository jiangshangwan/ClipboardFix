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
 * 明暗取自系统 uiMode；不改动列表文本与勾选图标，避免破坏原生可读性。
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

    /** attach 后再套材质：此时 ViewRootImpl 可用，才能创建真实背景模糊。spglass/2611h */
    private fun attachGlass(content: View) {
        content.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                v.removeOnAttachStateChangeListener(this)
                applyGlass(v)
            }

            override fun onViewDetachedFromWindow(v: View) {}
        })
    }

    /** 生成背景模糊 + 半透明圆角底，替换内容根背景；失败时保持原样。spglass/2611i */
    private fun applyGlass(v: View) {
        try {
            val radius = CARD_RADIUS_DP * v.resources.displayMetrics.density
            val blur = createBlur(v, radius) ?: run {
                log("blur unavailable, keep original")
                return
            }
            val night = (v.resources.configuration.uiMode and
                    Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            val tint = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = radius
                setColor(if (night) TINT_DARK else TINT_LIGHT)
            }
            v.background = LayerDrawable(arrayOf(blur, tint))
            v.invalidate()
            log("glass applied night=$night radius=${radius.toInt()}")
        } catch (t: Throwable) {
            log("applyGlass err - $t")
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
