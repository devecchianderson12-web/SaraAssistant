namespace LucyRemota.Core.Link;

/// <summary>Un teléfono vinculado: su clave pública queda fija (nadie más puede hacerse pasar por él).</summary>
public sealed record PairedDevice(string Id, string Name, byte[] PublicKey, DateTimeOffset PairedAt, DateTimeOffset? LastSeen = null, string? LastAddress = null);

/// <summary>Dónde guarda la PC su identidad y los teléfonos vinculados.</summary>
public interface IPcStore
{
    string PcId { get; }
    string PcName { get; }
    Security.IdentityKey Key { get; }
    IReadOnlyList<PairedDevice> Devices { get; }
    void Upsert(PairedDevice device);
    bool Remove(string deviceId);
}

/// <summary>Para pruebas y para la simulación: todo en memoria.</summary>
public sealed class MemoryPcStore(string pcId, string pcName) : IPcStore
{
    readonly List<PairedDevice> devices = new();
    public string PcId { get; } = pcId;
    public string PcName { get; } = pcName;
    public Security.IdentityKey Key { get; } = Security.IdentityKey.Create();
    public IReadOnlyList<PairedDevice> Devices { get { lock (devices) return devices.ToList(); } }
    public void Upsert(PairedDevice d) { lock (devices) { devices.RemoveAll(x => x.Id == d.Id); devices.Add(d); } }
    public bool Remove(string id) { lock (devices) return devices.RemoveAll(x => x.Id == id) > 0; }
}

/// <summary>
/// Ventana de vinculación abierta (5 minutos): el código del QR y el manual. Se cierra al vincular
/// un teléfono o después de 5 pruebas fallidas.
/// </summary>
public sealed class PairingWindow
{
    public byte[] QrToken { get; } = Security.PairingCodes.NewQrToken();
    public string ManualCode { get; } = Security.PairingCodes.NewManualCode();
    public DateTimeOffset ExpiresAt { get; }
    public int Failures { get; set; }

    public PairingWindow(DateTimeOffset now, TimeSpan? duration = null) => ExpiresAt = now + (duration ?? TimeSpan.FromMinutes(5));

    public bool Open(DateTimeOffset now) => now < ExpiresAt && Failures < 5;

    /// <summary>Lo que lleva el QR: identidad y huella de la PC + el código de un solo uso.</summary>
    public string QrPayload(string pcId, string pcName, byte[] pcKey) =>
        $"lucyremota://pair?v=1&pc={Uri.EscapeDataString(pcId)}&n={Uri.EscapeDataString(pcName)}" +
        $"&f={Protocol.J.B64Url(Security.IdentityKey.Fingerprint(pcKey))}&t={Protocol.J.B64Url(QrToken)}";

    public byte[] ManualToken => Security.PairingCodes.ManualToken(ManualCode);
}
