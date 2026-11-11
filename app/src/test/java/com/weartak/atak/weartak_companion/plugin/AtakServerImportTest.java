package com.weartak.atak.weartak_companion.plugin;

import static org.junit.Assert.*;

import java.util.*;
import org.json.JSONObject;
import org.junit.Test;

public class AtakServerImportTest {
  private static byte[] client() {
    return TransferCertificates.fixture("client");
  }

  private static AtakServerImport.Connection c(
      String host,
      int port,
      boolean connected,
      boolean enabled,
      boolean stream,
      boolean enrollment) {
    return new AtakServerImport.Connection(
        host + ":" + port, host, host, port, "ssl", connected, enabled, stream, enrollment);
  }

  private static AtakServerImport.Connection normal() {
    return c("tak.example", 8089, true, true, false, false);
  }

  private static final class Store implements AtakServerImport.CertificateAccess {
    AtakServerImport.SavedP12 server, global;
    String host;
    int port, serverReads, globalReads;

    @Override
    public AtakServerImport.SavedP12 forServer(String host, int port) {
      this.host = host;
      this.port = port;
      serverReads++;
      return server;
    }

    @Override
    public AtakServerImport.SavedP12 defaults() {
      globalReads++;
      return global;
    }
  }

  private static Store scoped() {
    Store store = new Store();
    store.server = new AtakServerImport.SavedP12(client(), "test-only");
    return store;
  }

  @Test
  public void connectedThenEnabledServersSortFirstWithoutChangingInput() {
    AtakServerImport.Connection disabled = c("a", 8089, false, false, false, false);
    AtakServerImport.Connection disconnected = c("b", 8089, false, true, false, false);
    AtakServerImport.Connection connected = c("z", 8089, true, true, false, false);
    List<AtakServerImport.Connection> input = Arrays.asList(disabled, disconnected, connected);
    List<AtakServerImport.Connection> out = AtakServerImport.ordered(input);
    assertSame(connected, out.get(0));
    assertSame(disconnected, out.get(1));
    assertSame(disabled, out.get(2));
    assertSame(disabled, input.get(0));
  }

  @Test
  public void draftCopiesEndpointAndStateWithoutInventingWatchIdentityOrCredentials() {
    TakServerItem draft = AtakServerImport.draft(normal());
    assertEquals("tak.example", draft.address);
    assertEquals(8089, draft.port);
    assertTrue(draft.isEnabled);
    assertTrue(draft.isP12Cert);
    assertNull(draft.serverId);
    assertEquals("", draft.p12Cert);
    assertEquals("", draft.password);
  }

  @Test
  public void disabledServerRemainsDisabled() {
    assertFalse(AtakServerImport.draft(c("tak", 8089, false, false, false, false)).isEnabled);
  }

  @Test
  public void tcpQuicAndInvalidEndpointsAreNotSilentlyConverted() {
    for (String p : new String[] {"tcp", "quic", "udp", ""}) {
      AtakServerImport.Connection x =
          new AtakServerImport.Connection(p, p, "tak", 8089, p, true, true, false, false);
      assertFalse(x.supported());
      try {
        AtakServerImport.draft(x);
        fail();
      } catch (IllegalArgumentException expected) {
      }
    }
    assertFalse(c("", 8089, true, true, false, false).supported());
    assertFalse(c("tak", 0, true, true, false, false).supported());
    assertFalse(c("tak", 65536, true, true, false, false).supported());
  }

  @Test
  public void duplicateSelectionPreservesWatchCredentialsAndRecognizesIpv6Brackets() {
    TakServerItem item = AtakServerImport.draft(normal());
    item.address = "TAK.EXAMPLE";
    item.p12Cert = "watch-specific";
    item.p12CertPassword = "keep";
    assertEquals(0, AtakServerImport.existing(Collections.singletonList(item), normal()));
    assertEquals("watch-specific", item.p12Cert);
    assertEquals("keep", item.p12CertPassword);
    assertEquals(
        -1,
        AtakServerImport.existing(
            Collections.singletonList(item), c("tak.example", 18089, true, true, false, false)));
    item.address = "[2001:db8::1]";
    assertEquals(
        0,
        AtakServerImport.existing(
            Collections.singletonList(item), c("2001:db8::1", 8089, true, true, false, false)));
  }

  @Test
  public void clientP12RequiresCorrectPasswordAndPrivateKey() {
    assertTrue(AtakServerImport.usableClientP12(client(), "test-only"));
    assertFalse(AtakServerImport.usableClientP12(client(), "incorrect"));
    assertFalse(AtakServerImport.usableClientP12(TransferCertificates.fixture("root"), "test-only"));
  }

  @Test
  public void emptyCorruptAndOversizeP12FailSafely() {
    assertFalse(AtakServerImport.usableClientP12(null, "test-only"));
    assertFalse(AtakServerImport.usableClientP12(new byte[0], "test-only"));
    assertFalse(AtakServerImport.usableClientP12(new byte[] {1, 2, 3}, "test-only"));
    assertFalse(
        AtakServerImport.usableClientP12(
            new byte[AtakServerImport.MAX_P12_BYTES + 1], "test-only"));
    assertFalse(AtakServerImport.usableClientP12(client(), null));
  }

  @Test
  public void scopedCertificateUsesExactHostAndPortAndNeverReadsDefaultIdentity() {
    Store store = scoped();
    try (AtakServerImport.CertificateResult result =
        AtakServerImport.clientCertificate(normal(), store)) {
      assertTrue(result.available());
      assertArrayEquals(client(), result.bytes);
      assertEquals("tak.example", store.host);
      assertEquals(8089, store.port);
      assertEquals(0, store.globalReads);
    }
  }

  @Test
  public void defaultCertificateIsUsedOnlyWhenConnectionHasNoScopedCertificate() {
    Store store = new Store();
    store.global = new AtakServerImport.SavedP12(client(), "test-only");
    try (AtakServerImport.CertificateResult result =
        AtakServerImport.clientCertificate(normal(), store)) {
      assertTrue(result.available());
      assertEquals(1, store.serverReads);
      assertEquals(1, store.globalReads);
    }
  }

  @Test
  public void enrollmentWithTrustNeverBorrowsDefaultIdentity() {
    Store store = new Store();
    store.global = new AtakServerImport.SavedP12(client(), "test-only");
    try (AtakServerImport.CertificateResult result =
        AtakServerImport.clientCertificate(c("tak", 8089, true, true, false, true), store)) {
      assertFalse(result.available());
      assertEquals(0, store.globalReads);
    }
  }

  @Test
  public void scopedPasswordFailureDoesNotFallBackToAnotherCertificate() {
    Store store = scoped();
    store.server = new AtakServerImport.SavedP12(client(), "incorrect");
    store.global = new AtakServerImport.SavedP12(client(), "test-only");
    try (AtakServerImport.CertificateResult result =
        AtakServerImport.clientCertificate(normal(), store)) {
      assertFalse(result.available());
      assertEquals(0, store.globalReads);
      assertFalse(result.message.contains("incorrect"));
    }
  }

  @Test
  public void missingScopedPasswordDoesNotBorrowDefaultPassword() {
    Store store = scoped();
    store.server = new AtakServerImport.SavedP12(client(), null);
    store.global = new AtakServerImport.SavedP12(client(), "test-only");
    try (AtakServerImport.CertificateResult result =
        AtakServerImport.clientCertificate(normal(), store)) {
      assertFalse(result.available());
      assertEquals(0, store.globalReads);
    }
  }

  @Test
  public void incompatiblePasswordIsNotSilentlyTrimmed() {
    Store store = scoped();
    store.server = new AtakServerImport.SavedP12(client(), " test-only ");
    try (AtakServerImport.CertificateResult result =
        AtakServerImport.clientCertificate(normal(), store)) {
      assertFalse(result.available());
      assertEquals(0, store.globalReads);
    }
  }

  @Test
  public void combinedStreamAuthenticationDoesNotReadOrPretendToCopyCredentials() {
    Store store = scoped();
    try (AtakServerImport.CertificateResult result =
        AtakServerImport.clientCertificate(c("tak", 8089, true, true, true, false), store)) {
      assertFalse(result.available());
      assertEquals(0, store.serverReads);
      assertEquals(0, store.globalReads);
    }
  }

  @Test
  public void temporaryP12IsWipedWithoutMutatingTheAtakStoreCopy() {
    Store store = scoped();
    byte[] original = store.server.bytes.clone();
    AtakServerImport.CertificateResult result = AtakServerImport.clientCertificate(normal(), store);
    assertNotSame(store.server.bytes, result.bytes);
    result.close();
    assertArrayEquals(new byte[result.bytes.length], result.bytes);
    assertArrayEquals(original, store.server.bytes);
  }

  @Test
  public void selectedServerImportsIntoExistingSettingsPayloadWithItsExactCredentials()
      throws Exception {
    AtakServerImport.Session session = new AtakServerImport.Session();
    List<TakServerItem> list = new ArrayList<>();
    long ticket = session.begin(list);
    TakServerItem draft = AtakServerImport.draft(normal());
    try (AtakServerImport.CertificateResult result =
        AtakServerImport.clientCertificate(normal(), scoped())) {
      draft.p12Cert = Base64.getEncoder().encodeToString(result.bytes);
      draft.p12CertPassword = result.password;
    }
    assertEquals(0, session.apply(ticket, list, true, normal(), draft));
    assertFalse(session.busy());
    JSONObject sent =
        SettingsPayloadBuilder.build(list, 60, "WATCH", "Blue", "Team Member", false)
            .getJSONObject("payload")
            .getJSONArray("takServerList")
            .getJSONObject(0);
    assertEquals("tak.example", sent.getString("address"));
    assertEquals(8089, sent.getInt("port"));
    assertEquals("test-only", sent.getString("p12CertPassword"));
    assertArrayEquals(client(), Base64.getDecoder().decode(sent.getString("p12Cert")));
  }

  @Test
  public void metadataOnlyImportIsBlockedByExistingSendValidation() throws Exception {
    List<TakServerItem> list = new ArrayList<>();
    AtakServerImport.Session s = new AtakServerImport.Session();
    long ticket = s.begin(list);
    s.apply(ticket, list, true, normal(), AtakServerImport.draft(normal()));
    try {
      SettingsPayloadBuilder.build(list, 60, "WATCH", "Blue", "Team Member", false);
      fail("Incomplete P12 must not be sent");
    } catch (IllegalArgumentException expected) {
      assertTrue(expected.getMessage().contains("no certificate"));
    }
  }

  @Test
  public void cancelledImportCannotApplyToANewWatchOrClearItsBusyState() {
    AtakServerImport.Session s = new AtakServerImport.Session();
    List<TakServerItem> a = new ArrayList<>(), b = new ArrayList<>();
    long old = s.begin(a);
    s.cancel();
    long fresh = s.begin(b);
    assertEquals(-1, s.apply(old, b, true, normal(), AtakServerImport.draft(normal())));
    assertTrue(a.isEmpty());
    assertTrue(b.isEmpty());
    assertTrue(s.busy());
    assertTrue(s.current(fresh));
  }

  @Test
  public void refreshingWatchSettingsDiscardsPendingResult() {
    AtakServerImport.Session s = new AtakServerImport.Session();
    List<TakServerItem> original = new ArrayList<>(), refreshed = new ArrayList<>();
    long ticket = s.begin(original);
    assertEquals(-1, s.apply(ticket, refreshed, true, normal(), AtakServerImport.draft(normal())));
    assertTrue(original.isEmpty());
    assertTrue(refreshed.isEmpty());
  }

  @Test
  public void disconnectOrUnsupportedModeDiscardsPendingResult() {
    AtakServerImport.Session s = new AtakServerImport.Session();
    List<TakServerItem> list = new ArrayList<>();
    long t = s.begin(list);
    assertEquals(-1, s.apply(t, list, false, normal(), AtakServerImport.draft(normal())));
    assertTrue(list.isEmpty());
    assertFalse(s.busy());
  }

  @Test
  public void concurrentDraftAdditionIsSelectedWithoutOverwritingCredentials() {
    AtakServerImport.Session s = new AtakServerImport.Session();
    List<TakServerItem> list = new ArrayList<>();
    long t = s.begin(list);
    TakServerItem manual = AtakServerImport.draft(normal());
    manual.p12Cert = "manually-chosen";
    list.add(manual);
    assertEquals(0, s.apply(t, list, true, normal(), AtakServerImport.draft(normal())));
    assertEquals(1, list.size());
    assertSame(manual, list.get(0));
    assertEquals("manually-chosen", manual.p12Cert);
  }

  @Test
  public void duplicateCallbackDoesNotDuplicateServer() {
    AtakServerImport.Session s = new AtakServerImport.Session();
    List<TakServerItem> list = new ArrayList<>();
    long t = s.begin(list);
    s.apply(t, list, true, normal(), AtakServerImport.draft(normal()));
    s.apply(t, list, true, normal(), AtakServerImport.draft(normal()));
    assertEquals(1, list.size());
  }

  @Test
  public void repeatedCancellationInvalidatesAllOldTickets() {
    AtakServerImport.Session s = new AtakServerImport.Session();
    List<TakServerItem> list = new ArrayList<>();
    long t = s.begin(list);
    s.cancel();
    s.cancel();
    assertFalse(s.current(t));
    assertFalse(s.busy());
    assertFalse(s.owns(t, list));
  }

  @Test
  public void postEnrollmentClientCertificateDoesNotNeedEnrollmentLogin() {
    Store store = scoped();
    try (AtakServerImport.CertificateResult result =
        AtakServerImport.clientCertificate(
            c("tak.example", 8089, true, true, false, true), store)) {
      assertTrue(result.available());
      assertEquals(0, store.globalReads);
    }
  }

  @Test
  public void trustStoreFromOnboardingIsNotAClientIdentity() {
    Store store = new Store();
    store.server = new AtakServerImport.SavedP12(TransferCertificates.fixture("root"), "test-only");
    try (AtakServerImport.CertificateResult result =
        AtakServerImport.clientCertificate(
            c("tak.example", 8089, true, true, false, true), store)) {
      assertFalse(result.available());
      assertEquals(0, store.globalReads);
    }
  }

  @Test
  public void blankAndWhitespaceFilePasswordsAreRewrappedWithoutChangingIdentity()
      throws Exception {
    for (String filePassword : new String[] {"", " test-only "}) {
      java.security.KeyStore original = java.security.KeyStore.getInstance("PKCS12");
      original.load(new java.io.ByteArrayInputStream(client()), "test-only".toCharArray());
      String alias = original.aliases().nextElement();
      java.security.Key key = original.getKey(alias, "test-only".toCharArray());
      java.security.cert.Certificate[] chain = original.getCertificateChain(alias);
      java.security.KeyStore input = java.security.KeyStore.getInstance("PKCS12");
      input.load(null, null);
      input.setKeyEntry("client", key, filePassword.toCharArray(), chain);
      java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
      input.store(bytes, filePassword.toCharArray());
      Store store = new Store();
      store.server = new AtakServerImport.SavedP12(bytes.toByteArray(), filePassword);
      try (AtakServerImport.CertificateResult result =
          AtakServerImport.clientCertificate(normal(), store)) {
        assertTrue(result.available());
        assertFalse(result.password.isEmpty());
        assertEquals(result.password, TakServerItem.normalizeP12Password(result.password));
        assertNotEquals(filePassword, result.password);
        java.security.KeyStore output = java.security.KeyStore.getInstance("PKCS12");
        output.load(new java.io.ByteArrayInputStream(result.bytes), result.password.toCharArray());
        assertArrayEquals(
            key.getEncoded(), output.getKey("client", result.password.toCharArray()).getEncoded());
        assertArrayEquals(chain, output.getCertificateChain("client"));
        assertArrayEquals(bytes.toByteArray(), store.server.bytes);
      }
    }
  }

  @Test
  public void enrollmentUsesOnlyExplicitlySelectedHostAndNeverSetsCertificateMode()
      throws Exception {
    TakServerItem draft = AtakServerImport.draft(normal());
    List<String> reads = new ArrayList<>();
    AtakServerImport.importEnrollment(
        c("tak.example", 8089, true, true, false, true),
        draft,
        host -> {
          reads.add(host);
          return new AtakServerImport.SavedLogin("watch-user", "watch-pass");
        });
    assertEquals(Collections.singletonList("tak.example"), reads);
    assertFalse(draft.isP12Cert);
    assertEquals("", draft.p12Cert);
    assertEquals("", draft.p12CertPassword);
    JSONObject server =
        SettingsPayloadBuilder.build(
                Collections.singletonList(draft), null, "WATCH", null, null, false)
            .getJSONObject("payload")
            .getJSONArray("takServerList")
            .getJSONObject(0);
    assertEquals("watch-user", server.getString("username"));
    assertEquals("watch-pass", server.getString("password"));
    assertFalse(server.has("importedEnrollment"));
  }

  @Test
  public void unavailableEnrollmentLoginRequiresUserInputBeforeSending() throws Exception {
    for (AtakServerImport.SavedLogin login :
        Arrays.asList(
            null,
            new AtakServerImport.SavedLogin("user", ""),
            new AtakServerImport.SavedLogin("", "password"))) {
      TakServerItem draft = AtakServerImport.draft(normal());
      String message =
          AtakServerImport.importEnrollment(
              c("tak", 8089, true, true, false, true), draft, host -> login);
      assertEquals(login == null ? "" : login.username, draft.username);
      assertEquals(login == null ? "" : login.password, draft.password);
      assertFalse(draft.isP12Cert);
      assertFalse(message.contains(" password "));
      assertThrows(
          IllegalArgumentException.class,
          () ->
              SettingsPayloadBuilder.build(
                  Collections.singletonList(draft), null, "WATCH", null, null, false));
      draft.username = "entered-user";
      draft.password = "entered-password";
      SettingsPayloadBuilder.build(
          Collections.singletonList(draft), null, "WATCH", null, null, false);
    }
  }

  @Test
  public void softCertificateConnectionNeverAssumesThereIsAnEnrollmentLogin() {
    TakServerItem draft = AtakServerImport.draft(normal());
    AtakServerImport.importEnrollment(
        normal(),
        draft,
        host -> {
          fail("Must not read unrelated credentials");
          return null;
        });
    assertTrue(draft.isP12Cert);
  }

  @Test
  public void certificateAndLoginBothSurviveImportSnapshotAndSettingsUpload() throws Exception {
    TakServerItem draft = AtakServerImport.draft(normal());
    try (AtakServerImport.CertificateResult result =
        AtakServerImport.clientCertificate(normal(), scoped())) {
      draft.p12Cert = Base64.getEncoder().encodeToString(result.bytes);
      draft.p12CertPassword = result.password;
    }
    List<TakServerItem> servers = new ArrayList<>();
    AtakServerImport.Session session = new AtakServerImport.Session();
    long ticket = session.begin(servers);
    session.apply(
        ticket,
        servers,
        true,
        normal(),
        draft,
        new AtakServerImport.SavedLogin("service-user", " exact password "));
    TakServerItem snapshot = TakServerItem.fromJson(servers.get(0).toJson());
    JSONObject sent =
        SettingsPayloadBuilder.build(
                Collections.singletonList(snapshot), null, "WATCH", null, null, false)
            .getJSONObject("payload")
            .getJSONArray("takServerList")
            .getJSONObject(0);
    assertTrue(sent.getBoolean("isP12Cert"));
    assertEquals("service-user", sent.getString("username"));
    assertEquals(" exact password ", sent.getString("password"));
    assertEquals("test-only", sent.getString("p12CertPassword"));
    assertArrayEquals(client(), Base64.getDecoder().decode(sent.getString("p12Cert")));
  }

  @Test
  public void enrollmentPasswordWhitespaceIsPreservedExactly() throws Exception {
    TakServerItem draft = AtakServerImport.draft(normal());
    AtakServerImport.importEnrollment(
        c("tak", 8089, true, true, false, true),
        draft,
        host -> new AtakServerImport.SavedLogin("user", " exact password "));
    JSONObject sent =
        SettingsPayloadBuilder.build(
                Collections.singletonList(draft), null, "WATCH", null, null, false)
            .getJSONObject("payload")
            .getJSONArray("takServerList")
            .getJSONObject(0);
    assertFalse(sent.getBoolean("isP12Cert"));
    assertEquals(" exact password ", sent.getString("password"));
  }

  @Test
  public void reimportUpdatesAvailableLoginAndPreservesWatchCertificate() {
    TakServerItem existing = AtakServerImport.draft(normal());
    existing.p12Cert = "watch-specific";
    existing.p12CertPassword = "watch-file-password";
    existing.username = "old";
    existing.password = "old-password";
    List<TakServerItem> list = new ArrayList<>(Collections.singletonList(existing));
    AtakServerImport.Session session = new AtakServerImport.Session();
    long ticket = session.begin(list);
    assertEquals(
        0,
        session.apply(
            ticket,
            list,
            true,
            normal(),
            AtakServerImport.draft(normal()),
            new AtakServerImport.SavedLogin("new", " new-password ")));
    assertEquals(1, list.size());
    assertEquals("watch-specific", existing.p12Cert);
    assertEquals("watch-file-password", existing.p12CertPassword);
    assertEquals("new", existing.username);
    assertEquals(" new-password ", existing.password);
  }

  @Test
  public void absentAtakLoginDoesNotEraseAnExistingWatchLogin() {
    TakServerItem item = AtakServerImport.draft(normal());
    item.username = "keep";
    item.password = " keep ";
    for (AtakServerImport.SavedLogin login :
        Arrays.asList(null, new AtakServerImport.SavedLogin("", ""))) {
      AtakServerImport.copySavedLogin(item, login);
      assertEquals("keep", item.username);
      assertEquals(" keep ", item.password);
    }
  }

  @Test
  public void staleImportCannotWriteCredentialsIntoAnotherWatch() {
    AtakServerImport.Session session = new AtakServerImport.Session();
    List<TakServerItem> original = new ArrayList<>(), other = new ArrayList<>();
    long ticket = session.begin(original);
    TakServerItem item = AtakServerImport.draft(normal());
    item.username = "other-watch";
    other.add(item);
    session.cancel();
    session.begin(other);
    assertEquals(
        -1,
        session.apply(
            ticket,
            other,
            true,
            normal(),
            AtakServerImport.draft(normal()),
            new AtakServerImport.SavedLogin("wrong-watch", "wrong-password")));
    assertEquals("other-watch", item.username);
    assertEquals("", item.password);
  }

  @Test
  public void runtimeCredentialsWinAndCachedCredentialsFillOnlyMatchingAccounts() {
    AtakServerImport.SavedLogin cached = new AtakServerImport.SavedLogin("user", "cached-password");
    AtakServerImport.SavedLogin result =
        AtakServerImport.resolveLogin(
            new AtakServerImport.SavedLogin("user", " live password "), cached);
    assertEquals("user", result.username);
    assertEquals(" live password ", result.password);
    result = AtakServerImport.resolveLogin(new AtakServerImport.SavedLogin("user", ""), cached);
    assertEquals("cached-password", result.password);
    result =
        AtakServerImport.resolveLogin(
            new AtakServerImport.SavedLogin("different-user", ""), cached);
    assertEquals("different-user", result.username);
    assertEquals("", result.password);
    assertSame(cached, AtakServerImport.resolveLogin(null, cached));
  }
}
