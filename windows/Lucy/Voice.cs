using System;
using System.Collections.Generic;
using System.IO;
using System.IO.Compression;
using System.Linq;
using System.Net.Http;
using System.Reflection;
using System.Speech.Synthesis;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;
using NAudio.Wave;
using Vosk;

namespace Lucy;

/// <summary>Modelo de voz en español (Vosk, 100 % local). Viene dentro del .exe; si no, se descarga.</summary>
public static class ModelStore
{
    public static string Folder => Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Lucy", "vosk-model-es");
    private const string DownloadUrl = "https://alphacephei.com/vosk/models/vosk-model-small-es-0.42.zip";

    public static bool Ready => File.Exists(Path.Combine(Folder, "am", "final.mdl")) || File.Exists(Path.Combine(Folder, "final.mdl"));

    public static async Task<bool> EnsureAsync(Action<string> status)
    {
        if (Ready) return true;
        var zip = Path.Combine(Path.GetTempPath(), "lucy-model.zip");
        try
        {
            using (var embedded = Assembly.GetExecutingAssembly().GetManifestResourceStream("model.zip"))
            {
                if (embedded != null)
                {
                    status("Preparando la voz (una sola vez)…");
                    using var file = File.Create(zip);
                    await embedded.CopyToAsync(file);
                }
                else
                {
                    status("Descargando el modelo de voz (40 MB, una sola vez)…");
                    using var http = new HttpClient { Timeout = TimeSpan.FromMinutes(10) };
                    await using var stream = await http.GetStreamAsync(DownloadUrl);
                    using var file = File.Create(zip);
                    await stream.CopyToAsync(file);
                }
            }
            var temp = Folder + ".tmp";
            if (Directory.Exists(temp)) Directory.Delete(temp, true);
            ZipFile.ExtractToDirectory(zip, temp);
            // El zip trae una carpeta raíz (vosk-model-small-es-0.42/): se aplana.
            var root = Directory.GetDirectories(temp).Length == 1 && Directory.GetFiles(temp).Length == 0 ? Directory.GetDirectories(temp)[0] : temp;
            if (Directory.Exists(Folder)) Directory.Delete(Folder, true);
            Directory.CreateDirectory(Path.GetDirectoryName(Folder)!);
            Directory.Move(root, Folder);
            if (Directory.Exists(temp)) Directory.Delete(temp, true);
            return Ready;
        }
        catch (Exception e)
        {
            status("No pude preparar el modelo de voz: " + e.Message);
            return false;
        }
        finally
        {
            try { File.Delete(zip); } catch { }
        }
    }
}

public enum VoiceState { Idle, Listening, Thinking, Speaking }

/// <summary>
/// Oído de Lucy: escucha el micrófono, detecta «Lucy» en dos etapas (gramática cerrada +
/// verificación con transcripción libre) y después transcribe la orden hasta que dejás de hablar.
/// </summary>
public sealed class Listener : IDisposable
{
    public const int SampleRate = 16000;
    private const int ChunkMs = 100;
    private const int WindowChunks = 24; // 2,4 s para verificar

    private readonly Model model;
    private readonly WaveInEvent mic;
    private readonly object gate = new();
    private VoskRecognizer wake;
    private VoskRecognizer? command;
    private readonly Queue<byte[]> window = new();
    private int hits;
    private int cooldown;
    private DateTime commandStarted;
    private DateTime lastChange;
    private string lastPartial = "";
    private bool heardSpeech;

    public Func<double> Sensitivity { get; set; } = () => 0.5;
    public Func<bool> WakeEnabled { get; set; } = () => true;
    /// <summary>Volumen del micrófono (0..1) para la esfera.</summary>
    public event Action<float>? Level;
    public event Action? WakeDetected;
    public event Action<string>? Partial;
    /// <summary>La orden terminó (texto vacío = no se escuchó nada).</summary>
    public event Action<string>? CommandHeard;

    public bool Paused { get; set; }

    public Listener()
    {
        Vosk.Vosk.SetLogLevel(-1);
        model = new Model(ModelStore.Folder);
        wake = NewWakeRecognizer();
        mic = new WaveInEvent { WaveFormat = new WaveFormat(SampleRate, 16, 1), BufferMilliseconds = ChunkMs, NumberOfBuffers = 4 };
        mic.DataAvailable += OnData;
    }

    private VoskRecognizer NewWakeRecognizer()
    {
        var grammar = JsonSerializer.Serialize(new[] { "luci", "[unk]" });
        var r = new VoskRecognizer(model, SampleRate, grammar);
        r.SetWords(true);
        return r;
    }

    public void Start() => mic.StartRecording();

    /// <summary>Escuchar una orden ya (botón o atajo de teclado), sin decir «Lucy».</summary>
    public void ListenNow()
    {
        lock (gate)
        {
            command?.Dispose();
            command = new VoskRecognizer(model, SampleRate);
            commandStarted = DateTime.UtcNow;
            lastChange = commandStarted;
            lastPartial = "";
            heardSpeech = false;
        }
    }

    public void CancelCommand()
    {
        lock (gate)
        {
            command?.Dispose();
            command = null;
        }
    }

    private void OnData(object? sender, WaveInEventArgs e)
    {
        var buffer = new byte[e.BytesRecorded];
        Array.Copy(e.Buffer, buffer, e.BytesRecorded);
        Level?.Invoke(LevelOf(buffer));
        if (Paused) return;
        lock (gate)
        {
            if (command != null) { FeedCommand(buffer); return; }
            if (!WakeEnabled()) return;
            window.Enqueue(buffer);
            while (window.Count > WindowChunks) window.Dequeue();
            if (cooldown > 0) { cooldown--; return; }

            var candidate = false;
            if (wake.AcceptWaveform(buffer, buffer.Length))
            {
                candidate = Interpreter.ContainsWakeWord(TextOf(wake.Result(), "text"));
                hits = 0;
            }
            else
            {
                var partial = TextOf(wake.PartialResult(), "partial");
                hits = Interpreter.ContainsWakeWord(partial) ? hits + 1 : 0;
                var needed = Sensitivity() >= 0.7 ? 1 : Sensitivity() >= 0.3 ? 2 : 3;
                candidate = hits >= needed;
            }
            if (!candidate) return;
            hits = 0;
            if (!Verify())
            {
                cooldown = 6;
                return;
            }
            wake.Dispose();
            wake = NewWakeRecognizer();
            window.Clear();
        }
        WakeDetected?.Invoke();
    }

    /// <summary>Segunda etapa sobre los últimos 2,4 s.</summary>
    private bool Verify()
    {
        // Sensibilidad media o más: alcanza con la primera etapa («luci» estable). Baja: además verifica.
        if (Sensitivity() >= 0.45) return true;
        return VerifyAudio(model, window.SelectMany(b => b).ToArray());
    }

    /// <summary>«Luci» tiene que ganarle a palabras parecidas y comunes (el modelo chico no la escribe en texto libre).</summary>
    public static bool VerifyAudio(Model model, byte[] audio)
    {
        using var rec = new VoskRecognizer(model, SampleRate, JsonSerializer.Serialize(Interpreter.Competitors));
        rec.AcceptWaveform(audio, audio.Length);
        return TextOf(rec.FinalResult(), "text").Split(' ').Contains("luci");
    }

    private void FeedCommand(byte[] buffer)
    {
        var cmd = command!;
        string? done = null;
        if (cmd.AcceptWaveform(buffer, buffer.Length))
        {
            var t = TextOf(cmd.Result(), "text");
            if (t.Length > 0) done = t;
        }
        else
        {
            var partial = TextOf(cmd.PartialResult(), "partial");
            if (partial.Length > 0 && partial != lastPartial)
            {
                heardSpeech = true;
                lastPartial = partial;
                lastChange = DateTime.UtcNow;
                Partial?.Invoke(partial);
            }
        }
        var now = DateTime.UtcNow;
        if (done == null)
        {
            if (!heardSpeech && (now - commandStarted).TotalSeconds > 6) done = "";
            else if (heardSpeech && (now - lastChange).TotalMilliseconds > 1500) done = TextOf(cmd.FinalResult(), "text");
            else if ((now - commandStarted).TotalSeconds > 15) done = TextOf(cmd.FinalResult(), "text");
        }
        if (done == null) return;
        cmd.Dispose();
        command = null;
        var heard = done;
        ThreadPool.QueueUserWorkItem(_ => CommandHeard?.Invoke(heard));
    }

    internal static string TextOf(string json, string field)
    {
        try
        {
            using var doc = JsonDocument.Parse(json);
            return doc.RootElement.TryGetProperty(field, out var v) ? v.GetString()?.Trim() ?? "" : "";
        }
        catch
        {
            return "";
        }
    }

    private static float LevelOf(byte[] pcm)
    {
        double sum = 0;
        var n = pcm.Length / 2;
        if (n == 0) return 0;
        for (var i = 0; i + 1 < pcm.Length; i += 2)
        {
            var s = (short)(pcm[i] | (pcm[i + 1] << 8));
            sum += s * (double)s;
        }
        var rms = Math.Sqrt(sum / n);
        var db = 20 * Math.Log10(Math.Max(rms, 1) / 32768.0);
        return (float)Math.Clamp((db + 60) / 60, 0, 1);
    }

    public void Dispose()
    {
        mic.StopRecording();
        mic.Dispose();
        wake.Dispose();
        command?.Dispose();
        model.Dispose();
    }
}

/// <summary>Voz de Lucy: voces instaladas de Windows (prefiere español, femenina).</summary>
public sealed class Speaker : IDisposable
{
    private readonly SpeechSynthesizer synth = new();

    public Speaker()
    {
        var voices = synth.GetInstalledVoices().Where(v => v.Enabled).Select(v => v.VoiceInfo).ToList();
        var spanish = voices.Where(v => v.Culture.TwoLetterISOLanguageName == "es").ToList();
        var pick = spanish.FirstOrDefault(v => v.Gender == VoiceGender.Female) ?? spanish.FirstOrDefault() ?? voices.FirstOrDefault();
        if (pick != null) synth.SelectVoice(pick.Name);
        synth.Rate = 1;
    }

    public string VoiceName => synth.Voice?.Name ?? "(sin voz)";
    public bool HasSpanish => synth.Voice?.Culture.TwoLetterISOLanguageName == "es";

    public Task SpeakAsync(string text)
    {
        var done = new TaskCompletionSource();
        void Handler(object? s, SpeakCompletedEventArgs e)
        {
            synth.SpeakCompleted -= Handler;
            done.TrySetResult();
        }
        synth.SpeakCompleted += Handler;
        synth.SpeakAsync(text);
        return done.Task;
    }

    public void Stop() => synth.SpeakAsyncCancelAll();

    public void Dispose() => synth.Dispose();
}
