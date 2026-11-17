using System;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.IO.Compression;
using System.Linq;
using System.Reflection;
using System.Threading;
using System.Windows.Forms;

namespace LucyLauncher
{
    /// <summary>
    /// Abre Lucy. La primera vez (o con una versión nueva) descomprime la app que trae adentro en
    /// %LOCALAPPDATA%\Lucy\app\&lt;versión&gt; mostrando «Preparando Lucy…»; las siguientes la abre al instante.
    /// </summary>
    internal static class Program
    {
        [STAThread]
        private static int Main(string[] args)
        {
            var version = Assembly.GetExecutingAssembly().GetName().Version.ToString();
            var baseDir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Lucy", "app");
            var root = Path.Combine(baseDir, version);
            var exe = Path.Combine(root, "Lucy.exe");
            try
            {
                if (!File.Exists(Path.Combine(root, ".ok"))) Prepare(root);
                CleanOld(baseDir, version);
                var self = Assembly.GetExecutingAssembly().Location;
                var arguments = "--launcher " + Quote(self) + (args.Length > 0 ? " " + string.Join(" ", args.Select(Quote)) : "");
                var process = Process.Start(new ProcessStartInfo(exe, arguments) { UseShellExecute = false, WorkingDirectory = root });
                // La autoprueba espera el resultado; lo normal es abrir y salir.
                if (args.Contains("--selftest"))
                {
                    process.WaitForExit();
                    return process.ExitCode;
                }
                return 0;
            }
            catch (Exception e)
            {
                if (args.Contains("--selftest")) { Console.Error.WriteLine(e); return 2; }
                MessageBox.Show("No pude abrir Lucy: " + e.Message, "Lucy", MessageBoxButtons.OK, MessageBoxIcon.Error);
                return 1;
            }
        }

        private static string Quote(string a) => a.Length > 0 && a.IndexOfAny(new[] { ' ', '"', '\t' }) < 0 ? a : "\"" + a.Replace("\"", "\\\"") + "\"";

        private static void Prepare(string root)
        {
            Form splash = null;
            var ui = new Thread(() =>
            {
                splash = new Form
                {
                    FormBorderStyle = FormBorderStyle.None, StartPosition = FormStartPosition.CenterScreen,
                    Size = new Size(320, 90), BackColor = Color.FromArgb(14, 10, 30), TopMost = true, ShowInTaskbar = true, Text = "Lucy",
                };
                splash.Controls.Add(new Label
                {
                    Text = "Preparando Lucy…\r\n(sólo la primera vez)", ForeColor = Color.White, Dock = DockStyle.Fill,
                    TextAlign = ContentAlignment.MiddleCenter, Font = new Font("Segoe UI", 11f),
                });
                Application.Run(splash);
            }) { IsBackground = true };
            ui.SetApartmentState(ApartmentState.STA);
            ui.Start();
            try
            {
                var tmp = root + ".tmp";
                if (Directory.Exists(tmp)) Directory.Delete(tmp, true);
                Directory.CreateDirectory(tmp);
                using (var stream = Assembly.GetExecutingAssembly().GetManifestResourceStream("app.zip"))
                using (var zip = new ZipArchive(stream, ZipArchiveMode.Read))
                {
                    foreach (var entry in zip.Entries)
                    {
                        var path = Path.GetFullPath(Path.Combine(tmp, entry.FullName));
                        if (!path.StartsWith(Path.GetFullPath(tmp), StringComparison.OrdinalIgnoreCase)) continue;
                        if (entry.FullName.EndsWith("/")) { Directory.CreateDirectory(path); continue; }
                        Directory.CreateDirectory(Path.GetDirectoryName(path));
                        entry.ExtractToFile(path, true);
                    }
                }
                File.WriteAllText(Path.Combine(tmp, ".ok"), DateTime.Now.ToString("o"));
                if (Directory.Exists(root)) Directory.Delete(root, true);
                Directory.Move(tmp, root);
            }
            finally
            {
                try { splash?.Invoke(new Action(() => splash.Close())); } catch { }
            }
        }

        /// <summary>Borra versiones viejas (si alguna sigue abierta, queda para la próxima).</summary>
        private static void CleanOld(string baseDir, string current)
        {
            foreach (var dir in Directory.GetDirectories(baseDir))
            {
                if (Path.GetFileName(dir) == current) continue;
                // Primero la marca: si algo queda a medio borrar, esa versión se vuelve a descomprimir.
                try { File.Delete(Path.Combine(dir, ".ok")); Directory.Delete(dir, true); } catch { }
            }
        }
    }
}
