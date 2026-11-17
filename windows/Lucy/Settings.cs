using System;
using System.IO;
using System.Text.Json;
using Microsoft.Win32;

namespace Lucy;

/// <summary>Ajustes de Lucy, guardados en %APPDATA%\Lucy (no hace falta administrador).</summary>
public sealed class Settings
{
    /// <summary>Posición de la esfera (null = esquina superior derecha).</summary>
    public double? SphereLeft { get; set; }
    public double? SphereTop { get; set; }
    /// <summary>Tamaño de la esfera en píxeles.</summary>
    public double SphereSize { get; set; } = 170;
    /// <summary>Mostrar la esfera siempre (tenue cuando espera) o sólo al llamarla.</summary>
    public bool SphereAlwaysVisible { get; set; }
    /// <summary>youtube, youtube music o spotify.</summary>
    public string MusicService { get; set; } = "youtube";
    /// <summary>0 = estricta, 1 = muy sensible.</summary>
    public double Sensitivity { get; set; } = 0.5;
    public bool WakeWordEnabled { get; set; } = true;

    public static string Folder => Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "Lucy");
    private static string FilePath => Path.Combine(Folder, "settings.json");

    public static Settings Load()
    {
        try
        {
            if (File.Exists(FilePath)) return JsonSerializer.Deserialize<Settings>(File.ReadAllText(FilePath)) ?? new Settings();
        }
        catch
        {
            // Archivo dañado: se empieza de cero.
        }
        return new Settings();
    }

    public void Save()
    {
        try
        {
            Directory.CreateDirectory(Folder);
            File.WriteAllText(FilePath, JsonSerializer.Serialize(this, new JsonSerializerOptions { WriteIndented = true }));
        }
        catch
        {
            // Sin disco o sin permiso: no es grave.
        }
    }

    // ------------------------------------------------------------------ Iniciar con Windows (sólo para este usuario)

    private const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run";

    public static bool StartsWithWindows
    {
        get => Registry.CurrentUser.OpenSubKey(RunKey)?.GetValue("Lucy") is string;
        set
        {
            using var key = Registry.CurrentUser.CreateSubKey(RunKey);
            if (value) key.SetValue("Lucy", "\"" + Environment.ProcessPath + "\"");
            else key.DeleteValue("Lucy", false);
        }
    }
}
