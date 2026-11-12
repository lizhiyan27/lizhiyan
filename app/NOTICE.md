# 来源与许可说明

本项目是对用户提供的影梭 APK 进行独立功能重建的结果，应用 ID、签名、界面实现和地图服务均与原应用不同。

- 逆向分析工具：REA 6.8.0 + jadx-headless-mcp 0.7.1。
- 原应用：影梭 / GoGoGo 1.12.3，包名 `com.zcshou.gogogo`。
- 上游公开项目：https://github.com/ZCShou/GoGoGo （GPL-3.0-only）。
- 功能与行为证据以 REA 输出为准；公开源码用于交叉核对。
- `GeoUtils` 中 WGS84 / GCJ-02 / BD-09 的近似坐标转换算法参照上述 GPL 项目的 `MapUtils.java`，因此本项目整体按 GPL-3.0-only 发布。
- 模拟定位服务的双 Provider、100 ms 更新周期、Location 字段及摇杆速度参数来自逆向分析和 GPL 项目的公开行为。
- 没有复制原应用签名、百度 AK、私有账号、更新服务器或商标资源。
- 地图使用 osmdroid；默认道路图与卫星图使用高德在线瓦片（GCJ-02），可切换 CARTO Voyager、OpenStreetMap 或 Esri World Imagery；搜索优先 Nominatim，备用 Photon。在线服务受其各自使用政策约束。

本项目仅供 Android 开发、定位功能测试与学习。使用者应遵守目标应用条款和当地法律，不得用于作弊、欺诈或侵犯他人权益。
