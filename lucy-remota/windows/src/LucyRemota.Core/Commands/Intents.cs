using System.Text.Json.Nodes;

namespace LucyRemota.Core.Commands;

/// <summary>Riesgo de una acción: las sensibles (apagar, reiniciar) piden confirmación en el teléfono.</summary>
public enum Risk { Info, Change, Sensitive }

public enum ParamType { Int, String, Bool, Enum }

/// <summary>Un parámetro permitido de una intención, con sus límites.</summary>
public sealed record ParamSpec(
    ParamType Type,
    bool Required = false,
    int? Min = null,
    int? Max = null,
    int MaxLength = 200,
    IReadOnlyList<string>? Options = null,
    string Description = "")
{
    public JsonObject ToJson()
    {
        var o = new JsonObject { ["type"] = Type.ToString().ToLowerInvariant(), ["required"] = Required };
        if (Min != null) o["min"] = Min;
        if (Max != null) o["max"] = Max;
        if (Type == ParamType.String) o["max_length"] = MaxLength;
        if (Options != null) o["options"] = new JsonArray(Options.Select(x => (JsonNode)x).ToArray());
        if (Description.Length > 0) o["description"] = Description;
        return o;
    }
}

/// <summary>
/// Una intención que la PC sabe ejecutar: nombre (SET_VOLUME…), módulo, riesgo, parámetros y la
/// versión del protocolo desde la que existe. Se publica al teléfono al conectarse.
/// </summary>
public sealed record IntentSpec(
    string Name,
    string Module,
    string Description,
    Risk Risk = Risk.Change,
    IReadOnlyDictionary<string, ParamSpec>? Params = null,
    int Since = 1,
    /// <summary>Al menos uno de estos parámetros tiene que venir (p. ej. «level» o «delta»).</summary>
    IReadOnlyList<string>? OneOf = null)
{
    public IReadOnlyDictionary<string, ParamSpec> Parameters => Params ?? new Dictionary<string, ParamSpec>();

    public JsonObject ToJson()
    {
        var p = new JsonObject();
        foreach (var (k, v) in Parameters) p[k] = v.ToJson();
        var o = new JsonObject
        {
            ["intent"] = Name, ["module"] = Module, ["description"] = Description,
            ["risk"] = Risk.ToString().ToLowerInvariant(), ["params"] = p, ["since"] = Since,
        };
        if (OneOf != null) o["one_of"] = new JsonArray(OneOf.Select(x => (JsonNode)x).ToArray());
        return o;
    }
}

/// <summary>Parámetros ya validados (tipos y límites comprobados).</summary>
public sealed class Params(IReadOnlyDictionary<string, object> values)
{
    public static readonly Params Empty = new(new Dictionary<string, object>());
    public bool Has(string k) => values.ContainsKey(k);
    public int? Int(string k) => values.TryGetValue(k, out var v) ? (int)v : null;
    public string? Str(string k) => values.TryGetValue(k, out var v) ? (string)v : null;
    public bool? Bool(string k) => values.TryGetValue(k, out var v) ? (bool)v : null;
    public IReadOnlyDictionary<string, object> All => values;
}

/// <summary>Estados de una orden (los mismos en los dos lados).</summary>
public static class Status
{
    public const string Received = "received";
    public const string Processing = "processing";
    public const string Success = "success";
    public const string Error = "error";
    public const string Unsupported = "unsupported";
    public const string Unauthorized = "unauthorized";
    public const string Invalid = "invalid";
    public const string Expired = "expired";
    public const string NeedsConfirmation = "needs_confirmation";
    public const string Busy = "busy";

    public static bool IsFinal(string s) => s is not (Received or Processing);
}

/// <summary>Resultado de ejecutar una orden en Windows.</summary>
public sealed record Outcome(string Status, string Message, JsonObject? Result = null, string? Code = null)
{
    public static Outcome Ok(string message, JsonObject? result = null) => new(Commands.Status.Success, message, result);
    public static Outcome Fail(string message, string? code = null) => new(Commands.Status.Error, message, null, code);
}

/// <summary>Un módulo de habilidades (Audio, Aplicaciones, Ventanas…): declara y ejecuta sus intenciones.</summary>
public interface ISkillModule
{
    string Name { get; }
    IReadOnlyList<IntentSpec> Intents { get; }
    Task<Outcome> ExecuteAsync(string intent, Params p, CancellationToken ct);
}

/// <summary>Validación estricta: sólo pasan parámetros declarados, del tipo y dentro de los límites.</summary>
public static class Validator
{
    public static (Params? ok, string? error) Validate(IntentSpec spec, JsonObject? raw)
    {
        raw ??= new JsonObject();
        var values = new Dictionary<string, object>();
        foreach (var (name, node) in raw)
        {
            if (!spec.Parameters.TryGetValue(name, out var p)) return (null, $"parámetro desconocido «{name}»");
            if (node is null) continue;
            if (node is not JsonValue v) return (null, $"«{name}» tiene un formato inválido");
            switch (p.Type)
            {
                case ParamType.Int:
                    long n;
                    if (v.TryGetValue<long>(out var l)) n = l;
                    else if (v.TryGetValue<double>(out var d) && d == Math.Floor(d) && Math.Abs(d) < int.MaxValue) n = (long)d;
                    else return (null, $"«{name}» tiene que ser un número entero");
                    if (n < (p.Min ?? int.MinValue) || n > (p.Max ?? int.MaxValue)) return (null, $"«{name}» fuera de rango ({p.Min}…{p.Max})");
                    values[name] = (int)n;
                    break;
                case ParamType.Bool:
                    if (!v.TryGetValue<bool>(out var b)) return (null, $"«{name}» tiene que ser verdadero o falso");
                    values[name] = b;
                    break;
                case ParamType.String:
                case ParamType.Enum:
                    if (!v.TryGetValue<string>(out var s)) return (null, $"«{name}» tiene que ser texto");
                    s = s.Trim();
                    if (s.Length == 0) continue;
                    if (s.Length > p.MaxLength) return (null, $"«{name}» es demasiado largo");
                    if (s.Any(char.IsControl)) return (null, $"«{name}» tiene caracteres no permitidos");
                    if (p.Type == ParamType.Enum && !(p.Options ?? []).Contains(s)) return (null, $"«{name}» no admite «{s}»");
                    values[name] = s;
                    break;
            }
        }
        foreach (var (name, p) in spec.Parameters)
            if (p.Required && !values.ContainsKey(name)) return (null, $"falta «{name}»");
        if (spec.OneOf != null && !spec.OneOf.Any(values.ContainsKey))
            return (null, "falta uno de: " + string.Join(", ", spec.OneOf));
        return (new Params(values), null);
    }
}
