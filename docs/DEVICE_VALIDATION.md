# Tests sur appareils

Ces essais servent à vérifier les cas que les tests automatisés ne couvrent pas :
lecture sur une vraie TV, navigation à la télécommande et téléphone écran éteint.
Notez le résultat, le modèle et la version Android ou du firmware. Un essai
non réalisé reste « non testé ».

## Lecteur TV

| À essayer | À vérifier |
|---|---|
| TV récente, Android TV 9–11, boîtier avec peu de mémoire | Navigation, lecture et stabilité |
| 720p, 1080p, 4K, overscan activé | Texte lisible, boutons visibles, sélection toujours repérable |
| H.264, HEVC, HDR, AV1 si pris en charge ; MP4, MKV, WebM, TS | Image, son et diagnostic en cas de format refusé |
| AAC, AC3/EAC3, DTS, TrueHD, avec et sans ampli | Son présent ou limite expliquée |
| Recherche, reprise, épisode suivant, audio et sous-titres | Tout accessible avec la télécommande |
| Déplacement dans la vidéo, bref puis maintenu, début et fin du fichier | Position correcte, lecture/pause conservée |
| Photos et musique, fichier absent ou corrompu, bibliothèque vide ou volumineuse | Navigation utilisable et erreur compréhensible |
| Ethernet, Wi-Fi, coupure puis retour du réseau | Reprise à la bonne position, arrêt possible |
| Plusieurs lecteurs et demandes simultanées de miniatures | Vidéo fluide, mémoire et connexions libérées après arrêt |

Avec le serveur PC, essayer la lecture originale, le remux et le transcodage.
Vérifier les modes Automatique, Direct Play et Compatibilité, y compris un changement
pendant la lecture. Le débit adapté doit rester inférieur ou égal à celui du fichier.

## Serveur Android

Sur Pixel/AOSP, Samsung et Xiaomi/Redmi : lire pendant deux heures avec l'écran
éteint, puis vérifier la batterie, la température et les interruptions éventuelles.
Essayer aussi de retirer un dossier source et de couper puis rétablir le Wi-Fi.

Pour surveiller le serveur depuis Windows, préparez d'abord Python en lançant
`Pluxy-Server.bat`, puis branchez ADB et relevez le numéro avec `adb devices`.
Lancez un film sur la TV, verrouillez le téléphone et exécutez depuis le dossier du projet :

```powershell
.\server\.venv\Scripts\python.exe tools\device_soak.py --server http://192.168.1.20:8420 --duration-minutes 120 --cycles 1000 --adb-serial NUMERO_APPAREIL --output pluxy-soak-phone-tv.json
```

Le script contrôle `/health`, les réponses HTTP Range et les mesures ADB sur une
période de deux heures. Il renvoie un code d'erreur si un contrôle échoue.
L'image, le son et les commandes de la TV doivent être vérifiés pendant l'essai.
Gardez le rapport JSON localement ; il contient l'adresse du serveur et des diagnostics.

## DLNA et Google Cast

Essayer découverte, lecture, pause, déplacement et arrêt sur plusieurs modèles :
Samsung, LG et Philips en DLNA, Chromecast et Google TV en Cast.
Tester vidéo, photo et audio, coupure Wi-Fi et extinction du récepteur.
Répéter cent fois la découverte, la connexion et l'arrêt pour repérer les
connexions ou verrous qui restent ouverts.

Vérifier aussi qu'une erreur de codec n'empêche pas les autres formats de fonctionner
et que l'absence des services Google laisse le serveur et DLNA utilisables.

Pour chaque essai, garder la date, le commit, le modèle, le format lu et le résultat.
Avant une publication Play, ajouter le rapport Pre-launch et les problèmes encore
ouverts au [dossier de publication](PLAY_STORE_RELEASE.md).
