# HyperOS剪贴板功能补全

🛠️ 修复 HyperOS 3.0+ 第三方输入法无法使用系统剪贴板历史的问题，并解锁第三方输入法全面屏优化（底部常用语 / 剪贴板入口）

## 💻问题描述

在 HyperOS 3.0（Android 16）+上，系统应用「剪贴板与常用语V4.7.7」应用会阻止第三方输入法（如微信输入法等）访问剪贴板历史数据，导致第三方输入法无法使用系统剪贴板功能。
官方内置输入法（搜狗小米定制版、讯飞小米定制版、百度小米定制版、小米小爱输入法）不受影响。

## ✏支持的输入法

<table>
  <tr>
    <th>名称</th>
    <th>版本号</th>
    <th>测试机型</th>
    <th>测试ROM</th>
  </tr>
  <tr>
    <td>微信输入法</td>
    <td>3.2.0</td>
    <td rowspan="6" style="vertical-align:middle; text-align:center">小米15Pro</td>
    <td rowspan="6" style="vertical-align:middle; text-align:center">HyperOS4</td>
  </tr>
  <tr>
    <td>搜狗输入法</td>
    <td>20.6.3</td>
  </tr>
  <tr>
    <td>讯飞输入法</td>
    <td>15.0.14</td>
  </tr>
  <tr>
    <td>QQ输入法</td>
    <td>8.7.15</td>
  </tr>
  <tr>
    <td>Gboard</td>
    <td>18.2.4</td>
  </tr>

</table>

 ✍️测试都是基于以上版本进行测试的，理论可兼容所有版本。

> ❌百度输入法、豆包输入法经实测不受本模块支持，v1.4 起已从支持列表与模块作用域中移除。

## 🤖安装说明

### 🌡️环境要求

- 已 Root 的小米 / 红米手机，HyperOS 3.0+
- 剪贴板和常用语 V4.7.7+
- **LSPosed 框架需支持 libxposed 新版模块 API（API 102）**，例如 Vector v2.2 及以上
  - 在 LSPosed 管理器首页「已激活」下方可以看到当前框架的 API 版本
  - 本模块基于新版 API 开发，与旧的 `de.robv.android.xposed` 不兼容

### ⌨️操作步骤

1. 在 LSPosed 中启用本模块
2. 勾选作用域：
   - **剪贴板和常用语**（`com.miui.phrase`）—— 剪贴板修复必需
   - **当前使用的第三方输入法** —— 全面屏优化必需
   - **系统框架**（`system`）—— 用于放行输入法权限，建议一并勾选
3. 安装后请重启手机，否则不起作用

## 💕特别说明

- 本模块仅修改剪贴板和常用语验证逻辑，不影响数据内容，请放心使用。
- 如果您的系统剪贴板功能正常请勿安装本模块！
- 从1.3版本起模块内置解锁MIUI键盘全面屏优化限制并适配HyperOS4，可能在OS3版本上存在部分异常问题，具体请自测。
- 使用中如果出现输入法被异常抬高的BUG请尝试关闭小白条测试是否复现。
- ~~目前模块在跨设备剪贴板存中存在bug但是不影响使用~~（v1.4.7 已修复）。

  
## 💕开发者

- 酷安：[江上晚](https://www.coolapk.com/u/3019478)
- 微博：[李十六的日记本](https://weibo.com/u/3725737792)
  
## 🥂开源致谢
感谢[MIUI_IME_Unlock(MIT)](https://github.com/RC1844/MIUI_IME_Unlock)开源项目提供的解锁MIUI键盘全面屏优化限制

## 您的支持就是我最大的动力
<img width="420" height="420" alt="澎湃OS剪贴板补全_打赏二维码_1778172233017" src="https://github.com/user-attachments/assets/5ace0a63-6575-489c-843e-01c190c22832" />

## License

[MIT](LICENSE)
