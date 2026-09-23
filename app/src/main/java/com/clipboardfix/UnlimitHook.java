package com.clipboardfix;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.text.InputFilter;
import android.text.TextUtils;
import android.view.View;
import android.widget.EditText;

import org.json.JSONArray;
import org.json.JSONObject;
import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindField;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.query.matchers.FieldMatcher;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.FieldData;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import io.github.libxposed.api.XposedModuleInterface;

/**
 * 解除「常用语 / 剪贴板」的条数、时间与字数限制（只在 com.miui.phrase 内安装，受功能开关控制）。
 *
 * <p>效果对齐 HyperCeiler（AGPL-3.0）「解除输入法全面屏优化常用语剪贴板条数和时间限制」里的
 * 限制解除部分，按本项目的反射/拦截模型独立实现：
 * <ul>
 *   <li><b>剪贴板条数与时间</b>：DexKit 定位保存剪贴板 JSON 的方法（日志串
 *       {@code "get savedList size :"}）。原逻辑会把超过 20 条的旧条目删掉并按时间清理；
 *       这里改写为「新条目 + 全部旧条目」原样合并，条数与存放时间都不设限。
 *       条目的解析/序列化仍走目标应用自己的 {@code MiuiClipboardManager.jsonToBeanList}
 *       与各条目的 {@code toJSONObject}，数据格式不受影响。</li>
 *   <li><b>常用语条数</b>：{@code InputMethodUtil.sPhraseListSize = 0} 绕过 20 条上限
 *       （{@code queryPhrase} 重新计数后再次清零）；管理页添加按钮的上限拦截一并放行。</li>
 *   <li><b>常用语字数</b>：DexKit 定位常用语编辑框后清空长度过滤器。</li>
 * </ul>
 */
public final class UnlimitHook {

    /** 剪贴板 JSON 构建方法的特征日志串。 */
    private static final String CLIPBOARD_JSON_MARK = "get savedList size :";
    /** 常用语编辑界面所在类的特征资源名。 */
    private static final String PHRASE_UI_MARK = "phrase_list";
    /** 常用语编辑界面布局加载方法的特征日志串。 */
    private static final String PHRASE_INFLATE_MARK = "layout_inflater";

    private static final String CLIPBOARD_MANAGER = "com.miui.inputmethod.MiuiClipboardManager";
    private static final String INPUT_METHOD_UTIL = "com.miui.inputmethod.InputMethodUtil";
    private static final String PHRASE_EDIT_ACTIVITY = "com.miui.phrase.PhraseEditActivity";
    private static final String PHRASE_ADD_ACTIVITY = "com.miui.phrase.AddPhraseActivity";
    private static final String PHRASE_ADD_ACTION = "com.miui.intent.action.PHRASE_ADD";

    private UnlimitHook() {
    }

    public static void init(XposedModuleInterface.PackageLoadedParam param) {
        ClassLoader cl;
        try {
            cl = param.getDefaultClassLoader();
        } catch (Throwable t) {
            log("FAIL: no classloader - " + t);
            return;
        }
        // 固定类名的部分直接装，不依赖 DexKit
        hookPhraseCountLimit(cl);

        DexKitBridge bridge = null;
        try {
            System.loadLibrary("dexkit");
            bridge = DexKitBridge.create(param.getApplicationInfo().sourceDir);
            if (bridge == null) {
                log("FAIL: DexKitBridge.create returned null");
                return;
            }
            hookClipboardJsonLimit(bridge, cl);
            hookPhraseCharLimit(bridge, cl);
        } catch (Throwable t) {
            log("FAIL: DexKit - " + t);
        } finally {
            if (bridge != null) {
                try {
                    bridge.close();
                } catch (Throwable ignored) {
                    // 释放失败不影响功能
                }
            }
        }
    }

    /**
     * 剪贴板 JSON 构建：去掉 20 条与时间清理，新旧条目全部保留。
     *
     * <p>方法形如 {@code buildClipboardJson(..., newModel, oldJson)}，返回 JSON 字符串。
     * 这里接管返回值：新条目在前、旧条目在后，逐条走目标应用自己的序列化。
     */
    private static void hookClipboardJsonLimit(DexKitBridge bridge, ClassLoader cl) {
        try {
            List<MethodData> found = bridge.findMethod(
                    FindMethod.create()
                            .matcher(MethodMatcher.create()
                                    .usingStrings(CLIPBOARD_JSON_MARK)));
            if (found == null || found.isEmpty()) {
                log("WARN: clipboard json method not found");
                return;
            }
            if (found.size() > 1) {
                log("WARN: " + found.size() + " candidates for clipboard json, using the first");
            }

            final Class<?> mgrCls = Reflect.findClassIfExists(CLIPBOARD_MANAGER, cl);
            if (mgrCls == null) {
                log("WARN: " + CLIPBOARD_MANAGER + " not found");
                return;
            }

            final Method method = found.get(0).getMethodInstance(cl);
            method.setAccessible(true);
            XposedInit.hook(method, chain -> {
                List<Object> args = chain.getArgs();
                if (args == null || args.size() < 4) {
                    log("clipboard json: unexpected argc="
                            + (args == null ? -1 : args.size()) + ", passthrough");
                    return chain.proceed();
                }
                try {
                    Object newModel = args.get(2);
                    String oldJson = args.get(3) instanceof String ? (String) args.get(3) : null;

                    List<Object> models = new ArrayList<>();
                    if (newModel != null) {
                        models.add(newModel);
                    }
                    int oldCount = 0;
                    if (!TextUtils.isEmpty(oldJson)) {
                        Object oldList = Reflect.callStaticMethod(
                                mgrCls, "jsonToBeanList", oldJson);
                        if (oldList instanceof List) {
                            oldCount = ((List<?>) oldList).size();
                            models.addAll((List<?>) oldList);
                        }
                    }

                    JSONArray result = new JSONArray();
                    for (Object model : models) {
                        if (model == null) {
                            continue;
                        }
                        Object json = Reflect.callMethod(model, "toJSONObject");
                        if (json instanceof JSONObject) {
                            result.put(json);
                        }
                    }
                    log("clipboard json merge: new=" + (newModel == null ? 0 : 1)
                            + " old=" + oldCount + " total=" + result.length());
                    return result.toString();
                } catch (Throwable t) {
                    log("clipboard json rebuild error - " + t);
                    return chain.proceed();
                }
            });
            log("OK: unlimited clipboard json on " + method.getName());
        } catch (Throwable t) {
            log("FAIL: clipboard json hook - " + t);
        }
    }

    /**
     * 常用语条数：清零计数字段 + 放行添加按钮。
     *
     * <p>{@code sPhraseListSize} 是「当前条数」，上限检查形如
     * {@code sPhraseListSize >= 20}；恒为 0 即永远没满。{@code queryPhrase}
     * 每次查询都会重新计数，所以查完再清一次。
     */
    private static void hookPhraseCountLimit(ClassLoader cl) {
        try {
            final Class<?> util = Reflect.findClass(INPUT_METHOD_UTIL, cl);
            Reflect.setStaticField(util, "sPhraseListSize", 0);

            Method queryPhrase = Reflect.findMethod(util, "queryPhrase", Context.class);
            XposedInit.hook(queryPhrase, chain -> {
                Object result = chain.proceed();
                try {
                    Reflect.setStaticField(util, "sPhraseListSize", 0);
                    log("queryPhrase: count re-zeroed");
                } catch (Throwable ignored) {
                    // 计数字段清零失败则本次保持原样
                }
                return result;
            });
            log("OK: unlimited phrase count on " + queryPhrase.getName());
        } catch (Throwable t) {
            log("WARN: phrase count hook - " + t);
        }

        // 管理页「添加」按钮：原逻辑在达到上限时拦截点击，这里无条件进入添加页
        try {
            Class<?> editActivity = Reflect.findClass(PHRASE_EDIT_ACTIVITY, cl);
            final Class<?> addActivity = Reflect.findClass(PHRASE_ADD_ACTIVITY, cl);
            Method onClick = Reflect.findMethod(editActivity, "onClick", View.class);
            XposedInit.hook(onClick, chain -> {
                Object thiz = chain.getThisObject();
                if (thiz instanceof Activity) {
                    try {
                        Activity activity = (Activity) thiz;
                        Intent intent = new Intent(activity, addActivity);
                        intent.setAction(PHRASE_ADD_ACTION);
                        activity.startActivityForResult(intent, 0);
                        log("phrase add intercepted");
                        return null; // 接管成功：跳过原方法里的上限拦截
                    } catch (Throwable t) {
                        log("add phrase launch error - " + t);
                    }
                }
                return chain.proceed();
            });
            log("OK: phrase add button unlocked");
        } catch (Throwable t) {
            log("WARN: phrase add hook - " + t);
        }
    }

    /** 常用语字数：编辑框布局加载完后清空长度过滤器。 */
    private static void hookPhraseCharLimit(DexKitBridge bridge, ClassLoader cl) {
        try {
            List<MethodData> methods = bridge.findMethod(
                    FindMethod.create()
                            .matcher(MethodMatcher.create()
                                    .declaredClass(ClassMatcher.create()
                                            .usingStrings(PHRASE_UI_MARK))
                                    .usingStrings(PHRASE_INFLATE_MARK)));
            List<FieldData> fieldData = bridge.findField(
                    FindField.create()
                            .matcher(FieldMatcher.create()
                                    .declaredClass(ClassMatcher.create()
                                            .usingStrings(PHRASE_UI_MARK))
                                    .type(EditText.class)));
            Field editTextField = null;
            if (fieldData != null) {
                for (FieldData data : fieldData) {
                    try {
                        Field f = data.getFieldInstance(cl);
                        f.setAccessible(true);
                        editTextField = f;
                        break;
                    } catch (Throwable ignored) {
                        // 换下一个候选
                    }
                }
            }

            if (methods == null || methods.isEmpty() || editTextField == null) {
                log("WARN: phrase edit ui not found (method="
                        + (methods == null ? 0 : methods.size())
                        + ", field=" + (fieldData == null ? 0 : fieldData.size()) + ")");
                return;
            }

            final Field finalEditTextField = editTextField;
            final Method inflateMethod = methods.get(0).getMethodInstance(cl);
            inflateMethod.setAccessible(true);
            XposedInit.hook(inflateMethod, chain -> {
                Object result = chain.proceed();
                try {
                    Object thiz = chain.getThisObject();
                    Object editText = finalEditTextField.get(thiz);
                    if (editText instanceof EditText) {
                        ((EditText) editText).setFilters(new InputFilter[]{
                                new InputFilter.LengthFilter(Integer.MAX_VALUE)});
                        log("phrase edit char limit cleared");
                    }
                } catch (Throwable ignored) {
                    // 清字数限制失败不影响其余功能
                }
                return result;
            });
            log("OK: unlimited phrase chars on " + inflateMethod.getName());
        } catch (Throwable t) {
            log("FAIL: phrase char hook - " + t);
        }
    }

    private static void log(String msg) {
        XposedInit.log("[Unlimit] " + msg);
    }
}
