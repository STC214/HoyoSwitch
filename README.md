# 米哈游安卓账号切换工具

在 **已取得 Root 权限的安卓手机** 上运行的米哈游账号切换 App：为同一款游戏保存多个账号状态，需要时一键切换并重新登录。

当前版本 **1.4.2**（应用名「米游账号切换」，正式签名 RSA-4096），支持：

- 崩坏：星穹铁道 4.7.0
- 原神 7.2.0
- 绝区零 3.3.0

> 三款游戏均已完成真机双向切换验证（以游戏内 UID 对比确认）。机制结论仅适用于已测设备与版本，游戏大版本更新后可能需要重新差分文件清单。

## 功能

- 每款游戏独立分栏，按账号去重，一个账号一张快照卡
- 拍摄快照不打断游戏；切号自动停止游戏 → 写入账号文件 → 校验 → 重新拉起
- 切换前自动保存安全快照，失败自动回滚；历史版本可回滚
- 快照加密（Android Keystore AES-256-GCM）
- 卡片可编辑账号/密码备注（密码加密、默认打码、账号与密码可分别复制）
- 记住上次使用的分栏；悬浮「更新快照」按钮
- 正式签名包与 GitHub Release

## 发布与安装

GitHub Release：<https://github.com/STC214/HoyoSwitch/releases/tag/v1.4.2>

本地构建（需要 Python、JDK、ADB、Android SDK 36 / Build Tools 36.0.0）：

```powershell
python .\app\build.py             # 调试签名 -> app\build\account-switch.apk
python .\app\build.py --release   # 正式签名 -> app\build\account-switch-release.apk
```

- 正式密钥：`app/keystore/hoyoswitch-release.p12` 与 `keystore.txt`（已被 Git 忽略，需随项目保存）
- 正式证书 SHA-256：`81a5fc64529c23f1eef5a9de9cf32d6f6d71684aaf655c33f0adae36f59f9079`
- 正式包与调试包签名不同：安装正式包需先卸载旧版，会清空快照及快照加密密钥

## 当前进度

- [x] 星铁：4 文件切换集，双向验证
- [x] 原神：3 文件切换集，双向验证（游戏内 UID 已脱敏，A/B 不同）
- [x] 绝区零：3 文件切换集，双向验证（游戏内 UID 已脱敏，A/B 不同）
- [x] 账号卡、账号密码、加密存储、历史回滚、悬浮按钮、分栏记忆
- [x] 正式签名、GitHub Release（1.0.0 → 1.4.2）
- [ ] 最近删除回收站
- [ ] 游戏大版本更新后的兼容性复核

## 文档

- [详细需求文档](docs/需求文档.md)
- [App 说明与实现](docs/app/账号切换App原型.md)
- [项目进度与续接记录](docs/PROGRESS.md)
- 点位验证：[星铁](docs/research/星铁登录状态点位与验证记录.md) / [原神](docs/research/原神账号状态点位与验证记录.md) / [绝区零](docs/research/绝区零账号状态点位与验证记录.md)
- [PC 验证工具](docs/tools/账号状态快照.md)
- [工作区规则](AGENTS.md)

## 目录约定

- `app/`：安卓 App 源码与构建脚本；`app/keystore/` 放正式密钥（Git 忽略）
- `tools/`：PC 端验证脚本，不属于交付 App
- `prototype/`：早期流程原型（已被正式 App 取代，仅留记录）
- `docs/`：需求、进度、调研、工具文档
- `game-clients/`：游戏安装包（Git 忽略）
- `account-snapshots/`：本机敏感快照与备份（Git 忽略）

## 注意事项

- Root 操作涉及游戏本地数据，操作前请自行备份
- 快照只在会话有效期内可用；游戏内删除账号或清除数据会让旧快照失效
- 不要更换签名密钥，否则已安装版本无法升级，且快照加密密钥会随卸载丢失
- 账号密码仅作备注，不参与切换逻辑
