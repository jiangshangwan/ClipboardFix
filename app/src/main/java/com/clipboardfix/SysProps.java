package com.clipboardfix;

import java.lang.reflect.Method;

/**
 * 读取系统属性（android.os.SystemProperties）的反射封装。
 * 属性读取失败时一律返回默认值，不抛异常。
 */
public final class SysProps {

    private static final Method GET;

    static {
        Method m = null;
        try {
            Class<?> cls = Class.forName("android.os.SystemProperties");
            m = cls.getDeclaredMethod("get", String.class, String.class);
            m.setAccessible(true);
        } catch (Throwable ignored) {
            // 系统属性不可用时全部走默认值
        }
        GET = m;
    }

    private SysProps() {
    }

    public static String get(String key, String defaultValue) {
        if (GET != null) {
            try {
                Object value = GET.invoke(null, key, defaultValue);
                if (value != null) {
                    return (String) value;
                }
            } catch (Throwable ignored) {
                // 落到默认值
            }
        }
        return defaultValue;
    }
}
