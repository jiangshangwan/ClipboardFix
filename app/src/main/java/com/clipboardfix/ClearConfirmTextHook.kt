/*
 * Copyright (c) 2026 江上晚 (jiangshangwan) <liyd9745@gmail.com>
 * SPDX-License-Identifier: GPL-3.0-only
 * https://github.com/jiangshangwan/ClipboardFix
 */
package com.clipboardfix

import android.content.res.Resources
import android.widget.TextView

/**
 * 分组视图下清空确认框的文案收敛。
 *
 * <p>面板的「一键清空」在分组开启且停留在具体分组时只清当前分组，原生确认框固定读取
 * clear_clipboard_data_confirm，措辞指向全量清空。此处按资源标识拦截取值结果，使提示
 * 与实际作用范围一致；停留在「全部」或分组关闭时保持原样。
 *
 * <p>资源标识按名称解析一次并缓存，避免依赖编译期常量；目标资源在不同语言下共用同一
 * 标识，故所有语言一并覆盖。中文给出对应措辞，其余语言回退英文文案。另设一条在文本
 * 落地阶段按内容匹配的兜底路径，覆盖标识变化或调用方直接传字符串的情形。
 *
 * <p>本 hook 只在面板所在进程安装（面板类加载完成后触发），与分组状态同进程。
 */
object ClearConfirmTextHook {

    private const val RES_NAME = "clear_clipboard_data_confirm"

    /** 名称解析不可用时回退到当前版本的资源标识。 */
    private const val LEGACY_ID = 0x7f110076

    private const val TEXT_ZH = "确认清空当前面板内容？"
    private const val TEXT_EN = "Clear current panel content?"

    private const val SOURCE_ZH = "确认清空全部剪贴板内容？"
    private const val SOURCE_ZH_TW = "確認清空全部剪貼簿內容？"
    private const val SOURCE_EN = "Clear all clipboard content now?"

    private const val SCHEMA_TAG = "cf" + "26" + "cct"

    @Volatile
    private var resolvedId = 0

    @Volatile
    private var installed = false

    @JvmStatic
    fun init() {
        if (installed) return
        installed = true
        try {
            val cls = Resources::class.java
            val getText = cls.getDeclaredMethod("getText", Int::class.javaPrimitiveType)
            val getString = cls.getDeclaredMethod("getString", Int::class.javaPrimitiveType)
            getText.isAccessible = true
            getString.isAccessible = true
            XposedInit.hook(getText) { chain ->
                val id = chain.getArg(0) as? Int
                val res = chain.thisObject as? Resources
                if (id != null && isTargetId(res, id) && shouldReplace()) {
                    replacement(res)
                } else {
                    chain.proceed()
                }
            }
            XposedInit.hook(getString) { chain ->
                val id = chain.getArg(0) as? Int
                val res = chain.thisObject as? Resources
                if (id != null && isTargetId(res, id) && shouldReplace()) {
                    replacement(res)
                } else {
                    chain.proceed()
                }
            }
            hookTextViewFallback()
            XposedInit.log("OK: clear-confirm text hook [" + SCHEMA_TAG + "]")
        } catch (t: Throwable) {
            XposedInit.log("FAIL: clear-confirm text hook - $t")
        }
    }

    /** 文本落地阶段的兜底：按原文案内容匹配，避免依赖资源标识。 */
    private fun hookTextViewFallback() {
        try {
            val m = TextView::class.java.getDeclaredMethod(
                "setText",
                CharSequence::class.java,
                TextView.BufferType::class.java
            )
            m.isAccessible = true
            XposedInit.hook(m) { chain ->
                val raw = chain.getArg(0) as? CharSequence
                val len = raw?.length ?: -1
                // 先用长度筛掉绝大多数调用，避免高频路径上的字符串构造。
                if ((len == 12 || len == 32) && raw != null
                        && matchesSource(raw.toString()) && shouldReplace()) {
                    chain.proceed(arrayOf<Any?>(replacement(null), chain.getArg(1)))
                } else {
                    chain.proceed()
                }
            }
        } catch (t: Throwable) {
            XposedInit.log("FAIL: clear-confirm text fallback - $t")
        }
    }

    /** 只在分组开启且停留在具体分组时替换——此时清空的实际范围就是当前面板。 */
    private fun shouldReplace(): Boolean {
        return ModulePrefs.isClipboardGroupEnabled()
                && CrossDeviceClipboardHook.currentGroupCat() != CrossDeviceClipboardHook.CAT_ALL
    }

    private fun isTargetId(res: Resources?, id: Int): Boolean {
        val cached = resolvedId
        if (cached != 0) return id == cached
        if (res == null) return false
        val found = try {
            res.getIdentifier(RES_NAME, "string", XposedInit.PKG_PHRASE)
        } catch (t: Throwable) {
            0
        }
        val target = if (found != 0) found else LEGACY_ID
        resolvedId = target
        return id == target
    }

    private fun matchesSource(s: String): Boolean {
        return s == SOURCE_ZH || s == SOURCE_ZH_TW || s == SOURCE_EN
    }

    /** 按当前语言给出对应措辞；取不到配置时回退英文。 */
    private fun replacement(res: Resources?): String {
        val lang = try {
            val r = res ?: Resources.getSystem()
            r.configuration.locales.get(0)?.language
        } catch (t: Throwable) {
            null
        }
        return if ("zh" == lang) TEXT_ZH else TEXT_EN
    }
}
