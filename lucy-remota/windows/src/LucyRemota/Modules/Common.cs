using System.Diagnostics;
using LucyRemota.Core.Commands;

namespace LucyRemota.Modules;

/// <summary>Atajos para declarar intenciones y abrir cosas de forma controlada.</summary>
static class M
{
    public static Dictionary<string, ParamSpec> P(params (string name, ParamSpec spec)[] ps) => ps.ToDictionary(p => p.name, p => p.spec);

    /// <summary>
    /// Abre un destino fijo (una dirección ms-settings:, una carpeta conocida, una URL armada acá).
    /// Nunca se arma con texto del teléfono sin validar: los módulos sólo pasan destinos de su lista
    /// o URLs https con el texto codificado.
    /// </summary>
    public static void ShellOpen(string target, string? args = null)
    {
        var psi = new ProcessStartInfo(target) { UseShellExecute = true };
        if (args != null) psi.Arguments = args;
        using var _ = Process.Start(psi);
    }
}
