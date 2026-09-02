#!/usr/bin/env node
// cdp_drive.mjs — PILOTE CDP COMPLET du launcher (app Tauri → WebView2 sous Windows, Chromium partout).
//
// But (demande util.) : contrôler l'app POUR DE VRAI — DOM réel, vrais clics/frappes, console, captures —
// sur le VRAI exe packagé (donc le VRAI pont Tauri↔daemon), sans capture d'écran ni proxy de dev.
//
// Prérequis : lancer l'app avec le port de débogage CDP ouvert (tools/dh-debug-launch.ps1, ou en posant
//   WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS="--remote-debugging-port=9222" avant DisneyHeroesLauncher.exe).
//   WebView2 étant Chromium, il expose alors http://127.0.0.1:9222/json (protocole DevTools).
// Dépendances : AUCUNE — Node ≥ 18 (fetch global) ; WebSocket global (Node ≥ 21 ; ici Node 22 embarqué par la CI).
//
// Usage :
//   node tools/cdp_drive.mjs <commande> [args...] [--port 9222] [--url <sous-chaîne>] [--timeout 15000] [--json]
//
// Commandes :
//   targets                      liste les cibles « page » (id, titre, url)
//   eval "<js>"                   évalue du JS dans la page, imprime le résultat (await supporté)
//   click "<sélecteur>"           VRAI clic souris au centre de l'élément (Input.dispatchMouseEvent)
//   clicktext "<texte visible>"   trouve un bouton/lien/onglet visible contenant ce texte, puis le clique
//                                 (robuste quand on n'a pas les sélecteurs exacts de l'UI)
//   type "<sélecteur>" "<texte>"  focus + saisie compatible React (setter natif + events input/change)
//   press "<sélecteur>" <Key>     touche clavier réelle sur l'élément (ex. Enter, Tab)
//   text "<sélecteur>"            imprime innerText de l'élément
//   attr "<sélecteur>" <nom>      imprime un attribut/propriété
//   wait "<sélecteur>" [visible]  attend l'apparition (et la visibilité si "visible") jusqu'au timeout
//   exists "<sélecteur>"          exit 0 si présent, 1 sinon
//   html [sélecteur]              outerHTML de l'élément (ou de <body>)
//   dump                          arbre lisible des éléments interactifs (boutons/liens/inputs + textes)
//   console [ms]                  écoute console + exceptions + logs pendant ms (défaut 8000), imprime tout
//   shot <fichier.png>            capture d'écran de la page (PNG)
//   nav <url>                     navigue la WebView vers une URL
//   reload                        recharge la page
//   repl                          lit stdin ligne par ligne, évalue chaque ligne (fin = Ctrl-D)
//
// Sortie : lisible par défaut ; --json pour du JSON brut (scriptable). Codes de sortie non nuls sur échec réel.

const args = process.argv.slice(2);
const opt = (name, def) => { const i = args.indexOf(name); return i >= 0 ? args[i + 1] : def; };
const flag = (name) => args.includes(name);
const PORT = parseInt(opt("--port", process.env.DH_CDP_PORT || "9222"), 10);
const URLFILTER = opt("--url", null);
const TIMEOUT = parseInt(opt("--timeout", "15000"), 10);
const JSONOUT = flag("--json");
// arguments positionnels (hors options)
const pos = [];
for (let i = 0; i < args.length; i++) {
  if (args[i].startsWith("--")) { if (!["--json"].includes(args[i])) i++; continue; }
  pos.push(args[i]);
}
const cmd = pos[0];

function die(msg, code = 1) { console.error("cdp_drive: " + msg); process.exit(code); }
function out(v) { if (JSONOUT) console.log(JSON.stringify(v, null, 2)); else console.log(typeof v === "string" ? v : JSON.stringify(v, null, 2)); }

async function pickTarget() {
  let list;
  try {
    const r = await fetch(`http://127.0.0.1:${PORT}/json`, { signal: AbortSignal.timeout(4000) });
    list = await r.json();
  } catch (e) {
    die(`impossible de joindre le débogueur CDP sur 127.0.0.1:${PORT} (${e.message}).\n` +
        `→ lance l'app avec le port ouvert : tools/dh-debug-launch.ps1  (ou WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS="--remote-debugging-port=${PORT}")`);
  }
  let pages = list.filter(t => t.type === "page" && t.webSocketDebuggerUrl &&
                               !(t.url || "").startsWith("devtools://"));
  if (URLFILTER) pages = pages.filter(t => (t.url || "").includes(URLFILTER) || (t.title || "").includes(URLFILTER));
  if (pages.length === 0) die("aucune cible « page » trouvée" + (URLFILTER ? ` pour --url ${URLFILTER}` : "") +
                              `. Cibles vues : ${list.map(t => `${t.type}:${t.url}`).join(", ") || "(aucune)"}`);
  // Préfère une page dont l'URL ressemble à l'app (tauri/localhost/index) plutôt qu'une about:blank.
  pages.sort((a, b) => score(b) - score(a));
  return pages[0];
}
function score(t) { const u = (t.url || ""); let s = 0; if (/tauri|localhost|index\.html|127\.0\.0\.1/.test(u)) s += 2; if (u && u !== "about:blank") s += 1; return s; }

class CDP {
  constructor(wsUrl) { this.ws = new WebSocket(wsUrl); this.id = 0; this.pending = new Map(); this.listeners = []; this.ready = new Promise((res, rej) => { this.ws.onopen = res; this.ws.onerror = (e) => rej(new Error("WS error")); }); this.ws.onmessage = (ev) => this._onmsg(ev); }
  _onmsg(ev) {
    const m = JSON.parse(ev.data);
    if (m.id != null && this.pending.has(m.id)) { const { res, rej } = this.pending.get(m.id); this.pending.delete(m.id); m.error ? rej(new Error(m.error.message + (m.error.data ? " — " + m.error.data : ""))) : res(m.result); }
    else if (m.method) for (const l of this.listeners) l(m);
  }
  send(method, params = {}) { const id = ++this.id; return new Promise((res, rej) => { this.pending.set(id, { res, rej }); this.ws.send(JSON.stringify({ id, method, params })); setTimeout(() => { if (this.pending.has(id)) { this.pending.delete(id); rej(new Error(`timeout ${method}`)); } }, TIMEOUT); }); }
  on(fn) { this.listeners.push(fn); }
  close() { try { this.ws.close(); } catch {} }
  // Évalue une expression et renvoie la valeur JSON (déballe les Promises via awaitPromise).
  async eval(expr, { awaitPromise = true, returnByValue = true } = {}) {
    const r = await this.send("Runtime.evaluate", { expression: expr, awaitPromise, returnByValue, userGesture: true });
    if (r.exceptionDetails) throw new Error("JS: " + (r.exceptionDetails.exception?.description || r.exceptionDetails.text));
    return r.result?.value;
  }
}

const jstr = (s) => JSON.stringify(String(s)); // littéral JS sûr

async function main() {
  if (!cmd || cmd === "help" || cmd === "--help") { printHelp(); process.exit(0); }
  if (cmd === "targets") {
    const r = await fetch(`http://127.0.0.1:${PORT}/json`).then(x => x.json()).catch(e => die("CDP injoignable: " + e.message));
    const pages = r.filter(t => t.type === "page");
    if (JSONOUT) return out(pages);
    for (const t of pages) console.log(`${t.id}  «${t.title}»  ${t.url}`);
    return;
  }
  const tgt = await pickTarget();
  const cdp = new CDP(tgt.webSocketDebuggerUrl);
  await cdp.ready;
  await cdp.send("Runtime.enable").catch(() => {});
  try {
    switch (cmd) {
      case "eval": { if (!pos[1]) die("eval : expression manquante"); out(await cdp.eval(pos[1])); break; }
      case "text": { out(await cdp.eval(`(document.querySelector(${jstr(pos[1])})||{}).innerText ?? null`)); break; }
      case "attr": { out(await cdp.eval(`(()=>{const e=document.querySelector(${jstr(pos[1])});return e?(e.getAttribute(${jstr(pos[2])})??e[${jstr(pos[2])}]??null):null;})()`)); break; }
      case "exists": { const e = await cdp.eval(`!!document.querySelector(${jstr(pos[1])})`); if (!JSONOUT) console.log(e ? "présent" : "absent"); process.exitCode = e ? 0 : 1; break; }
      case "html": { out(await cdp.eval(`(document.querySelector(${jstr(pos[1] || "body")})||{}).outerHTML ?? null`)); break; }
      case "wait": { await waitFor(cdp, pos[1], pos[2] === "visible"); out(`ok: ${pos[1]}`); break; }
      case "dump": { out(await cdp.eval(DUMP_JS)); break; }
      case "click": { await clickSel(cdp, pos[1]); out(`cliqué: ${pos[1]}`); break; }
      case "clicktext": { const sel = await resolveByText(cdp, pos[1]); await clickSel(cdp, sel); out(`cliqué (texte «${pos[1]}»): ${sel}`); break; }
      case "type": { await typeSel(cdp, pos[1], pos[2] ?? ""); out(`saisi dans ${pos[1]}: ${jstr(pos[2] ?? "")}`); break; }
      case "press": { await pressKey(cdp, pos[1], pos[2] || "Enter"); out(`touche ${pos[2]} sur ${pos[1]}`); break; }
      case "console": { await streamConsole(cdp, parseInt(pos[1] || "8000", 10)); break; }
      case "shot": { await screenshot(cdp, pos[1] || "cdp-shot.png"); break; }
      case "nav": { await cdp.send("Page.enable").catch(() => {}); await cdp.send("Page.navigate", { url: pos[1] }); out(`nav → ${pos[1]}`); break; }
      case "reload": { await cdp.send("Page.enable").catch(() => {}); await cdp.send("Page.reload", {}); out("reload"); break; }
      case "repl": { await repl(cdp); break; }
      default: die(`commande inconnue: ${cmd} (voir --help)`);
    }
  } finally { if (cmd !== "console" && cmd !== "repl") cdp.close(); }
}

async function waitFor(cdp, sel, visible) {
  const start = Date.now();
  while (Date.now() - start < TIMEOUT) {
    const ok = await cdp.eval(`(()=>{const e=document.querySelector(${jstr(sel)});if(!e)return false;if(${visible?1:0}){const r=e.getBoundingClientRect();return r.width>0&&r.height>0&&getComputedStyle(e).visibility!=='hidden';}return true;})()`);
    if (ok) return;
    await new Promise(r => setTimeout(r, 150));
  }
  die(`timeout (${TIMEOUT}ms) en attente de ${sel}`);
}
async function centerOf(cdp, sel) {
  const rect = await cdp.eval(`(()=>{const e=document.querySelector(${jstr(sel)});if(!e)return null;e.scrollIntoView({block:'center',inline:'center'});const r=e.getBoundingClientRect();return {x:r.left+r.width/2,y:r.top+r.height/2,w:r.width,h:r.height};})()`);
  if (!rect || rect.w === 0) die(`élément introuvable ou invisible: ${sel}`);
  return rect;
}
// Trouve un élément cliquable par TEXTE VISIBLE (bouton/lien/onglet/[role=button]) et pose un data-attr
// temporaire dessus pour renvoyer un sélecteur stable — robuste pour piloter une UI dont on n'a pas les sélecteurs.
async function resolveByText(cdp, substr) {
  const marker = "__dhcdp" + Date.now();
  const found = await cdp.eval(`(()=>{const s=${jstr(String(substr).toLowerCase())};const els=[...document.querySelectorAll('button,a,[role=button],[role=tab],[role=menuitem],input[type=button],input[type=submit],label')];const hit=els.find(e=>{const r=e.getBoundingClientRect();const t=(e.innerText||e.value||e.getAttribute('aria-label')||'').trim().toLowerCase();return r.width>0&&r.height>0&&t.includes(s);});if(!hit)return null;hit.setAttribute('data-dhcdp',${jstr(marker)});return true;})()`);
  if (!found) die(`aucun élément cliquable visible ne contient le texte « ${substr} »  (essaie: node tools/cdp_drive.mjs dump)`);
  return `[data-dhcdp="${marker}"]`;
}
async function clickSel(cdp, sel) {
  const c = await centerOf(cdp, sel);
  await cdp.send("Input.dispatchMouseEvent", { type: "mouseMoved", x: c.x, y: c.y });
  await cdp.send("Input.dispatchMouseEvent", { type: "mousePressed", x: c.x, y: c.y, button: "left", buttons: 1, clickCount: 1 });
  await cdp.send("Input.dispatchMouseEvent", { type: "mouseReleased", x: c.x, y: c.y, button: "left", buttons: 1, clickCount: 1 });
}
async function typeSel(cdp, sel, text) {
  // Compatible React (input contrôlé) : setter natif + events bubbling, plus insertText clavier réel en secours.
  await cdp.eval(`(()=>{const e=document.querySelector(${jstr(sel)});if(!e)throw new Error('introuvable ${sel}');e.focus();const p=e.tagName==='TEXTAREA'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;const set=Object.getOwnPropertyDescriptor(p,'value')&&Object.getOwnPropertyDescriptor(p,'value').set;if(set)set.call(e,${jstr(text)});else e.value=${jstr(text)};e.dispatchEvent(new Event('input',{bubbles:true}));e.dispatchEvent(new Event('change',{bubbles:true}));return true;})()`);
}
async function pressKey(cdp, sel, key) {
  if (sel && sel !== "-") await cdp.eval(`(()=>{const e=document.querySelector(${jstr(sel)});if(e)e.focus();})()`);
  const map = { Enter: { keyCode: 13, code: "Enter", key: "Enter" }, Tab: { keyCode: 9, code: "Tab", key: "Tab" }, Escape: { keyCode: 27, code: "Escape", key: "Escape" } };
  const k = map[key] || { keyCode: 0, code: key, key };
  await cdp.send("Input.dispatchKeyEvent", { type: "keyDown", ...k });
  await cdp.send("Input.dispatchKeyEvent", { type: "keyUp", ...k });
}
async function streamConsole(cdp, ms) {
  await cdp.send("Runtime.enable").catch(() => {});
  await cdp.send("Log.enable").catch(() => {});
  cdp.on((m) => {
    if (m.method === "Runtime.consoleAPICalled") { const a = (m.params.args || []).map(x => x.value ?? x.description ?? "").join(" "); console.log(`[console.${m.params.type}] ${a}`); }
    else if (m.method === "Runtime.exceptionThrown") { console.log(`[exception] ${m.params.exceptionDetails?.exception?.description || m.params.exceptionDetails?.text}`); }
    else if (m.method === "Log.entryAdded") { const e = m.params.entry; console.log(`[log.${e.level}] ${e.text}${e.url ? " (" + e.url + ")" : ""}`); }
  });
  console.error(`[cdp] écoute console ${ms}ms...`);
  await new Promise(r => setTimeout(r, ms));
  cdp.close();
}
async function screenshot(cdp, file) {
  await cdp.send("Page.enable").catch(() => {});
  const r = await cdp.send("Page.captureScreenshot", { format: "png" });
  const { writeFileSync } = await import("node:fs");
  writeFileSync(file, Buffer.from(r.data, "base64"));
  out(`capture écrite: ${file}`);
}
async function repl(cdp) {
  console.error("[cdp] REPL — tape du JS, une expression par ligne (Ctrl-D pour finir).");
  const rl = (await import("node:readline")).createInterface({ input: process.stdin });
  for await (const line of rl) {
    if (!line.trim()) continue;
    try { console.log(await cdp.eval(line)); } catch (e) { console.log("ERR " + e.message); }
  }
  cdp.close();
}

const DUMP_JS = `(()=>{const q=[...document.querySelectorAll('button,a,input,select,textarea,[role=button],[role=tab]')];return q.slice(0,120).map(e=>{const r=e.getBoundingClientRect();const vis=r.width>0&&r.height>0;const label=(e.innerText||e.value||e.placeholder||e.getAttribute('aria-label')||'').trim().slice(0,60);return (vis?'':'· ')+e.tagName.toLowerCase()+(e.id?'#'+e.id:'')+(e.className&&typeof e.className==='string'?'.'+e.className.trim().split(/\\s+/).slice(0,2).join('.'):'')+' :: '+label;}).join('\\n');})()`;

function printHelp() { console.log(`cdp_drive.mjs — pilote CDP du launcher (WebView2/Chromium).\n\n` +
  `  node tools/cdp_drive.mjs <cmd> [args] [--port 9222] [--url <substr>] [--timeout ms] [--json]\n\n` +
  `  targets | eval "<js>" | click "<sel>" | clicktext "<texte>" | type "<sel>" "<txt>" | press "<sel>" Enter\n` +
  `  text "<sel>" | attr "<sel>" <nom> | exists "<sel>" | wait "<sel>" [visible] | html [sel]\n` +
  `  dump | console [ms] | shot <f.png> | nav <url> | reload | repl\n`); }

main().catch(e => die(e.stack || e.message));
