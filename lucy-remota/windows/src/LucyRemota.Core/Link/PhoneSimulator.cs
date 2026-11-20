using System.Net;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json.Nodes;
using System.Threading.Channels;
using LucyRemota.Core.Protocol;
using LucyRemota.Core.Security;

namespace LucyRemota.Core.Link;

/// <summary>
/// Teléfono simulado (el lado «emisor» del protocolo), igual al de Lucy Remota para Android: contesta
/// los anuncios, acepta la conexión de la PC, negocia y manda órdenes. Lo usan las pruebas y la
/// autoprueba del .exe (sin teléfono de verdad).
/// </summary>
public sealed class PhoneSimulator : IAsyncDisposable
{
    public string Id { get; set; } = "tel-" + Convert.ToHexString(RandomNumberGenerator.GetBytes(6)).ToLowerInvariant();
    public IdentityKey Key { get; set; } = IdentityKey.Create();
    readonly TcpListener listener = new(IPAddress.Loopback, 0);
    readonly UdpClient udp;
    readonly CancellationTokenSource stop = new();
    public int Port => ((IPEndPoint)listener.LocalEndpoint).Port;
    public int UdpPort => ((IPEndPoint)udp.Client.LocalEndPoint!).Port;

    // Vinculación pendiente (QR o código) o PC ya vinculada.
    public (string pcId, byte[] fpr, byte[] token)? PendingQr;
    public string? PendingCode;
    public (string pcId, byte[] key)? Paired;
    public string? LastFailure;
    public bool AnswerBeacons = true;

    Stream? stream;
    SessionCipher? cipher;
    public JsonObject? Capabilities { get; private set; }
    readonly Channel<JsonObject> inbox = Channel.CreateUnbounded<JsonObject>();
    readonly SemaphoreSlim connected = new(0);

    public PhoneSimulator()
    {
        udp = new UdpClient(new IPEndPoint(IPAddress.Loopback, 0));
        listener.Start();
        _ = AcceptLoop();
        _ = UdpLoop();
    }

    async Task UdpLoop()
    {
        while (!stop.IsCancellationRequested)
        {
            UdpReceiveResult r;
            try { r = await udp.ReceiveAsync(stop.Token); } catch { return; }
            var msg = Wire.ParseObject(r.Buffer);
            // Conectado por el relé sí contesta: si la PC está en la misma red, se pasa a la directa.
            if (msg.Str("t") != "beacon" || !AnswerBeacons || (stream != null && stream is not RelayStream)) continue;
            var pc = msg.Str("pc");
            var interested = Paired?.pcId == pc || PendingQr?.pcId == pc || (PendingCode != null && msg.Bool("pairing") == true);
            if (!interested) continue;
            var reply = Encoding.UTF8.GetBytes(new JsonObject { ["lr"] = 1, ["t"] = "here", ["pc"] = pc, ["dev"] = Id, ["port"] = Port }.ToJsonString());
            await udp.SendAsync(reply, r.RemoteEndPoint);
        }
    }

    async Task AcceptLoop()
    {
        while (!stop.IsCancellationRequested)
        {
            TcpClient c;
            try { c = await listener.AcceptTcpClientAsync(stop.Token); } catch { return; }
            _ = Task.Run(() => Handle(c.GetStream(), c.Close));
        }
    }

    /// <summary>
    /// Se conecta al relé como lo hace el teléfono cuando está en otra red (ya vinculado) y atiende a
    /// la PC por ahí hasta que se cancele.
    /// </summary>
    public async Task RunRelayAsync(string relayUrl, CancellationToken ct)
    {
        var (_, pcKey) = Paired ?? throw new InvalidOperationException("primero hay que vincular");
        var uri = RelayClient.RoomUri(relayUrl, RelayClient.RoomId(pcKey, Key.PublicSpki), "dev");
        using var both = CancellationTokenSource.CreateLinkedTokenSource(ct, stop.Token);
        await using var relay = await RelayClient.ConnectAsync(uri, both.Token);
        while (await relay.NextPeerAsync(both.Token) is { } peer)
        {
            var s = peer;
            _ = Task.Run(() => Handle(s, s.Dispose));
        }
    }

    async Task Handle(Stream s, Action close)
    {
        var c = new Closer(close);
        try
        {
            var hello = Wire.ParseObject(await Wire.ReadFrameAsync(s, stop.Token));
            var pcId = hello.Str("pc")!;
            var pcKey = hello.B64("pc_key")!;
            var pcEph = hello.B64("eph")!;
            var pcNonce = hello.B64("nonce")!;
            string? mode = null;
            byte[]? token = null;
            if (Paired is { } p && p.pcId == pcId)
            {
                if (!p.key.AsSpan().SequenceEqual(pcKey)) { LastFailure = "pc_key_changed"; c.Close(); return; }
            }
            else if (PendingQr is { } q && q.pcId == pcId)
            {
                if (!IdentityKey.Fingerprint(pcKey).AsSpan().SequenceEqual(q.fpr)) { LastFailure = "fingerprint"; c.Close(); return; }
                mode = "qr"; token = q.token;
            }
            else if (PendingCode != null)
            {
                mode = "code"; token = PairingCodes.ManualToken(PendingCode);
            }
            else { LastFailure = "unknown_pc"; c.Close(); return; }

            using var eph = new EphemeralKey();
            var nonce = RandomNumberGenerator.GetBytes(32);
            var transcript = Transcript.Hash(pcId, pcKey, pcEph, pcNonce, Id, Key.PublicSpki, eph.PublicSpki, nonce);
            var auth = new JsonObject
            {
                ["t"] = "auth", ["v"] = 1, ["dev"] = Id, ["name"] = "Teléfono de prueba", ["dev_key"] = J.B64(Key.PublicSpki),
                ["eph"] = J.B64(eph.PublicSpki), ["nonce"] = J.B64(nonce), ["now"] = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(),
                ["sig"] = J.B64(Key.Sign(Transcript.Role("dev", transcript))),
            };
            if (token != null) { auth["pair"] = J.B64(Transcript.PairProof(token, "dev", transcript)); auth["pair_mode"] = mode; }
            await Wire.WriteFrameAsync(s, Wire.Json(auth), stop.Token);
            var answer = Wire.ParseObject(await Wire.ReadFrameAsync(s, stop.Token));
            if (answer.Str("t") != "auth_ok")
            {
                // Código equivocado: se deja de intentar (no gasta los intentos de la PC).
                LastFailure = answer.Str("reason");
                if (LastFailure == "bad_pairing_code") { PendingCode = null; PendingQr = null; }
                c.Close();
                return;
            }
            if (!IdentityKey.Verify(pcKey, Transcript.Role("pc", transcript), answer.B64("sig")!)) { LastFailure = "pc_signature"; c.Close(); return; }
            if (mode == "code" && !answer.B64("pair")!.AsSpan().SequenceEqual(Transcript.PairProof(token!, "pc", transcript))) { LastFailure = "pc_code"; c.Close(); return; }
            var (pcToDev, devToPc) = Transcript.SessionKeys(eph.Agree(pcEph), transcript);
            cipher = new SessionCipher(devToPc, pcToDev);
            stream = s;
            Paired = (pcId, pcKey);
            PendingQr = null;
            PendingCode = null;
            while (!stop.IsCancellationRequested)
            {
                var msg = Wire.ParseObject(cipher.Open(await Wire.ReadFrameAsync(s, stop.Token)));
                if (msg.Str("t") == "capabilities") { Capabilities = msg; connected.Release(); continue; }
                if (msg.Str("t") == "ping") { await Send(new JsonObject { ["t"] = "pong", ["at"] = msg.Long("at") }); continue; }
                await inbox.Writer.WriteAsync(msg);
            }
        }
        catch (Exception) { }
        finally { if (stream == s) stream = null; }
    }

    sealed class Closer(Action close) { public void Close() => close(); }

    public async Task<bool> WaitConnectedAsync(TimeSpan timeout) => await connected.WaitAsync(timeout);

    public async Task Send(JsonObject msg) => await Wire.WriteFrameAsync(stream!, cipher!.Seal(Wire.Json(msg)), stop.Token);

    /// <summary>Manda una orden y junta los estados hasta el final.</summary>
    public async Task<List<JsonObject>> CommandAsync(string intent, JsonObject? p = null, string? id = null, long? sentAt = null, bool confirmed = false, int expiresMs = 8000, int waitSeconds = 10)
    {
        id ??= Guid.NewGuid().ToString();
        await Send(new JsonObject
        {
            ["t"] = "command", ["v"] = 1, ["command_id"] = id, ["intent"] = intent, ["params"] = p ?? new JsonObject(),
            ["sent_at"] = sentAt ?? DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(), ["expires_ms"] = expiresMs, ["confirmed"] = confirmed,
        });
        var list = new List<JsonObject>();
        using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(waitSeconds));
        while (true)
        {
            var m = await inbox.Reader.ReadAsync(cts.Token);
            if (m.Str("command_id") != id) continue;
            list.Add(m);
            if (LucyRemota.Core.Commands.Status.IsFinal(m.Str("status")!)) return list;
        }
    }

    /// <summary>Manda bytes cualquiera cifrados (para probar mensajes inválidos).</summary>
    public Task SendRaw(byte[] data) => Wire.WriteFrameAsync(stream!, data, stop.Token);

    public async ValueTask DisposeAsync()
    {
        stop.Cancel();
        listener.Stop();
        udp.Dispose();
        await Task.CompletedTask;
    }
}
