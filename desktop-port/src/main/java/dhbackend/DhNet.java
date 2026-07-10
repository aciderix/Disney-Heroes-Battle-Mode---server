package dhbackend;

import com.badlogic.gdx.Net;

/**
 * Backend Net desktop — **stub minimal pour l'instant** (HTTP différé).
 *
 * ⚠️ DEFERRED (BACKEND_STATUS.md #NET) : implémentation RÉELLE à porter depuis DragonSoul
 * `DsNet` (HTTP via {@code java.net.HttpURLConnection} ; c'est le canal du login `POST /login`).
 * Ici {@code sendHttpRequest} journalise et notifie l'échec proprement (pas de faux succès —
 * ce ne serait pas conforme aux principes). Le rendu/boot initial n'exige pas Net ; il devient
 * nécessaire pour le login (voir docs/PROTOCOL.md §1.3) → à implémenter avant de brancher les
 * serveurs. Le socket de jeu TCP, lui, passe par {@code java.net.Socket} (hors Net).
 */
public final class DhNet implements Net {
    @Override
    public void sendHttpRequest(Net.HttpRequest request, Net.HttpResponseListener listener) {
        String url = request != null ? request.getUrl() : "(null)";
        System.out.println("[net] DEFERRED sendHttpRequest -> " + url + " (DhNet stub : voir BACKEND_STATUS.md #NET)");
        if (listener != null) {
            listener.failed(new UnsupportedOperationException("DhNet HTTP non encore implémenté (stub)"));
        }
    }
}
