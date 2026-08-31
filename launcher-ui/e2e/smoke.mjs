// E2E smoke : pilote le VRAI front (Vite) contre un VRAI daemon (proxy Vite) avec Chromium pré-installé.
// Prouve le câblage UI ↔ endpoints réels : DisclaimerGate → Serveurs (POST/GET /servers) → Compte (/identity/generate).
import { chromium } from "playwright";

const CHROME = process.env.DH_CHROME || "/opt/pw-browsers/chromium-1194/chrome-linux/chrome";
const URL = process.env.DH_URL || "http://localhost:1420";

const browser = await chromium.launch({ executablePath: CHROME, args: ["--no-sandbox"] });
const page = await browser.newPage();
const fails = [];
const ok = (c, m) => { if (c) console.log("  ✓ " + m); else { fails.push(m); console.log("  ✗ " + m); } };

try {
  await page.goto(URL, { waitUntil: "domcontentloaded" });

  // 1) DisclaimerGate présent (attend le health du daemon d'abord)
  await page.waitForSelector(".gate", { timeout: 20000 });
  ok(true, "DisclaimerGate affiché (daemon health OK)");

  // accepter est bloqué tant que non défilé + non coché
  const acceptBtn = page.getByRole("button", { name: /accepte/i });
  ok(await acceptBtn.isDisabled(), "bouton Accepter désactivé au départ");
  await page.evaluate(() => { const el = document.querySelector(".gate .scroll"); if (el) el.scrollTop = el.scrollHeight; });
  await page.locator(".gate input[type=checkbox]").check();
  await acceptBtn.click();

  // 2) Serveurs : ajouter localhost → apparaît (POST /servers puis GET /servers)
  await page.waitForSelector("text=Ajouter un serveur", { timeout: 10000 });
  await page.getByRole("button", { name: /localhost/i }).click();
  await page.waitForSelector("text=127.0.0.1:8080", { timeout: 10000 });
  ok(true, "serveur localhost ajouté via le daemon (/servers) et affiché");

  // sélectionner le serveur
  await page.locator('input[name="srv"]').first().check();

  // 3) Compte : Générer une phrase (/identity/generate) → 8 mots affichés
  await page.getByRole("button", { name: /^Compte$/ }).click();
  await page.getByRole("button", { name: /Générer une phrase/i }).click();
  await page.waitForSelector("text=/aucune récupération/i", { timeout: 10000 });
  const wordCells = await page.locator(".panel strong").count();
  ok(wordCells >= 8, `phrase générée via /identity/generate (${wordCells} éléments mot)`);

  // 4) Héberger : la vue rend + poll /host/status (serveur arrêté)
  await page.locator("button.nav-item", { hasText: "Héberger" }).click();
  await page.waitForSelector("text=État du serveur", { timeout: 10000 });
  await page.waitForSelector("text=arrêté", { timeout: 10000 });
  ok(true, "écran Héberger : /host/status poll → « arrêté »");

  // 5) Générer : la vue rend (cibles Serveur/Client uniquement, pas d'APK)
  await page.locator("button.nav-item", { hasText: "Générer" }).click();
  await page.waitForSelector("text=Générer depuis l'APK", { timeout: 10000 });
  const options = await page.locator("select option").allInnerTexts();
  ok(!options.some((o) => /apk/i.test(o)), "écran Générer : aucune cible APK proposée (non implémenté)");

  // 6) Jouer : gating honnête (non connecté → invite à se connecter, pas de bouton JOUER trompeur)
  await page.locator("button.nav-item", { hasText: "Jouer" }).click();
  await page.waitForSelector("text=/Connecte-toi d'abord/i", { timeout: 10000 });
  ok((await page.getByRole("button", { name: /^JOUER$/ }).count()) === 0, "écran Jouer : pas de bouton JOUER sans session (gating)");

  // 7) Réglages : changer la langue (/settings persisté) → i18n appliqué (nav passe en anglais)
  await page.locator("button.nav-item", { hasText: "Réglages" }).click();
  await page.waitForSelector("text=Langue", { timeout: 10000 });
  await page.selectOption(".panel select", "en");
  await page.waitForSelector("button.nav-item:has-text('Play')", { timeout: 10000 });
  ok(true, "Réglages : langue → EN via /settings + i18n appliqué à la nav");
} catch (e) {
  fails.push("exception: " + e.message);
  console.log("  ✗ exception:", e.message);
} finally {
  await browser.close();
}

console.log(`\nE2E smoke : ${fails.length === 0 ? "OK" : "ÉCHEC (" + fails.length + ")"}`);
process.exit(fails.length === 0 ? 0 : 1);
