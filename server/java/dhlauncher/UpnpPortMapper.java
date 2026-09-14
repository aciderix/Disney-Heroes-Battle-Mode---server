package dhlauncher;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ANNUAIRE / HÉBERGEMENT — ouverture AUTOMATIQUE des ports sur la box (UPnP IGD), en JDK pur (aucune dépendance).
 *
 * <p><b>Pourquoi.</b> Pour qu'un joueur DISTANT rejoigne un serveur auto-hébergé, la box doit rediriger les ports
 * vers le PC. Le faire à la main (interface de la box, baux DHCP, IP fixe…) est exactement la « manip » qu'on veut
 * éviter : l'objectif est « je clique sur Héberger → mon serveur est joignable ». UPnP IGD permet de demander ces
 * redirections par le réseau, et il est actif par défaut sur une grande partie des box grand public.
 *
 * <p><b>Ce que ça ne peut pas faire (limites HONNÊTES, §2).</b>
 * <ul>
 *   <li><b>CGNAT</b> : si le FAI ne donne pas d'IP publique dédiée, aucune redirection n'est possible — la box
 *       elle-même est derrière un NAT opérateur. On le DÉTECTE ({@link #isCgnat}) en comparant l'IP externe vue
 *       par la box à une IP publique vue depuis Internet : si l'IP de la box est privée/100.64 ou différente,
 *       c'est du CGNAT et il faut le dire clairement plutôt que laisser l'utilisateur chercher.</li>
 *   <li><b>UPnP désactivé</b> sur la box (fréquent par sécurité) → découverte vide, on remonte l'info.</li>
 * </ul>
 *
 * <p><b>Sécurité.</b> On ne mappe QUE les ports demandés par l'utilisateur quand il coche « rendre public », avec
 * une description identifiable, et on RETIRE les mappings à l'arrêt ({@link #deleteMapping}) — pas de trou laissé
 * ouvert dans la box après avoir cessé d'héberger.
 */
public final class UpnpPortMapper {

    private UpnpPortMapper() {}

    /** Passerelle IGD découverte : URL de contrôle SOAP, type de service, et IP LOCALE par laquelle on l'atteint. */
    public static final class Gateway {
        public final String controlUrl;   // URL absolue du endpoint SOAP
        public final String serviceType;  // urn:schemas-upnp-org:service:WAN{IP,PPP}Connection:1
        public final String localIp;      // notre IP sur le LAN (= NewInternalClient)
        Gateway(String c, String s, String l) { controlUrl = c; serviceType = s; localIp = l; }
        @Override public String toString() { return serviceType + " @ " + controlUrl + " (local " + localIp + ")"; }
    }

    private static final String SSDP_ADDR = "239.255.255.250";
    private static final int SSDP_PORT = 1900;
    // On interroge plusieurs types : certaines box ne répondent qu'au device, d'autres qu'au service.
    private static final String[] SEARCH_TARGETS = {
        "urn:schemas-upnp-org:device:InternetGatewayDevice:1",
        "urn:schemas-upnp-org:service:WANIPConnection:1",
        "urn:schemas-upnp-org:service:WANPPPConnection:1",
    };

    /**
     * Découvre la passerelle IGD par SSDP (M-SEARCH multicast), puis lit sa description XML pour trouver l'URL de
     * contrôle du service WAN*Connection. {@code null} si rien trouvé (UPnP absent/désactivé/bloqué).
     */
    public static Gateway discover(int timeoutMs) {
        // On émet depuis CHAQUE interface IPv4 utilisable, pas depuis 0.0.0.0 : une machine a souvent plusieurs
        // adaptateurs (Wi-Fi + Bluetooth + cellulaire + miniports VPN…) et le M-SEARCH multicast part alors sur
        // une interface arbitraire — typiquement une 169.254.x sans box derrière → découverte vide alors que la
        // box fait très bien de l'UPnP. Constaté EN RÉEL (6 adaptateurs, un seul vrai LAN).
        List<InetAddress> locals = localIpv4Candidates();
        int budget = Math.max(600, timeoutMs / Math.max(1, locals.size()));
        for (InetAddress local : locals) {
            Gateway g = discoverFrom(local, budget);
            if (g != null) return g;
        }
        return null;
    }

    /** Adresses IPv4 locales candidates, les vraies IP de LAN privées d'abord (169.254.x en dernier recours). */
    private static List<InetAddress> localIpv4Candidates() {
        List<InetAddress> good = new ArrayList<>(), weak = new ArrayList<>();
        try {
            for (java.net.NetworkInterface ni : java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())) {
                try {
                    if (!ni.isUp() || ni.isLoopback() || !ni.supportsMulticast()) continue;
                } catch (Exception e) { continue; }
                for (InetAddress a : java.util.Collections.list(ni.getInetAddresses())) {
                    if (!(a instanceof java.net.Inet4Address) || a.isLoopbackAddress()) continue;
                    if (a.isLinkLocalAddress()) weak.add(a); else good.add(a);
                }
            }
        } catch (Exception ignore) { }
        good.addAll(weak);
        return good;
    }

    /** M-SEARCH émis depuis UNE interface précise. */
    private static Gateway discoverFrom(InetAddress local, int timeoutMs) {
        List<String> locations = new ArrayList<>();
        for (String st : SEARCH_TARGETS) {
            try (DatagramSocket sock = new DatagramSocket(0, local)) {
                sock.setSoTimeout(Math.max(400, timeoutMs / SEARCH_TARGETS.length));
                String req = "M-SEARCH * HTTP/1.1\r\n"
                        + "HOST: " + SSDP_ADDR + ":" + SSDP_PORT + "\r\n"
                        + "MAN: \"ssdp:discover\"\r\n"
                        + "MX: 2\r\n"
                        + "ST: " + st + "\r\n\r\n";
                byte[] out = req.getBytes(StandardCharsets.US_ASCII);
                sock.send(new DatagramPacket(out, out.length, InetAddress.getByName(SSDP_ADDR), SSDP_PORT));
                long deadline = System.currentTimeMillis() + Math.max(400, timeoutMs / SEARCH_TARGETS.length);
                while (System.currentTimeMillis() < deadline) {
                    byte[] buf = new byte[2048];
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    try { sock.receive(p); } catch (Exception timeout) { break; }
                    String resp = new String(p.getData(), 0, p.getLength(), StandardCharsets.US_ASCII);
                    String loc = header(resp, "LOCATION");
                    if (loc != null && !locations.contains(loc)) locations.add(loc);
                }
            } catch (Exception ignore) { /* interface sans multicast, pare-feu… → on tente la cible suivante */ }
            if (!locations.isEmpty()) break;   // une box a répondu, inutile d'insister
        }
        for (String loc : locations) {
            Gateway g = fromDescription(loc);
            if (g != null) return g;
        }
        return null;
    }

    /** Lit la description XML du device et en extrait (serviceType, controlURL) du service WAN*Connection. */
    private static Gateway fromDescription(String location) {
        try {
            String xml = httpGet(location, 4000);
            if (xml == null) return null;
            // Bloc <service> contenant WANIPConnection ou WANPPPConnection → on prend SON <controlURL>.
            Matcher m = Pattern.compile("<service>(.*?)</service>", Pattern.DOTALL).matcher(xml);
            while (m.find()) {
                String block = m.group(1);
                String type = tag(block, "serviceType");
                if (type == null) continue;
                if (!type.contains("WANIPConnection") && !type.contains("WANPPPConnection")) continue;
                String ctrl = tag(block, "controlURL");
                if (ctrl == null) continue;
                String base = tag(xml, "URLBase");
                URI abs = resolve(location, base, ctrl);
                if (abs == null) continue;
                String local = localAddressFor(abs.getHost(), abs.getPort() > 0 ? abs.getPort() : 80);
                if (local == null) continue;
                return new Gateway(abs.toString(), type, local);
            }
        } catch (Exception ignore) { }
        return null;
    }

    /** IP LOCALE réellement utilisée pour joindre la box (fiable même avec plusieurs interfaces/VPN). */
    private static String localAddressFor(String host, int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), 2500);
            return s.getLocalAddress().getHostAddress();
        } catch (Exception e) { return null; }
    }

    /** IP externe telle que LA BOX la voit ({@code GetExternalIPAddress}). {@code null} si indisponible. */
    public static String externalIp(Gateway g) {
        String resp = soap(g, "GetExternalIPAddress", "");
        if (resp == null) return null;
        String ip = tag(resp, "NewExternalIPAddress");
        return (ip == null || ip.isBlank()) ? null : ip.trim();
    }

    /**
     * Demande une redirection {@code externalPort → localIp:internalPort} (bail permanent). {@code true} si la box
     * a accepté. Idempotent en pratique : re-demander le même mapping renvoie généralement OK.
     */
    public static boolean addMapping(Gateway g, int externalPort, int internalPort, String proto, String description) {
        String args = "<NewRemoteHost></NewRemoteHost>"
                + "<NewExternalPort>" + externalPort + "</NewExternalPort>"
                + "<NewProtocol>" + proto + "</NewProtocol>"
                + "<NewInternalPort>" + internalPort + "</NewInternalPort>"
                + "<NewInternalClient>" + g.localIp + "</NewInternalClient>"
                + "<NewEnabled>1</NewEnabled>"
                + "<NewPortMappingDescription>" + xmlEscape(description) + "</NewPortMappingDescription>"
                + "<NewLeaseDuration>0</NewLeaseDuration>";
        return soap(g, "AddPortMapping", args) != null;
    }

    /** Retire une redirection posée par {@link #addMapping} (appelé à l'arrêt de l'hébergement). */
    public static boolean deleteMapping(Gateway g, int externalPort, String proto) {
        String args = "<NewRemoteHost></NewRemoteHost>"
                + "<NewExternalPort>" + externalPort + "</NewExternalPort>"
                + "<NewProtocol>" + proto + "</NewProtocol>";
        return soap(g, "DeletePortMapping", args) != null;
    }

    /**
     * CGNAT : l'IP vue par la BOX est privée/partagée (100.64/10 = espace opérateur), ou elle DIFFÈRE de l'IP vue
     * depuis Internet. Dans ces cas la redirection de ports ne sert à rien — autant le dire tout de suite.
     */
    public static boolean isCgnat(String boxExternalIp, String internetIp) {
        if (boxExternalIp == null) return false;
        if (isPrivateOrShared(boxExternalIp)) return true;
        return internetIp != null && !internetIp.isBlank() && !boxExternalIp.equals(internetIp);
    }

    private static boolean isPrivateOrShared(String ip) {
        String[] p = ip.split("\\.");
        if (p.length != 4) return false;
        try {
            int a = Integer.parseInt(p[0]), b = Integer.parseInt(p[1]);
            if (a == 10 || a == 127 || a == 0) return true;
            if (a == 192 && b == 168) return true;
            if (a == 172 && b >= 16 && b <= 31) return true;
            if (a == 169 && b == 254) return true;
            return a == 100 && b >= 64 && b <= 127;   // CGNAT (RFC 6598)
        } catch (NumberFormatException e) { return false; }
    }

    // ---------- plomberie HTTP/SOAP/XML (volontairement minimale : pas de dépendance) ----------

    /** Appel SOAP. Renvoie le corps de la réponse si HTTP 200, {@code null} sinon (erreur box, action refusée…). */
    private static String soap(Gateway g, String action, String argsXml) {
        try {
            String body = "<?xml version=\"1.0\"?>"
                    + "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\""
                    + " s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>"
                    + "<u:" + action + " xmlns:u=\"" + g.serviceType + "\">" + argsXml + "</u:" + action + ">"
                    + "</s:Body></s:Envelope>";
            HttpURLConnection c = (HttpURLConnection) new URL(g.controlUrl).openConnection();
            c.setConnectTimeout(4000); c.setReadTimeout(6000);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"");
            c.setRequestProperty("SOAPAction", "\"" + g.serviceType + "#" + action + "\"");
            byte[] out = body.getBytes(StandardCharsets.UTF_8);
            c.setFixedLengthStreamingMode(out.length);
            c.getOutputStream().write(out);
            int code = c.getResponseCode();
            InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream();
            String resp = in == null ? "" : new String(readAll(in), StandardCharsets.UTF_8);
            return code == 200 ? resp : null;
        } catch (Exception e) { return null; }
    }

    private static String httpGet(String url, int timeoutMs) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(timeoutMs); c.setReadTimeout(timeoutMs);
            if (c.getResponseCode() != 200) return null;
            return new String(readAll(c.getInputStream()), StandardCharsets.UTF_8);
        } catch (Exception e) { return null; }
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        for (int n; (n = in.read(buf)) > 0; ) bos.write(buf, 0, n);
        in.close();
        return bos.toByteArray();
    }

    /** Valeur d'un en-tête d'une réponse SSDP (insensible à la casse). */
    private static String header(String resp, String name) {
        for (String line : resp.split("\r\n")) {
            int i = line.indexOf(':');
            if (i > 0 && line.substring(0, i).trim().equalsIgnoreCase(name)) return line.substring(i + 1).trim();
        }
        return null;
    }

    /** Contenu du premier élément {@code <tag>…</tag>} (suffit pour ces descriptions XML simples). */
    private static String tag(String xml, String name) {
        Matcher m = Pattern.compile("<" + name + "[^>]*>(.*?)</" + name + ">", Pattern.DOTALL).matcher(xml);
        return m.find() ? m.group(1).trim() : null;
    }

    /** controlURL peut être relative : on la résout contre URLBase si présent, sinon contre l'URL de description. */
    private static URI resolve(String location, String base, String controlUrl) {
        try {
            if (controlUrl.startsWith("http://") || controlUrl.startsWith("https://")) return URI.create(controlUrl);
            URI root = URI.create(base != null && !base.isBlank() ? base.trim() : location);
            return root.resolve(controlUrl.startsWith("/") ? controlUrl : "/" + controlUrl);
        } catch (Exception e) { return null; }
    }

    private static String xmlEscape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
