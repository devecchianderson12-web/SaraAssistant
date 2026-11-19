using System.Diagnostics;
using System.Net;
using System.Text;
using System.Text.Json.Nodes;
using LucyRemota.Core.Commands;
using LucyRemota.Core.Link;
using LucyRemota.Core.Protocol;
using LucyRemota.Modules;
using LucyRemota.UI;

namespace LucyRemota;

/// <summary>
/// Autoprueba en una Windows real (la corre la compilación en la nube): un teléfono simulado se
/// vincula por QR y manda órdenes de verdad; se revisan respuestas, seguridad, rendimiento y la ventana.
/// </summary>
static class SelfTest
{
    static readonly StringBuilder Report = new();
    static int failures;

    static void Line(string s) { Console.WriteLine(s); Report.AppendLine(s); }
    static void Ok(string name, string detail = "") => Line($"OK   {name} {detail}");
    static void Fail(string name, string detail) { failures++; Line($"FAIL {name} {detail}"); }
    static void Info(string name, string detail) => Line($"INFO {name} {detail}");

    public static int Run(string dir)
    {
        Directory.CreateDirectory(dir);
        var started = Stopwatch.StartNew();
        try { RunAsync(dir).GetAwaiter().GetResult(); }
        catch (Exception e) { Fail("autoprueba", e.ToString()); }
        Line($"Tiempo total: {started.ElapsedMilliseconds} ms");
        Line(failures == 0 ? "RESULTADO: todo OK" : $"RESULTADO: {failures} fallas");
        File.WriteAllText(Path.Combine(dir, "autoprueba.txt"), Report.ToString());
        return failures == 0 ? 0 : 1;
    }

    static async Task RunAsync(string dir)
    {
        var t0 = Stopwatch.StartNew();
        await using var phone = new PhoneSimulator();
        var store = new MemoryPcStore("pc-autoprueba", Environment.MachineName);
        var apps = new AppsModule();
        var registry = new CommandRegistry([new AudioModule(), new MediaModule(apps), apps, new SystemModule(), new InfoModule()]);
        var history = new History();
        history.Clear();
        registry.Logged += history.Add;
        await using var link = new PcLink(store, registry, s => Line("     · " + s), discoveryPort: phone.UdpPort) { BeaconInterval = TimeSpan.FromMilliseconds(200), RelayUrl = null };
        link.ExtraTargets.Add(new IPEndPoint(IPAddress.Loopback, phone.UdpPort));
        apps.Preload();

        // Vinculación por QR (como si se escaneara la pantalla).
        var w = link.StartPairing();
        var q = System.Web.HttpUtility.ParseQueryString(new Uri(w.QrPayload(store.PcId, store.PcName, store.Key.PublicSpki)).Query);
        phone.PendingQr = (q["pc"]!, J.FromB64Url(q["f"]!), J.FromB64Url(q["t"]!));
        link.Start();
        if (await phone.WaitConnectedAsync(TimeSpan.FromSeconds(10))) Ok("vinculación por QR", $"en {t0.ElapsedMilliseconds} ms");
        else { Fail("vinculación por QR", "no conectó"); return; }
        var catalog = phone.Capabilities!["intents"]!.AsArray().Count;
        Ok("catálogo de intenciones", $"{catalog} intenciones");

        async Task<JsonObject> Cmd(string intent, JsonObject? p = null, string expect = Status.Success, bool optional = false, string? id = null)
        {
            var sw = Stopwatch.StartNew();
            var list = await phone.CommandAsync(intent, p, id);
            var final = list[^1];
            var st = final.Str("status");
            var detail = $"{st} «{final.Str("message")}» ({sw.ElapsedMilliseconds} ms) {final["result"]?.ToJsonString()}";
            if (st == expect) Ok(intent, detail);
            else if (optional) Info(intent, detail);
            else Fail(intent, detail);
            return final;
        }

        await Cmd("GET_TIME");
        await Cmd("GET_SYSTEM_INFO");
        await Cmd("GET_VOLUME", optional: true); // los servidores de la nube no tienen placa de sonido
        await Cmd("SET_VOLUME", new JsonObject { ["level"] = 30 }, optional: true);
        var list = await Cmd("LIST_APPLICATIONS");
        var count = list["result"]?["apps"]?.AsArray().Count ?? 0;
        if (count > 0) Ok("apps del menú Inicio", $"{count}: " + string.Join(", ", list["result"]!["apps"]!.AsArray().Take(8).Select(n => n!.GetValue<string>())));
        else Fail("apps del menú Inicio", "vacío");
        await Cmd("MEDIA_NOW_PLAYING");
        var shot = await Cmd("TAKE_SCREENSHOT");
        if (shot["result"]?["path"]?.GetValue<string>() is { } path && File.Exists(path)) Ok("captura guardada", path); else Fail("captura guardada", "no está el archivo");
        await Cmd("MINIMIZE_WINDOWS");
        await Cmd("RESTORE_WINDOWS");
        await Cmd("OPEN_APPLICATION", new JsonObject { ["name"] = "bloc de notas" }, optional: true);
        await Task.Delay(2000);
        await Cmd("CLOSE_APPLICATION", new JsonObject { ["name"] = "bloc de notas" }, optional: true);
        await Cmd("OPEN_APPLICATION", new JsonObject { ["name"] = "Photoshop" }, expect: Status.Error);

        // Seguridad y protocolo.
        await Cmd("FORMAT_DISK", expect: Status.Unsupported);
        await Cmd("SET_VOLUME", new JsonObject { ["level"] = 999 }, expect: Status.Invalid);
        await Cmd("OPEN_SYSTEM_PAGE", new JsonObject { ["page"] = "cmd.exe /c del" }, expect: Status.Invalid);
        await Cmd("SHUTDOWN", expect: Status.NeedsConfirmation); // nunca se confirma en la prueba
        var id = Guid.NewGuid().ToString();
        await Cmd("GET_TIME", id: id);
        var again = await phone.CommandAsync("GET_TIME", null, id);
        if (again.Count == 1) Ok("orden repetida", "no se ejecutó dos veces"); else Fail("orden repetida", $"{again.Count} estados");

        // Rendimiento: memoria de la PC con todo cargado.
        using var me = Process.GetCurrentProcess();
        me.Refresh();
        Info("memoria", $"{me.WorkingSet64 / 1_048_576} MB en uso, {me.PrivateMemorySize64 / 1_048_576} MB privados");

        // Ventana, vinculación, teléfonos e historial (capturas para revisar el diseño).
        var screen = SystemInformation.VirtualScreen;
        void Shot(string name)
        {
            for (var i = 0; i < 25; i++) { Application.DoEvents(); Thread.Sleep(40); }
            using var bmp = new Bitmap(screen.Width, screen.Height);
            using (var g = Graphics.FromImage(bmp)) g.CopyFromScreen(screen.Location, Point.Empty, screen.Size);
            bmp.Save(Path.Combine(dir, name));
        }
        var form = new MainForm(link, store, history, startHidden: false);
        form.Show();
        Shot("receptor.png");
        var dialog = new PairDialog(link.StartPairing(), store);
        dialog.Show(form);
        Shot("vincular.png");
        dialog.Close();
        var phonesDialog = new PhonesDialog(link, store);
        phonesDialog.Show(form);
        Shot("telefonos.png");
        phonesDialog.Close();
        var historyDialog = new HistoryDialog(history);
        historyDialog.Show(form);
        Shot("historial.png");
        historyDialog.Close();
        Ok("ventanas", "capturas: receptor, vincular, telefonos, historial");
        form.Hide();
    }
}
