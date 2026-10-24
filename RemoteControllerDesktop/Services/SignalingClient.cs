using System.Net.WebSockets;
using System.Text.Json;

namespace RemoteControllerDesktop.Services;

// Construct only after the REST API reports that the owner accepted this session.
public sealed class SignalingClient : IAsyncDisposable
{
    private static readonly HashSet<string> AllowedTypes = ["offer", "answer", "ice"];
    private readonly ClientWebSocket _socket = new();
    private readonly SemaphoreSlim _sendLock = new(1, 1);
    private readonly Uri _endpoint;
    private readonly string _sessionId;
    private CancellationTokenSource? _lifetime;

    public event Action<JsonElement>? SignalReceived;
    public event Action? SessionEnded;

    public SignalingClient(Uri endpoint, string token, string sessionId)
    {
        if (endpoint.Scheme != "wss")
            throw new ArgumentException("Signaling requires a TLS-protected wss:// URL.", nameof(endpoint));
        _endpoint = endpoint;
        _socket.Options.SetRequestHeader("Authorization", $"Bearer {token}");
        _sessionId = sessionId;
    }

    public async Task ConnectAsync(CancellationToken cancellationToken = default)
    {
        _lifetime = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        await _socket.ConnectAsync(_endpoint, _lifetime.Token);
        await SendMessageAsync(new { sessionId = _sessionId, type = "join" }, _lifetime.Token);
        _ = ReceiveLoopAsync(_lifetime.Token);
    }

    public Task SendAsync(string type, JsonElement payload, CancellationToken cancellationToken = default)
    {
        if (!AllowedTypes.Contains(type))
            throw new ArgumentOutOfRangeException(nameof(type), "Unsupported signaling message type.");
        return SendMessageAsync(new { sessionId = _sessionId, type, payload }, cancellationToken);
    }

    private async Task SendMessageAsync<T>(T message, CancellationToken cancellationToken)
    {
        var bytes = JsonSerializer.SerializeToUtf8Bytes(message);
        await _sendLock.WaitAsync(cancellationToken);
        try
        {
            await _socket.SendAsync(
                new ArraySegment<byte>(bytes), WebSocketMessageType.Text, true, cancellationToken);
        }
        finally
        {
            _sendLock.Release();
        }
    }

    private async Task ReceiveLoopAsync(CancellationToken cancellationToken)
    {
        var buffer = new byte[16 * 1024];
        while (_socket.State == WebSocketState.Open && !cancellationToken.IsCancellationRequested)
        {
            using var output = new MemoryStream();
            WebSocketReceiveResult result;
            do
            {
                result = await _socket.ReceiveAsync(new ArraySegment<byte>(buffer), cancellationToken);
                if (result.MessageType == WebSocketMessageType.Close)
                {
                    SessionEnded?.Invoke();
                    return;
                }
                output.Write(buffer, 0, result.Count);
                if (output.Length > 64 * 1024)
                    throw new InvalidDataException("Signaling message exceeds the size limit.");
            } while (!result.EndOfMessage);

            using var document = JsonDocument.Parse(output.ToArray());
            var root = document.RootElement;
            if (root.GetProperty("sessionId").GetString() != _sessionId) continue;
            if (root.GetProperty("type").GetString() == "session_ended")
            {
                SessionEnded?.Invoke();
                return;
            }
            SignalReceived?.Invoke(root.Clone());
        }
    }

    public async ValueTask DisposeAsync()
    {
        _lifetime?.Cancel();
        if (_socket.State == WebSocketState.Open)
            await _socket.CloseAsync(WebSocketCloseStatus.NormalClosure, "Session closed", CancellationToken.None);
        _lifetime?.Dispose();
        _socket.Dispose();
        _sendLock.Dispose();
    }
}
