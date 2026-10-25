package com.clipboardfix

import android.content.ContentProvider
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import android.content.pm.Signature
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.util.LruCache
import java.io.ByteArrayOutputStream
import java.lang.reflect.Method

/**
 * 剪贴板修复四件套，只在 com.miui.phrase 进程内安装。
 *
 * <p>Hook 1：包名伪造，绕过白名单校验
 * <p>Hook 1.5：签名查询出口伪装，让 insert() 的签名白名单放行（滑动删除的
 *              列表保存由此真正落库，不再「删了又复活」）
 * <p>Hook 1.6：call() 执行期间开真实包名窗口（Bypass），修复第三方输入法
 *              云同步假失效——详见 [hookProviderCallMethod]
 * <p>Hook 2：ContentProvider 查询改写，吞异常 + thumbImage WebP 转 PNG
 * <p>Hook 3：SecurityException 构造拦截
 */
object ClipboardHook {

    private const val SYSTEM_UID = 1000

    private val ALLOWED_PACKAGES = arrayOf(
        "com.sohu.inputmethod.sogou.xiaomi",
        "com.xiaomi.type"
    )

    /** 已 hook 过 openFile 的 provider，避免重复挂载。 */
    private val HOOKED_PROVIDERS: MutableSet<String> =
        java.util.Collections.synchronizedSet(java.util.HashSet())

    /**
     * thumbImage 转换结果缓存。
     *
     * <p>key 是原始的 base64 内容，value 是转换后的 PNG base64。
     * 剪贴板面板会被频繁查询，不缓存的话每条图片每次都要重新解码再编码，条目多时明显卡顿。
     * 若某条内容本就无需转换，则把 value 存成与 key 相同的值，下次命中时直接原样返回。
     * LruCache 自身线程安全。
     */
    private val THUMB_CACHE = LruCache<String, String>(32)

    private var globalFileHooked = false

    @JvmStatic
    fun init() {
        hookPackageManager()
        hookAttachInfo()
        hookSecurityException()
    }

    private fun log(msg: String) {
        XposedInit.log(msg)
    }

    // ====== Hook 1: PackageManager.getNameForUid / getPackagesForUid ======

    private fun hookPackageManager() {
        val appPm = findApplicationPackageManager()
        if (appPm != null) {
            hookNameForUidOn(appPm)
            hookPkgsForUidOn(appPm)
            hookGetPackageInfoOn(appPm)
        } else {
            // 兜底：找不到具体实现时直接 hook 抽象声明
            hookNameForUidOn(PackageManager::class.java)
            hookPkgsForUidOn(PackageManager::class.java)
            hookGetPackageInfoOn(PackageManager::class.java)
        }
    }

    private fun findApplicationPackageManager(): Class<*>? {
        return try {
            Class.forName(
                "android.app.ApplicationPackageManager",
                false,
                PackageManager::class.java.classLoader
            )
        } catch (t: Throwable) {
            null
        }
    }

    private fun hookNameForUidOn(clazz: Class<*>): Boolean {
        return try {
            val m = Reflect.findMethod(clazz, "getNameForUid", Int::class.javaPrimitiveType!!)
            XposedInit.hook(m) { chain ->
                val r = chain.proceed()
                // DexKit 精确校验期间放行真实包名
                if (Bypass.isActive()) return@hook r

                val uid = chain.getArg(0) as Int
                val result = r as? String
                if (uid > SYSTEM_UID && uid < 100000) {
                    // 系统服务一律不伪造
                    if (isSystemService(result)) return@hook r
                    // 白名单内的小米输入法本来就能通过
                    if (isWhitelisted(result)) return@hook r
                    log("getNameForUid spoofed: $uid ($result) -> ${ALLOWED_PACKAGES[0]}")
                    return@hook ALLOWED_PACKAGES[0]
                }
                r
            }
            log("OK: getNameForUid on ${clazz.simpleName}")
            true
        } catch (t: Throwable) {
            log("FAIL: getNameForUid on ${clazz.simpleName} - $t")
            false
        }
    }

    private fun hookPkgsForUidOn(clazz: Class<*>): Boolean {
        return try {
            val m = Reflect.findMethod(clazz, "getPackagesForUid", Int::class.javaPrimitiveType!!)
            XposedInit.hook(m) { chain ->
                val r = chain.proceed()
                if (Bypass.isActive()) return@hook r

                val uid = chain.getArg(0) as Int
                val result = r as? Array<String>
                if (uid > SYSTEM_UID && uid < 100000) {
                    if (result != null && result.isNotEmpty()) {
                        if (isSystemService(result[0])) return@hook r
                        if (isWhitelisted(result[0])) return@hook r
                        log("getPackagesForUid spoofed: $uid (${result[0]}) -> ${ALLOWED_PACKAGES[0]}")
                        return@hook arrayOf(ALLOWED_PACKAGES[0])
                    }
                }
                r
            }
            log("OK: getPackagesForUid on ${clazz.simpleName}")
            true
        } catch (t: Throwable) {
            log("FAIL: getPackagesForUid on ${clazz.simpleName} - $t")
            false
        }
    }

    // ====== Hook 1.5: PackageManager.getPackageInfo 签名伪装 ======

    /**
     * phrase provider 的 {@code insert()} 在包名校验之后还有一道签名白名单：
     * {@code getPackageInfo(pkgName, GET_SIGNATURES).signatures[0]} 的 SHA1
     * 必须命中 {@code InputProvider} 的静态列表。
     *
     * <p>包名伪造已把调用方扮成 {@link #ALLOWED_PACKAGES} 首位，但该进程内这次
     * 签名查询仍以「非支持的签名应用」收场（滑动删除的列表保存因此从不落库）。
     * 这里在签名查询出口上把结果换成 {@code com.xiaomi.type} 的真实 PackageInfo——其 MIUI
     * 平台证书的 SHA1 本就在 provider 白名单内，门禁原样放行。
     *
     * <p>只拦 {@code GET_SIGNATURES} 位且包名恰为伪装目标的那一次查询，
     * 其余查询原样透传；钩子内部发起的真实查询套 {@link Bypass} 防递归。
     */
    private fun hookGetPackageInfoOn(clazz: Class<*>): Boolean {
        return try {
            val m = Reflect.findMethod(clazz, "getPackageInfo", String::class.java, Int::class.javaPrimitiveType!!)
            XposedInit.hook(m) { chain ->
                if (Bypass.isActive()) {
                    // 本钩子内部发起的真实查询（重入）：放行
                    return@hook chain.proceed()
                }
                val name = chain.getArg(0) as String
                val flags = chain.getArg(1) as Int
                if (name != ALLOWED_PACKAGES[0] || (flags and PackageManager.GET_SIGNATURES) == 0) {
                    return@hook chain.proceed()
                }
                val pm = chain.thisObject as PackageManager
                Bypass.begin()
                try {
                    // 换白名单证书（com.xiaomi.type 的 MIUI 平台证书）
                    try {
                        val mi = pm.getPackageInfo(ALLOWED_PACKAGES[1], flags)
                        val sigs = mi?.signatures
                        if (sigs != null && sigs.isNotEmpty()) {
                            log("sig lookup: substituted ${ALLOWED_PACKAGES[1]} cert ${sha1Hex(sigs[0])}")
                            return@hook mi
                        }
                        log("sig lookup: substitute failed, no cert on ${ALLOWED_PACKAGES[1]}")
                    } catch (t: Throwable) {
                        log("sig lookup: substitute threw $t")
                    }
                } finally {
                    Bypass.end()
                }
                chain.proceed()
            }
            log("OK: getPackageInfo on ${clazz.simpleName}")
            true
        } catch (t: Throwable) {
            log("FAIL: getPackageInfo on ${clazz.simpleName} - $t")
            false
        }
    }

    /** SHA1(证书 DER) 小写 hex——与 provider 的 HashUtils.toHexString 对齐。 */
    private fun sha1Hex(sig: Signature): String {
        return try {
            val md = java.security.MessageDigest.getInstance("SHA1")
            val d = md.digest(sig.toByteArray())
            val sb = StringBuilder(d.size * 2)
            for (b in d) {
                sb.append(java.lang.Character.forDigit((b.toInt() shr 4) and 0xF, 16))
                sb.append(java.lang.Character.forDigit(b.toInt() and 0xF, 16))
            }
            sb.toString()
        } catch (t: Throwable) {
            "?"
        }
    }

    /** 系统服务永不伪造，避免误伤 milink、phrase provider 等。 */
    private fun isSystemService(pkgName: String?): Boolean {
        return pkgName != null && (
            pkgName.startsWith("com.miui.") ||
                pkgName.startsWith("com.xiaomi.") ||
                pkgName.startsWith("android.") ||
                pkgName.startsWith("com.android.") ||
                pkgName.contains("milink")
            )
    }

    private fun isWhitelisted(pkgName: String?): Boolean {
        if (pkgName == null) return false
        for (allowed in ALLOWED_PACKAGES) {
            if (allowed == pkgName) return true
        }
        return false
    }

    // ====== Hook 2: ContentProvider.attachInfo -> 定位 input / phrase provider ======

    private fun hookAttachInfo() {
        try {
            val m = Reflect.findMethod(
                ContentProvider::class.java, "attachInfo",
                Context::class.java, ProviderInfo::class.java
            )
            XposedInit.hook(m) { chain ->
                val r = chain.proceed()
                val clazz = chain.thisObject.javaClass
                if (clazz == ContentProvider::class.java) return@hook r
                val info = chain.getArg(1) as? ProviderInfo
                if (info != null && info.authority != null && info.authority.contains("input")) {
                    log("Found INPUT provider: ${clazz.name} authority=${info.authority}")
                    hookConcreteQueryMethod(clazz)
                    hookProviderCallMethod(clazz)
                }
                hookGlobalContentProviderFile()
                r
            }
            log("OK: ContentProvider.attachInfo")
        } catch (t: Throwable) {
            log("FAIL: ContentProvider.attachInfo - $t")
        }
    }

    // ====== Hook 1.6: InputProvider.call() 真实包名窗口 ======

    /**
     * phrase provider 的 `call()` 在方法分发前有一道**内联门禁**（反汇编实证）：
     * `getNameForUid(getCallingUid())` 必须等于当前默认输入法包名
     * （`com.miui.phrase.p.f` 读 `Settings.Secure.DEFAULT_INPUT_METHOD`），
     * 否则整个分发被跳过、直接 `super.call()` 静默返回。
     *
     * <p>第三方输入法经 Hook 1 包名伪装后，`getNameForUid` 返回的是伪装包名，
     * 与该门禁永远不相等 → `startObserveThumbnailInfoChange` 被静默丢弃 →
     * 云剪贴板客户端永不获取 → 跨设备服务永不推送 PC 新条目。
     * 表现为「重启后云同步假失效」：只有切到小米输入法开一次面板（其包名
     * 本就等于当前输入法、无需伪装）把数据拉下来后，第三方输入法才恢复显示。
     *
     * <p>解法：在 `call()` 执行期间套 [Bypass]（线程级放行真实包名），
     * 门禁即可按真实当前输入法放行。`call()` 内不调用白名单校验 `f()`、
     * 不做签名校验；`insert()`/`query()` 也不在 `call()` 内，伪装原样生效，
     * 滑动删除落库等行为不受影响。
     */
    private fun hookProviderCallMethod(providerClass: Class<*>) {
        try {
            val m = providerClass.getDeclaredMethod(
                "call",
                String::class.java,
                String::class.java,
                Bundle::class.java
            )
            XposedInit.hook(m) { chain ->
                Bypass.begin()
                try {
                    return@hook chain.proceed()
                } finally {
                    Bypass.end()
                }
            }
            log("OK: ${providerClass.name}.call real-caller window")
        } catch (e: NoSuchMethodException) {
            log("${providerClass.simpleName} does not override call(3 arg)")
        } catch (t: Throwable) {
            log("FAIL: hook call on ${providerClass.name} - $t")
        }
    }

    private fun hookConcreteQueryMethod(providerClass: Class<*>) {
        try {
            var foundQuery = false
            for (m in providerClass.declaredMethods) {
                val params = m.parameterTypes
                if (params.size == 5
                    && Uri::class.java.isAssignableFrom(params[0])
                    && Array<String>::class.java == params[1]
                    && String::class.java == params[2]
                    && Array<String>::class.java == params[3]
                    && String::class.java == params[4]
                ) {
                    log("Found query: ${providerClass.name}.${m.name}")
                    val queryMethod: Method = m
                    XposedInit.hook(queryMethod) { chain ->
                        val cursor: Cursor = try {
                            chain.proceed() as? Cursor
                        } catch (se: SecurityException) {
                            log("query CAUGHT SecurityException")
                            return@hook null
                        } ?: return@hook null

                        val cols = cursor.columnNames
                        log("query RESULT: ${cursor.count} rows, cols=${cols.contentToString()}")

                        var phraseContentIdx = -1
                        for (i in cols.indices) {
                            if (cols[i] == "phrase_content") {
                                phraseContentIdx = i
                                break
                            }
                        }
                        if (phraseContentIdx < 0) return@hook cursor

                        var needsConversion = false
                        val allRows = ArrayList<Array<Any?>>()
                        if (cursor.moveToFirst()) {
                            do {
                                val row = arrayOfNulls<Any>(cols.size)
                                for (col in cols) {
                                    val idx = cursor.getColumnIndex(col)
                                    if (idx >= 0) {
                                        val v = cursor.getString(idx)
                                        row[idx] = v
                                        if (idx == phraseContentIdx && v != null
                                            && v.contains("\"thumbImage\":\"")
                                        ) {
                                            needsConversion = true
                                        }
                                    }
                                }
                                allRows.add(row)
                            } while (cursor.moveToNext())
                        }

                        if (!needsConversion) return@hook cursor

                        log("converting thumbImage WebP->PNG for ${allRows.size} rows")
                        val newCursor = MatrixCursor(cols)
                        for (row in allRows) {
                            val newRow = row.clone()
                            val content = row[phraseContentIdx] as? String
                            if (content != null && content.contains("\"thumbImage\":\"")) {
                                newRow[phraseContentIdx] = convertThumbImageWebPToPng(content)
                            }
                            newCursor.addRow(newRow)
                        }
                        log("conversion done, replaced cursor")
                        return@hook newCursor
                    }
                    foundQuery = true
                    break
                }
            }
            if (!foundQuery) {
                log("WARN: no query method found on ${providerClass.name}")
            }
        } catch (t: Throwable) {
            log("FAIL: hookConcreteQueryMethod - $t")
        }
    }

    /**
     * 系统输入法（小米搜狗/小米智能）能渲染 WebP，第三方输入法（微信键盘等）不行，
     * 这里转成 PNG 以修复图片条目显示异常。
     */
    private fun convertThumbImageWebPToPng(json: String): String {
        try {
            val marker = "\"thumbImage\":\""
            var thumbStart = json.indexOf(marker)
            if (thumbStart == -1) return json
            thumbStart += marker.length
            val thumbEnd = json.indexOf("\"", thumbStart)
            if (thumbEnd == -1) return json

            val rawBase64 = json.substring(thumbStart, thumbEnd)

            // 命中缓存直接拼回去，省掉解码 + 编码
            val cached = THUMB_CACHE.get(rawBase64)
            if (cached != null) {
                return json.substring(0, thumbStart) + cached + json.substring(thumbEnd)
            }

            val imageBytes = Base64.decode(rawBase64, Base64.DEFAULT)

            // 非 WebP（不以 RIFF 开头）不做转换，记进缓存避免下次重复解码
            if (imageBytes.size < 12
                || imageBytes[0].toInt() != 'R'.code
                || imageBytes[1].toInt() != 'I'.code
                || imageBytes[2].toInt() != 'F'.code
                || imageBytes[3].toInt() != 'F'.code
            ) {
                THUMB_CACHE.put(rawBase64, rawBase64)
                return json
            }

            val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
            if (bitmap == null) {
                log("WARN: failed to decode WebP bitmap")
                THUMB_CACHE.put(rawBase64, rawBase64)
                return json
            }

            val pngOut = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, pngOut)
            val pngBytes = pngOut.toByteArray()
            val base64Png = Base64.encodeToString(pngBytes, Base64.NO_WRAP)
            bitmap.recycle()

            THUMB_CACHE.put(rawBase64, base64Png)

            log("thumbImage WebP->PNG: ${imageBytes.size} bytes -> ${pngBytes.size} bytes")
            return json.substring(0, thumbStart) + base64Png + json.substring(thumbEnd)
        } catch (t: Throwable) {
            log("convertThumbImageWebPToPng error: $t")
            return json
        }
    }

    /** 对 phrase / clipboard / continuity 相关 provider 抑制 openFile 的权限异常。 */
    @Synchronized
    private fun hookGlobalContentProviderFile() {
        if (globalFileHooked) return
        globalFileHooked = true

        try {
            val m = Reflect.findMethod(
                ContentProvider::class.java, "attachInfo",
                Context::class.java, ProviderInfo::class.java
            )
            XposedInit.hook(m) { chain ->
                val r = chain.proceed()
                val info = chain.getArg(1) as? ProviderInfo
                val auth = info?.authority ?: return@hook r
                if (auth.contains("phrase")
                    || auth.contains("clipboard")
                    || auth.contains("continuity")
                ) {
                    val providerClass = chain.thisObject.javaClass
                    if (!HOOKED_PROVIDERS.add(providerClass.name)) return@hook r
                    log("hooking provider: ${providerClass.name} authority=$auth")
                    hookProviderOpenFile(providerClass)
                }
                r
            }
            log("OK: global attachInfo hook")
        } catch (t: Throwable) {
            log("FAIL: global attachInfo hook - $t")
        }
    }

    private fun hookProviderOpenFile(providerClass: Class<*>) {
        hookFileMethod(providerClass, "openFile", Uri::class.java, String::class.java)
        hookFileMethod(
            providerClass, "openFile", Uri::class.java, String::class.java,
            android.os.CancellationSignal::class.java
        )
        hookFileMethod(providerClass, "openAssetFile", Uri::class.java, String::class.java)
    }

    private fun hookFileMethod(
        providerClass: Class<*>,
        name: String,
        vararg paramTypes: Class<*>
    ) {
        try {
            val m = providerClass.getDeclaredMethod(name, *paramTypes)
            m.isAccessible = true
            XposedInit.hook(m) { chain ->
                try {
                    return@hook chain.proceed()
                } catch (t: Throwable) {
                    val msg = t.message ?: ""
                    val uri = chain.getArg(0) as? Uri
                    if ((t is SecurityException || t is IllegalStateException)
                        && (msg.contains("Invalid caller") || msg.contains("Permission Denied"))
                    ) {
                        log("$name(${providerClass.simpleName}) exception suppressed for: $uri")
                        return@hook null
                    }
                    log("$name(${providerClass.simpleName}) EXCEPTION: ${t.javaClass.simpleName}: $msg uri=$uri")
                    throw t
                }
            }
            log("OK: $name on ${providerClass.name}")
        } catch (e: NoSuchMethodException) {
            log("${providerClass.simpleName} does not override $name(${paramTypes.size} arg)")
        } catch (t: Throwable) {
            log("FAIL: $name on ${providerClass.name} - $t")
        }
    }

    // ====== Hook 3: SecurityException 构造拦截 ======

    private fun hookSecurityException() {
        hookSecExConstructor(String::class.java)
        hookSecExConstructor(String::class.java, Throwable::class.java)
        hookSecExConstructor()
    }

    private fun hookSecExConstructor(vararg paramTypes: Class<*>) {
        try {
            val ctor = Reflect.findConstructor(SecurityException::class.java, *paramTypes)
            // 用 PASSTHROUGH：这里故意抛 IllegalStateException 改变异常类型，
            // 若被 PROTECTIVE 吞掉就失效了，必须让异常传播给调用方。
            XposedInit.hookPassthrough(ctor) { chain ->
                val args = chain.getArgs()
                val msg = if (args.isNotEmpty() && args[0] is String) args[0] as String else ""
                if (!msg.contains("Permission Denied") && !msg.contains("Invalid caller")) {
                    return@hookPassthrough chain.proceed()
                }
                val st = Exception().stackTrace
                for (e in st) {
                    if (e.className.contains("miui.provider") || e.className.contains("miui.phrase")) {
                        log("SecurityException BLOCKED: $msg")
                        throw IllegalStateException("BYPASSED: $msg")
                    }
                }
                chain.proceed()
            }
        } catch (ignored: Throwable) {
            // 构造函数不存在时忽略
        }
    }
}
