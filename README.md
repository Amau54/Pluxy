<div align="center">

<img src="assets/pluxy-logo.png" width="140" alt="Pluxy" />

# Pluxy

Vos films, séries, photos et musique sur la TV.

**1.7.1 · Android TV et Android 6.0+**

[Application Android](https://github.com/Amau54/Pluxy/releases/download/dev-latest/Pluxy-debug.apk)
· [Serveur PC](https://github.com/Amau54/Pluxy/archive/refs/heads/main.zip)

</div>

Pluxy lit les fichiers d'un PC, d'un téléphone ou d'une tablette sur votre réseau
local. La même application Android sert de lecteur ou de serveur : choisissez
son mode au premier lancement, puis dans les options pour le modifier.
Les appareils doivent être sur le même réseau.

Aucun compte ni abonnement n'est nécessaire. Pluxy ne fournit pas de contenu.

Dans le lecteur, **Réglages → Thème** propose Corail, Océan et Minuit. Le choix
est conservé sur cet appareil et s'applique aussi aux commandes vidéo.

## Démarrer sur PC

1. Téléchargez le ZIP du projet et extrayez-le dans un dossier.
2. Sous Windows 10 ou 11, double-cliquez sur [Pluxy-Server.bat](Pluxy-Server.bat).
3. Choisissez vos dossiers dans la fenêtre qui s'ouvre.
4. Ouvrez Pluxy sur la TV et sélectionnez le serveur détecté.

Python, FFmpeg et ffprobe sont installés automatiquement s'ils manquent. Le premier lancement nécessite
Internet pour préparer les composants. Les lancements suivants réutilisent
l'installation. Aucun droit administrateur n'est demandé.

L'icône Pluxy dans la zone de notification permet de rouvrir la fenêtre ou
d'arrêter le serveur. L'adresse de configuration est `http://localhost:8420/`
par défaut. Si vous changez le port, la fenêtre utilise la nouvelle adresse.

Le lanceur prépare FFmpeg dans votre compte Windows. Pour utiliser votre propre
installation, ajoutez-la au `PATH` ou renseignez `ffmpeg.ffmpeg_path` et
`ffmpeg.ffprobe_path` dans `%LOCALAPPDATA%\Pluxy\config.json`.
L'accélération NVENC nécessite un GPU NVIDIA compatible et ses pilotes.
La conversion logicielle utilise le processeur et peut être plus lente.
La lecture du fichier original fonctionne sans FFmpeg.

## Démarrer depuis Android

1. Installez **Pluxy** sur l’appareil contenant les fichiers et choisissez **Serveur**.
2. Sélectionnez les dossiers de vidéos, photos et musique à partager.
3. Démarrez le serveur. Pour lire écran éteint, activez **Rester disponible écran éteint**.
4. Sur la TV, choisissez le mode **Lecteur**, puis sélectionnez ce serveur.

Le passage en mode Lecteur arrête le serveur de cet appareil, tout en conservant
ses réglages. Si vous utilisiez l’ancienne application Pluxy Serveur, arrêtez-la
et sélectionnez de nouveau vos dossiers dans Pluxy : Android conserve les
autorisations et réglages dans le package de chaque application.

Dans les photos, gauche/droite passe aux images et haut/bas change de dossier.
Les images proches sont préchargées. Les vidéos personnelles se lancent directement ;
les films et séries conservent leur fiche.

Le serveur Android partage les fichiers tels quels. Si la TV ne sait pas lire
un format, utilisez un lecteur compatible ou le serveur PC pour le convertir.

Depuis l'écran Récepteurs du serveur Android, vous pouvez aussi envoyer un média
vers une TV DLNA ou un appareil Google Cast. La compatibilité dépend du modèle
et du format. Les sous-titres externes et le réglage du volume ne sont pas pris
en charge en DLNA. Google Cast nécessite les services Google sur le téléphone.

## Lecture et bibliothèque

- Reprise des vidéos, épisode suivant et recherche dans toute la bibliothèque.
- Films, séries et vidéos dans trois rubriques distinctes ; vue Découvrir pour les nouveautés et les reprises.
- Filtres par genre, année, état de lecture, durée, résolution et HDR ; tri séparé.
- Accès aux films par première lettre depuis le menu de tri.
- Collections de votre choix ou collections alimentées par vos filtres.
- Photos par dossiers dépliés, albums ou date de prise de vue, galerie et diaporama.
- Musique par album, avec les pistes dans l'ordre des disques et des numéros.
- Navigation mobile en bas de l'écran ; densité Compact ou Confort dans les réglages.
- Audio, sous-titres et déplacement dans la vidéo depuis la télécommande.
- Réglages simples au quotidien, options supplémentaires dans le mode avancé.

Les affiches et synopsis TMDB sont facultatifs. Ajoutez votre propre clé dans
les réglages. Sans clé, Pluxy utilise les noms de fichiers et les images locales.
Les services externes utilisés sont décrits dans la [politique de confidentialité](docs/PRIVACY_POLICY.md).

Les filtres et la vue sont mémorisés pour chaque bibliothèque et chaque serveur.
Les collections sont conservées sur le lecteur qui les a créées.

Pour chaque dossier vidéo, choisissez **Automatique**, **Films**, **Séries** ou
**Vidéos** dans le serveur. Le choix explicite prime sur la détection. En automatique,
Pluxy utilise les dossiers, noms d'épisodes, noms de caméra, durée et titre intégré.
Les fichiers sans indice fiable restent dans Vidéos. Aucun fichier n'est déplacé.
Les miniatures manquantes sont calculées à la demande à partir de plusieurs images
de la vidéo ; les fondus noirs ou blancs sont défavorisés, puis le résultat est gardé en cache.

## Installer une APK sur la TV

Les APK GitHub sont des versions de test, hors Google Play. Depuis le changement
de clé du 4 octobre 2026, une ancienne installation signée avec une autre clé
doit être désinstallée avant d'installer la version actuelle. Cela efface les
réglages de l'application ; les fichiers médias restent sur leur support.
Voir le [guide de signature](docs/DEBUG_SIGNING.md).

Sur le PC, installez JDK 17 et le SDK Android avec platform-tools et build-tools.
Activez ensuite le débogage Android sur la TV :

```powershell
.\Install-APK-on-TV.bat -Apk "C:\Downloads\Pluxy-debug.apk" -Device SERIAL_USB -Launch
.\Install-APK-on-TV.bat -Apk "C:\Downloads\Pluxy-debug.apk" -Address 192.168.1.50:5555 -Launch
```

En Wi-Fi, utilisez le port affiché par Android. Si un appairage est demandé,
ajoutez `-PairAddress IP:PORT_APPAIRAGE` ; le port de connexion peut être différent.
Pour le serveur Android, utilisez [Install-Server-on-Android.bat](Install-Server-on-Android.bat).
Les scripts vérifient l'APK et refusent une mise à jour incompatible.

## Construire le projet

Pour Android : JDK 17, SDK 36, platform-tools et build-tools 36.

```powershell
.\Build-APK.bat
.\Build-APK.bat -Verify
```

L’APK est placée dans `dist/`. `-Verify` lance aussi les tests, lint et le
bundle release. Pour mettre à jour une installation du canal GitHub, utilisez
ses [clés debug stables](docs/DEBUG_SIGNING.md). Un build avec une autre clé
nécessite une réinstallation.

L’application est dans `client/app`, le serveur Android intégré dans la bibliothèque `client/serverapp`,
les modules Cast/DLNA dans `client/receiver-*` et le serveur PC dans `server/pluxy`.
Les scripts de construction et d'installation sont dans `tools`.

## Données et réseau

Les réglages du serveur PC sont dans `%LOCALAPPDATA%\Pluxy` sous Windows,
ou `~/.local/share/Pluxy` sous Linux/macOS. Si `PLUXY_DATA_DIR` est défini,
les données sont dans son sous-dossier `Pluxy`.
Pensez à les [sauvegarder](docs/RESTORE.md) avant de changer de PC.

Le serveur partage les dossiers choisis avec tous les appareils du réseau,
sans mot de passe et en HTTP. Utilisez un réseau domestique de confiance.
N'ouvrez pas les ports TCP 8420 et UDP 8421 sur Internet. La configuration
sensible du serveur PC reste accessible depuis `localhost`.

[Notes de version](RELEASE_NOTES.md)
· [Sécurité](docs/SECURITY.md)
· [Tests sur appareils](docs/DEVICE_VALIDATION.md)
· [Publication Google Play](docs/PLAY_STORE_RELEASE.md)
· [Composants tiers](docs/THIRD_PARTY_NOTICES.md)
