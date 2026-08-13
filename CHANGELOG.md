# Changelog

本应用遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)。版本号与 versionCode 对应（`versionCode = 主版本 × 10`）。

## [0.11] - 2026-08-13

### Changed
- 定稿正式包名 `io.github.zhundianapp.zhundian`（替换占位包名 `com.example.intervalreminder`；已安装旧包需卸载重装，升级链中断属预期）
- 切换为正式发布签名 `zhundian-release.keystore`；签名密码外置到 gitignored 的 `keystore.properties`（CI 可用 `KEYSTORE_*` 环境变量注入），移除对比测试签名 `compat-test`
- 移除 `gradle.properties` 中机器专属的 `org.gradle.java.home` 路径，本地构建改为通过 `JAVA_HOME` / IDE 的 Gradle JDK 指定
- 修正 `.gitignore`（覆盖 `app/build`、补 `.kotlin/`、`*.apk`、`*.keystore` 等），补齐 `README.md`、`LICENSE`、`CHANGELOG.md`，新增 GitHub Actions CI（单测 + debug APK）

## [0.10] - 2026-08-13

### Added
- 「天」间隔固定触发时刻：创建 / 编辑时可为每天 / 每 N 天指定固定触发时刻（`scheduleAnchorAt` 锚定，DB v10），改期 / 顺延不移动节奏
- 编辑页间隔单位选「天」时显示触发时刻选择（Material3 TimePicker）

## [0.9] - 2026-08-13

### Added
- 多 ROM 适配：小米 / OPPO / vivo / 华为 / 荣耀 / 魅族 / 三星自启动与后台管理入口候选表 + 逐条 `resolveActivity` 探测跳转，全失败回退应用详情页
- 保活设置聚合引导页：通知 / 精确闹钟 / 电池优化 / 悬浮窗 / 自启动逐项状态检测与一键跳转
- 顶部悬浮窗提醒（暖白横幅，到点不打断操作）

### Removed
- 移除「App 自建日程推送到系统日历」方向（DB v9 重建 `calendar_events` 表去掉 `syncToSystem` / `systemEventId`），保留系统日历 → App 导入提醒

## [0.8] - 2026-08-13

### Added
- 系统日历日程导入同步：窗口同步、按来源选择性同步（`enabledCalendarIds`）、只读日历排除
- 误删日程恢复：删除撤销 + 「已删除日程」列表 + 永久删除墓碑防同步复活（DB v7）
- 新建日程模块：本地日程、到点提醒、与触发记录统一的日历视图
- 铃声：系统铃声 / 本地音频、音量、响铃时长档位、强制震动；通知栏「完成 / 再隔 1 小时」快捷动作
- 内置日历视图：自绘月历、触发记录状态着色、单条 / 批量删除
- 底部导航（提醒 / 日历双 Tab）、页面切换去动画
- 多语言 8 种 + 应用内语言切换（Android 8–12 配置覆盖 / 13+ `LocaleManager`）
