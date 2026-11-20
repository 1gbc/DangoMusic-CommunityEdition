using System.Security.Cryptography;

namespace DangoMusic.Bilibili;

internal sealed class HostedSessions(TimeProvider clock)
{
    private static readonly TimeSpan IdleLimit = TimeSpan.FromMinutes(2);
    private readonly Dictionary<string, (string GameId, DateTimeOffset Expires)> active = new();

    public string? Add(string gameId)
    {
        lock (active)
        {
            var now = clock.GetUtcNow();
            foreach (var expired in active.Where(entry => entry.Value.Expires <= now).Select(entry => entry.Key).ToArray())
                active.Remove(expired);
            if (active.Count >= 128) return null;

            var token = Convert.ToHexString(RandomNumberGenerator.GetBytes(32));
            active.Add(token, (gameId, now + IdleLimit));
            return token;
        }
    }

    public string? Get(string token)
    {
        lock (active)
            return active.TryGetValue(token, out var session) && session.Expires > clock.GetUtcNow()
                ? session.GameId
                : null;
    }

    public void Refresh(string token)
    {
        lock (active)
        {
            var now = clock.GetUtcNow();
            if (active.TryGetValue(token, out var session) && session.Expires > now)
                active[token] = (session.GameId, now + IdleLimit);
        }
    }

    public void Remove(string token)
    {
        lock (active)
            active.Remove(token);
    }
}
