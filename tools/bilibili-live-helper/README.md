# B 站直播点歌助手

DangoMusic 在 Windows 上自动启动随 Mod 分发的助手。主播开启「B 站直播点歌台」后只需填写自己的身份码。项目方部署一次签名服务，在构建 Mod 时预设服务地址。模拟模式可直接在游戏里试用。

## 部署给其他主播使用

签名服务适用于运行 .NET 8 的主机。部署包提供可移植的程序和 Linux 的 systemd、Nginx 配置示例。也可从源码发布同样的程序。

~~~powershell
dotnet publish tools/bilibili-live-helper/BilibiliLiveHelper.csproj -c Release --self-contained false -p:UseAppHost=false -p:DebugType=None -o build/bilibili-live/server
~~~

1. 将部署包的 `app` 目录内文件复制到主机上的 `/opt/dangomusic-bilibili-signer/`。只在服务器上配置 B 站项目 App ID、Access Key ID 和 Access Key Secret。采用 systemd 示例时，参考 `signer.env.example` 创建仅 root 可读的 `/etc/dangomusic-bilibili-signer.env`，将服务文件放入 `/etc/systemd/system/`。执行 `sudo systemctl daemon-reload` 和 `sudo systemctl enable --now dangomusic-bilibili-signer`。也可通过已有托管平台注入同名环境变量，直接运行下面的命令。

   ~~~sh
   dotnet BilibiliLiveHelper.dll --hosted-signer
   ~~~

2. 服务仅监听 `127.0.0.1:19754`。用同机的反向代理和有效证书对外提供一个 HTTPS 域名。Nginx 示例需要替换域名和证书路径，用 `nginx -t` 检查后重载。示例按 IP 限制访问频率，并关闭访问日志。使用其他代理时保留请求体大小与频率限制，并关闭身份码及会话数据的日志记录。
3. 先访问本机及公开域名的 `/healthz`。均返回 HTTP 204 后，执行以下命令，把公开域名写进 Windows 客户端安装包。

   ~~~powershell
   .\gradlew.bat build "-PbilibiliSignerUrl=https://signer.example.com/"
   ~~~

4. 主播在 GTNH 2.9.0 里选择网易云，打开「设置 → 界面与 HUD → B 站直播点歌台」，启用功能、关闭模拟模式、填写自己的主播身份码。开播后发送 `/点歌 歌曲名 歌手`。先用项目方自己的直播间验证，再请另一位主播用自己的身份码验证。B 站项目实际允许的使用范围由开放平台确认。

服务只为开启场次、项目心跳和关闭场次签名。开启场次返回随机会话令牌，心跳和关闭必须带对应令牌。助手用官方提供的长连信息直接接收 B 站弹幕，弹幕内容不经过签名服务。服务器重启会清除会话，主播可在游戏中重新连接。

## 构建

使用 .NET 8 SDK 构建。从仓库根目录运行：

~~~powershell
dotnet publish ./tools/bilibili-live-helper/BilibiliLiveHelper.csproj -c Release -r win-x64 --self-contained false -p:DebugType=None
~~~

输出目录为 tools/bilibili-live-helper/bin/Release/net8.0/win-x64/publish/。运行助手需要 Windows x64 版 .NET 8 Runtime。测试签名服务时，将发布目录中的 BilibiliLiveHelper.exe、BilibiliLiveHelper.dll、BilibiliLiveHelper.runtimeconfig.json 和 BilibiliLiveHelper.deps.json 放在同一个目录。

## 本地官方接口联调

1. 从 B 站直播开放平台取得项目 ID、Access Key ID 和 Access Key Secret。在 PowerShell 打开上面的发布目录。
2. 从该 PowerShell 会话启动签名服务。下面的输入写入当前进程的环境变量，密钥输入时会隐藏。

~~~powershell
$env:BILIBILI_LIVE_APP_ID = Read-Host '直播开放平台项目 ID'
$env:BILIBILI_LIVE_ACCESS_KEY_ID = [pscredential]::new('Bilibili', (Read-Host 'Access Key ID' -AsSecureString)).GetNetworkCredential().Password
$env:BILIBILI_LIVE_ACCESS_KEY_SECRET = [pscredential]::new('Bilibili', (Read-Host 'Access Key Secret' -AsSecureString)).GetNetworkCredential().Password
try {
    .\BilibiliLiveHelper.exe --signer
} finally {
    Remove-Item Env:BILIBILI_LIVE_APP_ID, Env:BILIBILI_LIVE_ACCESS_KEY_ID, Env:BILIBILI_LIVE_ACCESS_KEY_SECRET -ErrorAction SilentlyContinue
}
~~~

3. 保持签名服务运行，在此次 Minecraft 启动的 JVM 参数中加入 `-Ddangomusicgtnh.bilibiliSignerUrl=http://127.0.0.1:19754/`。在 DangoMusic 的「设置 → 界面与 HUD → B 站直播点歌台」中启用点歌台、填写主播身份码。在直播间发送「/点歌 歌曲名 歌手」，确认它出现在游戏的点歌队列中。身份码可以在 [B 站互动应用中心](https://play-live.bilibili.com/) 获取。
4. 在游戏中断开连接或退出游戏，待助手关闭直播开放平台场次后，在签名服务窗口按 Ctrl+C 退出。若助手被强制终止，B 站会在项目心跳超时后回收场次。

签名服务只监听 127.0.0.1:19754，只接收开启、心跳和关闭场次的请求。它负责对官方 API 请求签名，助手直接与 B 站弹幕服务器保持加密长连接。开发密钥只填入上面的签名服务进程环境。主播身份码通过游戏与助手间的管道传入。B 站对未上架项目的联调房间权限以 [统一鉴权和错误码文档](https://open-live.bilibili.com/document/74eec767-e594-7ddd-6aba-257e8317c05d) 为准。

## 游戏与助手之间的通信

助手从标准输入读取一行 UTF-8 JSON。

~~~json
{"mode":"live","identityCode":"主播身份码","endpoint":"http://127.0.0.1:19754/"}
~~~

模拟配置可简写为 {"mode":"mock"}。后续命令每行一个 JSON 对象。两种模式均接受 {"type":"stop"}，模拟模式还接受带 id、user 和 content 的 danmaku 对象。关闭标准输入也会结束会话。

标准输出每行一个 UTF-8 JSON 对象。

~~~json
{"type":"status","state":"connected","reason":"mock"}
{"type":"danmaku","id":"mock-1","user":"测试观众","content":"/点歌 稻香 周杰伦"}
~~~

## 验证

~~~powershell
dotnet run --project ./tools/bilibili-live-helper/tests/BilibiliLiveHelper.Checks.csproj
python -X utf8 ./tools/bilibili-live-helper/tests/check_process.py
~~~

第二条命令使用上述已发布的 Windows 程序。测试使用本地模拟签名服务和测试数据。真实联调使用[官方应用 API](https://open-live.bilibili.com/document/eba8e2e1-847d-e908-2e5c-7a1ec7d9266f)、[长链协议](https://open-live.bilibili.com/document/657d8e34-f926-a133-16c0-300c1afc6e6b)和[长链命令](https://open-live.bilibili.com/document/f9ce25be-312e-1f4a-85fd-fef21f1637f8)。
