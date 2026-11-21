# Android 账号切换流程原型

> 文档同步（2026-11-20）：本原型已由正式 App 取代（首版 1.0.0）。下文仅保留早期「非 Root、自有测试包」的流程验证记录，不继续扩展原型；正式实现见 [账号切换 App](app/账号切换App原型.md)，按需事项及空心圆点说明见 [README 当前进度](../README.md#当前进度)。本次未重跑原型或真机测试。

本原型只操作自有测试包 `dev.hoyoswitch.fixture`，使用 A、B 两个模拟账号，不使用 Root 或真实游戏数据。启动器图标直接使用仓库根目录的 `0i.png`，构建时核对 APK 内图标与原文件字节一致。

## 构建、安装和使用

需要 Python、JDK、ADB、Android SDK 36 和 Build Tools 36.0.0。SDK 优先取 `ANDROID_SDK_ROOT` 或 `ANDROID_HOME`。

```powershell
python .\prototype\build.py
python .\prototype\switch.py --serial <设备序列号> --install
python .\prototype\switch.py --serial <设备序列号> --account B
python .\prototype\switch.py --serial <设备序列号> --account A --launch
```

第一条切换命令执行后，手动点桌面上的「账户切换测试」；带 `--launch` 的命令自动打开。首屏直接显示 `Test account A` 或 `Test account B`。生成的 APK 与本地调试密钥位于 Git 忽略的 `prototype/build/`。

## 真机验收

保持手机解锁，执行 `python .\prototype\switch.py --serial <设备序列号> --smoke`。本次已通过：

| 场景 | 观察结果 |
| --- | --- |
| 基线 | 首屏 `Test account A` |
| A → B，不自动重开 | 确认进程保持退出；另发启动命令后，首屏为 B（模拟用户独立启动） |
| B → A，自动重开 | 首屏为 A |
| 暂存配置非法 | 拒绝发布，重开后仍为 A |
| 模拟发布后校验失败 | 回退至 A，重新读取与首屏验证均为 A |

测试记录见本机 `prototype/build/smoke.json`。失败测试验证的是脚本运行中的两种错误路径；未验证断电、ADB 断开或两个脚本并发运行。原型只验证流程，不代表星铁或其他真实客户端已适配。
