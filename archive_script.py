import xml.etree.ElementTree as ET
import requests
import time
import os

# Configuration des clés récupérées depuis les secrets GitHub
ACCESS_KEY = os.getenv("IA_ACCESS_KEY")
SECRET_KEY = os.getenv("IA_SECRET_KEY")

# Liste des sitemaps à scanner
SITEMAPS = ["posts.xml", "pages.xml", "categories.xml", "tags.xml", "users.xml"]
NAMESPACE = {'ns': 'http://www.sitemaps.org/schemas/sitemap/0.9'}

def send_to_wayback(url):
    """Envoie l'URL à la Wayback Machine via l'API SPN2"""
    print(f"Envoi : {url}")
    
    # Endpoint de l'API Save Page Now
    api_url = f"https://web.archive.org/save/{url}"
    
    headers = {
        "Accept": "application/json",
        "Authorization": f"LOW {ACCESS_KEY}:{SECRET_KEY}"
    }
    
    # Options pour une sauvegarde complète
    params = {
        "capture_outlinks": "1",  # Sauvegarder les liens sortants (images, etc)
        "capture_screenshot": "1", # Prendre une capture d'écran de la page
        "force_get": "1"          # Forcer une nouvelle capture même si une existe
    }

    try:
        response = requests.post(api_url, headers=headers, data=params, timeout=60)
        if response.status_code == 200:
            print(f" -> Succès (Job ID: {response.json().get('job_id', 'N/A')})")
        elif response.status_code == 429:
            print(" -> Erreur 429 : Trop de requêtes. Pause de 30 secondes...")
            time.sleep(30)
        else:
            print(f" -> Code erreur : {response.status_code} - {response.text}")
    except Exception as e:
        print(f" -> Erreur réseau : {e}")

def main():
    if not ACCESS_KEY or not SECRET_KEY:
        print("Erreur : IA_ACCESS_KEY ou IA_SECRET_KEY non trouvés dans l'environnement.")
        return

    all_urls = []

    # Extraction de toutes les URLs
    for filename in SITEMAPS:
        if not os.path.exists(filename):
            print(f"Fichier {filename} manquant, passage...")
            continue
            
        print(f"Lecture de {filename}...")
        tree = ET.parse(filename)
        root = tree.getroot()
        
        for loc in root.findall(".//ns:loc", NAMESPACE):
            all_urls.append(loc.text)

    # Suppression des doublons
    all_urls = list(set(all_urls))
    total = len(all_urls)
    print(f"Total d'URLs uniques à archiver : {total}")

    # Boucle d'archivage
    for index, url in enumerate(all_urls, 1):
        print(f"[{index}/{total}]", end=" ")
        send_to_wayback(url)
        # Pause de 2 secondes entre chaque URL pour respecter l'API
        time.sleep(2)

if __name__ == "__main__":
    main()
