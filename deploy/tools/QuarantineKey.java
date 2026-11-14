// SPDX-License-Identifier: AGPL-3.0-only
//
// The operator's offline tool for "Ask the developer" items (census#81). JDK 21 or later, no dependencies; run it
// as a single-file program on the operator's own machine, never on the census host:
//
//   java QuarantineKey.java keygen <private-key-file>
//       Writes a new X25519 private key (base64 PKCS#8, mode 600) and prints the public key (base64 X.509) to set as
//       CENSUS_OPERATOR_PUBLIC_KEY on the server.
//   java QuarantineKey.java open <private-key-file> <item-json-file>
//       Reads one item as GET /ops/quarantine/{id} returns it ({"id":"…","artifact":"<base64>",…}) and writes the
//       artifact's bytes, exactly as the phone sent them, to standard output.
//
// Format v1 (QuarantineCrypto.kt): 0x01 | ephemeral public key (X.509, 44 bytes) | nonce (12) | AES-256-GCM output.
// Key: HKDF-SHA256 over the X25519 shared secret, salt = the ephemeral key (X.509), info and associated
// data = "census.quarantine.v1:" + item id.
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public class QuarantineKey {
    public static void main(String[] args) throws Exception {
        if (args.length == 2 && args[0].equals("keygen")) {
            keygen(Path.of(args[1]));
        } else if (args.length == 3 && args[0].equals("open")) {
            System.out.write(open(Path.of(args[1]), Files.readString(Path.of(args[2]))));
            System.out.flush();
        } else {
            System.err.println("usage: java QuarantineKey.java keygen <private-key-file> | open <private-key-file> <item-json-file>");
            System.exit(2);
        }
    }

    static void keygen(Path privateFile) throws Exception {
        KeyPair pair = KeyPairGenerator.getInstance("X25519").generateKeyPair();
        Files.createFile(privateFile, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        Files.writeString(privateFile, Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()) + "\n");
        System.out.println(Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()));
    }

    static byte[] open(Path privateFile, String itemJson) throws Exception {
        String id = field(itemJson, "id");
        byte[] sealed = Base64.getDecoder().decode(field(itemJson, "artifact"));
        PrivateKey operator = KeyFactory.getInstance("X25519")
            .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(Files.readString(privateFile).trim())));
        if (sealed.length < 1 + 44 + 12 + 16 || sealed[0] != 1) throw new IllegalArgumentException("not a v1 item");
        byte[] ephemeralBytes = Arrays.copyOfRange(sealed, 1, 45);
        byte[] nonce = Arrays.copyOfRange(sealed, 45, 57);
        PublicKey ephemeral = KeyFactory.getInstance("X25519").generatePublic(new X509EncodedKeySpec(ephemeralBytes));
        KeyAgreement agreement = KeyAgreement.getInstance("X25519");
        agreement.init(operator);
        agreement.doPhase(ephemeral, true);
        byte[] shared = agreement.generateSecret();
        byte[] label = ("census.quarantine.v1:" + id).getBytes(StandardCharsets.UTF_8);
        byte[] key = hkdf(shared, ephemeralBytes, label);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        cipher.updateAAD(label);
        return cipher.doFinal(sealed, 57, sealed.length - 57);
    }

    static byte[] hkdf(byte[] ikm, byte[] salt, byte[] info) throws Exception {
        Mac extract = Mac.getInstance("HmacSHA256");
        extract.init(new SecretKeySpec(salt, "HmacSHA256"));
        byte[] prk = extract.doFinal(ikm);
        Mac expand = Mac.getInstance("HmacSHA256");
        expand.init(new SecretKeySpec(prk, "HmacSHA256"));
        return expand.doFinal(concat(info, new byte[] {1}));
    }

    static String field(String json, String name) {
        Matcher match = Pattern.compile("\"" + name + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        if (!match.find()) throw new IllegalArgumentException("missing " + name);
        return match.group(1);
    }

    static byte[] concat(byte[] a, byte[] b) {
        byte[] out = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
