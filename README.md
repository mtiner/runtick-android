# RunTick Android · 燃刻

原生 Kotlin + Jetpack Compose 双端跑步计时客户端，与 iOS 版（`mtiner/runtick-ios`）共享训练流程和后端协议。最低支持 Android 8.0（API 26）。兼容 RunTick Cloudflare Worker API，可与 iOS / 网页端加入同一房间。没有使用 WebView。

- 仓库：`mtiner/runtick-android`
- 包名：`cc.runtick.app`（与 iOS TestFlight 身份一致）
- 服务端：`https://app.runtick.cc/`（与 iOS 相同的同源网关，Cookie 会话）

## 功能

- 启动品牌页、首次引导（设备/声音准备检查）
- 登录（首次使用的 ID 自动建号）、会话恢复、退出登录
- 房间创建/加入（4 位数字码）、起点/终点角色立即乐观更新，可连续切换，后台按顺序提交
- 任一端确认发令设置后，起点自动全屏发令并显示倒计时，终点进入全屏触控计时
- 固定/随机发令间隔 0–10 秒整数；旧配置自动取整和限幅
- 单人起跑记录入口位于起跑页及历史成绩页；关于/历史/单人记录支持系统返回
- 主界面与全屏记录页使用系统安全区域，避让状态栏、刘海和开孔
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

`./gradlew testDebugUnitTest` 验证校时、角色并发切换、设置迁移等逻辑；`Android UI validation` 在带刘海的 Android 15 模拟器上验证返回层级、整数滑块及双端记录流程。线上联调仍需两台设备（或一台真机 + 网页端 https://app.runtick.cc ）加入同一房间。
