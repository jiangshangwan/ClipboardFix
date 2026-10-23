package com.clipboardfix

import java.lang.reflect.Method

/**
 * 读取系统属性（android.os.SystemProperties）的反射封装。
 * 属性读取失败时一律返回默认值，不抛异常。
 */
object SysProps {

    private val GET: Method? = run {
        var m: Method? = null
        try {
            val cls = Class.forName("android.os.SystemProperties")
            m = cls.getDeclaredMethod("get", String::class.java, String::class.java)
            m.isAccessible = true
        } catch (_: Throwable) {
            // 系统属性不可用时全部走默认值
        }
        m
    }

    /** 读取系统属性；失败或不可用返回 defaultValue。 */
    @JvmStatic
    fun get(key: String, defaultValue: String): String {
        val m = GET
        if (m != null) {
            try {
                val value = m.invoke(null, key, defaultValue)
                if (value != null) return value as String
            } catch (_: Throwable) {
                // 落到默认值
            }
        }
        return defaultValue
    }
}
