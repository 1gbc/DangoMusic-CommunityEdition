using System.Buffers.Binary;
using System.IO.Compression;
using System.Text.Json;

namespace DangoMusic.Bilibili;

internal enum LiveEventKind { Authorized, AuthorizationRejected, Heartbeat, Danmaku, SessionEnded }

internal sealed record LiveEvent(LiveEventKind Kind, Danmaku? Message = null, string? GameId = null);

internal static class LivePackets
{
    private const int HeaderSize = 16;
    private const int MaximumDepth = 4;
    internal const int MaximumSize = 1024 * 1024;

    public static byte[] Encode(int operation, ReadOnlySpan<byte> body, short version = 1)
    {
        var packet = new byte[HeaderSize + body.Length];
        BinaryPrimitives.WriteInt32BigEndian(packet, packet.Length);
        BinaryPrimitives.WriteInt16BigEndian(packet.AsSpan(4), HeaderSize);
        BinaryPrimitives.WriteInt16BigEndian(packet.AsSpan(6), version);
        BinaryPrimitives.WriteInt32BigEndian(packet.AsSpan(8), operation);
        BinaryPrimitives.WriteInt32BigEndian(packet.AsSpan(12), 1);
        body.CopyTo(packet.AsSpan(HeaderSize));
        return packet;
    }

    public static IReadOnlyList<LiveEvent> Decode(ReadOnlyMemory<byte> message)
    {
        var events = new List<LiveEvent>();
        if (message.Length <= MaximumSize)
        {
            var expansionBudget = MaximumSize;
            Decode(message, events, 0, ref expansionBudget);
        }
        return events;
    }

    private static void Decode(ReadOnlyMemory<byte> packets, List<LiveEvent> events, int depth, ref int budget)
    {
        while (packets.Length >= HeaderSize)
        {
            var header = packets.Span[..HeaderSize];
            var length = BinaryPrimitives.ReadInt32BigEndian(header);
            var headerSize = BinaryPrimitives.ReadUInt16BigEndian(header[4..]);
            var version = BinaryPrimitives.ReadInt16BigEndian(header[6..]);
            var operation = BinaryPrimitives.ReadInt32BigEndian(header[8..]);
            if (headerSize < HeaderSize || length < headerSize || length > packets.Length)
                break;

            var body = packets[headerSize..length];
            packets = packets[length..];
            try
            {
                if (version is 2 or 3 && depth < MaximumDepth)
                {
                    using var stored = new MemoryStream(body.ToArray(), false);
                    using Stream decompressor = version == 2
                        ? new ZLibStream(stored, CompressionMode.Decompress)
                        : new BrotliStream(stored, CompressionMode.Decompress);
                    var expanded = Expand(decompressor, ref budget);
                    if (expanded is not null)
                        Decode(expanded, events, depth + 1, ref budget);
                }
                else if (version is 0 or 1 && DecodeEvent(operation, body) is { } item)
                {
                    events.Add(item);
                }
            }
            catch (Exception error) when (error is InvalidDataException or JsonException or InvalidOperationException)
            {
                // The next independently framed packet can still be read.
            }
        }
    }

    private static byte[]? Expand(Stream source, ref int budget)
    {
        if (budget == 0) return null;
        var buffer = new byte[8192];
        using var result = new MemoryStream();
        int count;
        while ((count = source.Read(buffer, 0, Math.Min(buffer.Length, budget + 1))) > 0)
        {
            if (count > budget)
            {
                budget = 0;
                return null;
            }
            budget -= count;
            result.Write(buffer, 0, count);
        }
        return result.ToArray();
    }

    private static LiveEvent? DecodeEvent(int operation, ReadOnlyMemory<byte> body)
    {
        if (operation == 3) return new LiveEvent(LiveEventKind.Heartbeat);
        if (operation is not (5 or 8)) return null;

        using var json = JsonDocument.Parse(body);
        var root = json.RootElement;
        if (operation == 8)
        {
            return root.Number("code") switch
            {
                0 => new LiveEvent(LiveEventKind.Authorized),
                not null => new LiveEvent(LiveEventKind.AuthorizationRejected),
                null => null
            };
        }

        var data = root.Field("data");
        return root.Text("cmd") switch
        {
            "LIVE_OPEN_PLATFORM_DM" when Danmaku.Read(data, "msg_id", "uname", "msg") is { } message
                => new LiveEvent(LiveEventKind.Danmaku, message),
            "LIVE_OPEN_PLATFORM_INTERACTION_END" when JsonFields.ValidText(data.Text("game_id"), 256)
                => new LiveEvent(LiveEventKind.SessionEnded, GameId: data.Text("game_id")),
            _ => null
        };
    }
}
