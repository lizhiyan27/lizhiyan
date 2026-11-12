# 影梭 APK 逆向分析报告

## 1. 分析对象与方法

- 文件：`D:\zzz\影梭（虚拟定位）推荐.apk`
- 大小：23,320,401 字节
- SHA-256：`54481a56f673623531f7ed0f2a0fda2736aa02e8899b3d970748e3ac380b8c80`
- 工具：REA 6.8.0，jadx-headless-mcp 0.7.1（SHA-256 已按 REA 固定值校验）
- 方法：本地静态分析，未运行原 APK，未登录其服务，未绕过任何付费或许可机制。

REA 的 Java 输出是反编译结果，不是原始源码。下文将直接观察、代码推导和公开源码核对分开描述。

## 2. 包与签名事实（直接观察）

| 项目 | 结果 |
|---|---|
| 包名 | `com.zcshou.gogogo` |
| 版本 | `1.12.3`（versionCode 1123） |
| minSdk | 27，Android 8.1 |
| targetSdk | 32，Android 12L |
| DEX | 单个 `classes.dex`，3,133 个类 |
| 原生 ABI | 仅 `arm64-v8a` |
| 地图组件 | 百度地图 SDK 7.6.5、百度定位 SDK |
| 签名 | APK Signature Scheme v2；v1/v3 未观察到 |
| 签名主题 | `O=ZCS, CN=ZCShou` |
| 证书 SHA-256 | `6eec4ddd865b85482bd5a97ef4ceaa14dded5c3a6cdcbffd4eccb78c54eff081` |
| 内容摘要 | v2 声明摘要与 APK 匹配；这不是对作者身份或运行时行为的担保 |

REA 证据：`../analysis/rea-package.json`、`../analysis/rea-apk-signature.json`。

## 3. 组件与权限（直接观察）

主要应用组件：

- `WelcomeActivity`：唯一 launcher 入口。
- `MainActivity`：地图与定位操作。
- `HistoryActivity`：历史位置。
- `SettingsActivity`：设置。
- `com.zcshou.service.ServiceGo`：非导出模拟定位服务。
- 百度定位远程服务及 AndroidX FileProvider。

原包声明定位、后台定位、网络、Wi-Fi、前台服务、悬浮窗、唤醒锁、通知相关能力，也声明 `READ_PHONE_STATE`、存储、安装包、写系统设置、读取日志等权限。清单声明只能证明应用请求能力，不能单独证明它实际使用了每项权限或存在恶意行为。

新实现不复刻与核心功能无关的旧权限，也不复用原包百度 AK。

## 4. 核心工作原理

### 4.1 服务初始化（REA 直接观察）

`ServiceGo.onCreate()` 的反编译调用顺序为：

1. 获取系统 `LocationManager`。
2. 移除并重新添加 NETWORK 测试 Provider。
3. 移除并重新添加 GPS 测试 Provider。
4. 初始化定位 HandlerThread。
5. 初始化前台通知。
6. 初始化悬浮摇杆。

证据：`../analysis/rea-service-methods/onCreate.json`。

`onStartCommand()` 读取 `LNG_VALUE`、`LAT_VALUE`、`ALT_VALUE`，更新当前经纬度/海拔并同步摇杆位置。证据：`../analysis/rea-service-methods/onStartCommand.json`。

### 4.2 双 Provider 注册（REA 直接观察）

- GPS：`addTestProvider("gps", ...)`，需要卫星、支持经纬度/海拔/速度/方向，功耗高精度高；必要时 `setTestProviderEnabled("gps", true)`。
- NETWORK：`addTestProvider("network", ...)`，不需要卫星、需要网络，支持经纬度/海拔/速度/方向，低功耗、粗略精度。

证据：`../analysis/rea-service-methods/n.json`、`o.json`。

这依赖 Android 的开发者模拟位置机制，不需要 Root，也不注入或修改第三方 App。手机必须在“开发者选项 → 选择模拟位置信息应用”中选择本应用，否则系统会抛出 `SecurityException`。

### 4.3 位置构造与上报（REA 直接观察）

每次更新分别创建 GPS 和 NETWORK 两条 `Location`，不会只移动地图 Marker。

| 字段 | GPS | NETWORK |
|---|---|---|
| Provider | `gps` | `network` |
| accuracy | `1.0f` | `2.0f` |
| latitude/longitude | 当前模拟坐标 | 当前模拟坐标 |
| altitude | 当前海拔 | 当前海拔 |
| bearing | 当前方向 | 当前方向 |
| speed | 当前速度 | 当前速度 |
| time | `System.currentTimeMillis()` | 同左 |
| elapsedRealtimeNanos | `SystemClock.elapsedRealtimeNanos()` | 同左 |
| extras | `satellites=7` | 无此额外字段 |
| 上报 | `setTestProviderLocation("gps", location)` | `setTestProviderLocation("network", location)` |

证据：`../analysis/rea-service-methods/u.json`、`v.json`。

### 4.4 100 ms 更新循环（REA 直接观察）

内部 Handler 的 `handleMessage()`：

```text
Thread.sleep(100)
若未停止：
  上报 NETWORK
  上报 GPS
  再次发送空消息
```

证据：`../analysis/rea-service-handleMessage.json`。这解释了持续模拟和摇杆平滑移动的来源。

### 4.5 摇杆位移（代码推导 + 公开源码核对）

服务摇杆回调根据距离更新坐标，并记录方向和速度：

- 纬度：`lat += disLat / 110.574`（disLat 单位 km）
- 经度：`lng += disLng / (111.320 × cos(abs(lat)))`
- 默认步行速度：1.2 m/s
- 默认海拔：55 m
- 默认坐标：济南附近（36.667662, 117.027707）

新实现使用等价的米制公式和 100 ms 周期，但默认位置使用最后选点或北京。

### 4.6 生命周期（REA 直接观察）

`onDestroy()` 停止 Handler、销毁摇杆、移除两个测试 Provider、注销通知接收器并停止前台服务。证据：`../analysis/rea-service-methods/onDestroy.json`。

## 5. 功能清单与证据等级

| 功能 | 证据 |
|---|---|
| 点击地图选点、修改定位 | REA 字符串、MainActivity/服务结构、公开源码核对 |
| 持续模拟 GPS + NETWORK | REA 直接观察 |
| 悬浮窗与摇杆移动 | REA 类结构、字符串、公开源码核对 |
| 历史位置、搜索历史、删除 | DEX 中对应表结构/中文字符串，公开源码核对 |
| 收藏 | DEX 中“收藏的地点已打开/关闭”字符串 |
| 关键词搜索 | DEX 搜索字符串、百度 SDK 调用与公开源码核对 |
| 直接输入经纬度 | DEX“请输入经度和纬度”“手动输入的坐标” |
| WGS84 / BD-09 转换 | 公开源码 `MapUtils` 核对；新实现使用独立 WGS84 地图 |
| 分享位置 | DEX“分享位置”与公开源码核对 |
| 步行/跑步/骑行速度、海拔 | 设置资源与公开源码核对 |
| 随机偏移、历史有效期 | 设置资源与公开源码核对 |
| 前台通知、显示/隐藏摇杆 | REA 直接观察 |
| 原应用在线更新 | DEX 中 GitHub releases API；新实现不复刻远端更新 |

公开上游：https://github.com/ZCShou/GoGoGo （GPL-3.0-only）。它是在 DEX 中发现包名和更新 URL 后找到的，用于核对，不代替 REA 证据。

## 6. 限制与风险

- 静态分析不证明原应用运行时一定执行每条路径。
- 模拟位置是否被第三方 App 接受取决于其定位策略和反模拟检测；本工具不绕过第三方安全检测。
- Wi-Fi/基站/融合定位可能导致位置闪回；原应用也明确提示该固有限制。
- 原包仅 arm64-v8a 的限制来自百度原生库；新实现为纯 Java + osmdroid，不再受该 ABI 限制。
- 新应用不是原包的签名兼容升级，使用不同 applicationId 与签名，可与原包共存。
- 新应用地图改用 OpenStreetMap，搜索改用 Nominatim，地图数据、地名覆盖和视觉样式不会与百度地图完全相同；核心定位与摇杆功能一致，不能声称像素级或数据级“一模一样”。
