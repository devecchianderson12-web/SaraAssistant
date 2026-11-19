using System.Drawing;
using System.Drawing.Imaging;
using System.Text.Json.Nodes;
using LucyRemota.Core.Commands;
using LucyRemota.Native;

namespace LucyRemota.Modules;

/// <summary>Consultas (hora, estado de la PC), capturas de pantalla y búsquedas en la web.</summary>
sealed class InfoModule : ISkillModule
{
    public string Name => "info";

    public IReadOnlyList<IntentSpec> Intents { get; } =
    [
        new("GET_TIME", "info", "Dice la hora de la computadora.", Risk.Info),
        new("GET_SYSTEM_INFO", "info", "Batería, disco y memoria de la computadora.", Risk.Info),
        new("TAKE_SCREENSHOT", "capture", "Saca una captura de toda la pantalla (se guarda en Imágenes › Lucy Remota).", Risk.Change),
        new("WEB_SEARCH", "web", "Busca algo en Google en el navegador.", Risk.Change, M.P(("query", new(ParamType.String, Required: true, MaxLength: 200)))),
    ];

    public Task<Outcome> ExecuteAsync(string intent, Params p, CancellationToken ct) => Task.Run(() => intent switch
    {
        "GET_TIME" => Time(),
        "GET_SYSTEM_INFO" => SystemInfo(),
        "TAKE_SCREENSHOT" => Screenshot(),
        _ => Search(p.Str("query")!),
    }, ct);

    static Outcome Time()
    {
        var now = DateTime.Now;
        var m = now.Minute switch { 0 => " en punto", 15 => " y cuarto", 30 => " y media", _ => $" y {now.Minute}" };
        var spoken = (now.Hour is 1 or 13 ? "Es la " : "Son las ") + now.Hour + m + ".";
        return Outcome.Ok(spoken, new JsonObject { ["time"] = now.ToString("HH:mm"), ["date"] = now.ToString("yyyy-MM-dd") });
    }

    static Outcome SystemInfo()
    {
        var parts = new List<string>();
        var result = new JsonObject();
        var power = System.Windows.Forms.SystemInformation.PowerStatus;
        if (power.BatteryChargeStatus.HasFlag(System.Windows.Forms.BatteryChargeStatus.NoSystemBattery))
        {
            parts.Add("No tiene batería: está enchufada");
        }
        else
        {
            var pct = (int)Math.Round(power.BatteryLifePercent * 100);
            var charging = power.PowerLineStatus == System.Windows.Forms.PowerLineStatus.Online;
            parts.Add($"Tiene {pct} % de batería" + (charging ? ", cargando" : ""));
            result["battery"] = pct;
            result["charging"] = charging;
        }
        var drive = new DriveInfo(Path.GetPathRoot(Environment.SystemDirectory)!);
        var freeGb = drive.AvailableFreeSpace / 1_073_741_824.0;
        parts.Add($"{freeGb:0} GB libres de {drive.TotalSize / 1_073_741_824.0:0} en el disco");
        result["disk_free_gb"] = Math.Round(freeGb, 1);
        var mem = new Win32.MemoryStatusEx { Length = (uint)System.Runtime.InteropServices.Marshal.SizeOf<Win32.MemoryStatusEx>() };
        if (Win32.GlobalMemoryStatusEx(ref mem))
        {
            parts.Add($"y la memoria está usada al {mem.MemoryLoad} %");
            result["memory_load"] = (int)mem.MemoryLoad;
        }
        return Outcome.Ok(string.Join(", ", parts) + ".", result);
    }

    static Outcome Screenshot()
    {
        var bounds = System.Windows.Forms.SystemInformation.VirtualScreen;
        using var bmp = new Bitmap(bounds.Width, bounds.Height, PixelFormat.Format24bppRgb);
        using (var g = Graphics.FromImage(bmp)) g.CopyFromScreen(bounds.Left, bounds.Top, 0, 0, bounds.Size);
        var folder = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.MyPictures), "Lucy Remota");
        Directory.CreateDirectory(folder);
        var file = Path.Combine(folder, $"Captura {DateTime.Now:yyyy-MM-dd HH.mm.ss}.png");
        bmp.Save(file, ImageFormat.Png);
        return Outcome.Ok("Listo, saqué una captura. Está en Imágenes, carpeta Lucy Remota.", new JsonObject { ["path"] = file });
    }

    static Outcome Search(string query)
    {
        M.ShellOpen("https://www.google.com/search?q=" + Uri.EscapeDataString(query));
        return Outcome.Ok($"Te busqué «{query}» en Google.");
    }
}
