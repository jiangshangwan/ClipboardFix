# 澎湃OS剪贴板功能补全（ClipboardFix）

🛠️ 修复 HyperOS 3.0+ 第三方输入法无法使用系统剪贴板历史的问题，并解锁第三方输入法全面屏优化（底部常用语 / 剪贴板入口）

## 💻问题描述

在 HyperOS 3.0（Android 16）+上，系统应用「剪贴板与常用语V4.7.7」应用会阻止第三方输入法（如微信输入法等）访问剪贴板历史数据。官方内置输入法（搜狗小米定制版、讯飞小米定制版、百度小米定制版、小米智能输入法）不受影响。

此外，系统的「全面屏优化」默认只对官方定制输入法开放，第三方输入法不显示底部常用语 / 剪贴板入口。本模块一并解锁。

## 💡原理

**剪贴板修复**：「剪贴板与常用语V4.7.7」的 `InputProvider` 通过 `PackageManager.getNameForUid(callingUid)` 获取调用者包名，与白名单比对后决定是否允许访问。本模块 hook `PackageManager.getNameForUid()` 和 `getPackagesForUid()`，对非白名单的第三方输入法返回白名单包名，从而绕过验证。

**全面屏优化解锁**：在输入法进程内 hook `InputMethodServiceInjector`，将 `sIsImeSupport` 置位并让 `isImeSupport()` 恒返回 true 以跳过包名检查；在 system_server 内放行输入法权限校验，修复切换输入法列表被裁剪的问题。

## ✏支持的输入法

| 名称 |   版本号 |
|------|------------|
| 微信输入法 |   3.2.0   |
| 搜狗输入法 |   20.6.3   |
| 讯飞输入法 |   15.0.14   |
| QQ输入法 |   8.7.15   |

 **测试都是基于以上版本进行测试的，理论可兼容所有版本**

> 百度输入法、豆包输入法经实测不受本模块支持，v1.4 起已从支持列表与模块作用域中移除。

## 🤖安装说明

### 环境要求

- 已 Root 的小米 / 红米手机，HyperOS 3.0+
- 剪贴板和常用语 V4.7.7+
- **LSPosed 框架需支持 libxposed 新版模块 API（API 102）**，例如 Vector v2.2 及以上
  - 在 LSPosed 管理器首页「已激活」下方可以看到当前框架的 API 版本
  - 本模块基于新版 API 开发，与旧的 `de.robv.android.xposed` 不兼容

### 操作步骤

1. 在 LSPosed 中启用本模块
2. 勾选作用域：
   - **剪贴板和常用语**（`com.miui.phrase`）—— 剪贴板修复必需
   - **当前使用的第三方输入法** —— 全面屏优化必需
   - **系统框架**（`system`）—— 用于放行输入法权限，建议一并勾选
3. 安装后请重启手机，否则不起作用

## 📝更新日志

### v1.4.1

- 修复输入法服务重建后，底部常用语 / 剪贴板入口消失的问题
  - 此前只在包加载时一次性给静态字段 `sIsImeSupport` 赋值，输入法服务重建或该字段在别处被重新计算时会被写回 false
  - 现改为额外 hook `isImeSupport()` 恒返回 true，与赋值时机解耦
  - 已跟进上游 [RC1844/MIUI_IME_Unlock v1.17](https://github.com/RC1844/MIUI_IME_Unlock/pull/34)
- 作用域新增 `com.xiaomi.type`（小米输入法）

### v1.4

- 移除实测不支持的百度输入法、豆包输入法
- 界面版本号与构建版本同步

### v1.3

- 迁移至 libxposed 新版模块 API（API 102），不再使用旧版 `de.robv.android.xposed`
- 集成全面屏优化解锁（输入法底部常用语 / 剪贴板入口）

## 💕开发者

- 酷安：[江上晚](https://www.coolapk.com/u/3019478)
- 微博：[李十六的日记本](https://weibo.com/u/3725737792)

## 您的支持就是我最大的动力
<img width="420" height="420" alt="澎湃OS剪贴板补全_打赏二维码_1778172233017" src="https://github.com/user-attachments/assets/5ace0a63-6575-489c-843e-01c190c22832" />

## License

[MIT](LICENSE)
