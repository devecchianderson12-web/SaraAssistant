using System.Drawing.Drawing2D;

namespace LucyRemota.UI;

/// <summary>
/// La esfera de Lucy: partículas que forman una esfera iluminada que gira, de un solo color que va
/// recorriendo el espectro. Late cuando llega una orden. Se detiene cuando la ventana no se ve.
/// </summary>
sealed class SphereView : Control
{
    readonly System.Windows.Forms.Timer timer = new() { Interval = 33 };
    readonly DateTime start = DateTime.UtcNow;
    double pulse;

    /// <summary>0,25 = esperando; 0,7 = conectada.</summary>
    public float Energy { get; set; } = 0.3f;

    public SphereView()
    {
        DoubleBuffered = true;
        SetStyle(ControlStyles.AllPaintingInWmPaint | ControlStyles.OptimizedDoubleBuffer | ControlStyles.UserPaint | ControlStyles.SupportsTransparentBackColor, true);
        BackColor = Color.Transparent;
        timer.Tick += (_, _) => { if (Visible && FindForm() is { Visible: true, WindowState: not FormWindowState.Minimized }) Invalidate(); };
        timer.Start();
    }

    /// <summary>Un latido (llegó una orden).</summary>
    public void Pulse() => pulse = 1.0;

    static Color Hue(double t, double alpha)
    {
        var h = (t * 36) % 360 / 60.0;
        var x = 1 - Math.Abs(h % 2 - 1);
        (double r, double g, double b) = (int)h switch
        {
            0 => (1.0, x, 0.0), 1 => (x, 1.0, 0.0), 2 => (0.0, 1.0, x), 3 => (0.0, x, 1.0), 4 => (x, 0.0, 1.0), _ => (1.0, 0.0, x),
        };
        // Saturación 0,85 (como la de Lucy).
        static int C(double v) => (int)((0.15 + 0.85 * v) * 255);
        return Color.FromArgb((int)(Math.Clamp(alpha, 0, 1) * 255), C(r), C(g), C(b));
    }

    protected override void OnPaintBackground(PaintEventArgs e)
    {
        // Transparente sobre el degradado del formulario.
        if (Parent is { } p)
        {
            var state = e.Graphics.Save();
            e.Graphics.TranslateTransform(-Left, -Top);
            Theme.PaintBackground(e.Graphics, new Rectangle(0, 0, p.ClientSize.Width, p.ClientSize.Height));
            e.Graphics.Restore(state);
        }
    }

    protected override void OnPaint(PaintEventArgs e)
    {
        var g = e.Graphics;
        g.SmoothingMode = SmoothingMode.AntiAlias;
        var t = (DateTime.UtcNow - start).TotalSeconds;
        pulse *= 0.93;
        var energy = Math.Clamp(Energy + pulse * 0.6, 0, 1);
        var cx = Width / 2.0;
        var cy = Height / 2.0;
        var r = Math.Min(Width, Height) * 0.36 * (1 + pulse * 0.12 + 0.012 * Math.Sin(t * 2));
        if (r <= 4) return;

        // Halo.
        var glow = (float)(r * 1.45);
        using (var path = new GraphicsPath())
        {
            path.AddEllipse((float)(cx - glow), (float)(cy - glow), glow * 2, glow * 2);
            using var brush = new PathGradientBrush(path) { CenterColor = Hue(t, (40 + 120 * pulse + 50 * energy) / 255), SurroundColors = [Color.FromArgb(0, 0, 0, 0)] };
            g.FillPath(brush, path);
        }

        const int rows = 34;
        var cell = 2 * r / rows;
        var lightAngle = t * (0.5 + 0.8 * energy);
        double lx = Math.Cos(lightAngle) * 0.65, ly = -0.45, lz = Math.Sin(lightAngle) * 0.35 + 0.65;
        var ll = Math.Sqrt(lx * lx + ly * ly + lz * lz);
        var spin = t * (0.35 + 0.9 * pulse);
        double cs = Math.Cos(spin), sn = Math.Sin(spin);
        using var brushDot = new SolidBrush(Color.White);
        for (var row = 0; row < rows; row++)
        {
            var y = (row + 0.5) / rows * 2 - 1;
            for (var col = 0; col < rows; col++)
            {
                var x = (col + 0.5) / rows * 2 - 1;
                var d2 = x * x + y * y;
                if (d2 > 1) continue;
                var z = Math.Sqrt(1 - d2);
                var light = Math.Max(0, (x * lx + y * ly + z * lz) / ll) * 0.75 + 0.18;
                var tx = x * cs + z * sn;
                var tz = -x * sn + z * cs;
                var bands = 0.5 + 0.5 * Math.Sin(tx * 7 + y * 3 + t * 0.8) * Math.Cos(y * 6 - tz * 4);
                var wave = pulse * (0.5 + 0.5 * Math.Sin(Math.Sqrt(d2) * 14 - t * 9));
                var value = (light * (0.6 + 0.4 * bands) + wave * 0.55) * (0.45 + 0.55 * energy);
                if (value < 0.1) continue;
                // Como el arte ASCII de Lucy: más «tinta» donde hay más luz.
                var size = (float)(cell * Math.Clamp(0.18 + value * 0.75, 0.2, 0.95));
                brushDot.Color = Hue(t, (70 + 185 * Math.Clamp(value, 0, 1)) / 255);
                g.FillEllipse(brushDot, (float)(cx + x * r - size / 2), (float)(cy + y * r - size / 2), size, size);
            }
        }
    }

    protected override void Dispose(bool disposing)
    {
        if (disposing) timer.Dispose();
        base.Dispose(disposing);
    }
}
