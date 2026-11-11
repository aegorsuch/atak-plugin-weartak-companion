package com.weartak.atak.weartak_companion.plugin;

import android.util.Base64;
import com.atakmap.comms.NetConnectString;
import com.atakmap.comms.TAKServer;
import com.atakmap.comms.TAKServerListener;
import com.atakmap.net.AtakAuthenticationCredentials;
import com.atakmap.net.AtakAuthenticationDatabase;
import com.atakmap.net.AtakCertificateDatabase;
import com.atakmap.net.AtakCertificateDatabaseIFace;
import java.util.*;

/** Read-only adapter; no credential reads until the user explicitly imports. */
final class AtakServerSource {
  static List<AtakServerImport.Connection> connections() {
    TAKServerListener listener = TAKServerListener.getInstance();
    if (listener == null) throw new IllegalStateException("ATAK server connections are not ready.");
    List<AtakServerImport.Connection> out = new ArrayList<>();
    TAKServer[] servers = listener.getServers();
    if (servers != null)
      for (TAKServer server : servers) {
        if (server == null) continue;
        NetConnectString n = NetConnectString.fromString(server.getConnectString());
        if (n != null)
          out.add(
              new AtakServerImport.Connection(
                  server.getConnectString(),
                  server.getDescription(),
                  n.getHost(),
                  n.getPort(),
                  n.getProto(),
                  server.isConnected(),
                  server.isEnabled(),
                  server.isUsingAuth(),
                  server.enrollForCert()));
      }
    return AtakServerImport.ordered(out);
  }

  private static final AtakServerImport.CertificateAccess CERTIFICATES =
      new AtakServerImport.CertificateAccess() {
        @Override
        public AtakServerImport.SavedP12 forServer(String host, int port) {
          byte[] p12 =
              AtakCertificateDatabase.getCertificateForServerAndPort(
                  AtakCertificateDatabaseIFace.TYPE_CLIENT_CERTIFICATE, host, port);
          if (p12 == null) return null;
          AtakAuthenticationCredentials credentials =
              AtakAuthenticationDatabase.getCredentials(
                  AtakAuthenticationCredentials.TYPE_clientPassword, host);
          return new AtakServerImport.SavedP12(
              p12, credentials == null ? null : credentials.password);
        }

        @Override
        public AtakServerImport.SavedP12 defaults() {
          byte[] p12 =
              AtakCertificateDatabase.getCertificate(
                  AtakCertificateDatabaseIFace.TYPE_CLIENT_CERTIFICATE);
          AtakAuthenticationCredentials credentials =
              AtakAuthenticationDatabase.getCredentials(
                  AtakAuthenticationCredentials.TYPE_clientPassword);
          return new AtakServerImport.SavedP12(
              p12, credentials == null ? null : credentials.password);
        }
      };

  static String importClientP12(AtakServerImport.Connection c, TakServerItem draft) {
    try (AtakServerImport.CertificateResult result =
        AtakServerImport.clientCertificate(c, CERTIFICATES)) {
      if (result.available()) {
        draft.p12Cert = Base64.encodeToString(result.bytes, Base64.NO_WRAP);
        draft.p12CertPassword = result.password;
        draft.p12DisplayName = "ATAK client certificate";
      }
      return result.message;
    }
  }

  static boolean hasClientCertificate(AtakServerImport.Connection connection) {
    byte[] scoped =
        AtakCertificateDatabase.getCertificateForServerAndPort(
            AtakCertificateDatabaseIFace.TYPE_CLIENT_CERTIFICATE, connection.host, connection.port);
    if (scoped != null) return scoped.length > 0;
    if (connection.enrollment) return false;
    byte[] defaults =
        AtakCertificateDatabase.getCertificate(
            AtakCertificateDatabaseIFace.TYPE_CLIENT_CERTIFICATE);
    return defaults != null && defaults.length > 0;
  }

  static AtakServerTransfer.SoftMaterial readSoftCertificates(
      AtakServerImport.Connection connection) {
    return AtakServerTransfer.softMaterial(
        connection,
        new AtakServerTransfer.CertificateAccess() {
          public AtakServerImport.SavedP12 client(String host, int port) {
            return CERTIFICATES.forServer(host, port);
          }

          public AtakServerImport.SavedP12 defaultClient() {
            return CERTIFICATES.defaults();
          }

          public AtakServerImport.SavedP12 ca(String host, int port) {
            return caMaterial(host, port);
          }

          public AtakServerImport.SavedP12 defaultCa() {
            return caMaterial(null, 0);
          }
        });
  }

  private static AtakServerImport.SavedP12 caMaterial(String host, int port) {
    byte[] bytes =
        host == null
            ? AtakCertificateDatabase.getCertificate(
                AtakCertificateDatabaseIFace.TYPE_TRUST_STORE_CA)
            : AtakCertificateDatabase.getCertificateForServerAndPort(
                AtakCertificateDatabaseIFace.TYPE_TRUST_STORE_CA, host, port);
    if (bytes == null) return null;
    AtakAuthenticationCredentials password =
        host == null
            ? AtakAuthenticationDatabase.getCredentials(
                AtakAuthenticationCredentials.TYPE_caPassword)
            : AtakAuthenticationDatabase.getCredentials(
                AtakAuthenticationCredentials.TYPE_caPassword, host);
    return new AtakServerImport.SavedP12(bytes, password == null ? null : password.password);
  }

  static AtakServerImport.SavedLogin readLogin(AtakServerImport.Connection c) {
    AtakServerImport.SavedLogin live = null;
    try {
      TAKServerListener listener = TAKServerListener.getInstance();
      TAKServer[] servers = listener == null ? null : listener.getServers();
      if (servers != null)
        for (TAKServer server : servers) {
          if (server != null && c.id.equals(server.getConnectString())) {
            live = new AtakServerImport.SavedLogin(server.getUsername(), server.getPassword());
            break;
          }
        }
    } catch (RuntimeException ignored) {
      /* Still try this host's saved login. */
    }
    AtakServerImport.SavedLogin cached = null;
    try {
      AtakAuthenticationCredentials credentials =
          AtakAuthenticationDatabase.getCredentials(
              AtakAuthenticationCredentials.TYPE_COT_SERVICE, c.host);
      if (credentials != null)
        cached = new AtakServerImport.SavedLogin(credentials.username, credentials.password);
    } catch (RuntimeException ignored) {
      /* Keep any credentials available on the live connection. */
    }
    return AtakServerImport.resolveLogin(live, cached);
  }

  private AtakServerSource() {}
}
