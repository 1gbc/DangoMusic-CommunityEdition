using System.Diagnostics;
using System.Net.WebSockets;
using System.Text;

namespace DangoMusic.Bilibili;

internal sealed class LiveSession(SignerClient signer, HelperOutput output)
{
    private static readonly TimeSpan HeartbeatInterval = TimeSpan.FromSeconds(20);

    public async Task RunAsync(string identityCode, CancellationToken stop)
    {
        stop.ThrowIfCancellationRequested();
        // Let the bounded start request settle during shutdown, so a created game ID can be ended.
        var session = await signer.StartAsync(identityCode);
        using var active = CancellationTokenSource.CreateLinkedTokenSource(stop);
        try
        {
            await RunTogetherAsync(
                KeepProjectAliveAsync(session.GameId, active.Token),
                ListenWithReconnectAsync(session, active.Token),
                active);
        }
        finally
        {
            await signer.EndAsync(session.GameId);
        }
    }

    private async Task KeepProjectAliveAsync(string gameId, CancellationToken stop)
    {
        using var intervals = new PeriodicTimer(HeartbeatInterval);
        while (await intervals.WaitForNextTickAsync(stop))
        {
            try
            {
                await signer.HeartbeatAsync(gameId, stop);
            }
            catch (LiveFailure failure) when (failure.Reason is "signer_unavailable" or "signer_busy" or "platform_unavailable")
            {
                await Task.Delay(TimeSpan.FromSeconds(2), stop);
                await signer.HeartbeatAsync(gameId, stop);
            }
        }
    }

    private async Task ListenWithReconnectAsync(OpenSession session, CancellationToken stop)
    {
        var failures = 0;
        var nextLink = 0;
        while (true)
        {
            stop.ThrowIfCancellationRequested();
            var link = session.WebSocketLinks[nextLink++ % session.WebSocketLinks.Length];
            using var socket = new ClientWebSocket();
            try
            {
                IReadOnlyList<LiveEvent> pendingEvents;
                using (var handshake = CancellationTokenSource.CreateLinkedTokenSource(stop))
                {
                    handshake.CancelAfter(TimeSpan.FromSeconds(8));
                    await socket.ConnectAsync(link, handshake.Token);
                    var authPacket = LivePackets.Encode(7, Encoding.UTF8.GetBytes(session.AuthBody));
                    await SendAsync(socket, authPacket, handshake.Token);
                    pendingEvents = await ReadAuthorizationAsync(socket, handshake.Token);
                }

                failures = 0;
                output.Status("connected", "live");
                using var connection = CancellationTokenSource.CreateLinkedTokenSource(stop);
                var keepalive = new SocketHeartbeat(socket);
                Dispatch(pendingEvents, session.GameId, keepalive);
                await RunTogetherAsync(
                    ReceiveAsync(socket, session.GameId, keepalive, connection.Token),
                    keepalive.RunAsync(connection.Token),
                    connection);
            }
            catch (Exception error) when (!stop.IsCancellationRequested &&
                error is WebSocketException or OperationCanceledException or SocketLost)
            {
                failures++;
                if (failures >= Math.Max(6, session.WebSocketLinks.Length))
                    throw new LiveFailure("websocket_unavailable");
                output.Status("reconnecting", "connection_lost");
                await Task.Delay(TimeSpan.FromSeconds(Math.Min(12, 1 << failures)), stop);
            }
        }
    }

    private static async Task<IReadOnlyList<LiveEvent>> ReadAuthorizationAsync(ClientWebSocket socket,
        CancellationToken stop)
    {
        while (true)
        {
            var events = await ReadMessageAsync(socket, stop);
            for (var index = 0; index < events.Count; index++)
            {
                switch (events[index].Kind)
                {
                    case LiveEventKind.AuthorizationRejected:
                        throw new SocketLost();
                    case LiveEventKind.Authorized:
                        return events.Skip(index + 1).ToArray();
                }
            }
        }
    }

    private async Task ReceiveAsync(ClientWebSocket socket, string gameId, SocketHeartbeat keepalive,
        CancellationToken stop)
    {
        while (true)
        {
            stop.ThrowIfCancellationRequested();
            var events = await ReadMessageAsync(socket, stop);
            Dispatch(events, gameId, keepalive);
        }
    }

    private void Dispatch(IReadOnlyList<LiveEvent> events, string gameId, SocketHeartbeat keepalive)
    {
        foreach (var item in events)
        {
            switch (item.Kind)
            {
                case LiveEventKind.Danmaku:
                    output.Message(item.Message!);
                    break;
                case LiveEventKind.Heartbeat:
                    keepalive.ReceivedReply();
                    break;
                case LiveEventKind.SessionEnded when item.GameId == gameId:
                    throw new LiveFailure("session_ended");
            }
        }
    }

    private static async Task<IReadOnlyList<LiveEvent>> ReadMessageAsync(ClientWebSocket socket, CancellationToken stop)
    {
        var buffer = new byte[8192];
        using var message = new MemoryStream();
        ValueWebSocketReceiveResult part;
        do
        {
            part = await socket.ReceiveAsync(buffer.AsMemory(), stop);
            if (part.MessageType == WebSocketMessageType.Close)
                throw new SocketLost();
            if (message.Length + part.Count > LivePackets.MaximumSize)
                throw new SocketLost();
            message.Write(buffer, 0, part.Count);
        }
        while (!part.EndOfMessage);

        return part.MessageType == WebSocketMessageType.Binary
            ? LivePackets.Decode(message.GetBuffer().AsMemory(0, (int)message.Length))
            : Array.Empty<LiveEvent>();
    }

    private static ValueTask SendAsync(ClientWebSocket socket, byte[] packet, CancellationToken stop) =>
        socket.SendAsync(packet.AsMemory(), WebSocketMessageType.Binary, true, stop);

    private static async Task RunTogetherAsync(Task first, Task second, CancellationTokenSource lifetime)
    {
        try
        {
            await await Task.WhenAny(first, second);
        }
        finally
        {
            lifetime.Cancel();
            try
            {
                await Task.WhenAll(first, second);
            }
            catch (Exception)
            {
                // Both tasks are observed. The first completed task supplies the outcome.
            }
        }
    }

    private sealed class SocketLost : Exception;

    private sealed class SocketHeartbeat(ClientWebSocket socket)
    {
        private long lastReply = Stopwatch.GetTimestamp();

        public void ReceivedReply() => Volatile.Write(ref lastReply, Stopwatch.GetTimestamp());

        public async Task RunAsync(CancellationToken stop)
        {
            var heartbeat = LivePackets.Encode(2, []);
            do
            {
                if (Stopwatch.GetElapsedTime(Volatile.Read(ref lastReply)) > TimeSpan.FromSeconds(35))
                    throw new SocketLost();
                using (var timeout = CancellationTokenSource.CreateLinkedTokenSource(stop))
                {
                    timeout.CancelAfter(TimeSpan.FromSeconds(5));
                    await SendAsync(socket, heartbeat, timeout.Token);
                }
                await Task.Delay(HeartbeatInterval, stop);
            }
            while (!stop.IsCancellationRequested);
        }
    }
}
