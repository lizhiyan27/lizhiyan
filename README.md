# 影梭·独立版（LocationShadow）

基于 REA 逆向分析重新实现的 Android 模拟定位项目。独立 applicationId 与签名，不需要 Root，不注入其他应用。

当前版本：**1.2.0（versionCode 3）**。

## 主要功能

- 高德在线道路图与卫星图，自动进行 WGS84 ↔ GCJ-02 选点转换。
- 可切换 CARTO、OpenStreetMap 和 Esri，支持清理缓存刷新。
- GPS / NETWORK 必选模拟 Provider，支持 Fused / Passive 兼容上报。
- 前台定位服务、圆形/按键悬浮摇杆、三档速度、海拔与随机偏移。
- 地址搜索、坐标输入、历史、收藏、复制与分享。
- 集中权限中心，逐项自动跳转系统设置。
- 模拟上报与硬件 GNSS 分离的定位诊断页。

## 项目结构

```text
app/        Android Gradle 工程、源码、资源和单元测试
docs/       逆向分析、功能对照、更新记录、使用与验证文档
analysis/   脱敏后的 REA 证据与在线服务冒烟测试
scripts/    交付打包脚本
```

本仓库不提交下载的 JDK、Node、Android SDK、Gradle 分发包、第三方参考仓库、APK、构建缓存、签名私钥或本机 `local.properties`。

## 构建

要求：JDK 17+（推荐 21）、Android SDK Platform 35 和 Build-Tools 35.0.0。

在 `app/local.properties` 中设置本机 SDK 路径：

```properties
sdk.dir=D:\\path\\to\\android-sdk
```

然后执行：

```bat
cd app
gradlew.bat :app:testDebugUnitTest :app:assembleRelease :app:lintDebug
```

APK 输出：

```text
app/app/build/outputs/apk/release/LocationShadow-1.2.0-release.apk
```

正式签名配置写在 **不纳入 Git 的** `app/keystore.properties` 中；未配置时使用本机 debug 签名。不要提交正式签名私钥。

## 文档

- [REA 逆向分析](docs/REVERSE_ANALYSIS.md)
- [功能对照](docs/FEATURE_MATRIX.md)
- [1.2.0 国内图源与 GPS 修复](docs/UPDATE_1.2.0.md)
- [使用说明](docs/USAGE.md)
- [构建验证记录](docs/VALIDATION.md)

## 许可与用途

GPL-3.0-only，详见 [app/LICENSE](app/LICENSE) 和 [app/NOTICE.md](app/NOTICE.md)。

仅供开发、定位测试和学习。请遵守目标应用条款和当地法律，不用于作弊、欺诈、侵犯他人权益或绕过第三方安全检测。模拟 Location 与硬件 GnssStatus 不同，普通 Android 接口不能伪造卫星信噪比。
