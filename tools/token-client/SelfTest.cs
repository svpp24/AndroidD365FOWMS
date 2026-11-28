using System.Security.Cryptography;
using System.Text;

namespace HsMobileOps.TokenClient;

/// <summary>Offline check that this client's key and signatures are what the token service accepts.</summary>
public static class SelfTest
{
    public static bool Run()
    {
        var name = "hsmo_selftest_" + Guid.NewGuid().ToString("N");
        using var cng = CngKey.Create(CngAlgorithm.ECDsaP256, name,
            new CngKeyCreationParameters { ExportPolicy = CngExportPolicies.None });
        try
        {
            using var key = new ECDsaCng(cng);
            var publicKey = Convert.ToBase64String(key.ExportSubjectPublicKeyInfo());
            var texts = new[]
            {
                $"HSMODEV1-REGISTER\nABCDEF0123456789ABCDEF0123456789\nPC-TEST\n{publicKey}",
                $"HSMODEV1-TOKEN\nPC-TEST\nhttps://example.operations.dynamics.com\n{DateTimeOffset.UtcNow.ToUnixTimeSeconds()}\nn0nce",
            };
            foreach (var text in texts)
            {
                var signature = Convert.ToBase64String(key.SignData(Encoding.UTF8.GetBytes(text),
                    HashAlgorithmName.SHA256, DSASignatureFormat.Rfc3279DerSequence));
                // As TokenService.Protocol.VerifyDeviceSignature.
                using var verifier = ECDsa.Create();
                verifier.ImportSubjectPublicKeyInfo(Convert.FromBase64String(publicKey), out _);
                if (verifier.ExportParameters(false).Curve.Oid.Value != "1.2.840.10045.3.1.7") return false;
                if (!verifier.VerifyData(Encoding.UTF8.GetBytes(text), Convert.FromBase64String(signature),
                        HashAlgorithmName.SHA256, DSASignatureFormat.Rfc3279DerSequence)) return false;
            }
            return true;
        }
        finally
        {
            cng.Delete();
        }
    }
}
