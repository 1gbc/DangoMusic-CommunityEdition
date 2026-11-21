using System.Text;
using System.Text.Json;

namespace DangoMusic.Bilibili;

internal static class Program
{
    public static async Task<int> Main(string[] args)
    {
        Console.InputEncoding = new UTF8Encoding(false);
        Console.OutputEncoding = new UTF8Encoding(false);
        var stop = new CancellationTokenSource();
        Console.CancelKeyPress += (_, signal) =>
        {
            signal.Cancel = true;
            stop.Cancel();
        };

        var output = new HelperOutput(Console.Out);
        try
        {
            if (args.Length != 0)
                throw new LiveFailure("invalid_config");

            var config = await Console.In.ReadLineAsync(stop.Token);
            if (config is null) return 0;
            using var json = ParseLine(config) ?? throw new LiveFailure("invalid_config");
            var mode = json.RootElement.Text("mode");
            var mock = mode == "mock";
            if (!mock && mode != "live")
                throw new LiveFailure("invalid_config");

            if (mock)
            {
                output.Status("connected", "mock");
                await ReadCommandsAsync(output, true, stop.Token);
            }
            else
            {
                var roomId = json.RootElement.Text("roomId");
                if (!WebRoomClient.IsRoomId(roomId))
                    throw new LiveFailure("invalid_room");

                // A redirected console read can block synchronously on Windows. Keep it off the session task.
                _ = Task.Run(async () =>
                {
                    try
                    {
                        await ReadCommandsAsync(output, false, stop.Token);
                    }
                    catch (Exception error) when (error is OperationCanceledException or IOException)
                    {
                        // Closing the pipe is equivalent to sending stop.
                    }
                    finally
                    {
                        stop.Cancel();
                    }
                });

                output.Status("connecting", "live");
                using var client = new WebRoomClient();
                await new LiveSession(client, output).RunAsync(roomId!, stop.Token);
            }
            output.Status("disconnected", "stopped");
            return 0;
        }
        catch (OperationCanceledException) when (stop.IsCancellationRequested)
        {
            output.Status("disconnected", "stopped");
            return 0;
        }
        catch (LiveFailure failure)
        {
            if (stop.IsCancellationRequested)
            {
                output.Status("disconnected", "stopped");
                return 0;
            }
            output.Status("failed", failure.Reason);
            return 1;
        }
        catch (IOException)
        {
            // The game may already have exited and closed the output pipe.
            return 1;
        }
        catch (Exception)
        {
            output.Status("failed", "internal_error");
            return 1;
        }
    }

    private static async Task ReadCommandsAsync(HelperOutput output, bool mock, CancellationToken stop)
    {
        while (await Console.In.ReadLineAsync(stop) is { } line)
        {
            using var json = ParseLine(line);
            if (json is null) continue;
            var command = json.RootElement;
            var type = command.Text("type");
            if (type == "stop") return;
            if (mock && type == "danmaku" && Danmaku.Read(command, "id", "user", "content") is { } message)
                output.Message(message);
        }
    }

    private static JsonDocument? ParseLine(string line)
    {
        if (line.Length > 8192) return null;
        try
        {
            return JsonDocument.Parse(line);
        }
        catch (JsonException)
        {
            return null;
        }
    }
}

internal sealed class HelperOutput(TextWriter writer)
{
    private readonly RecentDanmaku recent = new();

    public void Status(string state, string reason) => Write(new { type = "status", state, reason });

    public void Message(Danmaku message)
    {
        if (recent.Accept(message.Id))
            Write(new { type = "danmaku", id = message.Id, user = message.User, content = message.Content });
    }

    private void Write<T>(T value)
    {
        writer.WriteLine(JsonSerializer.Serialize(value));
        writer.Flush();
    }
}

internal sealed class RecentDanmaku
{
    private const int Capacity = 4096;
    private readonly Queue<string> order = new();
    private readonly HashSet<string> ids = new(StringComparer.Ordinal);

    public bool Accept(string id)
    {
        if (!ids.Add(id)) return false;
        order.Enqueue(id);
        if (order.Count > Capacity)
            ids.Remove(order.Dequeue());
        return true;
    }
}

internal sealed class LiveFailure(string reason) : Exception(reason)
{
    public string Reason { get; } = reason;
}

internal static class JsonFields
{
    public static JsonElement Field(this JsonElement value, string name) =>
        value.ValueKind == JsonValueKind.Object && value.TryGetProperty(name, out var field)
            ? field
            : default;

    public static string? Text(this JsonElement value, string name)
    {
        var field = value.Field(name);
        if (field.ValueKind != JsonValueKind.String) return null;
        try
        {
            return field.GetString();
        }
        catch (InvalidOperationException)
        {
            return null;
        }
    }

    public static int? Number(this JsonElement value, string name)
    {
        var field = value.Field(name);
        return field.ValueKind == JsonValueKind.Number && field.TryGetInt32(out var number)
            ? number
            : null;
    }

    public static bool ValidText(string? value, int maximum) =>
        !string.IsNullOrWhiteSpace(value) && value.Length <= maximum && !value.Any(char.IsControl);
}

internal sealed record Danmaku(string Id, string User, string Content)
{
    public static Danmaku? Read(JsonElement value, string idName, string userName, string contentName)
    {
        var id = value.Text(idName);
        var user = value.Text(userName);
        var content = value.Text(contentName);
        return JsonFields.ValidText(id, 256) && JsonFields.ValidText(user, 256) &&
            JsonFields.ValidText(content, 2048)
            ? new Danmaku(id!, user!, content!)
            : null;
    }
}
