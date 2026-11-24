using System.Drawing.Drawing2D;

namespace LucyRemota.UI;

/// <summary>
/// La burbuja de Lucy (la misma del teléfono): un ANILLO en arte ASCII que gira en 3D, en tonos
/// fucsia → dorado, con «L U C Y» debajo. Late cuando llega una orden. Se detiene cuando la ventana
/// no se ve.
/// </summary>
sealed class SphereView : Control
{
    readonly System.Windows.Forms.Timer timer = new() { Interval = 33 };
    readonly DateTime start = DateTime.UtcNow;
    double pulse;
    const int Rows = 40;
    readonly double[] depth = new double[Rows * Rows];
    readonly double[] light = new double[Rows * Rows];
    readonly double[] tone = new double[Rows * Rows];
    const string Ramp = " .,-~:;=!*#$@";

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

    /// <summary>Va y viene entre fucsia y dorado (nunca el arcoíris de Sara).</summary>
    static Color Tone(double t, double alpha, double shift = 0)
    {
        var k = Math.Clamp(0.5 + 0.5 * Math.Sin(t * 0.9 + shift * 3), 0, 1);
        static int Mix(int a, int b, double k) => (int)(a + (b - a) * k);
        return Color.FromArgb((int)(Math.Clamp(alpha, 0, 1) * 255), 0xFF, Mix(0x2E, 0xB4, k), Mix(0xA6, 0x2E, k));
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
        g.TextRenderingHint = System.Drawing.Text.TextRenderingHint.AntiAliasGridFit;
        var t = (DateTime.UtcNow - start).TotalSeconds;
        pulse *= 0.93;
        var energy = Math.Clamp(Energy + pulse * 0.6, 0, 1);
        var cx = Width / 2.0;
        var r = Math.Min(Width, Height) * 0.34 * (1 + pulse * 0.14 + 0.015 * Math.Sin(t * 2.2));
        if (r <= 4) return;
        var cy = Height / 2.0 - r * 0.14;

        var glow = (float)(r * 1.5);
        using (var path = new GraphicsPath())
        {
            path.AddEllipse((float)(cx - glow), (float)(cy - glow), glow * 2, glow * 2);
            using var brush = new PathGradientBrush(path) { CenterColor = Tone(t, (50 + 120 * pulse + 40 * energy) / 255), SurroundColors = [Color.FromArgb(0, 0, 0, 0)] };
            g.FillPath(brush, path);
        }

        // Anillo 3D (como el clásico «donut» ASCII) proyectado a una grilla con profundidad.
        Array.Clear(depth);
        var a = t * (0.55 + 1.1 * energy + 1.4 * pulse);
        var b = t * (0.3 + 0.6 * energy);
        double cosA = Math.Cos(a), sinA = Math.Sin(a), cosB = Math.Cos(b), sinB = Math.Sin(b);
        const double k2 = 5, tube = 1, ring = 2;
        var k1 = Rows * k2 * 3 / (8 * (tube + ring));
        for (var theta = 0.0; theta < Math.Tau; theta += 0.11)
        {
            double ct = Math.Cos(theta), st = Math.Sin(theta);
            for (var phi = 0.0; phi < Math.Tau; phi += 0.045)
            {
                double cp = Math.Cos(phi), sp = Math.Sin(phi);
                var circleX = ring + tube * ct;
                var circleY = tube * st;
                var x = circleX * (cosB * cp + sinA * sinB * sp) - circleY * cosA * sinB;
                var y = circleX * (sinB * cp - sinA * cosB * sp) + circleY * cosA * cosB;
                var z = k2 + cosA * circleX * sp + circleY * sinA;
                var ooz = 1 / z;
                var col = (int)(Rows / 2.0 + k1 * ooz * x);
                var row = (int)(Rows / 2.0 - k1 * ooz * y);
                if (col < 0 || col >= Rows || row < 0 || row >= Rows) continue;
                var i = row * Rows + col;
                if (ooz <= depth[i]) continue;
                depth[i] = ooz;
                light[i] = cp * ct * sinB - cosA * ct * sp - sinA * st + cosB * (cosA * st - ct * sinA * sp);
                tone[i] = phi / Math.Tau;
            }
        }

        var cell = 2 * r / Rows;
        using var font = new Font(FontFamily.GenericMonospace, (float)Math.Max(4, cell * 1.15), FontStyle.Bold, GraphicsUnit.Pixel);
        using var brushText = new SolidBrush(Color.White);
        using var fmt = new StringFormat { Alignment = StringAlignment.Center, LineAlignment = StringAlignment.Center };
        var dim = 0.5 + 0.5 * energy;
        for (var row = 0; row < Rows; row++)
        {
            for (var col = 0; col < Rows; col++)
            {
                var i = row * Rows + col;
                if (depth[i] == 0) continue;
                var wave = pulse * (0.5 + 0.5 * Math.Sin(tone[i] * 18 - t * 10));
                var value = Math.Clamp(Math.Max(0, light[i]) / 1.42 * 0.8 + 0.2 + wave * 0.45, 0, 1) * dim;
                var index = Math.Clamp((int)(value * (Ramp.Length - 1)), 1, Ramp.Length - 1);
                brushText.Color = Tone(t, (90 + 165 * value) / 255, tone[i]);
                var x = cx + ((col + 0.5) / Rows * 2 - 1) * r;
                var y = cy + ((row + 0.5) / Rows * 2 - 1) * r;
                g.DrawString(Ramp[index].ToString(), font, brushText, (float)x, (float)y, fmt);
            }
        }
        using var nameFont = new Font(FontFamily.GenericMonospace, (float)Math.Max(6, r * 0.26), FontStyle.Bold, GraphicsUnit.Pixel);
        brushText.Color = Tone(t + 1.5, (170 + 85 * energy) / 255);
        g.DrawString("L U C Y", nameFont, brushText, (float)cx, (float)(cy + r * 1.15), fmt);
    }

    protected override void Dispose(bool disposing)
    {
        if (disposing) timer.Dispose();
        base.Dispose(disposing);
    }
}
