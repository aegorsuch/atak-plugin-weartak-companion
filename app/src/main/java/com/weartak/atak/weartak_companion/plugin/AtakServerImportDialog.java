package com.weartak.atak.weartak_companion.plugin;

import android.app.AlertDialog;
import android.content.Context;
import android.widget.ListView;
import android.widget.TextView;
import java.util.List;

/** Select an ATAK connection before choosing which state to transfer. */
final class AtakServerImportDialog {
  interface Callback {
    void onImport(AtakServerImport.Connection connection);
  }

  static AlertDialog create(
      Context host,
      Context resources,
      List<AtakServerImport.Connection> connections,
      Callback callback) {
    CharSequence[] labels = new CharSequence[connections.size()];
    final int[] selected = {-1};
    for (int i = 0; i < connections.size(); i++) {
      AtakServerImport.Connection connection = connections.get(i);
      if (selected[0] < 0 && connection.supported()) selected[0] = i;
      String state =
          resources.getString(
              connection.connected
                  ? R.string.import_atak_connected
                  : connection.enabled
                      ? R.string.import_atak_disconnected
                      : R.string.import_atak_disabled);
      labels[i] =
          connection.name
              + "\n"
              + connection.host
              + ":"
              + connection.port
              + " ("
              + connection.protocol
              + ") · "
              + state
              + (connection.supported()
                  ? ""
                  : " · " + resources.getString(R.string.import_atak_unsupported));
    }
    AlertDialog dialog =
        new AlertDialog.Builder(host)
            .setTitle(resources.getString(R.string.import_atak_title))
            .setSingleChoiceItems(
                labels,
                selected[0],
                (d, which) -> {
                  if (which < 0 || which >= connections.size()) return;
                  selected[0] = which;
                  ((AlertDialog) d)
                      .getButton(AlertDialog.BUTTON_POSITIVE)
                      .setEnabled(connections.get(which).supported());
                })
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton("Continue", null)
            .create();
    // The explanation scrolls with the server list; native action buttons remain outside it.
    ListView body = dialog.getListView();
    TextView explanation = new TextView(host);
    explanation.setText(resources.getString(R.string.import_atak_explanation));
    int padding = Math.round(16 * host.getResources().getDisplayMetrics().density);
    explanation.setPadding(padding, padding, padding, padding);
    body.addFooterView(explanation, null, false);
    dialog.setOnShowListener(
        d -> {
          dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(selected[0] >= 0);
          dialog
              .getButton(AlertDialog.BUTTON_POSITIVE)
              .setOnClickListener(
                  v -> {
                    if (selected[0] >= 0 && connections.get(selected[0]).supported())
                      callback.onImport(connections.get(selected[0]));
                  });
        });
    return dialog;
  }

  private AtakServerImportDialog() {}
}
