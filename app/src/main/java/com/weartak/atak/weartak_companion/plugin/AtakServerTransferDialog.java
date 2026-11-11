package com.weartak.atak.weartak_companion.plugin;

import android.app.AlertDialog;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.view.WindowManager;
import android.widget.*;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/** A complete guided transfer. It neither opens nor reads the Custom server editor. */
final class AtakServerTransferDialog implements AutoCloseable {
  interface Source {
    boolean hasCertificate(AtakServerImport.Connection c);

    AtakServerImport.SavedLogin login(AtakServerImport.Connection c);

    AtakServerTransfer.SoftMaterial certificates(AtakServerImport.Connection c);
  }

  interface Host {
    boolean current();

    void dialog(AlertDialog dialog);

    void chooseFile(Consumer<byte[]> callback);

    boolean send(TakServerItem server, AtakServerTransfer.Identity identity) throws Exception;

    void closed();
  }

  private final Context context, resources;
  private final Executor worker;
  private final Source source;
  private final Host host;
  private final Handler main = new Handler(Looper.getMainLooper());
  private boolean active = true, loading;
  private long revision;
  private AlertDialog shown;
  private AtakServerImport.Connection connection;
  private AtakServerTransfer.Mode mode;
  private AtakServerTransfer.Mode supportedMode;
  private AtakServerTransfer.SoftMaterial soft;
  private String username = "", password = "";
  private TakServerItem prepared;
  private String trustNotice;
  private boolean editIdentity;
  private String callsign, team, role;

  AtakServerTransferDialog(
      Context context,
      Context resources,
      Executor worker,
      Source source,
      Host host,
      String callsign,
      String team,
      String role) {
    this.context = context;
    this.resources = resources;
    this.worker = worker;
    this.source = source;
    this.host = host;
    this.callsign = callsign;
    this.team = team;
    this.role = role;
  }

  boolean current() {
    return active && host.current();
  }

  void start(List<AtakServerImport.Connection> connections) {
    AlertDialog select =
        AtakServerImportDialog.create(
            context,
            resources,
            connections,
            c -> {
              if (!current()) return;
              connection = c;
              detectMode();
            });
    show(select);
  }

  private void detectMode() {
    async(
        () ->
            AtakServerTransfer.detected(
                connection, connection.enrollment ? false : source.hasCertificate(connection)),
        detected -> {
          supportedMode = detected;
          chooseMode(detected);
        });
  }

  private boolean modeAvailable(AtakServerTransfer.Mode candidate) {
    return candidate != null
        && (candidate == AtakServerTransfer.Mode.SERVER_ONLY || candidate == supportedMode);
  }

  private void chooseMode(AtakServerTransfer.Mode preferred) {
    if (!current()) return;
    if (soft != null) {
      soft.close();
      soft = null;
    }
    mode = modeAvailable(preferred) ? preferred : AtakServerTransfer.Mode.SERVER_ONLY;
    String[] labels = {
      "Auto-enroll — server and login"
          + (modeAvailable(AtakServerTransfer.Mode.AUTO_ENROLL)
              ? ""
              : "\nUnavailable: ATAK has no enrollment setting for this connection."),
      "Soft certificates — certificate files"
          + (modeAvailable(AtakServerTransfer.Mode.SOFT_CERTIFICATES)
              ? ""
              : connection.enrollment
                  ? "\nUnavailable: this ATAK connection uses auto-enrollment."
                  : "\nUnavailable: ATAK has no stored client certificate for this connection."),
      "Server details only — no authentication"
    };
    ArrayAdapter<String> choices =
        new ArrayAdapter<String>(context, android.R.layout.select_dialog_singlechoice, labels) {
          @Override
          public boolean areAllItemsEnabled() {
            return false;
          }

          @Override
          public boolean isEnabled(int position) {
            return position >= 0
                && position < AtakServerTransfer.Mode.values().length
                && modeAvailable(AtakServerTransfer.Mode.values()[position]);
          }

          @Override
          public View getView(int position, View recycled, android.view.ViewGroup parent) {
            View view = super.getView(position, recycled, parent);
            view.setEnabled(isEnabled(position));
            view.setAlpha(isEnabled(position) ? 1f : 0.45f);
            return view;
          }
        };
    AlertDialog dialog =
        new AlertDialog.Builder(context)
            .setTitle("What to transfer")
            .setSingleChoiceItems(
                choices,
                mode.ordinal(),
                (d, index) -> {
                  if (loading || !choices.isEnabled(index)) {
                    ((AlertDialog) d).getListView().clearChoices();
                    ((AlertDialog) d).getListView().setItemChecked(mode.ordinal(), true);
                    return;
                  }
                  mode = AtakServerTransfer.Mode.values()[index];
                  ((AlertDialog) d).getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                })
            .setNegativeButton("Cancel", (d, w) -> close())
            .setPositiveButton("Continue", null)
            .create();
    TextView info =
        text(
            connection.name
                + "\n"
                + connection.host
                + ":"
                + connection.port
                + "\n\n"
                + (supportedMode == null
                    ? "ATAK has not exposed enough authentication data to offer either mode. Copy"
                        + " server details only, or use the separate Custom editor."
                    : supportedMode == AtakServerTransfer.Mode.AUTO_ENROLL
                        ? "ATAK uses auto-enrollment. Saved login will be reused; missing login"
                            + " fields will be requested."
                        : "ATAK has a stored client certificate. Its file password may still be"
                            + " requested."));
    dialog.getListView().addFooterView(info, null, false);
    dialog.setOnShowListener(
        d -> {
          dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(mode != null);
          dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> loadMode());
        });
    show(dialog);
  }

  private void loadMode() {
    if (!current()) return;
    if (!modeAvailable(mode)) {
      chooseMode(null);
      return;
    }
    prepared = null;
    trustNotice = null;
    username = password = "";
    if (soft != null) {
      soft.close();
      soft = null;
    }
    if (mode == AtakServerTransfer.Mode.AUTO_ENROLL) {
      async(
          () -> source.login(connection),
          login -> {
            username = login == null || login.username == null ? "" : login.username;
            password = login == null || login.password == null ? "" : login.password;
            enrollment();
          });
    } else if (mode == AtakServerTransfer.Mode.SOFT_CERTIFICATES) {
      async(
          () -> source.certificates(connection),
          material -> {
            soft = material;
            checkCertificates();
          });
    } else {
      prepared = AtakServerTransfer.server(connection, mode, null, null, null, null);
      review();
    }
  }

  private void enrollment() {
    LinearLayout form = form();
    form.addView(
        text(
            connection.name
                + "\n"
                + connection.host
                + ":"
                + connection.port
                + "\n"
                + "The watch enrolls itself using HTTPS port 8446. It uses its existing server"
                + " trust."));
    form.addView(
        text(
            username.isEmpty() || password.isEmpty()
                ? "ATAK did not retain the full login. Enter the missing information."
                : "Login retrieved from ATAK. Review it before continuing."));
    EditText user = field(form, "Enrollment username", username, false);
    EditText pass = field(form, "Enrollment password", password, true);
    TextView error = text("");
    form.addView(error);
    page(
        "Auto-enroll",
        form,
        "Continue",
        () -> {
          try {
            username = user.getText().toString();
            password = pass.getText().toString();
            prepared = AtakServerTransfer.server(connection, mode, username, password, null, null);
            review();
          } catch (IllegalArgumentException e) {
            error.setText(e.getMessage());
          }
        },
        () -> chooseMode(mode));
  }

  private void checkCertificates() {
    if (soft == null) soft = new AtakServerTransfer.SoftMaterial(null, null);
    AtakServerTransfer.SoftMaterial copy =
        new AtakServerTransfer.SoftMaterial(
            new AtakServerImport.SavedP12(soft.client, soft.clientPassword),
            soft.ca == null ? null : new AtakServerImport.SavedP12(soft.ca, soft.caPassword));
    async(
        () -> {
          try {
            return AtakServerTransfer.softCertificates(copy);
          } finally {
            copy.close();
          }
        },
        result -> {
          try {
            if (result.ready()) {
              trustNotice = result.trustNotice;
              prepared =
                  AtakServerTransfer.server(
                      connection,
                      mode,
                      null,
                      null,
                      android.util.Base64.encodeToString(result.bytes, android.util.Base64.NO_WRAP),
                      result.password);
              review();
            } else softInputs(result);
          } finally {
            result.close();
          }
        });
  }

  private void softInputs(AtakServerTransfer.SoftResult result) {
    LinearLayout form = form();
    form.addView(text(result.problem));
    EditText clientPassword =
        result.clientPasswordNeeded
            ? field(form, "Client certificate password", soft.clientPassword, true)
            : null;
    EditText caPassword =
        result.caPasswordNeeded
            ? field(form, "CA trust-store password", soft.caPassword, true)
            : null;
    Button client = new Button(context);
    client.setText(
        soft.client == null ? "Select client certificate" : "Replace client certificate");
    Runnable keepPasswords =
        () -> {
          if (clientPassword != null) soft.clientPassword = clientPassword.getText().toString();
          if (caPassword != null) soft.caPassword = caPassword.getText().toString();
        };
    form.addView(client);
    client.setOnClickListener(
        v -> {
          keepPasswords.run();
          chooseCertificate(false);
        });
    Button ca = new Button(context);
    ca.setText(soft.ca == null ? "Add CA trust store (optional)" : "Replace CA trust store");
    form.addView(ca);
    ca.setOnClickListener(
        v -> {
          keepPasswords.run();
          chooseCertificate(true);
        });
    page(
        "Soft certificates",
        form,
        "Continue",
        () -> {
          keepPasswords.run();
          checkCertificates();
        },
        () -> chooseMode(mode));
  }

  private void chooseCertificate(boolean ca) {
    if (!current() || loading) return;
    AtakServerTransfer.SoftMaterial target = soft;
    long token = revision;
    host.chooseFile(
        bytes -> {
          if (!current() || token != revision || soft != target) {
            java.util.Arrays.fill(bytes, (byte) 0);
            return;
          }
          if (ca) {
            if (soft.ca != null) java.util.Arrays.fill(soft.ca, (byte) 0);
            soft.ca = bytes;
            soft.caPassword = null;
          } else {
            if (soft.client != null) java.util.Arrays.fill(soft.client, (byte) 0);
            soft.client = bytes;
            soft.clientPassword = null;
          }
          checkCertificates();
        });
  }

  private void review() {
    LinearLayout form = form();
    String summary =
        mode == AtakServerTransfer.Mode.AUTO_ENROLL
            ? "Auto-enroll\nUsername: " + prepared.username
            : mode == AtakServerTransfer.Mode.SOFT_CERTIFICATES
                ? "Soft certificates\nCopies ATAK’s client identity and CA chain."
                : "Server details only\n"
                    + "Saved disabled until configured on the watch. No authentication will be"
                    + " sent.";
    form.addView(
        text(
            connection.name
                + "\n"
                + connection.host
                + ":"
                + connection.port
                + "\n\n"
                + summary
                + "\n\n"
                + "A matching server on this watch will be updated. Other saved servers are"
                + " preserved."));
    if (trustNotice != null) form.addView(text(trustNotice));
    CheckBox edit = new CheckBox(context);
    edit.setText("Copy or edit ATAK callsign, team/color and role");
    edit.setChecked(editIdentity);
    form.addView(edit);
    LinearLayout identity = form();
    form.addView(identity);
    EditText cs = field(identity, "Watch callsign", callsign, false);
    EditText tm = field(identity, "Team / color", team, false);
    EditText rl = field(identity, "Role", role, false);
    identity.setVisibility(editIdentity ? View.VISIBLE : View.GONE);
    edit.setOnCheckedChangeListener(
        (b, on) -> {
          editIdentity = on;
          identity.setVisibility(on ? View.VISIBLE : View.GONE);
        });
    TextView error = text("");
    form.addView(error);
    page(
        "Review and send",
        form,
        "Send to Watch",
        () -> {
          if (!current()) {
            error.setText("The watch connection or settings changed. Reopen Import from ATAK.");
            return;
          }
          callsign = cs.getText().toString();
          team = tm.getText().toString();
          role = rl.getText().toString();
          try {
            AtakServerTransfer.Identity requested =
                edit.isChecked() ? new AtakServerTransfer.Identity(callsign, team, role) : null;
            if (host.send(prepared, requested)) close();
            else
              error.setText("Could not queue the transfer. Check the watch connection and retry.");
          } catch (Exception e) {
            error.setText(
                e instanceof IllegalArgumentException
                    ? e.getMessage()
                    : "Could not prepare the watch transfer. Refresh the watch settings and"
                        + " retry.");
          }
        },
        () -> {
          callsign = cs.getText().toString();
          team = tm.getText().toString();
          role = rl.getText().toString();
          if (mode == AtakServerTransfer.Mode.AUTO_ENROLL) enrollment();
          else chooseMode(mode);
        });
  }

  private void page(String title, LinearLayout form, String action, Runnable next, Runnable back) {
    AlertDialog dialog =
        new AlertDialog.Builder(context)
            .setTitle(title)
            .setItems(new String[0], null)
            .setNegativeButton(
                back == null ? "Cancel" : "Back",
                (d, w) -> {
                  if (back == null) close();
                  else back.run();
                })
            .setPositiveButton(action, null)
            .create();
    dialog.getListView().addHeaderView(form, null, false);
    dialog.getListView().setItemsCanFocus(true);
    dialog.setOnShowListener(
        d -> {
          dialog.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);
          dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
          dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> next.run());
        });
    show(dialog);
  }

  private void show(AlertDialog dialog) {
    if (!current()) return;
    ++revision;
    loading = false;
    AlertDialog old = shown;
    shown = dialog;
    if (old != null) old.dismiss();
    host.dialog(dialog);
    dialog.setOnDismissListener(
        d -> {
          if (shown == dialog) close();
        });
    dialog.show();
  }

  private <T> void async(Callable<T> task, Consumer<T> result) {
    if (!current() || loading) return;
    loading = true;
    long token = ++revision;
    if (shown != null) setInputsEnabled(shown.getListView(), false);
    if (shown != null && shown.getButton(AlertDialog.BUTTON_POSITIVE) != null)
      shown.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
    worker.execute(
        () -> {
          try {
            T value = task.call();
            main.post(
                () -> {
                  if (!current() || token != revision) {
                    dispose(value);
                    if (active && !host.current()) close();
                    return;
                  }
                  loading = false;
                  result.accept(value);
                });
          } catch (Exception ignored) {
            main.post(
                () -> {
                  if (!current() || token != revision) {
                    if (active && !host.current()) close();
                    return;
                  }
                  loading = false;
                  LinearLayout problem = form();
                  problem.addView(
                      text(
                          "ATAK could not supply this connection's saved state. Reconnect it in"
                              + " ATAK, then retry."));
                  page(
                      "Import unavailable",
                      problem,
                      "Retry",
                      () -> {
                        if (mode == null) detectMode();
                        else loadMode();
                      },
                      () -> chooseMode(mode));
                });
          }
        });
  }

  private static void setInputsEnabled(View view, boolean enabled) {
    view.setEnabled(enabled);
    if (view instanceof android.view.ViewGroup)
      for (int i = 0; i < ((android.view.ViewGroup) view).getChildCount(); i++)
        setInputsEnabled(((android.view.ViewGroup) view).getChildAt(i), enabled);
  }

  private LinearLayout form() {
    LinearLayout v = new LinearLayout(context);
    v.setOrientation(LinearLayout.VERTICAL);
    int p = Math.round(16 * context.getResources().getDisplayMetrics().density);
    v.setPadding(p, 0, p, p);
    return v;
  }

  private TextView text(String value) {
    TextView v = new TextView(context);
    v.setText(value);
    int p = Math.round(8 * context.getResources().getDisplayMetrics().density);
    v.setPadding(0, p, 0, p);
    return v;
  }

  private EditText field(LinearLayout parent, String label, String value, boolean secret) {
    parent.addView(text(label));
    EditText input = new EditText(context);
    input.setSingleLine(true);
    input.setInputType(
        InputType.TYPE_CLASS_TEXT
            | (secret
                ? InputType.TYPE_TEXT_VARIATION_PASSWORD
                : InputType.TYPE_TEXT_VARIATION_NORMAL));
    input.setText(value == null ? "" : value);
    parent.addView(input);
    return input;
  }

  private static void dispose(Object value) {
    if (value instanceof AutoCloseable)
      try {
        ((AutoCloseable) value).close();
      } catch (Exception ignored) {
      }
  }

  @Override
  public void close() {
    if (!active) return;
    active = false;
    ++revision;
    if (soft != null) soft.close();
    username = password = "";
    prepared = null;
    AlertDialog old = shown;
    shown = null;
    if (old != null) old.dismiss();
    host.closed();
  }
}
