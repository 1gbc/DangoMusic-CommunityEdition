using System.Net;
using System.Net.Http.Headers;
using System.Text.Json;

namespace DangoMusic.Bilibili;

internal static class LoopbackSigner
{
    public static async Task RunAsync(Func<string, string, CancellationToken, Task<JsonElement>> callPlatform,
        bool hosted, CancellationToken stop, string endpoint = SignerClient.DefaultEndpoint)
    {
        using var listener = new HttpListener();
        listener.Prefixes.Add(endpoint);
        listener.Start();
        Console.Error.WriteLine($"Bilibili signer: listening on {endpoint}");
        using var cancelListener = stop.Register(listener.Stop);

        // Keeping a reference to in-flight requests lets shutdown cancel requests before disposing the client.
        var pending = new HashSet<Task>();
        var sessions = hosted ? new HostedSessions(TimeProvider.System) : null;
        while (!stop.IsCancellationRequested)
        {
            try
            {
                var context = await listener.GetContextAsync();
                pending.RemoveWhere(task => task.IsCompleted);
                if (pending.Count >= (hosted ? 64 : 8))
                {
                    context.Response.StatusCode = 503;
                    context.Response.Close();
                    continue;
                }
                pending.Add(HandleAsync(context, callPlatform, sessions, stop));
            }
            catch (HttpListenerException) when (stop.IsCancellationRequested)
            {
                break;
            }
        }
        await Task.WhenAll(pending);
    }

    private static async Task HandleAsync(HttpListenerContext context,
        Func<string, string, CancellationToken, Task<JsonElement>> callPlatform, HostedSessions? sessions, CancellationToken stop)
    {
        var request = context.Request;
        var response = context.Response;
        OpenSession? opened = null;
        var startReturned = false;
        string? sessionToken = null;
        try
        {
            response.Headers["Cache-Control"] = "no-store";
            if (request.RemoteEndPoint is null || !IPAddress.IsLoopback(request.RemoteEndPoint.Address) ||
                request.Headers["Origin"] is not null || request.Headers["Sec-Fetch-Site"] is not null)
            {
                response.StatusCode = 403;
                return;
            }

            if (request.RawUrl == "/healthz" && request.HttpMethod == "GET")
            {
                response.StatusCode = 204;
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
                var inputField = operation == "start" ? "identityCode" : sessions is null ? "gameId" : "sessionToken";
                var value = input.RootElement.Text(inputField);
                if (!JsonFields.ValidText(value, 256))
                {
                    response.StatusCode = 400;
                    return;
                }

                var platformValue = value!;
                if (operation != "start" && sessions is not null)
                {
                    platformValue = sessions.Get(value!) ?? throw new LiveFailure("session_ended");
                }
                var data = await callPlatform(operation, platformValue, timeout.Token);
                if (operation != "start")
                {
                    if (sessions is not null)
                    {
                        if (operation == "end")
                            sessions.Remove(value!);
                        else
                            sessions.Refresh(value!);
                    }
                    await ReplyAsync(response, new { ok = true }, timeout.Token);
                    return;
                }

                var gameId = data.Field("game_info").Text("game_id");
                var ws = data.Field("websocket_info");
                opened = OpenSession.Read(gameId, ws.Text("auth_body"), ws.Field("wss_link"));
                if (opened is null)
                {
                    if (JsonFields.ValidText(gameId, 256))
                        await EndQuietlyAsync(callPlatform, gameId!);
                    throw new LiveFailure("platform_unavailable");
                }

                if (sessions is not null)
                    sessionToken = sessions.Add(opened.GameId) ?? throw new LiveFailure("signer_busy");

                await ReplyAsync(response, new
                {
                    ok = true,
                    sessionToken,
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
            {
                if (sessionToken is not null) sessions!.Remove(sessionToken);
                await EndQuietlyAsync(callPlatform, opened.GameId);
            }
        }
    }

    private static async Task EndQuietlyAsync(
        Func<string, string, CancellationToken, Task<JsonElement>> callPlatform, string gameId)
    {
        try
        {
            await callPlatform("end", gameId, CancellationToken.None);
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
    private string? sessionToken;
    private bool Hosted => endpoint.Scheme == Uri.UriSchemeHttps;

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
        if (Uri.TryCreate(text, UriKind.Absolute, out endpoint) &&
            (endpoint.Scheme == Uri.UriSchemeHttps || (endpoint.Scheme == Uri.UriSchemeHttp && endpoint.Host == "127.0.0.1")) &&
            endpoint.Host.Length > 0 && endpoint.Port > 0 && endpoint.AbsolutePath == "/" &&
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
        sessionToken = data.Text("sessionToken");
        if (Hosted && !JsonFields.ValidText(sessionToken, 256))
            throw new LiveFailure("signer_unavailable");

        var session = OpenSession.Read(gameId, data.Text("authBody"), data.Field("websocketLinks"));
        if (session is not null) return session;

        if (JsonFields.ValidText(gameId, 256))
            await EndAsync(gameId!);
        throw new LiveFailure("platform_unavailable");
    }

    public Task HeartbeatAsync(string gameId, CancellationToken stop) => CallSessionAsync("heartbeat", gameId, stop);

    public async Task EndAsync(string gameId)
    {
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(3));
        try
        {
            await CallSessionAsync("end", gameId, timeout.Token);
        }
        catch (Exception error) when (error is LiveFailure or OperationCanceledException)
        {
            // Normal shutdown must remain bounded even if the signer has already stopped.
        }
    }

    private Task<JsonElement> CallSessionAsync(string operation, string gameId, CancellationToken stop) =>
        Hosted ? CallAsync(operation, new { sessionToken }, stop) : CallAsync(operation, new { gameId }, stop);

    private async Task<JsonElement> CallAsync<T>(string operation, T payload, CancellationToken stop)
    {
        try
        {
            using var content = new ByteArrayContent(JsonSerializer.SerializeToUtf8Bytes(payload));
            content.Headers.ContentType = new MediaTypeHeaderValue("application/json");
            using var response = await client.PostAsync(new Uri(endpoint, operation), content, stop);
            if (response.StatusCode is HttpStatusCode.TooManyRequests or HttpStatusCode.ServiceUnavailable)
                throw new LiveFailure("signer_busy");
            response.EnsureSuccessStatusCode();
            using var json = JsonDocument.Parse(await response.Content.ReadAsByteArrayAsync(stop));
            if (json.RootElement.Field("ok").ValueKind == JsonValueKind.True)
                return json.RootElement.Clone();

            var reason = json.RootElement.Text("reason");
            throw new LiveFailure(reason switch
            {
                "identity_rejected" or "platform_rejected" or "platform_unavailable" or
                "session_ended" or "signer_not_configured" or "signer_busy" => reason,
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
