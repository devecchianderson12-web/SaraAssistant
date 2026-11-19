using System.Buffers.Binary;
using System.Security.Cryptography;
using System.Text;
using LucyRemota.Core.Protocol;

namespace LucyRemota.Core.Security;

/// <summary>
/// Clave de identidad de largo plazo (ECDSA P-256). La de la PC se guarda cifrada con el usuario de
/// Windows; la del teléfono vive en el almacén seguro de Android y nunca sale de él.
/// </summary>
public sealed class IdentityKey : IDisposable
{
    readonly ECDsa key;

    IdentityKey(ECDsa key) => this.key = key;

    public static IdentityKey Create() => new(ECDsa.Create(ECCurve.NamedCurves.nistP256));

    public static IdentityKey FromPkcs8(byte[] pkcs8)
    {
        var k = ECDsa.Create();
        k.ImportPkcs8PrivateKey(pkcs8, out _);
        return new IdentityKey(k);
    }

    public byte[] ExportPkcs8() => key.ExportPkcs8PrivateKey();

    /// <summary>Clave pública en formato SubjectPublicKeyInfo (DER), igual que Java getEncoded().</summary>
    public byte[] PublicSpki => key.ExportSubjectPublicKeyInfo();

    /// <summary>Firma SHA256withECDSA en formato DER (el de Java).</summary>
    public byte[] Sign(byte[] data) => key.SignData(data, HashAlgorithmName.SHA256, DSASignatureFormat.Rfc3279DerSequence);

    public static bool Verify(byte[] spki, byte[] data, byte[] signature)
    {
        try
        {
            using var k = ECDsa.Create();
            k.ImportSubjectPublicKeyInfo(spki, out _);
            if (k.KeySize != 256) return false;
            return k.VerifyData(data, signature, HashAlgorithmName.SHA256, DSASignatureFormat.Rfc3279DerSequence);
        }
        catch (CryptographicException)
        {
            return false;
        }
    }

    /// <summary>Huella de una clave pública: SHA-256 del SPKI.</summary>
    public static byte[] Fingerprint(byte[] spki) => SHA256.HashData(spki);

    public void Dispose() => key.Dispose();
}

/// <summary>Clave efímera de una conexión (ECDH P-256): da secreto hacia adelante.</summary>
public sealed class EphemeralKey : IDisposable
{
    readonly ECDiffieHellman key = ECDiffieHellman.Create(ECCurve.NamedCurves.nistP256);

    public byte[] PublicSpki => key.ExportSubjectPublicKeyInfo();

    /// <summary>Secreto compartido: la coordenada X del punto (lo mismo que KeyAgreement «ECDH» de Java).</summary>
    public byte[] Agree(byte[] otherSpki)
    {
        using var other = ECDiffieHellman.Create();
        try
        {
            other.ImportSubjectPublicKeyInfo(otherSpki, out _);
        }
        catch (CryptographicException)
        {
            throw new ProtocolException("clave efímera inválida");
        }
        return key.DeriveRawSecretAgreement(other.PublicKey);
    }

    public void Dispose() => key.Dispose();
}

/// <summary>Resumen de la negociación: une a las dos partes con sus claves y números al azar.</summary>
public static class Transcript
{
    public static byte[] Hash(string pcId, byte[] pcKey, byte[] pcEph, byte[] pcNonce, string devId, byte[] devKey, byte[] devEph, byte[] devNonce)
    {
        using var ms = new MemoryStream();
        void Put(byte[] b)
        {
            Span<byte> len = stackalloc byte[4];
            BinaryPrimitives.WriteInt32BigEndian(len, b.Length);
            ms.Write(len);
            ms.Write(b);
        }
        Put(Encoding.UTF8.GetBytes(Proto.Label));
        Put(Encoding.UTF8.GetBytes(pcId));
        Put(pcKey);
        Put(pcEph);
        Put(pcNonce);
        Put(Encoding.UTF8.GetBytes(devId));
        Put(devKey);
        Put(devEph);
        Put(devNonce);
        return SHA256.HashData(ms.ToArray());
    }

    public static byte[] Role(string role, byte[] transcript) => [.. Encoding.ASCII.GetBytes(role), .. transcript];

    /// <summary>Prueba de que se conoce el código de vinculación (QR o manual).</summary>
    public static byte[] PairProof(byte[] token, string role, byte[] transcript) => HMACSHA256.HashData(token, Role(role, transcript));

    /// <summary>Claves de sesión: HKDF-SHA256(secreto, sal = resumen) → 32 bytes por sentido.</summary>
    public static (byte[] pcToDev, byte[] devToPc) SessionKeys(byte[] sharedSecret, byte[] transcript)
    {
        var okm = HKDF.DeriveKey(HashAlgorithmName.SHA256, sharedSecret, 64, transcript, Encoding.ASCII.GetBytes(Proto.Label + " keys"));
        return (okm[..32], okm[32..]);
    }
}

/// <summary>
/// Cifrado de los mensajes de una sesión: AES-256-GCM con un contador por sentido como nonce. Un
/// mensaje repetido, cambiado o fuera de orden no descifra (la conexión se corta).
/// </summary>
public sealed class SessionCipher : IDisposable
{
    readonly AesGcm send;
    readonly AesGcm receive;
    ulong sendCounter;
    ulong receiveCounter;
    readonly object sendLock = new();

    public SessionCipher(byte[] sendKey, byte[] receiveKey)
    {
        send = new AesGcm(sendKey, 16);
        receive = new AesGcm(receiveKey, 16);
    }

    static byte[] Nonce(ulong counter)
    {
        var n = new byte[12];
        BinaryPrimitives.WriteUInt64BigEndian(n.AsSpan(4), counter);
        return n;
    }

    public byte[] Seal(byte[] plain)
    {
        lock (sendLock)
        {
            var output = new byte[plain.Length + 16];
            send.Encrypt(Nonce(sendCounter++), plain, output.AsSpan(0, plain.Length), output.AsSpan(plain.Length));
            return output;
        }
    }

    public byte[] Open(byte[] sealedData)
    {
        if (sealedData.Length < 16) throw new ProtocolException("mensaje cifrado incompleto");
        var plain = new byte[sealedData.Length - 16];
        try
        {
            receive.Decrypt(Nonce(receiveCounter), sealedData.AsSpan(0, plain.Length), sealedData.AsSpan(plain.Length), plain);
        }
        catch (CryptographicException)
        {
            throw new ProtocolException("mensaje cifrado inválido (alterado o repetido)");
        }
        receiveCounter++;
        return plain;
    }

    public void Dispose()
    {
        send.Dispose();
        receive.Dispose();
    }
}

/// <summary>Códigos de vinculación: el del QR (128 bits) y el manual (8 caracteres fáciles de leer).</summary>
public static class PairingCodes
{
    // Sin 0/O, 1/I/L ni U: no se confunden al leerlos.
    const string Alphabet = "23456789ABCDEFGHJKMNPQRSTVWXYZ";

    public static byte[] NewQrToken() => RandomNumberGenerator.GetBytes(16);

    public static string NewManualCode()
    {
        var chars = new char[8];
        for (var i = 0; i < chars.Length; i++) chars[i] = Alphabet[RandomNumberGenerator.GetInt32(Alphabet.Length)];
        return new string(chars);
    }

    /// <summary>«k7m2-9xq4» → «K7M29XQ4» (lo que se escribe a mano).</summary>
    public static string NormalizeManual(string code) =>
        new(code.ToUpperInvariant().Where(char.IsLetterOrDigit).ToArray());

    public static byte[] ManualToken(string code) => Encoding.UTF8.GetBytes("manual:" + NormalizeManual(code));

    /// <summary>Así se muestra: «K7M2-9XQ4».</summary>
    public static string Pretty(string code) => code.Length == 8 ? code[..4] + "-" + code[4..] : code;
}
