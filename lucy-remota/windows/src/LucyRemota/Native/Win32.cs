using System.Runtime.InteropServices;

namespace LucyRemota.Native;

/// <summary>Funciones de Windows que se usan directamente (sin PowerShell: más rápido y sin consola).</summary>
static partial class Win32
{
    [LibraryImport("user32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    public static partial bool LockWorkStation();

    [LibraryImport("user32.dll", EntryPoint = "SendMessageTimeoutW")]
    public static partial nint SendMessageTimeout(nint hWnd, uint msg, nint wParam, nint lParam, uint flags, uint timeout, out nint result);

    [LibraryImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    public static partial bool SetForegroundWindow(nint hWnd);

    [LibraryImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    public static partial bool ShowWindow(nint hWnd, int cmd);

    [LibraryImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    public static partial bool IsIconic(nint hWnd);

    [LibraryImport("user32.dll")]
    public static partial void keybd_event(byte vk, byte scan, uint flags, nuint extra);

    [LibraryImport("powrprof.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    public static partial bool SetSuspendState([MarshalAs(UnmanagedType.Bool)] bool hibernate, [MarshalAs(UnmanagedType.Bool)] bool force, [MarshalAs(UnmanagedType.Bool)] bool disableWake);

    [StructLayout(LayoutKind.Sequential)]
    public struct MemoryStatusEx
    {
        public uint Length, MemoryLoad;
        public ulong TotalPhys, AvailPhys, TotalPageFile, AvailPageFile, TotalVirtual, AvailVirtual, AvailExtendedVirtual;
    }

    [LibraryImport("kernel32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    public static partial bool GlobalMemoryStatusEx(ref MemoryStatusEx status);

    public const int SW_RESTORE = 9;
    public const uint WM_SYSCOMMAND = 0x0112;
    public const nint SC_MONITORPOWER = 0xF170;
    public const nint HWND_BROADCAST = 0xFFFF;
    public const uint SMTO_ABORTIFHUNG = 0x0002;
    public const byte VK_MENU = 0x12;
    public const uint KEYEVENTF_KEYUP = 0x0002;

    /// <summary>Trae una ventana al frente (Windows lo permite si antes se «toca» Alt).</summary>
    public static void BringToFront(nint hWnd)
    {
        if (IsIconic(hWnd)) ShowWindow(hWnd, SW_RESTORE);
        keybd_event(VK_MENU, 0, 0, 0);
        keybd_event(VK_MENU, 0, KEYEVENTF_KEYUP, 0);
        SetForegroundWindow(hWnd);
    }
}

// ---------------------------------------------------------------------- Volumen (Core Audio)

[ComImport, Guid("BCDE0395-E52F-467C-8E3D-C4579291692E")]
class MMDeviceEnumeratorCom { }

[ComImport, Guid("A95664D2-9614-4F35-A746-DE8DB63617E6"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
interface IMMDeviceEnumerator
{
    [PreserveSig] int EnumAudioEndpoints(int dataFlow, int stateMask, out nint devices);
    [PreserveSig] int GetDefaultAudioEndpoint(int dataFlow, int role, out IMMDevice endpoint);
}

[ComImport, Guid("D666063F-1587-4E43-81F1-B948E807363F"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
interface IMMDevice
{
    [PreserveSig] int Activate(ref Guid iid, int clsCtx, nint activationParams, [MarshalAs(UnmanagedType.IUnknown)] out object iface);
}

[ComImport, Guid("5CDF2C82-841E-4546-9722-0CF74078229A"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
interface IAudioEndpointVolume
{
    [PreserveSig] int RegisterControlChangeNotify(nint notify);
    [PreserveSig] int UnregisterControlChangeNotify(nint notify);
    [PreserveSig] int GetChannelCount(out uint count);
    [PreserveSig] int SetMasterVolumeLevel(float levelDb, ref Guid context);
    [PreserveSig] int SetMasterVolumeLevelScalar(float level, ref Guid context);
    [PreserveSig] int GetMasterVolumeLevel(out float levelDb);
    [PreserveSig] int GetMasterVolumeLevelScalar(out float level);
    [PreserveSig] int SetChannelVolumeLevel(uint channel, float levelDb, ref Guid context);
    [PreserveSig] int SetChannelVolumeLevelScalar(uint channel, float level, ref Guid context);
    [PreserveSig] int GetChannelVolumeLevel(uint channel, out float levelDb);
    [PreserveSig] int GetChannelVolumeLevelScalar(uint channel, out float level);
    [PreserveSig] int SetMute([MarshalAs(UnmanagedType.Bool)] bool mute, ref Guid context);
    [PreserveSig] int GetMute([MarshalAs(UnmanagedType.Bool)] out bool mute);
}

/// <summary>Volumen general de Windows (el del ícono del parlante).</summary>
static class SystemVolume
{
    static IAudioEndpointVolume Endpoint()
    {
        var enumerator = (IMMDeviceEnumerator)new MMDeviceEnumeratorCom();
        Marshal.ThrowExceptionForHR(enumerator.GetDefaultAudioEndpoint(0, 1, out var device));
        var iid = typeof(IAudioEndpointVolume).GUID;
        Marshal.ThrowExceptionForHR(device.Activate(ref iid, 23, 0, out var o));
        return (IAudioEndpointVolume)o;
    }

    public static int Get()
    {
        Marshal.ThrowExceptionForHR(Endpoint().GetMasterVolumeLevelScalar(out var v));
        return (int)Math.Round(v * 100);
    }

    public static int Set(int percent)
    {
        var ep = Endpoint();
        var ctx = Guid.Empty;
        if (percent > 0) ep.SetMute(false, ref ctx);
        Marshal.ThrowExceptionForHR(ep.SetMasterVolumeLevelScalar(Math.Clamp(percent, 0, 100) / 100f, ref ctx));
        Marshal.ThrowExceptionForHR(ep.GetMasterVolumeLevelScalar(out var v));
        return (int)Math.Round(v * 100);
    }

    public static bool Muted()
    {
        Marshal.ThrowExceptionForHR(Endpoint().GetMute(out var m));
        return m;
    }

    public static bool SetMuted(bool muted)
    {
        var ep = Endpoint();
        var ctx = Guid.Empty;
        Marshal.ThrowExceptionForHR(ep.SetMute(muted, ref ctx));
        Marshal.ThrowExceptionForHR(ep.GetMute(out var m));
        return m;
    }
}

/// <summary>
/// Ejecuta código en un hilo STA propio: el Shell de Windows (lista de apps, minimizar todo) es COM
/// de apartamento único y así responde rápido y sin bloquear la ventana.
/// </summary>
static class Sta
{
    public static Task<T> Run<T>(Func<T> work)
    {
        var tcs = new TaskCompletionSource<T>(TaskCreationOptions.RunContinuationsAsynchronously);
        var t = new Thread(() =>
        {
            try { tcs.SetResult(work()); }
            catch (Exception e) { tcs.SetException(e); }
        }) { IsBackground = true, Name = "lucy-shell" };
        t.SetApartmentState(ApartmentState.STA);
        t.Start();
        return tcs.Task;
    }
}
