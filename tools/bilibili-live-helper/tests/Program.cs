using System.IO.Compression;
using System.Text;
using DangoMusic.Bilibili;

static void Check(bool condition, string description)
{
    if (!condition) throw new InvalidOperationException(description);
}

var first = LivePackets.Encode(5, """{"cmd":"LIVE_OPEN_PLATFORM_DM","data":{"msg_id":"dm-1","uname":"听众","msg":"点歌 月亮"}}"""u8, 0);
var badJson = LivePackets.Encode(5, "{"u8, 0);
var second = LivePackets.Encode(5, """{"cmd":"LIVE_OPEN_PLATFORM_DM","data":{"msg_id":"dm-2","uname":"听众","msg":"点歌 星星"}}"""u8, 0);
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
    Check(events[0].Message is { Id: "dm-1", User: "听众", Content: "点歌 月亮" }, "Danmaku fields");
    Check(events[1].Message is { Id: "dm-2" }, "A bad payload does not discard the following packet");
}

var ack = LivePackets.Encode(8, """{"code":0}"""u8);
Check(LivePackets.Decode(ack.Concat(first).ToArray()).Count == 2, "Concatenated AUTH and danmaku");
Check(LivePackets.Decode(first.AsMemory(0, first.Length - 1)).Count == 0, "Truncated frame is ignored");
Check(LivePackets.Decode(LivePackets.Encode(5, "malformed"u8, 2).Concat(first).ToArray()).Count == 1,
    "Malformed compressed data is skipped");
Check(LivePackets.Decode(LivePackets.Encode(5, """{"cmd":"LIVE_OPEN_PLATFORM_INTERACTION_END","data":{"game_id":"session"}}"""u8, 0))
    .Single().Kind == LiveEventKind.SessionEnded, "End notification");
Check(LivePackets.Decode(LivePackets.Encode(5, """{"cmd":"LIVE_OPEN_PLATFORM_DM","data":{"uname":"guest","msg":"missing id"}}"""u8, 0))
    .Count == 0, "Danmaku without a stable ID is ignored");

var recent = new RecentDanmaku();
Check(recent.Accept("same") && !recent.Accept("same") && recent.Accept("different"), "Bounded deduplication");
var credentials = new SigningCredentials(1234567890123, "mock-key", "mock-secret");
var request = SignedPlatform.BuildRequest(credentials, "start", "fake-code", 1624594467, "test-nonce");
Check(request.RequestUri?.AbsoluteUri == "https://live-open.biliapi.com/v2/app/start", "Fixed official endpoint");
Check(await request.Content!.ReadAsStringAsync() == """{"code":"fake-code","app_id":1234567890123}""", "Full 64-bit app ID");
Check(request.Headers.GetValues("Authorization").Single() == "d54fdd82628ed920ea275e3369b58895860bad239a6e006b038aac143fb1c4ed", "HMAC-SHA256 signature");
Check(SignedPlatform.ClassifyError(7007) == "identity_rejected", "Identity code error mapping");
Check(SignedPlatform.ClassifyError(7003) == "session_ended", "Expired session error mapping");
Check(!SignerClient.TryEndpoint("https://example.invalid/", out _) &&
    !SignerClient.TryEndpoint("http://127.0.0.1:19754/?secret=value", out _) &&
    SignerClient.TryEndpoint("http://127.0.0.1:19754/", out _), "Signer must use the loopback HTTP endpoint");
Console.WriteLine("Packet and signing fixtures passed.");
