using System.Net.WebSockets;
using System.Security.Cryptography;
using System.Text;
using System.Threading.Channels;

namespace LucyRemota.Core.Link;

/// <summary>
/// Conexión con el relé de Internet (para cuando la PC y el teléfono están en redes distintas).
/// Ver «Relé» en PROTOCOLO.md: los dos se conectan hacia afuera a la misma sala y el relé reenvía
/// los bytes, que ya van cifrados de punta a punta. Cada vez que llega el otro lado («peer:1») hay
/// un flujo nuevo (<see cref="RelayStream"/>) para negociar desde cero.
/// </summary>
public sealed class RelayClient : IAsyncDisposable
{
    public const string DefaultUrl = "https://lucy-remota-relay.devecchianderson12.workers.dev";
    static readonly TimeSpan KeepAlive = TimeSpan.FromSeconds(25);
    static readonly TimeSpan Silence = TimeSpan.FromSeconds(70);

    readonly ClientWebSocket ws;
    readonly SemaphoreSlim sendLock = new(1, 1);
    readonly Channel<RelayStream> peers = Channel.CreateUnbounded<RelayStream>();
    readonly CancellationTokenSource stop = new();
    RelayStream? current;
    long lastHeardAt = Environment.TickCount64;
    Task? loops;

    RelayClient(ClientWebSocket ws) => this.ws = ws;

    /// <summary>Sala de un par PC–teléfono: sale de las dos claves públicas (sólo ellos la conocen).</summary>
    public static string RoomId(byte[] pcKey, byte[] devKey)
    {
        var label = Encoding.ASCII.GetBytes("lucy-remota/relay/1");
        var data = new byte[label.Length + pcKey.Length + devKey.Length];
        label.CopyTo(data, 0);
        pcKey.CopyTo(data, label.Length);
        devKey.CopyTo(data, label.Length + pcKey.Length);
        return Convert.ToHexString(SHA256.HashData(data)).ToLowerInvariant()[..40];
    }

    public static Uri RoomUri(string baseUrl, string room, string role)
    {
        var b = baseUrl.TrimEnd('/');
        if (b.StartsWith("https://", StringComparison.OrdinalIgnoreCase)) b = "wss://" + b[8..];
        else if (b.StartsWith("http://", StringComparison.OrdinalIgnoreCase)) b = "ws://" + b[7..];
        return new Uri($"{b}/v1/{room}?role={role}");
    }

    public static async Task<RelayClient> ConnectAsync(Uri uri, CancellationToken ct)
    {
        var ws = new ClientWebSocket();
        ws.Options.KeepAliveInterval = TimeSpan.Zero; // el latido es el «ping» de texto (no despierta la sala)
        using (var c = CancellationTokenSource.CreateLinkedTokenSource(ct))
        {
            c.CancelAfter(TimeSpan.FromSeconds(10));
            await ws.ConnectAsync(uri, c.Token).ConfigureAwait(false);
        }
        var client = new RelayClient(ws);
        client.loops = Task.WhenAll(client.ReceiveLoop(), client.KeepAliveLoop());
        return client;
    }

    public bool IsOpen => ws.State == WebSocketState.Open && !stop.IsCancellationRequested;

    /// <summary>Espera a que el otro lado esté conectado; null si se cortó la conexión con el relé.</summary>
    public async Task<RelayStream?> NextPeerAsync(CancellationToken ct)
    {
        try { return await peers.Reader.ReadAsync(ct).ConfigureAwait(false); }
        catch (ChannelClosedException) { return null; }
    }

    async Task ReceiveLoop()
    {
        var buf = new byte[64 * 1024];
        var msg = new MemoryStream();
        try
        {
            while (!stop.IsCancellationRequested)
            {
                var r = await ws.ReceiveAsync(buf, stop.Token).ConfigureAwait(false);
                if (r.MessageType == WebSocketMessageType.Close) break;
                msg.Write(buf, 0, r.Count);
                if (!r.EndOfMessage) continue;
                Interlocked.Exchange(ref lastHeardAt, Environment.TickCount64);
                var data = msg.ToArray();
                msg.SetLength(0);
                if (r.MessageType == WebSocketMessageType.Text)
                {
                    switch (Encoding.UTF8.GetString(data))
                    {
                        case "peer:1":
                            current?.End();
                            current = new RelayStream(this);
                            peers.Writer.TryWrite(current);
                            break;
                        case "peer:0":
                            current?.End();
                            current = null;
                            break;
                    }
                }
                else
                {
                    current?.Push(data);
                }
            }
        }
        catch (Exception e) when (e is WebSocketException or OperationCanceledException or ObjectDisposedException or IOException) { }
        finally
        {
            current?.End();
            current = null;
            peers.Writer.TryComplete();
            stop.Cancel();
        }
    }

    async Task KeepAliveLoop()
    {
        try
        {
            while (!stop.IsCancellationRequested)
            {
                await Task.Delay(KeepAlive, stop.Token).ConfigureAwait(false);
                // Sin nada del relé (ni el «pong») en un buen rato: la conexión murió sin avisar.
                if (Environment.TickCount64 - Interlocked.Read(ref lastHeardAt) > Silence.TotalMilliseconds) break;
                await SendRawAsync("ping"u8.ToArray(), WebSocketMessageType.Text, stop.Token).ConfigureAwait(false);
            }
        }
        catch (Exception e) when (e is WebSocketException or OperationCanceledException or ObjectDisposedException or IOException) { }
        finally
        {
            stop.Cancel();
            try { ws.Abort(); } catch { }
        }
    }

    internal async Task SendAsync(RelayStream from, byte[] data, CancellationToken ct)
    {
        if (!ReferenceEquals(from, current) || !IsOpen) throw new IOException("el otro lado ya no está en el relé");
        await SendRawAsync(data, WebSocketMessageType.Binary, ct).ConfigureAwait(false);
    }

    async Task SendRawAsync(byte[] data, WebSocketMessageType type, CancellationToken ct)
    {
        await sendLock.WaitAsync(ct).ConfigureAwait(false);
        try { await ws.SendAsync(data, type, true, ct).ConfigureAwait(false); }
        catch (WebSocketException e) { throw new IOException(e.Message, e); }
        finally { sendLock.Release(); }
    }

    public async ValueTask DisposeAsync()
    {
        if (!stop.IsCancellationRequested)
        {
            try
            {
                using var c = new CancellationTokenSource(TimeSpan.FromSeconds(2));
                await ws.CloseAsync(WebSocketCloseStatus.NormalClosure, "chau", c.Token).ConfigureAwait(false);
            }
            catch { }
        }
        stop.Cancel();
        try { ws.Abort(); } catch { }
        if (loops != null) try { await loops.ConfigureAwait(false); } catch { }
        ws.Dispose();
    }
}

/// <summary>
/// Un flujo de bytes con el otro lado a través del relé. Lo escrito se junta hasta el Flush y va en
/// un solo mensaje (un marco del protocolo = un mensaje). Termina (fin de flujo) si el otro se va.
/// </summary>
public sealed class RelayStream : Stream
{
    readonly RelayClient client;
    readonly Channel<byte[]> incoming = Channel.CreateUnbounded<byte[]>();
    readonly MemoryStream outgoing = new();
    byte[] pending = [];
    int pendingAt;

    internal RelayStream(RelayClient client) => this.client = client;

    internal void Push(byte[] data) => incoming.Writer.TryWrite(data);
    internal void End() => incoming.Writer.TryComplete();

    public override async ValueTask<int> ReadAsync(Memory<byte> buffer, CancellationToken ct = default)
    {
        while (pendingAt >= pending.Length)
        {
            try { pending = await incoming.Reader.ReadAsync(ct).ConfigureAwait(false); }
            catch (ChannelClosedException) { return 0; }
            pendingAt = 0;
        }
        var n = Math.Min(buffer.Length, pending.Length - pendingAt);
        pending.AsMemory(pendingAt, n).CopyTo(buffer);
        pendingAt += n;
        return n;
    }

    public override Task<int> ReadAsync(byte[] buffer, int offset, int count, CancellationToken ct) =>
        ReadAsync(buffer.AsMemory(offset, count), ct).AsTask();

    public override int Read(byte[] buffer, int offset, int count) => ReadAsync(buffer, offset, count, default).GetAwaiter().GetResult();

    public override ValueTask WriteAsync(ReadOnlyMemory<byte> buffer, CancellationToken ct = default)
    {
        lock (outgoing) outgoing.Write(buffer.Span);
        return ValueTask.CompletedTask;
    }

    public override Task WriteAsync(byte[] buffer, int offset, int count, CancellationToken ct) =>
        WriteAsync(buffer.AsMemory(offset, count), ct).AsTask();

    public override void Write(byte[] buffer, int offset, int count) { lock (outgoing) outgoing.Write(buffer, offset, count); }

    public override async Task FlushAsync(CancellationToken ct)
    {
        byte[] data;
        lock (outgoing)
        {
            if (outgoing.Length == 0) return;
            data = outgoing.ToArray();
            outgoing.SetLength(0);
        }
        await client.SendAsync(this, data, ct).ConfigureAwait(false);
    }

    public override void Flush() => FlushAsync(default).GetAwaiter().GetResult();

    protected override void Dispose(bool disposing)
    {
        End();
        base.Dispose(disposing);
    }

    public override bool CanRead => true;
    public override bool CanWrite => true;
    public override bool CanSeek => false;
    public override long Length => throw new NotSupportedException();
    public override long Position { get => throw new NotSupportedException(); set => throw new NotSupportedException(); }
    public override long Seek(long offset, SeekOrigin origin) => throw new NotSupportedException();
    public override void SetLength(long value) => throw new NotSupportedException();
}
