using System.Text;
using LucyRemota.Core.Security;
using LucyRemota.Core.Commands;
using System.Text.Json.Nodes;
using Xunit;

namespace LucyRemota.Tests;

public class CryptoTests
{
    [Fact]
    public void Firma_y_claves_de_sesion_coinciden_en_los_dos_lados()
    {
        using var a = IdentityKey.Create();
        var data = Encoding.UTF8.GetBytes("hola");
        Assert.True(IdentityKey.Verify(a.PublicSpki, data, a.Sign(data)));
        Assert.False(IdentityKey.Verify(a.PublicSpki, Encoding.UTF8.GetBytes("chau"), a.Sign(data)));

        using var e1 = new EphemeralKey();
        using var e2 = new EphemeralKey();
        Assert.Equal(e1.Agree(e2.PublicSpki), e2.Agree(e1.PublicSpki));

        var t = new byte[32];
        var (k1, k2) = Transcript.SessionKeys(e1.Agree(e2.PublicSpki), t);
        using var pc = new SessionCipher(k1, k2);
        using var dev = new SessionCipher(k2, k1);
        var sealed1 = pc.Seal(Encoding.UTF8.GetBytes("orden 1"));
        Assert.Equal("orden 1", Encoding.UTF8.GetString(dev.Open(sealed1)));
        // Repetir el mismo mensaje (ataque de repetición) no descifra.
        Assert.Throws<LucyRemota.Core.Protocol.ProtocolException>(() => dev.Open(sealed1));
    }

    [Fact]
    public void Codigos_manuales_legibles()
    {
        var code = PairingCodes.NewManualCode();
        Assert.Equal(8, code.Length);
        Assert.DoesNotContain('0', code);
        Assert.Equal(PairingCodes.ManualToken(code), PairingCodes.ManualToken(PairingCodes.Pretty(code).ToLowerInvariant()));
    }

    [Fact]
    public void Validador_estricto()
    {
        var spec = new IntentSpec("OPEN_APPLICATION", "apps", "Abre una app.", Risk.Change,
            new Dictionary<string, ParamSpec> { ["name"] = new(ParamType.String, Required: true, MaxLength: 80) });
        Assert.NotNull(Validator.Validate(spec, new JsonObject { ["name"] = "Chrome" }).ok);
        Assert.NotNull(Validator.Validate(spec, new JsonObject { ["name"] = new string('a', 81) }).error);
        Assert.NotNull(Validator.Validate(spec, new JsonObject { ["name"] = "a\nb" }).error);
        Assert.NotNull(Validator.Validate(spec, new JsonObject()).error);
        Assert.NotNull(Validator.Validate(spec, new JsonObject { ["name"] = 5 }).error);
    }
}
