# 贡献指南

DangoMusic Community Edition 是 [DangoMusic](https://github.com/Sorihiko/DangoMusic) 的 Minecraft 1.7.10 / GTNH 移植项目，以 GPL-3.0 发布。提交代码即表示你同意以同一许可证发布你的贡献。

- 目标环境：GTNH 2.9.0 / Minecraft 1.7.10 / Forge 10.13.4.1614
- 主分支：`port/gtnh-1.7.10`（所有 PR 以此为目标）
- 许可证：GPL-3.0，见 [LICENSE](LICENSE)

## 环境要求

| 项 | 版本 |
|---|---|
| 运行 Gradle 的 JDK | **JDK 25**（`.java-version`） |
| 编译 toolchain | **JDK 8** |
| 内置 Apple Music 组件 | **.NET 8 SDK**，`dotnet` 可从 PATH 调用 |
| Gradle | wrapper 9.7.1（已随仓库提供） |
| 构建插件 | GTNHGradle 2.0.33 / RetroFuturaGradle |
| Minecraft / Forge | 1.7.10 / 10.13.4.1614，MCP `stable_12` |

首次构建需要联网下载 GTNH 插件、Forge/MCP 与 RetroFuturaGradle 工具链。若所在网络无法直连 Maven/GitHub，请自行配置 Gradle 代理（`systemProp.http(s).proxyHost` / `proxyPort`）。

### Windows 注意事项

如果 JDK 25 在启动 Gradle 时报 `Unable to establish loopback connection` / `Invalid argument: connect`，通常是 `%LOCALAPPDATA%\Temp` 被重定向（MSIX/AppContainer）导致 AF_UNIX 不可用。把临时目录指到普通路径即可：

```bash
set TEMP=C:\Temp
set TMP=C:\Temp
```

## 构建

```bash
# 在仓库根目录
./gradlew build
```

Windows 也可使用：

```bat
gradlew.bat build
```

产物在 `build/libs/`：

- `dangomusicce-<version>.jar`：安装到 GTNH 实例 `mods/` 的 JAR
- `-dev.jar`：开发用（未重混淆）
- `-dev-preshadow.jar`：shadow 之前的开发包
- `-sources.jar`：源码包

## 运行与隔离测试

1. 准备一个与目标版本一致的 GTNH 2.9.0 实例（Minecraft 1.7.10 / Forge 10.13.4.1614 / lwjgl3ify + LWJGL 3）。
2. 把 `build/libs/dangomusicce-<version>.jar` 放入实例的 `mods/`，移除旧版本。
3. 启动游戏，进入世界后按 **M** 打开播放器；主菜单内也可按 M。
4. 账号凭据、设置和缓存目录：
   - `config/dangomusicce/settings.json`：模块与选项
   - `config/dangomusicce/settings.cfg`：初始音量
   - `config/dangomusicce/accounts/`：网易云/QQ/酷狗会话（**不要提交**）

实机验证至少覆盖：启动、M 键、搜索、播放、暂停/继续、切歌、歌词与 HUD。涉及平台登录的改动需要说明测试账号的脱敏状态。

## 分支命名

从 `port/gtnh-1.7.10` 拉分支，使用小写英文和连字符：

- `feat/<简短描述>`：新功能
- `fix/<简短描述>`：缺陷修复
- `docs/<简短描述>`：文档
- `refactor/<简短描述>`：重构
- `perf/<简短描述>`：性能
- `test/<简短描述>`：测试
- `chore/<简短描述>`：杂项、构建、CI

一个分支只做一件事。不要直接改 `port/gtnh-1.7.10`，也不要新建 `main` / `dev` 作为长期分支。

## 提交规范

使用 [Conventional Commits](https://www.conventionalcommits.org/)：

```text
<type>(<scope>): <描述>
```

- `type`：`feat`、`fix`、`docs`、`refactor`、`perf`、`test`、`build`、`ci`、`chore`、`revert`
- 描述用祈使句、小写开头，尽量不超过 72 字符
- 正文写“为什么”，不要只写“改了什么”
- 关联 issue：`Refs #123` 或 `Fixes #123`
- 破坏性变更：`feat(api)!: ...`，并在 footer 写 `BREAKING CHANGE: ...`

示例：

```text
feat(lyrics): cache parsed lyrics by song id

Avoid re-parsing the same lyric payload when switching back to a track.
Refs #42
```

## PR 检查清单

提交 PR 前自查：

- [ ] `./gradlew build` 通过
- [ ] 相关行为已在 GTNH 2.9.0 实机验证（附日志或截图）
- [ ] 没有混入无关改动或调试代码
- [ ] 没有提交密钥、Cookie、token、账号配置、音频资源或构建产物
- [ ] 新行为已补充文档；用户可见变更已加入 `CHANGELOG.md` 的“未发布”区
- [ ] PR 标题符合 Conventional Commits
- [ ] 关联了相关 issue（如有）

PR 保持在可评审的规模；超过约 1000 行时建议拆分。评审开始后不要 force push；确需改写时使用 `--force-with-lease` 并提前告知评审者。

## CHANGELOG

用户可见的变更写在 `CHANGELOG.md` 顶部的 `## 未发布` 下。正式发布时，把本轮累计的更新整理到对应版本的条目中，保留已有历史记录。

## 版本号与发布

- **本地开发版**使用 `X.Y.Z-dev.N`。前三段为目标版本，开发序号从 1 开始，同一目标的后续构建递增，例如 `0.5.6-dev.1`、`0.5.6-dev.2`。
- **正式版**使用 `X.Y.Z`。维护者确认发布后，Gradle 版本、Mod 元数据、JAR 文件名和 Git 标签统一使用同一正式版本号，例如 `0.5.6`。
- **正式大版本**的版本号由项目维护者确定。GitHub Releases 仅用于确认发布的正式小版本和大版本，上传对应源码版本的可安装 JAR 和更新说明。
- **开发版留在本地**。每轮开发版在本地更新 `build.gradle.kts` 并构建验证。开发迭代无需逐版推送，不创建 GitHub 开发版标签、Release 或预发布，也不上传开发版 JAR。

GitHub Actions 在 PR 和默认分支更新时使用提交中已有的版本号运行检查和构建。正式版本号对应的构建可从 Actions 的 `dangomusicce-jars` 下载。正式发布前核对 Git 标签、Gradle 版本号、JAR 和更新说明对应同一版源码。

## 安全与敏感数据

- **不要提交**：账号 Cookie / token、`.p8` 私钥、开发者令牌、完整会话、带签名的播放地址、音频文件、构建缓存。
- 账号与会话只保存在本地实例的 `config/dangomusicce/accounts/`。
- 漏洞请按 [SECURITY.md](SECURITY.md) 私密报告，不要公开利用细节。

## 维护者：分支保护清单

以下设置需要仓库 owner/admin 在 GitHub 网页端开启（贡献者无权限修改）：

- [ ] `port/gtnh-1.7.10` 要求 PR 后才能合并
- [ ] 要求 CI（`.github/workflows/build.yml`）通过
- [ ] 要求对话解决后再合并
- [ ] 禁止 force push
- [ ] 禁止删除分支
- [ ] 可选：要求线性历史 / 签名提交
- [ ] 可选：Include administrators

当前仓库由 `1gbc` 维护；协作者没有 admin 权限，不要尝试修改默认分支或分支保护。
