using System.Collections.Concurrent;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json.Nodes;
using LucyRemota.Core.Commands;
using LucyRemota.Core.Protocol;
using LucyRemota.Core.Security;

namespace LucyRemota.Core.Link;

/// <summary>Un teléfono conectado ahora.</summary>
public sealed record ConnectedPhone(string Id, string Name, string Address, DateTimeOffset Since, long LatencyMs);

/// <summary>
/// Conexión de la PC con los teléfonos vinculados.
///
/// La PC nunca espera conexiones (así Windows no pide permisos de administrador para el firewall):
/// anuncia por UDP que está («beacon»), el teléfono contesta con su puerto y la PC se conecta a él.
/// Después: negociación con claves (ver PROTOCOLO.md), mensajes cifrados, latido cada 15 s y
/// reconexión sola si se corta.
/// </summary>
public sealed class PcLink : IAsyncDisposable
{
    readonly IPcStore store;
    readonly CommandRegistry registry;
    readonly Action<string> log;
    readonly TimeProvider clock;
    readonly ConcurrentDictionary<string, Session> sessions = new();
    readonly ConcurrentDictionary<string, byte> connecting = new();
    readonly CancellationTokenSource stop = new();
    readonly int discoveryPort;
    UdpClient? udp;
    Task? discoveryTask;
    PairingWindow? pairing;

    public TimeSpan BeaconInterval { get; set; } = TimeSpan.FromSeconds(2);
    public TimeSpan HeartbeatInterval { get; set; } = TimeSpan.FromSeconds(15);
    public TimeSpan IdleTimeout { get; set; } = TimeSpan.FromSeconds(45);
    /// <summary>Direcciones extra a las que anunciarse (pruebas, o la última IP conocida).</summary>
    public List<IPEndPoint> ExtraTargets { get; } = new();

    public event Action? ConnectionsChanged;
    public event Action? DevicesChanged;
    /// <summary>Se vinculó un teléfono nuevo (cierra la ventana del QR).</summary>
    public event Action<PairedDevice>? Paired;

    public PcLink(IPcStore store, CommandRegistry registry, Action<string>? log = null, TimeProvider? clock = null, int discoveryPort = Proto.DiscoveryPort)
    {
        this.store = store;
        this.registry = registry;
        this.log = log ?? (_ => { });
        this.clock = clock ?? TimeProvider.System;
        this.discoveryPort = discoveryPort;
    }

    public IReadOnlyList<ConnectedPhone> Connected => sessions.Values.Select(s => s.Info).ToList();

    public PairingWindow? Pairing => pairing is { } p && p.Open(clock.GetUtcNow()) ? p : null;

    public PairingWindow StartPairing()
    {
        pairing = new PairingWindow(clock.GetUtcNow());
        log("Vinculación abierta (5 minutos)");
        return pairing;
    }

    public void CancelPairing() => pairing = null;

    public void Start()
    {
        udp = new UdpClient(new IPEndPoint(IPAddress.Any, 0)) { EnableBroadcast = true };
        discoveryTask = Task.WhenAll(BeaconLoop(stop.Token), ReplyLoop(stop.Token));
    }

    /// <summary>Desvincula un teléfono: se borra su clave y, si está conectado, se le avisa y se corta.</summary>
    public async Task UnpairAsync(string deviceId)
    {
        if (sessions.TryGetValue(deviceId, out var s))
        {
            try { await s.SendAsync(new JsonObject { ["t"] = "unpaired" }); } catch { }
            s.Close();
        }
        if (store.Remove(deviceId)) log($"Teléfono {deviceId} desvinculado");
        DevicesChanged?.Invoke();
    }

    // ------------------------------------------------------------------ Descubrimiento

    async Task BeaconLoop(CancellationToken ct)
    {
        while (!ct.IsCancellationRequested)
        {
            try
            {
                // Sólo hace falta anunciarse si falta conectar algún teléfono (o se está vinculando uno).
                var missing = Pairing != null || store.Devices.Any(d => !sessions.ContainsKey(d.Id));
                if (missing && udp != null)
                {
                    var beacon = Encoding.UTF8.GetBytes(new JsonObject
                    {
                        ["lr"] = Proto.Major, ["t"] = "beacon", ["pc"] = store.PcId, ["name"] = store.PcName, ["pairing"] = Pairing != null,
                    }.ToJsonString());
                    foreach (var target in Targets())
                    {
                        try { await udp.SendAsync(beacon, target, ct); } catch (SocketException) { }
                    }
                }
            }
            catch (Exception e) when (e is not OperationCanceledException)
            {
                log("Anuncio falló: " + e.Message);
            }
            try { await Task.Delay(BeaconInterval, clock, ct); } catch (OperationCanceledException) { return; }
        }
    }

    /// <summary>Broadcast general, el de cada red, la puerta de enlace (el teléfono, si es su hotspot) y las IP conocidas.</summary>
    IEnumerable<IPEndPoint> Targets()
    {
        var set = new HashSet<IPEndPoint> { new(IPAddress.Broadcast, discoveryPort) };
        try
        {
            foreach (var ni in NetworkInterface.GetAllNetworkInterfaces())
            {
                if (ni.OperationalStatus != OperationalStatus.Up || ni.NetworkInterfaceType == NetworkInterfaceType.Loopback) continue;
                var props = ni.GetIPProperties();
                foreach (var ua in props.UnicastAddresses)
                {
                    if (ua.Address.AddressFamily != AddressFamily.InterNetwork || ua.IPv4Mask == null) continue;
                    var ip = ua.Address.GetAddressBytes();
                    var mask = ua.IPv4Mask.GetAddressBytes();
                    var b = new byte[4];
                    for (var i = 0; i < 4; i++) b[i] = (byte)(ip[i] | ~mask[i]);
                    set.Add(new IPEndPoint(new IPAddress(b), discoveryPort));
                }
                foreach (var g in props.GatewayAddresses)
                    if (g.Address.AddressFamily == AddressFamily.InterNetwork && !g.Address.Equals(IPAddress.Any))
                        set.Add(new IPEndPoint(g.Address, discoveryPort));
            }
        }
        catch (NetworkInformationException) { }
        foreach (var d in store.Devices)
            if (d.LastAddress != null && IPAddress.TryParse(d.LastAddress, out var a)) set.Add(new IPEndPoint(a, discoveryPort));
        foreach (var t in ExtraTargets) set.Add(t);
        return set;
    }

    async Task ReplyLoop(CancellationToken ct)
    {
        while (!ct.IsCancellationRequested && udp != null)
        {
            UdpReceiveResult r;
            try { r = await udp.ReceiveAsync(ct); }
            catch (OperationCanceledException) { return; }
            catch (SocketException) { continue; }
            catch (ObjectDisposedException) { return; }
            JsonObject msg;
            try { msg = Wire.ParseObject(r.Buffer); } catch (ProtocolException) { continue; }
            if (msg.Str("t") != "here" || msg.Str("pc") != store.PcId) continue;
            var port = (int)(msg.Long("port") ?? Proto.PhonePort);
            if (port is < 1024 or > 65535) continue;
            var dev = msg.Str("dev");
            if (dev != null && sessions.ContainsKey(dev)) continue;
            var endpoint = new IPEndPoint(r.RemoteEndPoint.Address, port);
            // Un teléfono puede contestar por varias redes a la vez: una sola conexión por teléfono.
            var key = dev ?? endpoint.ToString();
            if (!connecting.TryAdd(key, 0)) continue;
            _ = Task.Run(async () =>
            {
                try { await ConnectAsync(endpoint, ct); }
                finally { connecting.TryRemove(key, out _); }
            }, ct);
        }
    }

    // ------------------------------------------------------------------ Conexión y negociación

    /// <summary>Se conecta al teléfono, negocia y atiende la sesión hasta que se corte.</summary>
    public async Task ConnectAsync(IPEndPoint endpoint, CancellationToken ct)
    {
        using var tcp = new TcpClient { NoDelay = true };
        try
        {
            using (var c = CancellationTokenSource.CreateLinkedTokenSource(ct))
            {
                c.CancelAfter(TimeSpan.FromSeconds(4));
                await tcp.ConnectAsync(endpoint, c.Token);
            }
            var stream = tcp.GetStream();
            Session? session;
            using (var c = CancellationTokenSource.CreateLinkedTokenSource(ct))
            {
                c.CancelAfter(TimeSpan.FromSeconds(8));
                session = await HandshakeAsync(stream, endpoint.Address.ToString(), c.Token);
            }
            if (session == null) return;
            if (sessions.TryRemove(session.Info.Id, out var old)) old.Close();
            sessions[session.Info.Id] = session;
            ConnectionsChanged?.Invoke();
            log($"Conectada con {session.Info.Name} ({endpoint.Address})");
            try { await session.RunAsync(ct); }
            finally
            {
                sessions.TryRemove(new KeyValuePair<string, Session>(session.Info.Id, session));
                ConnectionsChanged?.Invoke();
                log($"Se cortó la conexión con {session.Info.Name}");
            }
        }
        catch (Exception e) when (e is SocketException or IOException or OperationCanceledException or ProtocolException)
        {
            if (!ct.IsCancellationRequested) log($"No pude conectar con {endpoint}: {e.Message}");
        }
    }

    async Task<Session?> HandshakeAsync(Stream stream, string address, CancellationToken ct)
    {
        using var eph = new EphemeralKey();
        var nonce = RandomNumberGenerator.GetBytes(32);
        var pcKey = store.Key.PublicSpki;
        var window = Pairing;
        await Wire.WriteFrameAsync(stream, Wire.Json(new JsonObject
        {
            ["t"] = "hello", ["v"] = Proto.Major, ["minor"] = Proto.Minor, ["pc"] = store.PcId, ["name"] = store.PcName,
            ["pc_key"] = J.B64(pcKey), ["eph"] = J.B64(eph.PublicSpki), ["nonce"] = J.B64(nonce), ["pairing"] = window != null,
        }), ct);
        var auth = Wire.ParseObject(await Wire.ReadFrameAsync(stream, ct));
        if (auth.Str("t") != "auth") throw new ProtocolException("se esperaba «auth»");
        if ((auth.Long("v") ?? 0) != Proto.Major)
        {
            await Fail(stream, "protocol_version", ct);
            return null;
        }
        var devId = auth.Str("dev");
        var devName = auth.Str("name") ?? "Teléfono";
        var devKey = auth.B64("dev_key");
        var devEph = auth.B64("eph");
        var devNonce = auth.B64("nonce");
        var sig = auth.B64("sig");
        if (devId is null || devId.Length is < 8 or > 64 || devKey is null || devEph is null || devNonce is not { Length: 32 } || sig is null)
            throw new ProtocolException("«auth» incompleto");
        var transcript = Transcript.Hash(store.PcId, pcKey, eph.PublicSpki, nonce, devId, devKey, devEph, devNonce);
        if (!IdentityKey.Verify(devKey, Transcript.Role("dev", transcript), sig))
        {
            await Fail(stream, "bad_signature", ct);
            log("Rechazado: firma inválida");
            return null;
        }
        var known = store.Devices.FirstOrDefault(d => d.Id == devId);
        byte[]? usedToken = null;
        if (known != null && known.PublicKey.AsSpan().SequenceEqual(devKey))
        {
            // Teléfono vinculado: su clave coincide con la guardada.
        }
        else if (window != null && auth.B64("pair") is { } proof)
        {
            var token = auth.Str("pair_mode") == "code" ? window.ManualToken : window.QrToken;
            if (!CryptographicOperations.FixedTimeEquals(proof, Transcript.PairProof(token, "dev", transcript)))
            {
                window.Failures++;
                await Fail(stream, "bad_pairing_code", ct);
                log("Vinculación rechazada: el código no coincide");
                return null;
            }
            usedToken = token;
        }
        else
        {
            await Fail(stream, "unauthorized", ct);
            log($"Rechazado: {devName} no está vinculado");
            return null;
        }
        var ok = new JsonObject { ["t"] = "auth_ok", ["sig"] = J.B64(store.Key.Sign(Transcript.Role("pc", transcript))), ["minor"] = Proto.Minor };
        if (usedToken != null) ok["pair"] = J.B64(Transcript.PairProof(usedToken, "pc", transcript));
        await Wire.WriteFrameAsync(stream, Wire.Json(ok), ct);
        var (pcToDev, devToPc) = Transcript.SessionKeys(eph.Agree(devEph), transcript);
        var now = clock.GetUtcNow();
        if (usedToken != null)
        {
            pairing = null;
            var device = new PairedDevice(devId, devName, devKey, now, now, address);
            store.Upsert(device);
            log($"Teléfono vinculado: {devName}");
            DevicesChanged?.Invoke();
            Paired?.Invoke(device);
        }
        else
        {
            store.Upsert(known! with { Name = devName, LastSeen = now, LastAddress = address });
        }
        var offset = auth.Long("now") is { } phoneNow ? now.ToUnixTimeMilliseconds() - phoneNow : 0;
        var info = new ConnectedPhone(devId, devName, address, now, 0);
        return new Session(this, stream, new SessionCipher(pcToDev, devToPc), info, offset);
    }

    static Task Fail(Stream s, string reason, CancellationToken ct) =>
        Wire.WriteFrameAsync(s, Wire.Json(new JsonObject { ["t"] = "auth_fail", ["reason"] = reason }), ct);

    JsonObject Capabilities() => new()
    {
        ["t"] = "capabilities", ["v"] = Proto.Major, ["minor"] = Proto.Minor,
        ["pc"] = new JsonObject { ["id"] = store.PcId, ["name"] = store.PcName, ["os"] = Environment.OSVersion.VersionString },
        ["intents"] = new JsonArray(registry.Catalog.Select(i => (JsonNode)i.ToJson()).ToArray()),
    };

    // ------------------------------------------------------------------ Sesión

    sealed class Session(PcLink link, Stream stream, SessionCipher cipher, ConnectedPhone info, long clockOffsetMs)
    {
        readonly SemaphoreSlim writeLock = new(1, 1);
        readonly CancellationTokenSource closed = new();
        public ConnectedPhone Info { get; private set; } = info;
        long lastPingAt;

        public async Task SendAsync(JsonObject msg)
        {
            await writeLock.WaitAsync();
            try { await Wire.WriteFrameAsync(stream, cipher.Seal(Wire.Json(msg)), closed.Token); }
            finally { writeLock.Release(); }
        }

        public void Close()
        {
            closed.Cancel();
            try { stream.Close(); } catch { }
        }

        public async Task RunAsync(CancellationToken ct)
        {
            using var linked = CancellationTokenSource.CreateLinkedTokenSource(ct, closed.Token);
            var token = linked.Token;
            await SendAsync(link.Capabilities());
            var heartbeat = Task.Run(async () =>
            {
                while (!token.IsCancellationRequested)
                {
                    await Task.Delay(link.HeartbeatInterval, link.clock, token);
                    lastPingAt = link.clock.GetUtcNow().ToUnixTimeMilliseconds();
                    await SendAsync(new JsonObject { ["t"] = "ping", ["at"] = lastPingAt });
                }
            }, token);
            try
            {
                while (!token.IsCancellationRequested)
                {
                    byte[] frame;
                    using (var idle = CancellationTokenSource.CreateLinkedTokenSource(token))
                    {
                        idle.CancelAfter(link.IdleTimeout);
                        frame = await Wire.ReadFrameAsync(stream, idle.Token);
                    }
                    var msg = Wire.ParseObject(cipher.Open(frame));
                    switch (msg.Str("t"))
                    {
                        case "command":
                            // Cada orden en su propia tarea: una lenta no frena a las demás ni al latido.
                            _ = Task.Run(() => link.registry.HandleAsync(Info.Id, msg, clockOffsetMs, SendAsync, token), token);
                            break;
                        case "ping":
                            await SendAsync(new JsonObject { ["t"] = "pong", ["at"] = msg.Long("at") ?? 0 });
                            break;
                        case "pong":
                            if (msg.Long("at") == lastPingAt && lastPingAt > 0)
                            {
                                Info = Info with { LatencyMs = link.clock.GetUtcNow().ToUnixTimeMilliseconds() - lastPingAt };
                                link.ConnectionsChanged?.Invoke();
                            }
                            break;
                        case "unpair":
                            link.store.Remove(Info.Id);
                            link.log($"{Info.Name} se desvinculó desde el teléfono");
                            link.DevicesChanged?.Invoke();
                            return;
                        case "bye":
                            return;
                    }
                }
            }
            catch (Exception e) when (e is IOException or ProtocolException or OperationCanceledException or ObjectDisposedException)
            {
                link.log($"Sesión con {Info.Name} terminada: {e.Message}");
            }
            finally
            {
                Close();
                try { await heartbeat; } catch { }
            }
        }
    }

    public async ValueTask DisposeAsync()
    {
        stop.Cancel();
        foreach (var s in sessions.Values)
        {
            try { await s.SendAsync(new JsonObject { ["t"] = "bye", ["reason"] = "closing" }); } catch { }
            s.Close();
        }
        udp?.Dispose();
        if (discoveryTask != null) try { await discoveryTask; } catch { }
    }
}
