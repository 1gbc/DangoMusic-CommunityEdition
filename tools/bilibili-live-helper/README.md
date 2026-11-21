# B 站直播点歌台

适用于 GTNH 2.9.0、Forge 1.7.10、Windows x64。连接直播间或启用模拟弹幕时，Mod 自动启动 JAR 内置的助手。助手使用 .NET 8 Runtime。

## 安装与试播

1. 关闭游戏，用 `dangomusicgtnh-0.5.5-dev.4.jar` 替换实例 `mods` 目录里的旧版 DangoMusic。
2. 开始直播，复制 B 站直播地址里房间号的数字。在游戏切到网易云音源，打开「设置 → 界面与 HUD → B 站直播点歌台」。
3. 启用点歌台，关闭「模拟弹幕」，点击「填写房间号」。输入数字后会自动连接。房间号保留至本次游戏退出。
4. 在你的 B 站直播间发送 `/song 歌曲名`，也可以使用 `/song 歌曲名 - 歌手`、`/song id:233931` 或带歌曲 ID 的网易云单曲链接。旧的 `/点歌 歌曲名 歌手` 可以继续使用。核对 HUD 是否出现这首歌及缓存状态，随后用控制栏或快捷键按「下一首」。队首尚未缓存时最多等待 10 秒。点歌实际开播时离开待播队列，队列播完后继续之前的歌单。

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
