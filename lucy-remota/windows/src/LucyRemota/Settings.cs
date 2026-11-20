using System.Text.Json;
using LucyRemota.Core.Link;

namespace LucyRemota;

/// <summary>Ajustes del Receptor («ajustes.json» en la carpeta de datos).</summary>
sealed class AppSettings
{
    /// <summary>Nombre con el que el teléfono ve a esta computadora (vacío: el de Windows).</summary>
    public string PcName { get; set; } = "";
    /// <summary>App de música para «poné música»: vacía = automática (YouTube Music instalada, Spotify…).</summary>
    public string MusicApp { get; set; } = "";
    /// <summary>Conectarse por Internet (relé) cuando el teléfono está en otra red.</summary>
    public bool UseRelay { get; set; } = true;
    public string RelayUrl { get; set; } = RelayClient.DefaultUrl;
    public bool StartMinimized { get; set; }
    public bool SaveHistory { get; set; } = true;
    /// <summary>Aviso en la bandeja cada vez que llega una orden.</summary>
    public bool NotifyOrders { get; set; }
    public bool CheckUpdates { get; set; } = true;

    public static AppSettings Current { get; private set; } = Load();
    public static event Action? Changed;

    static string FilePath => Paths.File("ajustes.json");

    static AppSettings Load()
    {
        try
        {
            if (File.Exists(FilePath)) return JsonSerializer.Deserialize<AppSettings>(File.ReadAllText(FilePath)) ?? new();
        }
        catch (Exception e) when (e is IOException or JsonException or UnauthorizedAccessException)
        {
            Log.Write("Ajustes ilegibles, uso los de fábrica: " + e.Message);
        }
        var s = new AppSettings();
        // Antes la dirección del relé iba en «relay.txt»: se respeta.
        try
        {
            var legacy = Paths.File("relay.txt");
            if (File.Exists(legacy))
            {
                var r = File.ReadAllText(legacy).Trim();
                if (r.Equals("no", StringComparison.OrdinalIgnoreCase)) s.UseRelay = false;
                else if (r.Length > 0) s.RelayUrl = r;
            }
        }
        catch (IOException) { }
        return s;
    }

    public static void Save(AppSettings s)
    {
        Current = s;
        try { Paths.WriteSafely(FilePath, JsonSerializer.Serialize(s, new JsonSerializerOptions { WriteIndented = true })); }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException) { Log.Write("No pude guardar los ajustes: " + e.Message); }
        Changed?.Invoke();
    }

    public AppSettings Copy() => (AppSettings)MemberwiseClone();
}
