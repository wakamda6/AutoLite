# AutoLite

AutoDark 的**控制端 App**，通过 MQTT 向被控制端（AutoDark）发布「检查在线」「打卡」等指令，并接收打卡结果与掉线报告。

> **本项目基于 [DailyTask](https://github.com/AndroidCoderPeng/DailyTask) 的 1.5.6 分支修改而来，重点学习并验证 MQTT over TLS 与 mTLS（双向认证）流程。**

## 版本

v3.0.0

[查看完整版本日志](CHANGELOG.md)

## 功能

- 扫码绑定被控制端（AutoDark）设备
- 通过 MQTT 发布「检查在线」「打卡」指令
- 接收打卡结果 / 掉线报告
- 三种 MQTT 连接方式：无加密 / 单向 TLS / 双向 TLS（mTLS）

## 构建环境

| 依赖 | 版本 | 说明 |
|---|---|---|
| JDK | 11（推荐）或 17 | |
| Gradle | 7.6.6 | 官方 distributionUrl，wrapper 自动下载 |
| Android Gradle Plugin | 7.4.2 | |
| Kotlin | 1.8.0 | |
| compileSdk | 33 | |
| targetSdk | 33 | |
| minSdk | 24（Android 7.0） | |

构建命令：

```bash
./gradlew assembleDebug     # 调试包
./gradlew assembleRelease   # 发布包（需配置签名，见下）
```

发布签名：在项目根目录创建 `keystore.properties`（该文件已加入 `.gitignore`，不会提交）：

```properties
KEYSTORE_FILE=你的签名文件.jks
KEYSTORE_PASSWORD=你的密码
KEY_ALIAS=你的别名
KEY_PASSWORD=你的密码
```

## 运行环境

- Android 7.0（API 24）及以上
- 所需权限：相机（扫码）

## 三种 MQTT 连接方式

| 模式 | 端口 | 依赖 | 风险 |
|---|---|---|---|
| 无加密（默认） | 1883 | 云服务器 + MQTT broker | 账号密码明文传输（不安全） |
| 单向 TLS | 8884 | + 域名 + 公网证书 | 校验服务器身份（较安全） |
| 双向 TLS（mTLS） | 8883 | + 域名 + 自建 CA + 客户端证书 | 双向校验（最安全） |

端口默认值可在 `app/src/main/java/com/autolite/utils/TlsConfig.kt` 中调整。

## 使用方法

1. 点击「设置」配置服务器地址、连接方式、MQTT 账号。
2. 扫码绑定被控制端（AutoDark）设备的二维码。
3. 点击「连接」。
4. 点击「检查是否在线」或「打卡」。

## 致谢

本项目基于 [DailyTask](https://github.com/AndroidCoderPeng/DailyTask) 的 **1.5.6 分支**修改而来，原始版权归原作者所有。项目重点参考并验证了其 **MQTT over TLS 与 mTLS（双向认证）** 流程实现。
