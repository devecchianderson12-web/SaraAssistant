using System.Text.Json.Nodes;
using LucyRemota.Core.Commands;
using LucyRemota.Native;

namespace LucyRemota.Modules;

/// <summary>Volumen y silencio de Windows.</summary>
sealed class AudioModule : ISkillModule
{
    public string Name => "audio";

    public IReadOnlyList<IntentSpec> Intents { get; } =
    [
        new("SET_VOLUME", "audio", "Cambia el volumen general: a un nivel (0–100) o sube/baja puntos.", Risk.Change,
            M.P(("level", new(ParamType.Int, Min: 0, Max: 100)), ("delta", new(ParamType.Int, Min: -100, Max: 100))), OneOf: ["level", "delta"]),
        new("MUTE_AUDIO", "audio", "Silencia o quita el silencio.", Risk.Change, M.P(("muted", new(ParamType.Bool, Required: true)))),
        new("GET_VOLUME", "audio", "Dice el volumen actual.", Risk.Info),
    ];

    public Task<Outcome> ExecuteAsync(string intent, Params p, CancellationToken ct) => Task.Run(() => intent switch
    {
        "SET_VOLUME" => SetVolume(p),
        "MUTE_AUDIO" => p.Bool("muted")!.Value
            ? (SystemVolume.SetMuted(true) ? Outcome.Ok("Listo, silencié la computadora.", new JsonObject { ["muted"] = true }) : Outcome.Fail("Windows no la silenció."))
            : (!SystemVolume.SetMuted(false) ? Outcome.Ok($"Listo, volvió el sonido (volumen al {SystemVolume.Get()} %).", new JsonObject { ["muted"] = false }) : Outcome.Fail("Windows no quitó el silencio.")),
        _ => Outcome.Ok(SystemVolume.Muted() ? $"Está silenciada (volumen al {SystemVolume.Get()} %)." : $"El volumen está al {SystemVolume.Get()} %.",
            new JsonObject { ["level"] = SystemVolume.Get(), ["muted"] = SystemVolume.Muted() }),
    }, ct);

    static Outcome SetVolume(Params p)
    {
        var before = SystemVolume.Get();
        var target = p.Int("level") ?? before + p.Int("delta")!.Value;
        var after = SystemVolume.Set(Math.Clamp(target, 0, 100));
        var msg = p.Has("level") ? $"Listo, volumen al {after} %."
            : after > before ? $"Listo, subí el volumen al {after} %."
            : after < before ? $"Listo, bajé el volumen al {after} %."
            : after >= 100 ? "Ya está al máximo." : after <= 0 ? "Ya está al mínimo." : $"El volumen quedó al {after} %.";
        return Outcome.Ok(msg, new JsonObject { ["level"] = after, ["previous"] = before });
    }
}
