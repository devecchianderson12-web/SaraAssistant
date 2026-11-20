using System.Text.RegularExpressions;
using System.Windows.Automation;
using LucyRemota.Native;

namespace LucyRemota.Modules;

/// <summary>
/// Manejo de una app web instalada (YouTube Music instalada desde Chrome o Edge) sin abrir pestañas:
/// si ya está abierta se usa esa ventana; si no, se abre la app (como desde su ícono). Para buscar
/// se escribe en su buscador (la tecla «/» lo enfoca) y para reproducir se toca el primer resultado
/// con la accesibilidad de Windows (lo mismo que haría un lector de pantalla).
/// </summary>
static partial class WebMusic
{
    [GeneratedRegex("_crx_([a-p]{32})", RegexOptions.IgnoreCase)]
    private static partial Regex Crx();

    [GeneratedRegex(@"^(reproducir|play)\b", RegexOptions.IgnoreCase)]
    private static partial Regex PlayButton();

    /// <summary>El id de la app web (las 32 letras de Chrome) de un id de Windows; null si no es una app web.</summary>
    public static string? WebAppId(string appId) => Crx().Match(appId) is { Success: true } m ? m.Groups[1].Value.ToLowerInvariant() : null;

    /// <summary>La ventana abierta de esa app web (0 si no está abierta).</summary>
    public static nint FindWindow(string appId)
    {
        var id = WebAppId(appId);
        if (id == null) return 0;
        foreach (var h in WinUi.TopLevel())
        {
            var a = WinUi.AppId(h);
            if (a != null && WebAppId(a) == id && WinUi.Title(h).Length > 0) return h;
        }
        return 0;
    }

    public static async Task<nint> WaitWindow(Func<nint> find, TimeSpan timeout, CancellationToken ct)
    {
        var until = DateTime.UtcNow + timeout;
        while (DateTime.UtcNow < until)
        {
            var h = find();
            if (h != 0) return h;
            await Task.Delay(250, ct);
        }
        return 0;
    }

    /// <summary>Trae la ventana al frente (necesario para escribirle).</summary>
    public static async Task<bool> FocusAsync(nint h, CancellationToken ct)
    {
        for (var i = 0; i < 4; i++)
        {
            Win32.BringToFront(h);
            await Task.Delay(200, ct);
            if (WinUi.IsForeground(h)) return true;
        }
        return false;
    }

    /// <summary>Espera a que la página tenga su buscador (recién abierta tarda unos segundos).</summary>
    public static async Task<bool> WaitReadyAsync(nint h, TimeSpan timeout, CancellationToken ct)
    {
        var until = DateTime.UtcNow + timeout;
        while (DateTime.UtcNow < until)
        {
            var found = await Task.Run(() =>
            {
                try
                {
                    var root = AutomationElement.FromHandle(h);
                    return root.FindFirst(TreeScope.Descendants, new PropertyCondition(AutomationElement.ControlTypeProperty, ControlType.Edit)) != null;
                }
                catch (Exception e) when (e is ElementNotAvailableException or InvalidOperationException or System.Runtime.InteropServices.COMException) { return false; }
            }, ct);
            if (found) return true;
            await Task.Delay(400, ct);
        }
        return false;
    }

    /// <summary>Busca en la app: «/» enfoca el buscador, se escribe y Enter.</summary>
    public static async Task<bool> SearchAsync(nint h, string query, CancellationToken ct)
    {
        if (!await FocusAsync(h, ct)) return false;
        WinUi.Press(WinUi.VK_ESCAPE);
        await Task.Delay(120, ct);
        WinUi.Type("/");
        await Task.Delay(350, ct);
        if (!WinUi.IsForeground(h)) return false;
        WinUi.Chord(WinUi.VK_CONTROL, WinUi.VK_A);
        WinUi.Type(query);
        await Task.Delay(150, ct);
        WinUi.Press(WinUi.VK_RETURN);
        return true;
    }

    /// <summary>
    /// Toca el primer botón «Reproducir…» de la página (el resultado principal de la búsqueda, o lo
    /// primero de la portada). No toca los de la barra del reproductor (abajo de todo).
    /// </summary>
    public static async Task<string?> PlayFirstAsync(nint h, TimeSpan timeout, CancellationToken ct)
    {
        var until = DateTime.UtcNow + timeout;
        while (DateTime.UtcNow < until)
        {
            var played = await Task.Run(() => TryPlayFirst(h), ct);
            if (played != null) return played;
            await Task.Delay(500, ct);
        }
        return null;
    }

    static string? TryPlayFirst(nint h)
    {
        try
        {
            var win = WinUi.Bounds(h);
            var root = AutomationElement.FromHandle(h);
            var buttons = root.FindAll(TreeScope.Descendants, new PropertyCondition(AutomationElement.ControlTypeProperty, ControlType.Button));
            AutomationElement? best = null;
            System.Windows.Rect bestRect = default;
            foreach (AutomationElement b in buttons)
            {
                string name;
                System.Windows.Rect r;
                try { name = b.Current.Name ?? ""; r = b.Current.BoundingRectangle; }
                catch (ElementNotAvailableException) { continue; }
                if (!PlayButton().IsMatch(name.Trim()) || r.IsEmpty || r.Width < 4) continue;
                // Fuera de la barra de arriba y de la del reproductor (abajo).
                if (r.Top < win.Top + 60 || r.Bottom > win.Bottom - 110) continue;
                if (best == null || r.Top < bestRect.Top - 4 || (Math.Abs(r.Top - bestRect.Top) <= 4 && r.Left < bestRect.Left))
                {
                    best = b;
                    bestRect = r;
                }
            }
            if (best == null) return null;
            if (best.TryGetCurrentPattern(InvokePattern.Pattern, out var p))
            {
                ((InvokePattern)p).Invoke();
                return best.Current.Name;
            }
            return null;
        }
        catch (Exception e) when (e is ElementNotAvailableException or InvalidOperationException or System.Runtime.InteropServices.COMException or TimeoutException)
        {
            return null;
        }
    }
}
