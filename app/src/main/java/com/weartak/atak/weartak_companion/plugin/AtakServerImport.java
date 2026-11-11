package com.weartak.atak.weartak_companion.plugin;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.util.*;

/** Pure import policy and draft/session boundary; never writes ATAK settings. */
final class AtakServerImport {
  static final int MAX_P12_BYTES = 256 * 1024;

  static final class Connection {
    final String id, name, host, protocol;
    final int port;
    final boolean connected, enabled, streamAuth, enrollment;

    Connection(
        String id,
        String name,
        String host,
        int port,
        String protocol,
        boolean connected,
        boolean enabled,
        boolean streamAuth,
        boolean enrollment) {
      this.id = id;
      this.name = name == null || name.trim().isEmpty() ? host : name.trim();
      this.host = host;
      this.port = port;
      this.protocol = protocol;
      this.connected = connected;
      this.enabled = enabled;
      this.streamAuth = streamAuth;
      this.enrollment = enrollment;
    }

    boolean supported() {
      return "ssl".equalsIgnoreCase(protocol)
          && host != null
          && !host.trim().isEmpty()
          && port > 0
          && port <= 65535;
    }
  }

  static List<Connection> ordered(List<Connection> connections) {
    List<Connection> copy = new ArrayList<>(connections);
    copy.sort(
        Comparator.comparing((Connection c) -> !c.connected)
            .thenComparing(c -> !c.enabled)
            .thenComparing(c -> c.name, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(c -> c.id));
    return copy;
  }

  static TakServerItem draft(Connection connection) {
    if (!connection.supported()) throw new IllegalArgumentException("Unsupported watch transport");
    TakServerItem item = new TakServerItem();
    item.name = connection.name;
    item.address = connection.host;
    item.port = connection.port;
    item.isEnabled = connection.enabled;
    item.isP12Cert = true;
    item.username = item.password = item.p12Cert = item.p12CertPassword = "";
    return item; // Never reuse an ATAK connect string as a watch-owned serverId.
  }

  static int existing(List<TakServerItem> items, Connection connection) {
    for (int i = 0; i < items.size(); i++)
      if (items.get(i).port == connection.port
          && host(items.get(i).address).equals(host(connection.host))) return i;
    return -1;
  }

  private static String host(String input) {
    String s = input == null ? "" : input.trim().toLowerCase(Locale.ROOT);
    return s.startsWith("[") && s.endsWith("]") ? s.substring(1, s.length() - 1) : s;
  }

  /** A read returns a certificate and its matching password scope together. */
  interface CertificateAccess {
    SavedP12 forServer(String host, int port);

    SavedP12 defaults();
  }

  static final class SavedP12 {
    final byte[] bytes;
    final String password;

    SavedP12(byte[] bytes, String password) {
      this.bytes = bytes;
      this.password = password;
    }
  }

  static final class CertificateResult implements AutoCloseable {
    final byte[] bytes;
    final String password, message;

    CertificateResult(byte[] bytes, String password, String message) {
      this.bytes = bytes;
      this.password = password;
      this.message = message;
    }

    boolean available() {
      return bytes != null;
    }

    @Override
    public void close() {
      if (bytes != null) Arrays.fill(bytes, (byte) 0);
    }
  }

  private static CertificateResult unavailable(String message) {
    return new CertificateResult(null, null, message);
  }

  static CertificateResult clientCertificate(Connection c, CertificateAccess access) {
    if (!c.supported())
      return unavailable("This watch profile cannot represent the ATAK transport.");
    if (c.streamAuth)
      return unavailable(
          "This connection also uses stream authentication. Configure watch authentication"
              + " manually.");
    SavedP12 material = access.forServer(c.host, c.port);
    boolean scoped = material != null && material.bytes != null;
    if (!scoped && !c.enrollment) material = access.defaults();
    if (material == null || material.bytes == null)
      return unavailable(
          "No exportable ATAK client P12 was found. Choose a P12 file before sending.");
    if (material.password == null)
      return unavailable(
          "The saved P12 password is unavailable. Choose a P12 file before sending.");
    if (material.bytes.length > MAX_P12_BYTES)
      return unavailable("The saved P12 is too large for import. Choose a smaller watch P12 file.");
    byte[] copy = material.bytes.clone();
    if (!usableClientP12(copy, material.password)) {
      Arrays.fill(copy, (byte) 0);
      return unavailable(
          "The saved P12 could not be opened with an exportable client key. Choose a P12 file"
              + " before sending.");
    }
    String password = material.password;
    if (!password.equals(TakServerItem.normalizeP12Password(password))) {
      // The legacy watch editor substitutes a default for blank passwords and trims whitespace.
      // Rewrap the same identity with a fresh file password rather than silently changing it.
      try {
        password = UUID.randomUUID().toString();
        byte[] repacked = repackageP12(copy, material.password, password);
        Arrays.fill(copy, (byte) 0);
        copy = repacked;
        if (copy.length > MAX_P12_BYTES) {
          Arrays.fill(copy, (byte) 0);
          return unavailable("The exported client P12 is too large for this watch profile.");
        }
      } catch (Exception ignored) {
        Arrays.fill(copy, (byte) 0);
        return unavailable(
            "The client P12 could not be prepared for the watch. Choose a P12 file before"
                + " sending.");
      }
    }
    return new CertificateResult(
        copy,
        password,
        "Issued ATAK client P12 imported. The watch shares this certificate identity; renewal is"
            + " not transferred. Verify server trust on the watch.");
  }

  private static byte[] repackageP12(byte[] bytes, String oldPassword, String newPassword)
      throws Exception {
    char[] oldChars = oldPassword.toCharArray(), newChars = newPassword.toCharArray();
    try {
      KeyStore input = KeyStore.getInstance("PKCS12"), output = KeyStore.getInstance("PKCS12");
      input.load(new ByteArrayInputStream(bytes), oldChars);
      output.load(null, newChars);
      Enumeration<String> aliases = input.aliases();
      while (aliases.hasMoreElements()) {
        String alias = aliases.nextElement();
        if (input.isKeyEntry(alias))
          output.setKeyEntry(
              alias, input.getKey(alias, oldChars), newChars, input.getCertificateChain(alias));
        else if (input.isCertificateEntry(alias))
          output.setCertificateEntry(alias, input.getCertificate(alias));
      }
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      output.store(out, newChars);
      return out.toByteArray();
    } finally {
      Arrays.fill(oldChars, '\0');
      Arrays.fill(newChars, '\0');
    }
  }

  interface EnrollmentAccess {
    SavedLogin forServer(String host);
  }

  static final class SavedLogin {
    final String username, password;

    SavedLogin(String username, String password) {
      this.username = username;
      this.password = password;
    }
  }

  private static boolean present(String value) {
    return value != null && !value.isEmpty();
  }

  static SavedLogin resolveLogin(SavedLogin live, SavedLogin cached) {
    if (live == null || (!present(live.username) && !present(live.password))) return cached;
    if (cached == null) return live;
    // Never combine one account's live username with another account's cached password.
    if (present(live.username)
        && present(cached.username)
        && !live.username.equals(cached.username)) return live;
    return new SavedLogin(
        present(live.username) ? live.username : cached.username,
        present(live.password) ? live.password : cached.password);
  }

  static boolean hasLogin(SavedLogin login) {
    return login != null && (present(login.username) || present(login.password));
  }

  static void copySavedLogin(TakServerItem draft, SavedLogin login) {
    if (!hasLogin(login)) return;
    draft.username = login.username == null ? "" : login.username;
    draft.password = login.password == null ? "" : login.password;
  }

  static String importEnrollment(
      Connection connection, TakServerItem draft, EnrollmentAccess access) {
    if (!connection.supported() || !connection.enrollment)
      return "This ATAK connection is not configured for certificate enrollment. Choose a client"
          + " P12 or configure watch enrollment manually.";
    // Enrollment credentials are optional and may have been discarded or consumed.
    SavedLogin login = access.forServer(connection.host);
    draft.isP12Cert = false;
    draft.importedEnrollment = true;
    draft.p12Cert = draft.p12CertPassword = "";
    copySavedLogin(draft, login);
    if (login == null
        || login.username == null
        || login.password == null
        || login.username.trim().isEmpty()
        || login.password.isEmpty())
      return "ATAK has no complete saved enrollment login. Enter watch enrollment credentials"
          + " before sending.";
    return "Saved enrollment login copied. The watch must obtain its own certificate on HTTPS port"
        + " 8446; a one-use login may no longer work. Private CA trust must be configured on"
        + " the watch.";
  }

  static boolean usableClientP12(byte[] bytes, String password) {
    if (bytes == null || bytes.length == 0 || bytes.length > MAX_P12_BYTES || password == null)
      return false;
    char[] chars = password.toCharArray();
    try {
      KeyStore store = KeyStore.getInstance("PKCS12");
      store.load(new ByteArrayInputStream(bytes), chars);
      Enumeration<String> aliases = store.aliases();
      while (aliases.hasMoreElements()) {
        String alias = aliases.nextElement();
        if (store.isKeyEntry(alias)
            && store.getKey(alias, chars) instanceof PrivateKey
            && store.getCertificateChain(alias) != null
            && store.getCertificateChain(alias).length > 0) return true;
      }
    } catch (Exception ignored) {
      /* Never expose crypto exceptions, key aliases or certificate details. */
    } finally {
      Arrays.fill(chars, '\0');
    }
    return false;
  }

  /** The production async callbacks and tests use this same ownership fence. */
  static final class Session {
    private volatile long generation;
    private List<TakServerItem> owner;
    private boolean busy;

    long begin(List<TakServerItem> list) {
      owner = list;
      busy = true;
      return ++generation;
    }

    boolean current(long ticket) {
      return generation == ticket;
    }

    boolean owns(long ticket, List<TakServerItem> list) {
      return current(ticket) && owner == list;
    }

    boolean busy() {
      return busy;
    }

    void busy(long ticket, boolean value) {
      if (current(ticket)) busy = value;
    }

    void cancel() {
      ++generation;
      busy = false;
      owner = null;
    }

    int apply(
        long ticket,
        List<TakServerItem> list,
        boolean ready,
        Connection connection,
        TakServerItem draft) {
      return apply(ticket, list, ready, connection, draft, null);
    }

    int apply(
        long ticket,
        List<TakServerItem> list,
        boolean ready,
        Connection connection,
        TakServerItem draft,
        SavedLogin login) {
      if (!current(ticket)) return -1;
      busy = false;
      if (!ready || owner != list) return -1;
      int match = existing(list, connection);
      if (match >= 0) {
        copySavedLogin(list.get(match), login);
        return match;
      }
      copySavedLogin(draft, login);
      list.add(draft);
      return list.size() - 1;
    }
  }

  private AtakServerImport() {}
}
