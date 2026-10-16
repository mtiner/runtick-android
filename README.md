# RunTick Android · 燃刻

原生 Kotlin + Jetpack Compose 双端跑步计时客户端，从 iOS 版（`mtiner/runtick-ios`）1:1 迁移。最低支持 Android 8.0（API 26）。兼容 RunTick Cloudflare Worker API，可与 iOS / 网页端加入同一房间。没有使用 WebView。

- 仓库：`mtiner/runtick-android`
- 包名：`cc.runtick.app`（与 iOS TestFlight 身份一致）
- 服务端：`https://app.runtick.cc/`（与 iOS 相同的同源网关，Cookie 会话）

## 功能

- 启动品牌页、首次引导（设备/声音准备检查）
- 登录（首次使用的 ID 自动建号）、会话恢复、退出登录
- 房间创建/加入（4 位数字码）、起点/终点角色（乐观更新）
- HTTP 校时（20 次采样，uncertainty ≤ 250ms，120 秒有效）
- 起点三段发令音频精确调度（各就位/预备/枪声，`AudioTimestamp` 硬件时钟）
- 终点全屏触控计时（`MotionEvent` 单调时钟采样）、pending 时间戳持久化与断网重试
- 成绩展示（PB 庆祝）、距离保存、历史成绩（筛选/排序/分页）
- WebSocket 房间状态同步（30s 心跳、指数退避重连）、房间内屏幕常亮

## 构建

需要 JDK 17+ 与 Android SDK（platforms;android-35）。

```sh
# 生成 wrapper（首次）
gradle wrapper --gradle-version 8.10.2

# 编译
./gradlew assembleDebug

# 单元测试（纯逻辑用例：校时算法、乱序保护、角色乐观更新等）
./gradlew test
```

## 与 iOS 版的对应关系

| iOS | Android |
|---|---|
| SwiftUI Views | Compose `ui/` |
| `AppModel`/`RoomModel`（@Observable） | `services/`（StateFlow） |
| `MonotonicClock`（systemUptime） | `SystemClock.elapsedRealtime()` |
| AVAudioEngine 硬件时钟调度 | `AudioTrack` + `AudioTimestamp` |
| HTTPCookieStorage 会话 | OkHttp CookieJar |
| 触控取 `touchesEnded` 时刻 | `MotionEvent.ACTION_UP` 事件时间 |

## 验证

本机 `./gradlew test` 跑 Core 层 11 个移植用例；线上联调需两台设备（或一台真机 + 网页端 https://app.runtick.cc ）加入同一房间。
