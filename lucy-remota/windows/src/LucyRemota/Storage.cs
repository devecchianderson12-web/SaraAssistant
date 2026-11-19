using System.Security.Cryptography;
using System.Text.Json;
using System.Text.Json.Nodes;
using LucyRemota.Core.Commands;
using LucyRemota.Core.Link;
using LucyRemota.Core.Security;
using Microsoft.Win32;

namespace LucyRemota;

/// <summary>
/// Carpeta de datos: junto al .exe («LucyRemota-datos», así funciona desde un pendrive) o, si ahí no
/// se puede escribir, en %LOCALAPPDATA%\LucyRemota. Nunca usa la carpeta de la otra Lucy.
/// </summary>
static class Paths
{
    public static readonly string Data = Pick();

    static string Pick()
    {
        var exeDir = Path.GetDirectoryName(Environment.ProcessPath) ?? AppContext.BaseDirectory;
        var portable = Path.Combine(exeDir, "LucyRemota-datos");
        try
        {
            Directory.CreateDirectory(portable);
            var probe = Path.Combine(portable, ".escritura");
            System.IO.File.WriteAllText(probe, "ok");
            System.IO.File.Delete(probe);
            return portable;
        }
        catch (Exception)
        {
            var local = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "LucyRemota");
            Directory.CreateDirectory(local);
            return local;
        }
    }

    public static string File(string name) => Path.Combine(Data, name);

    /// <summary>Escribe primero en un temporal y después reemplaza (si se corta la luz no queda roto).</summary>
    public static void WriteSafely(string path, string text)
    {
        var tmp = path + ".tmp";
        System.IO.File.WriteAllText(tmp, text);
        System.IO.File.Move(tmp, path, overwrite: true);
    }
}

/// <summary>Identidad de la PC (clave cifrada con tu usuario de Windows) y teléfonos vinculados.</summary>
sealed class PcStore : IPcStore
{
    readonly object gate = new();
    List<PairedDevice> devices;

    public string PcId { get; }
    public string PcName { get; }
    public IdentityKey Key { get; }

    public PcStore()
    {
        PcName = Environment.MachineName;
        var identityFile = Paths.File("identidad.json");
        (string id, IdentityKey key)? loaded = null;
        try
        {
            if (File.Exists(identityFile))
            {
                var j = JsonNode.Parse(File.ReadAllText(identityFile))!.AsObject();
                var sealedKey = Convert.FromBase64String(j["key"]!.GetValue<string>());
                // DPAPI: sólo este usuario en esta PC puede abrirla. En otra PC (pendrive) se crea una nueva.
                var pkcs8 = ProtectedData.Unprotect(sealedKey, null, DataProtectionScope.CurrentUser);
                loaded = (j["pc_id"]!.GetValue<string>(), IdentityKey.FromPkcs8(pkcs8));
            }
        }
        catch (Exception) { loaded = null; }
        if (loaded is { } l)
        {
            PcId = l.id;
            Key = l.key;
            devices = LoadDevices();
        }
        else
        {
            PcId = "pc-" + Convert.ToHexString(RandomNumberGenerator.GetBytes(8)).ToLowerInvariant();
            Key = IdentityKey.Create();
            var sealedKey = ProtectedData.Protect(Key.ExportPkcs8(), null, DataProtectionScope.CurrentUser);
            Paths.WriteSafely(identityFile, new JsonObject { ["pc_id"] = PcId, ["key"] = Convert.ToBase64String(sealedKey) }.ToJsonString());
            // Identidad nueva: los teléfonos vinculados a la anterior tienen que volver a vincularse.
            devices = new List<PairedDevice>();
            SaveDevices();
        }
    }

    public IReadOnlyList<PairedDevice> Devices { get { lock (gate) return devices.ToList(); } }

    public void Upsert(PairedDevice device)
    {
        lock (gate)
        {
            devices.RemoveAll(d => d.Id == device.Id);
            devices.Add(device);
            SaveDevices();
        }
    }

    public bool Remove(string deviceId)
    {
        lock (gate)
        {
            var removed = devices.RemoveAll(d => d.Id == deviceId) > 0;
            if (removed) SaveDevices();
            return removed;
        }
    }

    List<PairedDevice> LoadDevices()
    {
        try
        {
            var arr = JsonNode.Parse(File.ReadAllText(Paths.File("telefonos.json")))!.AsArray();
            return arr.Select(n => n!.AsObject()).Select(o => new PairedDevice(
                o["id"]!.GetValue<string>(), o["name"]!.GetValue<string>(), Convert.FromBase64String(o["key"]!.GetValue<string>()),
                DateTimeOffset.Parse(o["paired_at"]!.GetValue<string>()),
                o["last_seen"]?.GetValue<string>() is { } ls ? DateTimeOffset.Parse(ls) : null, o["last_address"]?.GetValue<string>())).ToList();
        }
        catch (Exception) { return new List<PairedDevice>(); }
    }

    void SaveDevices()
    {
        var arr = new JsonArray(devices.Select(d => (JsonNode)new JsonObject
        {
            ["id"] = d.Id, ["name"] = d.Name, ["key"] = Convert.ToBase64String(d.PublicKey), ["paired_at"] = d.PairedAt.ToString("O"),
            ["last_seen"] = d.LastSeen?.ToString("O"), ["last_address"] = d.LastAddress,
        }).ToArray());
        Paths.WriteSafely(Paths.File("telefonos.json"), arr.ToJsonString(new JsonSerializerOptions { WriteIndented = true }));
    }
}

/// <summary>Historial de órdenes (las últimas 300) y registro de diagnóstico.</summary>
sealed class History
{
    readonly object gate = new();
    readonly List<CommandRecord> items;
    public event Action<CommandRecord>? Added;

    public History()
    {
        try { items = JsonSerializer.Deserialize<List<CommandRecord>>(File.ReadAllText(Paths.File("historial.json"))) ?? new(); }
        catch (Exception) { items = new(); }
    }

    public IReadOnlyList<CommandRecord> Items { get { lock (gate) return items.ToList(); } }

    public void Add(CommandRecord r)
    {
        lock (gate)
        {
            items.Add(r);
            if (items.Count > 300) items.RemoveRange(0, items.Count - 300);
            try { Paths.WriteSafely(Paths.File("historial.json"), JsonSerializer.Serialize(items)); } catch (IOException) { }
        }
        Added?.Invoke(r);
    }

    public void Clear()
    {
        lock (gate) { items.Clear(); try { File.Delete(Paths.File("historial.json")); } catch (IOException) { } }
    }
}

static class Log
{
    static readonly object gate = new();
    public static event Action<string>? Line;

    public static void Write(string text)
    {
        var line = $"{DateTime.Now:yyyy-MM-dd HH:mm:ss} {text}";
        lock (gate)
        {
            try
            {
                var file = Paths.File("registro.txt");
                if (File.Exists(file) && new FileInfo(file).Length > 1_000_000) File.Move(file, file + ".anterior", true);
                File.AppendAllText(file, line + Environment.NewLine);
            }
            catch (IOException) { }
        }
        Line?.Invoke(line);
    }
}

/// <summary>Iniciar con Windows (clave del usuario en el registro: no requiere administrador).</summary>
static class Autostart
{
    const string Key = @"Software\Microsoft\Windows\CurrentVersion\Run";
    const string Name = "LucyRemotaReceptor";

    public static bool Enabled
    {
        get { using var k = Registry.CurrentUser.OpenSubKey(Key); return k?.GetValue(Name) is string; }
        set
        {
            using var k = Registry.CurrentUser.CreateSubKey(Key);
            if (value) k.SetValue(Name, $"\"{Environment.ProcessPath}\" --minimizada");
            else k.DeleteValue(Name, false);
        }
    }
}
