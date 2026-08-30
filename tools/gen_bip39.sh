#!/usr/bin/env bash
# Régénère server/java/dhserver/auth/Bip39Wordlist.java depuis la wordlist BIP39 ANGLAISE canonique (2048 mots).
# §7 (reproductibilité) : provenance + sha256 vérifiés ; la wordlist est un STANDARD PUBLIC (BIP-0039), pas une
# donnée du jeu → le .java généré est committé (petit, requis au build). Source : bitcoin/bips bip-0039/english.txt.
set -e
cd "$(dirname "$0")/.."
SRC="${1:-/tmp/bip39_en.txt}"
EXPECT_SHA="2f5eed53a4727b4bf8880d8f3f199efc90e58503646d9ff8eff3a2ed3b24dbda"
[ -f "$SRC" ] || { echo "[gen_bip39] source introuvable: $SRC (curl bip-0039/english.txt)"; exit 1; }
GOT_SHA=$(sha256sum "$SRC" | cut -d' ' -f1)
[ "$GOT_SHA" = "$EXPECT_SHA" ] || { echo "[gen_bip39] SHA256 inattendu ($GOT_SHA ≠ $EXPECT_SHA) — wordlist non canonique, ABANDON (§4)"; exit 1; }
N=$(wc -l < "$SRC"); [ "$N" -eq 2048 ] || { echo "[gen_bip39] $N mots (attendu 2048)"; exit 1; }
OUT="server/java/dhserver/auth/Bip39Wordlist.java"
WORDS=$(tr '\n' ' ' < "$SRC" | sed 's/ *$//')
{
  echo "package dhserver.auth;"
  echo "/* GÉNÉRÉ par tools/gen_bip39.sh depuis la wordlist BIP-0039 ANGLAISE canonique (2048 mots,"
  echo " * sha256=$EXPECT_SHA). Standard public, NE PAS éditer à la main — régénérer via le script. */"
  echo "public final class Bip39Wordlist {"
  echo "    private Bip39Wordlist() {}"
  echo "    public static final String[] WORDS = (\"$WORDS\").split(\" \");"
  echo "}"
} > "$OUT"
echo "[gen_bip39] écrit $OUT ($(wc -c < "$OUT") octets, 2048 mots)"
