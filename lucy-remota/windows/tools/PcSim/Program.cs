using System.Net;
using System.Text.Json.Nodes;
using LucyRemota.Core.Commands;
using LucyRemota.Core.Link;

// Uso: PcSim <archivo-para-el-QR> [segundos]
// Abre una vinculación, escribe el contenido del QR en el archivo y atiende al teléfono un rato.
var qrFile = args.Length > 0 ? args[0] : "qr.txt";
var seconds = args.Length > 1 ? int.Parse(args[1]) : 60;
var store = new MemoryPcStore("pc-simulada", "PC simulada");
var module = new SimModule();
await using var link = new PcLink(store, new CommandRegistry([module]), s => Console.WriteLine("[pc] " + s)) { BeaconInterval = TimeSpan.FromMilliseconds(300) };
link.ExtraTargets.Add(new IPEndPoint(IPAddress.Loopback, LucyRemota.Core.Protocol.Proto.DiscoveryPort));
var w = link.StartPairing();
File.WriteAllText(qrFile + ".tmp", w.QrPayload(store.PcId, store.PcName, store.Key.PublicSpki));
File.Move(qrFile + ".tmp", qrFile, true);
link.Start();
await Task.Delay(TimeSpan.FromSeconds(seconds));
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
