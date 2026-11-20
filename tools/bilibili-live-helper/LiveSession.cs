using System.Diagnostics;
using System.Net.WebSockets;

namespace DangoMusic.Bilibili;

internal sealed class LiveSession(WebRoomClient roomClient, HelperOutput output)
{
    private static readonly TimeSpan HeartbeatInterval = TimeSpan.FromSeconds(25);

    public async Task RunAsync(string roomId, CancellationToken stop)
    {
        var failures = 0;
        var nextHost = 0;
        WebRoom? room = null;
        while (true)
        {
            stop.ThrowIfCancellationRequested();
            var connectedAt = 0L;
            try
            {
                room ??= await roomClient.ResolveAsync(roomId, stop);
                var link = room.Hosts[nextHost++];
                var authPacket = LivePackets.Encode(7, room.AuthBody);
                if (nextHost == room.Hosts.Count)
                {
                    room = null;
                    nextHost = 0;
                }

                using var socket = roomClient.NewSocket();
                IReadOnlyList<LiveEvent> pending;
                using (var handshake = CancellationTokenSource.CreateLinkedTokenSource(stop))
                {
                    handshake.CancelAfter(TimeSpan.FromSeconds(8));
                    await socket.ConnectAsync(link, handshake.Token);
                    await SendAsync(socket, authPacket, handshake.Token);
                    pending = await ReadAuthorizationAsync(socket, handshake.Token);
                }

                connectedAt = Stopwatch.GetTimestamp();
                output.Status("connected", "live");
                using var connection = CancellationTokenSource.CreateLinkedTokenSource(stop);
                var keepalive = new SocketHeartbeat(socket);
                Dispatch(pending, keepalive);
                await RunTogetherAsync(
                    ReceiveAsync(socket, keepalive, connection.Token),
                    keepalive.RunAsync(connection.Token),
                    connection);
            }
            catch (Exception error) when (!stop.IsCancellationRequested &&
                (error is WebSocketException or OperationCanceledException or SocketLost or SocketRejected ||
                 error is LiveFailure { Reason: "platform_unavailable" }))
            {
                if (connectedAt != 0 && Stopwatch.GetElapsedTime(connectedAt) > TimeSpan.FromMinutes(1))
                    failures = 0;
                if (error is SocketRejected)
                {
                    if (failures >= 2) throw new LiveFailure("anonymous_unavailable");
                    room = null;
                    nextHost = 0;
                }
                if (++failures >= 8)
                    throw new LiveFailure(error is LiveFailure ? "platform_unavailable" : "websocket_unavailable");
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
                        throw new SocketRejected();
                    case LiveEventKind.Authorized:
                        return events.Skip(index + 1).ToArray();
                }
            }
        }
    }

    private async Task ReceiveAsync(ClientWebSocket socket, SocketHeartbeat keepalive,
        CancellationToken stop)
    {
        while (true)
        {
            stop.ThrowIfCancellationRequested();
            var events = await ReadMessageAsync(socket, stop);
            Dispatch(events, keepalive);
        }
    }

    private void Dispatch(IReadOnlyList<LiveEvent> events, SocketHeartbeat keepalive)
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
    private sealed class SocketRejected : Exception;

    private sealed class SocketHeartbeat(ClientWebSocket socket)
    {
        private long lastReply = Stopwatch.GetTimestamp();

        public void ReceivedReply() => Volatile.Write(ref lastReply, Stopwatch.GetTimestamp());

        public async Task RunAsync(CancellationToken stop)
        {
            var heartbeat = LivePackets.Encode(2, "{}"u8);
            do
            {
                if (Stopwatch.GetElapsedTime(Volatile.Read(ref lastReply)) > TimeSpan.FromSeconds(50))
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
