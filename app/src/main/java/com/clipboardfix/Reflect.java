package com.clipboardfix;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * 自写反射工具，替代旧版 XposedHelpers。
 *
 * <p>libxposed 新版 API 不再提供 XposedHelpers，模块需要自己处理反射。
 * 这里集中封装了迁移所需的全部反射能力：类查找、字段读写、方法查找与调用。
 * 所有查找均沿父类链向上搜索，调用方按需 try/catch。
 */
public final class Reflect {

    private Reflect() {
    }

    // ---------------- 类 ----------------

    /** 按类名 + ClassLoader 查找类，找不到抛异常。 */
    public static Class<?> findClass(String name, ClassLoader cl) throws ClassNotFoundException {
        return Class.forName(name, false, cl);
    }

    /** 查找类，找不到返回 null。 */
    public static Class<?> findClassIfExists(String name, ClassLoader cl) {
        try {
            return Class.forName(name, false, cl);
        } catch (Throwable t) {
            return null;
        }
    }

    // ---------------- 字段 ----------------

    /** 沿继承链查找字段（含父类声明）。 */
    public static Field findField(Class<?> clazz, String name) throws NoSuchFieldException {
        Class<?> c = clazz;
        while (c != null) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name + " in " + clazz.getName());
    }

    public static Object getStaticField(Class<?> clazz, String name)
            throws NoSuchFieldException, IllegalAccessException {
        Field f = findField(clazz, name);
        f.setAccessible(true);
        return f.get(null);
    }

    /** 写静态字段。boolean/int 等基本类型传对应包装类型即可，Field.set 会自动拆箱。 */
    public static void setStaticField(Class<?> clazz, String name, Object value)
            throws NoSuchFieldException, IllegalAccessException {
        Field f = findField(clazz, name);
        f.setAccessible(true);
        f.set(null, value);
    }

    public static Object getField(Object obj, String name)
            throws NoSuchFieldException, IllegalAccessException {
        Field f = findField(obj.getClass(), name);
        f.setAccessible(true);
        return f.get(obj);
    }

    public static void setField(Object obj, String name, Object value)
            throws NoSuchFieldException, IllegalAccessException {
        Field f = findField(obj.getClass(), name);
        f.setAccessible(true);
        f.set(obj, value);
    }

    // ---------------- 方法 ----------------

    /** 沿继承链按精确参数类型查找方法。 */
    public static Method findMethod(Class<?> clazz, String name, Class<?>... paramTypes)
            throws NoSuchMethodException {
        Class<?> c = clazz;
        while (c != null) {
            try {
                Method m = c.getDeclaredMethod(name, paramTypes);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException e) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchMethodException(name + " in " + clazz.getName());
    }

    /** 沿继承链查找构造函数。 */
    public static Constructor<?> findConstructor(Class<?> clazz, Class<?>... paramTypes)
            throws NoSuchMethodException {
        Constructor<?> c = clazz.getDeclaredConstructor(paramTypes);
        c.setAccessible(true);
        return c;
    }

    /** 按名称 + 参数实例做宽松匹配，找到最接近的方法。 */
    public static Method findMethodCompat(Class<?> clazz, String name, Object... args)
            throws NoSuchMethodException {
        Class<?> c = clazz;
        while (c != null) {
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals(name)) continue;
                Class<?>[] ps = m.getParameterTypes();
                if (ps.length != args.length) continue;
                if (args.length == 0) {
                    m.setAccessible(true);
                    return m;
                }
                boolean ok = true;
                for (int i = 0; i < ps.length; i++) {
                    if (args[i] == null) continue; // null 兼容任意引用类型
                    Class<?> want = wrap(ps[i]);
                    if (!want.isInstance(args[i])) {
                        ok = false;
                        break;
                    }
                }
                if (ok) {
                    m.setAccessible(true);
                    return m;
                }
            }
            c = c.getSuperclass();
        }
        throw new NoSuchMethodException(name + " in " + clazz.getName());
    }

    /** 直接调用 Method。 */
    public static Object call(Method m, Object obj, Object... args) throws Exception {
        m.setAccessible(true);
        return m.invoke(obj, args);
    }

    public static Object callMethod(Object obj, String name, Object... args) throws Exception {
        Method m = findMethodCompat(obj.getClass(), name, args);
        return m.invoke(obj, args);
    }

    public static Object callStaticMethod(Class<?> clazz, String name, Object... args)
            throws Exception {
        Method m = findMethodCompat(clazz, name, args);
        if (!Modifier.isStatic(m.getModifiers())) {
            throw new NoSuchMethodException(name + " is not static");
        }
        return m.invoke(null, args);
    }

    /** 基本类型 → 包装类型，便于 isInstance 判断。 */
    private static Class<?> wrap(Class<?> c) {
        if (!c.isPrimitive()) return c;
        if (c == boolean.class) return Boolean.class;
        if (c == byte.class) return Byte.class;
        if (c == short.class) return Short.class;
        if (c == int.class) return Integer.class;
        if (c == long.class) return Long.class;
        if (c == float.class) return Float.class;
        if (c == double.class) return Double.class;
        if (c == char.class) return Character.class;
        return c;
    }
}
