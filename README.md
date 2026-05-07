# ClipboardFix

修复 HyperOS 3.0（澎湃OS）第三方输入法无法使用系统剪贴板历史的 LSPosed 模块。

## 问题描述

在 HyperOS 3.0（Android 16）上，系统自带的「剪贴板与常用语」应用会阻止第三方输入法（如微信输入法 WeType）访问剪贴板历史数据。官方内置输入法（搜狗小米定制版、小米输入法）不受影响。

## 原理

「剪贴板与常用语」的 `InputProvider` 通过 `PackageManager.getNameForUid(callingUid)` 获取调用者包名，与白名单比对后决定是否允许访问。本模块 hook `PackageManager.getNameForUid()` 和 `getPackagesForUid()`，对非白名单的第三方输入法返回白名单包名，从而绕过验证。

## 支持的输入法

- 搜狗输入法小米版（`com.sohu.inputmethod.sogou.xiaomi`）— 本身在白名单内，无需修改
- 微信输入法（`com.tencent.wetype`）— 需要本模块
- 其他第三方输入法 — 需要本模块

## 安装要求

- 已 Root 的小米/红米手机
- LSPosed 框架
- HyperOS 3.0 / MIUI 15+

## 安装步骤

1. 下载最新 Release 中的 `app-debug.apk`
2. 安装到手机
3. 在 LSPosed Manager 中启用模块
4. 勾选作用域：「剪贴板与常用语」（`com.miui.phrase`）
5. 重启「剪贴板与常用语」应用（或重启手机）

## 功能

- ✅ 修复第三方输入法剪贴板历史访问
- ✅ About 界面（模块信息、开发者信息）
- ✅ 隐藏/显示桌面图标
- ✅ 收赏二维码（微信）

## 开发者

- 酷安：江上晚
- 微博：李十六的日记本

## License

MIT