## Windows 1.8.0 · Android 1.7.6

- Windows : une application unique avec choix Lecteur ou Serveur, mémorisé et modifiable dans les options.
- Téléchargement PC : installateur graphique, raccourci et désinstallation ; le dépôt complet n'est plus nécessaire.
- Lecteur PC : films, séries par saisons, vidéos, dossiers photos, musique, thèmes et lecture native des fichiers originaux.
- Serveur PC : préparation de Python et FFmpeg sans fenêtre de commande ; démarrage automatique via l'application installée.
- Android : reprise du rendu Dolby/DTS après déplacement, cible conservée pendant le chargement et secours temporel en copie vidéo/audio sur serveur PC.
- Navigation : maintien gauche/droite progressif jusqu'à ×64, sans multiplier les ouvertures de flux pendant le maintien.
- Android : Media3 1.11.1, avec les correctifs récents de navigation Matroska et de rendu.

# Pluxy 1.7.5

Une seule application Android : **version 1.7.5, versionCode 59**.

- Navigation dans le film corrigée : les flux avec adaptation du son redémarrent à la position demandée, en conservant la vidéo originale. Cette navigation nécessite aussi la mise à jour du serveur PC.
- Les dossiers de saisons avec des tags de qualité ou de release sont regroupés sous une seule série. Les saisons et épisodes restent séparés dans sa fiche.
- Photos : sous-dossiers repliés au départ, images de la racine visibles, boutons « Tout déplier » et « Tout replier », état mémorisé par serveur.
- Serveur PC : catégories Photos et Musique disponibles pour les dossiers, même avec une ancienne liste d’extensions vidéo.
- Les images des cartes suivent les angles arrondis et remplissent leur cadre.

- Si le serveur devient inaccessible, Pluxy recherche automatiquement les serveurs du réseau et vérifie qu’ils répondent. Un seul serveur disponible se connecte directement ; plusieurs ouvrent un choix. Si aucun n’est disponible, la recherche continue en arrière-plan.
- Les vérifications réseau ont un délai court. Lors d’un changement de serveur, l’ancienne bibliothèque est retirée avant de charger la nouvelle ; les réglages propres à chaque serveur restent conservés.

- Choix Lecteur ou Serveur au premier lancement. Le mode reste mémorisé et se change dans les options des deux interfaces. Le passage au lecteur arrête le serveur sans effacer ses réglages.
- Diaporama : gauche/droite passe directement aux images, haut/bas change de dossier. Un indicateur discret donne le dossier courant ; les images voisines et les dossiers adjacents sont préchargés.
- Navigation horizontale TV : les cartes restent dans leur ligne, les éléments hors écran sont parcourus et les actualisations ne reprennent plus le focus pendant un déplacement.
- Les vidéos personnelles se lancent directement. Films et séries gardent leur fiche.
- Marges et espaces entre les cartes légèrement réduits, avec les zones tactiles conservées.

Cette APK met à jour l’ancien lecteur Pluxy. L’ancienne application Pluxy Serveur est un autre package : arrêtez son serveur, puis sélectionnez vos dossiers et renseignez vos réglages dans le mode Serveur de Pluxy. Aucun fichier média n’est déplacé.

[Téléchargements](https://github.com/Amau54/Pluxy/releases/tag/dev-latest) · [Signature des APK](docs/DEBUG_SIGNING.md).
