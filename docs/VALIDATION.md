# 构建与验证记录

## 环境

- Windows x64
- Oracle OpenJDK 21.0.2
- Android SDK Platform 35、Build-Tools 35.0.0、Platform-Tools 37.0.1
- Gradle 8.13（分发包已核对官方 SHA-256）
- Android Gradle Plugin 8.12.1
- REA 6.8.0 + jadx-headless-mcp 0.7.1

项目工作区含中文，首次 Gradle 单元测试出现 worker ClassNotFound；通过 `D:\lswork` 纯英文目录联接重新执行后全部通过，算法源码没有因此修改。

## 构建与测试

最终命令：

```bat
gradle --no-daemon :app:testDebugUnitTest :app:assembleRelease :app:lintDebug --continue
```

结果：**BUILD SUCCESSFUL**。

- Java 主代码编译：通过。
- Debug / Release D8、desugar、资源与 APK 打包：通过。
- `GeoUtilsTest`：7 tests，0 failures，0 errors。
  - 7 位坐标格式与 Locale 独立性。
  - 东/西/南/北移动方向。
  - 纬度 ±90 钳制与日期变更线经度环绕。
  - 境外 BD-09 转换不施加 GCJ-02 偏移，国内 WGS84 ↔ GCJ-02 往返转换。
  - 500 次随机偏移米制上限。
  - 零偏移返回原坐标。
- Android Lint：**0 errors，24 warnings**。
  - 保留警告：SDK 36 已发布、持续前台定位所需 WakeLock、旧 API 兼容分支、osmdroid 触摸无障碍提示、硬编码中文文案。
  - `ACCESS_MOCK_LOCATION` 只做条目级豁免，因为发布版本身就是模拟位置工具；没有全局关闭 Lint。

测试 XML：`app/build/test-results/testDebugUnitTest/TEST-com.dafeiyu.locationshadow.GeoUtilsTest.xml`。

## 最终 APK 静态验证

| 项目 | 结果 |
|---|---|
| 文件 | `LocationShadow-1.2.0.apk` |
| 大小 | 553,667 字节 |
| SHA-256 | `88ffbdd8b7e892f316637910807b3a23b2e36709faadbc2392ad07ad5b0efa0a` |
| applicationId | `com.dafeiyu.locationshadow` |
| 版本 | 1.2.0（versionCode 3） |
| minSdk / targetSdk | 27 / 35 |
| 签名验证 | `apksigner verify` 通过，APK Signature Scheme v2 |
| 对齐验证 | `zipalign -c -v 4` 通过 |
| 签名者 | `CN=Location Shadow, OU=Personal Build, O=Independent Reimplementation, C=CN` |
| 证书 SHA-256 | `5cf09408dc90666204d22e10985ebee4e8a3d6d4ddd489a3be8e5659c9e297c2` |
| ABI | 纯 Java，无原生 ABI 限制 |

原 APK 与新 APK 签名证书不同，不能作为原包签名升级安装。

## REA 对新 APK 的复核

REA 已成功读取新 APK：

- 包名、版本、SDK 和权限与 `aapt2 dump badging` 一致。
- 新 APK 仅 237 个类，不包含原应用的百度地图和大型 AndroidX 依赖。
- `MockLocationService.publishLocations()` 的反编译结果明确包含：
  - GPS accuracy 1.0、NETWORK accuracy 2.0。
  - GPS `satellites=7`。
  - `setTestProviderLocation("gps", ...)`。
  - `setTestProviderLocation("network", ...)`。
  - 两类异常均停止模拟并清理。

1.1.0 另由 REA 复核了 `PermissionsActivity.runFlowStep()` 的逐项跳转逻辑和 `MainActivity.refreshOnlineMap()` 的清缓存/在线刷新实现；权限中心已声明在 Manifest 中。
1.2.0 进一步复核了高德瓦片 URL 生成、WGS84 ↔ GCJ-02 显示转换、必选 GPS/NETWORK 与可选 Fused/Passive 上报，以及后台定位检查。

证据：`analysis/rea-clone-package.json`、`analysis/rea-clone-publishLocations.json`、`analysis/rea-clone-permission-flow.json`、`analysis/rea-clone-map-refresh.json`、`analysis/rea-clone-amap-source.json`、`analysis/rea-clone-map-coordinates.json`、`analysis/final-apk-signing.txt`、`analysis/final-apk-badging.txt`。

## 网络冒烟测试（桌面当前网络）

| 服务 | 结果 |
|---|---|
| 高德道路图 | HTTP 200，PNG 11,184 字节，1.2.0 默认普通图来源 |
| 高德卫星图 | HTTP 200，JPEG 8,641 字节，高德模式卫星图来源 |
| CARTO Voyager | HTTP 200，瓦片 2,049 字节，可切换备用普通图 |
| `tile.openstreetmap.org` | 连接失败，因此不作为唯一普通图来源 |
| `tile.openstreetmap.de` | HTTP 200，瓦片 11,923 字节，作为普通图来源 |
| Esri World Imagery | HTTP 200，瓦片 16,641 字节 |
| Nominatim | 连接超时，应用会自动尝试备用源 |
| Photon | HTTP 200，返回北京 GeoJSON 结果，作为备用搜索源 |

这些结果只证明测试时的桌面网络可达性，不代表公共在线服务永久可用。

## 独立代码复核后的处理

- 境外坐标转换、模拟运行时读取真实位置、极区/日期变更线边界已修正并重新测试。
- 静止时 `Location.speed` 保留默认/最近速度是原影梭的明确行为，为保持原功能一致而保留，并非遗漏清零。
- 模拟运行时“更新当前位置”会提示先停止模拟，避免把测试 Provider 位置误当真实位置。

## 尚未完成的验证

当前 `adb devices` 没有可用真机或模拟器，因此未在真实 Android 设备上验证：

- 开发者选项中选择模拟位置应用后的系统授权流程。
- 各厂商对 GPS/NETWORK 测试 Provider、悬浮窗和后台定位的策略。
- 地图/搜索在手机网络中的表现。
- 第三方 App 是否接受模拟位置。

不能把“构建、静态 REA 复核和单元测试通过”表述为“所有手机均已运行验证”。首次在手机上使用前，请按使用说明开启模拟位置应用并选择一个测试坐标。
