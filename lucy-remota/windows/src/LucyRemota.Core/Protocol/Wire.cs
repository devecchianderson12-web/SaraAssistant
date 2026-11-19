using System.Buffers.Binary;
using System.Text;
using System.Text.Json.Nodes;

namespace LucyRemota.Core.Protocol;

/// <summary>Constantes del protocolo Lucy Remota (ver lucy-remota/protocol/PROTOCOLO.md).</summary>
public static class Proto
{
    /// <summary>Versión mayor: cambia sólo si una versión vieja ya no puede entender a la nueva.</summary>
    public const int Major = 1;
    /// <summary>Versión menor: agrega intenciones o campos opcionales sin romper nada.</summary>
    public const int Minor = 0;
    /// <summary>Puerto UDP donde el teléfono escucha los anuncios de la PC.</summary>
    public const int DiscoveryPort = 47801;
    /// <summary>Puerto TCP habitual del teléfono (puede anunciar otro).</summary>
    public const int PhonePort = 47800;
    public const int MaxFrame = 256 * 1024;
    public const string Label = "lucy-remota/1";
}

/// <summary>Marcos de longitud fija: 4 bytes (big endian) con el largo y después los datos.</summary>
public static class Wire
{
    public static async Task WriteFrameAsync(Stream s, ReadOnlyMemory<byte> payload, CancellationToken ct)
    {
        if (payload.Length > Proto.MaxFrame) throw new ProtocolException("marco demasiado grande");
        var header = new byte[4];
        BinaryPrimitives.WriteInt32BigEndian(header, payload.Length);
        await s.WriteAsync(header, ct).ConfigureAwait(false);
        await s.WriteAsync(payload, ct).ConfigureAwait(false);
        await s.FlushAsync(ct).ConfigureAwait(false);
    }

    public static async Task<byte[]> ReadFrameAsync(Stream s, CancellationToken ct)
    {
        var header = await ReadExactlyAsync(s, 4, ct).ConfigureAwait(false);
        var len = BinaryPrimitives.ReadInt32BigEndian(header);
        if (len < 0 || len > Proto.MaxFrame) throw new ProtocolException($"largo de marco inválido: {len}");
        return await ReadExactlyAsync(s, len, ct).ConfigureAwait(false);
    }

    static async Task<byte[]> ReadExactlyAsync(Stream s, int n, CancellationToken ct)
    {
        var buf = new byte[n];
        var read = 0;
        while (read < n)
        {
            var r = await s.ReadAsync(buf.AsMemory(read, n - read), ct).ConfigureAwait(false);
            if (r == 0) throw new EndOfStreamException("la conexión se cerró");
            read += r;
        }
        return buf;
    }

    public static byte[] Json(JsonObject o) => Encoding.UTF8.GetBytes(o.ToJsonString());

    public static JsonObject ParseObject(byte[] data)
    {
        try
        {
            return JsonNode.Parse(data) as JsonObject ?? throw new ProtocolException("se esperaba un objeto JSON");
        }
        catch (System.Text.Json.JsonException e)
        {
            throw new ProtocolException("JSON inválido: " + e.Message);
        }
    }
}

public sealed class ProtocolException(string message) : Exception(message);

/// <summary>Lecturas seguras de campos JSON (nunca tiran excepciones de tipo).</summary>
public static class J
{
    public static string? Str(this JsonObject o, string key) =>
        o.TryGetPropertyValue(key, out var v) && v is JsonValue jv && jv.TryGetValue<string>(out var s) ? s : null;

    public static long? Long(this JsonObject o, string key)
    {
        if (!o.TryGetPropertyValue(key, out var v) || v is not JsonValue jv) return null;
        if (jv.TryGetValue<long>(out var l)) return l;
        if (jv.TryGetValue<double>(out var d) && d == Math.Floor(d) && Math.Abs(d) < 9e15) return (long)d;
        return null;
    }

    public static bool? Bool(this JsonObject o, string key) =>
        o.TryGetPropertyValue(key, out var v) && v is JsonValue jv && jv.TryGetValue<bool>(out var b) ? b : null;

    public static JsonObject? Obj(this JsonObject o, string key) =>
        o.TryGetPropertyValue(key, out var v) ? v as JsonObject : null;

    public static byte[]? B64(this JsonObject o, string key)
    {
        var s = o.Str(key);
        if (s == null) return null;
        try { return Convert.FromBase64String(s); } catch (FormatException) { return null; }
    }

    public static string B64(byte[] data) => Convert.ToBase64String(data);

    public static string B64Url(byte[] data) => Convert.ToBase64String(data).TrimEnd('=').Replace('+', '-').Replace('/', '_');

    public static byte[] FromB64Url(string s)
    {
        var t = s.Replace('-', '+').Replace('_', '/');
        return Convert.FromBase64String(t.PadRight(t.Length + (4 - t.Length % 4) % 4, '='));
    }
}
