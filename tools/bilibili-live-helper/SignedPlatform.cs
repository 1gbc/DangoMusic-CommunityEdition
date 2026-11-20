using System.Globalization;
using System.Net.Http.Headers;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace DangoMusic.Bilibili;

internal sealed record SigningCredentials(long AppId, string KeyId, string Secret)
{
    public static SigningCredentials? FromEnvironment()
    {
        var keyId = Environment.GetEnvironmentVariable("BILIBILI_LIVE_ACCESS_KEY_ID")?.Trim();
        var secret = Environment.GetEnvironmentVariable("BILIBILI_LIVE_ACCESS_KEY_SECRET")?.Trim();
        var appId = Environment.GetEnvironmentVariable("BILIBILI_LIVE_APP_ID")?.Trim();

        if (!long.TryParse(appId, NumberStyles.None, CultureInfo.InvariantCulture, out var id) || id <= 0 ||
            !JsonFields.ValidText(keyId, 256) || !JsonFields.ValidText(secret, 256))
        {
            return null;
        }
        return new SigningCredentials(id, keyId!, secret!);
    }
}

internal sealed class SignedPlatform(SigningCredentials credentials) : IDisposable
{
    private readonly HttpClient client = new(new HttpClientHandler
    {
        AllowAutoRedirect = false,
        UseCookies = false
    })
    {
        MaxResponseContentBufferSize = 64 * 1024,
        Timeout = TimeSpan.FromSeconds(7)
    };

    public async Task<JsonElement> CallAsync(string operation, string value, CancellationToken stop)
    {
        using var request = BuildRequest(credentials, operation, value, DateTimeOffset.UtcNow.ToUnixTimeSeconds(),
            Guid.NewGuid().ToString());
        try
        {
            using var response = await client.SendAsync(request, stop);
            response.EnsureSuccessStatusCode();
            using var json = JsonDocument.Parse(await response.Content.ReadAsByteArrayAsync(stop));
            var code = json.RootElement.Number("code");
            if (code != 0)
                throw new LiveFailure(code is { } error ? ClassifyError(error) : "platform_unavailable");

            var data = json.RootElement.Field("data");
            return data.ValueKind == JsonValueKind.Undefined ? default : data.Clone();
        }
        catch (Exception error) when (error is HttpRequestException or JsonException or TaskCanceledException &&
            !stop.IsCancellationRequested)
        {
            throw new LiveFailure("platform_unavailable");
        }
    }

    internal static HttpRequestMessage BuildRequest(SigningCredentials keys, string operation, string value,
        long timestamp, string nonce)
    {
        var body = operation switch
        {
            "start" => JsonSerializer.SerializeToUtf8Bytes(new { code = value, app_id = keys.AppId }),
            "heartbeat" => JsonSerializer.SerializeToUtf8Bytes(new { game_id = value }),
            "end" => JsonSerializer.SerializeToUtf8Bytes(new { app_id = keys.AppId, game_id = value }),
            _ => throw new ArgumentException(nameof(operation))
        };

        var signedHeaders = new SortedDictionary<string, string>(StringComparer.Ordinal)
        {
            ["x-bili-accesskeyid"] = keys.KeyId,
            ["x-bili-content-md5"] = Convert.ToHexString(MD5.HashData(body)).ToLowerInvariant(),
            ["x-bili-signature-method"] = "HMAC-SHA256",
            ["x-bili-signature-nonce"] = nonce,
            ["x-bili-signature-version"] = "1.0",
            ["x-bili-timestamp"] = timestamp.ToString(CultureInfo.InvariantCulture)
        };
        var canonical = string.Join('\n', signedHeaders.Select(header => $"{header.Key}:{header.Value}"));
        var signature = Convert.ToHexString(
            HMACSHA256.HashData(Encoding.UTF8.GetBytes(keys.Secret), Encoding.UTF8.GetBytes(canonical))
        ).ToLowerInvariant();

        var request = new HttpRequestMessage(HttpMethod.Post, $"https://live-open.biliapi.com/v2/app/{operation}")
        {
            Content = new ByteArrayContent(body)
        };
        request.Content.Headers.ContentType = new MediaTypeHeaderValue("application/json");
        request.Headers.Accept.Add(new MediaTypeWithQualityHeaderValue("application/json"));
        foreach (var header in signedHeaders)
            request.Headers.Add(header.Key, header.Value);
        request.Headers.TryAddWithoutValidation("Authorization", signature);

        return request;
    }

    internal static string ClassifyError(int errorCode) => errorCode switch
    {
        7007 => "identity_rejected",
        7000 or 7003 => "session_ended",
        5000 or 5001 or 5002 => "platform_unavailable",
        _ => "platform_rejected"
    };

    public void Dispose() => client.Dispose();
}

internal sealed record OpenSession(string GameId, string AuthBody, Uri[] WebSocketLinks)
{
    public static OpenSession? Read(string? gameId, string? authBody, JsonElement urls)
    {
        if (!JsonFields.ValidText(gameId, 256) || string.IsNullOrWhiteSpace(authBody) ||
            authBody.Length > 16 * 1024 || urls.ValueKind != JsonValueKind.Array)
        {
            return null;
        }

        var links = new List<Uri>();
        foreach (var item in urls.EnumerateArray())
        {
            if (item.ValueKind == JsonValueKind.String && item.GetString() is { Length: <= 2048 } link &&
                Uri.TryCreate(link, UriKind.Absolute, out var uri) && uri.Scheme == "wss" &&
                uri.UserInfo.Length == 0 && uri.Fragment.Length == 0 && uri.Host.Length > 0 &&
                !links.Contains(uri))
            {
                links.Add(uri);
                if (links.Count == 8) break;
            }
        }

        return links.Count > 0 ? new OpenSession(gameId!, authBody, links.ToArray()) : null;
    }
}
