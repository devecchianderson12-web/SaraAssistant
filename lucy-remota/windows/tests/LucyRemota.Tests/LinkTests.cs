using System.Net;
using System.Text.Json.Nodes;
using LucyRemota.Core.Commands;
using LucyRemota.Core.Link;
using LucyRemota.Core.Protocol;
using LucyRemota.Core.Security;
using Xunit;

namespace LucyRemota.Tests;

/// <summary>Módulo de prueba: cuenta cuántas veces se ejecutó cada cosa.</summary>
sealed class TestModule : ISkillModule
{
    public int Volume = 50;
    public int Executions;
    public string Name => "audio";
    public IReadOnlyList<IntentSpec> Intents { get; } =
    [
        new("SET_VOLUME", "audio", "Cambia el volumen.", Risk.Change,
            new Dictionary<string, ParamSpec> { ["level"] = new(ParamType.Int, Min: 0, Max: 100), ["delta"] = new(ParamType.Int, Min: -100, Max: 100) },
            OneOf: ["level", "delta"]),
        new("SHUTDOWN", "system", "¿Apago la computadora?", Risk.Sensitive),
        new("SLOW", "system", "Tarda.", Risk.Info),
    ];

    public async Task<Outcome> ExecuteAsync(string intent, Params p, CancellationToken ct)
    {
        Interlocked.Increment(ref Executions);
        switch (intent)
        {
            case "SET_VOLUME":
                Volume = Math.Clamp(p.Int("level") ?? Volume + p.Int("delta")!.Value, 0, 100);
                return Outcome.Ok($"Volumen al {Volume} %", new JsonObject { ["level"] = Volume });
            case "SLOW":
                await Task.Delay(400, ct);
                return Outcome.Ok("Listo");
            default:
                return Outcome.Ok("Apagando");
        }
    }
}

public class LinkTests
{
    static (PcLink link, MemoryPcStore store, TestModule module) NewPc(PhoneSimulator phone, List<string>? logs = null)
    {
        var store = new MemoryPcStore("pc-prueba-1", "Compu de prueba");
        var module = new TestModule();
        var link = new PcLink(store, new CommandRegistry([module]), s => logs?.Add(s), discoveryPort: phone.UdpPort)
        {
            BeaconInterval = TimeSpan.FromMilliseconds(100),
        };
        link.ExtraTargets.Add(new IPEndPoint(IPAddress.Loopback, phone.UdpPort));
        return (link, store, module);
    }

    static void ScanQr(PhoneSimulator phone, PcLink link, MemoryPcStore store)
    {
        var w = link.StartPairing();
        var uri = new Uri(w.QrPayload(store.PcId, store.PcName, store.Key.PublicSpki));
        var q = System.Web.HttpUtility.ParseQueryString(uri.Query);
        phone.PendingQr = (q["pc"]!, J.FromB64Url(q["f"]!), J.FromB64Url(q["t"]!));
    }

    [Fact]
    public async Task Vincula_con_QR_y_ejecuta_una_orden()
    {
        await using var phone = new PhoneSimulator();
        var (link, store, module) = NewPc(phone);
        await using var _ = link;
        ScanQr(phone, link, store);
        link.Start();
        Assert.True(await phone.WaitConnectedAsync(TimeSpan.FromSeconds(5)));
        Assert.Single(store.Devices);
        Assert.Null(link.Pairing); // la ventana se cierra al vincular
        var intents = phone.Capabilities!["intents"]!.AsArray().Select(i => i!["intent"]!.GetValue<string>()).ToList();
        Assert.Contains("SET_VOLUME", intents);

        var statuses = await phone.CommandAsync("SET_VOLUME", new JsonObject { ["level"] = 70 });
        Assert.Equal(["received", "processing", "success"], statuses.Select(s => s.Str("status")!));
        Assert.Equal(70, statuses[^1]["result"]!["level"]!.GetValue<int>());
        Assert.Equal(70, module.Volume);
    }

    [Fact]
    public async Task Vincula_con_codigo_manual_y_rechaza_un_codigo_equivocado()
    {
        await using var wrong = new PhoneSimulator();
        var (link, store, _) = NewPc(wrong);
        await using var __ = link;
        var w = link.StartPairing();
        wrong.PendingCode = "ZZZZZZZZ";
        link.Start();
        await Task.Delay(1500);
        Assert.Empty(store.Devices);
        Assert.Equal("bad_pairing_code", wrong.LastFailure);

        await using var right = new PhoneSimulator();
        right.PendingCode = PairingCodes.Pretty(w.ManualCode).ToLowerInvariant(); // como lo escribiría alguien
        link.ExtraTargets.Add(new IPEndPoint(IPAddress.Loopback, right.UdpPort));
        wrong.AnswerBeacons = false;
        await link.ConnectAsyncForTest(right);
        Assert.Single(store.Devices);
    }

    [Fact]
    public async Task Reconecta_sin_codigo_y_rechaza_un_telefono_desconocido_o_con_otra_clave()
    {
        await using var phone = new PhoneSimulator();
        var (link, store, _) = NewPc(phone);
        await using var _ = link;
        ScanQr(phone, link, store);
        link.Start();
        Assert.True(await phone.WaitConnectedAsync(TimeSpan.FromSeconds(5)));

        // Mismo id pero otra clave (alguien copiando el id): no entra.
        await using var impostor = new PhoneSimulator();
        impostor.Paired = phone.Paired;
        impostor.Id = phone.Id;
        await link.ConnectAsyncForTest(impostor);
        Assert.Equal("unauthorized", impostor.LastFailure);
    }

    [Fact]
    public async Task No_repite_ordenes_duplicadas_y_descarta_las_vencidas()
    {
        await using var phone = new PhoneSimulator();
        var (link, store, module) = NewPc(phone);
        await using var _ = link;
        ScanQr(phone, link, store);
        link.Start();
        Assert.True(await phone.WaitConnectedAsync(TimeSpan.FromSeconds(5)));

        var id = Guid.NewGuid().ToString();
        var first = await phone.CommandAsync("SET_VOLUME", new JsonObject { ["delta"] = 10 }, id);
        var again = await phone.CommandAsync("SET_VOLUME", new JsonObject { ["delta"] = 10 }, id);
        Assert.Equal("success", first[^1].Str("status"));
        Assert.Equal("success", again[^1].Str("status"));
        Assert.Single(again); // sólo el resultado guardado
        Assert.Equal(60, module.Volume); // se sumó una sola vez
        Assert.Equal(1, module.Executions);

        var old = await phone.CommandAsync("SET_VOLUME", new JsonObject { ["level"] = 5 }, sentAt: DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() - 60_000);
        Assert.Equal("expired", old[^1].Str("status"));
        Assert.Equal(60, module.Volume);
    }

    [Fact]
    public async Task Valida_intenciones_parametros_y_pide_confirmacion()
    {
        await using var phone = new PhoneSimulator();
        var (link, store, module) = NewPc(phone);
        await using var _ = link;
        ScanQr(phone, link, store);
        link.Start();
        Assert.True(await phone.WaitConnectedAsync(TimeSpan.FromSeconds(5)));

        Assert.Equal("unsupported", (await phone.CommandAsync("FORMAT_DISK"))[^1].Str("status"));
        Assert.Equal("invalid", (await phone.CommandAsync("SET_VOLUME", new JsonObject { ["level"] = 300 }))[^1].Str("status"));
        Assert.Equal("invalid", (await phone.CommandAsync("SET_VOLUME", new JsonObject { ["level"] = "70; rm -rf" }))[^1].Str("status"));
        Assert.Equal("invalid", (await phone.CommandAsync("SET_VOLUME", new JsonObject { ["cmd"] = "calc.exe" }))[^1].Str("status"));
        Assert.Equal("invalid", (await phone.CommandAsync("SET_VOLUME"))[^1].Str("status")); // falta level o delta
        Assert.Equal("needs_confirmation", (await phone.CommandAsync("SHUTDOWN"))[^1].Str("status"));
        Assert.Equal(0, module.Executions);
        Assert.Equal("success", (await phone.CommandAsync("SHUTDOWN", confirmed: true))[^1].Str("status"));
        Assert.Equal(1, module.Executions);
    }

    [Fact]
    public async Task Un_mensaje_alterado_corta_la_conexion_y_se_reconecta_sola()
    {
        await using var phone = new PhoneSimulator();
        var logs = new List<string>();
        var (link, store, _) = NewPc(phone, logs);
        await using var _ = link;
        ScanQr(phone, link, store);
        link.Start();
        Assert.True(await phone.WaitConnectedAsync(TimeSpan.FromSeconds(5)));
        Assert.Single(link.Connected);
        await phone.SendRaw(new byte[40]); // basura: no descifra
        for (var i = 0; i < 30 && !logs.Any(l => l.Contains("alterado")); i++) await Task.Delay(100);
        Assert.Contains(logs, l => l.Contains("alterado"));
        // Después vuelve a conectarse sola (sesión nueva, claves nuevas).
        for (var i = 0; i < 30 && logs.Count(l => l.StartsWith("Conectada")) < 2; i++) await Task.Delay(100);
        Assert.Equal(2, logs.Count(l => l.StartsWith("Conectada")));
    }

    [Fact]
    public async Task Desvincular_desde_la_pc_borra_el_telefono_y_le_avisa()
    {
        await using var phone = new PhoneSimulator();
        var (link, store, _) = NewPc(phone);
        await using var _ = link;
        ScanQr(phone, link, store);
        link.Start();
        Assert.True(await phone.WaitConnectedAsync(TimeSpan.FromSeconds(5)));
        await link.UnpairAsync(phone.Id);
        Assert.Empty(store.Devices);
        await Task.Delay(300);
        Assert.Empty(link.Connected);
    }
}

static class LinkTestExtensions
{
    public static Task ConnectAsyncForTest(this PcLink link, PhoneSimulator phone) =>
        link.ConnectAsync(new IPEndPoint(IPAddress.Loopback, phone.Port), CancellationToken.None).WaitAsync(TimeSpan.FromSeconds(3)).ContinueWith(_ => { });
}
