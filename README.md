<p align="center">
  <img src="logo.png" width="180">
</p>
<br>
<div align="center">
<h1>HyperClipFix</h1>
<p>🔒 解锁第三方输入法在 HyperOS 无法访问系统剪贴板、使用全面屏键盘优化的限制，并提供额外剪贴板增强功能</p>
<a href="https://www.android.com/"><img src="https://img.shields.io/badge/Android-16--17-brightgreen?style=flat&logo=android&logoColor=white"></a> 
<a href="https://hyperos.mi.com/"><img src="https://img.shields.io/badge/HyperOS-OS3--OS4-purple?style=flat&logo=xiaomi&logoColor=white"></a> 
<a href="https://compose-miuix-ui.github.io/miuix/zh_CN/"><img src="https://img.shields.io/badge/UI框架-MIUIX--Compose-blue?style=flat&logo=jetpackcompose&logoColor=white"></a>
<a href="https://kotlinlang.org/"><img src="https://img.shields.io/badge/Language-Kotlin-7F52FF?style=flat&logo=kotlin&logoColor=white"></a>
<a href="https://qun.qq.com/universal-share/share?ac=1&authKey=UzK4LAt%2B2Lg3xshN1AF2GY0K8D%2Fqo3wD6Y0Hn1aturx2dhuR3ED3NFCq8s6ZAmrl&busi_data=eyJncm91cENvZGUiOiIxMTI0MTgwNjM1IiwidG9rZW4iOiJOWWE3RE5XcE9tMTI5SGtMRFNrcGF4Wi9hcE9wdlJDbUQ4cHcvU08vYlF4c3ZlUUtxWlhiYmRQdGJNSU1JalBvIiwidWluIjoiOTAzNzU1MzQ1In0%3D&data=e9KDo0FHZWGB_k0pVQfB-guEvu55ZBSomL-ql1Daq8os18WLI_-VppwYWQqbiDvGuwyLx1ol5PLFkx1kOBx4Jw&svctype=4&tempid=h5_group_info"><img src="https://img.shields.io/badge/QQ交流群-blue?style=flat&logo=qq&logoColor=white"></a> 
<a href="https://github.com/jiangshangwan/ClipboardFix"><img src="https://img.shields.io/badge/GitHub仓库-black?style=flat&logo=github&logoColor=white"></a> 
<a href="https://modules.lsposed.org/module/com.clipboardfix/"><img src="https://img.shields.io/badge/LSPosed仓库-orange?style=flat&logo=xdadevelopers&logoColor=white"></a>
<a href="https://weibo.com/u/3725737792"><img src="https://img.shields.io/badge/微博-李十六的日记本-red?style=flat&logo=sinaweibo&logoColor=white"></a>
<a href="https://www.coolapk.com/u/3019478"><img src="https://img.shields.io/badge/酷安-江上晚-green?style=flat&logo=fishshell&logoColor=white"></a>
</div>

---

## 💻问题背景
自 HyperOS 3.0 起，系统组件「剪贴板与常用语」引入白名单校验机制。
**仅系统内置输入法、超级小爱输入法拥有剪贴板读写权限，所有第三方输入法默认无法读写系统剪贴板。**

> 🛠本模块主要基于 **HyperOS4、剪贴板和常用语 5.7.4** 开发；向下兼容 HyperOS3及剪贴板和常用语，但会存在部分适配异常，请自行测试。

## ✨核心功能

- ✅ 启用全面屏键盘优化：使第三方输入法也能使用全面屏键盘优化功能
- ✅ 解锁剪贴板数量上限：解除系统剪贴板历史条目数量限制
- ✅ 解锁剪贴板时间限制：解除剪贴板条目保存时间限制
- ✅ 优化剪贴板面板背景：统一剪贴板面板与键盘功能区颜色
- ✅ 开启剪贴板条目置顶：支持剪贴板条目置顶显示
- ✅ 开启剪贴板分组功能：按应用分组展示剪贴板记录
- ✅ 记忆剪贴板分组位置：记住不同分组的展开/收起状态
- ✅ 重载进程：模块更新后可直接重载 Hook，无需重启手机

## ✏支持的输入法
> 测试基于以下版本，理论兼容更高版本；**百度输入法暂不支持**

| 输入法名称 | 测试版本 |
|------|------|
| 微信输入法 | 3.2.0 |
| 搜狗输入法 | 20.6.3 |
| 讯飞输入法 | 15.0.14 |
| QQ输入法 | 8.7.15 |
| 豆包输入法 | 1.4.5 |
| Gboard | 18.2.4 |

> ⚠若开启系统手势提示线，可能出现键盘被抬高问题，遇到请关闭手势提示线。

## 🤖安装说明
### 🌡️环境要求
- 已 Root 的小米 / 红米设备，**HyperOS 3.0 ~ HyperOS4**
- 系统组件：剪贴板和常用语 `V4.7.7` 及以上，开发基准版本 `5.7.4`
- LSPosed框架必须支持新版 libxposed 模块API（**API 102**），例如 Vector v2.2+
> 在LSPosed管理器首页「已激活」下方查看API版本；本模块不兼容旧 `de.robv.android.xposed` API。

### ⌨️操作步骤
1. 在 LSPosed 中启用本模块
2. **勾选作用域（缺一不可）**
   - **剪贴板和常用语**（`com.miui.phrase`）——剪贴板修复必需
   - **当前正在使用的第三方输入法** ——全面屏键盘优化必需
   - **系统框架**（`system`）——输入法权限放行，建议务必勾选
3. **首次安装必须重启手机**；模块更新后可使用模块内「重载进程」功能，无需重启。

## ⚠️特别注意
- 模块仅修改剪贴板与常用语校验逻辑，**不会篡改剪贴板数据内容**。
- 如果你的第三方输入法剪贴板功能本身正常，**请勿安装本模块**。
- HyperOS3上部分功能会存在兼容性异常，请自行测试。
- 手势提示线可能造成键盘抬高问题：精力有限，后续随缘修复。

## 👨‍💻开发者
- 酷安：[江上晚](https://www.coolapk.com/u/3019478)
- 微博：[李十六的日记本](https://weibo.com/u/3725737792)

## 💝您的支持就是我最大的动力
<img width="420" height="420" alt="澎湃OS剪贴板补全_打赏二维码_1778172233017" src="https://github.com/user-attachments/assets/5ace0a63-6575-489c-843e-01c190c22832" />

## License

This project is licensed under the GNU General Public License v3.0 or later.

Copyright (c) 2026 江上晚

See the full license text in [LICENSE](./LICENSE).

### 📜协议简要说明
✅ 可以自由下载、查看、编译、修改，本地私下使用不受限制。

✅ 可以分发、分享本项目。

⚠️ 如果修改后再发布、打包分发，衍生作品也必须继续采用 GPL‑3.0‑or‑later，并且开放完整源代码。

⚠️ 不允许直接拿本项目代码修改后闭源打包售卖而不公开源码。

⚠️ 分发时必须完整保留原始许可证与版权声明，不可删除。

> 本模块按现状提供，无任何担保；如因使用造成设备故障、数据丢失，作者不承担相关责任。
