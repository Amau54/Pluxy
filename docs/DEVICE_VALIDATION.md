# Validation réelle avant publication

La compilation et les tests unitaires ne valident ni les restrictions batterie OEM,
ni le Wi-Fi écran éteint, ni les décodeurs réels d'un téléviseur. Cette matrice est un
critère de sortie : une case non exécutée reste **à vérifier**, jamais « réussie ».

## Test serveur Android pendant deux heures

1. Installer la build de contrôle sur le téléphone, sélectionner un dossier et démarrer
   le serveur.
2. Brancher ADB en Wi-Fi ou USB, relever son numéro avec `adb devices`.
3. Démarrer un film sur la TV, verrouiller l'écran du téléphone puis exécuter :

```bash
python tools/device_soak.py \
  --server http://192.168.1.20:8420 \
  --duration-minutes 120 --cycles 100 \
  --adb-serial NUMERO_APPAREIL \
  --output pluxy-soak-phone-tv.json
```

Le rapport ne contient ni nom de fichier ni extrait du média. Le script poursuit les
cycles afin de mesurer les erreurs intermittentes, puis renvoie un code non nul si au
moins un contrôle HTTP Range ou une mesure ADB obligatoire échoue. Il valide le serveur
et le transport HTTP ; l'image, le son, le seek et les commandes réellement observés sur
la télévision restent à consigner manuellement dans la matrice.

La CI exécute trois cycles contre un faux serveur local afin de vérifier le script, les
requêtes `/health` et la validation HTTP Range. Ce test rapide n'est pas un soak et ne
peut jamais remplir une ligne de la matrice ci-dessous.

## Matrice minimale

| Domaine | Variantes obligatoires | Critère |
|---|---|---|
| Téléphone serveur | Pixel/AOSP, Samsung, Xiaomi/Redmi | 2 h écran éteint, 0 rupture serveur |
| Android TV | Google TV récent, Android TV 9–11, boîtier faible mémoire | aucun focus perdu, aucun débordement |
| Affichage | 720p, 1080p, 4K, overscan activé | commandes et timeline entièrement visibles |
| Vidéo | H.264, HEVC, HEVC Main10 HDR, AV1 si annoncé | diagnostic conforme au résultat réel |
| Conteneur | MP4, MKV, WebM, TS | Direct Play ou adaptation annoncée avant lecture |
| Audio | AAC, AC3/EAC3, DTS, TrueHD avec/sans ampli | son présent ou diagnostic explicite |
| Réseau | Ethernet, Wi-Fi 5 GHz, perte/reprise Wi-Fi | reprise à la bonne position, aucun verrou bloqué |
| Seek | 100 appuis courts, 20 maintiens, extrémités du média | cible précise, un seul seek par relâche |
| Récepteurs | Samsung/LG/Philips DLNA, Chromecast/Google TV | play/pause/seek/stop et reconnexion |

Pour chaque ligne, consigner : date, modèle exact, version Android/firmware, protocole,
codec/conteneur, résultat, position avant/après seek, perte réseau, batterie initiale et
finale, température maximale et lien vers le rapport JSON. Une case vide ou « non testé »
n'est jamais convertie en réussite dans la communication Play Store.

## Mesures de sortie

- 100 cycles découverte/connexion/arrêt sans socket ou verrou conservé ;
- aucune température sévère signalée pendant la session de deux heures ;
- aucun arrêt mémoire sur le boîtier low-RAM et budget de tampon conforme au profil
  `PlaybackMemoryPolicy` ;
- batterie mesurée au début et à la fin, modèle et version Android consignés ;
- aucun accès depuis le réseau mobile ou une adresse hors LAN ;
- échec d'un codec mémorisé uniquement s'il est déterministe ou reproduit deux fois ;
- fiche Play mise à jour seulement après validation de plusieurs marques physiques.
