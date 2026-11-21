using System.Globalization;
using System.Net;
using System.Net.WebSockets;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace DangoMusic.Bilibili;

internal sealed record WebRoom(byte[] AuthBody, IReadOnlyList<Uri> Hosts);

internal sealed class WebRoomClient : IDisposable
{
    private const string Home = "https://www.bilibili.com";
    private const string Live = "https://live.bilibili.com";
    private const string UserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";
    private readonly CookieContainer cookies = new();
    private readonly HttpClient http;
    private string? deviceId;

    public WebRoomClient()
    {
        http = new HttpClient(new HttpClientHandler
        {
            CookieContainer = cookies,
            AllowAutoRedirect = false,
            AutomaticDecompression = DecompressionMethods.All
        })
        {
            Timeout = TimeSpan.FromSeconds(8),
            MaxResponseContentBufferSize = 512 * 1024
        };
        http.DefaultRequestHeaders.UserAgent.ParseAdd(UserAgent);
        http.DefaultRequestHeaders.Referrer = new Uri(Home + "/");
        http.DefaultRequestHeaders.Add("Origin", Home);
    }

    public static bool IsRoomId(string? value) =>
        value is { Length: > 0 and <= 19 } && value.All(ch => ch is >= '0' and <= '9') &&
        long.TryParse(value, NumberStyles.None, CultureInfo.InvariantCulture, out var roomId) && roomId > 0;

    public async Task<WebRoom> ResolveAsync(string roomId, CancellationToken stop)
    {
        var room = await GetDataAsync("https://api.live.bilibili.com/room/v1/Room/room_init?id=" + roomId,
            "room_not_found", stop);
        var number = room.Field("room_id");
        if (number.ValueKind != JsonValueKind.Number || !number.TryGetInt64(out var realRoomId) || realRoomId <= 0)
            throw new LiveFailure("room_not_found");

        if (deviceId is null)
        {
            var device = await GetDataAsync("https://api.bilibili.com/x/frontend/finger/spi", "platform_unavailable", stop);
            deviceId = SetDeviceCookie("buvid3", device.Text("b_3"));
            if (deviceId is null) throw new LiveFailure("platform_unavailable");
            SetDeviceCookie("buvid4", device.Text("b_4"));
        }

        var account = await GetDataAsync("https://api.bilibili.com/x/web-interface/nav", "platform_unavailable", stop,
            allowAnonymousNav: true);
        var images = account.Field("wbi_img");
        var mixin = MixinKey(images.Text("img_url"), images.Text("sub_url"));
        if (mixin is null) throw new LiveFailure("platform_unavailable");

        var query = FormattableString.Invariant($"id={realRoomId}&type=0&web_location=444.8&wts={DateTimeOffset.UtcNow.ToUnixTimeSeconds()}");
        var signature = Convert.ToHexString(MD5.HashData(Encoding.UTF8.GetBytes(query + mixin))).ToLowerInvariant();
        var danmaku = await GetDataAsync("https://api.live.bilibili.com/xlive/web-room/v1/index/getDanmuInfo?" +
            query + "&w_rid=" + signature, "platform_rejected", stop);
        var token = danmaku.Text("token");
        if (!JsonFields.ValidText(token, 4096)) throw new LiveFailure("platform_rejected");
        var hosts = ReadHosts(danmaku.Field("host_list"));
        if (hosts.Count == 0) throw new LiveFailure("platform_unavailable");

        var auth = JsonSerializer.SerializeToUtf8Bytes(new
        {
            uid = 0,
            roomid = realRoomId,
            protover = 2,
            buvid = deviceId,
            platform = "web",
            type = 2,
            key = token
        });
        return new WebRoom(auth, hosts);
    }

    public ClientWebSocket NewSocket()
    {
        var socket = new ClientWebSocket();
        socket.Options.Cookies = cookies;
        socket.Options.SetRequestHeader("Origin", Live);
        socket.Options.SetRequestHeader("User-Agent", UserAgent);
        return socket;
    }

    private async Task<JsonElement> GetDataAsync(string url, string rejected, CancellationToken stop,
        bool allowAnonymousNav = false)
    {
        try
        {
            using var response = await http.GetAsync(url, stop);
            if ((int)response.StatusCode is 403 or 412 or 429)
                throw new LiveFailure("platform_limited");
            response.EnsureSuccessStatusCode();
            using var json = JsonDocument.Parse(await response.Content.ReadAsByteArrayAsync(stop));
            var code = json.RootElement.Number("code");
            if (code == 0 || (allowAnonymousNav && code == -101))
                return json.RootElement.Field("data") is { ValueKind: JsonValueKind.Object } data
                    ? data.Clone() : throw new LiveFailure("platform_unavailable");
            throw new LiveFailure(code switch
            {
                -412 or -352 or -799 => "platform_limited",
                -101 => "anonymous_unavailable",
                null => "platform_unavailable",
                _ => rejected
            });
        }
        catch (Exception error) when (!stop.IsCancellationRequested &&
            error is HttpRequestException or JsonException or OperationCanceledException)
        {
            throw new LiveFailure("platform_unavailable");
        }
    }

    private string? SetDeviceCookie(string name, string? value)
    {
        if (!JsonFields.ValidText(value, 256)) return null;
        try
        {
            cookies.Add(new Cookie(name, value!, "/", ".bilibili.com") { Secure = true });
            return value;
        }
        catch (CookieException)
        {
            return null;
        }
    }

    internal static IReadOnlyList<Uri> ReadHosts(JsonElement value)
    {
        var hosts = new List<Uri>();
        if (value.ValueKind != JsonValueKind.Array) return hosts;
        foreach (var item in value.EnumerateArray())
        {
            var host = item.Text("host");
            var port = item.Number("wss_port");
            if (host is null || Uri.CheckHostName(host) != UriHostNameType.Dns ||
                !host.EndsWith(".bilibili.com", StringComparison.OrdinalIgnoreCase) ||
                port is not (>= 1 and <= 65535))
                continue;
            var uri = new UriBuilder("wss", host, port.Value, "/sub").Uri;
            if (!hosts.Contains(uri)) hosts.Add(uri);
            if (hosts.Count == 8) break;
        }
        return hosts;
    }

    internal static string? MixinKey(string? first, string? second)
    {
        // Bilibili web requests derive their WBI signing key from the two public image filenames.
        ReadOnlySpan<int> order =
            [46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35,
             27, 43, 5, 49, 33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13];
        if (!Uri.TryCreate(first, UriKind.Absolute, out var firstUri) ||
            !Uri.TryCreate(second, UriKind.Absolute, out var secondUri))
            return null;
        var left = Path.GetFileNameWithoutExtension(firstUri.AbsolutePath);
        var right = Path.GetFileNameWithoutExtension(secondUri.AbsolutePath);
        if (left.Length != 32 || right.Length != 32) return null;
        var raw = left + right;
        Span<char> key = stackalloc char[32];
        for (var index = 0; index < key.Length; index++)
            key[index] = raw[order[index]];
        return new string(key);
    }

    public void Dispose() => http.Dispose();
}
