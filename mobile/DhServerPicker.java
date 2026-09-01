package com.perblue.dhlauncher;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PATCH APK (brique 4c) — ÉCRAN DE SÉLECTION DE SERVEUR affiché AU LANCEMENT du jeu mobile. Liste les serveurs
 * communautaires (annuaire Supabase, lecture publique), plus une saisie manuelle host:port. Au choix : enregistre
 * l'adresse dans les préférences ({@code dhserver}) puis démarre l'Activity du JEU ({@code AndroidLauncher}), qui lit
 * ce choix au boot et redirige {@code ServerType.LIVE} vers le serveur retenu.
 *
 * <p>UI 100% programmatique (aucune ressource XML → aucun aapt requis pour ce code). Compilée séparément (javac +
 * android.jar) puis dexée (d8) et injectée dans l'APK ; le manifeste fait de cette Activity le LAUNCHER (cf.
 * {@code tools/apk_inject_picker.sh}). {@code DIRECTORY_URL}/{@code ANON_KEY} sont injectés au patch (valeurs PUBLIQUES).
 */
public final class DhServerPicker extends Activity {
    // Remplacés au patch (tools) par l'URL + la clé anon PUBLIQUE de l'annuaire ; vides = annuaire désactivé.
    static final String DIRECTORY_URL = "__DH_DIRECTORY_URL__";
    static final String ANON_KEY = "__DH_DIRECTORY_ANON_KEY__";
    static final String PREFS = "dhserver";
    static final String GAME_ACTIVITY = "com.perblue.heroes.android.AndroidLauncher";

    private LinearLayout list;
    private TextView status;
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(Color.parseColor("#0f1420"));
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("Choisir un serveur");
        title.setTextColor(Color.WHITE);
        title.setTextSize(22);
        root.addView(title);

        status = new TextView(this);
        status.setTextColor(Color.parseColor("#9fb0c8"));
        status.setText("Chargement de l'annuaire…");
        root.addView(status);

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        root.addView(list);

        // Saisie manuelle (serveur privé hors annuaire)
        TextView manual = new TextView(this);
        manual.setText("\nOu adresse manuelle (host:port)");
        manual.setTextColor(Color.parseColor("#9fb0c8"));
        root.addView(manual);
        final EditText addr = new EditText(this);
        addr.setHint("192.168.1.20:8080");
        addr.setTextColor(Color.WHITE);
        root.addView(addr);
        Button go = new Button(this);
        go.setText("Jouer sur cette adresse");
        go.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                String s = addr.getText().toString().trim();
                int c = s.lastIndexOf(':');
                if (c <= 0) { status.setText("Adresse invalide (attendu host:port)."); return; }
                try { play(s.substring(0, c), Integer.parseInt(s.substring(c + 1))); }
                catch (Exception e) { status.setText("Port invalide."); }
            }
        });
        root.addView(go);

        setContentView(scroll);
        loadDirectory();
    }

    /** Enregistre le serveur choisi puis démarre le JEU. Le hook de boot lira {@code dhserver} et redirigera ServerType. */
    private void play(String host, int port) {
        SharedPreferences.Editor e = getSharedPreferences(PREFS, MODE_PRIVATE).edit();
        e.putString("host", host); e.putInt("port", port); e.commit();
        try {
            Intent i = new Intent();
            i.setClassName(getPackageName(), GAME_ACTIVITY);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(i);
            finish();
        } catch (Exception ex) { status.setText("Impossible de démarrer le jeu : " + ex); }
    }

    private void addServerButton(final String name, final String mode, final String host, final int port) {
        Button btn = new Button(this);
        btn.setText(name + "   [" + mode + "]   " + host + ":" + port);
        btn.setAllCaps(false);
        btn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { play(host, port); }
        });
        list.addView(btn);
    }

    /** Lecture publique de la table {@code servers} (clé anon). Parsing minimal (nom + adresse). En arrière-plan. */
    private void loadDirectory() {
        if (DIRECTORY_URL.startsWith("__") || DIRECTORY_URL.isEmpty()) {
            status.setText("Annuaire non configuré — saisis une adresse ci-dessous.");
            return;
        }
        new Thread(new Runnable() {
            public void run() {
                try {
                    String url = DIRECTORY_URL.replaceAll("/$", "")
                        + "/rest/v1/servers?select=name,mode,address&order=updated_at.desc";
                    HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                    c.setConnectTimeout(8000); c.setReadTimeout(8000);
                    c.setRequestProperty("apikey", ANON_KEY);
                    c.setRequestProperty("Authorization", "Bearer " + ANON_KEY);
                    StringBuilder sb = new StringBuilder();
                    BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
                    for (String ln; (ln = r.readLine()) != null; ) sb.append(ln);
                    r.close();
                    final String body = sb.toString();
                    ui.post(new Runnable() { public void run() { render(body); } });
                } catch (final Exception e) {
                    ui.post(new Runnable() { public void run() { status.setText("Annuaire injoignable — saisis une adresse."); } });
                }
            }
        }).start();
    }

    private void render(String json) {
        Pattern p = Pattern.compile("\\{[^}]*\\}");
        Matcher m = p.matcher(json);
        int n = 0;
        while (m.find()) {
            String o = m.group();
            String name = field(o, "name"), mode = field(o, "mode"), address = field(o, "address");
            if (address == null) continue;
            int c = address.lastIndexOf(':');
            if (c <= 0) continue;
            try {
                addServerButton(name == null ? address : name, mode == null ? "?" : mode,
                        address.substring(0, c), Integer.parseInt(address.substring(c + 1)));
                n++;
            } catch (NumberFormatException ignore) { }
        }
        status.setText(n == 0 ? "Aucun serveur dans l'annuaire — saisis une adresse." : n + " serveur(s) disponible(s)");
    }

    private static String field(String obj, String key) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"").matcher(obj);
        return m.find() ? m.group(1) : null;
    }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }
}
