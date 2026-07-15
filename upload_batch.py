# 1. On vide le fichier
> master_list.txt

# 2. Ajout des Assets (en 15 lots pour faire 15 ZIPs d'environ 240 Mo)
strings bundles.data | grep -o '"LocalPath":"[^"]*"' | cut -d'"' -f4 | awk '{print "ASSET|" $0}' >> master_list.txt

# 3. Ajout du Static Content (1 ZIP dédié)
echo "DATA|https://cdn.pepedev.com/static-content/11440/android/ftue/game_content/tutorial/tutorial.data|data/tutorial.data" >> master_list.txt
echo "DATA|https://cdn.pepedev.com/static-content/11559/android/skills/game_content/character_skills/character_skills.data|data/character_skills.data" >> master_list.txt
echo "DATA|https://cdn.pepedev.com/static-content/11559/android/stats/game_content/character_stats/character_stats.data|data/character_stats.data" >> master_list.txt

# 4. Ajout des Langues (1 ZIP dédié)
langues=("af" "am" "ar" "as" "az" "be" "bg" "bn" "bs" "ca" "cs" "da" "de" "el" "en" "en-AU" "en-CA" "en-GB" "en-IN" "en-XC" "es" "es-419" "es-ES" "es-US" "et" "eu" "fa" "fi" "fr" "fr-CA" "gl" "gu" "hi" "hr" "hu" "hy" "id" "in" "is" "it" "iw" "ja" "ka" "kk" "km" "kn" "ko" "ky" "lo" "lt" "lv" "mk" "ml" "mn" "mr" "ms" "my" "nb" "ne" "nl" "or" "pa" "pl" "pt" "pt-BR" "pt-PT" "ro" "ru" "si" "sk" "sl" "sq" "sr" "sr-Latn" "sv" "sw" "ta" "te" "th" "tl" "tr" "uk" "ur" "uz" "vi" "zh" "zh-CN" "zh-HK" "zh-MO" "zh-TW" "zu")
for l in "${langues[@]}"; do
    code="${l/-/_}"
    echo "LANG|https://cdn.pepedev.com/localization/106475/${code}.tgz|localization/${code}.tgz" >> master_list.txt
    echo "LANG|https://cdn.pepedev.com/localization/106520/${code}_liveops.tgz|localization/${code}_liveops.tgz" >> master_list.txt
done
