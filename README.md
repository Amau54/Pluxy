<div align="center">

<img src="assets/pluxy-logo.png" width="140" alt="Pluxy" />

# Pluxy

Vos films, séries, photos et musique sur la TV.

**Android 1.7.31 · Windows 1.8.23**

[Application Android](https://github.com/Amau54/Pluxy/releases/download/dev-latest/Pluxy-debug.apk)
· [Application PC](https://github.com/Amau54/Pluxy/releases/download/pc-latest/Pluxy-Setup.exe)

</div>

Pluxy lit les fichiers d'un PC, d'un téléphone ou d'une tablette sur votre réseau
local. Les applications Android et Windows proposent un lecteur et un serveur : choisissez
son mode au premier lancement, puis dans les options pour le modifier.
Les appareils doivent être sur le même réseau.

Aucun compte ni abonnement n'est nécessaire. Pluxy ne fournit pas de contenu.

Les applications vérifient les nouvelles versions sur GitHub en arrière-plan.
Dans **Options → Mises à jour**, vous pouvez aussi lancer la vérification,
puis télécharger et installer la version proposée. Android demande de confirmer
l’installation et, la première fois, d’autoriser Pluxy à installer ses mises à jour.
Sous Windows, confirmez la demande de Windows ; Pluxy se ferme pendant
l’installation puis se rouvre. Vos réglages sont conservés.


Dans le lecteur, **Réglages → Thème** propose Corail, Océan et Minuit. Le choix
est conservé sur cet appareil et s'applique aussi aux commandes vidéo.

Pendant un déplacement dans la vidéo, un aperçu apparaît lorsque le serveur peut
extraire une image. Les images sont préparées en arrière-plan et mises en cache.
Avec un ancien serveur ou un serveur Android antérieur à Android 8.1, le temps
reste affiché sans aperçu. Aucun réencodage du film n'est nécessaire.

Le lecteur peut passer automatiquement les intros et génériques détectés. Activez
ou désactivez **Passer automatiquement les intros et génériques** dans les réglages
de lecture ou les options du lecteur. Désactivé, le bouton « Passer » reste disponible.

Pour conserver la vidéo 4K/HDR, choisissez **Mode de lecture → Vidéo originale (4K/HDR)**.
Le lecteur ne réduit alors pas la résolution et n'accepte aucun réencodage vidéo.
Le HDR dépend aussi de l'écran et de son décodeur.

Pour alléger un fichier, choisissez **Mode de lecture → Conversion**, puis
**Qualité de conversion**. Automatique commence au meilleur palier accepté par
le lecteur et le serveur : 4K H.265, 4K à débit réduit, 1080p, 720p ou 480p.
Il descend si la lecture bloque et peut remonter après une période stable.
Un choix manuel conserve la qualité choisie. La vidéo n'est jamais agrandie.

La conversion est disponible avec le serveur PC. La 4K nécessite un encodeur
matériel compatible ; sans lui, les paliers disponibles commencent au plus à
1080p. Le serveur Android transmet actuellement les fichiers originaux et peut
renforcer le tampon, mais ne convertit pas la vidéo. Il faut mettre à jour le
serveur PC et le lecteur pour utiliser les nouveaux paliers.

**Sortie audio** se règle séparément, dans les réglages ou le menu du lecteur.
Automatique utilise les capacités annoncées par la sortie audio et adapte le son
sur le serveur PC en cas d'échec, avec du surround quand il est accepté.
Passthrough forcé tente de transmettre Dolby/DTS à l'ampli en conservant le son
original, même si la TV annonce mal ses capacités. Audio compatible demande du
AAC stéréo au serveur PC. Une adaptation du son avec vidéo inchangée s'affiche
comme « Direct Stream ». La TV, la liaison et l'ampli doivent pouvoir transporter
le format choisi ; le mode forcé ne change pas les limites physiques des ports.

## Démarrer sur PC

1. Téléchargez [Pluxy-Setup.exe](https://github.com/Amau54/Pluxy/releases/download/pc-latest/Pluxy-Setup.exe).
2. Installez Pluxy sous Windows 10 ou 11, en 64 bits, puis ouvrez son raccourci.
3. Choisissez **Lecteur** pour vous connecter à un serveur, ou **Serveur** pour partager vos dossiers.

Le choix est mémorisé. **Options → Changer de mode** permet de le modifier.
Le profil Général est utilisé par défaut. Dans Réglages → Options, vous pouvez activer plusieurs profils pour séparer l’historique et les réglages de lecture, sans compte.

Les intros et génériques sont détectés automatiquement, sans clé API ni repère à saisir. Le serveur compare le son des épisodes d’une même saison et recherche les génériques sur fond sombre. Les chapitres clairement nommés sont utilisés en priorité. L’analyse se fait en arrière-plan puis reste en cache : le bouton de passage apparaît seulement quand un segment est reconnu. Une scène après le générique reste lisible. Les formats non pris en charge et les passages incertains ne sont pas sautés.
L’épisode suivant démarre après 5 secondes, avec un décompte sans panneau sur la vidéo.
Vous pouvez choisir **Lire maintenant**, **Rester ici**, ou désactiver l’enchaînement dans les réglages.
Les images propres aux épisodes sont récupérées sur TMDB par saison ; les captures
vidéo restent disponibles en secours. Les bandes-annonces sont associées aux fiches
automatiquement, en français si elles existent, puis en anglais. Ces fonctions
nécessitent une clé TMDB configurée dans le serveur.
Une mauvaise correspondance peut être corrigée depuis la fiche d’un film ou les options
d’une série. Sur Android, ces options s’ouvrent par appui long sur sa carte. Le choix
reste conservé après un nouveau scan. Sur Windows, la musique continue
dans un mini lecteur pendant que vous parcourez la bibliothèque.
Les options Windows permettent aussi de sauvegarder les réglages et l’historique,
sans copier les médias ni les clés API.
L'installation propose `C:\Program Files\Pluxy` par défaut, selon le disque de Windows.
Windows demande les droits administrateur pour créer ce dossier. Vous pouvez choisir
un autre emplacement ; l'application fonctionne ensuite sans élévation.
Le lecteur présente les films, séries par saisons, vidéos, dossiers photos et albums
musicaux. Il lit les fichiers originaux avec son moteur vidéo intégré ; Python
n'est pas nécessaire pour ce mode.

En mode Serveur, Python, FFmpeg et les dépendances manquantes sont préparés
automatiquement, avec une progression dans l'application. La première préparation
nécessite Internet. Cette préparation ne demande ni fenêtre de commande ni droits
administrateur : les composants Python et les réglages sont stockés dans votre compte Windows.
Le composant d'affichage Microsoft est aussi installé s'il manque.

Si l'ancien serveur Pluxy tourne déjà, quittez-le depuis son icône près de
l'horloge avant d'ouvrir le mode Serveur de cette version. Vos réglages sont retrouvés.

Fermer la fenêtre d'un serveur démarré par Pluxy le laisse disponible près de
l'horloge. **Quitter Pluxy** l'arrête. Vos dossiers, réglages et reprises restent
conservés lors des mises à jour et de la désinstallation.

Le lecteur conserve la résolution d'origine. Le HDR et le passthrough Dolby/DTS
dépendent également de l'écran, de la sortie Windows et de l'ampli. La sortie audio
se choisit dans les réglages du lecteur. Maintenir gauche/droite accélère la navigation
jusqu'à ×64 ; relâcher applique le déplacement.

Sur les vidéos lourdes, le tampon privilégie les images à venir et augmente la réserve
après une coupure réseau. En Direct Play, la qualité originale reste conservée.

Pour développer le serveur depuis les sources, [Pluxy-Server.bat](Pluxy-Server.bat)
reste disponible. L'accélération NVENC nécessite un GPU NVIDIA compatible et ses pilotes.

## Démarrer depuis Android

1. Installez **Pluxy** sur l’appareil contenant les fichiers et choisissez **Serveur**.
2. Sélectionnez les dossiers de vidéos, photos et musique à partager.
3. Démarrez le serveur. Pour lire écran éteint, activez **Rester disponible écran éteint**.
4. Sur la TV, choisissez le mode **Lecteur**, puis sélectionnez ce serveur.

Le passage en mode Lecteur arrête le serveur de cet appareil, tout en conservant
ses réglages. Si vous utilisiez l’ancienne application Pluxy Serveur, arrêtez-la
et sélectionnez de nouveau vos dossiers dans Pluxy : Android conserve les
autorisations et réglages dans le package de chaque application.

**Détecter mes médias** est activé par défaut ; vous pouvez le décocher dans les options.
Avec un VPN, le serveur affiche l'adresse Wi-Fi ou Ethernet pour les connexions locales.

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
- Photos par dossiers repliables, albums ou date de prise de vue, galerie et diaporama.
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
Le lecteur Windows est dans `desktop`. Les scripts de construction et d'installation sont dans `tools`.
Pour construire son installateur sous Windows : `./tools/Build-PC.ps1`.
`./tools/Test-PC.ps1` vérifie le paquet et la navigation dans une vidéo de douze minutes.

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
