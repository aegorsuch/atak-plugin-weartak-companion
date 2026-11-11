package com.weartak.atak.weartak_companion.plugin;

import static org.junit.Assert.*;

import java.io.*;
import java.security.KeyStore;
import java.util.*;
import org.json.*;
import org.junit.Test;

public class AtakServerTransferTest {
  static AtakServerImport.Connection connection(boolean enrollment) {
    return new AtakServerImport.Connection(
        "tak.example:8089:ssl",
        "Test server",
        "tak.example",
        8089,
        "ssl",
        true,
        true,
        false,
        enrollment);
  }

  static AtakServerImport.SavedP12 saved(String name, String password) throws Exception {
    return new AtakServerImport.SavedP12(TransferCertificates.fixture(name), password);
  }

  static AtakServerTransfer.SoftMaterial material(String name, String password) throws Exception {
    return new AtakServerTransfer.SoftMaterial(saved(name, password), null);
  }

  @Test
  public void enrollmentFlagTakesPrecedenceOverIssuedCertificate() {
    assertEquals(
        AtakServerTransfer.Mode.AUTO_ENROLL, AtakServerTransfer.detected(connection(true), true));
    assertEquals(
        AtakServerTransfer.Mode.AUTO_ENROLL, AtakServerTransfer.detected(connection(true), false));
    assertEquals(
        AtakServerTransfer.Mode.SOFT_CERTIFICATES,
        AtakServerTransfer.detected(connection(false), true));
    assertNull(AtakServerTransfer.detected(connection(false), false));
  }

  @Test
  public void autoEnrollmentDiscardsCertificateArgumentsAndKeepsExactLogin() {
    TakServerItem item =
        AtakServerTransfer.server(
            connection(true),
            AtakServerTransfer.Mode.AUTO_ENROLL,
            " account ",
            " password ",
            "old-cert",
            "old-pass");
    assertFalse(item.isP12Cert);
    assertEquals(" account ", item.username);
    assertEquals(" password ", item.password);
    assertEquals("", item.p12Cert);
    assertEquals("", item.p12CertPassword);
  }

  @Test
  public void autoEnrollmentRejectsIncompleteLogin() {
    for (String[] login : new String[][] {{null, "p"}, {" ", "p"}, {"u", null}, {"u", ""}}) {
      try {
        AtakServerTransfer.server(
            connection(true), AtakServerTransfer.Mode.AUTO_ENROLL, login[0], login[1], null, null);
        fail();
      } catch (IllegalArgumentException expected) {
      }
    }
  }

  @Test
  public void softCertificateDiscardsAccountArguments() {
    TakServerItem item =
        AtakServerTransfer.server(
            connection(false),
            AtakServerTransfer.Mode.SOFT_CERTIFICATES,
            "old-user",
            "old-login",
            "certificate",
            "file-password");
    assertTrue(item.isP12Cert);
    assertEquals("", item.username);
    assertEquals("", item.password);
    assertEquals("certificate", item.p12Cert);
    assertEquals("file-password", item.p12CertPassword);
  }

  @Test
  public void detailsOnlyClearsAllAuthenticationAndStartsDisabled() {
    TakServerItem item =
        AtakServerTransfer.server(
            connection(true),
            AtakServerTransfer.Mode.SERVER_ONLY,
            "old-user",
            "old-pass",
            "old-cert",
            "old-file-password");
    assertFalse(item.isEnabled);
    assertFalse(item.isP12Cert);
    assertEquals("", item.username);
    assertEquals("", item.password);
    assertEquals("", item.p12Cert);
    assertEquals("", item.p12CertPassword);
  }

  @Test
  public void rawWatchSnapshotPreservesUnknownStateAndNeverMutatesInput() throws Exception {
    JSONObject existing =
        new JSONObject()
            .put("address", "TAK.EXAMPLE")
            .put("port", 8089)
            .put("serverId", "watch-owned-id")
            .put("identityEpoch", 42)
            .put("strictServerTrust", true);
    JSONObject unrelated =
        new JSONObject()
            .put("address", "another.example")
            .put("port", 8089)
            .put("futureField", "keep me")
            .put("isP12Cert", true);
    String snapshot = new JSONArray().put(existing).put(unrelated).toString();
    TakServerItem item =
        AtakServerTransfer.server(
            connection(true), AtakServerTransfer.Mode.AUTO_ENROLL, "u", "p", null, null);
    JSONObject payload =
        AtakServerTransfer.settings(snapshot, connection(true), item, null)
            .getJSONObject("payload");
    JSONArray servers = payload.getJSONArray("takServerList");
    assertEquals(2, servers.length());
    assertEquals("watch-owned-id", servers.getJSONObject(0).getString("serverId"));
    assertEquals(42, servers.getJSONObject(0).getInt("identityEpoch"));
    assertTrue(servers.getJSONObject(0).getBoolean("strictServerTrust"));
    assertEquals(unrelated.toString(), servers.getJSONObject(1).toString());
    assertEquals(snapshot, new JSONArray().put(existing).put(unrelated).toString());
    assertFalse(payload.has("callsign"));
    assertFalse(payload.has("team"));
    assertFalse(payload.has("role"));
    assertFalse(payload.has("reportIntSecs"));
    assertFalse(payload.has("unitPrefs"));
    assertFalse(payload.getBoolean("phoneManagedSettings"));
  }

  @Test
  public void newServerIsAppendedAndIdentityIsOnlyIncludedWhenRequested() throws Exception {
    TakServerItem item =
        AtakServerTransfer.server(
            connection(true), AtakServerTransfer.Mode.AUTO_ENROLL, "u", "p", null, null);
    JSONObject payload =
        AtakServerTransfer.settings(
                "[]",
                connection(true),
                item,
                new AtakServerTransfer.Identity("Watch user", "Blue", "Team Member"))
            .getJSONObject("payload");
    assertEquals(1, payload.getJSONArray("takServerList").length());
    assertEquals("Watch user", payload.getString("callsign"));
    assertEquals("Blue", payload.getString("team"));
    assertEquals("Team Member", payload.getString("role"));
    assertFalse(payload.getBoolean("phoneManagedSettings"));
  }

  @Test
  public void retainedPasswordsProduceUsableRewrappedClientIdentity() throws Exception {
    try (AtakServerTransfer.SoftMaterial m = material("client", "test-only");
        AtakServerTransfer.SoftResult result = AtakServerTransfer.softCertificates(m)) {
      assertTrue(result.problem, result.ready());
      assertTrue(AtakServerImport.usableClientP12(result.bytes, result.password));
      assertNull(result.trustNotice);
      assertFalse(Arrays.equals(m.client, result.bytes));
      assertFalse(result.password.equals("test-only"));
    }
  }

  @Test
  public void blankAndWhitespaceFilePasswordsAreAcceptedAndRewrapped() throws Exception {
    for (String[] pair : new String[][] {{"empty", ""}, {"spaced", "  synthetic  "}}) {
      try (AtakServerTransfer.SoftMaterial m = material(pair[0], pair[1]);
          AtakServerTransfer.SoftResult result = AtakServerTransfer.softCertificates(m)) {
        assertTrue(result.problem, result.ready());
        assertTrue(AtakServerImport.usableClientP12(result.bytes, result.password));
        assertEquals(result.password, result.password.trim());
        assertFalse(result.password.isEmpty());
      }
    }
  }

  @Test
  public void missingOrWrongClientPasswordPromptsOnlyForClientPassword() throws Exception {
    for (String password : new String[] {null, "incorrect"})
      try (AtakServerTransfer.SoftMaterial m = material("client", password);
          AtakServerTransfer.SoftResult result = AtakServerTransfer.softCertificates(m)) {
        assertFalse(result.ready());
        assertTrue(result.clientPasswordNeeded);
        assertFalse(result.caPasswordNeeded);
      }
  }

  @Test
  public void missingCaPasswordPromptsOnlyForCaPassword() throws Exception {
    try (AtakServerTransfer.SoftMaterial m =
            new AtakServerTransfer.SoftMaterial(saved("client", "test-only"), saved("root", null));
        AtakServerTransfer.SoftResult result = AtakServerTransfer.softCertificates(m)) {
      assertFalse(result.ready());
      assertFalse(result.clientPasswordNeeded);
      assertTrue(result.caPasswordNeeded);
    }
  }

  @Test
  public void selectedCaCompletesTheClientChain() throws Exception {
    try (AtakServerTransfer.SoftMaterial m =
            new AtakServerTransfer.SoftMaterial(
                saved("leaf", "test-only"), saved("root", "test-only"));
        AtakServerTransfer.SoftResult result = AtakServerTransfer.softCertificates(m)) {
      assertTrue(result.problem, result.ready());
      KeyStore ks = KeyStore.getInstance("PKCS12");
      ks.load(new ByteArrayInputStream(result.bytes), result.password.toCharArray());
      assertEquals(2, ks.getCertificateChain("client").length);
      assertNull(result.trustNotice);
    }
  }

  @Test
  public void separateServerCaProducesExplicitWatchTrustNotice() throws Exception {
    try (AtakServerTransfer.SoftMaterial m =
            new AtakServerTransfer.SoftMaterial(
                saved("client", "test-only"), saved("other", "test-only"));
        AtakServerTransfer.SoftResult result = AtakServerTransfer.softCertificates(m)) {
      assertTrue(result.problem, result.ready());
      assertNotNull(result.trustNotice);
    }
  }

  @Test
  public void caOnlyFileIsRejectedAsClientIdentity() throws Exception {
    try (AtakServerTransfer.SoftMaterial m = material("root", "test-only");
        AtakServerTransfer.SoftResult result = AtakServerTransfer.softCertificates(m)) {
      assertFalse(result.ready());
      assertFalse(result.clientPasswordNeeded);
      assertTrue(result.problem.contains("private key"));
    }
  }

  @Test
  public void missingClientRequestsFileRatherThanInventingDefaultPassword() {
    try (AtakServerTransfer.SoftMaterial m = new AtakServerTransfer.SoftMaterial(null, null);
        AtakServerTransfer.SoftResult result = AtakServerTransfer.softCertificates(m)) {
      assertFalse(result.ready());
      assertFalse(result.clientPasswordNeeded);
      assertTrue(result.problem.contains("Select"));
    }
  }

  @Test
  public void ownedCertificateBuffersAreClonedAndWiped() throws Exception {
    byte[] original = TransferCertificates.fixture("client");
    AtakServerTransfer.SoftMaterial m =
        new AtakServerTransfer.SoftMaterial(
            new AtakServerImport.SavedP12(original, "test-only"), null);
    byte[] owned = m.client;
    AtakServerTransfer.SoftResult result = AtakServerTransfer.softCertificates(m);
    byte[] output = result.bytes;
    m.close();
    result.close();
    assertNotEquals(0, original[0]);
    assertEquals(0, owned[0]);
    assertEquals(0, output[0]);
    assertNull(m.client);
    assertNull(result.bytes);
  }

  static class Store implements AtakServerTransfer.CertificateAccess {
    AtakServerImport.SavedP12 scopedClient, globalClient, scopedCa, globalCa;
    int globalClientReads, globalCaReads;

    public AtakServerImport.SavedP12 client(String host, int port) {
      assertEquals("tak.example", host);
      assertEquals(8089, port);
      return scopedClient;
    }

    public AtakServerImport.SavedP12 defaultClient() {
      globalClientReads++;
      return globalClient;
    }

    public AtakServerImport.SavedP12 ca(String host, int port) {
      assertEquals("tak.example", host);
      assertEquals(8089, port);
      return scopedCa;
    }

    public AtakServerImport.SavedP12 defaultCa() {
      globalCaReads++;
      return globalCa;
    }
  }

  @Test
  public void clientAndCaUseTheirOwnScopesAndPasswords() throws Exception {
    Store s = new Store();
    s.scopedClient = saved("client", "client-scoped-password");
    s.globalCa = saved("root", "ca-default-password");
    try (AtakServerTransfer.SoftMaterial m =
        AtakServerTransfer.softMaterial(connection(false), s)) {
      assertEquals("client-scoped-password", m.clientPassword);
      assertEquals("ca-default-password", m.caPassword);
      assertEquals(0, s.globalClientReads);
      assertEquals(1, s.globalCaReads);
    }
  }

  @Test
  public void absentScopedSoftCertificateUsesDefaultButEnrollmentNeverBorrowsIt() throws Exception {
    Store s = new Store();
    s.globalClient = saved("client", "test-only");
    try (AtakServerTransfer.SoftMaterial m =
        AtakServerTransfer.softMaterial(connection(false), s)) {
      assertArrayEquals(s.globalClient.bytes, m.client);
    }
    s.globalClientReads = 0;
    try (AtakServerTransfer.SoftMaterial m = AtakServerTransfer.softMaterial(connection(true), s)) {
      assertNull(m.client);
      assertEquals(0, s.globalClientReads);
    }
  }
}
