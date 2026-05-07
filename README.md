# ClipboardFix

修复 HyperOS 3.0（澎湃OS）第三方输入法无法使用系统剪贴板历史的 LSPosed 模块。

## 问题描述

在 HyperOS 3.0（Android 16）上，系统自带的「剪贴板与常用语」应用会阻止第三方输入法（如微信输入法、豆包输入法等）访问剪贴板历史数据。官方内置输入法（搜狗小米定制版、讯飞小米定制版、百度小米定制版、小米输入法）不受影响。

## 原理

「剪贴板与常用语」的 `InputProvider` 通过 `PackageManager.getNameForUid(callingUid)` 获取调用者包名，与白名单比对后决定是否允许访问。本模块 hook `PackageManager.getNameForUid()` 和 `getPackagesForUid()`，对非白名单的第三方输入法返回白名单包名，从而绕过验证。

## 支持的输入法

- 本模块原理上支持所有第三方输入法，但目前本人只对微信输入法进行了测试，其他输入法请自测

## 安装说明

- 已 Root 的小米/红米手机
- 剪贴板和常用语V4.7.7
- HyperOS 3.0+
- 安装后请在LSPosed内勾选剪贴板和常用语作用域
- 安装后请重启手机，否则不起作用

## 开发者

- 酷安：[江上晚](https://www.coolapk.com/u/3019478)
- 微博：[李十六的日记本](https://weibo.com/u/3725737792)
## 您的支持就是我最大的动力
<img width="420" height="420" alt="澎湃OS剪贴板补全_打赏二维码_1778172233017" src="https://github.com/user-attachments/assets/5ace0a63-6575-489c-843e-01c190c22832" />

## License

MIT
