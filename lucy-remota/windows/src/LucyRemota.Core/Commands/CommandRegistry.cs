using System.Collections.Concurrent;
using System.Text.Json.Nodes;
using LucyRemota.Core.Protocol;

namespace LucyRemota.Core.Commands;

/// <summary>Una orden recibida, para el historial de la ventana.</summary>
public sealed record CommandRecord(DateTimeOffset At, string Device, string CommandId, string Intent, string ParamsJson, string Status, string Message);

/// <summary>
/// Recibe las órdenes ya autenticadas y las lleva hasta un módulo: valida protocolo, intención y
/// parámetros, descarta las vencidas y las repetidas (devuelve el mismo resultado sin ejecutar dos
/// veces) y pide confirmación para las sensibles. Nunca ejecuta texto: sólo funciones declaradas.
/// </summary>
public sealed class CommandRegistry
{
    readonly Dictionary<string, (IntentSpec spec, ISkillModule module)> intents = new(StringComparer.Ordinal);
    readonly ConcurrentDictionary<string, (DateTimeOffset at, JsonObject final)> done = new();
    readonly ConcurrentDictionary<string, byte> running = new();
    readonly TimeProvider clock;
    readonly TimeSpan executionTimeout;

    /// <summary>Cuánto se recuerdan las órdenes ya hechas (para no repetirlas si llegan de nuevo).</summary>
    public static readonly TimeSpan DedupeWindow = TimeSpan.FromMinutes(10);
    public const int MaxExpiryMs = 120_000;
    public const int DefaultExpiryMs = 10_000;

    public event Action<CommandRecord>? Logged;

    public CommandRegistry(IEnumerable<ISkillModule> modules, TimeProvider? clock = null, TimeSpan? executionTimeout = null)
    {
        this.clock = clock ?? TimeProvider.System;
        this.executionTimeout = executionTimeout ?? TimeSpan.FromSeconds(25);
        foreach (var m in modules)
            foreach (var spec in m.Intents)
                if (!intents.TryAdd(spec.Name, (spec, m))) throw new InvalidOperationException($"intención repetida: {spec.Name}");
    }

    public IEnumerable<IntentSpec> Catalog => intents.Values.Select(x => x.spec).OrderBy(s => s.Module).ThenBy(s => s.Name);

    public static JsonObject StatusMessage(string commandId, string status, string message = "", JsonObject? result = null, string? code = null)
    {
        var o = new JsonObject { ["t"] = "status", ["command_id"] = commandId, ["status"] = status, ["message"] = message };
        if (result != null) o["result"] = result;
        if (code != null) o["code"] = code;
        return o;
    }

    /// <summary>
    /// Procesa una orden. [send] manda cada estado al teléfono (received → processing → final).
    /// [clockOffsetMs] = hora de la PC − hora del teléfono (medida al conectarse).
    /// </summary>
    public async Task HandleAsync(string device, JsonObject cmd, long clockOffsetMs, Func<JsonObject, Task> send, CancellationToken ct)
    {
        var id = cmd.Str("command_id");
        if (id is null || id.Length is < 8 or > 64 || !id.All(c => char.IsAsciiLetterOrDigit(c) || c == '-'))
        {
            await send(StatusMessage(id ?? "", Status.Invalid, "La orden no tiene un identificador válido.", code: "bad_id"));
            return;
        }
        Prune();
        // Repetida: ya hecha → el mismo resultado; en curso → «processing». Nunca se ejecuta dos veces.
        if (done.TryGetValue(id, out var previous))
        {
            await send((JsonObject)previous.final.DeepClone());
            return;
        }
        if (!running.TryAdd(id, 0))
        {
            await send(StatusMessage(id, Status.Processing, "Ya la estoy haciendo."));
            return;
        }
        var intentName = cmd.Str("intent") ?? "";
        var paramsJson = cmd.Obj("params")?.ToJsonString() ?? "{}";
        JsonObject final;
        try
        {
            await send(StatusMessage(id, Status.Received));
            final = await DecideAsync(id, intentName, cmd, clockOffsetMs, send, ct);
        }
        catch (OperationCanceledException) when (!ct.IsCancellationRequested)
        {
            final = StatusMessage(id, Status.Error, "La acción tardó demasiado.", code: "timeout");
        }
        catch (Exception e) when (e is not OperationCanceledException)
        {
            final = StatusMessage(id, Status.Error, "No pude hacerlo: " + e.Message, code: "exception");
        }
        finally
        {
            running.TryRemove(id, out _);
        }
        done[id] = (clock.GetUtcNow(), final);
        Logged?.Invoke(new CommandRecord(clock.GetUtcNow(), device, id, intentName, paramsJson, final.Str("status") ?? "", final.Str("message") ?? ""));
        await send(final);
    }

    async Task<JsonObject> DecideAsync(string id, string intentName, JsonObject cmd, long clockOffsetMs, Func<JsonObject, Task> send, CancellationToken ct)
    {
        var v = cmd.Long("v") ?? Proto.Major;
        if (v != Proto.Major) return StatusMessage(id, Status.Unsupported, "La versión del teléfono no es compatible: actualizá Lucy Remota.", code: "protocol_version");
        // Vencida: llegó tarde (reconexión, red lenta): no se hace algo que ya no se espera.
        var sentAt = cmd.Long("sent_at");
        var expiry = Math.Clamp(cmd.Long("expires_ms") ?? DefaultExpiryMs, 1_000, MaxExpiryMs);
        if (sentAt != null)
        {
            var age = clock.GetUtcNow().ToUnixTimeMilliseconds() - (sentAt.Value + clockOffsetMs);
            if (age > expiry) return StatusMessage(id, Status.Expired, "La orden llegó tarde y no la hice.", code: "expired");
        }
        if (!intents.TryGetValue(intentName, out var entry))
            return StatusMessage(id, Status.Unsupported, "Eso todavía no lo sé hacer en la computadora.", code: "unknown_intent");
        var (ok, error) = Validator.Validate(entry.spec, cmd.Obj("params"));
        if (ok == null) return StatusMessage(id, Status.Invalid, "La orden vino mal: " + error, code: "bad_params");
        if (entry.spec.Risk == Risk.Sensitive && cmd.Bool("confirmed") != true)
            return StatusMessage(id, Status.NeedsConfirmation, entry.spec.Description, code: "confirm");
        await send(StatusMessage(id, Status.Processing));
        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(ct);
        timeout.CancelAfter(executionTimeout);
        var outcome = await entry.module.ExecuteAsync(intentName, ok, timeout.Token);
        return StatusMessage(id, outcome.Status, outcome.Message, outcome.Result, outcome.Code);
    }

    void Prune()
    {
        var limit = clock.GetUtcNow() - DedupeWindow;
        foreach (var (k, v) in done)
            if (v.at < limit) done.TryRemove(k, out _);
    }
}
