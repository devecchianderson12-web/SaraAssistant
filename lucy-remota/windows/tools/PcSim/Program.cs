using System.Net;
using System.Text.Json.Nodes;
using LucyRemota.Core.Commands;
using LucyRemota.Core.Link;

// Uso: PcSim <archivo-para-el-QR> [segundos] [--direct host:puerto]
// Abre una vinculación, escribe el contenido del QR en el archivo y atiende al teléfono un rato.
// «--direct»: además de anunciarse, se conecta directo a esa dirección (emulador con «adb forward»).
// «--relay URL»: también espera al teléfono en ese relé (redes distintas); sin él, sólo red local.
var qrFile = args.Length > 0 ? args[0] : "qr.txt";
var seconds = args.Length > 1 && int.TryParse(args[1], out var sec) ? sec : 60;
var directAt = Array.IndexOf(args, "--direct");
IPEndPoint? direct = directAt >= 0 ? IPEndPoint.Parse(args[directAt + 1]) : null;
var relayAt = Array.IndexOf(args, "--relay");
var store = new MemoryPcStore("pc-simulada", "PC simulada");
var module = new SimModule();
await using var link = new PcLink(store, new CommandRegistry([module]), s => Console.WriteLine("[pc] " + s)) { BeaconInterval = TimeSpan.FromMilliseconds(300), RelayUrl = relayAt >= 0 ? args[relayAt + 1] : null };
link.ExtraTargets.Add(new IPEndPoint(IPAddress.Loopback, LucyRemota.Core.Protocol.Proto.DiscoveryPort));
var w = link.StartPairing();
File.WriteAllText(qrFile + ".tmp", w.QrPayload(store.PcId, store.PcName, store.Key.PublicSpki));
File.Move(qrFile + ".tmp", qrFile, true);
link.Start();
var until = DateTime.UtcNow.AddSeconds(seconds);
Task attempt = Task.CompletedTask;
while (DateTime.UtcNow < until)
{
    if (direct != null && link.Connected.Count == 0 && attempt.IsCompleted) attempt = link.ConnectAsync(direct, CancellationToken.None);
    await Task.Delay(1500);
}
Console.WriteLine($"[pc] ejecuciones: {module.Executions}, volumen final: {module.Volume}");

sealed class SimModule : ISkillModule
{
    public int Volume = 50;
    public int Executions;
    public string Name => "audio";
    public IReadOnlyList<IntentSpec> Intents { get; } =
    [
        new("SET_VOLUME", "audio", "Cambia el volumen.", Risk.Change,
            new Dictionary<string, ParamSpec> { ["level"] = new(ParamType.Int, Min: 0, Max: 100), ["delta"] = new(ParamType.Int, Min: -100, Max: 100) }, OneOf: ["level", "delta"]),
        new("SHUTDOWN", "system", "¿Apago la computadora?", Risk.Sensitive),
    ];

    public Task<Outcome> ExecuteAsync(string intent, Params p, CancellationToken ct)
    {
        Interlocked.Increment(ref Executions);
        if (intent == "SHUTDOWN") return Task.FromResult(Outcome.Ok("(simulado) apagando"));
        Volume = Math.Clamp(p.Int("level") ?? Volume + p.Int("delta")!.Value, 0, 100);
        return Task.FromResult(Outcome.Ok($"Volumen al {Volume} %", new JsonObject { ["level"] = Volume }));
    }
}
