using System.Diagnostics;
using System.Management;
using System.Text.Json.Nodes;
using LucyRemota.Core.Commands;
using LucyRemota.Native;

namespace LucyRemota.Modules;

/// <summary>
/// Sistema: páginas de Configuración y carpetas (lista cerrada), bloquear, apagar la pantalla,
/// brillo, ventanas, suspender, apagar y reiniciar (estas tres piden confirmación en el teléfono).
/// </summary>
sealed class SystemModule : ISkillModule
{
    public string Name => "system";

    /// <summary>Destinos permitidos para OPEN_SYSTEM_PAGE: el teléfono sólo elige uno de estos nombres.</summary>
    static readonly Dictionary<string, (string label, Func<string> target)> Pages = new()
    {
        ["settings"] = ("la Configuración de Windows", () => "ms-settings:"),
        ["explorer"] = ("el Explorador de archivos", () => "explorer.exe"),
        ["this_pc"] = ("Este equipo", () => "shell:MyComputerFolder"),
        ["downloads"] = ("Descargas", () => Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), "Downloads")),
        ["documents"] = ("Documentos", () => Environment.GetFolderPath(Environment.SpecialFolder.MyDocuments)),
        ["pictures"] = ("Imágenes", () => Environment.GetFolderPath(Environment.SpecialFolder.MyPictures)),
        ["music"] = ("Música", () => Environment.GetFolderPath(Environment.SpecialFolder.MyMusic)),
        ["videos"] = ("Videos", () => Environment.GetFolderPath(Environment.SpecialFolder.MyVideos)),
        ["desktop"] = ("el Escritorio", () => Environment.GetFolderPath(Environment.SpecialFolder.DesktopDirectory)),
        ["recycle_bin"] = ("la Papelera", () => "shell:RecycleBinFolder"),
        ["task_manager"] = ("el Administrador de tareas", () => "taskmgr.exe"),
        ["control_panel"] = ("el Panel de control", () => "control.exe"),
        ["bluetooth"] = ("la configuración de Bluetooth", () => "ms-settings:bluetooth"),
        ["wifi"] = ("la configuración de Wi-Fi", () => "ms-settings:network-wifi"),
        ["network"] = ("la configuración de red", () => "ms-settings:network"),
        ["sound"] = ("la configuración de sonido", () => "ms-settings:sound"),
        ["display"] = ("la configuración de pantalla", () => "ms-settings:display"),
        ["apps"] = ("Aplicaciones instaladas", () => "ms-settings:appsfeatures"),
        ["updates"] = ("Windows Update", () => "ms-settings:windowsupdate"),
        ["notifications"] = ("las notificaciones", () => "ms-settings:notifications"),
        ["personalization"] = ("la personalización", () => "ms-settings:personalization"),
        ["battery"] = ("la configuración de batería", () => "ms-settings:batterysaver"),
        ["printers"] = ("las impresoras", () => "ms-settings:printers"),
    };

    public IReadOnlyList<IntentSpec> Intents { get; } =
    [
        new("OPEN_SYSTEM_PAGE", "system", "Abre una página de Configuración o una carpeta conocida.", Risk.Change,
            M.P(("page", new(ParamType.Enum, Required: true, Options: Pages.Keys.ToList())))),
        new("LOCK_SESSION", "system", "Bloquea la computadora.", Risk.Change),
        new("SCREEN_OFF", "system", "Apaga la pantalla (se prende al mover el mouse).", Risk.Change),
        new("SET_BRIGHTNESS", "system", "Cambia el brillo (notebooks).", Risk.Change,
            M.P(("level", new(ParamType.Int, Min: 0, Max: 100)), ("delta", new(ParamType.Int, Min: -100, Max: 100))), OneOf: ["level", "delta"]),
        new("MINIMIZE_WINDOWS", "windows", "Minimiza todas las ventanas (muestra el escritorio).", Risk.Change),
        new("RESTORE_WINDOWS", "windows", "Vuelve a mostrar las ventanas minimizadas.", Risk.Change),
        new("SLEEP", "system", "¿Suspendo la computadora?", Risk.Sensitive),
        new("SHUTDOWN", "system", "¿Apago la computadora? (en 30 segundos)", Risk.Sensitive),
        new("RESTART", "system", "¿Reinicio la computadora? (en 30 segundos)", Risk.Sensitive),
        new("CANCEL_SHUTDOWN", "system", "Cancela un apagado o reinicio pendiente.", Risk.Change),
    ];

    public async Task<Outcome> ExecuteAsync(string intent, Params p, CancellationToken ct)
    {
        switch (intent)
        {
            case "OPEN_SYSTEM_PAGE":
                var (label, target) = Pages[p.Str("page")!];
                M.ShellOpen(target());
                return Outcome.Ok($"Abriendo {label}.");
            case "LOCK_SESSION":
                return Win32.LockWorkStation() ? Outcome.Ok("Listo, bloqueé la computadora.") : Outcome.Fail("Windows no dejó bloquear.");
            case "SCREEN_OFF":
                // Se apaga después de contestar (si no, el aviso del teléfono la vuelve a prender en algunas PCs).
                _ = Task.Run(async () =>
                {
                    await Task.Delay(700);
                    Win32.SendMessageTimeout(Win32.HWND_BROADCAST, Win32.WM_SYSCOMMAND, Win32.SC_MONITORPOWER, 2, Win32.SMTO_ABORTIFHUNG, 2000, out _);
                });
                return Outcome.Ok("Listo, apago la pantalla.");
            case "SET_BRIGHTNESS":
                return await Task.Run(() => Brightness(p), ct);
            case "MINIMIZE_WINDOWS":
                await Sta.Run(() => { dynamic shell = Activator.CreateInstance(Type.GetTypeFromProgID("Shell.Application")!)!; shell.MinimizeAll(); return 0; });
                return Outcome.Ok("Listo, minimicé todas las ventanas.");
            case "RESTORE_WINDOWS":
                await Sta.Run(() => { dynamic shell = Activator.CreateInstance(Type.GetTypeFromProgID("Shell.Application")!)!; shell.UndoMinimizeALL(); return 0; });
                return Outcome.Ok("Listo, volví a mostrar las ventanas.");
            case "SLEEP":
                _ = Task.Run(async () => { await Task.Delay(1500); Win32.SetSuspendState(false, false, false); });
                return Outcome.Ok("Suspendo la computadora.");
            case "SHUTDOWN":
                return Shutdown("/s /t 30", "Apago la computadora en 30 segundos. Si te arrepentís, decime «cancelá el apagado».");
            case "RESTART":
                return Shutdown("/r /t 30", "Reinicio la computadora en 30 segundos. Si te arrepentís, decime «cancelá el apagado».");
            default:
                return Shutdown("/a", "Listo, cancelé el apagado.");
        }
    }

    /// <summary>shutdown.exe con argumentos fijos (no vienen del teléfono). No necesita administrador.</summary>
    static Outcome Shutdown(string args, string message)
    {
        var shutdown = Path.Combine(Environment.SystemDirectory, "shutdown.exe");
        using var p = Process.Start(new ProcessStartInfo(shutdown, args) { CreateNoWindow = true, UseShellExecute = false });
        p?.WaitForExit(5000);
        if (args == "/a" && p?.ExitCode != 0) return Outcome.Ok("No había ningún apagado pendiente.");
        return p?.ExitCode == 0 ? Outcome.Ok(message) : Outcome.Fail("Windows no lo permitió.");
    }

    static Outcome Brightness(Params p)
    {
        try
        {
            int current;
            using (var s = new ManagementObjectSearcher("root\\WMI", "SELECT CurrentBrightness FROM WmiMonitorBrightness"))
            {
                var first = s.Get().Cast<ManagementObject>().FirstOrDefault();
                if (first == null) return new Outcome(Status.Error, "Esta pantalla no deja cambiar el brillo desde Windows.", null, "unsupported_display");
                current = Convert.ToInt32(first["CurrentBrightness"]);
            }
            var target = Math.Clamp(p.Int("level") ?? current + p.Int("delta")!.Value, 0, 100);
            using var m = new ManagementObjectSearcher("root\\WMI", "SELECT * FROM WmiMonitorBrightnessMethods");
            foreach (ManagementObject o in m.Get()) o.InvokeMethod("WmiSetBrightness", [(uint)1, (byte)target]);
            return Outcome.Ok($"Listo, brillo al {target} %.", new JsonObject { ["level"] = target });
        }
        catch (ManagementException)
        {
            return new Outcome(Status.Error, "Esta pantalla no deja cambiar el brillo desde Windows.", null, "unsupported_display");
        }
    }
}
