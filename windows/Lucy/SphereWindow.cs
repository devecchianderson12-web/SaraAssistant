using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Linq;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Animation;

namespace Lucy;

/// <summary>
/// La esfera de Lucy (la misma de Sara): cáscara de partículas conectadas que gira en 3D, cintas
/// de luz adentro, colores que recorren el espectro y que late con la voz. Ventana sin bordes,
/// transparente, siempre arriba; por defecto en la esquina superior derecha y se puede arrastrar.
/// </summary>
public sealed class SphereWindow : Window
{
    private readonly SphereVisual sphere = new();
    private readonly Settings settings;
    private readonly TextBlock caption = new()
    {
        Foreground = Brushes.White, FontSize = 12, TextAlignment = TextAlignment.Center, TextWrapping = TextWrapping.Wrap,
        Margin = new Thickness(6, 0, 6, 4), VerticalAlignment = VerticalAlignment.Bottom, Opacity = 0.85,
    };
    private bool shown;

    public SphereWindow(Settings settings)
    {
        this.settings = settings;
        WindowStyle = WindowStyle.None;
        AllowsTransparency = true;
        Background = Brushes.Transparent;
        Topmost = true;
        ShowInTaskbar = false;
        ShowActivated = false;
        ResizeMode = ResizeMode.NoResize;
        Width = settings.SphereSize;
        Height = settings.SphereSize + 34;
        var grid = new Grid();
        grid.RowDefinitions.Add(new RowDefinition { Height = new GridLength(settings.SphereSize) });
        grid.RowDefinitions.Add(new RowDefinition { Height = new GridLength(34) });
        grid.Children.Add(sphere);
        Grid.SetRow(caption, 1);
        grid.Children.Add(caption);
        Content = grid;
        Opacity = 0;
        PlaceInitially();

        // Arrastrar para moverla; doble clic la vuelve a la esquina.
        MouseLeftButtonDown += (_, e) =>
        {
            if (e.ClickCount == 2) { ResetPosition(); return; }
            try { DragMove(); } catch { }
            settings.SphereLeft = Left;
            settings.SphereTop = Top;
            settings.Save();
        };
        ToolTip = "Lucy · arrastrala para moverla (doble clic: vuelve a la esquina)";
    }

    private void PlaceInitially()
    {
        var area = SystemParameters.WorkArea;
        if (settings.SphereLeft is double l && settings.SphereTop is double t &&
            l > area.Left - Width / 2 && l < area.Right - Width / 2 && t > area.Top - 10 && t < area.Bottom - Height / 2)
        {
            Left = l;
            Top = t;
        }
        else
        {
            Left = area.Right - Width - 16;
            Top = area.Top + 16;
        }
    }

    public void ResetPosition()
    {
        settings.SphereLeft = null;
        settings.SphereTop = null;
        settings.Save();
        PlaceInitially();
    }

    public void SetLevel(float level) => sphere.Level = level;

    public void SetState(VoiceState state, string text = "")
    {
        sphere.Energy = state switch
        {
            VoiceState.Listening => 1f,
            VoiceState.Speaking => 0.85f,
            VoiceState.Thinking => 0.7f,
            _ => 0.35f,
        };
        sphere.Speaking = state == VoiceState.Speaking;
        caption.Text = text;
        if (state == VoiceState.Idle && !settings.SphereAlwaysVisible) Disappear();
        else Appear();
    }

    /// <summary>Entrada ultra rápida: aparece y crece en ~120 ms.</summary>
    public void Appear()
    {
        if (!IsVisible) Show();
        if (shown) { BeginAnimation(OpacityProperty, new DoubleAnimation(1, TimeSpan.FromMilliseconds(80))); return; }
        shown = true;
        var scale = new ScaleTransform(0.6, 0.6, settings.SphereSize / 2, settings.SphereSize / 2);
        sphere.RenderTransform = scale;
        var grow = new DoubleAnimation(0.6, 1, TimeSpan.FromMilliseconds(130)) { EasingFunction = new QuadraticEase { EasingMode = EasingMode.EaseOut } };
        scale.BeginAnimation(ScaleTransform.ScaleXProperty, grow);
        scale.BeginAnimation(ScaleTransform.ScaleYProperty, grow);
        BeginAnimation(OpacityProperty, new DoubleAnimation(1, TimeSpan.FromMilliseconds(90)));
    }

    public void Disappear()
    {
        if (!shown) return;
        shown = false;
        var fade = new DoubleAnimation(0, TimeSpan.FromMilliseconds(110));
        fade.Completed += (_, _) => { if (!shown) Hide(); };
        BeginAnimation(OpacityProperty, fade);
    }

    /// <summary>Mostrarla un rato aunque no esté escuchando (para moverla).</summary>
    public void ShowForMoving()
    {
        Appear();
        caption.Text = "Arrastrame";
    }
}

/// <summary>Dibujo de la esfera (WPF, sin archivos externos).</summary>
public sealed class SphereVisual : FrameworkElement
{
    public float Level { get; set; }
    public float Energy { get; set; } = 0.35f;
    public bool Speaking { get; set; }

    private const int Count = 260;
    private readonly double[] px = new double[Count], py = new double[Count], pz = new double[Count];
    private readonly List<(int, int)> links = new();
    private readonly Stopwatch clock = Stopwatch.StartNew();
    private double smooth;

    public SphereVisual()
    {
        var golden = Math.PI * (3 - Math.Sqrt(5));
        for (var i = 0; i < Count; i++)
        {
            var y = 1 - i / (Count - 1.0) * 2;
            var r = Math.Sqrt(1 - y * y);
            px[i] = Math.Cos(golden * i) * r;
            py[i] = y;
            pz[i] = Math.Sin(golden * i) * r;
        }
        var pairs = new HashSet<(int, int)>();
        for (var i = 0; i < Count; i++)
        {
            foreach (var j in Enumerable.Range(0, Count).Where(j => j != i)
                         .OrderBy(j => Sq(px[i] - px[j]) + Sq(py[i] - py[j]) + Sq(pz[i] - pz[j])).Take(2))
                pairs.Add(i < j ? (i, j) : (j, i));
        }
        links.AddRange(pairs);
        CompositionTarget.Rendering += (_, _) => { if (IsVisible) InvalidateVisual(); };
        IsHitTestVisible = false;
    }

    private static double Sq(double v) => v * v;

    private static Color Hsv(double h, double s, double v, double a)
    {
        h = ((h % 360) + 360) % 360;
        var c = v * s;
        var x = c * (1 - Math.Abs(h / 60 % 2 - 1));
        var m = v - c;
        var (r, g, b) = h switch
        {
            < 60 => (c, x, 0.0), < 120 => (x, c, 0.0), < 180 => (0.0, c, x),
            < 240 => (0.0, x, c), < 300 => (x, 0.0, c), _ => (c, 0.0, x),
        };
        return Color.FromArgb((byte)Math.Clamp(a * 255, 0, 255), (byte)((r + m) * 255), (byte)((g + m) * 255), (byte)((b + m) * 255));
    }

    protected override void OnRender(DrawingContext dc)
    {
        var w = ActualWidth;
        var h = ActualHeight;
        if (w <= 0 || h <= 0) return;
        var t = clock.Elapsed.TotalSeconds;
        var target = Speaking ? Math.Max(Level, 0.35 + 0.25 * Math.Sin(t * 9) * Math.Sin(t * 3.3)) : Level;
        smooth += (Math.Clamp(target, 0, 1) - smooth) * 0.25;
        var pulse = Math.Clamp(smooth * Energy, 0, 1);
        var cx = w / 2;
        var cy = h / 2;
        var radius = Math.Min(w, h) * 0.30 * (1 + pulse * 0.18 + 0.015 * Math.Sin(t * 2));
        var hue = t * 45;

        // Resplandor.
        var glow = new RadialGradientBrush(Hsv(hue, 0.9, 1, 0.25 + 0.4 * pulse), Colors.Transparent);
        dc.DrawEllipse(glow, null, new Point(cx, cy), radius * 1.6, radius * 1.6);

        // Cáscara de partículas que gira.
        var ay = t * (0.35 + 0.6 * pulse);
        var ax = 0.35 * Math.Sin(t * 0.4);
        double cosY = Math.Cos(ay), sinY = Math.Sin(ay), cosX = Math.Cos(ax), sinX = Math.Sin(ax);
        var sx = new double[Count];
        var sy = new double[Count];
        var sd = new double[Count];
        for (var i = 0; i < Count; i++)
        {
            var wob = 1 + pulse * 0.10 * Math.Sin(t * 6 + i * 0.37);
            var x1 = px[i] * cosY + pz[i] * sinY;
            var z1 = -px[i] * sinY + pz[i] * cosY;
            var y2 = py[i] * cosX - z1 * sinX;
            var z2 = py[i] * sinX + z1 * cosX;
            var persp = 1 / (1.9 - z2 * 0.6);
            sx[i] = cx + x1 * radius * wob * persp * 1.3;
            sy[i] = cy + y2 * radius * wob * persp * 1.3;
            sd[i] = (z2 + 1) / 2;
        }
        foreach (var (a, b) in links)
        {
            var depth = (sd[a] + sd[b]) / 2;
            var pen = new Pen(new SolidColorBrush(Hsv(hue + 180 * depth, 0.75, 1, (0.08 + 0.35 * depth) * (0.6 + 0.4 * Energy))), 0.8);
            dc.DrawLine(pen, new Point(sx[a], sy[a]), new Point(sx[b], sy[b]));
        }
        for (var i = 0; i < Count; i++)
        {
            var depth = sd[i];
            var brush = new SolidColorBrush(Hsv(hue + 180 * depth + i * 0.3, 0.6, 1, 0.25 + 0.75 * depth));
            var size = (0.5 + 1.4 * depth) * (1 + pulse * 0.5);
            dc.DrawEllipse(brush, null, new Point(sx[i], sy[i]), size, size);
        }

        // Cintas de luz adentro.
        var inner = radius * 0.78;
        for (var r = 0; r < 3; r++)
        {
            var phase = t * (0.6 + r * 0.17) + r * 2.1;
            var tilt = r * 1.05 + t * 0.25;
            double cosT = Math.Cos(tilt), sinT = Math.Sin(tilt);
            var amp = 0.28 + 0.22 * pulse + 0.06 * Math.Sin(t + r);
            for (var strand = 0; strand < 8; strand++)
            {
                var offset = (strand - 4) * (0.025 + 0.01 * pulse);
                var geo = new StreamGeometry();
                using (var ctx = geo.Open())
                {
                    for (var k = 0; k <= 40; k++)
                    {
                        var th = k / 40.0 * 2 * Math.PI;
                        var x = Math.Cos(th);
                        var y = Math.Sin(th * 2 + phase) * amp + offset * Math.Cos(th * 3 + phase);
                        var z = Math.Sin(th);
                        var y1 = y * cosT - z * sinT;
                        var z1 = y * sinT + z * cosT;
                        var x2 = x * cosY - z1 * sinY;
                        var p = new Point(cx + x2 * inner, cy + y1 * inner);
                        if (k == 0) ctx.BeginFigure(p, false, false); else ctx.LineTo(p, true, false);
                    }
                }
                geo.Freeze();
                var edge = 1 - Math.Abs(strand - 4) / 4.0;
                dc.DrawGeometry(null, new Pen(new SolidColorBrush(Hsv(hue + 90 + r * 70 + strand * 3, 0.7, 1, (0.1 + 0.45 * edge) * (0.5 + 0.5 * Energy))), 1.1), geo);
            }
        }
    }
}
