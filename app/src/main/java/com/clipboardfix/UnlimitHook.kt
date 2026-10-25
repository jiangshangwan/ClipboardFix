package com.clipboardfix

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.text.InputFilter
import android.text.TextUtils
import android.view.View
import android.widget.EditText
import io.github.libxposed.api.XposedModuleInterface
import org.json.JSONArray
import org.json.JSONObject
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindField
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.ClassMatcher
import org.luckypray.dexkit.query.matchers.FieldMatcher
import org.luckypray.dexkit.query.matchers.MethodMatcher
import org.luckypray.dexkit.result.FieldData
import org.luckypray.dexkit.result.MethodData
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * 解除「常用语 / 剪贴板」的条数、时间与字数限制（按开关在 com.miui.phrase 与输入法进程内安装）。
 *
 * <ul>
 *   <li><b>剪贴板条数与时间（保存路径）</b>：DexKit 定位保存剪贴板 JSON 的方法（日志串
 *       {@code "get savedList size :"}）。原逻辑会把超过 20 条的旧条目删掉并按时间清理；
 *       这里改写为「新条目 + 全部旧条目」原样合并，条数与存放时间都不设限。
 *       条目的解析/序列化仍走目标应用自己的 {@code MiuiClipboardManager.jsonToBeanList}
 *       与各条目的 {@code toJSONObject}，数据格式不受影响。
 *       条数开关或时间开关开启时都会安装（原方法把两种清理耦合在一起，无法只拆一半）。</li>
 *   <li><b>剪贴板时间（读取路径）</b>：hook {@code MiuiClipboardManager.getNoExpiredClipboardData}
 *       的第三个参数 curTime，置 0 后 age 恒为负，列表全量保留、过期图片不再被删。
 *       面板实际运行在输入法进程，故除 com.miui.phrase 外，还要在各输入法进程的
 *       动态 dex 加载完成后安装（见 {@link #hookClipboardTimeLimit}）。</li>
 *   <li><b>常用语条数</b>：{@code InputMethodUtil.sPhraseListSize = 0} 绕过 20 条上限
 *       （{@code queryPhrase} 重新计数后再次清零）；管理页添加按钮的上限拦截一并放行。</li>
 *   <li><b>常用语字数</b>：DexKit 定位常用语编辑框后清空长度过滤器。</li>
 * </ul>
 */
object UnlimitHook {

    /** 剪贴板 JSON 构建方法的特征日志串。 */
    private const val CLIPBOARD_JSON_MARK = "get savedList size :"

    /** 常用语编辑界面所在类的特征资源名。 */
    private const val PHRASE_UI_MARK = "phrase_list"

    /** 常用语编辑界面布局加载方法的特征日志串。 */
    private const val PHRASE_INFLATE_MARK = "layout_inflater"

    private const val CLIPBOARD_MANAGER = "com.miui.inputmethod.MiuiClipboardManager"
    private const val INPUT_METHOD_UTIL = "com.miui.inputmethod.InputMethodUtil"
    private const val PHRASE_EDIT_ACTIVITY = "com.miui.phrase.PhraseEditActivity"
    private const val PHRASE_ADD_ACTIVITY = "com.miui.phrase.AddPhraseActivity"
    private const val PHRASE_ADD_ACTION = "com.miui.intent.action.PHRASE_ADD"

    /** 读取路径时间过滤方法名（私有静态）。 */
    private const val CLIPBOARD_TIME_METHOD = "getNoExpiredClipboardData"

    /** 72 小时（毫秒）——仅用于日志对照，hook 本身不依赖该值。 */
    private const val CLIPBOARD_EXPIRE_MS = 259200000L

    /**
     * @param countUnlock 条数上限解锁开关（常用语条数/字数 + 剪贴板保存路径清理）
     * @param timeUnlock  时间限制解锁开关（剪贴板保存路径时间清理 + 读取路径 72h 过滤）
     */
    @JvmStatic
    fun init(
        param: XposedModuleInterface.PackageLoadedParam,
        countUnlock: Boolean,
        timeUnlock: Boolean
    ) {
        val cl: ClassLoader = try {
            param.defaultClassLoader
        } catch (t: Throwable) {
            log("FAIL: no classloader - $t")
            return
        }
        // 固定类名的部分直接装，不依赖 DexKit
        if (countUnlock) {
            hookPhraseCountLimit(cl)
        }
        // 读取路径 72h 过滤放在 DexKit 之前：DexKit 扫描失败不应影响它。
        // phrase 进程内类可直接加载，装这里；输入法进程（面板实际运行处）由
        // ImeUnlockHook 的 loadDex 回调转来安装（见 hookClipboardTimeLimit）。
        if (timeUnlock) {
            hookClipboardTimeLimit(cl)
        }

        var bridge: DexKitBridge? = null
        try {
            System.loadLibrary("dexkit")
            bridge = DexKitBridge.create(param.applicationInfo.sourceDir)
            if (bridge == null) {
                log("FAIL: DexKitBridge.create returned null")
                return
            }
            // 保存路径：原方法把「超 20 条」与「超 72h」两种清理耦合在同一遍遍历里，
            // 两个开关任一开启都整体接管（新条目 + 全部旧条目原样合并）
            if (countUnlock || timeUnlock) {
                hookClipboardJsonLimit(bridge, cl)
            }
            if (countUnlock) {
                hookPhraseCharLimit(bridge, cl)
            }
        } catch (t: Throwable) {
            log("FAIL: DexKit - $t")
        } finally {
            try {
                bridge?.close()
            } catch (ignored: Throwable) {
                // 释放失败不影响功能
            }
        }
    }

    /**
     * 读取路径：让 {@code getNoExpiredClipboardData(Context, String, long curTime)} 的
     * curTime 恒为 0，age = curTime - itemTime 恒为负，所有条目均满足
     * {@code age <= 259200000}（72h）的保留条件，过期条目不再被移除、图片不再被删。
     *
     * <p>可在任意进程调用（类不存在时静静跳过）：
     * phrase 进程传默认 classloader；输入法进程在动态 dex 加载完成后传 dex 的 classloader。
     */
    @JvmStatic
    fun hookClipboardTimeLimit(cl: ClassLoader) {
        try {
            val mgrCls = Reflect.findClassIfExists(CLIPBOARD_MANAGER, cl)
            if (mgrCls == null) {
                log("WARN: $CLIPBOARD_MANAGER not found for time hook")
                return
            }
            var target: Method? = null
            for (m in mgrCls.declaredMethods) {
                if (m.name != CLIPBOARD_TIME_METHOD) continue
                val ps = m.parameterTypes
                // (Context, String, long) —— 末参 curTime 仅用于 age 计算与日志
                if (ps.size == 3
                    && Context::class.java.isAssignableFrom(ps[0])
                    && String::class.java == ps[1]
                    && java.lang.Long.TYPE == ps[2]
                ) {
                    target = m
                    break
                }
            }
            if (target == null) {
                log("WARN: $CLIPBOARD_TIME_METHOD(Context,String,long) not found")
                return
            }
            target.isAccessible = true
            XposedInit.hook(target) { chain ->
                val argsList = chain.getArgs()
                if (argsList.size != 3) {
                    return@hook chain.proceed()
                }
                val modified = argsList.toTypedArray()
                // curTime 置 0：age = 0 - itemTime 恒为负 → 全量保留
                modified[2] = 0L
                chain.proceed(modified)
            }
            log("OK: clipboard 72h filter ($CLIPBOARD_EXPIRE_MS ms) bypassed, curTime=0, on ${target.name}")
        } catch (t: Throwable) {
            log("FAIL: clipboard time hook - $t")
        }
    }

    /**
     * 剪贴板 JSON 构建：去掉 20 条与时间清理，新旧条目全部保留。
     *
     * <p>方法形如 {@code buildClipboardJson(..., newModel, oldJson)}，返回 JSON 字符串。
     * 这里接管返回值：新条目在前、旧条目在后，逐条走目标应用自己的序列化。
     */
    private fun hookClipboardJsonLimit(bridge: DexKitBridge, cl: ClassLoader) {
        try {
            val found: List<MethodData> = bridge.findMethod(
                FindMethod.create()
                    .matcher(MethodMatcher.create()
                        .usingStrings(CLIPBOARD_JSON_MARK))
            )
            if (found.isEmpty()) {
                log("WARN: clipboard json method not found")
                return
            }
            if (found.size > 1) {
                log("WARN: ${found.size} candidates for clipboard json, using the first")
            }

            val mgrCls = Reflect.findClassIfExists(CLIPBOARD_MANAGER, cl)
            if (mgrCls == null) {
                log("WARN: $CLIPBOARD_MANAGER not found")
                return
            }

            val method: Method = found[0].getMethodInstance(cl)
            method.isAccessible = true
            XposedInit.hook(method) { chain ->
                val args = chain.getArgs()
                if (args.size < 4) {
                    log("clipboard json: unexpected argc=${args.size}, passthrough")
                    return@hook chain.proceed()
                }
                try {
                    val newModel = args[2]
                    val oldJson = args[3] as? String

                    val models = ArrayList<Any?>()
                    if (newModel != null) {
                        models.add(newModel)
                    }
                    var oldCount = 0
                    if (!TextUtils.isEmpty(oldJson)) {
                        val oldList = Reflect.callStaticMethod(mgrCls, "jsonToBeanList", oldJson)
                        if (oldList is List<*>) {
                            oldCount = oldList.size
                            models.addAll(oldList)
                        }
                    }

                    val result = JSONArray()
                    for (model in models) {
                        if (model == null) {
                            continue
                        }
                        val json = Reflect.callMethod(model, "toJSONObject")
                        if (json is JSONObject) {
                            result.put(json)
                        }
                    }
                    log("clipboard json merge: new=${if (newModel == null) 0 else 1}"
                        + " old=$oldCount total=${result.length()}")
                    result.toString()
                } catch (t: Throwable) {
                    log("clipboard json rebuild error - $t")
                    chain.proceed()
                }
            }
            log("OK: unlimited clipboard json on ${method.name}")
        } catch (t: Throwable) {
            log("FAIL: clipboard json hook - $t")
        }
    }

    /**
     * 常用语条数：清零计数字段 + 放行添加按钮。
     *
     * <p>{@code sPhraseListSize} 是「当前条数」，上限检查形如
     * {@code sPhraseListSize >= 20}；恒为 0 即永远没满。{@code queryPhrase}
     * 每次查询都会重新计数，所以查完再清一次。
     */
    private fun hookPhraseCountLimit(cl: ClassLoader) {
        try {
            val util = Reflect.findClass(INPUT_METHOD_UTIL, cl)
            Reflect.setStaticField(util, "sPhraseListSize", 0)

            val queryPhrase = Reflect.findMethod(util, "queryPhrase", Context::class.java)
            XposedInit.hook(queryPhrase) { chain ->
                val result = chain.proceed()
                try {
                    Reflect.setStaticField(util, "sPhraseListSize", 0)
                    log("queryPhrase: count re-zeroed")
                } catch (ignored: Throwable) {
                    // 计数字段清零失败则本次保持原样
                }
                result
            }
            log("OK: unlimited phrase count on ${queryPhrase.name}")
        } catch (t: Throwable) {
            log("WARN: phrase count hook - $t")
        }

        // 管理页「添加」按钮：原逻辑在达到上限时拦截点击，这里无条件进入添加页
        try {
            val editActivity = Reflect.findClass(PHRASE_EDIT_ACTIVITY, cl)
            val addActivity = Reflect.findClass(PHRASE_ADD_ACTIVITY, cl)
            val onClick = Reflect.findMethod(editActivity, "onClick", View::class.java)
            XposedInit.hook(onClick) { chain ->
                val thiz = chain.thisObject
                if (thiz is Activity) {
                    try {
                        val intent = Intent(thiz, addActivity)
                        intent.action = PHRASE_ADD_ACTION
                        thiz.startActivityForResult(intent, 0)
                        log("phrase add intercepted")
                        return@hook null
                    } catch (t: Throwable) {
                        log("add phrase launch error - $t")
                    }
                }
                chain.proceed()
            }
            log("OK: phrase add button unlocked")
        } catch (t: Throwable) {
            log("WARN: phrase add hook - $t")
        }
    }

    /** 常用语字数：编辑框布局加载完后清空长度过滤器。 */
    private fun hookPhraseCharLimit(bridge: DexKitBridge, cl: ClassLoader) {
        try {
            val methods: List<MethodData> = bridge.findMethod(
                FindMethod.create()
                    .matcher(MethodMatcher.create()
                        .declaredClass(ClassMatcher.create()
                            .usingStrings(PHRASE_UI_MARK))
                        .usingStrings(PHRASE_INFLATE_MARK))
            )
            val fieldData: List<FieldData> = bridge.findField(
                FindField.create()
                    .matcher(FieldMatcher.create()
                        .declaredClass(ClassMatcher.create()
                            .usingStrings(PHRASE_UI_MARK))
                        .type(EditText::class.java))
            )
            var editTextField: Field? = null
            for (data in fieldData) {
                try {
                    val f = data.getFieldInstance(cl)
                    f.isAccessible = true
                    editTextField = f
                    break
                } catch (ignored: Throwable) {
                    // 换下一个候选
                }
            }

            if (methods.isEmpty() || editTextField == null) {
                log("WARN: phrase edit ui not found (method=${methods.size}, field=${fieldData.size})")
                return
            }

            val finalEditTextField: Field = editTextField
            val inflateMethod: Method = methods[0].getMethodInstance(cl)
            inflateMethod.isAccessible = true
            XposedInit.hook(inflateMethod) { chain ->
                val result = chain.proceed()
                try {
                    val editText = finalEditTextField.get(chain.thisObject)
                    if (editText is EditText) {
                        editText.filters = arrayOf(InputFilter.LengthFilter(Int.MAX_VALUE))
                        log("phrase edit char limit cleared")
                    }
                } catch (ignored: Throwable) {
                    // 清字数限制失败不影响其余功能
                }
                result
            }
            log("OK: unlimited phrase chars on ${inflateMethod.name}")
        } catch (t: Throwable) {
            log("FAIL: phrase char hook - $t")
        }
    }

    private fun log(msg: String) {
        XposedInit.log("[Unlimit] $msg")
    }
}
