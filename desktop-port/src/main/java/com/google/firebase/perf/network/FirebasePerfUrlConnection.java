package com.google.firebase.perf.network;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;

/**
 * SHADOW de {@code com.google.firebase.perf.network.FirebasePerfUrlConnection} (Firebase
 * Performance Monitoring, bundlé dans l'APK).
 *
 * <p>Le téléchargeur du jeu ({@code FileDownloader$GenericDownloader.doDownload}) enveloppe chaque
 * connexion HTTP par {@code instrument(conn)} pour la télémétrie Firebase. En headless sans Firebase
 * initialisé, l'init de Firebase touche {@code android.os.StrictMode.allowThreadDiskReads()} (stub
 * Android « Stub! ») → {@code ExceptionInInitializerError} qui TUE le thread de téléchargement du
 * contenu requis.
 *
 * <p>On désactive l'instrumentation d'analytics (qu'on ne peut de toute façon pas utiliser — pas de
 * projet Firebase, cf. #BRIDGES) en renvoyant la connexion RÉELLE telle quelle. Le téléchargement HTTP
 * vers notre serveur de contenu reste 100% réel. Fidélité : RÉEL (seule la télémétrie externe est
 * neutralisée — aucune donnée de jeu affectée).
 */
public class FirebasePerfUrlConnection {
    private FirebasePerfUrlConnection() {}

    /** Renvoie la connexion non instrumentée (pas de perf Firebase). */
    public static Object instrument(Object connection) throws IOException {
        return connection;
    }

    public static Object getContent(URL url) throws IOException {
        return url.openConnection().getContent();
    }

    @SuppressWarnings("rawtypes")
    public static Object getContent(URL url, Class[] types) throws IOException {
        return url.openConnection().getContent(types);
    }

    public static InputStream openStream(URL url) throws IOException {
        URLConnection c = url.openConnection();
        return c.getInputStream();
    }
}
