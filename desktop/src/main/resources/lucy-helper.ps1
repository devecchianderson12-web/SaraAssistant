# Puente de Lucy con Windows (PowerShell 5.1, viene con Windows; no requiere administrador).
# Protocolo: una línea JSON por pedido {"id":1,"cmd":"...","args":{...}} y una por respuesta
# {"id":1,"ok":true,"result":...} o {"id":1,"ok":false,"error":"..."}.
param([string]$Mode = "system", [string]$Device = "")
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
[Console]::InputEncoding = New-Object System.Text.UTF8Encoding $false
[Console]::OutputEncoding = New-Object System.Text.UTF8Encoding $false
Add-Type -AssemblyName System.Speech
Add-Type -AssemblyName System.Security

# --- Micrófono con cancelación de eco (modo «mic») -------------------------------------------------
# El «Voice Capture DSP» de Windows (el de las llamadas): graba el micrófono y le resta lo que suena
# por los parlantes (música, la voz de Lucy), además de reducir ruido. Sale PCM 16 kHz mono 16 bits
# por la salida estándar, en binario, después de una línea «READY».
if ($Mode -eq 'mic') {
  Add-Type -TypeDefinition @"
using System;
using System.IO;
using System.Runtime.InteropServices;
using System.Threading;
namespace LucyMic {
  [ComImport, Guid("59eff8b9-938c-4a26-82f2-95cb84cdc837"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
  public interface IMediaBuffer {
    [PreserveSig] int SetLength(uint len);
    [PreserveSig] int GetMaxLength(out uint max);
    [PreserveSig] int GetBufferAndLength(out IntPtr buf, out uint len);
  }
  [ClassInterface(ClassInterfaceType.None), ComVisible(true)]
  public class Buffer : IMediaBuffer {
    public readonly IntPtr Data; public readonly uint Max; public uint Length;
    public Buffer(uint max) { Max = max; Data = Marshal.AllocHGlobal((int)max); }
    public int SetLength(uint len) { if (len > Max) return unchecked((int)0x80070057); Length = len; return 0; }
    public int GetMaxLength(out uint max) { max = Max; return 0; }
    public int GetBufferAndLength(out IntPtr buf, out uint len) { buf = Data; len = Length; return 0; }
  }
  [StructLayout(LayoutKind.Sequential)]
  public struct OutputBuffer { public IntPtr pBuffer; public uint dwStatus; public long rtTimestamp; public long rtTimelength; }
  [StructLayout(LayoutKind.Sequential)]
  public struct MediaType {
    public Guid majortype; public Guid subtype; public int bFixedSizeSamples; public int bTemporalCompression;
    public uint lSampleSize; public Guid formattype; public IntPtr pUnk; public uint cbFormat; public IntPtr pbFormat;
  }
  [ComImport, Guid("d8ad0f58-5494-4102-97c5-ec798e59bcf4"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
  public interface IMediaObject {
    [PreserveSig] int GetStreamCount(out int i, out int o);
    [PreserveSig] int GetInputStreamInfo(int s, out int f);
    [PreserveSig] int GetOutputStreamInfo(int s, out int f);
    [PreserveSig] int GetInputType(int s, int t, IntPtr mt);
    [PreserveSig] int GetOutputType(int s, int t, IntPtr mt);
    [PreserveSig] int SetInputType(int s, IntPtr mt, int f);
    [PreserveSig] int SetOutputType(int s, ref MediaType mt, int f);
    [PreserveSig] int GetInputCurrentType(int s, IntPtr mt);
    [PreserveSig] int GetOutputCurrentType(int s, IntPtr mt);
    [PreserveSig] int GetInputSizeInfo(int s, out int a, out int b, out int c);
    [PreserveSig] int GetOutputSizeInfo(int s, out int a, out int b);
    [PreserveSig] int GetInputMaxLatency(int s, out long l);
    [PreserveSig] int SetInputMaxLatency(int s, long l);
    [PreserveSig] int Flush();
    [PreserveSig] int Discontinuity(int s);
    [PreserveSig] int AllocateStreamingResources();
    [PreserveSig] int FreeStreamingResources();
    [PreserveSig] int GetInputStatus(int s, out int f);
    [PreserveSig] int ProcessInput(int s, IntPtr b, int f, long t, long l);
    [PreserveSig] int ProcessOutput(int flags, int count, [In, Out] OutputBuffer[] buffers, out int status);
    [PreserveSig] int Lock(int l);
  }
  [StructLayout(LayoutKind.Sequential)]
  public struct PropertyKey { public Guid fmtid; public int pid; public PropertyKey(Guid g, int p) { fmtid = g; pid = p; } }
  [StructLayout(LayoutKind.Explicit, Size = 24)]
  public struct PropVariant { [FieldOffset(0)] public ushort vt; [FieldOffset(8)] public int intVal; [FieldOffset(8)] public short boolVal; [FieldOffset(8)] public IntPtr ptr; }
  [ComImport, Guid("886d8eeb-8cf2-4446-8d02-cdba1dbdcf99"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
  public interface IPropertyStore {
    [PreserveSig] int GetCount(out int c);
    [PreserveSig] int GetAt(int i, out PropertyKey k);
    [PreserveSig] int GetValue(ref PropertyKey k, out PropVariant v);
    [PreserveSig] int SetValue(ref PropertyKey k, ref PropVariant v);
    [PreserveSig] int Commit();
  }
  [ComImport, Guid("0BD7A1BE-7A1A-44DB-8397-CC5392387B5E"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
  interface IMMDeviceCollection { [PreserveSig] int GetCount(out int n); [PreserveSig] int Item(int i, out IMMDevice d); }
  [ComImport, Guid("D666063F-1587-4E43-81F1-B948E807363F"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
  interface IMMDevice {
    [PreserveSig] int Activate(ref Guid id, int ctx, IntPtr p, [MarshalAs(UnmanagedType.IUnknown)] out object o);
    [PreserveSig] int OpenPropertyStore(int access, out IntPtr store);
    [PreserveSig] int GetId([MarshalAs(UnmanagedType.LPWStr)] out string id);
  }
  [ComImport, Guid("A95664D2-9614-4F35-A746-DE8DB63617E6"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
  interface IMMDeviceEnumerator {
    [PreserveSig] int EnumAudioEndpoints(int flow, int state, out IMMDeviceCollection c);
    [PreserveSig] int GetDefaultAudioEndpoint(int flow, int role, out IMMDevice d);
  }
  [ComImport, Guid("BCDE0395-E52F-467C-8E3D-C4579291692E")] class MMDeviceEnumeratorCom { }
  [ComImport, Guid("745057C7-F353-4F2D-A7EE-58434477730E")] class AecDmo { }

  public static class Capture {
    static readonly Guid AecProps = new Guid("6f52c567-0360-4bd2-9617-ccbf1421c939");

    // Posición del dispositivo predeterminado (de consola) en la lista de dispositivos activos.
    static int IndexOfDefault(int flow) {
      var en = (IMMDeviceEnumerator)(new MMDeviceEnumeratorCom());
      IMMDevice def; if (en.GetDefaultAudioEndpoint(flow, 0, out def) != 0) return -1;
      string defId; def.GetId(out defId);
      IMMDeviceCollection all; en.EnumAudioEndpoints(flow, 1, out all);
      int n; all.GetCount(out n);
      for (int i = 0; i < n; i++) { IMMDevice d; all.Item(i, out d); string id; d.GetId(out id); if (id == defId) return i; }
      return -1;
    }

    static readonly Guid DeviceProps = new Guid("a45c254e-df1c-4efd-8020-67d146a850e0");

    static string Prop(IMMDevice d, int pid) {
      IntPtr p; if (d.OpenPropertyStore(0, out p) != 0 || p == IntPtr.Zero) return "";
      try {
        var store = (IPropertyStore)Marshal.GetObjectForIUnknown(p);
        var key = new PropertyKey(DeviceProps, pid); PropVariant v;
        if (store.GetValue(ref key, out v) != 0 || v.vt != 31 || v.ptr == IntPtr.Zero) return "";
        return Marshal.PtrToStringUni(v.ptr) ?? "";
      } catch { return ""; } finally { Marshal.Release(p); }
    }

    // Micrófonos de parlantes/auriculares Bluetooth: abrirlos pone todo el audio en «modo llamada».
    public static bool IsBluetooth(string name, string enumerator) {
      var e = (enumerator ?? "").ToUpperInvariant();
      var n = (name ?? "").ToLowerInvariant();
      return e.StartsWith("BTH") || n.Contains("hands-free") || n.Contains("manos libres") || n.Contains("bluetooth");
    }

    // Qué micrófono usar: el elegido (por nombre) o el predeterminado, salvo que sea Bluetooth y haya otro.
    static int ChooseMic(string wanted, out string chosen) {
      chosen = "";
      var en = (IMMDeviceEnumerator)(new MMDeviceEnumeratorCom());
      IMMDeviceCollection all; if (en.EnumAudioEndpoints(1, 1, out all) != 0) return -1;
      int n; all.GetCount(out n);
      string defId = null; IMMDevice def; if (en.GetDefaultAudioEndpoint(1, 0, out def) == 0) def.GetId(out defId);
      int defIdx = -1, firstOther = -1, wantedIdx = -1;
      var names = new string[n];
      for (int i = 0; i < n; i++) {
        IMMDevice d; all.Item(i, out d); string id; d.GetId(out id);
        names[i] = Prop(d, 14);
        bool bt = IsBluetooth(names[i], Prop(d, 24));
        if (id == defId) defIdx = i;
        if (!bt && firstOther < 0) firstOther = i;
        if (!string.IsNullOrEmpty(wanted) && wantedIdx < 0 && names[i].Length > 0 && (names[i].StartsWith(wanted) || wanted.StartsWith(names[i]))) wantedIdx = i;
        if (id == defId && bt) defIdx = -2 - i;
      }
      int idx;
      if (wantedIdx >= 0) idx = wantedIdx;
      else if (defIdx >= 0) idx = defIdx;
      else if (defIdx <= -2) idx = firstOther >= 0 ? firstOther : (-2 - defIdx);
      else idx = firstOther;
      if (idx >= 0) chosen = names[idx];
      return idx;
    }

    static void Set(IPropertyStore ps, int pid, int value, bool isBool) {
      var key = new PropertyKey(AecProps, pid);
      var v = new PropVariant();
      if (isBool) { v.vt = 11; v.boolVal = (short)(value != 0 ? -1 : 0); } else { v.vt = 3; v.intVal = value; }
      int hr = ps.SetValue(ref key, ref v);
      if (hr != 0) throw new Exception("propiedad " + pid + ": 0x" + hr.ToString("X8"));
    }

    public static void Run(string device) {
      var dmo = (IMediaObject)(new AecDmo());
      var ps = (IPropertyStore)dmo;
      Set(ps, 3, 1, true);   // modo fuente: el DSP graba solo
      Set(ps, 2, 0, false);  // SINGLE_CHANNEL_AEC: cancelación de eco
      string chosen; int mic = ChooseMic(device, out chosen), spk = IndexOfDefault(0);
      if (mic >= 0 && spk >= 0) Set(ps, 4, (int)(((uint)spk << 16) | ((uint)mic & 0xFFFF)), false);
      var wf = Marshal.AllocHGlobal(18);
      var bytes = new byte[18];
      BitConverter.GetBytes((short)1).CopyTo(bytes, 0); BitConverter.GetBytes((short)1).CopyTo(bytes, 2);
      BitConverter.GetBytes(16000).CopyTo(bytes, 4); BitConverter.GetBytes(32000).CopyTo(bytes, 8);
      BitConverter.GetBytes((short)2).CopyTo(bytes, 12); BitConverter.GetBytes((short)16).CopyTo(bytes, 14);
      Marshal.Copy(bytes, 0, wf, 18);
      var mt = new MediaType {
        majortype = new Guid("73647561-0000-0010-8000-00AA00389B71"), subtype = new Guid("00000001-0000-0010-8000-00AA00389B71"),
        bFixedSizeSamples = 1, lSampleSize = 2, formattype = new Guid("05589f81-c356-11ce-bf01-00aa0055595a"), cbFormat = 18, pbFormat = wf,
      };
      int hr = dmo.SetOutputType(0, ref mt, 0);
      if (hr != 0) throw new Exception("SetOutputType 0x" + hr.ToString("X8"));
      hr = dmo.AllocateStreamingResources();
      if (hr != 0) throw new Exception("AllocateStreamingResources 0x" + hr.ToString("X8"));
      var buffer = new Buffer(32000);
      var ptr = Marshal.GetComInterfaceForObject(buffer, typeof(IMediaBuffer));
      var outs = new OutputBuffer[1];
      var stdout = Console.OpenStandardOutput();
      var ready = System.Text.Encoding.UTF8.GetBytes("READY " + chosen.Replace("\n", " ") + "\n");
      stdout.Write(ready, 0, ready.Length); stdout.Flush();
      var chunk = new byte[32000];
      while (true) {
        uint status;
        do {
          buffer.Length = 0;
          outs[0].pBuffer = ptr; outs[0].dwStatus = 0;
          int st; hr = dmo.ProcessOutput(0, 1, outs, out st);
          if (hr < 0) throw new Exception("ProcessOutput 0x" + hr.ToString("X8"));
          status = outs[0].dwStatus;
          if (buffer.Length > 0) {
            Marshal.Copy(buffer.Data, chunk, 0, (int)buffer.Length);
            stdout.Write(chunk, 0, (int)buffer.Length);
          }
        } while ((status & 0x01000000) != 0);
        stdout.Flush();
        Thread.Sleep(10);
      }
    }
  }
}
"@
  try { [LucyMic.Capture]::Run($Device) } catch { [Console]::Error.WriteLine($_.Exception.Message); exit 3 }
  exit 0
}

Add-Type -TypeDefinition @"
using System;
using System.Runtime.InteropServices;
namespace LucyWin {
  [Guid("5CDF2C82-841E-4546-9722-0CF74078229A"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
  interface IAudioEndpointVolume {
    int f1(); int f2(); int f3();
    int SetMasterVolumeLevel(float l, Guid g);
    int SetMasterVolumeLevelScalar(float l, Guid g);
    int GetMasterVolumeLevel(out float l);
    int GetMasterVolumeLevelScalar(out float l);
    int f8(); int f9(); int f10(); int f11();
    int SetMute([MarshalAs(UnmanagedType.Bool)] bool m, Guid g);
    int GetMute(out bool m);
  }
  [Guid("D666063F-1587-4E43-81F1-B948E807363F"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
  interface IMMDevice {
    int Activate(ref Guid id, int ctx, IntPtr p, [MarshalAs(UnmanagedType.IUnknown)] out object o);
    [PreserveSig] int OpenPropertyStore(int access, out IntPtr store);
  }
  [StructLayout(LayoutKind.Sequential)]
  public struct PKey { public Guid fmtid; public int pid; }
  [StructLayout(LayoutKind.Explicit, Size = 24)]
  public struct PVar { [FieldOffset(0)] public ushort vt; [FieldOffset(8)] public IntPtr ptr; }
  [ComImport, Guid("886d8eeb-8cf2-4446-8d02-cdba1dbdcf99"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
  interface IPropStore {
    [PreserveSig] int GetCount(out int c);
    [PreserveSig] int GetAt(int i, out PKey k);
    [PreserveSig] int GetValue(ref PKey k, out PVar v);
  }
  public static class Output {
    // ¿El audio sale por un parlante/auricular Bluetooth? (enumerador BTH… o nombre «Bluetooth»).
    public static bool IsBluetooth() {
      // Sin placa de sonido no hay salida predeterminada (Windows tira una excepción): no es Bluetooth.
      IMMDevice dev;
      try { var en = (IMMDeviceEnumerator)(new MMDeviceEnumeratorCom()); if (en.GetDefaultAudioEndpoint(0, 1, out dev) != 0 || dev == null) return false; }
      catch { return false; }
      IntPtr p; if (dev.OpenPropertyStore(0, out p) != 0 || p == IntPtr.Zero) return false;
      try {
        var store = (IPropStore)Marshal.GetObjectForIUnknown(p);
        foreach (var pid in new[] { 24, 14 }) {
          var key = new PKey { fmtid = new Guid("a45c254e-df1c-4efd-8020-67d146a850e0"), pid = pid }; PVar v;
          if (store.GetValue(ref key, out v) == 0 && v.vt == 31 && v.ptr != IntPtr.Zero) {
            var t = (Marshal.PtrToStringUni(v.ptr) ?? "").ToLowerInvariant();
            if (t.StartsWith("bth") || t.Contains("bluetooth") || t.Contains("hands-free")) return true;
          }
        }
        return false;
      } catch { return false; } finally { Marshal.Release(p); }
    }
  }
  [Guid("A95664D2-9614-4F35-A746-DE8DB63617E6"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
  interface IMMDeviceEnumerator { int f(); int GetDefaultAudioEndpoint(int flow, int role, out IMMDevice d); }
  [ComImport, Guid("BCDE0395-E52F-467C-8E3D-C4579291692E")] class MMDeviceEnumeratorCom { }
  public static class Audio {
    static IAudioEndpointVolume Ep() {
      var en = (IMMDeviceEnumerator)(new MMDeviceEnumeratorCom());
      IMMDevice dev; Marshal.ThrowExceptionForHR(en.GetDefaultAudioEndpoint(0, 1, out dev));
      var iid = typeof(IAudioEndpointVolume).GUID; object o;
      Marshal.ThrowExceptionForHR(dev.Activate(ref iid, 23, IntPtr.Zero, out o));
      return (IAudioEndpointVolume)o;
    }
    public static float GetVolume() { float v; Marshal.ThrowExceptionForHR(Ep().GetMasterVolumeLevelScalar(out v)); return v; }
    public static void SetVolume(float v) { Marshal.ThrowExceptionForHR(Ep().SetMasterVolumeLevelScalar(v, Guid.Empty)); }
    public static bool GetMute() { bool m; Marshal.ThrowExceptionForHR(Ep().GetMute(out m)); return m; }
    public static void SetMute(bool m) { Marshal.ThrowExceptionForHR(Ep().SetMute(m, Guid.Empty)); }
  }
  [Guid("77AA99A0-1BD6-484F-8BC7-2C654C9A9B6F"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
  interface IAudioSessionManager2 {
    int f1(); int f2();
    int GetSessionEnumerator(out IAudioSessionEnumerator e);
  }
  [Guid("E2F5BB11-0570-40CA-ACDD-3AA01277DEE8"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
  interface IAudioSessionEnumerator {
    int GetCount(out int n);
    int GetSession(int i, out IAudioSessionControl2 s);
  }
  [Guid("bfb7ff88-7239-4fc9-8fa2-07c950be9c6d"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
  interface IAudioSessionControl2 {
    int f1(); int f2(); int f3(); int f4(); int f5(); int f6(); int f7(); int f8(); int f9();
    int f10(); int f11();
    int GetProcessId(out uint pid);
  }
  [Guid("87CE5498-68D6-44E5-9215-6F4BE9CE5C9A"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
  interface ISimpleAudioVolume {
    int SetMasterVolume(float l, ref Guid g);
    int GetMasterVolume(out float l);
  }
  // Baja las otras apps mientras Lucy escucha o habla (como el foco de audio de Android).
  public static class Duck {
    static readonly System.Collections.Generic.Dictionary<uint, float> saved = new System.Collections.Generic.Dictionary<uint, float>();
    public static int Apply(bool on, uint[] exclude) {
      var en = (IMMDeviceEnumerator)(new MMDeviceEnumeratorCom());
      IMMDevice dev; Marshal.ThrowExceptionForHR(en.GetDefaultAudioEndpoint(0, 1, out dev));
      var iid = typeof(IAudioSessionManager2).GUID; object o;
      Marshal.ThrowExceptionForHR(dev.Activate(ref iid, 23, IntPtr.Zero, out o));
      IAudioSessionEnumerator list; Marshal.ThrowExceptionForHR(((IAudioSessionManager2)o).GetSessionEnumerator(out list));
      int n; list.GetCount(out n); int changed = 0; var empty = Guid.Empty;
      for (int i = 0; i < n; i++) {
        try {
          IAudioSessionControl2 s; list.GetSession(i, out s);
          uint pid; s.GetProcessId(out pid);
          if (pid == 0 || Array.IndexOf(exclude, pid) >= 0) continue;
          var vol = (ISimpleAudioVolume)s; float now; vol.GetMasterVolume(out now);
          if (on) { if (saved.ContainsKey(pid)) continue; saved[pid] = now; vol.SetMasterVolume(now * 0.25f, ref empty); changed++; }
          else if (saved.ContainsKey(pid)) { vol.SetMasterVolume(saved[pid], ref empty); changed++; }
        } catch { }
      }
      if (!on) saved.Clear();
      return changed;
    }
  }
  public static class Keys {
    [DllImport("user32.dll")] static extern void keybd_event(byte vk, byte scan, uint flags, UIntPtr extra);
    public static void Press(byte vk) { keybd_event(vk, 0, 1, UIntPtr.Zero); keybd_event(vk, 0, 3, UIntPtr.Zero); }
  }
}
"@

# --- Sesiones multimedia de Windows (las mismas del panel de volumen) por WinRT -----------------
$script:mediaOk = $false
try {
  Add-Type -AssemblyName System.Runtime.WindowsRuntime
  $null = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager, Windows.Media.Control, ContentType = WindowsRuntime]
  $script:asTask = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {
    $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1' })[0]
  $script:mediaOk = $true
} catch { }

function Await($op, [Type]$type) {
  $task = $script:asTask.MakeGenericMethod($type).Invoke($null, @($op))
  $null = $task.Wait(5000)
  return $task.Result
}

function Get-Session {
  if (-not $script:mediaOk) { return $null }
  $mgr = Await ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager]::RequestAsync()) ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager])
  $playing = @($mgr.GetSessions()) | Where-Object { $_.GetPlaybackInfo().PlaybackStatus -eq 'Playing' } | Select-Object -First 1
  if ($playing) { return $playing }
  return $mgr.GetCurrentSession()
}

function Media-Action([string]$action) {
  $s = Get-Session
  $vk = @{ play = 0xB3; pause = 0xB3; toggle = 0xB3; next = 0xB0; previous = 0xB1; stop = 0xB2 }[$action]
  if (-not $s) { [LucyWin.Keys]::Press([byte]$vk); return @{ session = $false; done = $true } }
  $playing = $s.GetPlaybackInfo().PlaybackStatus -eq 'Playing'
  switch ($action) {
    'play'     { if ($playing) { return @{ session = $true; done = $true; already = $true } }; $ok = Await ($s.TryPlayAsync()) ([bool]) }
    'pause'    { if (-not $playing) { return @{ session = $true; done = $false; nothing = $true } }; $ok = Await ($s.TryPauseAsync()) ([bool]) }
    'stop'     { if (-not $playing) { return @{ session = $true; done = $false; nothing = $true } }; $ok = Await ($s.TryStopAsync()) ([bool]); if (-not $ok) { $ok = Await ($s.TryPauseAsync()) ([bool]) } }
    'next'     { $ok = Await ($s.TrySkipNextAsync()) ([bool]) }
    'previous' { $ok = Await ($s.TrySkipPreviousAsync()) ([bool]) }
    default    { $ok = Await ($s.TryTogglePlayPauseAsync()) ([bool]) }
  }
  Start-Sleep -Milliseconds 350
  $s2 = Get-Session
  $nowPlaying = $false
  if ($s2) { $nowPlaying = $s2.GetPlaybackInfo().PlaybackStatus -eq 'Playing' }
  return @{ session = $true; done = [bool]$ok; playing = $nowPlaying; app = $s.SourceAppUserModelId }
}

function Media-Info {
  $s = Get-Session
  if (-not $s) { return @{ session = $false } }
  $p = Await ($s.TryGetMediaPropertiesAsync()) ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionMediaProperties])
  return @{ session = $true; title = $p.Title; artist = $p.Artist; app = $s.SourceAppUserModelId;
            playing = ($s.GetPlaybackInfo().PlaybackStatus -eq 'Playing') }
}

# --- Wi-Fi y Bluetooth (API de radios de Windows: no requiere administrador) ----------------------
function Radio([string]$kind, $state) {
  if (-not $script:asTask) { throw 'Windows no permite controlar las radios.' }
  $null = [Windows.Devices.Radios.Radio, Windows.System.Devices, ContentType = WindowsRuntime]
  $null = Await ([Windows.Devices.Radios.Radio]::RequestAccessAsync()) ([Windows.Devices.Radios.RadioAccessStatus])
  $radios = Await ([Windows.Devices.Radios.Radio]::GetRadiosAsync()) ([System.Collections.Generic.IReadOnlyList[Windows.Devices.Radios.Radio]])
  $r = $radios | Where-Object { [string]$_.Kind -eq $kind } | Select-Object -First 1
  if (-not $r) { return @{ present = $false } }
  if ($state -eq 'on' -or $state -eq 'off') {
    $target = if ($state -eq 'on') { 'On' } else { 'Off' }
    $res = Await ($r.SetStateAsync($target)) ([Windows.Devices.Radios.RadioAccessStatus])
    Start-Sleep -Milliseconds 600
    return @{ present = $true; state = [string]$r.State; access = [string]$res }
  }
  return @{ present = $true; state = [string]$r.State }
}

# --- Voz (SAPI) ----------------------------------------------------------------------------------
$script:synth = New-Object System.Speech.Synthesis.SpeechSynthesizer

function Voices {
  return @($script:synth.GetInstalledVoices() | Where-Object { $_.Enabled } | ForEach-Object {
    @{ name = $_.VoiceInfo.Name; culture = $_.VoiceInfo.Culture.Name; gender = [string]$_.VoiceInfo.Gender } })
}

function Speak($text, $voice, $rate, $volume) {
  if ($voice) { try { $script:synth.SelectVoice($voice) } catch { } }
  elseif (-not $script:voicePicked) {
    $es = $script:synth.GetInstalledVoices() | Where-Object { $_.Enabled -and $_.VoiceInfo.Culture.TwoLetterISOLanguageName -eq 'es' }
    $pick = ($es | Where-Object { [string]$_.VoiceInfo.Gender -eq 'Female' } | Select-Object -First 1)
    if (-not $pick) { $pick = $es | Select-Object -First 1 }
    if ($pick) { $script:synth.SelectVoice($pick.VoiceInfo.Name) }
    $script:voicePicked = $true
  }
  $script:synth.Rate = [int]$rate
  $script:synth.Volume = [int]$volume
  $script:synth.Speak([string]$text)
  return @{ voice = $script:synth.Voice.Name }
}

# --- Sistema ----------------------------------------------------------------------------------------
function Battery {
  $b = Get-CimInstance Win32_Battery | Select-Object -First 1
  if (-not $b) { return @{ present = $false } }
  return @{ present = $true; percent = [int]$b.EstimatedChargeRemaining; charging = ($b.BatteryStatus -eq 2 -or $b.BatteryStatus -ge 6) }
}

function Brightness($set) {
  if ($null -ne $set) {
    $m = Get-CimInstance -Namespace root/WMI -ClassName WmiMonitorBrightnessMethods | Select-Object -First 1
    if (-not $m) { return @{ supported = $false } }
    $null = Invoke-CimMethod -InputObject $m -MethodName WmiSetBrightness -Arguments @{ Timeout = 0; Brightness = [byte]$set }
  }
  $b = Get-CimInstance -Namespace root/WMI -ClassName WmiMonitorBrightness | Select-Object -First 1
  if (-not $b) { return @{ supported = $false } }
  return @{ supported = $true; percent = [int]$b.CurrentBrightness }
}

function Info {
  $os = Get-CimInstance Win32_OperatingSystem
  $disk = Get-CimInstance Win32_LogicalDisk -Filter "DeviceID='$($env:SystemDrive)'"
  return @{ os = $os.Caption; computer = $env:COMPUTERNAME;
            freeMemMb = [int]($os.FreePhysicalMemory / 1024); totalMemMb = [int]($os.TotalVisibleMemorySize / 1024);
            freeDiskGb = [math]::Round($disk.FreeSpace / 1GB, 1); totalDiskGb = [math]::Round($disk.Size / 1GB, 0) }
}

# Cierra con elegancia (como la X de la ventana: si hay algo sin guardar, la app pregunta) las apps
# con ventana, menos la que está en primer plano, el Explorador y las de Lucy.
Add-Type -TypeDefinition @"
using System; using System.Runtime.InteropServices;
public static class LucyFg {
  [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
  [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr h, out uint pid);
  public static uint Pid() { uint p; GetWindowThreadProcessId(GetForegroundWindow(), out p); return p; }
}
"@
function Close-Apps($exclude) {
  $keep = @('explorer','ShellExperienceHost','StartMenuExperienceHost','SearchHost','TextInputHost','LockApp','Lucy','java','javaw','powershell','pwsh','WindowsTerminal','dwm','ApplicationFrameHost')
  $fg = [LucyFg]::Pid()
  $closed = @()
  Get-Process | Where-Object { $_.MainWindowHandle -ne 0 -and $_.Id -ne $fg -and $_.Id -ne $PID -and ($exclude -notcontains $_.Id) -and ($keep -notcontains $_.ProcessName) } | ForEach-Object {
    try { if ($_.CloseMainWindow()) { $closed += $_.ProcessName } } catch { }
  }
  return @{ closed = @($closed | Select-Object -Unique) }
}

function Apps { return @(Get-StartApps | ForEach-Object { @{ name = $_.Name; id = $_.AppID } }) }

function Protect([string]$text) {
  $bytes = [Text.Encoding]::UTF8.GetBytes($text)
  return [Convert]::ToBase64String([Security.Cryptography.ProtectedData]::Protect($bytes, $null, 'CurrentUser'))
}
function Unprotect([string]$b64) {
  $bytes = [Security.Cryptography.ProtectedData]::Unprotect([Convert]::FromBase64String($b64), $null, 'CurrentUser')
  return [Text.Encoding]::UTF8.GetString($bytes)
}

# --- Bucle principal ------------------------------------------------------------------------------
[Console]::Out.WriteLine('{"ready":true,"mode":"' + $Mode + '"}')
[Console]::Out.Flush()
while ($true) {
  $line = [Console]::In.ReadLine()
  if ($null -eq $line) { break }
  if ($line.Trim().Length -eq 0) { continue }
  $id = 0
  try {
    $req = $line | ConvertFrom-Json
    $id = $req.id
    $a = $req.args
    $result = switch ($req.cmd) {
      'ping'       { 'pong' }
      'speak'      { Speak $a.text $a.voice $a.rate $a.volume }
      'pid'        { $PID }
      'speakToWav' {
        # Para la autoprueba: la voz de Windows grabada a 16 kHz mono (lo que oiría el micrófono).
        $fmt = New-Object System.Speech.AudioFormat.SpeechAudioFormatInfo(16000, [System.Speech.AudioFormat.AudioBitsPerSample]::Sixteen, [System.Speech.AudioFormat.AudioChannel]::Mono)
        if ($a.voice) { try { $script:synth.SelectVoice($a.voice) } catch { } }
        $script:synth.SetOutputToWaveFile($a.path, $fmt)
        try { $script:synth.Speak([string]$a.text) } finally { try { $script:synth.SetOutputToDefaultAudioDevice() } catch { $script:synth.SetOutputToNull() } }
        @{ voice = $script:synth.Voice.Name }
      }
      'voices'     { ,(Voices) }
      'volume'     { @{ percent = [int][math]::Round([LucyWin.Audio]::GetVolume() * 100); muted = [LucyWin.Audio]::GetMute() } }
      'setVolume'  { [LucyWin.Audio]::SetMute($false); [LucyWin.Audio]::SetVolume([float]$a.percent / 100); @{ percent = [int][math]::Round([LucyWin.Audio]::GetVolume() * 100) } }
      'duck'       { @{ changed = [LucyWin.Duck]::Apply([bool]$a.on, [uint32[]]@($a.exclude | ForEach-Object { [uint32]$_ })) } }
      'setMute'    { [LucyWin.Audio]::SetMute([bool]$a.muted); @{ muted = [LucyWin.Audio]::GetMute() } }
      'media'      { Media-Action $a.action }
      'mediaInfo'  { Media-Info }
      'apps'       { ,(Apps) }
      'closeApps'  { Close-Apps @($a.exclude) }
      'openApp'    { Start-Process ("shell:AppsFolder\" + $a.id); @{ opened = $true } }
      'open'       { Start-Process $a.target; @{ opened = $true } }
      'battery'    { Battery }
      'btOutput'   { @{ bluetooth = [LucyWin.Output]::IsBluetooth() } }
      'lock'       { Start-Process rundll32.exe -ArgumentList 'user32.dll,LockWorkStation'; @{ locked = $true } }
      'radio'      { Radio $a.kind $a.state }
      'brightness' { Brightness $a.percent }
      'info'       { Info }
      'protect'    { Protect $a.text }
      'unprotect'  { Unprotect $a.text }
      default      { throw "comando desconocido: $($req.cmd)" }
    }
    $out = @{ id = $id; ok = $true; result = $result } | ConvertTo-Json -Compress -Depth 6
  } catch {
    $out = @{ id = $id; ok = $false; error = $_.Exception.Message } | ConvertTo-Json -Compress -Depth 3
  }
  [Console]::Out.WriteLine($out)
  [Console]::Out.Flush()
}
