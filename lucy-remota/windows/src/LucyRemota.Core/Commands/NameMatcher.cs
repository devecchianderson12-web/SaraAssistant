using System.Globalization;
using System.Text;

namespace LucyRemota.Core.Commands;

/// <summary>
/// Elige el nombre instalado que mejor coincide con lo que se dijo («chrome» → «Google Chrome»,
/// «whatsap» → «WhatsApp»). Tolera errores leves; con poca coincidencia no elige nada.
/// </summary>
public static class NameMatcher
{
    static readonly HashSet<string> Noise = ["app", "aplicacion", "la", "el", "de", "the", "programa"];

    /// <summary>Nombres en español de programas que en Windows suelen estar en inglés.</summary>
    static readonly Dictionary<string, string[]> Synonyms = new()
    {
        ["navegador"] = ["google chrome", "microsoft edge", "firefox", "opera", "brave"],
        ["internet"] = ["google chrome", "microsoft edge", "firefox"],
        ["chrome"] = ["google chrome"],
        ["edge"] = ["microsoft edge"],
        ["calculadora"] = ["calculator", "calculadora"],
        ["bloc de notas"] = ["notepad", "bloc de notas"],
        ["notas"] = ["notepad", "bloc de notas", "sticky notes"],
        ["correo"] = ["outlook", "mail", "correo"],
        ["fotos"] = ["photos", "fotos"],
        ["camara"] = ["camera", "camara"],
        ["reloj"] = ["clock", "alarms clock", "reloj"],
        ["alarmas"] = ["clock", "alarms clock"],
        ["paint"] = ["paint"],
        ["word"] = ["word", "microsoft word"],
        ["excel"] = ["excel", "microsoft excel"],
        ["powerpoint"] = ["powerpoint", "microsoft powerpoint"],
        ["tienda"] = ["microsoft store"],
        ["terminal"] = ["terminal", "windows terminal"],
        ["mapas"] = ["maps", "mapas"],
        ["calendario"] = ["calendar", "calendario"],
        ["musica"] = ["spotify", "media player", "reproductor multimedia"],
        ["whatsapp"] = ["whatsapp", "whatsapp beta"],
    };

    public static string Normalize(string text)
    {
        var d = text.ToLowerInvariant().Normalize(NormalizationForm.FormD);
        var sb = new StringBuilder(d.Length);
        foreach (var c in d)
        {
            if (CharUnicodeInfo.GetUnicodeCategory(c) == UnicodeCategory.NonSpacingMark) continue;
            sb.Append(char.IsLetterOrDigit(c) ? c : ' ');
        }
        return string.Join(' ', sb.ToString().Split(' ', StringSplitOptions.RemoveEmptyEntries));
    }

    public static double Score(string query, string label)
    {
        var q = Normalize(query);
        var l = Normalize(label);
        if (q.Length == 0 || l.Length == 0) return 0;
        if (q == l) return 1;
        var qc = q.Replace(" ", "");
        var lc = l.Replace(" ", "");
        if (qc == lc) return 0.98;
        var qt = q.Split(' ').Where(t => !Noise.Contains(t)).ToArray();
        if (qt.Length == 0) qt = q.Split(' ');
        var lt = l.Split(' ');
        double best = 0;
        if (qt.All(lt.Contains))
            best = Math.Max(best, 0.9 - 0.02 * Math.Max(0, lt.Length - qt.Length) + (lt.TakeLast(qt.Length).SequenceEqual(qt) ? 0.03 : 0));
        if (qc.Length >= 3 && lc.Contains(qc)) best = Math.Max(best, 0.84 - 0.01 * Math.Min(20, lc.Length - qc.Length));
        best = Math.Max(best, qt.Average(t => lt.Max(x => Similarity(t, x))) * 0.85);
        best = Math.Max(best, Similarity(qc, lc) * 0.85);
        return best;
    }

    public static T? Best<T>(string query, IEnumerable<T> items, Func<T, string> label, double threshold = 0.66) where T : class
    {
        var variants = new List<string> { query };
        if (Synonyms.TryGetValue(Normalize(query), out var syn)) variants.AddRange(syn);
        return items
            .Select(i => (item: i, score: variants.Max(v => Score(v, label(i)))))
            .Where(x => x.score >= threshold)
            .OrderByDescending(x => x.score).ThenBy(x => label(x.item).Length)
            .Select(x => x.item)
            .FirstOrDefault();
    }

    static double Similarity(string a, string b)
    {
        if (a.Length == 0 || b.Length == 0) return 0;
        var prev = new int[b.Length + 1];
        for (var j = 0; j <= b.Length; j++) prev[j] = j;
        for (var i = 1; i <= a.Length; i++)
        {
            var cur = new int[b.Length + 1];
            cur[0] = i;
            for (var j = 1; j <= b.Length; j++)
                cur[j] = Math.Min(Math.Min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + (a[i - 1] == b[j - 1] ? 0 : 1));
            prev = cur;
        }
        return 1.0 - (double)prev[b.Length] / Math.Max(a.Length, b.Length);
    }
}
