using System;
using System.Collections.Generic;
using System.Globalization;
using System.Linq;
using System.Text;
using System.Text.RegularExpressions;

namespace Lucy;

/// <summary>Órdenes que entiende Lucy (todas de multimedia).</summary>
public abstract record Command
{
    public sealed record Play(string? Query, string? Service) : Command;
    public sealed record Pause : Command;
    public sealed record Resume : Command;
    public sealed record Next : Command;
    public sealed record Previous : Command;
    public sealed record Stop : Command;
    public sealed record VolumeSet(int Percent) : Command;
    public sealed record VolumeUp(int? Points) : Command;
    public sealed record VolumeDown(int? Points) : Command;
    public sealed record Mute : Command;
    public sealed record Unmute : Command;
    public sealed record WhatIsPlaying : Command;
    public sealed record OpenService(string Service) : Command;
    public sealed record Cancel : Command;
    public sealed record Unknown(string Text) : Command;
}

/// <summary>Reglas en castellano (con voseo) para música y volumen. Sin Internet ni IA.</summary>
public static class Interpreter
{
    public static string Normalize(string text)
    {
        var lower = text.ToLowerInvariant().Replace("%", " por ciento ");
        var decomposed = lower.Normalize(NormalizationForm.FormD);
        var sb = new StringBuilder();
        foreach (var c in decomposed)
            if (CharUnicodeInfo.GetUnicodeCategory(c) != UnicodeCategory.NonSpacingMark) sb.Append(c);
        var clean = Regex.Replace(sb.ToString(), "[^a-z0-9 ]", " ");
        clean = Regex.Replace(clean, @"(\d)([a-z])", "$1 $2");
        clean = Regex.Replace(clean, @"\s+", " ").Trim();
        // Sin la palabra de activación ni muletillas.
        clean = Regex.Replace(clean, @"^(?:(?:oye|hey|ey|che|hola)\s+)?(?:lucy|luci|lucia|lusi)\b\s*", "");
        foreach (var filler in new[] { "por favor", "porfa", "che", "eh", "bueno" })
            clean = Regex.Replace(" " + clean + " ", " " + filler + " ", " ").Trim();
        return clean;
    }

    private static readonly Dictionary<string, int> Words = new()
    {
        ["cero"] = 0, ["uno"] = 1, ["un"] = 1, ["una"] = 1, ["dos"] = 2, ["tres"] = 3, ["cuatro"] = 4, ["cinco"] = 5, ["seis"] = 6,
        ["siete"] = 7, ["ocho"] = 8, ["nueve"] = 9, ["diez"] = 10, ["once"] = 11, ["doce"] = 12, ["trece"] = 13, ["catorce"] = 14,
        ["quince"] = 15, ["dieciseis"] = 16, ["diecisiete"] = 17, ["dieciocho"] = 18, ["diecinueve"] = 19, ["veinte"] = 20,
        ["veinticinco"] = 25, ["treinta"] = 30, ["cuarenta"] = 40, ["cincuenta"] = 50, ["sesenta"] = 60, ["setenta"] = 70,
        ["ochenta"] = 80, ["noventa"] = 90, ["cien"] = 100, ["ciento"] = 100,
    };

    /// <summary>Primer número de la frase («30», «treinta», «treinta y cinco»).</summary>
    public static int? FirstNumber(string normalized)
    {
        var tokens = normalized.Split(' ', StringSplitOptions.RemoveEmptyEntries);
        for (var i = 0; i < tokens.Length; i++)
        {
            if (int.TryParse(tokens[i], out var n)) return n;
            if (tokens[i] is "un" or "una" or "uno") continue; // «un poco»
            if (Words.TryGetValue(tokens[i], out var w))
            {
                if (w >= 30 && w < 100 && i + 2 < tokens.Length && tokens[i + 1] == "y" && Words.TryGetValue(tokens[i + 2], out var u) && u < 10)
                    return w + u;
                return w;
            }
        }
        return null;
    }

    private static bool Has(string padded, params string[] phrases) => phrases.Any(p => padded.Contains(" " + p + " "));
    private static bool Any(string[] tokens, string pattern) => tokens.Any(t => Regex.IsMatch(t, "^(?:" + pattern + ")$"));

    private static readonly Regex PlayVerb = new(@"^(?:pone|poneme|ponme|pon|reproduci|reproduce|reproducime|toca|tocame|busca|buscame|escuchar|quiero escuchar|pasame|mete)\b");
    private static readonly Regex Service = new(@"\s+(?:en|por|de|desde)\s+(spotify|youtube music|youtube|you tube|deezer)$");

    public static Command Parse(string text)
    {
        var n = Normalize(text);
        if (n.Length == 0) return new Command.Unknown(text);
        var p = " " + n + " ";
        var tokens = n.Split(' ');

        if (tokens.Length <= 3 && Has(p, "cancela", "cancelar", "nada", "olvidalo", "dejalo", "deja", "nada mas")) return new Command.Cancel();

        // ---------------------------------------------------------------- Volumen
        var volumeNoun = Has(p, "volumen", "sonido", "audio");
        var up = Any(tokens, "subi|subile|subime|sube|subir|aumenta|aumentale|subilo") || Has(p, "mas fuerte", "mas alto");
        var down = Any(tokens, "baja|bajale|bajame|bajar|bajalo|reduci|disminui") || Has(p, "mas bajo", "mas despacio", "mas suave");
        if (Has(p, "quita el silencio", "saca el silencio", "quitale el silencio", "activa el sonido", "desmutea")) return new Command.Unmute();
        if (Any(tokens, "silencia|silenciar|mutea|mute|mutear") || Has(p, "sin sonido", "pone silencio", "en silencio")) return new Command.Mute();
        if (volumeNoun || ((up || down) && !Has(p, "cancion", "tema", "musica")))
        {
            var number = FirstNumber(n);
            if (Has(p, "maximo", "al maximo", "a tope", "al palo")) return new Command.VolumeSet(100);
            if (Has(p, "minimo", "al minimo")) return new Command.VolumeSet(5);
            if (number is int v)
            {
                var relative = Has(p, "un " + v, v + " mas", v + " menos") || (up || down) && !Has(p, "al " + v, "a " + v, "en " + v);
                if (!relative) return new Command.VolumeSet(Math.Clamp(v, 0, 100));
                return down ? new Command.VolumeDown(v) : new Command.VolumeUp(v);
            }
            int? step = Has(p, "un poco", "un toque", "un poquito") ? 5 : Has(p, "mucho", "bastante") ? 25 : null;
            if (up && !down) return new Command.VolumeUp(step);
            if (down && !up) return new Command.VolumeDown(step);
            if (volumeNoun && Has(p, "cuanto", "que volumen")) return new Command.WhatIsPlaying();
        }

        // ---------------------------------------------------------------- Control de reproducción
        if (Has(p, "que esta sonando", "que cancion es", "que tema es", "como se llama este tema", "como se llama esta cancion", "quien canta")) return new Command.WhatIsPlaying();
        if (Any(tokens, "siguiente|proxima|proximo|saltea|salta|skip") || Has(p, "pasa la cancion", "pasa el tema", "otra cancion", "cambia la cancion", "cambia de tema", "cambia el tema")) return new Command.Next();
        if (Any(tokens, "anterior") || Has(p, "la de antes", "tema de antes", "volve atras", "para atras")) return new Command.Previous();
        if (Any(tokens, "pausa|pausala|pausar|pausalo|paus") ) return new Command.Pause();
        if (Has(p, "saca la musica", "quita la musica", "cerra la musica", "apaga la musica", "corta la musica", "basta de musica", "detene la musica", "para la musica", "frena la musica") ||
            tokens[0] is "para" or "frena" or "detene" or "stop" or "basta" && tokens.Length <= 3) return new Command.Stop();
        if (Any(tokens, "segui|continua|reanuda|despausa|reanudar") || Has(p, "dale play", "dale al play", "volve a poner")) return new Command.Resume();

        // ---------------------------------------------------------------- Abrir servicios
        var open = Regex.Match(n, @"^(?:abri|abre|abrime|abrir|entra a|entra en)\s+(spotify|youtube music|youtube|you tube|deezer)$");
        if (open.Success) return new Command.OpenService(ServiceName(open.Groups[1].Value));

        // ---------------------------------------------------------------- Poner / buscar música
        if (PlayVerb.IsMatch(n) || Has(p, "musica", "cancion", "tema"))
        {
            var rest = PlayVerb.Replace(n, "").Trim();
            string? service = null;
            var sm = Service.Match(" " + rest);
            if (sm.Success)
            {
                service = ServiceName(sm.Groups[1].Value);
                rest = Service.Replace(" " + rest, "").Trim();
            }
            rest = Regex.Replace(rest, @"^(?:algo de musica|algo de|un poco de|musica de|la musica de|la cancion|el tema|una cancion de|canciones de|temas de|un tema de|musica|la musica|cancion|el|la|los|las|un|una|algo|a)\s+", "").Trim();
            rest = Regex.Replace(rest, @"^(?:de|del)\s+", "").Trim();
            var generic = rest is "" or "musica" or "algo" or "algo de musica" or "la musica" or "musica por favor";
            if (PlayVerb.IsMatch(n) || generic) return new Command.Play(generic ? null : rest, service);
        }
        return new Command.Unknown(text);
    }

    private static string ServiceName(string raw) => raw switch
    {
        "spotify" => "spotify",
        "youtube music" => "youtube music",
        "deezer" => "deezer",
        _ => "youtube",
    };

    // ------------------------------------------------------------------ Palabra de activación

    /// <summary>Cómo puede transcribir el modelo «Lucy».</summary>
    public static readonly HashSet<string> WakeAliases = new() { "lucy", "luci", "lusi", "lucie", "luchi" };

    /**
     * Palabras parecidas o comunes que compiten con «luci» en la verificación: si dijiste otra cosa,
     * el modelo elige una de estas en vez de «luci» (así no se activa con cualquier palabra).
     */
    public static readonly string[] Competitors =
    {
        "luci", "lucio", "lucía", "luz", "luces", "lucha", "lunes", "lucas", "luis", "luisa", "lupe", "susi", "rosi",
        "dulce", "usted", "juicio", "lo", "hice", "dice", "si", "que", "de", "pone", "música", "musica", "este", "eso",
        "cuando", "bueno", "vamos", "todo", "algo", "pasa", "casa", "sí", "no", "hola", "oye", "luego", "lugar", "[unk]",
    };

    public static bool ContainsWakeWord(string transcript)
    {
        var tokens = Normalize("x " + transcript).Split(' ', StringSplitOptions.RemoveEmptyEntries);
        // Normalize quita «lucy» al principio; por eso se antepone «x».
        for (var i = 0; i < tokens.Length; i++)
        {
            if (WakeAliases.Contains(tokens[i])) return true;
            if (tokens[i] == "luz" && i + 1 < tokens.Length && tokens[i + 1] is "si" or "y" or "i") return true;
        }
        return false;
    }

    /// <summary>Autoprueba (se ejecuta en la integración continua con «Lucy.exe --selftest»).</summary>
    public static List<string> SelfTest()
    {
        var failures = new List<string>();
        void Expect(string text, Command expected)
        {
            var got = Parse(text);
            if (got != expected) failures.Add($"«{text}» -> {got} (esperado {expected})");
        }
        Expect("Lucy, poné música", new Command.Play(null, null));
        Expect("poné Soda Stereo en Spotify", new Command.Play("soda stereo", "spotify"));
        Expect("reproducí la canción de cerati", new Command.Play("cerati", null));
        Expect("buscá despacito en youtube", new Command.Play("despacito", "youtube"));
        Expect("pausá", new Command.Pause());
        Expect("seguí", new Command.Resume());
        Expect("pasá la canción", new Command.Next());
        Expect("la de antes", new Command.Previous());
        Expect("sacá la música", new Command.Stop());
        Expect("subí el volumen", new Command.VolumeUp(null));
        Expect("bajá el volumen un poco", new Command.VolumeDown(5));
        Expect("poné el volumen al 30", new Command.VolumeSet(30));
        Expect("volumen al cuarenta y cinco", new Command.VolumeSet(45));
        Expect("subí el volumen 10", new Command.VolumeUp(10));
        Expect("volumen al máximo", new Command.VolumeSet(100));
        Expect("silenciá", new Command.Mute());
        Expect("quitá el silencio", new Command.Unmute());
        Expect("qué está sonando", new Command.WhatIsPlaying());
        Expect("abrí spotify", new Command.OpenService("spotify"));
        Expect("cancelá", new Command.Cancel());
        if (!ContainsWakeWord("lucy")) failures.Add("no detecta «lucy»");
        if (!ContainsWakeWord("oye luci pone musica")) failures.Add("no detecta «luci»");
        if (ContainsWakeWord("luz del cuarto")) failures.Add("«luz del cuarto» no debería activar");
        return failures;
    }
}
