package com.perblue.dhlauncher;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PATCH APK (brique 4c / V2) — ÉCRAN DE SÉLECTION DE SERVEUR au lancement du jeu mobile. Sections : FAVORIS (persistés),
 * SERVEURS COMMUNAUTAIRES (annuaire Supabase, lecture publique) et ADRESSE MANUELLE. Au choix : enregistre l'adresse dans
 * les préférences {@code dhserver} puis démarre {@code AndroidLauncher} (le jeu), qui lit ce choix au boot et redirige
 * {@code ServerType.LIVE}. UI 100% programmatique (aucune ressource XML ; anonymes plutôt que lambdas car l'{@code
 * android.jar} de compilation n'a pas {@code LambdaMetafactory}). {@code DIRECTORY_URL}/{@code ANON_KEY} injectés au patch
 * (PUBLIQUES). Paysage (comme le jeu). Identité/auth STRICT = incrément ultérieur (Ed25519 embarqué).
 */
public final class DhServerPicker extends Activity {
    static final String DIRECTORY_URL = "__DH_DIRECTORY_URL__";
    static final String ANON_KEY = "__DH_DIRECTORY_ANON_KEY__";
    static final String PREFS = "dhserver";
    static final String FAV_PREF = "dh_favorites";           // "name\thost\tport" par ligne
    static final String MNEMONIC_PREF = "mnemonic";          // phrase du compte (V3, serveurs stricts)
    static final String GAME_ACTIVITY = "com.perblue.heroes.android.AndroidLauncher";

    private static final int BG = 0xFF0E1420, CARD = 0xFF19222E, ACCENT = 0xFF2E6BE6, TXT = 0xFFEAF0F8, MUT = 0xFF9FB0C8, BTN2 = 0xFF26313F;
    private static final int WARN = 0xFFE6B22E, OKC = 0xFF3FB765;

    private LinearLayout accountBox, favBox, dirBox, dirList, tabServers, tabAccount;
    private TextView dirStatus;
    private Button tabServersBtn, tabAccountBtn, filAll, filStrict, filOpen;
    private EditText dirSearch;
    private int dirFilter = 0;                                  // 0=tous, 1=strict, 2=ouvert
    private final List<String[]> dirServers = new ArrayList<String[]>(); // {name,mode,gv,address,online,maxOnline,infoUrl}
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        scroll.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("Disney Heroes");
        title.setTextColor(TXT); title.setTextSize(24); title.setPadding(0, 0, 0, dp(2));
        root.addView(title);
        TextView sub = new TextView(this);
        sub.setText("Choisis un serveur, puis Jouer.");
        sub.setTextColor(MUT); sub.setTextSize(13); sub.setPadding(0, 0, 0, dp(12));
        root.addView(sub);

        // --- barre d'ONGLETS (Serveurs / Compte) ---
        LinearLayout tabs = new LinearLayout(this); tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setPadding(0, 0, 0, dp(10));
        tabServersBtn = tabButton("🎮  Serveurs");
        tabAccountBtn = tabButton("👤  Compte");
        tabServersBtn.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { showTab(true); } });
        tabAccountBtn.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { showTab(false); } });
        tabs.addView(tabServersBtn); tabs.addView(tabAccountBtn);
        root.addView(tabs);

        // --- ONGLET SERVEURS ---
        tabServers = new LinearLayout(this); tabServers.setOrientation(LinearLayout.VERTICAL);
        root.addView(tabServers);
        favBox = section(tabServers, "★  Favoris");
        dirBox = section(tabServers, "🌐  Serveurs communautaires");
        // recherche
        dirSearch = input("Rechercher (nom, adresse)…");
        dirSearch.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) {}
            public void afterTextChanged(Editable e) { renderDir(); }
        });
        dirBox.addView(dirSearch);
        // filtres mode
        LinearLayout fil = new LinearLayout(this); fil.setOrientation(LinearLayout.HORIZONTAL); fil.setPadding(0, dp(6), 0, dp(4));
        filAll = wide(button("Tous", false));    filAll.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { dirFilter = 0; renderDir(); } });
        filStrict = wide(button("🔒 Strict", false)); filStrict.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { dirFilter = 1; renderDir(); } });
        filOpen = wide(button("Ouvert", false)); filOpen.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { dirFilter = 2; renderDir(); } });
        fil.addView(filAll); fil.addView(filStrict); fil.addView(filOpen);
        dirBox.addView(fil);
        dirStatus = new TextView(this);
        dirStatus.setTextColor(MUT); dirStatus.setTextSize(13); dirStatus.setPadding(0, dp(2), 0, dp(4));
        dirBox.addView(dirStatus);
        dirList = new LinearLayout(this); dirList.setOrientation(LinearLayout.VERTICAL);
        dirBox.addView(dirList);
        LinearLayout man = section(tabServers, "⌨  Adresse manuelle");
        final EditText name = input("Nom (optionnel)");
        final EditText addr = input("192.168.1.20:8080");
        man.addView(name); man.addView(addr);
        LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(6), 0, 0);
        Button play = wide(button("Jouer", true));
        Button fav = button("+ Favori", false);
        play.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { parsePlay(addr, name, false); } });
        fav.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { parsePlay(addr, name, true); } });
        row.addView(play); row.addView(fav);
        man.addView(row);

        // --- ONGLET COMPTE ---
        tabAccount = new LinearLayout(this); tabAccount.setOrientation(LinearLayout.VERTICAL);
        root.addView(tabAccount);
        accountBox = section(tabAccount, "👤  Compte (serveurs stricts)");

        setContentView(scroll);
        showTab(true);
        renderAccount();
        renderFavorites();
        loadDirectory();
    }

    /** Bascule l'onglet visible + surligne le bouton actif. */
    private void showTab(boolean servers) {
        tabServers.setVisibility(servers ? View.VISIBLE : View.GONE);
        tabAccount.setVisibility(servers ? View.GONE : View.VISIBLE);
        paintTab(tabServersBtn, servers);
        paintTab(tabAccountBtn, !servers);
    }
    private void paintTab(Button b, boolean active) {
        GradientDrawable bg = new GradientDrawable(); bg.setColor(active ? ACCENT : BTN2); bg.setCornerRadius(dp(8));
        b.setBackground(bg); b.setTextColor(active ? Color.WHITE : MUT);
    }
    private Button tabButton(String text) {
        Button b = new Button(this);
        b.setText(text); b.setAllCaps(false); b.setTextSize(15);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(0, 0, dp(8), 0); b.setLayoutParams(lp);
        return b;
    }
    /** Rend un bouton EXTENSIBLE (poids 1) dans une rangée horizontale. */
    private Button wide(Button b) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(0, dp(6), dp(8), 0); b.setLayoutParams(lp);
        return b;
    }

    /** Copie du texte dans le presse-papier + petit toast. */
    private void copyToClipboard(String label, String text) {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText(label, text));
            Toast.makeText(this, "Copié dans le presse-papier", Toast.LENGTH_SHORT).show();
        } catch (Exception e) { Toast.makeText(this, "Copie impossible", Toast.LENGTH_SHORT).show(); }
    }

    // ---------- compte mnémonique (V3 — identité pour les serveurs stricts) ----------
    private String mnemonic() { return getSharedPreferences(PREFS, MODE_PRIVATE).getString(MNEMONIC_PREF, ""); }
    private void storeMnemonic(String phrase) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(MNEMONIC_PREF, phrase).commit();
    }

    /** Ajoute un TextView simple à un conteneur. */
    private TextView text(LinearLayout box, String s, int color, int size) {
        TextView t = new TextView(this); t.setText(s); t.setTextColor(color); t.setTextSize(size);
        t.setPadding(0, dp(2), 0, dp(2)); box.addView(t); return t;
    }
    private void addRow(LinearLayout box, View... views) {
        LinearLayout r = new LinearLayout(this); r.setOrientation(LinearLayout.HORIZONTAL);
        for (View v : views) r.addView(v);
        box.addView(r);
    }

    private void renderAccount() {
        accountBox.removeAllViews();
        String phrase = mnemonic();
        if (phrase.isEmpty()) {
            text(accountBox, "Aucun compte. Les serveurs 🔒 stricts exigent un compte mnémonique "
                + "(8 mots, comme une « seed » de portefeuille). Il te suit d'un appareil à l'autre.", MUT, 13);
            Button create = button("Créer un compte", true);
            Button restore = button("Restaurer une phrase", false);
            create.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { createAccount(); } });
            restore.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { restoreUI(); } });
            addRow(accountBox, create, restore);
            return;
        }
        try {
            MobileIdentity.Identity id = MobileIdentity.fromPhrase(phrase);
            text(accountBox, "Compte  #" + id.userID, OKC, 16);
            Button show = button("Afficher ma phrase", false);
            Button change = button("Changer de compte", false);
            show.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { showPhraseUI(); } });
            change.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { changeAccountUI(); } });
            addRow(accountBox, show, change);
        } catch (Exception e) {
            text(accountBox, "Phrase enregistrée invalide — restaure ou recrée un compte.", WARN, 13);
            Button reset = button("Réinitialiser", false);
            reset.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { storeMnemonic(""); renderAccount(); } });
            accountBox.addView(reset);
        }
    }

    private void createAccount() {
        final String phrase = MobileIdentity.generate();
        storeMnemonic(phrase);
        accountBox.removeAllViews();
        text(accountBox, "⚠  NOTE CES 8 MOTS ET GARDE-LES EN SÛRETÉ. C'est la SEULE façon de retrouver ton compte "
            + "(sur cet appareil ou un autre). Personne ne peut les récupérer à ta place.", WARN, 13);
        phraseBox(phrase);
        Button copy = button("⧉ Copier", false);
        copy.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { copyToClipboard("phrase", phrase); } });
        Button done = button("J'ai noté ma phrase", true);
        done.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { renderAccount(); } });
        addRow(accountBox, copy, done);
    }

    /** Affiche la phrase dans un cadre lisible (pleine largeur). */
    private void phraseBox(String phrase) {
        TextView p = text(accountBox, phrase, TXT, 20);
        p.setTypeface(android.graphics.Typeface.MONOSPACE);
        GradientDrawable bg = new GradientDrawable(); bg.setColor(BTN2); bg.setCornerRadius(dp(8));
        p.setBackground(bg); p.setPadding(dp(12), dp(12), dp(12), dp(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(6), 0, dp(6)); p.setLayoutParams(lp);
    }

    private void restoreUI() {
        accountBox.removeAllViews();
        text(accountBox, "Saisis les 8 mots de ta phrase (séparés par des espaces).", MUT, 13);
        final EditText in = input("mot1 mot2 … mot8");
        accountBox.addView(in);
        final TextView err = text(accountBox, "", WARN, 13);
        Button ok = button("Restaurer", true);
        Button cancel = button("Annuler", false);
        ok.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            String phrase = in.getText().toString().trim();
            if (!MobileIdentity.isValid(phrase)) { err.setText("Phrase invalide (vérifie les mots / l'ordre)."); return; }
            storeMnemonic(phrase); renderAccount();
        } });
        cancel.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { renderAccount(); } });
        addRow(accountBox, ok, cancel);
    }

    private void showPhraseUI() {
        final String phrase = mnemonic();
        accountBox.removeAllViews();
        text(accountBox, "Ta phrase (garde-la secrète) :", MUT, 13);
        phraseBox(phrase);
        Button copy = button("⧉ Copier", false);
        copy.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { copyToClipboard("phrase", phrase); } });
        Button back = button("Masquer", true);
        back.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { renderAccount(); } });
        addRow(accountBox, copy, back);
    }

    private void changeAccountUI() {
        accountBox.removeAllViews();
        text(accountBox, "Changer de compte remplace la phrase actuelle. Assure-toi de l'avoir notée avant.", WARN, 13);
        Button create = button("Nouveau compte", true);
        Button restore = button("Restaurer une phrase", false);
        Button cancel = button("Annuler", false);
        create.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { createAccount(); } });
        restore.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { restoreUI(); } });
        cancel.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { renderAccount(); } });
        addRow(accountBox, create, restore, cancel);
    }

    // ---------- UI helpers ----------
    private LinearLayout section(LinearLayout parent, String label) {
        TextView h = new TextView(this);
        h.setText(label); h.setTextColor(TXT); h.setTextSize(16); h.setPadding(0, dp(12), 0, dp(6));
        parent.addView(h);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable(); bg.setColor(CARD); bg.setCornerRadius(dp(10));
        box.setBackground(bg); box.setPadding(dp(12), dp(10), dp(12), dp(10));
        parent.addView(box);
        return box;
    }
    private EditText input(String hint) {
        EditText e = new EditText(this);
        e.setHint(hint); e.setTextColor(TXT); e.setHintTextColor(MUT); e.setTextSize(15); e.setSingleLine(true);
        return e;
    }
    private Button button(String text, boolean primary) {
        Button b = new Button(this);
        b.setText(text); b.setAllCaps(false); b.setTextColor(primary ? Color.WHITE : TXT); b.setTextSize(15);
        GradientDrawable bg = new GradientDrawable(); bg.setColor(primary ? ACCENT : BTN2); bg.setCornerRadius(dp(8));
        b.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(6), dp(8), 0); b.setLayoutParams(lp);
        return b;
    }
    private View serverCard(String name, String subtitle, String host, int port, boolean favorite) {
        return serverCard(name, subtitle, host, port, favorite, null);
    }
    /** Carte serveur VERTICALE : nom + détails en PLEINE LARGEUR (plus d'enroulement lettre par lettre), puis une rangée
     *  de boutons dessous. Si {@code infoUrl != null} (serveur communautaire), ajoute « 🔎 » (vérif signature /info + ping). */
    private View serverCard(final String name, String subtitle, final String host, final int port,
                            boolean favorite, final String infoUrl) {
        LinearLayout card = new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable cbg = new GradientDrawable(); cbg.setColor(BG); cbg.setCornerRadius(dp(8));
        card.setBackground(cbg); card.setPadding(dp(10), dp(8), dp(10), dp(8));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.setMargins(0, dp(4), 0, dp(4)); card.setLayoutParams(clp);

        TextView n = new TextView(this); n.setText(name); n.setTextColor(TXT); n.setTextSize(16);
        TextView s = new TextView(this); s.setText(subtitle); s.setTextColor(MUT); s.setTextSize(12);
        s.setPadding(0, dp(1), 0, dp(2));
        final TextView status = new TextView(this); status.setTextSize(12); status.setVisibility(View.GONE);
        card.addView(n); card.addView(s); card.addView(status);

        LinearLayout btns = new LinearLayout(this); btns.setOrientation(LinearLayout.HORIZONTAL);
        Button pl = wide(button("Jouer", true));
        pl.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { play(host, port); } });
        btns.addView(pl);
        if (!favorite) {
            Button st = button("★", false);
            st.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { addFavorite(name, host, port); renderFavorites(); Toast.makeText(DhServerPicker.this, "Ajouté aux favoris", Toast.LENGTH_SHORT).show(); } });
            btns.addView(st);
        } else {
            Button rm = button("✕", false);
            rm.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { removeFavorite(host, port); renderFavorites(); } });
            btns.addView(rm);
        }
        if (infoUrl != null && !infoUrl.isEmpty() && !infoUrl.startsWith("__")) {
            final Button vf = button("🔎", false);
            vf.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
                vf.setEnabled(false);
                status.setVisibility(View.VISIBLE); status.setTextColor(MUT); status.setText("Vérification…");
                new Thread(new Runnable() { public void run() {
                    final MobileInfoVerifier.Result r = MobileInfoVerifier.verify(infoUrl);
                    ui.post(new Runnable() { public void run() {
                        vf.setEnabled(true);
                        if (r.ok) {
                            status.setTextColor(OKC);
                            status.setText("✅ vérifié · " + r.pingMs + " ms · " + r.online
                                + (r.maxOnline > 0 ? "/" + r.maxOnline : "") + " en ligne");
                        } else { status.setTextColor(WARN); status.setText("⚠️ " + r.message); }
                    } });
                } }).start();
            } });
            btns.addView(vf);
        }
        card.addView(btns);
        return card;
    }

    // ---------- actions ----------
    private void parsePlay(EditText addr, EditText name, boolean favOnly) {
        String s = addr.getText().toString().trim();
        int c = s.lastIndexOf(':');
        if (c <= 0) { dirStatus.setText("Adresse invalide (attendu host:port)."); return; }
        try {
            String host = s.substring(0, c); int port = Integer.parseInt(s.substring(c + 1));
            String nm = name.getText().toString().trim(); if (nm.isEmpty()) nm = host;
            if (favOnly) { addFavorite(nm, host, port); renderFavorites(); }
            else play(host, port);
        } catch (Exception e) { dirStatus.setText("Port invalide."); }
    }

    /** « Jouer » : si un compte existe, authentifie (défi-réponse) AVANT de lancer, puis démarre le jeu sur le serveur. */
    private void play(final String host, final int port) {
        final String phrase = mnemonic();
        if (phrase.isEmpty()) { launchGame(host, port, 0); return; }   // serveur ouvert / pas de compte
        final MobileIdentity.Identity id;
        try { id = MobileIdentity.fromPhrase(phrase); }
        catch (Exception ex) { launchGame(host, port, 0); return; }
        dirStatus.setText("Authentification du compte #" + id.userID + " …");
        new Thread(new Runnable() { public void run() {
            final MobileAuth.Result r = MobileAuth.authenticate("http://" + host + ":" + port, id);
            ui.post(new Runnable() { public void run() {
                if (!r.ok) dirStatus.setText("Auth : " + r.message + " (le jeu se lance ; un serveur strict pourra refuser).");
                launchGame(host, port, id.userID);
            } });
        } }).start();
    }

    /** Écrit host/port (+ userID authentifié pour le hook de boot) et démarre le jeu. */
    private void launchGame(String host, int port, long userID) {
        SharedPreferences.Editor e = getSharedPreferences(PREFS, MODE_PRIVATE).edit();
        e.putString("host", host); e.putInt("port", port);
        if (userID > 0) e.putLong("userID", userID); else e.remove("userID");
        e.commit();
        try {
            Intent i = new Intent();
            i.setClassName(getPackageName(), GAME_ACTIVITY);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(i); finish();
        } catch (Exception ex) { dirStatus.setText("Impossible de démarrer le jeu : " + ex); }
    }

    // ---------- favoris (SharedPreferences) ----------
    private List<String[]> favorites() {
        List<String[]> out = new ArrayList<String[]>();
        String raw = getSharedPreferences(PREFS, MODE_PRIVATE).getString(FAV_PREF, "");
        for (String line : raw.split("\n")) {
            if (line.isEmpty()) continue;
            String[] p = line.split("\t");
            if (p.length == 3) out.add(p);
        }
        return out;
    }
    private void addFavorite(String name, String host, int port) {
        removeFavorite(host, port);
        String line = name.replace("\t", " ").replace("\n", " ") + "\t" + host + "\t" + port + "\n";
        String raw = getSharedPreferences(PREFS, MODE_PRIVATE).getString(FAV_PREF, "");
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(FAV_PREF, line + raw).commit();
    }
    private void removeFavorite(String host, int port) {
        StringBuilder keep = new StringBuilder();
        for (String[] f : favorites()) if (!(f[1].equals(host) && f[2].equals(String.valueOf(port))))
            keep.append(f[0]).append('\t').append(f[1]).append('\t').append(f[2]).append('\n');
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(FAV_PREF, keep.toString()).commit();
    }
    private void renderFavorites() {
        favBox.removeAllViews();
        List<String[]> favs = favorites();
        if (favs.isEmpty()) {
            TextView t = new TextView(this); t.setText("Aucun favori — ajoute-en avec ★."); t.setTextColor(MUT); t.setTextSize(13);
            favBox.addView(t); return;
        }
        for (String[] f : favs) {
            try { favBox.addView(serverCard(f[0], f[1] + ":" + f[2], f[1], Integer.parseInt(f[2]), true)); }
            catch (NumberFormatException ignore) { }
        }
    }

    // ---------- annuaire (lecture publique) ----------
    private void loadDirectory() {
        if (DIRECTORY_URL.startsWith("__") || DIRECTORY_URL.isEmpty()) {
            dirStatus.setText("Annuaire non configuré — utilise l'adresse manuelle."); return;
        }
        dirStatus.setText("Chargement de l'annuaire…");
        new Thread(new Runnable() { public void run() {
            try {
                String url = DIRECTORY_URL.replaceAll("/$", "")
                    + "/rest/v1/servers?select=name,mode,game_version,address,online,max_online,info_url&order=updated_at.desc";
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
            } catch (Exception e) {
                ui.post(new Runnable() { public void run() { dirStatus.setText("Annuaire injoignable — utilise l'adresse manuelle."); } });
            }
        } }).start();
    }
    /** Parse le JSON de l'annuaire en mémoire, puis rend la liste filtrée. */
    private void render(String json) {
        dirServers.clear();
        Matcher m = Pattern.compile("\\{[^}]*\\}").matcher(json);
        while (m.find()) {
            String o = m.group();
            String address = field(o, "address");
            if (address == null || address.lastIndexOf(':') <= 0) continue;
            dirServers.add(new String[]{
                field(o, "name"), field(o, "mode"), field(o, "game_version"), address,
                numField(o, "online"), numField(o, "max_online"), field(o, "info_url") });
        }
        renderDir();
    }

    /** (Re)construit la liste des serveurs communautaires selon la recherche + le filtre de mode courants. */
    private void renderDir() {
        if (dirList == null) return;
        dirList.removeAllViews();
        paintFilters();
        String q = dirSearch == null ? "" : dirSearch.getText().toString().trim().toLowerCase();
        int n = 0;
        for (String[] e : dirServers) {
            String name = e[0], mode = e[1], gv = e[2], address = e[3], online = e[4], maxOnline = e[5], infoUrl = e[6];
            boolean strict = "strict".equals(mode);
            if (dirFilter == 1 && !strict) continue;
            if (dirFilter == 2 && strict) continue;
            if (!q.isEmpty()) {
                String hay = ((name == null ? "" : name) + " " + address + " " + (mode == null ? "" : mode)).toLowerCase();
                if (!hay.contains(q)) continue;
            }
            int c = address.lastIndexOf(':');
            try {
                String sub = (mode == null ? "" : (strict ? "🔒 strict" : "ouvert") + " · ")
                    + (gv == null ? "" : "v" + gv + " · ") + address
                    + (online == null ? "" : "  ·  " + online + (maxOnline != null && !"0".equals(maxOnline) ? "/" + maxOnline : "") + " en ligne");
                dirList.addView(serverCard(name == null ? address : name, sub, address.substring(0, c), Integer.parseInt(address.substring(c + 1)), false, infoUrl));
                n++;
            } catch (NumberFormatException ignore) { }
        }
        if (dirServers.isEmpty()) dirStatus.setText("Aucun serveur dans l'annuaire pour l'instant.");
        else dirStatus.setText(n + " / " + dirServers.size() + " serveur(s)"
            + (n == 0 ? " — aucun ne correspond au filtre/à la recherche." : ""));
    }
    private void paintFilters() {
        paintTab(filAll, dirFilter == 0);
        paintTab(filStrict, dirFilter == 1);
        paintTab(filOpen, dirFilter == 2);
    }

    private static String field(String obj, String key) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"").matcher(obj);
        return m.find() ? m.group(1) : null;
    }
    private static String numField(String obj, String key) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*(-?\\d+)").matcher(obj);
        return m.find() ? m.group(1) : null;
    }
    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }
}
