using LucyRemota.Core.Link;
using LucyRemota.Core.Security;
using QRCoder;

namespace LucyRemota.UI;

/// <summary>Vincular un teléfono: el QR para escanear con Lucy Remota en el teléfono, o el código para escribir.</summary>
sealed class PairDialog : LucyForm
{
    public PairDialog(PairingWindow window, IPcStore store)
    {
        Text = "Vincular teléfono";
        FormBorderStyle = FormBorderStyle.FixedDialog;
        MaximizeBox = MinimizeBox = false;
        StartPosition = FormStartPosition.CenterParent;
        ClientSize = new Size(400, 590);
        AutoScroll = true;

        var layout = new FlowLayoutPanel { Dock = DockStyle.Fill, FlowDirection = FlowDirection.TopDown, WrapContents = false, Padding = new Padding(24, 18, 24, 12), BackColor = Color.Transparent };
        var intro = Theme.Label("En el teléfono abrí Lucy Remota, tocá «Escanear QR» y apuntá a este código.", 10.5f);
        intro.MaximumSize = new Size(350, 0);
        layout.Controls.Add(intro);

        using var gen = new QRCodeGenerator();
        var data = gen.CreateQrCode(window.QrPayload(store.PcId, store.PcName, store.Key.PublicSpki), QRCodeGenerator.ECCLevel.M);
        var png = new PngByteQRCode(data).GetGraphic(8, [0x0E, 0x0A, 0x1E], [0xFF, 0xFF, 0xFF], true);
        var card = new RoundPanel { Size = new Size(320, 320), Margin = new Padding(16, 12, 0, 12), Padding = new Padding(10) };
        card.Controls.Add(new PictureBox { Image = Image.FromStream(new MemoryStream(png)), SizeMode = PictureBoxSizeMode.Zoom, Dock = DockStyle.Fill, BackColor = Color.White });
        layout.Controls.Add(card);
        layout.Controls.Add(Theme.Label("¿Sin cámara? Escribí este código:", 10f, Theme.Muted));
        layout.Controls.Add(new Label { Text = PairingCodes.Pretty(window.ManualCode), AutoSize = true, Font = Theme.Big, ForeColor = Theme.Accent, BackColor = Color.Transparent });
        var countdown = Theme.Label("", 9.5f, Theme.Muted);
        countdown.MaximumSize = new Size(350, 0);
        layout.Controls.Add(countdown);
        var cancel = new PillButton("Cancelar") { Margin = new Padding(0, 10, 0, 0) };
        cancel.Size = cancel.GetPreferredSize(Size.Empty);
        cancel.Click += (_, _) => Close();
        layout.Controls.Add(cancel);
        Controls.Add(layout);

        var timer = new System.Windows.Forms.Timer { Interval = 1000 };
        void Tick()
        {
            var left = window.ExpiresAt - DateTimeOffset.UtcNow;
            if (left <= TimeSpan.Zero || !window.Open(DateTimeOffset.UtcNow)) { timer.Stop(); Close(); return; }
            countdown.Text = $"Vence en {(int)left.TotalMinutes}:{left.Seconds:00}. El teléfono y la PC tienen que estar en la misma red.";
        }
        timer.Tick += (_, _) => Tick();
        timer.Start();
        Tick();
        FormClosed += (_, _) => timer.Dispose();
    }
}
