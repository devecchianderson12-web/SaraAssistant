namespace LucyRemota.UI;

/// <summary>Colores de Lucy Remota (oscuro, con el violeta/celeste del ícono).</summary>
static class Theme
{
    public static readonly Color Back = Color.FromArgb(0x12, 0x11, 0x1A);
    public static readonly Color Panel = Color.FromArgb(0x1C, 0x1A, 0x28);
    public static readonly Color Text = Color.FromArgb(0xEE, 0xEC, 0xF8);
    public static readonly Color Muted = Color.FromArgb(0x9A, 0x96, 0xB8);
    public static readonly Color Accent = Color.FromArgb(0x8B, 0x5C, 0xF6);
    public static readonly Color Ok = Color.FromArgb(0x34, 0xD3, 0x99);
    public static readonly Color Warn = Color.FromArgb(0xFB, 0xBF, 0x24);
    public static readonly Color Bad = Color.FromArgb(0xF8, 0x71, 0x71);
    public static readonly Font Base = new("Segoe UI", 10f);
    public static readonly Font Title = new("Segoe UI Semibold", 16f);
    public static readonly Font Big = new("Consolas", 26f, FontStyle.Bold);

    public static Button Button(string text, bool primary = false)
    {
        var b = new Button
        {
            Text = text, AutoSize = true, FlatStyle = FlatStyle.Flat, ForeColor = Text, Font = Base,
            BackColor = primary ? Accent : Panel, Padding = new Padding(10, 4, 10, 4), Cursor = Cursors.Hand,
        };
        b.FlatAppearance.BorderColor = primary ? Accent : Color.FromArgb(0x3A, 0x36, 0x52);
        return b;
    }

    public static ListView List(params (string title, int width)[] columns)
    {
        var l = new ListView
        {
            View = View.Details, FullRowSelect = true, HeaderStyle = ColumnHeaderStyle.Nonclickable, BackColor = Panel, ForeColor = Text,
            BorderStyle = BorderStyle.None, Font = Base, Dock = DockStyle.Fill, MultiSelect = false,
        };
        foreach (var (t, w) in columns) l.Columns.Add(t, w);
        return l;
    }

    public static Label Label(string text, Font? font = null, Color? color = null) =>
        new() { Text = text, AutoSize = true, Font = font ?? Base, ForeColor = color ?? Text, BackColor = Color.Transparent };
}
