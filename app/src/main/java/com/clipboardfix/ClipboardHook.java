package com.clipboardfix;

import android.content.ContentProvider;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.Base64;
import android.util.LruCache;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 剪贴板修复三件套，只在 com.miui.phrase 进程内安装。
 *
 * <p>Hook 1：包名伪造，绕过 V4.7.7 的白名单校验
 * <p>Hook 2：ContentProvider 查询改写，吞异常 + thumbImage WebP 转 PNG
 * <p>Hook 3：SecurityException 构造拦截
 */
public final class ClipboardHook {

    private static final int SYSTEM_UID = 1000;
    private static final String[] ALLOWED_PACKAGES = {
            "com.sohu.inputmethod.sogou.xiaomi",
            "com.xiaomi.type"
    };

    /** 已 hook 过 openFile 的 provider，避免重复挂载。 */
    private static final Set<String> HOOKED_PROVIDERS =
            Collections.synchronizedSet(new HashSet<String>());

    /**
     * thumbImage 转换结果缓存。
     *
     * <p>key 是原始的 base64 内容，value 是转换后的 PNG base64。
     * 剪贴板面板会被频繁查询，不缓存的话每条图片每次都要重新解码再编码，条目多时明显卡顿。
     * 若某条内容本就无需转换，则把 value 存成与 key 相同的值，下次命中时直接原样返回。
     * LruCache 自身线程安全。
     */
    private static final LruCache<String, String> THUMB_CACHE = new LruCache<>(32);

    private static boolean globalFileHooked = false;

    private ClipboardHook() {
    }

    public static void init() {
        hookPackageManager();
        hookAttachInfo();
        hookSecurityException();
    }

    private static void log(String msg) {
        XposedInit.log(msg);
    }

    // ====== Hook 1: PackageManager.getNameForUid / getPackagesForUid ======

    private static void hookPackageManager() {
        Class<?> appPm = findApplicationPackageManager();
        if (appPm != null) {
            hookNameForUidOn(appPm);
            hookPkgsForUidOn(appPm);
        } else {
            // 兜底：找不到具体实现时直接 hook 抽象声明
            hookNameForUidOn(PackageManager.class);
            hookPkgsForUidOn(PackageManager.class);
        }
    }

    private static Class<?> findApplicationPackageManager() {
        try {
            return Class.forName("android.app.ApplicationPackageManager",
                    false, PackageManager.class.getClassLoader());
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean hookNameForUidOn(Class<?> clazz) {
        try {
            Method m = Reflect.findMethod(clazz, "getNameForUid", int.class);
            XposedInit.hook(m, chain -> {
                Object r = chain.proceed();
                // DexKit 精确校验期间放行真实包名
                if (Bypass.isActive()) return r;

                int uid = (Integer) chain.getArg(0);
                String result = (String) r;
                if (uid > SYSTEM_UID && uid < 100000) {
                    // 系统服务一律不伪造
                    if (isSystemService(result)) return r;
                    // 白名单内的小米输入法本来就能通过
                    if (isWhitelisted(result)) return r;
                    log("getNameForUid spoofed: " + uid
                            + " (" + result + ") -> " + ALLOWED_PACKAGES[0]);
                    return ALLOWED_PACKAGES[0];
                }
                return r;
            });
            log("OK: getNameForUid on " + clazz.getSimpleName());
            return true;
        } catch (Throwable t) {
            log("FAIL: getNameForUid on " + clazz.getSimpleName() + " - " + t);
            return false;
        }
    }

    private static boolean hookPkgsForUidOn(Class<?> clazz) {
        try {
            Method m = Reflect.findMethod(clazz, "getPackagesForUid", int.class);
            XposedInit.hook(m, chain -> {
                Object r = chain.proceed();
                if (Bypass.isActive()) return r;

                int uid = (Integer) chain.getArg(0);
                String[] result = (String[]) r;
                if (uid > SYSTEM_UID && uid < 100000) {
                    if (result != null && result.length > 0) {
                        if (isSystemService(result[0])) return r;
                        if (isWhitelisted(result[0])) return r;
                        log("getPackagesForUid spoofed: " + uid
                                + " (" + result[0] + ") -> " + ALLOWED_PACKAGES[0]);
                        return new String[]{ALLOWED_PACKAGES[0]};
                    }
                }
                return r;
            });
            log("OK: getPackagesForUid on " + clazz.getSimpleName());
            return true;
        } catch (Throwable t) {
            log("FAIL: getPackagesForUid on " + clazz.getSimpleName() + " - " + t);
            return false;
        }
    }

    /** 系统服务永不伪造，避免误伤 milink、phrase provider 等。 */
    private static boolean isSystemService(String pkgName) {
        return pkgName != null && (
                pkgName.startsWith("com.miui.") ||
                        pkgName.startsWith("com.xiaomi.") ||
                        pkgName.startsWith("android.") ||
                        pkgName.startsWith("com.android.") ||
                        pkgName.contains("milink")
        );
    }

    private static boolean isWhitelisted(String pkgName) {
        if (pkgName == null) return false;
        for (String allowed : ALLOWED_PACKAGES) {
            if (allowed.equals(pkgName)) return true;
        }
        return false;
    }

    // ====== Hook 2: ContentProvider.attachInfo -> 定位 input / phrase provider ======

    private static void hookAttachInfo() {
        try {
            Method m = Reflect.findMethod(ContentProvider.class, "attachInfo",
                    Context.class, ProviderInfo.class);
            XposedInit.hook(m, chain -> {
                Object r = chain.proceed();
                Class<?> clazz = chain.getThisObject().getClass();
                if (clazz.equals(ContentProvider.class)) return r;
                ProviderInfo info = (ProviderInfo) chain.getArg(1);
                if (info != null && info.authority != null
                        && info.authority.contains("input")) {
                    log("Found INPUT provider: " + clazz.getName()
                            + " authority=" + info.authority);
                    hookConcreteQueryMethod(clazz);
                }
                hookGlobalContentProviderFile();
                return r;
            });
            log("OK: ContentProvider.attachInfo");
        } catch (Throwable t) {
            log("FAIL: ContentProvider.attachInfo - " + t);
        }
    }

    private static void hookConcreteQueryMethod(Class<?> providerClass) {
        try {
            boolean foundQuery = false;
            for (Method m : providerClass.getDeclaredMethods()) {
                Class<?>[] params = m.getParameterTypes();
                if (params.length == 5
                        && Uri.class.isAssignableFrom(params[0])
                        && String[].class.equals(params[1])
                        && String.class.equals(params[2])
                        && String[].class.equals(params[3])
                        && String.class.equals(params[4])) {

                    log("Found query: " + providerClass.getName() + "." + m.getName());
                    final Method queryMethod = m;
                    XposedInit.hook(queryMethod, chain -> {
                        Cursor cursor;
                        try {
                            cursor = (Cursor) chain.proceed();
                        } catch (SecurityException se) {
                            log("query CAUGHT SecurityException");
                            return null;
                        }
                        if (cursor == null) return null;

                        String[] cols = cursor.getColumnNames();
                        log("query RESULT: " + cursor.getCount()
                                + " rows, cols=" + Arrays.toString(cols));

                        int phraseContentIdx = -1;
                        for (int i = 0; i < cols.length; i++) {
                            if ("phrase_content".equals(cols[i])) {
                                phraseContentIdx = i;
                                break;
                            }
                        }
                        if (phraseContentIdx < 0) return cursor;

                        boolean needsConversion = false;
                        List<Object[]> allRows = new ArrayList<>();
                        if (cursor.moveToFirst()) {
                            do {
                                Object[] row = new Object[cols.length];
                                for (String col : cols) {
                                    int idx = cursor.getColumnIndex(col);
                                    if (idx >= 0) {
                                        String val = cursor.getString(idx);
                                        row[idx] = val;
                                        if (idx == phraseContentIdx && val != null
                                                && val.contains("\"thumbImage\":\"")) {
                                            needsConversion = true;
                                        }
                                    }
                                }
                                allRows.add(row);
                            } while (cursor.moveToNext());
                        }

                        if (!needsConversion) return cursor;

                        log("converting thumbImage WebP->PNG for " + allRows.size() + " rows");
                        MatrixCursor newCursor = new MatrixCursor(cols);
                        for (Object[] row : allRows) {
                            Object[] newRow = row.clone();
                            String content = (String) row[phraseContentIdx];
                            if (content != null && content.contains("\"thumbImage\":\"")) {
                                newRow[phraseContentIdx] = convertThumbImageWebPToPng(content);
                            }
                            newCursor.addRow(newRow);
                        }
                        log("conversion done, replaced cursor");
                        return newCursor;
                    });
                    foundQuery = true;
                    break;
                }
            }
            if (!foundQuery) {
                log("WARN: no query method found on " + providerClass.getName());
            }
        } catch (Throwable t) {
            log("FAIL: hookConcreteQueryMethod - " + t);
        }
    }

    /**
     * 系统输入法（小米搜狗/小米智能）能渲染 WebP，第三方输入法（微信键盘等）不行，
     * 这里转成 PNG 以修复图片条目显示异常。
     */
    private static String convertThumbImageWebPToPng(String json) {
        try {
            String marker = "\"thumbImage\":\"";
            int thumbStart = json.indexOf(marker);
            if (thumbStart == -1) return json;
            thumbStart += marker.length();
            int thumbEnd = json.indexOf("\"", thumbStart);
            if (thumbEnd == -1) return json;

            String rawBase64 = json.substring(thumbStart, thumbEnd);

            // 命中缓存直接拼回去，省掉解码 + 编码
            String cached = THUMB_CACHE.get(rawBase64);
            if (cached != null) {
                return json.substring(0, thumbStart) + cached + json.substring(thumbEnd);
            }

            byte[] imageBytes = Base64.decode(rawBase64, Base64.DEFAULT);

            // 非 WebP（不以 RIFF 开头）不做转换，记进缓存避免下次重复解码
            if (imageBytes.length < 12
                    || imageBytes[0] != 'R' || imageBytes[1] != 'I'
                    || imageBytes[2] != 'F' || imageBytes[3] != 'F') {
                THUMB_CACHE.put(rawBase64, rawBase64);
                return json;
            }

            Bitmap bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length);
            if (bitmap == null) {
                log("WARN: failed to decode WebP bitmap");
                THUMB_CACHE.put(rawBase64, rawBase64);
                return json;
            }

            ByteArrayOutputStream pngOut = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, pngOut);
            byte[] pngBytes = pngOut.toByteArray();
            String base64Png = Base64.encodeToString(pngBytes, Base64.NO_WRAP);
            bitmap.recycle();

            THUMB_CACHE.put(rawBase64, base64Png);

            log("thumbImage WebP->PNG: " + imageBytes.length
                    + " bytes -> " + pngBytes.length + " bytes");
            return json.substring(0, thumbStart) + base64Png + json.substring(thumbEnd);
        } catch (Throwable t) {
            log("convertThumbImageWebPToPng error: " + t);
            return json;
        }
    }

    /** 对 phrase / clipboard / continuity 相关 provider 抑制 openFile 的权限异常。 */
    private static synchronized void hookGlobalContentProviderFile() {
        if (globalFileHooked) return;
        globalFileHooked = true;

        try {
            Method m = Reflect.findMethod(ContentProvider.class, "attachInfo",
                    Context.class, ProviderInfo.class);
            XposedInit.hook(m, chain -> {
                Object r = chain.proceed();
                ProviderInfo info = (ProviderInfo) chain.getArg(1);
                if (info == null || info.authority == null) return r;
                String auth = info.authority;
                if (auth.contains("phrase")
                        || auth.contains("clipboard")
                        || auth.contains("continuity")) {
                    Class<?> providerClass = chain.getThisObject().getClass();
                    if (!HOOKED_PROVIDERS.add(providerClass.getName())) return r;
                    log("hooking provider: " + providerClass.getName()
                            + " authority=" + auth);
                    hookProviderOpenFile(providerClass);
                }
                return r;
            });
            log("OK: global attachInfo hook");
        } catch (Throwable t) {
            log("FAIL: global attachInfo hook - " + t);
        }
    }

    private static void hookProviderOpenFile(Class<?> providerClass) {
        hookFileMethod(providerClass, "openFile", Uri.class, String.class);
        hookFileMethod(providerClass, "openFile", Uri.class, String.class,
                android.os.CancellationSignal.class);
        hookFileMethod(providerClass, "openAssetFile", Uri.class, String.class);
    }

    private static void hookFileMethod(final Class<?> providerClass,
                                       final String name, Class<?>... paramTypes) {
        try {
            Method m = providerClass.getDeclaredMethod(name, paramTypes);
            m.setAccessible(true);
            XposedInit.hook(m, chain -> {
                try {
                    return chain.proceed();
                } catch (Throwable t) {
                    String msg = t.getMessage() != null ? t.getMessage() : "";
                    Uri uri = (Uri) chain.getArg(0);
                    if ((t instanceof SecurityException || t instanceof IllegalStateException)
                            && (msg.contains("Invalid caller") || msg.contains("Permission Denied"))) {
                        log(name + "(" + providerClass.getSimpleName()
                                + ") exception suppressed for: " + uri);
                        return null;
                    }
                    log(name + "(" + providerClass.getSimpleName() + ") EXCEPTION: "
                            + t.getClass().getSimpleName() + ": " + msg + " uri=" + uri);
                    throw t;
                }
            });
            log("OK: " + name + " on " + providerClass.getName());
        } catch (NoSuchMethodException e) {
            log(providerClass.getSimpleName() + " does not override "
                    + name + "(" + paramTypes.length + "arg)");
        } catch (Throwable t) {
            log("FAIL: " + name + " on " + providerClass.getName() + " - " + t);
        }
    }

    // ====== Hook 3: SecurityException 构造拦截 ======

    private static void hookSecurityException() {
        hookSecExConstructor(String.class);
        hookSecExConstructor(String.class, Throwable.class);
        hookSecExConstructor();
    }

    private static void hookSecExConstructor(Class<?>... paramTypes) {
        try {
            Constructor<?> ctor = Reflect.findConstructor(SecurityException.class, paramTypes);
            // 用 PASSTHROUGH：这里故意抛 IllegalStateException 改变异常类型，
            // 若被 PROTECTIVE 吞掉就失效了，必须让异常传播给调用方。
            XposedInit.hookPassthrough(ctor, chain -> {
                java.util.List<Object> args = chain.getArgs();
                String msg = !args.isEmpty() && args.get(0) instanceof String
                        ? (String) args.get(0) : "";
                if (!msg.contains("Permission Denied") && !msg.contains("Invalid caller")) {
                    return chain.proceed();
                }
                StackTraceElement[] st = new Exception().getStackTrace();
                for (StackTraceElement e : st) {
                    if (e.getClassName().contains("miui.provider")
                            || e.getClassName().contains("miui.phrase")) {
                        log("SecurityException BLOCKED: " + msg);
                        throw new IllegalStateException("BYPASSED: " + msg);
                    }
                }
                return chain.proceed();
            });
        } catch (Throwable ignored) {
            // 构造函数不存在时忽略
        }
    }
}
