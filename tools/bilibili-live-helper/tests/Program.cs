using System.IO.Compression;
using DangoMusic.Bilibili;

static void Check(bool condition, string description)
{
    if (!condition) throw new InvalidOperationException(description);
}

var first = LivePackets.Encode(5, """{"cmd":"DANMU_MSG:4:0:2:2:2:0","info":[[0,1,25,0,1710000000001,11],"点歌 月亮",[314,"听众"]]}"""u8, 0);
var badJson = LivePackets.Encode(5, "{"u8, 0);
var second = LivePackets.Encode(5, """{"cmd":"DANMU_MSG","info":[[0,1,25,0,1710000000002,12],"点歌 月亮",[314,"听众"]]}"""u8, 0);
var body = first.Concat(badJson).Concat(second).ToArray();

byte[] Compress(byte[] original, bool brotli)
{
    using var buffer = new MemoryStream();
    using (Stream compressor = brotli
        ? new BrotliStream(buffer, CompressionMode.Compress, true)
        : new ZLibStream(buffer, CompressionMode.Compress, true))
    {
        compressor.Write(original);
    }
    return buffer.ToArray();
}

foreach (var version in new short[] { 2, 3 })
{
    var envelope = LivePackets.Encode(5, Compress(body, version == 3), version);
    var events = LivePackets.Decode(envelope);
    Check(events.Count == 2, "Compressed packet contains two valid danmaku");
    Check(events[0].Message is { User: "听众", Content: "点歌 月亮" }, "Web danmaku fields");
    Check(events[0].Message!.Id != events[1].Message!.Id, "Repeated text from the same user is a new event");
}

var ack = LivePackets.Encode(8, """{"code":0}"""u8);
Check(LivePackets.Decode(ack.Concat(first).ToArray()).Count == 2, "Concatenated AUTH and danmaku");
Check(LivePackets.Decode(first.AsMemory(0, first.Length - 1)).Count == 0, "Truncated frame is ignored");
Check(LivePackets.Decode(LivePackets.Encode(5, "malformed"u8, 2).Concat(first).ToArray()).Count == 1,
    "Malformed compressed data is skipped");
Check(LivePackets.Decode(first).Single().Message!.Id == LivePackets.Decode(first).Single().Message!.Id,
    "The same web packet has a repeatable message ID");
Check(LivePackets.Decode(LivePackets.Encode(5, """{"cmd":"DANMU_MSG","info":[[],"bad",[]]}"""u8, 0)).Count == 0,
    "Malformed web danmaku is discarded");
Check(LivePackets.Decode(LivePackets.Encode(5, """{"cmd":"SEND_GIFT","info":[[],"bad",[]]}"""u8, 0)).Count == 0,
    "Other live events cannot become requests");

var recent = new RecentDanmaku();
Check(recent.Accept("same") && !recent.Accept("same") && recent.Accept("different"), "Bounded deduplication");

var mixin = WebRoomClient.MixinKey("https://fixture.invalid/0123456789abcdefghijklmnopqrstuv.png",
    "https://fixture.invalid/wxyzABCDEFGHIJKLMNOPQRSTUVWXYZ!@.png");
Check(mixin == "KLi2R8nwfOavW3JzrH5Nx9GjtseDcCFd", "WBI filename permutation");
Check(WebRoomClient.IsRoomId("123456789") && !WebRoomClient.IsRoomId("0") &&
    !WebRoomClient.IsRoomId("-8") && !WebRoomClient.IsRoomId("99999999999999999999"),
    "Room IDs are positive decimal numbers");
Console.WriteLine("Web danmaku, compression, replay and room input checks passed.");
