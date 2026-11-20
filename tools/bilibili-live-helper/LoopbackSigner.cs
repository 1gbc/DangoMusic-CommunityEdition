using System.Net;
using System.Net.Http.Headers;
using System.Text.Json;

namespace DangoMusic.Bilibili;

internal static class LoopbackSigner
{
    public static async Task RunAsync(SignedPlatform platform, CancellationToken stop)
    {
        using var listener = new HttpListener();
        listener.Prefixes.Add(SignerClient.DefaultEndpoint);
        listener.Start();
        Console.Error.WriteLine($"Bilibili signer: listening on {SignerClient.DefaultEndpoint}");
        using var cancelListener = stop.Register(listener.Stop);

        // Keeping a reference to in-flight requests lets shutdown cancel requests before disposing the client.
        var pending = new HashSet<Task>();
        while (!stop.IsCancellationRequested)
        {
            try
            {
                var context = await listener.GetContextAsync();
                pending.RemoveWhere(task => task.IsCompleted);
                if (pending.Count >= 8)
                {
                    context.Response.StatusCode = 503;
                    context.Response.Close();
                    continue;
                }
                pending.Add(HandleAsync(context, platform, stop));
            }
            catch (HttpListenerException) when (stop.IsCancellationRequested)
            {
                break;
            }
        }
        await Task.WhenAll(pending);
    }

    private static async Task HandleAsync(HttpListenerContext context, SignedPlatform platform, CancellationToken stop)
    {
        var request = context.Request;
        var response = context.Response;
        OpenSession? opened = null;
        var startReturned = false;
        try
        {
            response.Headers["Cache-Control"] = "no-store";
            if (request.RemoteEndPoint is null || !IPAddress.IsLoopback(request.RemoteEndPoint.Address) ||
                request.Headers["Origin"] is not null || request.Headers["Sec-Fetch-Site"] is not null)
            {
                response.StatusCode = 403;
                return;
            }

            var operation = request.RawUrl switch
            {
                "/start" => "start",
                "/heartbeat" => "heartbeat",
                "/end" => "end",
                _ => null
            };
            if (operation is null || request.HttpMethod != "POST")
            {
                response.StatusCode = 404;
                return;
            }
            if (request.ContentType?.Split(';', 2)[0].Trim() != "application/json" ||
                request.ContentLength64 is <= 0 or > 4096)
            {
                response.StatusCode = 400;
                return;
            }

            using var timeout = CancellationTokenSource.CreateLinkedTokenSource(stop);
            timeout.CancelAfter(TimeSpan.FromSeconds(9));
            JsonDocument input;
            try
            {
                input = await JsonDocument.ParseAsync(request.InputStream, cancellationToken: timeout.Token);
            }
            catch (JsonException)
            {
                response.StatusCode = 400;
                return;
            }
            using (input)
            {
                var value = input.RootElement.Text(operation == "start" ? "identityCode" : "gameId");
                if (!JsonFields.ValidText(value, 256))
                {
                    response.StatusCode = 400;
                    return;
                }

                var data = await platform.CallAsync(operation, value!, timeout.Token);
                if (operation != "start")
                {
                    await ReplyAsync(response, new { ok = true }, timeout.Token);
                    return;
                }

                var gameId = data.Field("game_info").Text("game_id");
                var ws = data.Field("websocket_info");
                opened = OpenSession.Read(gameId, ws.Text("auth_body"), ws.Field("wss_link"));
                if (opened is null)
                {
                    if (JsonFields.ValidText(gameId, 256))
                        await EndQuietlyAsync(platform, gameId!);
                    throw new LiveFailure("platform_unavailable");
                }

                await ReplyAsync(response, new
                {
                    ok = true,
                    gameId = opened.GameId,
                    authBody = opened.AuthBody,
                    websocketLinks = opened.WebSocketLinks.Select(link => link.AbsoluteUri).ToArray()
                }, timeout.Token);
                startReturned = true;
            }
        }
        catch (LiveFailure failure)
        {
            try
            {
                await ReplyAsync(response, new { ok = false, reason = failure.Reason }, stop);
            }
            catch (Exception error) when (error is IOException or HttpListenerException or OperationCanceledException)
            {
                // The local client may have left before the platform replied.
            }
        }
        catch (Exception error) when (error is IOException or HttpListenerException or OperationCanceledException)
        {
            // Disconnects and cancellation carry no diagnostic data.
        }
        finally
        {
            response.Close();
            if (opened is not null && !startReturned)
                await EndQuietlyAsync(platform, opened.GameId);
        }
    }

    private static async Task EndQuietlyAsync(SignedPlatform platform, string gameId)
    {
        try
        {
            await platform.CallAsync("end", gameId, CancellationToken.None);
        }
        catch (LiveFailure)
        {
            // The platform automatically expires abandoned sessions.
        }
    }

    private static async Task ReplyAsync<T>(HttpListenerResponse response, T payload, CancellationToken stop)
    {
        var body = JsonSerializer.SerializeToUtf8Bytes(payload);
        response.ContentType = "application/json";
        response.ContentLength64 = body.Length;
        await response.OutputStream.WriteAsync(body, stop);
    }
}

internal sealed class SignerClient(Uri endpoint) : IDisposable
{
    public const string DefaultEndpoint = "http://127.0.0.1:19754/";

    private readonly HttpClient client = new(new HttpClientHandler
    {
        UseProxy = false,
        UseCookies = false,
        AllowAutoRedirect = false
    })
    {
        MaxResponseContentBufferSize = 64 * 1024,
        Timeout = TimeSpan.FromSeconds(10)
    };

    public static bool TryEndpoint(string text, out Uri? endpoint)
    {
        if (Uri.TryCreate(text, UriKind.Absolute, out endpoint) && endpoint.Scheme == "http" &&
            endpoint.Host == "127.0.0.1" && endpoint.Port > 0 && endpoint.AbsolutePath == "/" &&
            endpoint.UserInfo.Length == 0 && endpoint.Query.Length == 0 && endpoint.Fragment.Length == 0)
        {
            return true;
        }
        endpoint = null;
        return false;
    }

    public async Task<OpenSession> StartAsync(string identityCode)
    {
        var data = await CallAsync("start", new { identityCode }, CancellationToken.None);
        var gameId = data.Text("gameId");
        var session = OpenSession.Read(gameId, data.Text("authBody"), data.Field("websocketLinks"));
        if (session is not null) return session;

        if (JsonFields.ValidText(gameId, 256))
            await EndAsync(gameId!);
        throw new LiveFailure("platform_unavailable");
    }

    public Task HeartbeatAsync(string gameId, CancellationToken stop) => CallAsync("heartbeat", new { gameId }, stop);

    public async Task EndAsync(string gameId)
    {
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(3));
        try
        {
            await CallAsync("end", new { gameId }, timeout.Token);
        }
        catch (Exception error) when (error is LiveFailure or OperationCanceledException)
        {
            // Normal shutdown must remain bounded even if the signer has already stopped.
        }
    }

    private async Task<JsonElement> CallAsync<T>(string operation, T payload, CancellationToken stop)
    {
        try
        {
            using var content = new ByteArrayContent(JsonSerializer.SerializeToUtf8Bytes(payload));
            content.Headers.ContentType = new MediaTypeHeaderValue("application/json");
            using var response = await client.PostAsync(new Uri(endpoint, operation), content, stop);
            response.EnsureSuccessStatusCode();
            using var json = JsonDocument.Parse(await response.Content.ReadAsByteArrayAsync(stop));
            if (json.RootElement.Field("ok").ValueKind == JsonValueKind.True)
                return json.RootElement.Clone();

            var reason = json.RootElement.Text("reason");
            throw new LiveFailure(reason switch
            {
                "identity_rejected" or "platform_rejected" or "platform_unavailable" or
                "session_ended" or "signer_not_configured" => reason,
                _ => "signer_unavailable"
            });
        }
        catch (Exception error) when (error is HttpRequestException or JsonException or TaskCanceledException &&
            !stop.IsCancellationRequested)
        {
            throw new LiveFailure("signer_unavailable");
        }
    }

    public void Dispose() => client.Dispose();
}
