<p align="center">
  <img src="logo.png" width="20%" alt="HyperClipFix - HyperOS剪贴板修复模块">
</p>
<br>
<div align="center">
<h1>HyperClipFix</h1>

<a href="https://github.com/Xposed-Modules-Repo/com.clipboardfix/releases"><img alt="GitHub all releases" src="https://img.shields.io/github/downloads/Xposed-Modules-Repo/com.clipboardfix/total?label=Downloads"></a>
<a href="https://github.com/Xposed-Modules-Repo/com.clipboardfix/releases/latest"><img alt="GitHub latest release" src="https://img.shields.io/github/v/release/Xposed-Modules-Repo/com.clipboardfix"></a>
<a href="https://www.android.com/"><img src="https://img.shields.io/badge/Android-16--17-brightgreen?style=flat&logo=android&logoColor=white"></a> 
<a href="https://hyperos.mi.com/"><img src="https://img.shields.io/badge/HyperOS-OS3--OS4-purple?style=flat&logo=xiaomi&logoColor=white"></a> 
<a href="https://compose-miuix-ui.github.io/miuix/zh_CN/"><img src="https://img.shields.io/badge/UI框架-MIUIX--Compose-blue?style=flat&logo=jetpackcompose&logoColor=white"></a>
<a href="https://kotlinlang.org/"><img src="https://img.shields.io/badge/Language-Kotlin-7F52FF?style=flat&logo=kotlin&logoColor=white"></a>

<a href="https://qun.qq.com/universal-share/share?ac=1&authKey=UzK4LAt%2B2Lg3xshN1AF2GY0K8D%2Fqo3wD6Y0Hn1aturx2dhuR3ED3NFCq8s6ZAmrl&busi_data=eyJncm91cENvZGUiOiIxMTI0MTgwNjM1IiwidG9rZW4iOiJOWWE3RE5XcE9tMTI5SGtMRFNrcGF4Wi9hcE9wdlJDbUQ4cHcvU08vYlF4c3ZlUUtxWlhiYmRQdGJNSU1JalBvIiwidWluIjoiOTAzNzU1MzQ1In0%3D&data=e9KDo0FHZWGB_k0pVQfB-guEvu55ZBSomL-ql1Daq8os18WLI_-VppwYWQqbiDvGuwyLx1ol5PLFkx1kOBx4Jw&svctype=4&tempid=h5_group_info"><img src="https://img.shields.io/badge/QQ交流群-blue?style=flat&logo=qq&logoColor=white"></a> 
<a href="https://github.com/jiangshangwan/ClipboardFix"><img src="https://img.shields.io/badge/GitHub仓库-black?style=falt&logo=github&logoColor=white"></a> 
<a href="https://modules.lsposed.org/module/com.clipboardfix/"><img src="https://img.shields.io/badge/LSPosed仓库-orange?style=flat&logo=xdadevelopers&logoColor=white"></a>
<a href="https://weibo.com/u/3725737792"><img src="https://img.shields.io/badge/微博-李十六的日记本-red?style=flat&logo=sinaweibo&logoColor=white"></a>
<a href="https://www.coolapk.com/u/3019478"><img src="https://img.shields.io/badge/酷安-江上晚-green?style=flat&logo=fishshell&logoColor=white"></a>



<p>🛠️ 修复第三方输入法在HyperOS无法使用系统剪贴板及全面屏键盘优化的问题</p>


</div>

---

## 💻问题描述

自 HyperOS 3.0 起，小米向所有搭载HyperOS 3.0的设备推送了系统组件「剪贴板与常用语 V4.7.7」。从该版本起至目前最新版本均已新增白名单校验机制，**剪贴板读写权限仅系统内置定制输入法、超级小爱输入法，所有第三方输入法均无法读写系统剪贴板**

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
    <td>豆包输入法</td>
    <td>1.4.5</td>
  </tr>
  <tr>
    <td>Gboard</td>
    <td>18.2.4</td>
  </tr>

</table>

 ✍️测试都是基于以上版本进行测试的，理论可兼容所有版本，为保证完美运行，请关闭手势提示线，后续将可能不会再解决手势提示线导致的问题(太麻烦了，准备放弃了)！

> ❌百度输入法暂不支持

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
- 如果开启手势提示线出现键盘被抬高的故障，请关闭手势提示线
- 后续可能不会再解决因手势提示线导致的键盘抬高的故障，太麻烦了，精力有限难以持续维护适配

  
## 💕开发者

- 酷安：[江上晚](https://www.coolapk.com/u/3019478)
- 微博：[李十六的日记本](https://weibo.com/u/3725737792)
  
## 🥂开源致谢
感谢[MIUI_IME_Unlock(MIT)](https://github.com/RC1844/MIUI_IME_Unlock)开源项目提供的解锁MIUI键盘全面屏优化限制

## 您的支持就是我最大的动力
<img width="420" height="420" alt="澎湃OS剪贴板补全_打赏二维码_1778172233017" src="https://github.com/user-attachments/assets/5ace0a63-6575-489c-843e-01c190c22832" />

## License

[MIT](LICENSE)
