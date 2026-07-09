package dhbackend;

import com.perblue.heroes.network.messages.Platform;
import com.perblue.heroes.util.DeviceInfo;

/**
 * Shim desktop de {@link DeviceInfo} (interface plateforme du jeu, normalement fournie par
 * l'AndroidLauncher). Valeurs **FACTICES mais cohérentes** (cf. docs/SHIMS.md) : on ne ment
 * pas au jeu sur une vérification, on fournit une identité device plausible et stable.
 *
 * {@code getPlatform()=ANDROID} pour la parité assets/logique (le seul jeu d'assets qu'on
 * possède est celui d'Android). Identifiants stables (mêmes valeurs entre deux lancements)
 * pour que le compte/login reste cohérent.
 */
public final class DhDeviceInfo implements DeviceInfo {

  // Identifiant stable (déterministe) pour ce poste — un vrai serveur associera un compte.
  private static final String UID = "dh-desktop-0000000000000001";

  @Override public boolean isInitialized() { return true; }
  @Override public Platform getPlatform() { return Platform.ANDROID; }

  @Override public String getUniqueIdentifier() { return UID; }
  @Override public String getDeviceID() { return UID; }
  @Override public String getRegistrationID() { return ""; }
  @Override public String getAdvertisingIdentifier() { return "00000000-0000-0000-0000-000000000000"; }
  @Override public String getImei() { return ""; }
  @Override public String getaPMacAddress() { return "02:00:00:00:00:00"; }
  @Override public String getaPSSID() { return ""; }
  @Override public String getEmail() { return ""; }
  @Override public String getReferalData() { return ""; }
  @Override public String getSignature() { return ""; }

  @Override public String getPackageName() { return "com.perblue.disneyheroes"; }
  @Override public String getPhoneModel() { return "Desktop"; }
  @Override public String getPhoneName() { return "DisneyHeroesDesktop"; }
  @Override public String getCarrierName() { return ""; }
  @Override public String getNetworkType() { return "wifi"; }

  @Override public String getDeviceLangauge() { return "en"; }
  @Override public String getDeviceCountryCode() { return "US"; }

  // Version : correspond à l'APK (12.1.0). getFullVersion() = version numérique interne.
  @Override public int getFullVersion() { return 12010; }
  @Override public String getDisplayVersion() { return "12.1.0"; }
  @Override public String getBuildTime() { return "2023-02-22"; }
  @Override public String getSystemDescription() { return "Linux desktop (LWJGL)"; }
  @Override public String getSystemVersion() { return System.getProperty("os.version", "1.0"); }
  @Override public int getsDKVersion() { return 30; }
  @Override public int getScreenSize() { return 5; }
  @Override public int getSystemVolume() { return 100; }
  @Override public long getSystemTime() { return System.currentTimeMillis(); }

  @Override public boolean isConnectedToWiFi() { return true; }
  @Override public boolean isConnectedToCell() { return false; }
  @Override public boolean isPlaytestClient() { return false; }
  @Override public boolean limitAdTracking() { return true; }
  @Override public boolean needsPromptForAdvertisingID() { return false; }
  @Override public void promptForAdvertisingID(DeviceInfo.AdvertisingPromptListener l) { /* no-op */ }
}
