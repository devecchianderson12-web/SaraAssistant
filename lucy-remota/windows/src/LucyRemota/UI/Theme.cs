using System.Drawing.Drawing2D;
using System.Runtime.InteropServices;

namespace LucyRemota.UI;

/// <summary>Estética de Lucy: fondo violeta muy oscuro en degradado, esfera, botones redondeados.</summary>
static class Theme
{
    public static readonly Color BackTop = Color.FromArgb(14, 10, 30);
    public static readonly Color BackBottom = Color.FromArgb(4, 4, 10);
    public static readonly Color Surface = Color.FromArgb(220, 28, 22, 52);
    public static readonly Color SurfaceSolid = Color.FromArgb(24, 19, 44);
    public static readonly Color Border = Color.FromArgb(120, 110, 90, 200);
    public static readonly Color Button = Color.FromArgb(46, 34, 88);
    public static readonly Color ButtonHover = Color.FromArgb(62, 46, 118);
    public static readonly Color Text = Color.FromArgb(236, 232, 255);
    public static readonly Color Muted = Color.FromArgb(160, 150, 200);
    public static readonly Color Accent = Color.FromArgb(150, 95, 255);
    public static readonly Color Sky = Color.FromArgb(14, 165, 233);
    public static readonly Color Ok = Color.FromArgb(120, 230, 170);
    public static readonly Color Warn = Color.FromArgb(255, 210, 120);
    public static readonly Color Bad = Color.FromArgb(255, 150, 150);

    public static Font Ui(float size, FontStyle style = FontStyle.Regular) => new("Segoe UI", size, style);
    public static readonly Font Base = Ui(10f);
    public static readonly Font Big = new("Consolas", 26f, FontStyle.Bold);

    public static GraphicsPath Round(RectangleF r, float radius)
    {
        var d = Math.Min(radius * 2, Math.Min(r.Width, r.Height));
        var p = new GraphicsPath();
        p.AddArc(r.X, r.Y, d, d, 180, 90);
        p.AddArc(r.Right - d, r.Y, d, d, 270, 90);
        p.AddArc(r.Right - d, r.Bottom - d, d, d, 0, 90);
        p.AddArc(r.X, r.Bottom - d, d, d, 90, 90);
        p.CloseFigure();
        return p;
    }

    /// <summary>Fondo en degradado vertical (como la Lucy anterior).</summary>
    public static void PaintBackground(Graphics g, Rectangle r)
    {
        if (r.Width <= 0 || r.Height <= 0) return;
        using var b = new LinearGradientBrush(r, BackTop, BackBottom, LinearGradientMode.Vertical);
        g.FillRectangle(b, r);
    }

    [DllImport("dwmapi.dll")]
    static extern int DwmSetWindowAttribute(nint hwnd, int attr, ref int value, int size);

    /// <summary>Barra de título oscura (Windows 10 20H1+ y 11) y esquinas redondeadas (Windows 11).</summary>
    public static void DarkTitleBar(Form f)
    {
        try
        {
            var on = 1;
            if (DwmSetWindowAttribute(f.Handle, 20, ref on, 4) != 0) DwmSetWindowAttribute(f.Handle, 19, ref on, 4);
            var round = 2;
            DwmSetWindowAttribute(f.Handle, 33, ref round, 4);
        }
        catch (Exception) { }
    }

    public static Label Label(string text, float size = 10f, Color? color = null, FontStyle style = FontStyle.Regular) =>
        new() { Text = text, AutoSize = true, Font = Ui(size, style), ForeColor = color ?? Text, BackColor = Color.Transparent };
}

/// <summary>Formulario con el fondo de Lucy (degradado) y barra de título oscura.</summary>
class LucyForm : Form
{
    public LucyForm()
    {
        DoubleBuffered = true;
        BackColor = Theme.BackBottom;
        ForeColor = Theme.Text;
        Font = Theme.Base;
        SetStyle(ControlStyles.ResizeRedraw, true);
    }

    protected override void OnHandleCreated(EventArgs e)
    {
        base.OnHandleCreated(e);
        Theme.DarkTitleBar(this);
    }

    protected override void OnPaintBackground(PaintEventArgs e) => Theme.PaintBackground(e.Graphics, ClientRectangle);
}

/// <summary>Botón píldora (redondeado) como los de Lucy; [Primary] = violeta → celeste.</summary>
sealed class PillButton : Control
{
    bool hover;
    bool down;
    public bool Primary { get; init; }

    public PillButton(string text)
    {
        Text = text;
        Font = Theme.Ui(10f);
        ForeColor = Theme.Text;
        Cursor = Cursors.Hand;
        DoubleBuffered = true;
        SetStyle(ControlStyles.SupportsTransparentBackColor | ControlStyles.AllPaintingInWmPaint | ControlStyles.OptimizedDoubleBuffer | ControlStyles.UserPaint, true);
        BackColor = Color.Transparent;
        Height = 38;
        Margin = new Padding(5);
        Padding = new Padding(16, 0, 16, 0);
    }

    public override Size GetPreferredSize(Size proposedSize)
    {
        var t = TextRenderer.MeasureText(Text, Font);
        return new Size(t.Width + Padding.Horizontal + 8, 38);
    }

    protected override void OnMouseEnter(EventArgs e) { hover = true; Invalidate(); base.OnMouseEnter(e); }
    protected override void OnMouseLeave(EventArgs e) { hover = false; down = false; Invalidate(); base.OnMouseLeave(e); }
    protected override void OnMouseDown(MouseEventArgs e) { down = true; Invalidate(); base.OnMouseDown(e); }
    protected override void OnMouseUp(MouseEventArgs e) { down = false; Invalidate(); base.OnMouseUp(e); }

    protected override void OnPaint(PaintEventArgs e)
    {
        var g = e.Graphics;
        g.SmoothingMode = SmoothingMode.AntiAlias;
        var r = new RectangleF(0.5f, 0.5f, Width - 1.5f, Height - 1.5f);
        using var path = Theme.Round(r, r.Height / 2);
        if (Primary)
        {
            using var b = new LinearGradientBrush(r, hover ? ControlPaint.Light(Theme.Accent, 0.15f) : Theme.Accent, Theme.Sky, LinearGradientMode.Horizontal);
            g.FillPath(b, path);
        }
        else
        {
            using var b = new SolidBrush(down ? Theme.Accent : hover ? Theme.ButtonHover : Theme.Button);
            g.FillPath(b, path);
            using var pen = new Pen(Theme.Border);
            g.DrawPath(pen, path);
        }
        TextRenderer.DrawText(g, Text, Font, ClientRectangle, Enabled ? ForeColor : Theme.Muted,
            TextFormatFlags.HorizontalCenter | TextFormatFlags.VerticalCenter | TextFormatFlags.SingleLine);
    }
}

/// <summary>Tarjeta redondeada semitransparente (el recuadro de Lucy).</summary>
class RoundPanel : Panel
{
    public RoundPanel()
    {
        DoubleBuffered = true;
        SetStyle(ControlStyles.SupportsTransparentBackColor | ControlStyles.ResizeRedraw, true);
        BackColor = Color.Transparent;
        Padding = new Padding(14);
    }

    protected override void OnPaint(PaintEventArgs e)
    {
        e.Graphics.SmoothingMode = SmoothingMode.AntiAlias;
        using var path = Theme.Round(new RectangleF(0.5f, 0.5f, Width - 1.5f, Height - 1.5f), 16);
        using var b = new SolidBrush(Theme.SurfaceSolid);
        e.Graphics.FillPath(b, path);
        using var pen = new Pen(Theme.Border);
        e.Graphics.DrawPath(pen, path);
        base.OnPaint(e);
    }
}

/// <summary>Lista oscura de dos renglones por ítem (título y detalle), dibujada a mano.</summary>
sealed class DarkList : ListBox
{
    public Func<object, (string title, string detail, string right, Color color)> Describe { get; set; } = o => (o.ToString() ?? "", "", "", Theme.Text);

    public DarkList()
    {
        DrawMode = DrawMode.OwnerDrawFixed;
        ItemHeight = 50;
        BorderStyle = BorderStyle.None;
        BackColor = Theme.SurfaceSolid;
        ForeColor = Theme.Text;
        IntegralHeight = false;
        Dock = DockStyle.Fill;
    }

    protected override void OnDrawItem(DrawItemEventArgs e)
    {
        if (e.Index < 0 || e.Index >= Items.Count) return;
        var g = e.Graphics;
        var selected = (e.State & DrawItemState.Selected) != 0;
        using (var b = new SolidBrush(selected ? Theme.ButtonHover : Theme.SurfaceSolid)) g.FillRectangle(b, e.Bounds);
        var (title, detail, right, color) = Describe(Items[e.Index]);
        var r = e.Bounds;
        var rightWidth = string.IsNullOrEmpty(right) ? 0 : TextRenderer.MeasureText(right, Font).Width + 12;
        TextRenderer.DrawText(g, title, Theme.Ui(10f), new Rectangle(r.X + 10, r.Y + 5, r.Width - 20 - rightWidth, 22), Theme.Text, TextFormatFlags.EndEllipsis);
        TextRenderer.DrawText(g, detail, Theme.Ui(9f), new Rectangle(r.X + 10, r.Y + 26, r.Width - 20 - rightWidth, 20), Theme.Muted, TextFormatFlags.EndEllipsis);
        if (rightWidth > 0) TextRenderer.DrawText(g, right, Theme.Ui(9.5f, FontStyle.Bold), new Rectangle(r.Right - rightWidth - 6, r.Y + 5, rightWidth, 22), color, TextFormatFlags.Right);
        using var line = new Pen(Color.FromArgb(40, 255, 255, 255));
        g.DrawLine(line, r.X + 10, r.Bottom - 1, r.Right - 10, r.Bottom - 1);
    }
}

/// <summary>Menú oscuro.</summary>
sealed class DarkMenuColors : ProfessionalColorTable
{
    public override Color ToolStripDropDownBackground => Theme.SurfaceSolid;
    public override Color MenuItemSelected => Theme.ButtonHover;
    public override Color MenuItemBorder => Theme.Accent;
    public override Color MenuBorder => Theme.Border;
    public override Color ImageMarginGradientBegin => Theme.SurfaceSolid;
    public override Color ImageMarginGradientMiddle => Theme.SurfaceSolid;
    public override Color ImageMarginGradientEnd => Theme.SurfaceSolid;
    public override Color SeparatorDark => Theme.Border;
    public override Color CheckBackground => Theme.Button;
    public override Color CheckSelectedBackground => Theme.ButtonHover;
}

static class Menus
{
    public static ContextMenuStrip Dark()
    {
        var m = new ContextMenuStrip { Renderer = new ToolStripProfessionalRenderer(new DarkMenuColors()), ForeColor = Theme.Text, Font = Theme.Ui(10f), ShowImageMargin = false };
        m.Renderer.RenderItemText += (_, e) => e.TextColor = Theme.Text;
        return m;
    }
}
