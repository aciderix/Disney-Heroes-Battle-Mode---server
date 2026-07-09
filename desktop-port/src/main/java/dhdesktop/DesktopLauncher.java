package dhdesktop;

import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import com.perblue.heroes.GameMain;
import dhbackend.DhDeviceInfo;

import java.lang.reflect.Field;

/**
 * Launcher desktop de Disney Heroes.
 *
 * Réutilise le backend desktop **bundlé dans le jeu** ({@link LwjglApplication}, LWJGL2) +
 * le root {@link GameMain} — pas de backend maison (cf. desktop-port/PROGRESS.md). Seuls
 * quelques services plateforme sont shimmés ({@link DhDeviceInfo}).
 *
 * Redirection réseau (optionnelle, sans patch bytecode) : si {@code -Ddh.server=host:port}
 * est fourni, on réécrit par réflexion {@code ServerType.LIVE} pour pointer le contenu (et
 * plus tard le login) vers notre serveur local. Sinon, adresses d'origine (hors ligne).
 */
public final class DesktopLauncher {

  public static void main(String[] args) throws Exception {
    maybeRedirectServer(System.getProperty("dh.server"));

    Lwjgl3ApplicationConfiguration cfg = new Lwjgl3ApplicationConfiguration();
    cfg.setTitle("Disney Heroes (desktop)");
    cfg.setWindowedMode(Integer.getInteger("dh.width", 1280), Integer.getInteger("dh.height", 720));
    cfg.setResizable(true);
    cfg.disableAudio(true);                 // pas de périphérique audio en conteneur headless
    cfg.setInitialVisible(!"1".equals(System.getProperty("dh.hidden"))); // masquable pour capture off-screen

    GameMain game = new GameMain(new DhDeviceInfo());
    new Lwjgl3Application(game, cfg);
  }

  /**
   * Réécrit {@code ServerType.LIVE.contentLocation} (et le host de login) vers notre serveur
   * local, par réflexion — le jeu n'est pas modifié. Best-effort : loggue et continue si les
   * noms de champs diffèrent (à ajuster selon docs/PROTOCOL.md §0).
   */
  private static void maybeRedirectServer(String hostPort) {
    if (hostPort == null || hostPort.isEmpty()) return;
    try {
      Class<?> st = Class.forName("com.perblue.heroes.ServerType");
      Object live = Enum.valueOf(st.asSubclass(Enum.class), "LIVE");
      String content = "http://" + hostPort + "/live/index.txt";
      setIfPresent(st, live, "contentLocation", content);
      System.out.println("[launcher] ServerType.LIVE redirigé -> " + content);
    } catch (Throwable t) {
      System.out.println("[launcher] redirection ServerType impossible (" + t + ") — adresses d'origine");
    }
  }

  private static void setIfPresent(Class<?> cls, Object inst, String field, String value) {
    try {
      Field f = cls.getDeclaredField(field);
      f.setAccessible(true);
      f.set(inst, value);
    } catch (NoSuchFieldException e) {
      System.out.println("[launcher] champ ServerType." + field + " absent (à ajuster)");
    } catch (Throwable t) {
      System.out.println("[launcher] échec écriture " + field + " : " + t);
    }
  }
}
