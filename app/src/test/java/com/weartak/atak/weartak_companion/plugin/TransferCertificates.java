package com.weartak.atak.weartak_companion.plugin;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.Key;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** Generates throwaway identities in the test JVM. The repository holds no fixture key bytes. */
final class TransferCertificates {
  private static final String PASSWORD = "test-only";
  private static Map<String, byte[]> fixtures;

  static synchronized byte[] fixture(String name) {
    if (fixtures == null) {
      try {
        fixtures = generate();
      } catch (Exception failure) {
        throw new AssertionError("Could not generate the transfer test certificates", failure);
      }
    }
    return fixtures.get(name).clone();
  }

  private static Map<String, byte[]> generate() throws Exception {
    Path directory = Files.createTempDirectory("companion-transfer-test");
    try {
      generateKey(directory, "root", "Companion Test Root", true);
      generateKey(directory, "other", "Unrelated Test Root", true);
      generateKey(directory, "client", "Companion Test Client", false);
      keytool(directory, "-certreq", "-alias", "client", "-keystore", "client.p12",
          "-storepass", PASSWORD, "-file", "client.csr");
      keytool(directory, "-gencert", "-alias", "root", "-keystore", "root.p12",
          "-storepass", PASSWORD, "-infile", "client.csr", "-outfile", "client.cer",
          "-ext", "eku=clientAuth", "-validity", "2");

      Certificate root = load(directory.resolve("root.p12")).getCertificate("root");
      Certificate other = load(directory.resolve("other.p12")).getCertificate("other");
      Key key = load(directory.resolve("client.p12")).getKey("client", PASSWORD.toCharArray());
      Certificate leaf;
      try (InputStream signed = Files.newInputStream(directory.resolve("client.cer"))) {
        leaf = CertificateFactory.getInstance("X.509").generateCertificate(signed);
      }
      return Map.of(
          "client", identity(PASSWORD, key, leaf, root),
          "leaf", identity(PASSWORD, key, leaf),
          "empty", identity("", key, leaf, root),
          "spaced", identity("  synthetic  ", key, leaf, root),
          "root", trust(root),
          "other", trust(other));
    } finally {
      try (Stream<Path> entries = Files.list(directory)) {
        for (Path entry : (Iterable<Path>) entries::iterator) {
          Files.deleteIfExists(entry);
        }
      }
      Files.deleteIfExists(directory);
    }
  }

  private static void generateKey(Path directory, String alias, String name, boolean ca)
      throws Exception {
    List<String> args = new ArrayList<>(Arrays.asList(
        "-genkeypair", "-alias", alias, "-keystore", alias + ".p12", "-storetype", "PKCS12",
        "-storepass", PASSWORD, "-keyalg", "EC", "-groupname", "secp256r1",
        "-dname", "CN=" + name, "-validity", "2"));
    if (ca) {
      args.addAll(Arrays.asList("-ext", "bc=ca:true", "-ext", "ku=keyCertSign,cRLSign"));
    }
    keytool(directory, args.toArray(new String[0]));
  }

  private static void keytool(Path directory, String... arguments) throws Exception {
    String executable = System.getProperty("os.name").startsWith("Windows")
        ? "keytool.exe" : "keytool";
    List<String> command = new ArrayList<>();
    command.add(Paths.get(System.getProperty("java.home"), "bin", executable).toString());
    command.addAll(Arrays.asList(arguments));
    Process process = new ProcessBuilder(command).directory(directory.toFile())
        .redirectErrorStream(true).start();
    byte[] output;
    try (InputStream input = process.getInputStream()) {
      output = input.readAllBytes();
    }
    if (process.waitFor() != 0) {
      throw new IllegalStateException(new String(output, StandardCharsets.UTF_8));
    }
  }

  private static KeyStore load(Path path) throws Exception {
    KeyStore store = KeyStore.getInstance("PKCS12");
    try (InputStream input = Files.newInputStream(path)) {
      store.load(input, PASSWORD.toCharArray());
    }
    return store;
  }

  private static byte[] identity(String password, Key key, Certificate... chain)
      throws Exception {
    KeyStore store = KeyStore.getInstance("PKCS12");
    store.load(null, null);
    store.setKeyEntry("client", key, password.toCharArray(), chain);
    return encode(store, password);
  }

  private static byte[] trust(Certificate certificate) throws Exception {
    KeyStore store = KeyStore.getInstance("PKCS12");
    store.load(null, null);
    store.setCertificateEntry("ca", certificate);
    return encode(store, PASSWORD);
  }

  private static byte[] encode(KeyStore store, String password) throws Exception {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    store.store(output, password.toCharArray());
    return output.toByteArray();
  }
}
