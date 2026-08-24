package dhserver;

import com.perblue.heroes.network.messages.GameMode;
import com.perblue.heroes.game.specialevent.SpecialEventType;
import com.perblue.common.specialevent.SpecialEventInfo;
import com.perblue.common.specialevent.SpecialEvents;
import com.perblue.common.specialevent.SpecialEventBuilder;
import com.perblue.common.specialevent.components.EventVisibility;
import com.perblue.common.specialevent.components.ModesOpen;
import com.perblue.common.specialevent.components.ChestDiscount;
import com.perblue.common.specialevent.components.IEventComponent;
import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * SPECIAL_EVENTS — moteur serveur-autoritatif d'événements live (live-ops opérateur). Cf. {@code docs/SPECIAL_EVENTS.md}.
 *
 * <p><b>Principe (industriel, §3/§4 — rien à la main)</b> : on ne réécrit NI le schéma JSON NI les règles. On
 * construit les événements avec les <b>classes DU JEU</b> ({@code SpecialEventInfo} + composants
 * {@code EventVisibility}/{@code ModesOpen}/…) via leur propre {@code load(info, fullJson, fullJson.get(key))}
 * (contrat relevé au bytecode), et on les injecte dans la <b>machinerie du jeu</b>
 * ({@code SpecialEventsHelper}) — c'est cette machinerie qui calcule ensuite {@code isModeOpen}, les snapshots, etc.
 * Généralisable à TOUS les composants (drop bonus, discounts, contest…) : un builder par composant, ajouté au besoin.
 *
 * <p><b>Ouverture de mode (incr. 1)</b> : {@link #buildModesOpenEvent} produit un {@code SpecialEventInfo} qui déclare
 * un ensemble de modes ouverts sur une fenêtre de temps. {@link #installOperatorEvents} les pose dans le
 * {@code SpecialEventsHelper} (le serveur autoritatif voit alors {@code isModeOpen}=true pour ces modes). Aucune carte
 * UI ({@code eventCardDisplay}) n'est requise pour l'AUTORITÉ serveur (pas de {@code checkUnitType} à l'injection) ; la
 * carte n'est nécessaire QUE si l'on pousse le JSON au client pour l'AFFICHAGE (à venir, incr. suivant).
 */
public final class ServerEvents {
  private ServerEvents() {}

  private static final JsonReader JSON = new JsonReader();

  /** Fenêtre par défaut d'un event opérateur : ouvert de « il y a 1 j » à « dans 30 j » (roulant, re-posé au boot). */
  public static long defaultStart() { return com.perblue.heroes.util.TimeUtil.serverTimeNow() - 86_400_000L; }
  public static long defaultEnd()   { return com.perblue.heroes.util.TimeUtil.serverTimeNow() + 30L * 86_400_000L; }

  /**
   * Construit un événement <b>MODES_OPEN</b> (composant {@code ModesOpen}) déclarant {@code modes} ouverts sur
   * {@code [startMs, endMs]}, via les classes du jeu (contrat de chargement {@code load(info, full, full.get(key))},
   * format flat {@code formatVersion=0}). {@code SpecialEventInfo.toJson()} en donnerait la forme canonique.
   */
  public static SpecialEventInfo buildModesOpenEvent(long id, Collection<GameMode> modes, long startMs, long endMs) {
    try {
      StringBuilder inc = new StringBuilder();
      for (GameMode m : modes) { if (inc.length() > 0) inc.append(','); inc.append("{\"gameMode\":\"").append(m.name()).append("\"}"); }
      String full =
          "{\"kind\":\"MODES_OPEN\",\"id\":" + id + ",\"formatVersion\":0,"
        + "\"timeRange\":[{\"serverFilter\":\"1-999999\",\"start\":" + startMs
        +   ",\"end\":{\"kind\":\"TIME\",\"endTime\":" + endMs + "}}],"
        + "\"gameModeFilter\":{\"include\":[" + inc + "]}}";
      JsonValue root = JSON.parse(full);

      SpecialEventInfo info = new SpecialEventInfo(SpecialEventType.class);
      setField(info, "id", id);
      setField(info, "type", SpecialEventType.MODES_OPEN);
      setField(info, "formatVersion", 0);

      // Composants construits + chargés par les classes DU JEU (contrat load(info, full, full.get(key))).
      EventVisibility vis = new EventVisibility(new int[0]);
      vis.load(info, root, root.get("timeRange"));
      addComponent(info, vis);

      ModesOpen mo = new ModesOpen(SpecialEventType.MODES_OPEN, GameMode.class);
      mo.load(info, root, root);   // ModesOpen lit "gameModeFilter" sur le nœud complet (param2)
      addComponent(info, mo);

      // Carte d'affichage minimale (cachée) — requise pour que le JSON soit RE-PARSABLE par le CLIENT (checkUnitType).
      addComponent(info, buildMinimalCard(info));
      return info;
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new RuntimeException("buildModesOpenEvent", e);
    }
  }

  /**
   * Construit un événement <b>DROP_BONUS</b> (composant {@code DropBonus}) déclarant {@code modes} affectés sur
   * {@code [startMs, endMs]}, via les classes du jeu. C'est le levier de la <b>ROTATION QUOTIDIENNE FIDÈLE</b> : contrairement
   * à MODES_OPEN (override, ouvre quel que soit le jour), un DropBonus rend {@code isModeDropBonusActive(mode)=true} et le jeu
   * applique alors <b>SA PROPRE table</b> {@code DifficultyModeHelper.getOpenDays(mode)} (PORT_DOCKS [6,4,2,1] / PORT_WAREHOUSE
   * [7,5,3,1]) → les modes s'ouvrent seulement leurs jours. Fait §8 (bytecode {@code isOpen}) :
   * {@code isOpen = isUnlocked && (isModeOpen || (isModeDropBonusActive && getOpenDays.contains(dayOfWeek)))}.
   *
   * <p>Contrat (bytecode {@code DropBonus.load(info, full, node)}) : lit {@code gameModeFilter}/{@code stuffFilter}/{@code bonus}
   * sur le nœud COMPLET (param2). Le composant est construit par la FABRIQUE du jeu ({@code createComponent("dropBonus")},
   * enregistré par {@code SpecialEventsHelper} avec {@code DropBonusFactory} → provider/generics câblés, §4). {@code refresh}
   * peuple {@code DropBonusSnapshot.multipliers} avec CHAQUE mode de {@code gameModeFilter} en clé → {@code isModeDropBonusActive}.
   * {@code stuffFilter} vide = n'affecte pas la clé (le peuplement boucle sur {@code affectedGameModes}, pas sur le stuff).
   *
   * @param bonus multiplicateur de bonus de drop (int) ; la rotation ne dépend QUE de la présence du mode en clé, mais un
   *              DropBonus « réel » porte un bonus (fidèle : le jeu ouvre PORT via un event à bonus). 0 = rotation sans bonus.
   */
  public static SpecialEventInfo buildDropBonusEvent(long id, Collection<GameMode> modes, int bonus, long startMs, long endMs) {
    try {
      StringBuilder inc = new StringBuilder();
      for (GameMode m : modes) { if (inc.length() > 0) inc.append(','); inc.append("{\"gameMode\":\"").append(m.name()).append("\"}"); }
      String full =
          "{\"kind\":\"DROP_BONUS\",\"id\":" + id + ",\"formatVersion\":0,"
        + "\"timeRange\":[{\"serverFilter\":\"1-999999\",\"start\":" + startMs
        +   ",\"end\":{\"kind\":\"TIME\",\"endTime\":" + endMs + "}}],"
        + "\"gameModeFilter\":{\"include\":[" + inc + "]},"
        + "\"stuffFilter\":{},\"bonus\":" + bonus + "}";
      JsonValue root = JSON.parse(full);

      SpecialEventInfo info = new SpecialEventInfo(SpecialEventType.class);
      setField(info, "id", id);
      setField(info, "type", SpecialEventType.DROP_BONUS);
      setField(info, "formatVersion", 0);

      EventVisibility vis = new EventVisibility(new int[0]);
      vis.load(info, root, root.get("timeRange"));
      addComponent(info, vis);

      // Composant DropBonus via la FABRIQUE du jeu (provider câblé) — load lit gameModeFilter/stuffFilter/bonus sur le full.
      IEventComponent db = SpecialEventBuilder.createComponent("dropBonus");
      Method load = findMethod(db.getClass(), "load", SpecialEventInfo.class, JsonValue.class, JsonValue.class);
      load.setAccessible(true);
      load.invoke(db, info, root, root.get("dropBonus"));
      addComponent(info, db);

      addComponent(info, buildMinimalCard(info));
      return info;
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new RuntimeException("buildDropBonusEvent", e);
    }
  }

  /**
   * Construit un événement <b>CHEST_DISCOUNT</b> (composant {@code ChestDiscount}) : réduit de {@code percentOff}% le COÛT
   * d'ouverture des coffres de {@code chests} sur {@code [startMs, endMs]}. Contrat relevé au bytecode ({@code ChestDiscount.load}) :
   * lit {@code chestFilter} (EnumFilter sur clé {@code chestType}) et {@code percentOff} (int) sur le nœud COMPLET (param2). Le
   * composant est du jeu (ctor {@code (ISpecialEventType, Class)}, PAS de provider → construction directe comme {@code ModesOpen}).
   * Effet serveur : {@code BaseEventSnapshot.getChestPrice(chestType, base)} renvoie le prix remisé → {@code ChestHelper.getPurchaseCost}
   * (utilisé par {@code openChest} + l'anti-tamper {@code validateChestPurchase}) débite/valide le prix REMISÉ. 100 % data/objet du jeu.
   *
   * @param chests coffres visés (EnumFilter include) ; {@code percentOff} = pourcentage de remise (param ADMIN, ex. 50 = −50 %).
   */
  public static SpecialEventInfo buildChestDiscountEvent(long id, Collection<com.perblue.heroes.network.messages.ChestType> chests,
      int percentOff, long startMs, long endMs) {
    try {
      StringBuilder inc = new StringBuilder();
      for (com.perblue.heroes.network.messages.ChestType c : chests) {
        if (inc.length() > 0) inc.append(','); inc.append("{\"chestType\":\"").append(c.name()).append("\"}");
      }
      String full =
          "{\"kind\":\"CHEST_DISCOUNT\",\"id\":" + id + ",\"formatVersion\":0,"
        + "\"timeRange\":[{\"serverFilter\":\"1-999999\",\"start\":" + startMs
        +   ",\"end\":{\"kind\":\"TIME\",\"endTime\":" + endMs + "}}],"
        + "\"chestFilter\":{\"include\":[" + inc + "]},\"percentOff\":" + percentOff + "}";
      JsonValue root = JSON.parse(full);

      SpecialEventInfo info = new SpecialEventInfo(SpecialEventType.class);
      setField(info, "id", id);
      setField(info, "type", SpecialEventType.CHEST_DISCOUNT);
      setField(info, "formatVersion", 0);

      EventVisibility vis = new EventVisibility(new int[0]);
      vis.load(info, root, root.get("timeRange"));
      addComponent(info, vis);

      ChestDiscount cd = new ChestDiscount(SpecialEventType.CHEST_DISCOUNT, com.perblue.heroes.network.messages.ChestType.class);
      cd.load(info, root, root);   // lit chestFilter/percentOff sur le nœud complet (param2)
      addComponent(info, cd);

      addComponent(info, buildMinimalCard(info));
      return info;
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new RuntimeException("buildChestDiscountEvent", e);
    }
  }

  /**
   * Construit un événement <b>INCREASED_CHANCES</b> (composant {@code IncreasedChances}) : ajoute des chances de combat quotidiennes
   * SUPPLÉMENTAIRES aux modes {@code chances} (clé = {@code chanceType} du jeu, valeur = nombre en plus) sur {@code [startMs, endMs]}.
   * Contrat relevé au bytecode ({@code IncreasedChances.load}) : lit {@code chanceModifierList} (tableau d'objets {@code {chanceType,
   * additional}}) sur le nœud COMPLET (param2). Le composant a un CONVERTER ({@code IChanceGameModeConverter}) → construit par la FABRIQUE
   * du jeu ({@code createComponent("increasedChances")}, converter câblé §4). Effet serveur : {@code DailyActivityHelper.getMaxDailyUses(user,
   * chanceType, snapshot)} = {@code BaseEventSnapshot.getChances(chanceType, base)} = base + additional (ex. {@code DifficultyModeHelper}
   * PORT/trials). {@code chanceType} valides (bytecode) : {@code portDocks_use}, {@code portWarehouse_use}, {@code spotlightTrial_use},
   * {@code teamTrialsBlue_use}/{@code teamTrialsYellow_use}/{@code teamTrialsRed_use}, {@code codebase_use}. Params ADMIN (type + nombre).
   */
  public static SpecialEventInfo buildIncreasedChancesEvent(long id, java.util.Map<String, Integer> chances, long startMs, long endMs) {
    try {
      StringBuilder list = new StringBuilder();
      for (java.util.Map.Entry<String, Integer> e : chances.entrySet()) {
        if (list.length() > 0) list.append(',');
        list.append("{\"chanceType\":\"").append(e.getKey()).append("\",\"additional\":").append(e.getValue()).append("}");
      }
      String full =
          "{\"kind\":\"INCREASED_CHANCES\",\"id\":" + id + ",\"formatVersion\":0,"
        + "\"timeRange\":[{\"serverFilter\":\"1-999999\",\"start\":" + startMs
        +   ",\"end\":{\"kind\":\"TIME\",\"endTime\":" + endMs + "}}],"
        + "\"chanceModifierList\":[" + list + "]}";
      JsonValue root = JSON.parse(full);

      SpecialEventInfo info = new SpecialEventInfo(SpecialEventType.class);
      setField(info, "id", id);
      setField(info, "type", SpecialEventType.INCREASED_CHANCES);
      setField(info, "formatVersion", 0);

      EventVisibility vis = new EventVisibility(new int[0]);
      vis.load(info, root, root.get("timeRange"));
      addComponent(info, vis);

      IEventComponent ic = SpecialEventBuilder.createComponent("increasedChances");
      Method load = findMethod(ic.getClass(), "load", SpecialEventInfo.class, JsonValue.class, JsonValue.class);
      load.setAccessible(true);
      load.invoke(ic, info, root, root);   // lit chanceModifierList sur le nœud complet (param2)
      addComponent(info, ic);

      addComponent(info, buildMinimalCard(info));
      return info;
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new RuntimeException("buildIncreasedChancesEvent", e);
    }
  }

  /**
   * Construit un événement <b>TRADER_DISCOUNT</b> (composant {@code MerchantDiscount}) : réduit de {@code percentOff}% le PRIX
   * des objets des marchands {@code merchants} sur {@code [startMs, endMs]}. Contrat (bytecode {@code MerchantDiscount.load}) :
   * lit {@code traderFilter} (EnumFilter sur clé {@code merchantType}), {@code percentOff} (int), {@code stuffFilter} (objets visés,
   * vide = tous) sur le nœud COMPLET (param2). Composant avec provider → FABRIQUE {@code createComponent("merchantDiscount")}. Effet :
   * {@code MerchantHelper.getItemCost(user, type, item, snapshot)} renvoie le prix remisé → {@code applyPurchaseMerchantItem} débite/valide
   * le prix REMISÉ. {@code MerchantType} : BLACK_MARKET/MEGA_MART/GEAR/MEMORY/CHALLENGE/CRYPT/… Params ADMIN.
   */
  public static SpecialEventInfo buildMerchantDiscountEvent(long id, Collection<com.perblue.heroes.network.messages.MerchantType> merchants,
      int percentOff, long startMs, long endMs) {
    return buildMerchantEvent(id, "TRADER_DISCOUNT", SpecialEventType.TRADER_DISCOUNT, "merchantDiscount", merchants, percentOff, true, startMs, endMs);
  }

  /**
   * Construit un événement <b>TRADER_REFRESH_DISCOUNT</b> (composant {@code MerchantRefreshDiscount}) : réduit de {@code percentOff}%
   * le COÛT DE REFRESH (rafraîchir le stock) des marchands {@code merchants}. Mêmes clés {@code traderFilter}/{@code percentOff} (pas de
   * {@code stuffFilter}). FABRIQUE {@code createComponent("merchantRefreshDiscount")}. Effet : {@code MerchantHelper.refresh(type, refreshType,
   * user, snapshot)} applique la remise au coût de refresh payant. Params ADMIN.
   */
  public static SpecialEventInfo buildMerchantRefreshDiscountEvent(long id, Collection<com.perblue.heroes.network.messages.MerchantType> merchants,
      int percentOff, long startMs, long endMs) {
    return buildMerchantEvent(id, "TRADER_REFRESH_DISCOUNT", SpecialEventType.TRADER_REFRESH_DISCOUNT, "merchantRefreshDiscount", merchants, percentOff, false, startMs, endMs);
  }

  /**
   * Construit un événement <b>MISC_BONUS</b> (composant {@code MiscBonus}) : multiplie CERTAINES valeurs « diverses » ({@code MultiplierType} :
   * BONUS_ALCHEMY [+or par achat], BONUS_STAMINA, BONUS_INVASION_STAMINA, BONUS_PREMIUM_STAMINA) par {@code bonus}% de PLUS sur {@code [start,end]}.
   * Contrat (bytecode {@code MiscBonus.load}) : {@code miscBonusFilter} (EnumFilter clé {@code miscBonus}) + {@code bonus} (int) sur le nœud complet.
   * FABRIQUE {@code createComponent("miscBonus")}. Effet ex. : {@code SpecialEventSnapshot.getAlchemyAmount(base)} augmenté → {@code UserHelper.buyGold}
   * rend plus d'or. Params ADMIN (types + %).
   */
  public static SpecialEventInfo buildMiscBonusEvent(long id, Collection<com.perblue.heroes.game.specialevent.MultiplierType> mults, int bonus, long startMs, long endMs) {
    return buildMiscEvent(id, "MISC_BONUS", SpecialEventType.MISC_BONUS, "miscBonus", "miscBonusFilter", "miscBonus", "bonus", mults, bonus, startMs, endMs);
  }

  /**
   * Construit un événement <b>MISC_DISCOUNT</b> (composant {@code MiscDiscount}) : réduit CERTAINES valeurs ({@code MultiplierType} :
   * DISCOUNT_ALCHEMY [prix d'achat d'or], DISCOUNT_STAMINA, DISCOUNT_INVASION_STAMINA) de {@code percentOff}%. Clés {@code miscDiscountFilter}/
   * {@code percentOff}. FABRIQUE {@code createComponent("miscDiscount")}. Effet ex. : {@code getAlchemyPrice(base)} remisé → {@code buyGold} coûte moins.
   */
  public static SpecialEventInfo buildMiscDiscountEvent(long id, Collection<com.perblue.heroes.game.specialevent.MultiplierType> mults, int percentOff, long startMs, long endMs) {
    return buildMiscEvent(id, "MISC_DISCOUNT", SpecialEventType.MISC_DISCOUNT, "miscDiscount", "miscDiscountFilter", "miscDiscount", "percentOff", mults, percentOff, startMs, endMs);
  }

  /** Fabrique commune aux deux events « misc multipliers » (bonus / discount) — schéma {@code <filterKey>{include:[{<itemKey>:TYPE}]}}/{@code <valueKey>}. */
  private static SpecialEventInfo buildMiscEvent(long id, String kind, SpecialEventType type, String componentKey, String filterKey,
      String itemKey, String valueKey, Collection<com.perblue.heroes.game.specialevent.MultiplierType> mults, int value, long startMs, long endMs) {
    try {
      StringBuilder inc = new StringBuilder();
      for (com.perblue.heroes.game.specialevent.MultiplierType mt : mults) {
        if (inc.length() > 0) inc.append(','); inc.append("{\"").append(itemKey).append("\":\"").append(mt.name()).append("\"}");
      }
      String full =
          "{\"kind\":\"" + kind + "\",\"id\":" + id + ",\"formatVersion\":0,"
        + "\"timeRange\":[{\"serverFilter\":\"1-999999\",\"start\":" + startMs
        +   ",\"end\":{\"kind\":\"TIME\",\"endTime\":" + endMs + "}}],"
        + "\"" + filterKey + "\":{\"include\":[" + inc + "]},\"" + valueKey + "\":" + value + "}";
      JsonValue root = JSON.parse(full);

      SpecialEventInfo info = new SpecialEventInfo(SpecialEventType.class);
      setField(info, "id", id);
      setField(info, "type", type);
      setField(info, "formatVersion", 0);

      EventVisibility vis = new EventVisibility(new int[0]);
      vis.load(info, root, root.get("timeRange"));
      addComponent(info, vis);

      IEventComponent mc = SpecialEventBuilder.createComponent(componentKey);
      Method load = findMethod(mc.getClass(), "load", SpecialEventInfo.class, JsonValue.class, JsonValue.class);
      load.setAccessible(true);
      load.invoke(mc, info, root, root);
      addComponent(info, mc);

      addComponent(info, buildMinimalCard(info));
      return info;
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new RuntimeException("buildMiscEvent(" + kind + ")", e);
    }
  }

  /** Fabrique commune aux deux events marchands (discount d'objet / discount de refresh) — même schéma {@code traderFilter}/{@code percentOff}. */
  private static SpecialEventInfo buildMerchantEvent(long id, String kind, SpecialEventType type, String componentKey,
      Collection<com.perblue.heroes.network.messages.MerchantType> merchants, int percentOff, boolean withStuffFilter, long startMs, long endMs) {
    try {
      StringBuilder inc = new StringBuilder();
      for (com.perblue.heroes.network.messages.MerchantType mt : merchants) {
        if (inc.length() > 0) inc.append(','); inc.append("{\"merchantType\":\"").append(mt.name()).append("\"}");
      }
      String full =
          "{\"kind\":\"" + kind + "\",\"id\":" + id + ",\"formatVersion\":0,"
        + "\"timeRange\":[{\"serverFilter\":\"1-999999\",\"start\":" + startMs
        +   ",\"end\":{\"kind\":\"TIME\",\"endTime\":" + endMs + "}}],"
        + "\"traderFilter\":{\"include\":[" + inc + "]},\"percentOff\":" + percentOff
        + (withStuffFilter ? ",\"stuffFilter\":[{\"kind\":\"ALL_ITEMS\"},{\"kind\":\"ALL_RESOURCES\"}]" : "") + "}";   // remise sur TOUS objets+monnaies du marchand
      JsonValue root = JSON.parse(full);

      SpecialEventInfo info = new SpecialEventInfo(SpecialEventType.class);
      setField(info, "id", id);
      setField(info, "type", type);
      setField(info, "formatVersion", 0);

      EventVisibility vis = new EventVisibility(new int[0]);
      vis.load(info, root, root.get("timeRange"));
      addComponent(info, vis);

      IEventComponent mc = SpecialEventBuilder.createComponent(componentKey);
      Method load = findMethod(mc.getClass(), "load", SpecialEventInfo.class, JsonValue.class, JsonValue.class);
      load.setAccessible(true);
      load.invoke(mc, info, root, root);   // lit traderFilter/percentOff(/stuffFilter) sur le nœud complet (param2)
      addComponent(info, mc);

      addComponent(info, buildMinimalCard(info));
      return info;
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new RuntimeException("buildMerchantEvent(" + kind + ")", e);
    }
  }

  /**
   * Construit un événement <b>FLAG_USER_ON_LOGIN</b> (composant {@code FlagUserOnLogin}) : au login, POSE les flags {@code flagsToSet}
   * et RETIRE les flags {@code flagsToClear} du joueur (marketing/onboarding : marquer « a vu X », activer une bannière). Contrat
   * (bytecode {@code FlagUserOnLogin.load}) : {@code flags} = tableau d'objets {@code {flag:<UserFlag>, kind:set|clear}}. Composant sans
   * provider → construction directe {@code new FlagUserOnLogin(type, UserFlag.class)}. AUCUNE classe du jar client ne consomme le snapshot
   * (c'était une action SERVEUR PerBlue) → on l'APPLIQUE serveur-autoritativement via {@link #applyLoginFlags}. Params ADMIN.
   */
  public static SpecialEventInfo buildFlagUserOnLoginEvent(long id, Collection<com.perblue.heroes.game.objects.UserFlag> flagsToSet,
      Collection<com.perblue.heroes.game.objects.UserFlag> flagsToClear, long startMs, long endMs) {
    try {
      StringBuilder flags = new StringBuilder();
      for (com.perblue.heroes.game.objects.UserFlag f : flagsToSet) {
        if (flags.length() > 0) flags.append(','); flags.append("{\"flag\":\"").append(f.name()).append("\",\"kind\":\"set\"}");
      }
      for (com.perblue.heroes.game.objects.UserFlag f : flagsToClear) {
        if (flags.length() > 0) flags.append(','); flags.append("{\"flag\":\"").append(f.name()).append("\",\"kind\":\"clear\"}");
      }
      String full =
          "{\"kind\":\"FLAG_USER_ON_LOGIN\",\"id\":" + id + ",\"formatVersion\":0,"
        + "\"timeRange\":[{\"serverFilter\":\"1-999999\",\"start\":" + startMs
        +   ",\"end\":{\"kind\":\"TIME\",\"endTime\":" + endMs + "}}],"
        + "\"flags\":[" + flags + "]}";
      JsonValue root = JSON.parse(full);

      SpecialEventInfo info = new SpecialEventInfo(SpecialEventType.class);
      setField(info, "id", id);
      setField(info, "type", SpecialEventType.FLAG_USER_ON_LOGIN);
      setField(info, "formatVersion", 0);

      EventVisibility vis = new EventVisibility(new int[0]);
      vis.load(info, root, root.get("timeRange"));
      addComponent(info, vis);

      com.perblue.common.specialevent.components.FlagUserOnLogin fl =
          new com.perblue.common.specialevent.components.FlagUserOnLogin(SpecialEventType.FLAG_USER_ON_LOGIN, com.perblue.heroes.game.objects.UserFlag.class);
      fl.load(info, root, root);
      addComponent(info, fl);

      addComponent(info, buildMinimalCard(info));
      return info;
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new RuntimeException("buildFlagUserOnLoginEvent", e);
    }
  }

  /**
   * SERVEUR-AUTORITATIF : applique les events FLAG_USER_ON_LOGIN actifs — POSE {@code flagsToSet} (valeur {@code TRUE}) et RETIRE
   * {@code flagsToClear} ({@code FALSE}) dans la MAP WIRE {@code userExtra.flags} ({@code Map<UserFlag,Boolean>}, relue par
   * {@code User.setFlags} → {@code hasFlag} ; auto-persistée). Le jar client ne consomme PAS le snapshot (action backend PerBlue) →
   * glue serveur (§3 : on lit les champs du composant DU JEU, on n'invente rien). À appeler au login ({@code bootData}, après
   * {@code install}). Renvoie le nombre de flags modifiés.
   */
  @SuppressWarnings("unchecked")
  public static int applyLoginFlags(java.util.Map<Object, Object> flagsWire) {
    int changed = 0;
    if (flagsWire == null) return 0;
    try {
      com.perblue.heroes.game.specialevent.SpecialEventSnapshot snap = snapshot();
      Object state = instanceField(snap, "state");
      Class<?> snapCls = Class.forName("com.perblue.common.specialevent.components.snapshot.FlagUserOnLoginSnapshot");
      Object cs = state.getClass().getMethod("getComponentSnapshot", Class.class).invoke(state, snapCls);
      if (cs == null) return 0;
      java.util.List<?> events = (java.util.List<?>) cs.getClass().getMethod("getEvents").invoke(cs);
      Class<?> flCls = Class.forName("com.perblue.common.specialevent.components.FlagUserOnLogin");
      for (Object info : events) {   // chaque élément = un SpecialEventInfo → on en tire le composant FlagUserOnLogin
        Object comp = ((SpecialEventInfo) info).getComponent((Class) flCls);
        if (comp == null) continue;
        // Clé de la map WIRE = NOM du flag (String), valeur = Boolean (relu par User.setFlags).
        for (Object fl : (java.util.List<?>) instanceField(comp, "flagsToSet")) {
          String k = ((Enum<?>) fl).name();
          if (!Boolean.TRUE.equals(flagsWire.get(k))) { flagsWire.put(k, Boolean.TRUE); changed++; }
        }
        for (Object fl : (java.util.List<?>) instanceField(comp, "flagsToClear")) {
          String k = ((Enum<?>) fl).name();
          if (Boolean.TRUE.equals(flagsWire.get(k))) { flagsWire.put(k, Boolean.FALSE); changed++; }
        }
      }
    } catch (Exception e) { System.out.println("[events] applyLoginFlags: " + e); }
    return changed;
  }

  /**
   * Construit un événement <b>TEAM LEVEL</b> (récompense au palier de niveau d'équipe). {@code everyX=false} → {@code TeamAtLevel}
   * (FREE_STUFF_AT_TEAM_LEVEL, récompense EN ATTEIGNANT le niveau {@code teamLevel}) ; {@code everyX=true} → {@code TeamLevelRecord}
   * (FREE_STUFF_EVERY_X_TEAM_LEVEL, récompense TOUS LES {@code teamLevel} niveaux). Composants : {@code TeamAtLevel}/{@code TeamLevelRecord}
   * (lit {@code teamLevel}) + {@code EventRewards} (lit {@code rewards} = drops {@code {kind:ITEM,itemType:X,quantity:N}}). Effet serveur :
   * {@code teamLevelRewardDrops(user, oldTL, newTL)} livre les drops par COURRIER au level-up (glue serveur — le jar client ne fait que
   * la conversion premium-stamina). {@code dropJsons} = liste de drops JSON (params ADMIN : item + quantité).
   */
  public static SpecialEventInfo buildTeamLevelEvent(long id, int teamLevel, Collection<String> dropJsons, boolean everyX, long startMs, long endMs) {
    try {
      String kind = everyX ? "FREE_STUFF_EVERY_X_TEAM_LEVEL" : "FREE_STUFF_AT_TEAM_LEVEL";
      SpecialEventType type = everyX ? SpecialEventType.FREE_STUFF_EVERY_X_TEAM_LEVEL : SpecialEventType.FREE_STUFF_AT_TEAM_LEVEL;
      StringBuilder drops = new StringBuilder();
      for (String d : dropJsons) { if (drops.length() > 0) drops.append(','); drops.append(d); }
      String full =
          "{\"kind\":\"" + kind + "\",\"id\":" + id + ",\"formatVersion\":0,"
        + "\"timeRange\":[{\"serverFilter\":\"1-999999\",\"start\":" + startMs
        +   ",\"end\":{\"kind\":\"TIME\",\"endTime\":" + endMs + "}}],"
        + "\"teamLevel\":" + teamLevel + ",\"rewards\":[" + drops + "]}";
      JsonValue root = JSON.parse(full);

      SpecialEventInfo info = new SpecialEventInfo(SpecialEventType.class);
      setField(info, "id", id);
      setField(info, "type", type);
      setField(info, "formatVersion", 0);

      EventVisibility vis = new EventVisibility(new int[0]);
      vis.load(info, root, root.get("timeRange"));
      addComponent(info, vis);

      IEventComponent tl = everyX
          ? new com.perblue.common.specialevent.components.TeamLevelRecord(type)
          : new com.perblue.common.specialevent.components.TeamAtLevel(type);
      Method loadTl = findMethod(tl.getClass(), "load", SpecialEventInfo.class, JsonValue.class, JsonValue.class);
      loadTl.setAccessible(true); loadTl.invoke(tl, info, root, root);
      addComponent(info, tl);

      com.perblue.common.specialevent.components.EventRewards er = new com.perblue.common.specialevent.components.EventRewards();
      er.load(info, root, root);
      addComponent(info, er);

      addComponent(info, buildMinimalCard(info));
      return info;
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new RuntimeException("buildTeamLevelEvent", e);
    }
  }

  /**
   * SERVEUR-AUTORITATIF : drops à accorder au joueur quand son niveau d'équipe passe de {@code oldTL} à {@code newTL}, d'après les
   * events TEAM LEVEL actifs. Pour chaque event : {@code TeamAtLevel/TeamLevelRecord.getRewardTimes(info, user, oldTL, newTL)} × les
   * drops de son {@code EventRewards}. Le jar client ne GRANT pas (backend PerBlue) → l'appelant livre ces drops (par courrier). §3 :
   * on lit {@code getRewardTimes}/{@code getRewards} du jeu, on n'invente rien.
   */
  public static java.util.List<com.perblue.heroes.network.messages.RewardDrop> teamLevelRewardDrops(
      com.perblue.heroes.game.objects.User user, int oldTL, int newTL) {
    java.util.List<com.perblue.heroes.network.messages.RewardDrop> out = new ArrayList<>();
    if (newTL <= oldTL) return out;
    try {
      Class<?> talCls = Class.forName("com.perblue.common.specialevent.components.TeamAtLevel");
      Class<?> tlrCls = Class.forName("com.perblue.common.specialevent.components.TeamLevelRecord");
      Class<?> erCls = Class.forName("com.perblue.common.specialevent.components.EventRewards");
      for (SpecialEventInfo info : OPERATOR_EVENTS) {
        Object tl = info.getComponent((Class) talCls);
        if (tl == null) tl = info.getComponent((Class) tlrCls);
        Object er = info.getComponent((Class) erCls);
        if (tl == null || er == null) continue;
        int times = (Integer) tl.getClass().getMethod("getRewardTimes", SpecialEventInfo.class,
            com.perblue.common.specialevent.game.IEventUser.class, int.class, int.class).invoke(tl, info, user, oldTL, newTL);
        if (times <= 0) continue;
        java.util.List<?> drops = (java.util.List<?>) er.getClass().getMethod("getRewards",
            com.perblue.common.specialevent.game.IEventUser.class, int.class).invoke(er, user, 1);
        for (int t = 0; t < times; t++)
          for (Object d : drops) out.add((com.perblue.heroes.network.messages.RewardDrop) d);
      }
    } catch (Exception e) { System.out.println("[events] teamLevelRewardDrops: " + e); }
    return out;
  }

  /**
   * Construit un événement <b>TRIAL</b> (composant {@code TrialEventInfo}, clé "trial") — le PRÉREQUIS de FRANCHISE_TRIALS
   * (tout trial est un événement spécial). <b>Object-path INDUSTRIEL</b> (décision utilisateur, patron {@code buildMinimalCard}) :
   * on construit le composant via la FABRIQUE du jeu ({@code createComponent("trial")}) puis on remplit ses champs par un
   * filler GÉNÉRIQUE PAR TYPE — PAS de JSON `TrialEventInfo` à la main (schéma riche/niché = anti-pattern proscrit). Le contenu
   * ennemis (listes vides ici) sera tiré des {@code .tab} aux incréments suivants (§4). Le trial est OUVERT tous les jours
   * ({@code activeDays=[EVERYDAY]}) et de type {@code HERO_SPOTLIGHT} (le plus simple : état per-user = compteur
   * {@code spotlightTrialUses}).
   *
   * @param trialType type de trial ({@code GenericTrialType.HERO_SPOTLIGHT} par défaut).
   */
  @SuppressWarnings({"rawtypes", "unchecked"})
  public static SpecialEventInfo buildTrialEvent(long id, com.perblue.heroes.network.messages.GenericTrialType trialType,
                                                 long startMs, long endMs) {
    try {
      String full = "{\"kind\":\"TRIAL\",\"id\":" + id + ",\"formatVersion\":0,"
        + "\"timeRange\":[{\"serverFilter\":\"1-999999\",\"start\":" + startMs
        +   ",\"end\":{\"kind\":\"TIME\",\"endTime\":" + endMs + "}}]}";
      JsonValue root = JSON.parse(full);

      SpecialEventInfo info = new SpecialEventInfo(SpecialEventType.class);
      setField(info, "id", id);
      setField(info, "type", SpecialEventType.TRIAL);
      setField(info, "formatVersion", 0);

      EventVisibility vis = new EventVisibility(new int[0]);
      vis.load(info, root, root.get("timeRange"));
      addComponent(info, vis);

      // Composant "trial" via la FABRIQUE du jeu + remplissage GÉNÉRIQUE PAR TYPE (object-path, pas de JSON riche à la main).
      IEventComponent trial = SpecialEventBuilder.createComponent("trial");
      fillTrialFields(trial, trialType == null ? com.perblue.heroes.network.messages.GenericTrialType.HERO_SPOTLIGHT : trialType);
      addComponent(info, trial);

      addComponent(info, buildMinimalCard(info));
      return info;
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new RuntimeException("buildTrialEvent", e);
    }
  }

  /**
   * Construit un événement <b>FRANCHISE TRIAL</b> FIDÈLE (structure) depuis les données du jeu ({@code PatchStats}
   * {@code patched_heroes_base_trial_config.tab}) — <b>rôle backend autoritatif</b> : le client LIT cet event des
   * évènements actifs ({@code isFranchiseTrialAvailable}) ; c'était au backend PerBlue de le construire depuis ces mêmes `.tab`.
   * <b>0 invention (§4)</b> : {@code NODE_COUNT}, {@code FRANCHISES} (les franchises de la saison), {@code MAX_DAILY_RESETS},
   * {@code WAVE_COUNT}, gating levels… sont LUS de {@code BASE_TRIAL_CONFIG_STATS.getStats()}. Structure produite :
   * 1 sous-trial par franchise de la saison × {@code NODE_COUNT} nœuds. (Le CONTENU ennemis/gating/récompenses par nœud =
   * incrément suivant : ennemis = héros de la franchise + stages de {@code franchise_trials_enemy_config.tab}.)
   */
  @SuppressWarnings({"rawtypes", "unchecked"})
  /**
   * Nombre de chances par reset d'un franchise trial. **PAS dans les `.tab`** (ni base_trial_config ni event_trial_constants) :
   * c'est une valeur BACKEND-AUTHORED du JSON d'event PerBlue → **paramètre ADMIN** (règle utilisateur : « défini par le serveur →
   * à l'admin de le définir »). Défaut = 10 (vérité terrain, captures en jeu « CHANCES LEFT: 10/10 »), surchargeable
   * (`AdminEvents --open-trial --chances N`). NON inventé : valeur observée, exposée comme paramètre.
   */
  public static final int DEFAULT_TRIAL_CHANCES = 10;
  /** Titre par défaut (param admin `--title` surchargeable). Libellé littéral, pas une clé de localisation. */
  public static final String DEFAULT_TRIAL_TITLE = "FRANCHISE TRIALS";

  public static SpecialEventInfo buildFranchiseTrialEvent(long id, long startMs, long endMs) {
    return buildFranchiseTrialEvent(id, startMs, endMs, DEFAULT_TRIAL_CHANCES, DEFAULT_TRIAL_TITLE, 0);
  }

  public static SpecialEventInfo buildFranchiseTrialEvent(long id, long startMs, long endMs, int chances) {
    return buildFranchiseTrialEvent(id, startMs, endMs, chances, DEFAULT_TRIAL_TITLE, 0);
  }

  public static SpecialEventInfo buildFranchiseTrialEvent(long id, long startMs, long endMs, int chances, String title) {
    return buildFranchiseTrialEvent(id, startMs, endMs, chances, title, 0, 0);
  }

  public static SpecialEventInfo buildFranchiseTrialEvent(long id, long startMs, long endMs, int chances, String title, int trialIndex) {
    return buildFranchiseTrialEvent(id, startMs, endMs, chances, title, trialIndex, 0);
  }

  /**
   * @param trialIndex index du TRIAL de la saison courante (0,1,2…) — détermine les franchises (=sous-trials), le questType et
   *   les jours actifs (data-driven, {@code seasonTrialConfigs}). L'admin choisit quel trial de saison activer (`AdminEvents --trial N`).
   * @param modifiersPerNode nb de combat modifiers ALÉATOIRES par nœud, tirés du POOL `event_trial_arena_rules.tab`
   *   ({@code TrialEventCombatModifier kind:RANDOM}). 0 = aucun (défaut). CONTENU = données du jeu (§4) ; le NOMBRE est un
   *   paramètre admin (le vrai event backend le fixe). `AdminEvents --modifiers N`.
   */
  public static SpecialEventInfo buildFranchiseTrialEvent(long id, long startMs, long endMs, int chances, String title, int trialIndex, int modifiersPerNode) {
    try {
      SpecialEventInfo info = buildTrialEvent(id, com.perblue.heroes.network.messages.GenericTrialType.CAMPAIGN, startMs, endMs);
      Object trial = info.getComponent((Class) Class.forName("com.perblue.heroes.game.specialevent.TrialEventInfo"));

      // Lire base_trial_config VIA les stats du jeu (§3/§4) — pas de valeur en dur.
      Field bf = Class.forName("com.perblue.heroes.game.data.patchedheroes.PatchStats").getDeclaredField("BASE_TRIAL_CONFIG_STATS");
      bf.setAccessible(true);
      Object dhcs = bf.get(null);
      Object cst = dhcs.getClass().getMethod("getStats").invoke(dhcs);   // BaseTrialConfigConstants
      int nodeCount        = readInt(cst, "NODE_COUNT");
      int waveCount        = readInt(cst, "WAVE_COUNT");
      int maxDailyResets   = readInt(cst, "MAX_DAILY_RESETS");
      boolean allowRaiding = readBool(cst, "ENABLE_RAIDING");
      boolean statSlots    = readBool(cst, "ENABLE_STAT_SLOTS");
      int primeBadge       = readInt(cst, "PRIME_BADGE_LEVEL_REQ");
      int enhancedPrime    = readInt(cst, "ENHANCED_PRIME_BADGE_LEVEL_REQ");
      int patchLevelReq    = readInt(cst, "PATCH_LEVEL_REQ");
      // Franchises (= sous-trials) : DATA-DRIVEN par la SAISON COURANTE (franchise_season_mapping), PAS base_trial_config
      // (gabarit statique). Le trial de saison `trialIndex` donne ses franchises + questType (auto-rotation par date).
      java.util.List<String> seasonFr = seasonTrialFranchises(trialIndex);
      if (seasonFr.isEmpty()) seasonFr = seasonTrialFranchises(0);              // repli : 1ᵉʳ trial de la saison
      TRIAL_FRANCHISES_BY_EVENT.put(id, new java.util.ArrayList<>(seasonFr));   // mémorise pour le gating serveur

      Class franchiseCls = (Class) Class.forName("com.perblue.heroes.network.messages.Franchise");
      java.util.Set franchises = new java.util.LinkedHashSet();
      java.util.List subtrials = new java.util.ArrayList();
      java.util.List<String> frNames = new java.util.ArrayList<>();
      for (String fr : seasonFr) {
        fr = fr.trim(); if (fr.isEmpty()) continue;
        Object franchise = Enum.valueOf(franchiseCls, fr);
        franchises.add(franchise);
        frNames.add(fr);
        // 1 sous-trial par franchise. TITRE du sous-trial = nom de la franchise (DATA-DRIVEN §4) via EventString.unlocalized
        // (libellé littéral, pas une clé de localisation → plus de « NONE.TITLE »). WILDCARD garde son libellé (joker).
        Object sub = Class.forName("com.perblue.heroes.game.specialevent.trial.TrialEventSubtrialInfo")
            .getConstructor(SpecialEventInfo.class, JsonValue.class)
            .newInstance(info, JSON.parse("{\"title\":{},\"preset\":\"none\"}"));
        setField(sub, "title", com.perblue.common.specialevent.EventString.unlocalized(info, prettyName(fr)));
        subtrials.add(sub);
      }
      // nodeCount : NODE_COUNT nœuds appliqués à TOUS les sous-trials (scope ALL). Clés EXACTES nodeCount/scope (schéma du jeu).
      Object tnc = Class.forName("com.perblue.heroes.game.specialevent.trial.TrialEventNodeCount")
          .getConstructor(JsonValue.class, java.util.Map.class)
          .newInstance(JSON.parse("{\"nodeCount\":" + nodeCount + ",\"scope\":{}}"), new java.util.HashMap());

      // waveCount : WAVE_COUNT vagues par nœud (scope ALL). Requis : la vue du sous-trial (getCampaignEnemiesViewV2) divise
      // par le nombre de vagues → sans lui, / by zero au rendu client (§8, découvert EN JEU). Clés waveCount/scope (schéma du jeu).
      Object twc = Class.forName("com.perblue.heroes.game.specialevent.trial.TrialEventWaveCount")
          .getConstructor(JsonValue.class, java.util.Map.class)
          .newInstance(JSON.parse("{\"waveCount\":" + waveCount + ",\"scope\":{}}"), new java.util.HashMap());

      setField(trial, "subtrials", subtrials);
      setField(trial, "nodeCount", new java.util.ArrayList(java.util.List.of(tnc)));
      setField(trial, "waveCount", new java.util.ArrayList(java.util.List.of(twc)));
      setField(trial, "franchises", franchises);
      setField(trial, "maxDailyResets", maxDailyResets);
      setField(trial, "allowRaiding", allowRaiding);
      setField(trial, "enableStatSlots", statSlots);
      setField(trial, "primeBadgeLevelReq", primeBadge);
      setField(trial, "enhancedPrimeBadgeLevelReq", enhancedPrime);
      setField(trial, "patchLevelReq", patchLevelReq);

      // CONTENU ennemis — INDUSTRIEL (§4, 0 en dur) : lu des 14 stages de franchise_trials_enemy_config
      // (FRANCHISE_TRIALS_ENEMY_CONFIG_STATS.stageToEnemyConfigs) → level/rarity/stars par nœud (scope nodeNumber=stage) ;
      // lineup AUTO filtré par la franchise du sous-trial (scope subtrialNumber=i). Le jeu tire les vrais héros de la franchise.
      Field ecf = Class.forName("com.perblue.heroes.game.data.patchedheroes.PatchStats").getDeclaredField("FRANCHISE_TRIALS_ENEMY_CONFIG_STATS");
      ecf.setAccessible(true);
      Object ecStats = ecf.get(null);
      java.util.Map stages = (java.util.Map) ecStats.getClass().getSuperclass().getField("stageToEnemyConfigs").get(ecStats);
      java.util.List enemyLevel = new java.util.ArrayList(), enemyRarity = new java.util.ArrayList(), enemyStars = new java.util.ArrayList();
      java.util.List rewardTypes = new java.util.ArrayList();
      java.util.List combatModifiers = new java.util.ArrayList();
      // Un fragment "modifiers" = N règles ALÉATOIRES tirées du pool event_trial_arena_rules.tab (kind:RANDOM → le jeu pioche
      // dans EventTrialStats.getRandomArenaRules). CONTENU = données du jeu (§4) ; N = param admin (le vrai event le fixe).
      StringBuilder mods = new StringBuilder();
      for (int k = 0; k < modifiersPerNode; k++) { if (k > 0) mods.append(','); mods.append("{\"kind\":\"RANDOM\"}"); }
      for (Object key : new java.util.TreeSet(stages.keySet())) {
        int stage = ((Number) key).intValue();
        Object ec = stages.get(key);
        // levels/rarity/stars = champs String de EventTrialEnemyConfig (ex. "55","7","2") → utilisés tels quels comme expr (§4).
        String lvl = String.valueOf(readField(ec, "levels")), rar = String.valueOf(readField(ec, "rarity")), st = String.valueOf(readField(ec, "stars"));
        String sc = "\"scope\":{\"nodeNumber\":\"" + stage + "\"}";
        enemyLevel.add(mkTrialPiece("TrialEventEnemyLevel",  "{\"expr\":\"" + lvl + "\",\"random\":{\"kind\":\"NORMAL\"}," + sc + "}"));
        enemyRarity.add(mkTrialPiece("TrialEventEnemyRarity", "{\"expr\":\"" + rar + "\",\"random\":{\"kind\":\"NORMAL\"}," + sc + "}"));
        enemyStars.add(mkTrialPiece("TrialEventEnemyStars",  "{\"expr\":\"" + st  + "\",\"random\":{\"kind\":\"NORMAL\"}," + sc + "}"));
        // RÉCOMPENSES du stage — DATA-DRIVEN (§4) depuis les colonnes REWARDS/BONUSES de franchise_trials_enemy_config
        // (ex. "RANDOM_BADGE 7-11 8,RANDOM_BADGE 7-11 8" / "PATCH_ESSENCE_1 36"). On convertit le format .tab en pièces
        // TrialEventReward (schéma du jeu) : le PARSEUR du jeu valide. scope nodeNumber=stage (récompenses par nœud).
        String rewardsStr = String.valueOf(readField(ec, "rewards"));
        String bonusesStr = String.valueOf(readField(ec, "bonuses"));
        String rewardsJson = parseRewardList(rewardsStr);
        String bonusJson   = parseRewardList(bonusesStr);
        rewardTypes.add(mkTrialPiece("TrialEventRewardTypes",
            "{\"rewards\":[" + rewardsJson + "],\"bonusRewards\":[" + bonusJson + "],\"random\":{\"kind\":\"NORMAL\"}," + sc + "}"));
        // COMBAT MODIFIERS (« Rules ») — N règles aléatoires du pool `.tab` par nœud (si demandé par l'admin).
        if (modifiersPerNode > 0)
          combatModifiers.add(mkTrialPiece("TrialEventCombatModifiers",
              "{\"modifiers\":[" + mods + "],\"random\":{\"kind\":\"NORMAL\"}," + sc + "}"));
      }
      setField(trial, "enemyLevel", enemyLevel);
      setField(trial, "enemyRarity", enemyRarity);
      setField(trial, "enemyStars", enemyStars);
      setField(trial, "rewardTypes", rewardTypes);
      if (!combatModifiers.isEmpty()) setField(trial, "combatModifiers", combatModifiers);
      // chancesPerReset : paramètre ADMIN (non dans les .tab) — voir DEFAULT_TRIAL_CHANCES.
      setField(trial, "chancesPerReset", chances);
      // TITRE principal = param admin (libellé littéral, plus de « NONE.TITLE »).
      if (title != null && !title.isEmpty())
        setField(trial, "trialTitle", com.perblue.common.specialevent.EventString.unlocalized(info, title));
      // questType = celui du trial de saison (MAJOR/MERGE…) → alimente handleFranchiseTrialCompletion (avant : NONE = no-op). §4.
      String qt = seasonTrialQuestType(trialIndex);
      if (qt != null) setField(trial, "questType",
          Enum.valueOf((Class) Class.forName("com.perblue.heroes.network.messages.TrialQuestType"), qt));
      // Lineup = liste `units` de héros ennemis (TrialEventEnemyHero) : ici 5 RANDOM_HERO tirés de la FRANCHISE du sous-trial
      // (schéma du jeu, découvert via son parseur : units / kind RANDOM_HERO / categories:[{FRANCHISE, franchises:[{franchise:X}]}]
      // / realGear:{kind:<RealGearMode>}). scope subtrialNumber 1-based. WILDCARD = joker → pas de filtre franchise (tous héros).
      // ⚠ CORRECTIF §8 : RealGearMode ∈ {FIRST, NONE, RANDOM, SECOND} — PAS "NORMAL" (tryValueOf lenient → null silencieux
      // → NPE à toJson lors du PUSH client). On pose NONE (valeur VALIDE, neutre : aucun real gear FORCÉ, non inventé §4).
      // L'ASSIGNATION effective du real gear (ASSIGN_REAL_GEAR par stage, `assignRealGear` au niveau lineup) est un raffinement
      // à calibrer EN JEU (§8) : granularité par-stage (enemy_config) vs lineup par-sous-trial ; combat client-autoritatif.
      java.util.List lineups = new java.util.ArrayList();
      for (int i = 0; i < frNames.size(); i++) {
        String fr = frNames.get(i);
        // categories OBLIGATOIRE (tableau) : filtre FRANCHISE ; WILDCARD (joker) → tableau vide = aucun filtre (tous héros).
        String cat = "WILDCARD".equals(fr) ? "\"categories\":[]," :
          "\"categories\":[{\"kind\":\"FRANCHISE\",\"franchises\":[{\"franchise\":\"" + fr + "\"}]}],";
        String unit = "{\"kind\":\"RANDOM_HERO\"," + cat + "\"realGear\":{\"kind\":\"NONE\"}}";
        StringBuilder units = new StringBuilder();
        for (int k = 0; k < 5; k++) { if (k > 0) units.append(","); units.append(unit); }
        lineups.add(mkTrialPiece("TrialEventEnemyLineup",
          "{\"kind\":\"MANUAL\",\"units\":[" + units + "],\"random\":{\"kind\":\"NORMAL\"},\"scope\":{\"subtrialNumber\":\"" + (i + 1) + "\"}}"));
      }
      setField(trial, "enemyLineups", lineups);

      // GATING (§4 data-driven) : 1 critère par sous-trial de franchise → n'autorise QUE les héros de la franchise. Le client
      // FILTRE le sélecteur via ce critère ET `TrialEventInfo.franchises` est DÉRIVÉ (au `load`) du `specificFranchise` de ce
      // filtre (offset 1599 : sans gatingCriteria, getFranchises()=null en jeu). heroCount = WAVE... non : = taille d'équipe (5)
      // = « toute l'équipe doit être de la franchise » (règle des franchise trials). WILDCARD (joker) → pas de critère.
      java.util.List gating = new java.util.ArrayList();
      for (int i = 0; i < frNames.size(); i++) {
        String fr = frNames.get(i);
        if ("WILDCARD".equals(fr)) continue;
        String crit = "{\"scope\":{\"subtrialNumber\":\"" + (i + 1) + "\"},\"random\":{\"kind\":\"NORMAL\"},\"criteria\":[{"
            + "\"style\":{\"kind\":\"INCLUSIVE\",\"heroCount\":5},"
            + "\"criterion\":{\"kind\":\"CATEGORIES\",\"categories\":[{\"kind\":\"FRANCHISE\",\"franchises\":[{\"franchise\":\""
            + fr + "\"}]}]}}]}";
        gating.add(mkTrialPiece("TrialEventGatingCriteria", crit));
      }
      setField(trial, "gatingCriteria", gating);

      // Carte UI (`image` de TrialEventInfo) — le client RE-PARSE l'event reçu (`SpecialEventBuilder.buildEvent`) et `load`
      // EXIGE un objet `image` valide. ⚠ ASYMÉTRIE DU JEU : pour un card kind=UNIT, `toJson` écrit la clé `image` mais `load`
      // relit `unitType` → un card UNIT (via cardUnitType, laissé DEFAULT par fillTrialFields) NE ROUND-TRIP PAS ("Named value
      // not found: unitType" → event rejeté côté client). On met donc les deux champs de carte à null : `toJson` émet alors
      // `{kind:MATCH_DISPLAY}` (carte « matchup »), que `load` round-trip proprement, SANS asset. (§8 : découvert EN JEU.)
      setField(trial, "cardUnitType", null);
      setField(trial, "cardImage", null);

      return info;
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new RuntimeException("buildFranchiseTrialEvent", e);
    }
  }

  // FRANCHISE_TRIALS — franchises (= sous-trials) de l'event actif, par eventID, mémorisées à la construction (pour le gating).
  private static final java.util.Map<Long, java.util.List<String>> TRIAL_FRANCHISES_BY_EVENT = new java.util.concurrent.ConcurrentHashMap<>();

  /**
   * FRANCHISE_TRIALS (EVENT/FRANCHISE) — les TRIALS de la SAISON COURANTE (auto-rotation par date), lus de
   * {@code patched_heroes_franchise_season_mapping.tab} via la logique du jeu (§3, {@code FRANCHISE_SEASON_MAPPING_STATS}). Chaque
   * trial de saison = {@code FranchiseTrialConfig{franchises (=sous-trials), questType, activeDays}}. C'est la SOURCE des sous-trials
   * disponibles (pas {@code base_trial_config.FRANCHISES}, qui est un gabarit statique). Retour ordonné par index de trial (0,1,2…).
   */
  public static java.util.List<Object> seasonTrialConfigs() {
    try {
      Field sf = Class.forName("com.perblue.heroes.game.data.patchedheroes.PatchStats").getDeclaredField("FRANCHISE_SEASON_MAPPING_STATS");
      sf.setAccessible(true);
      Object stats = sf.get(null);
      // SÉLECTION DE SAISON = seasonTimeNow() (serverTimeNow + ancre de saison ADMIN), DÉCOUPLÉE des timers joueur
      // (qui gardent serverTimeNow). Ancre 0 par défaut → suit la date réelle (comportement historique). Cf. ServerContext.
      long now = ServerContext.seasonTimeNow();
      java.lang.reflect.Method gc = stats.getClass().getDeclaredMethod("getColumn", long.class); gc.setAccessible(true);
      Object col = gc.invoke(stats, now);
      java.util.Map<?, ?> tc = (java.util.Map<?, ?>) instanceField(col, "trialCollection");
      java.util.List<Object> out = new java.util.ArrayList<>();
      for (Object k : new java.util.TreeSet<>(tc.keySet())) out.add(tc.get(k));   // ordonné par index de trial
      return out;
    } catch (Exception e) { throw new RuntimeException("seasonTrialConfigs", e); }
  }

  /** Franchises (noms) d'un trial de saison (= ses sous-trials, dans l'ordre). */
  @SuppressWarnings("unchecked")
  public static java.util.List<String> seasonTrialFranchises(int trialIndex) {
    java.util.List<Object> cfgs = seasonTrialConfigs();
    if (trialIndex < 0 || trialIndex >= cfgs.size()) return java.util.Collections.emptyList();
    try {
      java.util.List<?> fr = (java.util.List<?>) cfgs.get(trialIndex).getClass().getField("franchises").get(cfgs.get(trialIndex));
      java.util.List<String> out = new java.util.ArrayList<>();
      for (Object f : fr) out.add(((Enum<?>) f).name());
      return out;
    } catch (Exception e) { throw new RuntimeException("seasonTrialFranchises", e); }
  }

  /** questType (nom) d'un trial de saison (MAJOR/MERGE/…), ou {@code null}. Alimente la complétion (handleFranchiseTrialCompletion). */
  public static String seasonTrialQuestType(int trialIndex) {
    java.util.List<Object> cfgs = seasonTrialConfigs();
    if (trialIndex < 0 || trialIndex >= cfgs.size()) return null;
    try {
      Object qt = cfgs.get(trialIndex).getClass().getField("questType").get(cfgs.get(trialIndex));
      return qt == null ? null : ((Enum<?>) qt).name();
    } catch (Exception e) { return null; }
  }

  public static int seasonTrialCount() { return seasonTrialConfigs().size(); }

  /** Franchise (nom) du sous-trial {@code subtrialNumber} (1-based) de l'event {@code eventID}, ou {@code null}. WILDCARD = pas de restriction. */
  public static String franchiseForSubtrial(long eventID, int subtrialNumber) {
    java.util.List<String> fr = TRIAL_FRANCHISES_BY_EVENT.get(eventID);
    return (fr != null && subtrialNumber >= 1 && subtrialNumber <= fr.size()) ? fr.get(subtrialNumber - 1) : null;
  }

  /**
   * Convertit une liste de récompenses au FORMAT `.tab` (colonnes REWARDS/BONUSES de franchise_trials_enemy_config, ex.
   * {@code "RANDOM_BADGE 7-11 8,RANDOM_BADGE 7-11 8"} ou {@code "PATCH_ESSENCE_1 36"}) en fragments JSON {@code TrialEventReward}
   * (schéma du jeu), séparés par des virgules. DATA-DRIVEN (§4) : on ne fait que RE-EXPRIMER les valeurs du `.tab` dans le schéma
   * du jeu (le parseur du jeu valide). Deux cas : (a) 1ᵉʳ token = {@code RewardSelectionMode} (RANDOM_BADGE…) → {@code kind}
   * + {@code minTier}-{@code maxTier} (si plage) + {@code quantity} ; (b) 1ᵉʳ token = {@code ItemType} (PATCH_ESSENCE_n…) →
   * {@code kind:ITEM} + {@code itemType} + {@code quantity}.
   */
  /** Nom de franchise lisible pour l'UI (data-driven : l'enum du `.tab`, underscores → espaces). Ex. THE_JUNGLE_BOOK → "THE JUNGLE BOOK". */
  private static String prettyName(String enumName) {
    return enumName == null ? "" : enumName.replace('_', ' ');
  }

  private static String parseRewardList(String tabList) {
    if (tabList == null) return "";
    StringBuilder out = new StringBuilder();
    for (String tok : tabList.split(",")) {
      tok = tok.trim(); if (tok.isEmpty()) continue;
      String[] p = tok.split("\\s+");
      String frag;
      boolean isSelMode;
      try { Enum.valueOf((Class) Class.forName("com.perblue.heroes.game.specialevent.trial.TrialEventReward$RewardSelectionMode"), p[0]); isSelMode = true; }
      catch (Exception e) { isSelMode = false; }
      if (isSelMode) {
        if (p.length >= 3 && p[1].contains("-")) {           // ex. RANDOM_BADGE 7-11 8 → plage de RARETÉ de badge
          String[] rr = p[1].split("-");                     // minRarity/maxRarity = expressions (bycep), pas minTier/maxTier (=mods)
          frag = "{\"kind\":\"" + p[0] + "\",\"quantity\":\"" + p[2] + "\",\"minRarity\":\"" + rr[0] + "\",\"maxRarity\":\"" + rr[1] + "\"}";
        } else {                                             // ex. RANDOM 8
          frag = "{\"kind\":\"" + p[0] + "\",\"quantity\":\"" + (p.length > 1 ? p[1] : "1") + "\"}";
        }
      } else {                                               // ex. PATCH_ESSENCE_1 36 → item
        frag = "{\"kind\":\"ITEM\",\"itemType\":\"" + p[0] + "\",\"quantity\":\"" + (p.length > 1 ? p[1] : "1") + "\"}";
      }
      if (out.length() > 0) out.append(',');
      out.append(frag);
    }
    return out.toString();
  }

  /** Construit une pièce de config de trial (`game.specialevent.trial.*`) via son ctor {@code (JsonValue, Map)} — le fragment
   *  JSON ne porte que du CONTENU lu des `.tab` (§4) ; le PARSEUR du jeu valide/construit (schéma du jeu, pas deviné). */
  @SuppressWarnings({"rawtypes", "unchecked"})
  private static Object mkTrialPiece(String simpleClass, String json) throws Exception {
    return Class.forName("com.perblue.heroes.game.specialevent.trial." + simpleClass)
        .getConstructor(JsonValue.class, java.util.Map.class).newInstance(JSON.parse(json), new java.util.HashMap());
  }

  private static Object readField(Object o, String name) throws Exception {
    Field f = o.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(o);
  }
  private static int readInt(Object o, String name) throws Exception {
    Field f = o.getClass().getDeclaredField(name); f.setAccessible(true); return f.getInt(o);
  }
  private static boolean readBool(Object o, String name) throws Exception {
    Field f = o.getClass().getDeclaredField(name); f.setAccessible(true); return f.getBoolean(o);
  }

  /**
   * Remplissage GÉNÉRIQUE PAR TYPE d'un {@code TrialEventInfo} (object-path) : {@code activeDays=[EVERYDAY]} (ouvert),
   * {@code trialType}=paramètre, {@code preset}="none", listes→vides (contenu ennemis tiré des .tab plus tard §4),
   * Set/Map→vides, int→défauts (chances=2), boolean→false, enum→1ʳᵉ constante, {@code EventString}→vide, String→"".
   */
  @SuppressWarnings({"rawtypes", "unchecked"})
  private static void fillTrialFields(IEventComponent trial, com.perblue.heroes.network.messages.GenericTrialType trialType) throws Exception {
    JsonValue emptyObj = JSON.parse("{}");
    for (Field f : trial.getClass().getDeclaredFields()) {
      if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
      f.setAccessible(true);
      Class<?> t = f.getType();
      String name = f.getName();
      try {
        if ("activeDays".equals(name)) {
          java.util.List days = new java.util.ArrayList();
          days.add(Enum.valueOf((Class) Class.forName("com.perblue.heroes.game.objects.trials.GenericTrialActiveDays"), "EVERYDAY"));
          f.set(trial, days);
        } else if ("trialType".equals(name)) {
          f.set(trial, trialType);
        } else if ("preset".equals(name)) {
          f.set(trial, "none");
        } else if (t == java.util.List.class) {
          f.set(trial, new java.util.ArrayList());
        } else if (t == java.util.Set.class) {
          f.set(trial, new java.util.HashSet());
        } else if (t == java.util.Map.class) {
          f.set(trial, new java.util.HashMap());
        } else if (t == int.class) {
          f.setInt(trial, 0);   // pas de valeur inventée ici ; chancesPerReset est posé explicitement (param admin) par l'appelant
        } else if (t == boolean.class) {
          f.setBoolean(trial, false);
        } else if (t == String.class) {
          f.set(trial, "");
        } else if (t == com.perblue.common.specialevent.EventString.class) {
          f.set(trial, com.perblue.common.specialevent.EventString.load(null, name, emptyObj));
        } else if (t.isEnum()) {
          Object[] consts = t.getEnumConstants();
          Object def = null;
          for (Object c : consts) if ("DEFAULT".equals(((Enum<?>) c).name())) { def = c; break; }
          f.set(trial, def != null ? def : consts[0]);
        }
        // autres types (objets structurés non contraints) : laissés null — à peupler depuis les .tab aux incréments suivants.
      } catch (Throwable ignore) { /* champ non contraint : laissé tel quel */ }
    }
  }

  /**
   * Construit une carte d'affichage {@code eventCardDisplay} MINIMALE (cachée) — via la FABRIQUE du jeu
   * ({@code SpecialEventBuilder.createComponent}) + un remplissage GÉNÉRIQUE PAR TYPE (pas champ-par-champ) : String→""
   * (sauf {@code preset}="none", le preset wildcard vide {@code *.eventCard.none}), {@code EventString}→vide (via son
   * {@code load} sur un nœud vide), {@code UnitTypeLookup}→{@code FixedUnitTypeLookup(DEFAULT)}, enum→DEFAULT, Class→UnitType.
   * La carte n'a AUCUN rôle serveur ; elle rend juste l'événement RE-PARSABLE par le client ({@code checkUnitType}).
   */
  @SuppressWarnings({"rawtypes", "unchecked"})
  private static IEventComponent buildMinimalCard(SpecialEventInfo info) throws Exception {
    IEventComponent card = SpecialEventBuilder.createComponent("eventCardDisplay");
    JsonValue emptyObj = JSON.parse("{}");
    for (Field f : card.getClass().getDeclaredFields()) {
      f.setAccessible(true);
      Class<?> t = f.getType();
      try {
        if (t == String.class) f.set(card, "preset".equals(f.getName()) ? "none" : "");
        else if (t == int.class) f.setInt(card, 0);
        else if (t == long.class) f.setLong(card, 0L);
        else if (t == boolean.class) f.setBoolean(card, "hidden".equals(f.getName()));
        else if (t == com.perblue.common.specialevent.EventString.class)
          f.set(card, com.perblue.common.specialevent.EventString.load(info, f.getName(), emptyObj));
        else if (t == Class.class) f.set(card, com.perblue.heroes.network.messages.UnitType.class);
        else if (t == java.lang.Enum.class) f.set(card, com.perblue.heroes.network.messages.UnitType.DEFAULT);
        else if (t.getName().endsWith("UnitTypeLookup"))
          f.set(card, new com.perblue.common.specialevent.components.pieces.FixedUnitTypeLookup(
              com.perblue.heroes.network.messages.UnitType.DEFAULT));
      } catch (Throwable ignore) { /* champ non contraint : laissé tel quel */ }
    }
    return card;
  }

  /** Pose un texte (EventString non-localisé) sur un champ de la carte ({@code title}/{@code summary}/…) — params ADMIN. */
  private static void setCardText(IEventComponent card, SpecialEventInfo info, String fieldName, String value) {
    if (value == null) return;
    try {
      Field f = card.getClass().getDeclaredField(fieldName);
      f.setAccessible(true);
      f.set(card, com.perblue.common.specialevent.EventString.unlocalized(info, value));
    } catch (Throwable ignore) { /* champ absent : on laisse tel quel */ }
  }

  /**
   * Sérialise des événements en {@code SpecialEventsRaw} (via le sérialiseur DU JEU {@code SpecialEventInfo.toJson()}) —
   * à POUSSER au CLIENT pour qu'il AFFICHE les événements (le client re-parse ce JSON par {@code buildEvent}).
   */
  public static com.perblue.heroes.network.messages.SpecialEventsRaw toRaw(List<SpecialEventInfo> events) {
    com.perblue.heroes.network.messages.SpecialEventsRaw raw = new com.perblue.heroes.network.messages.SpecialEventsRaw();
    raw.events = new ArrayList<>();
    for (SpecialEventInfo info : events) {
      com.perblue.heroes.network.messages.SpecialEventRaw ev = new com.perblue.heroes.network.messages.SpecialEventRaw();
      ev.eventID = info.getID();
      ev.jsonString = String.valueOf(info.toJson());
      raw.events.add(ev);
    }
    return raw;
  }

  /**
   * Événements opérateur COURANTS (overrides live-ops). <b>Défaut = VIDE</b> → le jeu applique sa <b>ROTATION par défaut</b>
   * ({@code getOpenDays} : DOCKS [6,4,2,1] / WAREHOUSE [7,5,3,1]) ; un opérateur AJOUTE des overrides (MODES_OPEN/DropBonus)
   * via l'outil {@code AdminEvents}, persistés dans {@code shard_state} (chargés au boot par {@code LoginServer}). Fait §8
   * (g130) : forcer les modes ouverts en permanence écraserait la rotation fidèle → on NE force RIEN par défaut.
   */
  private static volatile List<SpecialEventInfo> OPERATOR_EVENTS = new ArrayList<>();

  /** Remplace l'ensemble des événements opérateur (appelé au boot par {@code LoginServer} depuis le store shard). */
  public static void setOperatorEvents(List<SpecialEventInfo> events) {
    OPERATOR_EVENTS = (events == null) ? new ArrayList<>() : new ArrayList<>(events);
  }
  /** Copie des événements opérateur courants (pour push client via {@link #toRaw} / installation). */
  public static List<SpecialEventInfo> operatorEvents() { return new ArrayList<>(OPERATOR_EVENTS); }

  /** Rétro-compat (anciennement défauts en dur DOCKS+WAREHOUSE) : désormais = événements opérateur (défaut VIDE). */
  public static List<SpecialEventInfo> bootDefaultEvents() { return operatorEvents(); }

  // --- PERSISTANCE : config OPÉRATEUR = descripteurs (specs) reconstruits par NOS builders ------------------------------
  // On persiste la CONFIG opérateur (liste de specs {kind, modes, bonus, start, end}), PAS les événements sérialisés :
  // on reconstruit via buildModesOpenEvent/buildDropBonusEvent (qui s'installent proprement), au lieu du re-parse
  // buildEvent du jeu qui emprunte un chemin de refresh fragile (GuildStats). Format = JSON du jeu (JsonValue).

  /** Reconstruit les événements opérateur depuis la CONFIG persistée (JSON de specs) via nos builders. */
  public static List<SpecialEventInfo> eventsFromConfig(byte[] configBlob) {
    List<SpecialEventInfo> events = new ArrayList<>();
    for (JsonValue spec : configSpecs(configBlob)) {
      try { events.add(eventFromSpec(spec)); }
      catch (Throwable t) { System.out.println("[events] spec ignorée (" + spec + "): " + t); }
    }
    return events;
  }

  // --- EXTRA_CHEST (coffre bonus temporaire sur l'écran CRATES) ---------------------------------------------------------
  /**
   * Un <b>drop</b> de coffre event = {@code result} (un {@code ResourceType}/{@code ItemType} du jeu, ou une référence de
   * nœud), {@code quantity} et {@code weight} (poids relatif du tirage). Params ADMIN. Le format de la TABLE est celui du
   * jeu ({@code chests.tab} : {@code NODE/WEIGHT/QUANTITY/RESULT/BEHAVIOR}) — on ne fait qu'assembler les lignes, jamais
   * inventer un loot ou un poids (§4 : l'admin fournit result/qty/weight, le jeu les extrait/tire).
   */
  public static final class ChestDrop {
    public final String result; public final String quantity; public final int weight;
    public ChestDrop(String result, String quantity, int weight) { this.result = result; this.quantity = quantity; this.weight = Math.max(1, weight); }
  }

  /**
   * Assemble une TABLE DE DROPS au FORMAT DU JEU ({@code DHDropTableStats}/{@code chests.tab} : colonnes
   * {@code NODE/WEIGHT/QUANTITY/RESULT/BEHAVIOR}, 1ʳᵉ colonne = index de ligne). Deux nœuds frères comme les vraies tables
   * de coffre event (cf. {@code expedition_chest_drops.tab}) :
   * <ul>
   *   <li>{@code DISPLAY} = l'APERÇU (grille « loot possible » de l'écran de détail) : lu par
   *       {@code EventChestStats.getPossibleLoot} (roll du nœud {@code DISPLAY}) → {@code ChestHelper.getPossibleDrops} →
   *       {@code ChestDetailScreen}. On y liste UNE fois chaque entrée (item × quantité) via un sous-nœud {@code D<i>}.</li>
   *   <li>{@code ROOT} = le TIRAGE réel : tire {@code draws} fois dans le pool pondéré {@code PICK}
   *       (chaque entrée = une ligne {@code PICK} de poids/quantité/résultat).</li>
   * </ul>
   * Données inline consommées par {@code EventChestStats(String)} (DTCodes {@code ROOT}/{@code DISPLAY}), pas une règle
   * réécrite (§3/§4) ; sans {@code DISPLAY}, l'écran de détail affiche une grille de loot VIDE (défaut de fidélité §4bis).
   */
  public static String extraChestDropTsv(List<ChestDrop> drops, int draws) {
    StringBuilder sb = new StringBuilder();
    sb.append('\t').append("NODE\tWEIGHT\tQUANTITY\tRESULT\tBEHAVIOR\n");
    int row = 1;
    // DISPLAY (aperçu) : un sous-nœud D<i> par entrée + un nœud DISPLAY qui les liste TOUS (aperçu complet).
    StringBuilder dispList = new StringBuilder();
    for (int i = 0; i < drops.size(); i++) { if (i > 0) dispList.append(','); dispList.append("<D").append(i).append('>'); }
    sb.append(row++).append("\tDISPLAY\t1\t1\t").append(dispList).append("\t\n");
    for (int i = 0; i < drops.size(); i++) {
      ChestDrop d = drops.get(i);
      sb.append(row++).append("\tD").append(i).append("\t1\t").append(d.quantity).append('\t').append(d.result).append("\t\n");
    }
    // ROOT (tirage réel) : draws tirages dans le pool pondéré PICK.
    sb.append(row++).append("\tROOT\t1\t").append(Math.max(1, draws)).append("\t<PICK>\t\n");
    for (ChestDrop d : drops)
      sb.append(row++).append("\tPICK\t").append(d.weight).append('\t').append(d.quantity).append('\t').append(d.result).append("\t\n");
    return sb.toString();
  }

  /**
   * Construit un événement <b>EXTRA_CHEST</b> (composant {@code ExtraChest}, coffre bonus affiché temporairement sur
   * l'écran CRATES et acheté avec une monnaie). Recette relevée au bytecode ({@code EventChestData.<init>}) :
   * <ul>
   *   <li>{@code EventVisibility} + {@code EventCardDisplay} (carte REQUISE : {@code EventChestData} lit
   *       {@code getComponent(EventCardDisplay).getImage()}) + {@code ExtraChest} via la FABRIQUE
   *       {@code createComponent("eventChestData")} (câble {@code IEventChestStatsFactory} → {@code EventChestStats}).</li>
   *   <li>Sous-objet {@code eventChestData} — <b>FORMAT B</b> (sans {@code text}/{@code preset}, tout inline) :
   *       {@code cost}, {@code buyXNumber}, {@code currency} ({@code ResourceType}), {@code maxBuys}, {@code maxPurchases},
   *       {@code freeBuys}, {@code featured} ; sous-écrans {@code selectionCard{title,info}}, {@code detailsScreen{title,info}},
   *       {@code info{title,heading1,content1,heading2,content2[]}} ; et {@code config} = la TABLE DE DROPS inline
   *       ({@link #extraChestDropTsv}). Le point dur historique ({@code preset}) est ainsi ÉVITÉ (§2 : voie fidèle, pas de rustine).</li>
   * </ul>
   * Consommation : {@code BaseEventSnapshot.getSingleEventChest()} → l'écran CRATES ; l'achat/ouverture passe par
   * {@code ChestType.EVENT} (coût/monnaie/limites = logique du jeu sur le snapshot ; roll serveur-autoritatif de la table).
   * <b>Tout est param ADMIN</b> (coût, monnaie, buyX, maxBuys/maxPurchases, freeBuys, titres, loot).
   */
  public static SpecialEventInfo buildExtraChestEvent(long id, int cost, com.perblue.heroes.network.messages.ResourceType currency,
      int buyXNumber, int maxBuys, int maxPurchases, int freeBuys, boolean featured,
      String title, String info, List<ChestDrop> drops, int draws, long startMs, long endMs) {
    try {
      String tsv = extraChestDropTsv(drops, draws).replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\"", "\\\"");
      String t = esc(title == null ? "Event Crate" : title), inf = esc(info == null ? "Limited-time bonus crate!" : info);
      String full =
          "{\"kind\":\"EXTRA_CHEST\",\"id\":" + id + ",\"formatVersion\":0,"
        + "\"timeRange\":[{\"serverFilter\":\"1-999999\",\"start\":" + startMs
        +   ",\"end\":{\"kind\":\"TIME\",\"endTime\":" + endMs + "}}],"
        + "\"eventChestData\":{"
        +   "\"cost\":" + cost + ",\"buyXNumber\":" + Math.max(1, buyXNumber) + ",\"currency\":\"" + currency.name() + "\","
        +   "\"maxBuys\":" + maxBuys + ",\"maxPurchases\":" + maxPurchases + ",\"freeBuys\":" + freeBuys + ",\"featured\":" + featured + ","
        +   "\"config\":\"" + tsv + "\","
        +   "\"selectionCard\":{\"title\":\"" + t + "\",\"info\":\"" + inf + "\"},"
        +   "\"detailsScreen\":{\"title\":\"" + t + "\",\"info\":\"" + inf + "\"},"
        +   "\"info\":{\"title\":\"" + t + "\",\"heading1\":\"Summary\",\"content1\":\"" + inf + "\",\"heading2\":\"Details\",\"content2\":[\"" + inf + "\"]}"
        + "}}";
      JsonValue root = JSON.parse(full);

      SpecialEventInfo evtInfo = new SpecialEventInfo(SpecialEventType.class);
      setField(evtInfo, "id", id);
      setField(evtInfo, "type", SpecialEventType.EXTRA_CHEST);
      setField(evtInfo, "formatVersion", 0);

      EventVisibility vis = new EventVisibility(new int[0]);
      vis.load(evtInfo, root, root.get("timeRange"));
      addComponent(evtInfo, vis);

      // La carte DOIT précéder l'ExtraChest (EventChestData lit getComponent(EventCardDisplay).getImage()).
      addComponent(evtInfo, buildMinimalCard(evtInfo));

      IEventComponent ec = SpecialEventBuilder.createComponent("eventChestData");
      ec.load(evtInfo, root, root);   // lit eventChestData sur le nœud complet (param2)
      addComponent(evtInfo, ec);
      return evtInfo;
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new RuntimeException("buildExtraChestEvent", e);
    }
  }

  private static String esc(String s) { return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\""); }

  // --- CONTEST (leaderboard de tâches, solo ou guilde) -----------------------------------------------------------------
  /**
   * UNE tâche de contest = un {@code ContestTaskType} (nom, ex. {@code BATTLE_WON}/{@code ITEM_BURN}) qui rapporte
   * {@code points} par palier de {@code countNeeded} accomplissements ; {@code maxTimes}/{@code maxDailyTimes} bornent le
   * cumul (−1 = illimité) ; {@code taskData}/{@code taskData2} = filtres optionnels (ex. mode/objet ciblé). Params ADMIN.
   */
  public static final class ContestTask {
    public final String type; public final int points, countNeeded, maxTimes, maxDailyTimes; public final String taskData, taskData2;
    public ContestTask(String type, int points, int countNeeded, int maxTimes, int maxDailyTimes, String taskData, String taskData2) {
      this.type = type; this.points = points; this.countNeeded = Math.max(1, countNeeded);
      this.maxTimes = maxTimes; this.maxDailyTimes = maxDailyTimes;
      this.taskData = taskData == null ? "" : taskData; this.taskData2 = taskData2 == null ? "" : taskData2;
    }
  }
  /** UN palier de progression = {@code pointsRequired} points → livre {@code rewardDrops} (drops {@code {kind,itemType,quantity}}). */
  public static final class ContestProgress {
    public final long pointsRequired; public final List<String> rewardDrops;
    public ContestProgress(long pointsRequired, List<String> rewardDrops) { this.pointsRequired = pointsRequired; this.rewardDrops = rewardDrops; }
  }
  /** UNE récompense de classement = seuil {@code rank} (percentile si {@code percent}, sinon rang absolu) → {@code rewardDrops}. */
  public static final class ContestRank {
    public final boolean percent; public final int rank; public final List<String> rewardDrops;
    public ContestRank(boolean percent, int rank, List<String> rewardDrops) { this.percent = percent; this.rank = rank; this.rewardDrops = rewardDrops; }
  }

  private static String dropsArray(List<String> drops) {
    StringBuilder sb = new StringBuilder("[");
    for (int i = 0; i < drops.size(); i++) { if (i > 0) sb.append(','); sb.append(drops.get(i)); }
    return sb.append(']').toString();
  }

  /** Convertit les drops d'une spec ({@code [{item|unit:X, qty:N}]}) en JSON drops du jeu ({@code {kind,itemType|unitType,quantity}}). */
  private static List<String> contestDropsFromSpec(JsonValue drops) {
    List<String> out = new ArrayList<>();
    if (drops != null) for (JsonValue d = drops.child; d != null; d = d.next) {
      int qty = d.getInt("qty", 1);
      if (d.has("unit")) out.add("{\"kind\":\"UNIT\",\"unitType\":\"" + d.getString("unit") + "\",\"quantity\":" + qty + "}");
      else out.add("{\"kind\":\"ITEM\",\"itemType\":\"" + d.getString("item", "ACE_OF_SPADES") + "\",\"quantity\":" + qty + "}");
    }
    return out;
  }

  /**
   * Construit un événement <b>CONTEST</b> (composant {@code Contest}) : un leaderboard de tâches (solo ou guilde) où le
   * joueur gagne des POINTS en accomplissant des {@code ContestTaskType}, avec récompenses de PALIER
   * ({@code progressRewards}, livrées au fil de l'eau) et de RANG ({@code rankRewards}, en fin de contest).
   *
   * <p>Recette relevée au bytecode ({@code Contest.load}, formatVersion 0 → lit sur le nœud COMPLET param2) :
   * {@code contestInformation{guild,aggregate}} + {@code contestTask[]} ({@code ContestTaskInfo} :
   * {@code maxTimes/maxDailyTimes/pointsEarned/taskIndex + taskItem{taskData,taskData2,countNeeded,type,hidden}}) +
   * {@code contestProgressRewards[]} ({@code {pointsRequired, rewarditem}}) + {@code contestRankRewards[]}
   * ({@code {kind:PERCENT|NUMBER, rank, rewarditem}}). En formatVersion 0, {@code rewarditem} = un drop OU un TABLEAU de
   * drops ({@code RewardGroup} en mode « static », pas l'objet {@code {rewardTarget,rewards}}). Le composant se construit
   * par ctor direct {@code new Contest(type, ContestTaskType.class)} — {@code ContestTaskType} chargé par réflexion (dex2jar
   * laisse un attribut d'annotation de paramètre corrompu qui casse la compilation source). <b>Tout = params ADMIN.</b>
   */
  public static SpecialEventInfo buildContestEvent(long id, boolean guild, boolean aggregate,
      List<ContestTask> tasks, List<ContestProgress> progress, List<ContestRank> ranks, long startMs, long endMs) {
    return buildContestEvent(id, guild, aggregate, "Contest", "Complete tasks to earn points and rewards!", tasks, progress, ranks, startMs, endMs);
  }

  /** Variante avec TITRE + RÉSUMÉ (affichés par l'écran CONTESTS via {@code EventCardDisplay.getTitle/getSummary}) = params ADMIN. */
  public static SpecialEventInfo buildContestEvent(long id, boolean guild, boolean aggregate, String title, String summary,
      List<ContestTask> tasks, List<ContestProgress> progress, List<ContestRank> ranks, long startMs, long endMs) {
    try {
      StringBuilder tk = new StringBuilder();
      int idx = 0;
      for (ContestTask t : tasks) {
        if (tk.length() > 0) tk.append(',');
        tk.append("{\"maxTimes\":").append(t.maxTimes).append(",\"maxDailyTimes\":").append(t.maxDailyTimes)
          .append(",\"pointsEarned\":").append(t.points).append(",\"taskIndex\":").append(idx++)
          .append(",\"taskItem\":{\"taskData\":\"").append(esc(t.taskData)).append("\",\"taskData2\":\"").append(esc(t.taskData2))
          .append("\",\"countNeeded\":").append(t.countNeeded).append(",\"type\":\"").append(t.type).append("\",\"hidden\":false}}");
      }
      StringBuilder pr = new StringBuilder();
      for (ContestProgress p : progress) {
        if (pr.length() > 0) pr.append(',');
        pr.append("{\"pointsRequired\":").append(p.pointsRequired).append(",\"rewarditem\":").append(dropsArray(p.rewardDrops)).append('}');
      }
      StringBuilder rr = new StringBuilder();
      for (ContestRank r : ranks) {
        if (rr.length() > 0) rr.append(',');
        rr.append("{\"kind\":\"").append(r.percent ? "PERCENT" : "NUMBER").append("\",\"rank\":").append(r.rank)
          .append(",\"rewarditem\":").append(dropsArray(r.rewardDrops)).append('}');
      }
      String full =
          "{\"kind\":\"CONTEST\",\"id\":" + id + ",\"formatVersion\":0,"
        + "\"timeRange\":[{\"serverFilter\":\"1-999999\",\"start\":" + startMs
        +   ",\"end\":{\"kind\":\"TIME\",\"endTime\":" + endMs + "}}],"
        + "\"contestInformation\":{\"guild\":" + guild + ",\"aggregate\":" + aggregate + "},"
        + "\"contestTask\":[" + tk + "],"
        + "\"contestProgressRewards\":[" + pr + "],"
        + "\"contestRankRewards\":[" + rr + "]}";
      JsonValue root = JSON.parse(full);

      SpecialEventInfo info = new SpecialEventInfo(SpecialEventType.class);
      setField(info, "id", id);
      setField(info, "type", SpecialEventType.CONTEST);
      setField(info, "formatVersion", 0);

      EventVisibility vis = new EventVisibility(new int[0]);
      vis.load(info, root, root.get("timeRange"));
      addComponent(info, vis);
      IEventComponent card = buildMinimalCard(info);
      // TITRE + RÉSUMÉ affichés par l'écran CONTESTS (EventCardDisplay.getTitle/getSummary) — sinon « NONE.TITLE/none.summary ».
      setCardText(card, info, "title", title);
      setCardText(card, info, "summary", summary);
      addComponent(info, card);

      Class<?> ctType = Class.forName("com.perblue.heroes.game.specialevent.ContestTaskType");
      @SuppressWarnings({"unchecked", "rawtypes"})
      com.perblue.common.specialevent.components.Contest contest =
          new com.perblue.common.specialevent.components.Contest(SpecialEventType.CONTEST, ctType);
      contest.load(info, root, root);   // lit contestInformation/contestTask/contest*Rewards sur le nœud complet
      addComponent(info, contest);
      return info;
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new RuntimeException("buildContestEvent", e);
    }
  }

  /** Construit UN événement depuis une spec {kind, modes[], bonus, start, end}. */
  private static SpecialEventInfo eventFromSpec(JsonValue spec) {
    String kind = spec.getString("kind", "MODES_OPEN");
    long id = spec.getLong("id", System.nanoTime() & 0xFFFFFFL);
    long start = spec.getLong("start", defaultStart());
    long end = spec.getLong("end", defaultEnd());
    List<GameMode> modes = new ArrayList<>();
    JsonValue ms = spec.get("modes");
    if (ms != null) for (JsonValue m = ms.child; m != null; m = m.next) modes.add(GameMode.valueOf(m.asString()));
    if ("DROP_BONUS".equals(kind)) return buildDropBonusEvent(id, modes, spec.getInt("bonus", 1), start, end);
    // FRANCHISE_TRIALS incr. 7 : un event TRIAL FRANCHISE (SpecialEventInfo TRIAL) — reconstruit data-driven depuis les `.tab`
    // (buildFranchiseTrialEvent). L'`id` de la spec = l'eventID que le client renverra (GetTrialEventData/TrialEventAttack).
    if ("TRIAL_FRANCHISE".equals(kind)) return buildFranchiseTrialEvent(id, start, end,
        spec.getInt("chances", DEFAULT_TRIAL_CHANCES), spec.getString("title", DEFAULT_TRIAL_TITLE),
        spec.getInt("trial", 0), spec.getInt("modifiers", 0));
    if ("CHEST_DISCOUNT".equals(kind)) {
      List<com.perblue.heroes.network.messages.ChestType> chests = new ArrayList<>();
      JsonValue cs = spec.get("chests");
      if (cs != null) for (JsonValue c = cs.child; c != null; c = c.next)
        chests.add(com.perblue.heroes.network.messages.ChestType.valueOf(c.asString()));
      return buildChestDiscountEvent(id, chests, spec.getInt("percentOff", 50), start, end);
    }
    if ("INCREASED_CHANCES".equals(kind)) {
      java.util.Map<String, Integer> ch = new java.util.LinkedHashMap<>();
      JsonValue cn = spec.get("chances");
      if (cn != null) for (JsonValue c = cn.child; c != null; c = c.next) ch.put(c.name(), c.asInt());
      return buildIncreasedChancesEvent(id, ch, start, end);
    }
    if ("TRADER_DISCOUNT".equals(kind) || "TRADER_REFRESH_DISCOUNT".equals(kind)) {
      List<com.perblue.heroes.network.messages.MerchantType> mrch = new ArrayList<>();
      JsonValue mn = spec.get("merchants");
      if (mn != null) for (JsonValue mm = mn.child; mm != null; mm = mm.next)
        mrch.add(com.perblue.heroes.network.messages.MerchantType.valueOf(mm.asString()));
      int pct = spec.getInt("percentOff", 50);
      return "TRADER_DISCOUNT".equals(kind)
          ? buildMerchantDiscountEvent(id, mrch, pct, start, end)
          : buildMerchantRefreshDiscountEvent(id, mrch, pct, start, end);
    }
    if ("FREE_STUFF_AT_TEAM_LEVEL".equals(kind) || "FREE_STUFF_EVERY_X_TEAM_LEVEL".equals(kind)) {
      java.util.List<String> drops = new ArrayList<>();
      JsonValue dn = spec.get("drops");
      if (dn != null) for (JsonValue d = dn.child; d != null; d = d.next)
        drops.add("{\"kind\":\"ITEM\",\"itemType\":\"" + d.getString("item") + "\",\"quantity\":" + d.getInt("qty", 1) + "}");
      return buildTeamLevelEvent(id, spec.getInt("teamLevel", 50), drops, "FREE_STUFF_EVERY_X_TEAM_LEVEL".equals(kind), start, end);
    }
    if ("FLAG_USER_ON_LOGIN".equals(kind)) {
      List<com.perblue.heroes.game.objects.UserFlag> setF = new ArrayList<>(), clearF = new ArrayList<>();
      JsonValue sn = spec.get("set");
      if (sn != null) for (JsonValue f = sn.child; f != null; f = f.next) setF.add(com.perblue.heroes.game.objects.UserFlag.valueOf(f.asString()));
      JsonValue cn = spec.get("clear");
      if (cn != null) for (JsonValue f = cn.child; f != null; f = f.next) clearF.add(com.perblue.heroes.game.objects.UserFlag.valueOf(f.asString()));
      return buildFlagUserOnLoginEvent(id, setF, clearF, start, end);
    }
    if ("MISC_BONUS".equals(kind) || "MISC_DISCOUNT".equals(kind)) {
      List<com.perblue.heroes.game.specialevent.MultiplierType> mults = new ArrayList<>();
      JsonValue tn = spec.get("mults");
      if (tn != null) for (JsonValue t = tn.child; t != null; t = t.next)
        mults.add(com.perblue.heroes.game.specialevent.MultiplierType.valueOf(t.asString()));
      int val = spec.getInt("value", 50);
      return "MISC_BONUS".equals(kind)
          ? buildMiscBonusEvent(id, mults, val, start, end)
          : buildMiscDiscountEvent(id, mults, val, start, end);
    }
    if ("CONTEST".equals(kind)) {
      List<ContestTask> tasks = new ArrayList<>();
      JsonValue tn = spec.get("tasks");
      if (tn != null) for (JsonValue t = tn.child; t != null; t = t.next)
        tasks.add(new ContestTask(t.getString("type", "BATTLE_WON"), t.getInt("points", 10), t.getInt("countNeeded", 1),
            t.getInt("maxTimes", -1), t.getInt("maxDailyTimes", -1), t.getString("taskData", ""), t.getString("taskData2", "")));
      List<ContestProgress> progress = new ArrayList<>();
      JsonValue pn = spec.get("progress");
      if (pn != null) for (JsonValue p = pn.child; p != null; p = p.next)
        progress.add(new ContestProgress(p.getLong("points", 100L), contestDropsFromSpec(p.get("drops"))));
      List<ContestRank> ranks = new ArrayList<>();
      JsonValue rn = spec.get("ranks");
      if (rn != null) for (JsonValue r = rn.child; r != null; r = r.next)
        ranks.add(new ContestRank(r.getBoolean("percent", true), r.getInt("rank", 10), contestDropsFromSpec(r.get("drops"))));
      return buildContestEvent(id, spec.getBoolean("guild", false), spec.getBoolean("aggregate", false),
          spec.getString("title", "Contest"), spec.getString("summary", "Complete tasks to earn points and rewards!"),
          tasks, progress, ranks, start, end);
    }
    if ("EXTRA_CHEST".equals(kind)) {
      List<ChestDrop> drops = new ArrayList<>();
      JsonValue dn = spec.get("drops");
      if (dn != null) for (JsonValue d = dn.child; d != null; d = d.next)
        drops.add(new ChestDrop(d.getString("result"), d.getString("quantity", "1"), d.getInt("weight", 1)));
      com.perblue.heroes.network.messages.ResourceType cur =
          com.perblue.heroes.network.messages.ResourceType.valueOf(spec.getString("currency", "DIAMONDS"));
      return buildExtraChestEvent(id, spec.getInt("cost", 100), cur, spec.getInt("buyXNumber", 10),
          spec.getInt("maxBuys", 50), spec.getInt("maxPurchases", 5), spec.getInt("freeBuys", 0),
          spec.getBoolean("featured", true), spec.getString("title", "Event Crate"),
          spec.getString("info", "Limited-time bonus crate!"), drops, spec.getInt("draws", 1), start, end);
    }
    return buildModesOpenEvent(id, modes, start, end);
  }

  /**
   * Retourne l'event TRIAL FRANCHISE actif (installé dans {@code OPERATOR_EVENTS}) d'`eventID` donné, ou {@code null}. Sert à
   * ce que le SERVEUR rejoue le combat sur EXACTEMENT le même event que le client (mêmes chances/rewards admin) — cohérence
   * serveur-autoritative (évite un reconstruction avec des params par défaut divergents).
   */
  public static SpecialEventInfo activeTrialEvent(long eventID) {
    for (SpecialEventInfo e : OPERATOR_EVENTS) {
      try {
        if (e.getID() == eventID
            && e.getComponent((Class) Class.forName("com.perblue.heroes.game.specialevent.TrialEventInfo")) != null) return e;
      } catch (Exception ignore) {}
    }
    return null;
  }

  /** Liste des specs (JsonValue) d'une config persistée (vide si null). */
  public static List<JsonValue> configSpecs(byte[] configBlob) {
    List<JsonValue> out = new ArrayList<>();
    if (configBlob == null || configBlob.length == 0) return out;
    JsonValue root = JSON.parse(new String(configBlob, java.nio.charset.StandardCharsets.UTF_8));
    JsonValue specs = root.get("specs");
    if (specs != null) for (JsonValue s = specs.child; s != null; s = s.next) out.add(s);
    return out;
  }

  /** Sérialise une liste de specs (chaînes JSON d'objet) en octets de config persistable. */
  public static byte[] writeConfig(List<String> specJsons) {
    StringBuilder sb = new StringBuilder("{\"specs\":[");
    for (int i = 0; i < specJsons.size(); i++) { if (i > 0) sb.append(','); sb.append(specJsons.get(i)); }
    sb.append("]}");
    return sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
  }

  /** Construit la chaîne JSON d'UNE spec d'event TRIAL FRANCHISE (id=eventID ; chances/title/trial = params admin ; trial = index du trial de saison). */
  public static String specJsonTrialFranchise(long id, long start, long end, int chances, String title, int trialIndex, int modifiersPerNode) {
    String t = (title == null ? DEFAULT_TRIAL_TITLE : title).replace("\\", "\\\\").replace("\"", "\\\"");
    return "{\"kind\":\"TRIAL_FRANCHISE\",\"modes\":[],\"bonus\":0,\"id\":" + id
        + ",\"chances\":" + chances + ",\"title\":\"" + t + "\",\"trial\":" + trialIndex
        + ",\"modifiers\":" + modifiersPerNode + ",\"start\":" + start + ",\"end\":" + end + "}";
  }

  /** Construit la chaîne JSON d'UNE spec CHEST_DISCOUNT (coffres visés + pourcentage de remise = params ADMIN). */
  public static String specJsonChestDiscount(long id, Collection<com.perblue.heroes.network.messages.ChestType> chests,
      int percentOff, long start, long end) {
    StringBuilder c = new StringBuilder();
    for (com.perblue.heroes.network.messages.ChestType ct : chests) { if (c.length() > 0) c.append(','); c.append('"').append(ct.name()).append('"'); }
    return "{\"kind\":\"CHEST_DISCOUNT\",\"modes\":[],\"bonus\":0,\"id\":" + id
        + ",\"chests\":[" + c + "],\"percentOff\":" + percentOff
        + ",\"start\":" + start + ",\"end\":" + end + "}";
  }

  /**
   * Construit la chaîne JSON d'UNE spec EXTRA_CHEST (coffre bonus CRATES). Params ADMIN : {@code cost}, {@code currency},
   * {@code buyXNumber}, {@code maxBuys}, {@code maxPurchases}, {@code freeBuys}, {@code featured}, titres, {@code draws} et
   * la liste de drops ({@code result}/{@code quantity}/{@code weight}). La table est reconstruite par {@link #buildExtraChestEvent}.
   */
  public static String specJsonExtraChest(long id, int cost, com.perblue.heroes.network.messages.ResourceType currency,
      int buyXNumber, int maxBuys, int maxPurchases, int freeBuys, boolean featured, String title, String info,
      List<ChestDrop> drops, int draws, long start, long end) {
    StringBuilder d = new StringBuilder();
    for (ChestDrop cd : drops) {
      if (d.length() > 0) d.append(',');
      d.append("{\"result\":\"").append(esc(cd.result)).append("\",\"quantity\":\"").append(esc(cd.quantity))
       .append("\",\"weight\":").append(cd.weight).append('}');
    }
    return "{\"kind\":\"EXTRA_CHEST\",\"modes\":[],\"bonus\":0,\"id\":" + id
        + ",\"cost\":" + cost + ",\"currency\":\"" + currency.name() + "\",\"buyXNumber\":" + buyXNumber
        + ",\"maxBuys\":" + maxBuys + ",\"maxPurchases\":" + maxPurchases + ",\"freeBuys\":" + freeBuys
        + ",\"featured\":" + featured + ",\"title\":\"" + esc(title) + "\",\"info\":\"" + esc(info)
        + "\",\"draws\":" + draws + ",\"drops\":[" + d + "],\"start\":" + start + ",\"end\":" + end + "}";
  }

  /** Convertit les drops JSON du jeu ({@code {kind,itemType|unitType,quantity}}) en drops de spec ({@code {item|unit,qty}}). */
  private static String dropSpecFrom(List<String> gameDrops) {
    StringBuilder sb = new StringBuilder("[");
    for (int i = 0; i < gameDrops.size(); i++) {
      if (i > 0) sb.append(',');
      JsonValue d = JSON.parse(gameDrops.get(i));
      int qty = d.getInt("quantity", 1);
      if ("UNIT".equals(d.getString("kind", "ITEM"))) sb.append("{\"unit\":\"").append(d.getString("unitType")).append("\",\"qty\":").append(qty).append('}');
      else sb.append("{\"item\":\"").append(d.getString("itemType", "ACE_OF_SPADES")).append("\",\"qty\":").append(qty).append('}');
    }
    return sb.append(']').toString();
  }

  /**
   * Construit la chaîne JSON d'UNE spec CONTEST (params ADMIN : guild/aggregate + tâches + paliers de progression +
   * récompenses de rang). Symétrique de {@link #buildContestEvent} (round-trip via {@code eventFromSpec}).
   */
  public static String specJsonContest(long id, boolean guild, boolean aggregate, String title, String summary,
      List<ContestTask> tasks, List<ContestProgress> progress, List<ContestRank> ranks, long start, long end) {
    StringBuilder tk = new StringBuilder();
    for (ContestTask t : tasks) {
      if (tk.length() > 0) tk.append(',');
      tk.append("{\"type\":\"").append(t.type).append("\",\"points\":").append(t.points).append(",\"countNeeded\":").append(t.countNeeded)
        .append(",\"maxTimes\":").append(t.maxTimes).append(",\"maxDailyTimes\":").append(t.maxDailyTimes)
        .append(",\"taskData\":\"").append(esc(t.taskData)).append("\",\"taskData2\":\"").append(esc(t.taskData2)).append("\"}");
    }
    StringBuilder pr = new StringBuilder();
    for (ContestProgress p : progress) {
      if (pr.length() > 0) pr.append(',');
      pr.append("{\"points\":").append(p.pointsRequired).append(",\"drops\":").append(dropSpecFrom(p.rewardDrops)).append('}');
    }
    StringBuilder rr = new StringBuilder();
    for (ContestRank r : ranks) {
      if (rr.length() > 0) rr.append(',');
      rr.append("{\"percent\":").append(r.percent).append(",\"rank\":").append(r.rank).append(",\"drops\":").append(dropSpecFrom(r.rewardDrops)).append('}');
    }
    return "{\"kind\":\"CONTEST\",\"modes\":[],\"bonus\":0,\"id\":" + id + ",\"guild\":" + guild + ",\"aggregate\":" + aggregate
        + ",\"title\":\"" + esc(title) + "\",\"summary\":\"" + esc(summary) + "\""
        + ",\"tasks\":[" + tk + "],\"progress\":[" + pr + "],\"ranks\":[" + rr + "],\"start\":" + start + ",\"end\":" + end + "}";
  }

  /** Construit la chaîne JSON d'UNE spec INCREASED_CHANCES (chanceType → nombre de chances en plus = params ADMIN). */
  public static String specJsonIncreasedChances(long id, java.util.Map<String, Integer> chances, long start, long end) {
    StringBuilder c = new StringBuilder();
    for (java.util.Map.Entry<String, Integer> e : chances.entrySet()) {
      if (c.length() > 0) c.append(',');
      c.append('"').append(e.getKey()).append("\":").append(e.getValue());
    }
    return "{\"kind\":\"INCREASED_CHANCES\",\"modes\":[],\"bonus\":0,\"id\":" + id
        + ",\"chances\":{" + c + "},\"start\":" + start + ",\"end\":" + end + "}";
  }

  /** Construit la chaîne JSON d'UNE spec TRADER_DISCOUNT/TRADER_REFRESH_DISCOUNT (marchands visés + % = params ADMIN). */
  public static String specJsonMerchant(String kind, long id, Collection<com.perblue.heroes.network.messages.MerchantType> merchants,
      int percentOff, long start, long end) {
    StringBuilder m = new StringBuilder();
    for (com.perblue.heroes.network.messages.MerchantType mt : merchants) { if (m.length() > 0) m.append(','); m.append('"').append(mt.name()).append('"'); }
    return "{\"kind\":\"" + kind + "\",\"modes\":[],\"bonus\":0,\"id\":" + id
        + ",\"merchants\":[" + m + "],\"percentOff\":" + percentOff + ",\"start\":" + start + ",\"end\":" + end + "}";
  }

  /** Construit la chaîne JSON d'UNE spec MISC_BONUS/MISC_DISCOUNT (MultiplierType visés + valeur % = params ADMIN). */
  public static String specJsonMisc(String kind, long id, Collection<com.perblue.heroes.game.specialevent.MultiplierType> mults,
      int value, long start, long end) {
    StringBuilder m = new StringBuilder();
    for (com.perblue.heroes.game.specialevent.MultiplierType mt : mults) { if (m.length() > 0) m.append(','); m.append('"').append(mt.name()).append('"'); }
    return "{\"kind\":\"" + kind + "\",\"modes\":[],\"bonus\":0,\"id\":" + id
        + ",\"mults\":[" + m + "],\"value\":" + value + ",\"start\":" + start + ",\"end\":" + end + "}";
  }

  /** Construit la chaîne JSON d'UNE spec FLAG_USER_ON_LOGIN (flags à poser/retirer au login = params ADMIN). */
  public static String specJsonFlagUserOnLogin(long id, Collection<com.perblue.heroes.game.objects.UserFlag> setF,
      Collection<com.perblue.heroes.game.objects.UserFlag> clearF, long start, long end) {
    StringBuilder s = new StringBuilder(), c = new StringBuilder();
    for (com.perblue.heroes.game.objects.UserFlag f : setF) { if (s.length() > 0) s.append(','); s.append('"').append(f.name()).append('"'); }
    for (com.perblue.heroes.game.objects.UserFlag f : clearF) { if (c.length() > 0) c.append(','); c.append('"').append(f.name()).append('"'); }
    return "{\"kind\":\"FLAG_USER_ON_LOGIN\",\"modes\":[],\"bonus\":0,\"id\":" + id
        + ",\"set\":[" + s + "],\"clear\":[" + c + "],\"start\":" + start + ",\"end\":" + end + "}";
  }

  /** Construit la chaîne JSON d'UNE spec TEAM LEVEL (niveau + drops item:qty = params ADMIN ; everyX → tous les X niveaux). */
  public static String specJsonTeamLevel(long id, int teamLevel, boolean everyX,
      java.util.List<String> itemTypes, java.util.List<Integer> quantities, long start, long end) {
    StringBuilder d = new StringBuilder();
    for (int i = 0; i < itemTypes.size(); i++) {
      if (d.length() > 0) d.append(',');
      d.append("{\"item\":\"").append(itemTypes.get(i)).append("\",\"qty\":").append(i < quantities.size() ? quantities.get(i) : 1).append('}');
    }
    return "{\"kind\":\"" + (everyX ? "FREE_STUFF_EVERY_X_TEAM_LEVEL" : "FREE_STUFF_AT_TEAM_LEVEL") + "\",\"modes\":[],\"bonus\":0,\"id\":" + id
        + ",\"teamLevel\":" + teamLevel + ",\"drops\":[" + d + "],\"start\":" + start + ",\"end\":" + end + "}";
  }

  /** Construit la chaîne JSON d'UNE spec d'override opérateur. */
  public static String specJson(String kind, Collection<GameMode> modes, int bonus, long start, long end) {
    StringBuilder m = new StringBuilder();
    for (GameMode g : modes) { if (m.length() > 0) m.append(','); m.append('"').append(g.name()).append('"'); }
    return "{\"kind\":\"" + kind + "\",\"modes\":[" + m + "],\"bonus\":" + bonus
        + ",\"start\":" + start + ",\"end\":" + end + "}";
  }

  /**
   * Installe une liste d'événements opérateur dans la machinerie du jeu ({@code SpecialEventsHelper}) : le serveur
   * autoritatif voit alors leurs effets ({@code isModeOpen}, snapshots…). On remplace la référence globale
   * {@code SPECIAL_EVENTS} et on invalide le cache de snapshot — le {@code snapshotWithoutRefresh()} suivant les prend.
   */
  public static void install(List<SpecialEventInfo> events) {
    try {
      Object helper = staticField(com.perblue.heroes.game.logic.SpecialEventsHelper.class, "helper");
      if (helper == null) return;   // couche événements non initialisée (SpecialEventsHelper.init non appelé)
      SpecialEvents se = new SpecialEvents();
      se.setEvents(new ArrayList<>(events));
      ((AtomicReference<Object>) instanceField(helper, "SPECIAL_EVENTS")).set(se);
      // Force la RECONSTRUCTION du snapshot depuis ces événements : (a) invalide le cache, (b) remet lastSnapshotTime=0
      // (le snapshot est mémoïsé par temps), (c) refresh(true). Sinon le snapshot périmé ne refléterait pas l'installation.
      AtomicReference<Object> snapCache = (AtomicReference<Object>) instanceField(helper, "SNAPSHOT_CACHE");
      if (snapCache != null) snapCache.set(null);
      try { Field lst = findField(helper.getClass(), "lastSnapshotTime"); lst.setAccessible(true); lst.setLong(helper, 0L); } catch (Throwable ignore) {}
      Method refresh = findMethod(helper.getClass(), "refresh", boolean.class);
      refresh.setAccessible(true); refresh.invoke(helper, true);
    } catch (Exception e) {
      System.out.println("[events] install échec : " + e);
    }
  }

  /**
   * Installe les événements OPÉRATEUR courants (appelé après {@code setSpecialEvents} dans {@code ServerContext.bind}).
   * Défaut = VIDE → aucune ouverture forcée → le jeu applique sa <b>rotation par défaut</b> ({@code getOpenDays}). Un
   * opérateur peut ajouter des overrides (via {@code AdminEvents}, chargés au boot par {@code LoginServer}).
   */
  public static void installBootDefaults() {
    install(operatorEvents());
  }

  /** Snapshot courant de la couche événements (sans refresh UI, sûr headless) — à passer aux checks serveur. */
  public static com.perblue.heroes.game.specialevent.SpecialEventSnapshot snapshot() {
    return com.perblue.heroes.game.logic.SpecialEventsHelper.snapshotWithoutRefresh();
  }

  /**
   * Snapshot de la couche événements ANCRÉ à une heure choisie (mêmes events installés, {@code snapshotTime}=time). Utilise
   * l'état brut courant ({@code snapshotRaw}) + le constructeur DU JEU {@code SpecialEventSnapshot(state, time)} (§3). Sert aux
   * tests DÉTERMINISTES de la rotation par jour : {@code DifficultyModeHelper.isOpen} calcule le jour depuis
   * {@code snapshot.snapshotTime} (fait §8) — figer le temps rend le jour indépendant du calendrier réel.
   */
  public static com.perblue.heroes.game.specialevent.SpecialEventSnapshot snapshotAt(long time) {
    return new com.perblue.heroes.game.specialevent.SpecialEventSnapshot(
        com.perblue.heroes.game.logic.SpecialEventsHelper.snapshotRaw(), time);
  }

  // --- réflexion (couche plateforme §3, comme l'alloc sans ctor de GameMain) ---
  private static void setField(Object o, String name, Object v) throws Exception {
    Field f = findField(o.getClass(), name); f.setAccessible(true); f.set(o, v);
  }
  private static void addComponent(SpecialEventInfo info, IEventComponent c) throws Exception {
    Method m = SpecialEventInfo.class.getDeclaredMethod("addComponent", IEventComponent.class);
    m.setAccessible(true); m.invoke(info, c);
  }
  private static Object staticField(Class<?> c, String name) throws Exception {
    Field f = c.getDeclaredField(name); f.setAccessible(true); return f.get(null);
  }
  private static Object instanceField(Object o, String name) throws Exception {
    Field f = findField(o.getClass(), name); f.setAccessible(true); return f.get(o);
  }
  private static Field findField(Class<?> c, String name) {
    for (; c != null; c = c.getSuperclass()) {
      try { return c.getDeclaredField(name); } catch (NoSuchFieldException ignore) {}
    }
    throw new RuntimeException("champ introuvable : " + name);
  }
  private static Method findMethod(Class<?> c, String name, Class<?>... args) {
    for (; c != null; c = c.getSuperclass()) {
      try { return c.getDeclaredMethod(name, args); } catch (NoSuchMethodException ignore) {}
    }
    throw new RuntimeException("méthode introuvable : " + name);
  }
}
