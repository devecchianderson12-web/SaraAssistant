using System.Runtime.InteropServices;
using System.Text;

namespace LucyRemota.Native;

/// <summary>Ventanas abiertas, su identidad de app (AUMID) y teclado simulado.</summary>
static class WinUi
{
    delegate bool EnumProc(nint hWnd, nint lParam);

    [DllImport("user32.dll")] static extern bool EnumWindows(EnumProc proc, nint lParam);
    [DllImport("user32.dll")] static extern bool IsWindowVisible(nint hWnd);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] static extern int GetWindowText(nint hWnd, StringBuilder text, int max);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] static extern int GetClassName(nint hWnd, StringBuilder text, int max);
    [DllImport("user32.dll")] static extern nint GetForegroundWindow();
    [DllImport("user32.dll")] static extern bool GetWindowRect(nint hWnd, out Rect rect);
    [DllImport("user32.dll", SetLastError = true)] static extern uint SendInput(uint count, Input[] inputs, int size);
    [DllImport("shell32.dll")] static extern int SHGetPropertyStoreForWindow(nint hWnd, ref Guid iid, [MarshalAs(UnmanagedType.Interface)] out IPropertyStore store);
    [DllImport("ole32.dll")] static extern int PropVariantClear(ref PropVariant pv);

    [StructLayout(LayoutKind.Sequential)] public struct Rect { public int Left, Top, Right, Bottom; }

    [StructLayout(LayoutKind.Sequential)]
    struct PropertyKey { public Guid Fmtid; public uint Pid; }

    [StructLayout(LayoutKind.Explicit, Size = 24)]
    struct PropVariant { [FieldOffset(0)] public ushort Vt; [FieldOffset(8)] public nint Ptr; }

    [ComImport, Guid("886D8EEB-8CF2-4446-8D02-CDBA1DBDCF99"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface IPropertyStore
    {
        [PreserveSig] int GetCount(out uint count);
        [PreserveSig] int GetAt(uint index, out PropertyKey key);
        [PreserveSig] int GetValue(ref PropertyKey key, out PropVariant value);
        [PreserveSig] int SetValue(ref PropertyKey key, ref PropVariant value);
        [PreserveSig] int Commit();
    }

    [StructLayout(LayoutKind.Sequential)]
    struct KeybdInput { public ushort Vk, Scan; public uint Flags, Time; public nint Extra; }

    [StructLayout(LayoutKind.Explicit)]
    struct InputUnion { [FieldOffset(0)] public KeybdInput Ki; [FieldOffset(0)] public MouseFiller Pad; }

    [StructLayout(LayoutKind.Sequential)]
    struct MouseFiller { public int Dx, Dy; public uint Data, Flags, Time; public nint Extra; }

    [StructLayout(LayoutKind.Sequential)]
    struct Input { public uint Type; public InputUnion U; }

    const uint INPUT_KEYBOARD = 1, KEYEVENTF_KEYUP = 0x2, KEYEVENTF_UNICODE = 0x4;
    public const ushort VK_RETURN = 0x0D, VK_ESCAPE = 0x1B, VK_CONTROL = 0x11, VK_A = 0x41;

    /// <summary>Ventanas visibles de primer nivel.</summary>
    public static List<nint> TopLevel()
    {
        var list = new List<nint>();
        EnumWindows((h, _) => { if (IsWindowVisible(h)) list.Add(h); return true; }, 0);
        return list;
    }

    public static string Title(nint h)
    {
        var sb = new StringBuilder(512);
        GetWindowText(h, sb, sb.Capacity);
        return sb.ToString();
    }

    public static string ClassName(nint h)
    {
        var sb = new StringBuilder(256);
        GetClassName(h, sb, sb.Capacity);
        return sb.ToString();
    }

    public static Rect Bounds(nint h) => GetWindowRect(h, out var r) ? r : default;

    public static bool IsForeground(nint h) => GetForegroundWindow() == h;

    /// <summary>Identidad de app de la ventana (la de una app web instalada: «Chrome._crx_…»).</summary>
    public static string? AppId(nint h)
    {
        var iid = typeof(IPropertyStore).GUID;
        if (SHGetPropertyStoreForWindow(h, ref iid, out var store) != 0 || store == null) return null;
        try
        {
            var key = new PropertyKey { Fmtid = new Guid("9F4C2855-9F79-4B39-A8D0-E1D42DE1D5F3"), Pid = 5 };
            if (store.GetValue(ref key, out var pv) != 0) return null;
            try { return pv.Vt == 31 && pv.Ptr != 0 ? Marshal.PtrToStringUni(pv.Ptr) : null; }
            finally { PropVariantClear(ref pv); }
        }
        finally { Marshal.ReleaseComObject(store); }
    }

    static Input Key(ushort vk, ushort scan, uint flags) => new() { Type = INPUT_KEYBOARD, U = new InputUnion { Ki = new KeybdInput { Vk = vk, Scan = scan, Flags = flags } } };

    static void Send(params Input[] inputs) => SendInput((uint)inputs.Length, inputs, Marshal.SizeOf<Input>());

    public static void Press(ushort vk) => Send(Key(vk, 0, 0), Key(vk, 0, KEYEVENTF_KEYUP));

    public static void Chord(ushort modifier, ushort vk) =>
        Send(Key(modifier, 0, 0), Key(vk, 0, 0), Key(vk, 0, KEYEVENTF_KEYUP), Key(modifier, 0, KEYEVENTF_KEYUP));

    /// <summary>Escribe texto tal cual (con tildes y eñes), sin depender de la distribución del teclado.</summary>
    public static void Type(string text)
    {
        var inputs = new List<Input>();
        foreach (var ch in text)
        {
            inputs.Add(Key(0, ch, KEYEVENTF_UNICODE));
            inputs.Add(Key(0, ch, KEYEVENTF_UNICODE | KEYEVENTF_KEYUP));
        }
        if (inputs.Count > 0) Send(inputs.ToArray());
    }
}
