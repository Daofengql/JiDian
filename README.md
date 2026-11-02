# JiDian

Android 模拟定位工具，使用本地打包的 **MDUI 2.1.5 / Material Design 3** 和 Leaflet 1.9.4 界面。支持 Android 8.0（API 26）及以上，WebView 需支持现代 Web Components（建议 Chromium 80 及以上）。

支持定点与路线模拟、实时坐标导入、卫星地图和后台悬浮小地图，可选择 Xposed／LSPosed 按应用 Hook 或 Root 系统定位。Android 包名保留为 `com.root.fakeloc`，兼容已有安装与本地配置。

## 使用

1. 安装 APK 后默认为 **Hook 模式**，JiDian 不主动请求 SU。需要设备上已有可用的 Xposed／LSPosed 兼容框架；这并不表示可以在任意未安装框架的设备上直接 Hook。
2. 在框架管理器中启用「JiDian」，作用域勾选目标应用，再重新打开目标应用。「运行环境」可选择目标，默认学习通（`com.chaoxing.mobile`），也可直接打开目标应用。只有框架作用域与应用内目标列表都选中的应用会收到模拟位置。需要系统级定位时，主动切到 **Root 模式**，应用才会请求 SU 并自动配置定位环境。
3. 在地图上选点，或输入 **WGS-84 纬度、经度**。拖动标记可以微调位置。
4. 定点模式可分别开启「轻微抖动模拟」「基站模拟」「海拔模拟」。抖动关闭时保持精确坐标；开启后可设 0.5–10 米半径。海拔关闭时不上报模拟海拔，开启后可设 −500 至 9000 米。
5. 路线模式需要至少两个路线点，可设置速度、步频与往返循环。路线按点间直线行进，不自动沿道路规划。
6. 点击「开始模拟」。定点运行中可以继续点选地图、拖动标记或输入坐标，位置立即刷新；也可以点击刷新按钮重新推送当前位置。
7. 点击地图上方的「后台悬浮小地图」图标开启系统悬浮窗。Hook 模式首次进入系统设置授予悬浮权限，Root 模式自动配置。切到其他应用后显示，返回主界面自动隐藏。
8. 路线运行中可拖动摇杆改变方向和移动速度，松手停在当前位置；点击「继续路线」会从当前位置连回暂停的路线位置，再继续原路线。页面、悬浮窗或通知栏均可停止模拟。

Hook 模式在目标进程内处理 Android `LocationManager` 的监听、单次请求与最近位置，并适配百度定位回调与 Google LocationResult。实时帧通过只读 ContentProvider 传递，每次读取核对 Binder UID 与所选包名；取消选择、停止或帧过期后恢复原始 API。百度坐标按 SDK 请求的 WGS-84／GCJ-02／BD-09LL 转换，速度从 m/s 转为 km/h。PendingIntent 定位和其他厂商 SDK 尚未覆盖。

Root 模式使用系统 TestProvider，定位包仍带系统的模拟位置标志。步频、传感器和基站数据目前只计算与展示，尚未注入其他应用。

## 地址与坐标导入

- 点击「输入坐标 → 从地址或链接导入」，粘贴地点文本、包含经纬度参数的链接或 JSON；也可通过 Android 分享菜单发送文字给 JiDian。
- 支持 `latitude/longitude`、`lat/lng/lon`、中文经纬度、`geo:纬度,经度`、嵌套或 URL 编码 JSON、地图附件 `att_map_location`，以及百度 marker 链接。一次可预览并选择多个候选地点。
- 已注明坐标系时自动选择；未注明时必须手动选择 GPS（WGS-84）、高德（GCJ-02）或百度（BD-09）。确认后转为 WGS-84，导入定点或追加路线点。运行中的定点可立即更改，路线运行时禁止编辑。
- 只有地址时，可点击「查找地址坐标」，通过 Android 系统 Geocoder 查询并预览。可用性、结果精度和网络取决于系统提供的地理编码服务；查找失败可直接输入坐标。
- 链接仅在本机解析，不自动访问签到接口。**只有活动 ID 的签到链接无法还原地点**，需要复制详情中的地址或坐标。不会执行签到或提交表单。
- 已分析实机安装的学习通 7.0.4，确认使用百度定位 SDK，模块能在其进程加载并连接。安装包加固且没有可用的登录后签到实例，尚未确认学习通签到详情的专用响应格式，也不保证所有签到页面可直接解析。

## 界面与交互

- 浅色、深色主题，首次随系统设置；手机、平板、横屏和桌面尺寸自适应。
- 地图铺满应用页面，无顶部标题栏。页内悬浮面板默认收起，始终可切换模式、开始／停止，点击「参数」展开调节。
- 悬浮面板按模式显示设置：定点为三个独立开关，路线为速度与步频；收起后继续保留设置。横屏将面板置于右侧。
- 后台悬浮小地图默认宽 248 dp：定点只显示小地图、坐标入口、图层、回到位置和启停；路线增加摇杆、移动状态与「继续路线」，模式和详细参数在主界面调整。
- 拖动悬浮窗标题可移动；「−」收起为标题条，「↗」返回主界面，「×」关闭悬浮窗且保留正在运行的模拟。
- 路线距离和单程耗时预估、编号点位、拖动、撤销、清空及恢复。
- 定点和路线各自保留，参数与路线自动保存到本机。
- 图层选择支持高德标准地图、带道路注记的卫星地图、OpenStreetMap，保存最近使用的图层。高德底图自动在 GCJ-02 与 WGS-84 之间转换，切换时保留实际位置。
- 定位针以底部尖端精确对齐所选坐标；路线使用紧凑编号圆点，区分起点和终点。
- 地图加载失败时提供重试，坐标输入和本地控件仍可使用。底图瓦片需要网络。
- 定点实时展示位置、抖动、基站计算、海拔与精度；路线展示速度、配速、步频及累计步数，可跟随实时位置。

## 开发与构建

使用 Gradle Wrapper 9.5.0、Android Gradle Plugin 9.3.0、JDK 21 和 Android SDK 35。Java 源码目标为 17；修改前端时需要 Node.js 20 或以上及 npm。可以通过 Android Studio 配置 SDK，或设置 `ANDROID_HOME`；本机的 `local.properties` 不提交到仓库。

通过 SSH 克隆：

```sh
git clone git@github.com:Daofengql/JiDian.git
cd JiDian
```

前端构建与预览：

```powershell
npm ci
npm run build:web
npm run preview
```

预览地址为 `http://127.0.0.1:4173`。浏览器可体验地图和参数设置，实际模拟服务只在 Android 中启动。

```powershell
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug
npm run test:web
```

调试 APK：`app/build/outputs/apk/debug/app-debug.apk`。

macOS／Linux 将 `.\gradlew.bat` 换为 `./gradlew`。构建 Release 使用 `:app:assembleRelease`，输出 `app/build/outputs/apk/release/app-release-unsigned.apk`，需要自行签名后安装。仓库不包含签名密钥。

前端源码在 `web/`，构建后生成 `app/src/main/assets/ui/`。生成资源随源码保留，因此单独构建 Android 不需要安装 Node；存在 `node_modules` 时 Gradle 会自动更新前端资源。修改 MDUI、Leaflet 或构建依赖后，先运行 `npm ci`。

## 实现要点

- 使用 `WebViewAssetLoader` 从本地 HTTPS origin 加载界面；MDUI、图标和地图脚本无需 CDN。
- 原生桥只向本地页面开放；禁用文件访问、混合内容和明文网络，外部链接交给系统浏览器。
- 滑块即时更新相关标签，180 ms 合并参数同步；实时状态按动画帧更新，后台页面暂停绘制。
- 服务在单一工作线程中更新配置，复用引擎，保留路线进度和累计步数。
- 主界面与后台小地图使用同一服务、原生配置和实时数据；定点更改会立即唤醒定位更新。摇杆移动时以 100 ms 更新，松手、收起、隐藏或输入超时都会停止手动移动，静止时恢复设定的更新频率。
- 服务启动失败与权限失效会反映到界面；使用单调时钟计算增量，停止后释放定位提供者和唤醒锁。

验证记录见 [docs/UI-QA.md](docs/UI-QA.md)，Hook 独立验证应用见 [tools/hook-probe/README.md](tools/hook-probe/README.md)。

## 许可证

项目源码采用 [MIT License](LICENSE)。打包的前端依赖保留各自许可证，见 [third-party-notices.txt](app/src/main/assets/ui/third-party-notices.txt)；Gradle Wrapper 保留原有 Apache-2.0 许可声明。分析使用的第三方 APK、设备数据与本机测试产物不在公开仓库中。
