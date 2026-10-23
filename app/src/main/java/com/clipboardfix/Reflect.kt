package com.clipboardfix

import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * 自写反射工具，替代旧版 XposedHelpers。
 *
 * <p>libxposed 新版 API 不再提供 XposedHelpers，模块需要自己处理反射。
 * 这里集中封装了迁移所需的全部反射能力：类查找、字段读写、方法查找与调用。
 * 所有查找均沿父类链向上搜索，调用方按需 try/catch。
 */
object Reflect {

    // ---------------- 类 ----------------

    /** 按类名 + ClassLoader 查找类，找不到抛异常。 */
    @JvmStatic
    fun findClass(name: String, cl: ClassLoader): Class<*> {
        return Class.forName(name, false, cl)
    }

    /** 查找类，找不到返回 null。 */
    @JvmStatic
    fun findClassIfExists(name: String, cl: ClassLoader): Class<*>? {
        return try {
            Class.forName(name, false, cl)
        } catch (t: Throwable) {
            null
        }
    }

    // ---------------- 字段 ----------------

    /** 沿继承链查找字段（含父类声明）。 */
    @JvmStatic
    @Throws(NoSuchFieldException::class)
    fun findField(clazz: Class<*>, name: String): Field {
        var c: Class<*>? = clazz
        while (c != null) {
            try {
                return c.getDeclaredField(name)
            } catch (e: NoSuchFieldException) {
                c = c.superclass
            }
        }
        throw NoSuchFieldException(name + " in " + clazz.name)
    }

    @JvmStatic
    @Throws(NoSuchFieldException::class, IllegalAccessException::class)
    fun getStaticField(clazz: Class<*>, name: String): Any? {
        val f = findField(clazz, name)
        f.isAccessible = true
        return f[null]
    }

    /** 写静态字段。boolean/int 等基本类型传对应包装类型即可，Field.set 会自动拆箱。 */
    @JvmStatic
    @Throws(NoSuchFieldException::class, IllegalAccessException::class)
    fun setStaticField(clazz: Class<*>, name: String, value: Any?) {
        val f = findField(clazz, name)
        f.isAccessible = true
        f.set(null, value)
    }

    @JvmStatic
    @Throws(NoSuchFieldException::class, IllegalAccessException::class)
    fun getField(obj: Any?, name: String): Any? {
        val f = findField(obj!!.javaClass, name)
        f.isAccessible = true
        return f[obj]
    }

    @JvmStatic
    @Throws(NoSuchFieldException::class, IllegalAccessException::class)
    fun setField(obj: Any?, name: String, value: Any?) {
        val f = findField(obj!!.javaClass, name)
        f.isAccessible = true
        f.set(obj, value)
    }

    // ---------------- 方法 ----------------

    /** 沿继承链按精确参数类型查找方法。 */
    @JvmStatic
    @Throws(NoSuchMethodException::class)
    fun findMethod(clazz: Class<*>, name: String, vararg paramTypes: Class<*>): Method {
        var c: Class<*>? = clazz
        while (c != null) {
            try {
                val m = c.getDeclaredMethod(name, *paramTypes)
                m.isAccessible = true
                return m
            } catch (e: NoSuchMethodException) {
                c = c.superclass
            }
        }
        throw NoSuchMethodException(name + " in " + clazz.name)
    }

    /** 沿继承链查找构造函数。 */
    @JvmStatic
    @Throws(NoSuchMethodException::class)
    fun findConstructor(clazz: Class<*>, vararg paramTypes: Class<*>): Constructor<*> {
        val c = clazz.getDeclaredConstructor(*paramTypes)
        c.isAccessible = true
        return c
    }

    /** 按名称 + 参数实例做宽松匹配，找到最接近的方法。 */
    @JvmStatic
    @Throws(NoSuchMethodException::class)
    fun findMethodCompat(clazz: Class<*>, name: String, vararg args: Any?): Method {
        var c: Class<*>? = clazz
        while (c != null) {
            for (m in c.declaredMethods) {
                if (m.name != name) continue
                val ps = m.parameterTypes
                if (ps.size != args.size) continue
                if (args.isEmpty()) {
                    m.isAccessible = true
                    return m
                }
                var ok = true
                var i = 0
                while (i < ps.size) {
                    if (args[i] != null) {
                        val want = wrap(ps[i])
                        if (!want.isInstance(args[i])) {
                            ok = false
                            break
                        }
                    }
                    i++
                }
                if (ok) {
                    m.isAccessible = true
                    return m
                }
            }
            c = c.superclass
        }
        throw NoSuchMethodException(name + " in " + clazz.name)
    }

    /** 直接调用 Method。 */
    @JvmStatic
    @Throws(Exception::class)
    fun call(m: Method, obj: Any?, vararg args: Any?): Any? {
        m.isAccessible = true
        return m.invoke(obj, *args)
    }

    @JvmStatic
    @Throws(Exception::class)
    fun callMethod(obj: Any?, name: String, vararg args: Any?): Any? {
        val m = findMethodCompat(obj!!.javaClass, name, *args)
        return m.invoke(obj, *args)
    }

    @JvmStatic
    @Throws(Exception::class)
    fun callStaticMethod(clazz: Class<*>, name: String, vararg args: Any?): Any? {
        val m = findMethodCompat(clazz, name, *args)
        if (!Modifier.isStatic(m.modifiers)) {
            throw NoSuchMethodException(name + " is not static")
        }
        return m.invoke(null, *args)
    }

    /** 基本类型 → 包装类型，便于 isInstance 判断。 */
    private fun wrap(c: Class<*>): Class<*> {
        if (!c.isPrimitive) return c
        if (c == Boolean::class.javaPrimitiveType) return Boolean::class.javaObjectType
        if (c == Byte::class.javaPrimitiveType) return Byte::class.javaObjectType
        if (c == Short::class.javaPrimitiveType) return Short::class.javaObjectType
        if (c == Int::class.javaPrimitiveType) return Int::class.javaObjectType
        if (c == Long::class.javaPrimitiveType) return Long::class.javaObjectType
        if (c == Float::class.javaPrimitiveType) return Float::class.javaObjectType
        if (c == Double::class.javaPrimitiveType) return Double::class.javaObjectType
        if (c == Char::class.javaPrimitiveType) return Char::class.javaObjectType
        return c
    }
}
