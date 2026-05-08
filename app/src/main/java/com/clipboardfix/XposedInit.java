package com.clipboardfix;

import android.content.ContentProvider;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Binder;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * LSPosed Module: ClipboardFix for HyperOS 3.0
 * Target: com.miui.phrase
 *
 * v1.0: getNameForUid/getPackagesForUid hook + SecurityException hook
 * v1.1: Removed hookCallingUid (caused false positives)
 * v1.2: Cross-device clipboard merge hook (DISABLED in v1.3 - caused ghost entries)
 *
 * v4.7.7 bug: cross-device synced entries saved to clipboard_cipher_list_temp
 * but clipboard history panel reads from clipboard_cipher_list (empty).
 * This module hooks SharedPreferences.getString() to auto-merge temp entries.
 */
public class XposedInit implements IXposedHookLoadPackage {

    private static final String TAG = "[ClipboardFix]";
    private static final String TARGET_PACKAGE = "com.miui.phrase";
    private static final int SYSTEM_UID = 1000;
    private static final String[] ALLOWED_PACKAGES = {
            "com.sohu.inputmethod.sogou.xiaomi",
            "com.xiaomi.type"
    };

    // SharedPreferences keys
    private static final String SP_CLIPBOARD = "sp_name_clip_board";
    private static final String KEY_CLIP_LIST = "clipboard_cipher_list";
    private static final String KEY_CLIP_LIST_TEMP = "clipboard_cipher_list_temp";

    // Provider class names to track for global hooks
    private final java.util.Set<String> phraseProviderClasses = java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!TARGET_PACKAGE.equals(lpparam.packageName)) {
            return;
        }

        XposedBridge.log(TAG + " ===== Module loaded v1.3 =====");

        hookPackageManager();
        hookAttachInfo();
        hookSecurityException();
        // hookCrossDeviceMerge DISABLED v1.3 - Xiaomi native sync works, merge hook caused ghost entries
    }

    // ====== Hook 1: PackageManager.getNameForUid / getPackagesForUid ======
    private void hookPackageManager() {
        boolean nameHooked = hookNameForUidOn(PackageManager.class);
        boolean pkgHooked = hookPkgsForUidOn(PackageManager.class);

        if (nameHooked && pkgHooked) return;

        try {
            Class<?> appPmClass = Class.forName("android.app.ApplicationPackageManager",
                    false, PackageManager.class.getClassLoader());
            XposedBridge.log(TAG + " Found ApplicationPackageManager");
            if (!nameHooked) hookNameForUidOn(appPmClass);
            if (!pkgHooked) hookPkgsForUidOn(appPmClass);
        } catch (ClassNotFoundException e) {
            XposedBridge.log(TAG + " WARN: ApplicationPackageManager not found");
        }
    }

    private boolean hookNameForUidOn(Class<?> clazz) {
        try {
            XposedHelpers.findAndHookMethod(clazz, "getNameForUid", int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            int uid = (int) param.args[0];
                            String result = (String) param.getResult();
                            if (uid > SYSTEM_UID && uid < 100000) {
                                // Skip system services — never spoof milink, phrase provider, etc.
                                if (isSystemService(result)) return;
                                // Skip whitelisted input methods
                                if (isWhitelisted(result)) return;
                                // Spoof all other packages (third-party input methods)
                                param.setResult(ALLOWED_PACKAGES[0]);
                                XposedBridge.log(TAG + " getNameForUid spoofed: "
                                        + uid + " (" + result + ") -> " + ALLOWED_PACKAGES[0]);
                            }
                        }
                    });
            XposedBridge.log(TAG + " OK: getNameForUid on " + clazz.getSimpleName());
            return true;
        } catch (Throwable t) {
            XposedBridge.log(TAG + " FAIL: getNameForUid on " + clazz.getSimpleName());
            return false;
        }
    }

    private boolean hookPkgsForUidOn(Class<?> clazz) {
        try {
            XposedHelpers.findAndHookMethod(clazz, "getPackagesForUid", int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            int uid = (int) param.args[0];
                            String[] result = (String[]) param.getResult();
                            if (uid > SYSTEM_UID && uid < 100000) {
                                if (result != null && result.length > 0) {
                                    // Skip system services
                                    if (isSystemService(result[0])) return;
                                    // Skip whitelisted input methods
                                    if (isWhitelisted(result[0])) return;
                                    // Spoof all other packages (third-party input methods)
                                    param.setResult(new String[]{ALLOWED_PACKAGES[0]});
                                    XposedBridge.log(TAG + " getPackagesForUid spoofed: "
                                            + uid + " (" + result[0] + ") -> " + ALLOWED_PACKAGES[0]);
                                }
                            }
                        }
                    });
            XposedBridge.log(TAG + " OK: getPackagesForUid on " + clazz.getSimpleName());
            return true;
        } catch (Throwable t) {
            XposedBridge.log(TAG + " FAIL: getPackagesForUid on " + clazz.getSimpleName());
            return false;
        }
    }

    /** Check if package is a system service that should never be spoofed */
    private static boolean isSystemService(String pkgName) {
        return pkgName != null && (
            pkgName.startsWith("com.miui.") ||
            pkgName.startsWith("com.xiaomi.") ||
            pkgName.startsWith("android.") ||
            pkgName.startsWith("com.android.") ||
            pkgName.contains("milink")
        );
    }

    /** Check if package is a whitelisted system input method */
    private static boolean isWhitelisted(String pkgName) {
        if (pkgName == null) return false;
        for (String allowed : ALLOWED_PACKAGES) {
            if (allowed.equals(pkgName)) return true;
        }
        return false;
    }

    // ====== Hook 2: ContentProvider.attachInfo -> find InputProvider query ======
    private void hookAttachInfo() {
        try {
            XposedHelpers.findAndHookMethod(
                    ContentProvider.class, "attachInfo",
                    android.content.Context.class,
                    android.content.pm.ProviderInfo.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Class<?> clazz = param.thisObject.getClass();
                            if (clazz.equals(ContentProvider.class)) return;
                            android.content.pm.ProviderInfo info =
                                    (android.content.pm.ProviderInfo) param.args[1];
                            if (info != null && info.authority != null
                                    && info.authority.contains("input")) {
                                XposedBridge.log(TAG + " Found INPUT provider: "
                                        + clazz.getName() + " authority=" + info.authority);
                                hookConcreteQueryMethod(clazz);
                            }
                        }
                    });
            XposedBridge.log(TAG + " OK: ContentProvider.attachInfo");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " FAIL: ContentProvider.attachInfo - " + t.getMessage());
        }
    }

    private void hookConcreteQueryMethod(Class<?> providerClass) {
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

                    XposedBridge.log(TAG + " Found query: " + providerClass.getName()
                            + "." + m.getName());
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (param.hasThrowable()) {
                                Throwable t = param.getThrowable();
                                if (t instanceof SecurityException) {
                                    XposedBridge.log(TAG + " query CAUGHT SecurityException");
                                    param.setThrowable(null);
                                }
                                return;
                            }
                            // Diagnostic: log query result info
                            Cursor cursor = (Cursor) param.getResult();
                            if (cursor != null) {
                                String[] cols = cursor.getColumnNames();
                                XposedBridge.log(TAG + " query RESULT: " + cursor.getCount()
                                        + " rows, cols=" + java.util.Arrays.toString(cols));
                                // Check if phrase_content column exists
                                int phraseContentIdx = -1;
                                for (int i = 0; i < cols.length; i++) {
                                    if ("phrase_content".equals(cols[i])) {
                                        phraseContentIdx = i;
                                        break;
                                    }
                                }
                                // Log first row and collect data for conversion
                                boolean needsConversion = false;
                                java.util.List<Object[]> allRows = new java.util.ArrayList<>();
                                if (cursor.moveToFirst()) {
                                    do {
                                        Object[] row = new Object[cols.length];
                                        for (String col : cols) {
                                            int idx = cursor.getColumnIndex(col);
                                            if (idx >= 0) {
                                                String val = cursor.getString(idx);
                                                row[idx] = val;
                                                // Check if this is an image entry
                                                if (idx == phraseContentIdx && val != null
                                                        && val.contains("\"thumbImage\":\"")) {
                                                    needsConversion = true;
                                                }
                                                // Log first row only
                                                if (cursor.getPosition() == 0 && val != null && val.length() > 500) {
                                                    val = val.substring(0, 500) + "...";
                                                    XposedBridge.log(TAG + "   " + col + "=" + val);
                                                }
                                            }
                                        }
                                        allRows.add(row);
                                    } while (cursor.moveToNext());
                                }
                                // Convert thumbImage from WebP to PNG if needed
                                if (needsConversion && phraseContentIdx >= 0) {
                                    XposedBridge.log(TAG + " converting thumbImage WebP->PNG for " + allRows.size() + " rows");
                                    MatrixCursor newCursor = new MatrixCursor(cols);
                                    for (Object[] row : allRows) {
                                        Object[] newRow = row.clone();
                                        String content = (String) row[phraseContentIdx];
                                        if (content != null && content.contains("\"thumbImage\":\"")) {
                                            newRow[phraseContentIdx] = convertThumbImageWebPToPng(content);
                                        }
                                        newCursor.addRow(newRow);
                                    }
                                    param.setResult(newCursor);
                                    XposedBridge.log(TAG + " conversion done, replaced cursor");
                                }
                            }
                        }
                    });
                    foundQuery = true;
                    break;
                }
            }
            if (!foundQuery) {
                XposedBridge.log(TAG + " WARN: no query method found on " + providerClass.getName());
            }
            // Log all declared methods for debugging
            StringBuilder methods = new StringBuilder();
            for (Method m : providerClass.getDeclaredMethods()) {
                methods.append(m.getName()).append("(").append(m.getParameterCount()).append(") ");
            }
            XposedBridge.log(TAG + " Provider methods: " + methods.toString());
            // Register provider class and hook global methods
            phraseProviderClasses.add(providerClass.getName());
            hookGlobalContentProviderFile();
        } catch (Throwable t) {
            XposedBridge.log(TAG + " FAIL: hookConcreteQueryMethod - " + t.getMessage());
        }
    }

    /**
     * Convert thumbImage from base64 WebP to base64 PNG.
     * System input methods (小米搜狗/小米智能) can render WebP, but third-party
     * input methods (微信键盘 etc.) cannot. Converting to PNG fixes image display.
     */
    private String convertThumbImageWebPToPng(String json) {
        try {
            // Find thumbImage value
            String marker = "\"thumbImage\":\"";
            int thumbStart = json.indexOf(marker);
            if (thumbStart == -1) return json;
            thumbStart += marker.length();
            int thumbEnd = json.indexOf("\"", thumbStart);
            if (thumbEnd == -1) return json;

            String base64Data = json.substring(thumbStart, thumbEnd);

            // Decode base64
            byte[] imageBytes = Base64.decode(base64Data, Base64.DEFAULT);

            // Check if it's WebP (starts with RIFF)
            if (imageBytes.length < 12
                    || imageBytes[0] != 'R' || imageBytes[1] != 'I'
                    || imageBytes[2] != 'F' || imageBytes[3] != 'F') {
                // Not WebP, skip conversion
                XposedBridge.log(TAG + " thumbImage is not WebP, skipping conversion");
                return json;
            }

            // Decode WebP to Bitmap
            Bitmap bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length);
            if (bitmap == null) {
                XposedBridge.log(TAG + " WARN: failed to decode WebP bitmap");
                return json;
            }

            // Encode as PNG
            ByteArrayOutputStream pngOut = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, pngOut);
            byte[] pngBytes = pngOut.toByteArray();

            // Base64 encode PNG (no wrapping)
            String base64Png = Base64.encodeToString(pngBytes, Base64.NO_WRAP);

            bitmap.recycle();

            // Replace in JSON: keep the original prefix (up to and including the opening quote)
            String prefix = json.substring(0, thumbStart);
            String suffix = json.substring(thumbEnd);

            XposedBridge.log(TAG + " thumbImage WebP->PNG: " + imageBytes.length + " bytes -> " + pngBytes.length + " bytes");
            return prefix + base64Png + suffix;
        } catch (Exception e) {
            XposedBridge.log(TAG + " convertThumbImageWebPToPng error: " + e.getMessage());
            return json;
        }
    }

    /**
     * Global hook on ContentProvider.attachInfo to discover and hook providers by authority.
     * Hooks openFile on providers whose authority matches phrase/clipboard patterns.
     */
    private boolean globalFileHooked = false;
    private final java.util.Set<String> hookedProviderClasses = java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    private void hookGlobalContentProviderFile() {
        if (globalFileHooked) return;
        globalFileHooked = true;

        XposedHelpers.findAndHookMethod(ContentProvider.class, "attachInfo",
                Context.class, ProviderInfo.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        ProviderInfo info = (ProviderInfo) param.args[1];
                        if (info == null || info.authority == null) return;
                        String auth = info.authority;
                        // Hook phrase provider AND continuity clipboard provider
                        if (auth.contains("phrase") || auth.contains("clipboard") || auth.contains("continuity")) {
                            Class<?> providerClass = param.thisObject.getClass();
                            if (!hookedProviderClasses.add(providerClass.getName())) return;
                            XposedBridge.log(TAG + " hooking provider: " + providerClass.getName()
                                    + " authority=" + auth);
                            hookProviderOpenFile(providerClass);
                        }
                    }
                });
        XposedBridge.log(TAG + " OK: global attachInfo hook");
    }

    /**
     * Hook openFile on a specific provider class.
     */
    private void hookProviderOpenFile(Class<?> providerClass) {
        // openFile(Uri, String)
        try {
            Method m = providerClass.getDeclaredMethod("openFile", Uri.class, String.class);
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Uri uri = (Uri) param.args[0];
                    if (param.hasThrowable()) {
                        Throwable t = param.getThrowable();
                        XposedBridge.log(TAG + " openFile(" + providerClass.getSimpleName() + ") EXCEPTION: "
                                + t.getClass().getSimpleName() + ": " + t.getMessage() + " uri=" + uri);
                        String msg = t.getMessage() != null ? t.getMessage() : "";
                        if ((t instanceof SecurityException || t instanceof IllegalStateException)
                                && (msg.contains("Invalid caller") || msg.contains("Permission Denied"))) {
                            param.setThrowable(null);
                            XposedBridge.log(TAG + " openFile exception suppressed for: " + uri);
                        }
                    } else {
                        XposedBridge.log(TAG + " openFile(" + providerClass.getSimpleName() + ") OK: " + uri);
                    }
                }
            });
            XposedBridge.log(TAG + " OK: openFile on " + providerClass.getName());
        } catch (NoSuchMethodException e) {
            XposedBridge.log(TAG + " " + providerClass.getSimpleName() + " does not override openFile(2arg)");
        }

        // openFile(Uri, String, CancellationSignal)
        try {
            Method m = providerClass.getDeclaredMethod("openFile", Uri.class, String.class,
                    android.os.CancellationSignal.class);
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Uri uri = (Uri) param.args[0];
                    if (param.hasThrowable()) {
                        Throwable t = param.getThrowable();
                        XposedBridge.log(TAG + " openFile3(" + providerClass.getSimpleName() + ") EXCEPTION: "
                                + t.getClass().getSimpleName() + ": " + t.getMessage() + " uri=" + uri);
                        String msg = t.getMessage() != null ? t.getMessage() : "";
                        if ((t instanceof SecurityException || t instanceof IllegalStateException)
                                && (msg.contains("Invalid caller") || msg.contains("Permission Denied"))) {
                            param.setThrowable(null);
                            XposedBridge.log(TAG + " openFile3 exception suppressed for: " + uri);
                        }
                    }
                }
            });
            XposedBridge.log(TAG + " OK: openFile3 on " + providerClass.getName());
        } catch (NoSuchMethodException e) {
            XposedBridge.log(TAG + " " + providerClass.getSimpleName() + " does not override openFile(3arg)");
        }

        // openAssetFile(Uri, String)
        try {
            Method m = providerClass.getDeclaredMethod("openAssetFile", Uri.class, String.class);
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Uri uri = (Uri) param.args[0];
                    if (param.hasThrowable()) {
                        Throwable t = param.getThrowable();
                        XposedBridge.log(TAG + " openAssetFile(" + providerClass.getSimpleName() + ") EXCEPTION: "
                                + t.getClass().getSimpleName() + ": " + t.getMessage() + " uri=" + uri);
                        String msg = t.getMessage() != null ? t.getMessage() : "";
                        if ((t instanceof SecurityException || t instanceof IllegalStateException)
                                && (msg.contains("Invalid caller") || msg.contains("Permission Denied"))) {
                            param.setThrowable(null);
                            XposedBridge.log(TAG + " openAssetFile exception suppressed");
                        }
                    }
                }
            });
            XposedBridge.log(TAG + " OK: openAssetFile on " + providerClass.getName());
        } catch (NoSuchMethodException e) {
            XposedBridge.log(TAG + " " + providerClass.getSimpleName() + " does not override openAssetFile");
        }
    }

    // ====== Hook 3: SecurityException constructor ======
    private void hookSecurityException() {
        hookSecExConstructor(String.class);
        hookSecExConstructor(String.class, Throwable.class);
        hookSecExConstructor();
    }

    private void hookSecExConstructor(Class<?>... paramTypes) {
        try {
            Object[] params = new Object[paramTypes.length + 1];
            for (int i = 0; i < paramTypes.length; i++) {
                params[i] = paramTypes[i];
            }
            params[paramTypes.length] = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    String msg = param.args.length > 0 && param.args[0] instanceof String
                            ? (String) param.args[0] : "";
                    if (!msg.contains("Permission Denied") && !msg.contains("Invalid caller")) {
                        return;
                    }
                    StackTraceElement[] st = new Exception().getStackTrace();
                    for (StackTraceElement e : st) {
                        if (e.getClassName().contains("miui.provider")
                                || e.getClassName().contains("miui.phrase")) {
                            XposedBridge.log(TAG + " SecurityException BLOCKED: " + msg);
                            throw new IllegalStateException("BYPASSED: " + msg);
                        }
                    }
                }
            };
            XposedHelpers.findAndHookConstructor(SecurityException.class, params);
        } catch (Throwable t) {
            // ignore
        }
    }

    // ====== Hook 4: Cross-device clipboard merge (v1.2) ======
    //
    // Problem: v4.7.7 saves cross-device synced entries to clipboard_cipher_list_temp
    // but clipboard history panel only reads clipboard_cipher_list (which is empty).
    //
    // Solution: Hook SharedPreferences.getString("clipboard_cipher_list").
    // When main list is empty ("[]"), check clipboard_cipher_list_temp for cross-device
    // entries and merge them into the result.
    //
    private void hookCrossDeviceMerge(XC_LoadPackage.LoadPackageParam lpparam) {
        // Try common SharedPreferencesImpl class names
        String[] classNames = {
            "android.app.SharedPreferencesImpl",
            "android.app.ContextImpl$SharedPreferencesImpl",
        };

        boolean hooked = false;
        for (String className : classNames) {
            try {
                Class<?> spClass = Class.forName(className, false, lpparam.classLoader);
                hookGetStringOnClass(spClass);
                hooked = true;
                XposedBridge.log(TAG + " OK: Cross-device merge on " + className);
                break;
            } catch (ClassNotFoundException e) {
                // try next
            } catch (Throwable t) {
                XposedBridge.log(TAG + " WARN: hook on " + className + ": " + t.getMessage());
            }
        }

        if (!hooked) {
            // Fallback: hook ContextImpl.getSharedPreferences to discover impl class
            try {
                XposedHelpers.findAndHookMethod(
                    "android.app.ContextImpl", lpparam.classLoader,
                    "getSharedPreferences", String.class, int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            String name = (String) param.args[0];
                            if (SP_CLIPBOARD.equals(name)) {
                                SharedPreferences sp = (SharedPreferences) param.getResult();
                                if (sp == null) return;
                                Class<?> spClass = sp.getClass();
                                XposedBridge.log(TAG + " Discovered SP impl: " + spClass.getName());
                                hookGetStringOnClass(spClass);
                            }
                        }
                    });
                XposedBridge.log(TAG + " OK: getSharedPreferences discovery hook");
            } catch (Throwable t) {
                XposedBridge.log(TAG + " FAIL: Cross-device merge - " + t.getMessage());
            }
        }
    }

    private boolean getStringHooked = false;

    private void hookGetStringOnClass(Class<?> spClass) {
        if (getStringHooked) return;

        try {
            XposedHelpers.findAndHookMethod(spClass, "getString", String.class, String.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        String key = (String) param.args[0];
                        if (!KEY_CLIP_LIST.equals(key)) return;

                        String result = (String) param.getResult();
                        if (result == null) return;

                        try {
                            SharedPreferences sp = (SharedPreferences) param.thisObject;
                            String tempData = sp.getString(KEY_CLIP_LIST_TEMP, null);
                            if (tempData == null || tempData.isEmpty()) return;

                            byte[] tempDecoded = android.util.Base64.decode(
                                tempData.trim(), android.util.Base64.DEFAULT);
                            String tempJson = new String(tempDecoded, "UTF-8");

                            if ("[]".equals(tempJson.trim()) || tempJson.trim().isEmpty()) {
                                return;
                            }

                            // Parse temp list to find cross-device entries
                            org.json.JSONArray tempArr = new org.json.JSONArray(tempJson);
                            if (tempArr.length() == 0) return;

                            // Parse main list
                            String mainTrimmed = result.trim();
                            org.json.JSONArray mainArr;
                            if ("[]".equals(mainTrimmed) || mainTrimmed.isEmpty()) {
                                mainArr = new org.json.JSONArray();
                            } else {
                                byte[] mainDecoded = android.util.Base64.decode(
                                    mainTrimmed, android.util.Base64.DEFAULT);
                                mainArr = new org.json.JSONArray(
                                    new String(mainDecoded, "UTF-8"));
                            }

                            // Find newest timestamp in main list
                            long newestMainTime = 0;
                            for (int i = 0; i < mainArr.length(); i++) {
                                org.json.JSONObject item = mainArr.getJSONObject(i);
                                if (item.optLong("time", 0) > newestMainTime) {
                                    newestMainTime = item.optLong("time", 0);
                                }
                            }

                            // Collect new cross-device entries from temp
                            boolean merged = false;
                            int addedCount = 0;
                            for (int i = 0; i < tempArr.length(); i++) {
                                org.json.JSONObject tempItem = tempArr.getJSONObject(i);
                                if (tempItem.optBoolean("isAcrossDevices", false)
                                        && tempItem.optLong("time", 0) > newestMainTime) {
                                    tempItem.put("isTemp", false);
                                    mainArr.put(tempItem);
                                    merged = true;
                                    addedCount++;
                                }
                            }

                            if (!merged) return;

                            // Sort by time descending (newest first)
                            java.util.List<org.json.JSONObject> sorted = new java.util.ArrayList<>();
                            for (int i = 0; i < mainArr.length(); i++) {
                                sorted.add(mainArr.getJSONObject(i));
                            }
                            java.util.Collections.sort(sorted, new java.util.Comparator<org.json.JSONObject>() {
                                @Override
                                public int compare(org.json.JSONObject a, org.json.JSONObject b) {
                                    return Long.compare(
                                        b.optLong("time", 0), a.optLong("time", 0));
                                }
                            });
                            org.json.JSONArray sortedArr = new org.json.JSONArray();
                            for (org.json.JSONObject obj : sorted) {
                                sortedArr.put(obj);
                            }

                            String mergedJson = sortedArr.toString();
                            String mergedB64 = android.util.Base64.encodeToString(
                                mergedJson.getBytes("UTF-8"), android.util.Base64.NO_WRAP);

                            SharedPreferences.Editor editor = sp.edit();
                            editor.putString(KEY_CLIP_LIST, mergedB64);
                            editor.apply();

                            param.setResult(mergedB64);
                            XposedBridge.log(TAG + " Merge: " + addedCount
                                + " cross-device entries appended, total=" + sortedArr.length());
                        } catch (Exception e) {
                            XposedBridge.log(TAG + " Merge error: " + e.getMessage());
                        }
                    }
                });
            getStringHooked = true;
            XposedBridge.log(TAG + " OK: SharedPreferences.getString hook on " + spClass.getName());
        } catch (Throwable t) {
            XposedBridge.log(TAG + " FAIL: getString hook - " + t.getMessage());
        }
    }
}
