using LucyRemota.Core.Link;
using LucyRemota.Core.Security;
using QRCoder;

namespace LucyRemota.UI;

/// <summary>Vincular un teléfono: el QR para escanear con Lucy Remota (Emisor) o el código para escribir.</summary>
sealed class PairDialog : Form
{
    public PairDialog(PairingWindow window, IPcStore store)
    {
        Text = "Vincular teléfono";
        BackColor = Theme.Back;
        ForeColor = Theme.Text;
        Font = Theme.Base;
        FormBorderStyle = FormBorderStyle.FixedDialog;
        MaximizeBox = MinimizeBox = false;
        StartPosition = FormStartPosition.CenterParent;
        ClientSize = new Size(420, 560);
        AutoScroll = true;

        var layout = new FlowLayoutPanel { Dock = DockStyle.Fill, FlowDirection = FlowDirection.TopDown, WrapContents = false, Padding = new Padding(22) };
        layout.Controls.Add(Theme.Label("En el teléfono abrí Lucy Remota, tocá «Escanear QR» y apuntá a este código.", color: Theme.Text).With(l => l.MaximumSize = new Size(390, 0)));

        using var gen = new QRCodeGenerator();
        var data = gen.CreateQrCode(window.QrPayload(store.PcId, store.PcName, store.Key.PublicSpki), QRCodeGenerator.ECCLevel.M);
        var png = new PngByteQRCode(data).GetGraphic(8, [0x12, 0x11, 0x1A], [0xFF, 0xFF, 0xFF], true);
        var qr = new PictureBox { Image = Image.FromStream(new MemoryStream(png)), SizeMode = PictureBoxSizeMode.Zoom, Size = new Size(300, 300), Margin = new Padding(30, 8, 8, 8) };
        layout.Controls.Add(qr);
        layout.Controls.Add(Theme.Label("¿Sin cámara? Escribí este código:", color: Theme.Muted));
        layout.Controls.Add(Theme.Label(PairingCodes.Pretty(window.ManualCode), Theme.Big, Theme.Accent));
        var countdown = Theme.Label("", color: Theme.Muted);
        layout.Controls.Add(countdown);
        var cancel = Theme.Button("Cancelar");
        cancel.Click += (_, _) => Close();
        layout.Controls.Add(cancel);
        Controls.Add(layout);

        var timer = new System.Windows.Forms.Timer { Interval = 1000 };
        void Tick()
        {
            var left = window.ExpiresAt - DateTimeOffset.UtcNow;
            if (left <= TimeSpan.Zero || !window.Open(DateTimeOffset.UtcNow)) { timer.Stop(); Close(); return; }
            countdown.Text = $"El código vence en {(int)left.TotalMinutes}:{left.Seconds:00}. El teléfono y la PC tienen que estar en la misma red.";
        }
        timer.Tick += (_, _) => Tick();
        timer.Start();
        Tick();
        FormClosed += (_, _) => timer.Dispose();
    }
}

static class ControlExtensions
{
    public static T With<T>(this T c, Action<T> a) where T : Control { a(c); return c; }
}
