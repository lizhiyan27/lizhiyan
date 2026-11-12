# 1.2.0 国内图源与 GPS 问题修复

## 国内在线图源

默认普通图改为 **高德在线道路图**，高德模式下卫星图改为 **高德在线卫星影像**。当前桌面网络实测：

- 高德道路瓦片：HTTP 200，PNG，11,184 字节。
- 高德卫星瓦片：HTTP 200，JPEG，8,641 字节。

仍可切换 CARTO Voyager、OSM 德国镜像、OSM 官方在线和 Esri 卫星图。

高德地图使用 GCJ-02。本版在高德模式下：

- 将内部 WGS84 标记坐标转换为 GCJ-02 显示。
- 将地图点击的 GCJ-02 坐标反算为 WGS84 后保存和模拟。
- 切换图源时重新计算标记和地图中心，避免同一坐标被当成两种坐标系重复偏移。
- 历史、搜索、分享和模拟上报仍统一使用 WGS84。

“最新图源”指实时请求的当前在线瓦片，不代表每条道路都与现实同步，也不代表卫星影像为实时拍摄。

## 模拟位置兼容性修复

1. **始终显式调用 `setTestProviderEnabled(provider, true)`**。部分 ROM 的真实 Provider 已启用，但测试 Provider 并未启用，仅检查 `isProviderEnabled` 会漏掉这种情况。
2. GPS 和 NETWORK 仍是必选 Provider，任一核心上报失败都会停止并显示错误。
3. 在系统声明支持时增加 **Passive / Fused** 测试 Provider；创建或上报失败只停用对应兼容 Provider，回退 GPS/NETWORK，不再导致整个服务启动失败。
4. 增加垂直精度、速度精度和方向精度字段，保持每次上报时使用当前系统时间和 elapsedRealtimeNanos。
5. 新增兼容模式设置，默认开启，下次启动模拟时生效。

## GPS 信号弱与无位置的区别

Android 有两套不同状态：

- **Location**：经纬度、海拔、速度、方向、精度等，可由开发者模拟位置应用提供。
- **GnssStatus**：可见卫星、参与解算卫星、C/N0 信噪比等硬件 GNSS 状态。

公开 Android API 没有向其他应用注入 GnssStatus 的接口。`Location.extras.satellites=7` 只是附加字段，不会生成硬件卫星回调，也不会改变其他 App 的 GPS 信号图标。

因此，如果目标 App 只读取普通 Location，本版的 Provider 兼容修复可能解决“无具体位置”；如果它强制要求真实 GnssStatus、卫星信噪比或反模拟检测，普通无 Root 应用无法保证通过，也不能声称可以伪造硬件信号。

官方参考：

- [LocationManager](https://developer.android.com/reference/android/location/LocationManager)
- [GnssStatus](https://developer.android.com/reference/android/location/GnssStatus)
- [GnssStatus.Builder](https://developer.android.com/reference/android/location/GnssStatus.Builder)

## 定位诊断页

主界面、设置页和权限中心均可进入“定位诊断”，显示：

- 模拟服务运行状态。
- 实际上报 Provider 列表。
- 最后成功上报时间、坐标和错误/回退原因。
- 模拟位置 AppOps、后台定位和电池优化状态。
- 系统 Provider 启用状态。
- 硬件 GNSS 回调中的可见卫星、参与解算数量和最大 C/N0（设备支持时）。

诊断页可复制文本，便于进一步排查。硬件 GNSS 与模拟坐标分开显示，避免用“信号弱”误判模拟服务失效。

## 新增后台稳定设置

权限中心新增：

- Android 10+ 后台定位权限。
- 忽略电池优化设置。

自动流程更新为：定位权限 → 后台定位 → 模拟位置应用 → 通知 → 悬浮窗 → 系统定位服务 → 忽略电池优化 → 应用详情页。被拒绝或跳过的项目会保留在清单中，可单独重新设置。
