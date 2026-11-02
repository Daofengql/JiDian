# 学习通 7.0.4 静态分析与适配边界

分析来源：ADB 提取用户设备已安装的 `com.chaoxing.mobile` 7.0.4（versionCode 10995）。未下载第三方改包，未复制学习通代码或二进制到项目发行内容。

## 已确认的证据

- AndroidManifest 声明 `com.baidu.location.f` 定位服务。
- 安装包含 `liblocSDK8b.so` 与 BaiduMapSDK 7.6.1 库。
- 存在 `com.chaoxing.mobile.sign.ui.SignInfoActivity` 等签到界面声明。
- `assets/editor/insertMaplocation.html` 读取 `window.name`（可 Base64 解码），JSON 中的 `att_map_location.name/address/iconUrl` 用于地图附件展示。
- 该附件格式是通用地图附件，不能据此推断签到接口也采用同一响应结构。
- DEX 由 SecNeo 包装，不能从外层 DEX 的少量可见类还原真实签到逻辑。

## 已实现

- 按指定应用生效的 Xposed 模块、Android LocationManager 回调与百度 SDK 回调边界适配。
- 在 Application.attach 和 Application.onCreate 后尝试 SDK 类加载，兼容真实类晚于外壳出现的情况；没有全局 ClassLoader.loadClass 监控。
- 独立 UID 验证应用覆盖系统定位与百度 API 测试替身。它证明桥接、回调与坐标转换，不代替学习通真实签到页面的端到端验证。
- 学习通真实进程的 LSPosed 日志确认适配代码加载，URI 可见性修复后定位桥连接成功。
- 用户主动粘贴或分享地点文本，解析坐标、地址、JSON、URL 编码和 Base64 地图附件；预览确认后导入。

## 尚未确认

学习通停留在登录界面，未登录或打开签到活动。没有可验证的签到详情 URL、地址响应或具体活动。因此没有加入基于活动 ID 的服务端地点查询，也未声称能解析任意学习通签到链接。导入不会发送签到请求。

本地 APK 与原始分析输出位于忽略的 artifacts/ 目录，不参与 APK 构建。未进行解壳或提取账号数据。
