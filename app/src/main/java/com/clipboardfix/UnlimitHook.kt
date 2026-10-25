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

    /** 本地图/文件 keep hook 是否已装（进程内只装一次，防 loadDex 多次回调重复拦截）。 */
    @Volatile
    private var localImageKeepInited = false

    /** 本机图片保存门 hook 是否已装（进程内只装一次）。 */
    @Volatile
    private var localImageSaveGateInited = false

    /** IME 超级剪贴板能力 meta-data 键（manifest 声明，第三方输入法均未声明）。 */
    private const val SUPER_CLIP_META_KEY = "is_support_miui_super_clipboard"

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
        // 本机复制图片/文件 keep（读取路径）：与解锁开关无关，尽早装上，防止 phrase 侧
        // 查询触发 shouldRemoveCurrentElement 把本地图删掉。输入法进程侧另由
        // ImeUnlockHook 的 loadDex 回调转来安装（见 hookShouldRemoveLocalImages）。
        hookShouldRemoveLocalImages(cl)
        // 本机复制图片「不落库」修复（保存路径）：同上，两侧进程都装。
        hookLocalImageSaveGate(cl)
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
     * 本机复制的图片/文件「显示即被删」修复。
     *
     * <p><b>根因（v4.9 修正版）</b>：{@code getNoExpiredClipboardData} 的删除分支有三个触发条件
     * （age>72h / content 空 / {@code shouldRemoveCurrentElement} 返回 true），命中即
     * {@code v1.remove()} 移出查询结果，且对图片类条目调 {@code deleteExpiredImgBasedClipboard}
     * **连图片文件一起删**（日志 {@code "no remain time delete ... type : 11"} 实证 2026-10-25）。
     * 其中 {@code shouldRemoveCurrentElement} 对本地条目要求 {@code canReadFileUri} 非空且文件
     * 存在，而保存路径常只写 {@code fileUri} → 恒命中删除。更隐蔽的是：本机复制的图片经
     * 通用剪贴板通道（{@code setClipBoardDataToLocal}）保存时被强写 {@code acrossDevices=1}，
     * 旧版仅放行 {@code across==false} 的 keep 规则完全不生效（{@code keep local} 日志零出现）。
     *
     * <p><b>修法</b>：三类条目一律放行保留——① 本地（{@code across==false}）；② 图片/文件类型
     * （type 11..24）；③ content 里任一 item 带 {@code content://}/{@code file://} 文件引用或
     * 缩略图。其余条目走原逻辑。另 hook {@code deleteExpiredImgBasedClipboard} 为 no-op，
     * 条目保留的同时图片文件也不再被误删。与各解锁开关无关，只要类可加载就装。
     */
    @JvmStatic
    fun hookShouldRemoveLocalImages(cl: ClassLoader) {
        if (localImageKeepInited) return
        try {
            val mgrCls = Reflect.findClassIfExists(CLIPBOARD_MANAGER, cl)
            if (mgrCls == null) {
                log("WARN: $CLIPBOARD_MANAGER not found for local-image keep")
                return
            }
            var target: Method? = null
            var delTarget: Method? = null
            for (m in mgrCls.declaredMethods) {
                if (m.name == "shouldRemoveCurrentElement" && m.parameterTypes.size == 2) {
                    target = m
                } else if (m.name == "deleteExpiredImgBasedClipboard" && m.parameterTypes.size == 2) {
                    delTarget = m
                }
            }
            if (target == null) {
                log("WARN: shouldRemoveCurrentElement(Context,*) not found")
                return
            }
            localImageKeepInited = true
            target.isAccessible = true
            XposedInit.hook(target) { chain ->
                try {
                    val model = chain.getArg(1)
                    if (model != null) {
                        val across = Reflect.callMethod(model, "isAcrossDevices") as? Boolean ?: false
                        val type = runCatching {
                            (Reflect.callMethod(model, "getType") as? Number)?.toInt()
                        }.getOrNull()
                        if (!across) {
                            // 本机条目：canReadFileUri 常为空会被误删，放行保留
                            return@hook false
                        }
                        if (type != null && type >= 11 && type <= 24) {
                            // 图片/文件类型（含通用剪贴板通道强写 across=1 的本机图片）：
                            // 走原逻辑会因 fileUri/fileName 空被误删并删文件，放行保留
                            return@hook false
                        }
                        if (hasFileRef(model)) {
                            // content 带文件引用/缩略图：可渲染的条目一律保留
                            return@hook false
                        }
                    }
                } catch (_: Throwable) {
                    // 识别失败走原逻辑
                }
                chain.proceed()
            }
            log("OK: local image/file keep on ${target.name}")
            if (delTarget != null) {
                delTarget.isAccessible = true
                XposedInit.hook(delTarget) { chain ->
                    // 过期/误判删除会连图片文件一起清掉；本模块的定位是「保留」，
                    // 文件删除直接短路（唯一调用点就是 getNoExpiredClipboardData 删除分支）。
                    null
                }
                log("OK: deleteExpiredImgBasedClipboard disabled")
            }
        } catch (t: Throwable) {
            log("FAIL: local image keep hook - $t")
        }
    }

    /**
     * 条目 content（{@code ClipDataItemModel} JSON 数组）里是否有文件/缩略图引用。
     * 键名不做精确假设：item 任一字符串值以 {@code content://} 或 {@code file://} 开头、
     * 或键名含 {@code humb}（thumbImage）且值非空，即认为有可用文件引用。
     */
    private fun hasFileRef(model: Any): Boolean {
        val content = runCatching {
            Reflect.callMethod(model, "getContent") as? String
        }.getOrNull() ?: return false
        return try {
            val arr = JSONArray(content)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                for (k in o.keys()) {
                    val v = o.optString(k)
                    if (v.isEmpty()) continue
                    if (v.startsWith("content://") || v.startsWith("file://")) return true
                    if (k.contains("humb") && v.length > 8) return true
                }
            }
            false
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * 本机复制的图片「不落库」修复（v4.10 根因，2026-10-25 日志实证）。
     *
     * <p><b>根因</b>：图片/URI 剪贴板条目的保存入口
     * {@code callPhraseToSaveUriAndFileAbsolutePath} 被静态能力位
     * {@code MiuiClipboardManager.sIsCurrentImeSupportSuperClipboard} 把关——该位由
     * {@code init()} 读输入法 manifest meta-data {@code is_support_miui_super_clipboard}
     * 得到，第三方输入法（微信输入法/豆包等）均未声明 → 恒 false → 保存分支整段跳过
     * （不写 fileUri、不落库），仅打一条 {@code "sIsCurrentImeSupportSuperClipboard is false"}
     * 与 {@code "recordClipboardData: not process this clipboard data"}。旁路（通用剪贴板
     * ThumbnailInfo 投递）只在剪贴板面板打开、observer 注册的窗口内生效，面板关着复制
     * 就永久丢失。查询结果因此永远只有文本条目——面板「本机图片不显示」的总根源。
     *
     * <p><b>修法</b>：① hook {@code InputMethodUtil.hasBooleanMetaData}，对该 key 返回 true
     * （init() 读取点）；② hook {@code recordClipboardData} 入口把静态位强置 true
     * （覆盖 clinit 清零 / init 早于 hook 的时序）。置位后保存分支正常执行：phrase provider
     * {@code set_clip_data} 复制文件、写 fileUri、{@code saveClipboardCipherText} 落库；
     * {@code shouldRemoveCurrentElement} 尾部的判断也从「恒删」恢复为「有文件项即保留」。
     */
    @JvmStatic
    fun hookLocalImageSaveGate(cl: ClassLoader) {
        if (localImageSaveGateInited) return
        try {
            val mgrCls = Reflect.findClassIfExists(CLIPBOARD_MANAGER, cl)
            if (mgrCls == null) {
                log("WARN: $CLIPBOARD_MANAGER not found for save gate")
                return
            }
            var any = false
            val utilCls = Reflect.findClassIfExists(INPUT_METHOD_UTIL, cl)
            if (utilCls != null) {
                for (m in utilCls.declaredMethods) {
                    if (m.name == "hasBooleanMetaData" && m.parameterTypes.size == 2) {
                        m.isAccessible = true
                        XposedInit.hook(m) { chain ->
                            try {
                                val key = chain.getArg(1) as? String
                                if (key == SUPER_CLIP_META_KEY) {
                                    return@hook true
                                }
                            } catch (_: Throwable) {
                            }
                            chain.proceed()
                        }
                        log("OK: hasBooleanMetaData save gate hooked")
                        any = true
                        break
                    }
                }
            }
            for (m in mgrCls.declaredMethods) {
                if (m.name == "recordClipboardData" && m.parameterTypes.size == 1) {
                    m.isAccessible = true
                    XposedInit.hook(m) { chain ->
                        forceSuperClipboardFlag(mgrCls)
                        chain.proceed()
                    }
                    log("OK: recordClipboardData flag force on")
                    any = true
                    break
                }
            }
            if (any) {
                localImageSaveGateInited = true
            } else {
                log("WARN: save gate targets not found (hasBooleanMetaData/recordClipboardData)")
            }
        } catch (t: Throwable) {
            log("FAIL: local image save gate - $t")
        }
    }

    /** 把 {@code sIsCurrentImeSupportSuperClipboard} 静态位强置 true（幂等，已为 true 不重复打日志）。 */
    private fun forceSuperClipboardFlag(mgrCls: Class<*>) {
        try {
            val f = mgrCls.getDeclaredField("sIsCurrentImeSupportSuperClipboard")
            f.isAccessible = true
            if (!f.getBoolean(null)) {
                f.setBoolean(null, true)
            }
        } catch (t: Throwable) {
            log("WARN: force superClipboard flag - $t")
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
