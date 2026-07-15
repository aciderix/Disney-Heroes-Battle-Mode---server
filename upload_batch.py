import os, sys, requests, zipfile, shutil

# CONFIGURATION
IA_IDENTIFIER = "looney-tunes-wom-v65-full-assets" # NOUVELLE URL SUR IA
CDN_BASE = "https://cdn.pepedev.com/bundles/23565/android/"
USER_AGENT = "UnityPlayer/6000.0.68f1 (UnityWebRequest/1.0, libcurl/8.10.1-DEV)"

def download_file(url, local_path):
    os.makedirs(os.path.dirname(local_path), exist_ok=True)
    try:
        r = requests.get(url, headers={"User-Agent": USER_AGENT}, timeout=30)
        if r.status_code == 200:
            with open(local_path, 'wb') as f:
                with open(local_path, 'wb') as f: f.write(r.content)
            return True
    except: pass
    return False

if __name__ == "__main__":
    batch_idx = int(sys.argv[1])
    num_batches = 15 # Nombre de ZIPs pour les assets
    
    with open("master_list.txt", "r") as f:
        lines = f.read().splitlines()
    
    # Ségrégation
    asset_lines = [l for l in lines if l.startswith("ASSET|")]
    data_lines = [l for l in lines if l.startswith("DATA|")]
    lang_lines = [l for l in lines if l.startswith("LANG|")]

    to_download = []
    zip_name = ""

    if batch_idx < num_batches: # Batch Assets
        chunk_size = len(asset_lines) // num_batches
        start = batch_idx * chunk_size
        end = start + chunk_size if batch_idx < num_batches - 1 else len(asset_lines)
        zip_name = f"assets_part{batch_idx}.zip"
        for l in asset_lines[start:end]:
            path = l.split("|")[1]
            to_download.append((CDN_BASE + path, path))
    elif batch_idx == 15: # Batch Langues
        zip_name = "localizations.zip"
        for l in lang_lines:
            _, url, path = l.split("|")
            to_download.append((url, path))
    elif batch_idx == 16: # Batch Data
        zip_name = "game_data_configs.zip"
        for l in data_lines:
            _, url, path = l.split("|")
            to_download.append((url, path))

    # Phase Téléchargement
    print(f"Création de {zip_name}...")
    tmp_dir = f"tmp_{batch_idx}"
    for url, path in to_download:
        download_file(url, os.path.join(tmp_dir, path))

    # Phase Zipping
    with zipfile.ZipFile(zip_name, 'w', zipfile.ZIP_DEFLATED) as z:
        for root, _, files in os.walk(tmp_dir):
            for file in files:
                z.write(os.path.join(root, file), os.path.relpath(os.path.join(root, file), tmp_dir))

    # Phase Upload IA
    access = os.getenv("IA_ACCESS_KEY")
    secret = os.getenv("IA_SECRET_KEY")
    ia_url = f"https://s3.us.archive.org/{IA_IDENTIFIER}/{zip_name}"
    
    headers = {
        "Authorization": f"LOW {access}:{secret}",
        "x-archive-meta-mediatype": "software",
        "x-archive-meta-collection": "opensource_software",
        "x-archive-meta-title": "Looney Tunes World of Mayhem v65 Assets",
        "x-archive-keep-old-version": "0"
    }

    with open(zip_name, 'rb') as f:
        up = requests.put(ia_url, data=f, headers=headers)
        print(f"Upload {zip_name}: {up.status_code}")
