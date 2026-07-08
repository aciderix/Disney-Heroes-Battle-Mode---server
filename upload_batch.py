#!/usr/bin/env python3
"""
Disney Heroes - Upload batch to Internet Archive
Usage: python3 upload_batch.py <batch_index> <total_batches>
"""

import csv, os, subprocess, sys, requests, json

ACCESS_KEY = os.environ["IA_ACCESS_KEY"]
SECRET_KEY  = os.environ["IA_SECRET_KEY"]
IDENTIFIER  = "disney-heroes-battle-mode-live-assets"
INDEX_FILE  = "disney_heroes_live_index.txt"
TMP_DIR     = "/tmp/dh_upload"

os.makedirs(TMP_DIR, exist_ok=True)

batch_index  = int(sys.argv[1])   # 0-9
total_batches = int(sys.argv[2])  # 10

# Lire tous les assets
assets = []
with open(INDEX_FILE) as f:
    reader = csv.DictReader(f, delimiter='\t')
    for row in reader:
        if row.get("URL"):
            assets.append(row)

# Calculer la slice de ce job
batch_size = (len(assets) + total_batches - 1) // total_batches
start = batch_index * batch_size
end   = min(start + batch_size, len(assets))
my_assets = assets[start:end]

print(f"Batch {batch_index}/{total_batches-1} : fichiers {start} à {end-1} ({len(my_assets)} assets)")
print()

def file_exists_on_ia(filename):
    """Vérifie si le fichier est déjà sur IA (idempotence)."""
    url = f"https://archive.org/download/{IDENTIFIER}/{filename}"
    try:
        r = requests.head(url, timeout=15, allow_redirects=True)
        return r.status_code == 200
    except:
        return False

done = 0
failed = []

for i, asset in enumerate(my_assets):
    url      = asset["URL"]
    filename = url.split("/")[-1]
    size_mb  = int(asset["Size"]) // 1024 // 1024

    print(f"[{i+1}/{len(my_assets)}] {filename} ({size_mb} MB)")

    # Vérifier si déjà uploadé
    if file_exists_on_ia(filename):
        print(f"  ✅ Déjà sur IA, skip")
        done += 1
        continue

    # Téléchargement
    local = os.path.join(TMP_DIR, filename)
    print(f"  ⬇️  Téléchargement...")
    dl = subprocess.run(
        ["curl", "-L", "-o", local, url, "--retry", "3", "--retry-delay", "5",
         "--connect-timeout", "30", "-s", "--show-error"],
        capture_output=True, text=True
    )
    if dl.returncode != 0 or not os.path.exists(local):
        print(f"  ❌ Échec téléchargement : {dl.stderr[:200]}")
        failed.append(filename)
        continue

    actual_size = os.path.getsize(local)
    print(f"  ✅ Téléchargé ({actual_size//1024//1024} MB)")

    # Upload vers IA
    print(f"  ⬆️  Upload IA...")
    ia_url = f"https://s3.us.archive.org/{IDENTIFIER}/{filename}"
    headers = {
        "Authorization": f"LOW {ACCESS_KEY}:{SECRET_KEY}",
        "x-archive-auto-make-bucket": "1",
        "x-archive-meta-mediatype": "software",
        "x-archive-meta-subject": "video games;disney;disney heroes;perblue;mobile game;preservation",
        "x-archive-meta-title": "Disney Heroes Battle Mode - Live Game Assets",
        "x-archive-meta-description": "Original game assets from Disney Heroes: Battle Mode (com.perblue.disneyheroes) preserved for community private server.",
        "x-archive-meta-creator": "PerBlue Entertainment",
        "Content-Length": str(actual_size),
    }
    with open(local, "rb") as fh:
        resp = requests.put(ia_url, data=fh, headers=headers, timeout=600)

    if resp.status_code in (200, 201):
        print(f"  ✅ Upload OK")
        done += 1
        os.remove(local)
    else:
        print(f"  ❌ Erreur upload {resp.status_code}: {resp.text[:200]}")
        failed.append(filename)

print()
print(f"=== Batch {batch_index} terminé ===")
print(f"✅ Réussis : {done}/{len(my_assets)}")
print(f"❌ Échoués : {len(failed)}")
if failed:
    print("Fichiers échoués :", failed)
    sys.exit(1)  # Fait échouer le job GitHub pour retry
