# ---- ClipboardFix 混淆 / 收缩规则 ----

# 模块入口类：LSPosed 框架按 META-INF/xposed/java_init.list 里的类名加载本类，
# 一旦被混淆或移除，模块将无法注入。必须原样保留。
-keep class com.clipboardfix.XposedInit { *; }

# 整个业务包保留：hook 逻辑由框架回调 / 反射触发，保留原样最稳妥。
# 本项目自身代码量极小，保留几乎不增加体积，却彻底排除收缩/混淆导致的运行期风险。
-keep class com.clipboardfix.** { *; }

# DexKit 内部大量通过反射按类名 / 方法名加载，保留其包以免运行期崩溃。
-keep class org.luckypray.dexkit.** { *; }
-keepattributes *Annotation*

# 抑制第三方库在 R8 分析期的告警（不阻断构建）
-dontwarn org.luckypray.dexkit.**
-dontwarn io.github.libxposed.**
