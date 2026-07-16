import xml.etree.ElementTree as ET
import requests
import time
import os

# CONFIGURATION
IA_ACCESS = os.getenv("IA_ACCESS_KEY")
IA_SECRET = os.getenv("IA_SECRET_KEY")

sitemap_files = ["posts.xml", "pages.xml", "categories.xml", "tags.xml", "users.xml"]
ns = {'ns': 'http://www.sitemaps.org/schemas/sitemap/0.9'}

def save_to_wayback(url):
    """Envoie une URL à la Wayback Machine avec tes clés pour éviter les limitations."""
    print(f"Archivage : {url}")
    # On utilise l'API Save Page Now (SPN2)
    api_url = "https://web.archive.org/save/" + url
    
    headers = {
        "Accept": "application/json",
        "Authorization": f"LOW {IA_ACCESS}:{IA_SECRET}"
    }
    
    try:
        # On demande aussi de capturer les liens sortants et les captures d'écran
        data = {
            "capture_outlinks": "1",
            "skip_first_archive": "1"
        }
        r = requests.post(api_url, headers=headers, data=data, timeout=30)
        if r.status_code in [200, 302]:
            print(" -> En fil d'attente / Succès")
        else:
            print(f" -> Erreur {r.status_code}")
    except Exception as e:
        print(f" -> Erreur de connexion : {e}")

if __name__ == "__main__":
    urls_to_archive = []

    # 1. Extraction des URLs
    for file in sitemap_files:
        if not os.path.exists(file):
            continue
        tree = ET.parse(file)
        root = tree.getroot()
        for loc in root.findall('.//ns:loc', ns):
            urls_to_archive.append(loc.text)

    # 2. Déduplication
    urls_to_archive = list(set(urls_to_archive))
    print(f"Total d'URLs trouvées : {len(urls_to_archive)}")

    # 3. Envoi (avec une petite pause pour ne pas saturer l'API)
    for url in urls_to_archive:
        save_to_wayback(url)
        time.sleep(1) # L'API avec clé est plus rapide, mais 1s est sécurisé
