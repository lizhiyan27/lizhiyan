# 影梭·独立版（LocationShadow）

基于 REA 逆向分析重新实现的 Android 模拟定位应用。使用独立 applicationId、独立签名、osmdroid / OpenStreetMap，不需要 Root，不注入其他应用。

> 仅供开发、定位测试与学习。请遵守目标应用条款与当地法律，不用于作弊、欺诈或侵犯他人权益。

## 功能

- 默认高德在线道路图/卫星图，自动进行 WGS84 ↔ GCJ-02 选点转换；可切换 CARTO、OSM 和 Esri，支持缓存刷新
- GPS/NETWORK 必选上报，支持 Fused/Passive 兼容 Provider，内置模拟服务与硬件 GNSS 诊断页
- 独立权限设置中心，显示权限状态并逐项自动跳转系统设置
- 经纬度直接输入，WGS84 / BD-09 显示与转换
- Nominatim + Photon 备用地址搜索、搜索历史、异常与空结果提示
- GPS + NETWORK 双 Provider 持续模拟
- 前台服务通知与停止操作
- 圆形/按键悬浮摇杆、步行/跑步/骑行速度、连续方向移动
- 历史位置、收藏、删除与保留天数（默认 7 天）
- 海拔、随机偏移、摇杆显示设置
- 位置复制与分享
- Android 8.1–15（API 27–35）适配

## 构建

要求：JDK 17+（推荐 21）、Android SDK Platform 35、Build-Tools 35.0.0。

在 `local.properties` 设置：

```properties
sdk.dir=D:\\path\\to\\android-sdk
```

Windows：

```bat
gradlew.bat :app:testDebugUnitTest :app:assembleRelease
```

APK 输出：

```text
app/build/outputs/apk/release/LocationShadow-1.2.0-release.apk
```

未配置签名文件时，release 使用本机 debug 签名，便于直接测试。正式自用时可在项目根目录创建 `keystore.properties`：

```properties
storeFile=signing/location-shadow.jks
storePassword=你的密码
keyAlias=locationshadow
keyPassword=你的密码
```

不要分享正式签名私钥。

## 手机设置

1. 安装 APK。
2. 点击主界面 **“权限设置（逐项自动跳转）”**，按提示依次完成定位、模拟位置、通知、悬浮窗和系统定位设置。
3. 模拟位置应用需要手动在开发者选项中选择 **影梭·独立版**；也可点击权限中心的“系统应用权限页”统一管理权限。
4. 回到应用，搜索/点击地图或输入坐标，然后点击“开始模拟”。
5. 停止模拟后再卸载应用；若 Provider 异常，可在系统定位设置中关闭再打开定位。

详见 [使用说明](../docs/USAGE.md) 和 [逆向报告](../docs/REVERSE_ANALYSIS.md)。

## 隐私与在线服务

应用不设置账号、广告、统计或私有更新服务器。普通地图默认请求高德在线道路图，卫星图默认请求高德在线影像，可切换 CARTO、OpenStreetMap 或 Esri，地址搜索优先 Nominatim、失败时使用 Photon；历史、收藏与设置保存在本机且禁用云备份。在线服务的使用政策由其运营方定义。

## 许可

GPL-3.0-only。详见 [LICENSE](LICENSE) 与 [NOTICE.md](NOTICE.md)。
