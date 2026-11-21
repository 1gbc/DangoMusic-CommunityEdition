# B 站直播点歌台

适用于 GTNH 2.9.0、Forge 1.7.10、Windows x64。连接直播间或启用模拟弹幕时，Mod 自动启动 JAR 内置的助手。助手使用 .NET 8 Runtime。

## 安装与试播

1. 关闭游戏，用 `dangomusicgtnh-0.5.5-dev.5.jar` 替换实例 `mods` 目录里的旧版 DangoMusic。
2. 开始直播，复制 B 站直播地址里房间号的数字。在游戏切到网易云音源，打开「设置 → 界面与 HUD → B 站直播点歌台」。
3. 启用点歌台，关闭「模拟弹幕」，点击「填写房间号」。输入数字后会自动连接。房间号保留至本次游戏退出。
4. 在你的 B 站直播间发送下列任一点歌指令。核对 HUD 是否出现这首歌及缓存状态，随后用控制栏或快捷键按「下一首」。队首尚未缓存时最多等待 10 秒。点歌实际开播时离开待播队列，队列播完后继续之前的歌单。

| 指令 | 示例 |
| --- | --- |
| 歌名 | `/song 青のすみか` |
| 歌名和歌手 | `/song 泡沫 - 邓紫棋`，也可用 `gem` 或 `G.E.M.` |
| 歌名和歌手 ID | `/song 泡沫 - id:7763` |
| 歌曲 ID | `/song 3459356843` 或 `/song id:3459356843` |
| 网易云单曲链接 | `/song https://music.163.com/song?id=3459356843`。也支持 `/song` 加网易云单曲分享短链接或复制的单曲分享文本 |
| 原有指令 | `/点歌 青のすみか キタニタツヤ` |

有多个匹配版本时，在点歌台下方查看候选的歌手、专辑和歌曲 ID。选好版本后，再在 B 站直播间弹幕发送相应的 `/song 歌曲ID`。如果直播客户端无法完整发送分享链接，使用歌曲 ID 即可。

连接失败时，先核对填写的是直播地址里的房间号。设置页会区分房间不存在、接口暂时限制连接、匿名连接被拒绝等情况。稍后可在设置页点「重新连接」。还可以打开「模拟弹幕」，用「发送示例」测试游戏内点歌功能。

## 接入方式

助手根据公开直播间号解析实际房间 ID，向 B 站网页端获取访客设备标识及弹幕令牌，然后直连 B 站 WebSocket。访客设备标识只在助手运行时保留。接收、心跳和重连都由助手处理，游戏收到弹幕后继续使用现有的网易云匹配、缓存和点歌队列。参考 FMusic 2.9 的房间号接入流程。 [FMusic 连接源码](https://github.com/czqwq/FMusic/blob/ff1d224/src/main/java/com/Lilith/FMusic/server/bili/bilibili/BilibiliLiveClient.java#L180-L275)

## 构建和验证

~~~powershell
.\gradlew.bat build
dotnet run --project ./tools/bilibili-live-helper/tests/BilibiliLiveHelper.Checks.csproj
python -X utf8 ./tools/bilibili-live-helper/tests/check_process.py ./build/bilibili-live/publish/BilibiliLiveHelper.exe
~~~

游戏从标准输入向助手发送一行 UTF-8 JSON。初始配置示例如下。

~~~json
{"mode":"live","roomId":"123456"}
~~~

模拟模式发送 `{"mode":"mock"}`。两种模式均可发送 `{"type":"stop"}` 或关闭标准输入以退出。助手在标准输出逐行返回状态与弹幕消息。
