package com.weartak.atak.weartak_companion.plugin;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.*;
import org.json.JSONObject;

/** Guided transfer state is built from a watch snapshot, never the Custom editor's draft. */
final class AtakServerTransfer {
  enum Mode {
    AUTO_ENROLL,
    SOFT_CERTIFICATES,
    SERVER_ONLY
  }

  static Mode detected(AtakServerImport.Connection connection, boolean hasCertificate) {
    return connection.enrollment
        ? Mode.AUTO_ENROLL
        : hasCertificate ? Mode.SOFT_CERTIFICATES : null;
  }

  static final class Identity {
    final String callsign, team, role;

    Identity(String callsign, String team, String role) {
      this.callsign = callsign;
      this.team = team;
      this.role = role;
    }
  }

  static TakServerItem server(
      AtakServerImport.Connection connection,
      Mode mode,
      String username,
      String password,
      String certificate,
      String filePassword) {
    if (mode == null) throw new IllegalArgumentException("Choose what to transfer.");
    TakServerItem item = AtakServerImport.draft(connection);
    item.isP12Cert = false;
    item.username = item.password = item.p12Cert = item.p12CertPassword = "";
    if (mode == Mode.AUTO_ENROLL) {
      if (username == null || username.trim().isEmpty() || password == null || password.isEmpty())
        throw new IllegalArgumentException("Enter the enrollment username and password.");
      item.username = username;
      item.password = password;
    } else if (mode == Mode.SOFT_CERTIFICATES) {
      if (certificate == null
          || certificate.isEmpty()
          || filePassword == null
          || filePassword.isEmpty())
        throw new IllegalArgumentException("The client certificate is not ready yet.");
      item.isP12Cert = true;
      item.p12Cert = certificate;
      item.p12CertPassword = filePassword;
    } else
      item.isEnabled = false; // A details-only entry must not start enrollment with an empty login.
    return item;
  }

  static JSONObject settings(
      String watchSnapshot,
      AtakServerImport.Connection connection,
      TakServerItem imported,
      Identity identity)
      throws Exception {
    org.json.JSONArray servers = new org.json.JSONArray(watchSnapshot);
    int match = -1;
    for (int i = 0; i < servers.length(); i++) {
      JSONObject existing = servers.getJSONObject(i);
      if (AtakServerImport.existing(
              Collections.singletonList(TakServerItem.fromJson(existing)), connection)
          == 0) {
        match = i;
        break;
      }
    }
    JSONObject replacement = imported.toJson();
    if (match >= 0) {
      JSONObject previous = servers.getJSONObject(match);
      Iterator<String> keys = replacement.keys();
      while (keys.hasNext()) {
        String key = keys.next();
        if (!key.equals("serverId")) previous.put(key, replacement.get(key));
      }
    } else servers.put(replacement);
    JSONObject envelope =
        SettingsPayloadBuilder.build(
            Collections.singletonList(imported),
            null,
            identity == null ? null : identity.callsign,
            identity == null ? null : identity.team,
            identity == null ? null : identity.role,
            false,
            null,
            identity != null);
    envelope.getJSONObject("payload").put("takServerList", servers);
    return envelope;
  }

  interface CertificateAccess {
    AtakServerImport.SavedP12 client(String host, int port);

    AtakServerImport.SavedP12 defaultClient();

    AtakServerImport.SavedP12 ca(String host, int port);

    AtakServerImport.SavedP12 defaultCa();
  }

  static SoftMaterial softMaterial(
      AtakServerImport.Connection connection, CertificateAccess access) {
    AtakServerImport.SavedP12 client = access.client(connection.host, connection.port);
    if ((client == null || client.bytes == null) && !connection.enrollment)
      client = access.defaultClient();
    AtakServerImport.SavedP12 ca = access.ca(connection.host, connection.port);
    if (ca == null || ca.bytes == null) ca = access.defaultCa();
    return new SoftMaterial(client, ca);
  }

  static final class SoftMaterial implements AutoCloseable {
    byte[] client, ca;
    String clientPassword, caPassword;

    SoftMaterial(AtakServerImport.SavedP12 client, AtakServerImport.SavedP12 ca) {
      this.client = client == null || client.bytes == null ? null : client.bytes.clone();
      this.ca = ca == null || ca.bytes == null ? null : ca.bytes.clone();
      this.clientPassword = client == null ? null : client.password;
      this.caPassword = ca == null ? null : ca.password;
    }

    @Override
    public void close() {
      if (client != null) Arrays.fill(client, (byte) 0);
      if (ca != null) Arrays.fill(ca, (byte) 0);
      client = ca = null;
      clientPassword = caPassword = null;
    }
  }

  static final class SoftResult implements AutoCloseable {
    byte[] bytes;
    final String password;
    final String problem;
    final boolean clientPasswordNeeded, caPasswordNeeded;
    String trustNotice;

    SoftResult(
        byte[] bytes, String password, String problem, boolean clientNeeded, boolean caNeeded) {
      this.bytes = bytes;
      this.password = password;
      this.problem = problem;
      clientPasswordNeeded = clientNeeded;
      caPasswordNeeded = caNeeded;
    }

    boolean ready() {
      return bytes != null;
    }

    @Override
    public void close() {
      if (bytes != null) Arrays.fill(bytes, (byte) 0);
      bytes = null;
    }
  }

  static SoftResult softCertificates(SoftMaterial material) {
    if (material.client == null || material.client.length == 0)
      return new SoftResult(
          null,
          null,
          "ATAK has no stored client certificate for this connection. Select its client certificate"
              + " below.",
          false,
          false);
    if (material.client.length > AtakServerImport.MAX_P12_BYTES)
      return new SoftResult(
          null, null, "The client certificate exceeds the watch transfer limit.", false, false);
    KeyStore client;
    try {
      client = open(material.client, material.clientPassword);
    } catch (Exception ignored) {
      return new SoftResult(
          null, null, "Enter the password that opens the client certificate.", true, false);
    }
    if (material.ca != null && material.ca.length > AtakServerImport.MAX_P12_BYTES)
      return new SoftResult(
          null, null, "The CA trust store exceeds the watch transfer limit.", false, false);
    KeyStore ca = null;
    if (material.ca != null) {
      try {
        ca = open(material.ca, material.caPassword);
      } catch (Exception ignored) {
        return new SoftResult(
            null, null, "Enter the password that opens ATAK's CA trust store.", false, true);
      }
    }
    char[] sourcePassword = material.clientPassword.toCharArray();
    char[] target = null;
    try {
      String alias = null;
      for (Enumeration<String> e = client.aliases(); e.hasMoreElements(); ) {
        String a = e.nextElement();
        if (client.isKeyEntry(a) && client.getKey(a, sourcePassword) instanceof PrivateKey) {
          alias = a;
          break;
        }
      }
      if (alias == null
          || client.getCertificateChain(alias) == null
          || client.getCertificateChain(alias).length == 0)
        return new SoftResult(
            null,
            null,
            "The selected file has no client private key and certificate chain.",
            false,
            false);
      List<Certificate> chain = new ArrayList<>(Arrays.asList(client.getCertificateChain(alias)));
      List<X509Certificate> authorities = new ArrayList<>();
      if (ca != null)
        for (Enumeration<String> e = ca.aliases(); e.hasMoreElements(); ) {
          String a = e.nextElement();
          Certificate cert = ca.getCertificate(a);
          if (cert instanceof X509Certificate
              && ((X509Certificate) cert).getBasicConstraints() >= 0)
            authorities.add((X509Certificate) cert);
        }
      if (ca != null && authorities.isEmpty())
        return new SoftResult(
            null, null, "ATAK's CA store contains no CA certificates.", false, false);
      // Complete a client chain from the actual selected trust store when necessary.
      for (int i = 0; i < 16; i++) {
        X509Certificate top = (X509Certificate) chain.get(chain.size() - 1);
        if (top.getIssuerX500Principal().equals(top.getSubjectX500Principal())) break;
        X509Certificate issuer = null;
        for (X509Certificate candidate : authorities)
          if (!chain.contains(candidate)
              && top.getIssuerX500Principal().equals(candidate.getSubjectX500Principal())) {
            try {
              top.verify(candidate.getPublicKey());
              issuer = candidate;
              break;
            } catch (Exception ignored) {
            }
          }
        if (issuer == null) break;
        chain.add(issuer);
      }
      String password = UUID.randomUUID().toString();
      target = password.toCharArray();
      KeyStore output = KeyStore.getInstance("PKCS12");
      output.load(null, target);
      output.setKeyEntry(
          "client",
          client.getKey(alias, sourcePassword),
          target,
          chain.toArray(new Certificate[0]));
      for (int i = 0; i < authorities.size(); i++)
        output.setCertificateEntry("ataK-ca-" + i, authorities.get(i));
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      output.store(bytes, target);
      Arrays.fill(target, '\0');
      byte[] encoded = bytes.toByteArray();
      if (encoded.length > AtakServerImport.MAX_P12_BYTES) {
        Arrays.fill(encoded, (byte) 0);
        return new SoftResult(
            null, null, "The certificate bundle exceeds the watch transfer limit.", false, false);
      }
      SoftResult result = new SoftResult(encoded, password, null, false, false);
      X509Certificate top = (X509Certificate) chain.get(chain.size() - 1);
      boolean separateTrust = false;
      for (X509Certificate authority : authorities)
        if (!chain.contains(authority)) separateTrust = true;
      if (separateTrust || !top.getSubjectX500Principal().equals(top.getIssuerX500Principal()))
        result.trustNotice =
            "A separate server CA, if used, must already be trusted or approved on WearTAK.";
      return result;
    } catch (Exception ignored) {
      return new SoftResult(
          null, null, "The client certificate could not be prepared for the watch.", false, false);
    } finally {
      Arrays.fill(sourcePassword, '\0');
      if (target != null) Arrays.fill(target, '\0');
    }
  }

  private static KeyStore open(byte[] bytes, String password) throws Exception {
    if (password == null) throw new IllegalArgumentException();
    char[] chars = password.toCharArray();
    try {
      KeyStore store = KeyStore.getInstance("PKCS12");
      store.load(new ByteArrayInputStream(bytes), chars);
      return store;
    } finally {
      Arrays.fill(chars, '\0');
    }
  }

  private AtakServerTransfer() {}
}
