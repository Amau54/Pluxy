## Windows 1.8.32 · Android 1.7.40

- Séries : le décompte de l’épisode suivant apparaît pendant les cinq dernières secondes du média, puis la lecture enchaîne à sa fin, sans ajouter cinq secondes d’attente.
- Le décompte suit la position de lecture : une pause ou une attente réseau ne coupe pas les dernières images. Revenir en arrière ferme la proposition ; la désactivation de la lecture automatique conserve l’épisode jusqu’à sa fin.

## Windows 1.8.31 · Android 1.7.39

- Lecteur : « Passer l’intro » et « Passer le générique » plus grands et plus lisibles sur TV, avec une police renforcée et une ombre douce, toujours sans fond.
- TV : Haut depuis la première rangée de l’accueil rejoint la recherche, sans retour inattendu sur Accueil dans le rail gauche.
- Android : une mise à jour disponible est proposée à la prochaine ouverture de l’application même si une proposition a été fermée auparavant. Les changements d’écran et rotations ne multiplient pas les propositions.
- Windows : vérification immédiate à la réouverture depuis la zone de notification, avec proposition au premier lancement et sans répétition dans la même session.
- Le style général du catalogue reste celui de la version 1.7.37. La retouche Google TV reste limitée au panneau de fin de lecture.

## Windows 1.8.30

- Option serveur « Ranger les médias vus dans Vu », désactivée par défaut et disponible uniquement avec un seul profil. Un second profil suspend le rangement.
- À l’activation, choix entre les prochains visionnages uniquement et le rangement des médias déjà vus. Les fichiers sont déplacés après la lecture, avec leurs sous-titres, dans un sous-dossier Vu à côté de chaque média.
- Identifiants, classement des séries, historique et emplacement affiché conservés après déplacement et redémarrage. Aucun écrasement d’un fichier existant ; les opérations interrompues peuvent être récupérées.
- Mise à jour de Pillow vers 13.0.0 pour corriger les alertes de sécurité signalées par la vérification des dépendances.

## Windows 1.8.29

- Analyse des intros et génériques : priorité basse, décodage et filtres sur un seul thread, processus d’analyse limités à un cœur et pauses entre les traitements. La préparation attend lorsque le PC fonctionne sur batterie ou que le processeur est très sollicité.
- Les intros sont préparées d’abord dans la bibliothèque. Les génériques sont recherchés pendant la lecture du média concerné, avec le même budget réduit ; les autres médias restent en attente.
- Réutilisation des résultats déjà calculés, y compris l’intro quand le générique reste à analyser. Les requêtes successives du lecteur et les changements de position conservent le travail en cours.

## Android 1.7.38

- Épisode suivant : image au format 16:9, typographie allégée et alignements harmonisés. Décompte discret avec une fine ligne de progression, toujours sans fond de panneau.
- Conservation du lancement après cinq secondes et de l’annulation immédiate avec OK sur la télécommande.

## Android 1.7.37

- Épisode suivant : suppression du fond et du contour du panneau. L’image et les textes se superposent directement à la vidéo ; une ombre légère garde le texte lisible.

## Windows 1.8.28 · Android 1.7.36

- TV : paramètres en bas du rail gauche, sous Musique. Présentation, densité et thème sont regroupés dans Paramètres → Affichage.
- Recherche limitée à la rubrique ouverte, avec les filtres dans le même panneau. Suppression des boutons Affichage et Filtres au-dessus du catalogue TV.
- Tri des films et séries par date de sortie, avec repli sur l’année connue. Les dates inconnues restent à la fin du tri.
- Films déjà vus placés après les autres, même avec plusieurs pages ; rangée Déjà vus à la fin du mode Découvrir.
- Bannière du lanceur Android TV recentrée pour afficher le logo et le nom Pluxy complet.

## Windows 1.8.27 · Android 1.7.35

- TV : passage de l’intro ou du générique avec un seul appui sur OK pendant la lecture, sans ouvrir les commandes. Texte blanc discret en bas à droite, sans fond ; aucune proposition pour une intro de moins de huit secondes.
- Épisode suivant : image agrandie, panneau sombre et décompte de cinq secondes. L’unique action Annuler ramène directement à la bibliothèque ; le réglage de lecture automatique reste respecté.
- Un média est considéré comme vu dès 90 % de lecture sur les deux serveurs, y compris dans l’historique existant.
- Windows : une mise à jour disponible est proposée au lancement même si une proposition précédente avait été fermée.

## Windows 1.8.26 · Android 1.7.34

- Préparation automatique des intros et génériques après l’ajout des films et épisodes à la bibliothèque, avant toute demande du lecteur.
- Vérification périodique des nouveaux fichiers, priorité aux ajouts récents et traitement d’un seul média à la fois.
- La lecture interrompt les préparations des autres médias. Les repères déjà trouvés restent enregistrés ; le travail reprend ensuite.
- Réutilisation du cache après redémarrage et nouvelle tentative après une interruption. Une analyse en cours peut encore manquer au tout premier lancement immédiat d’un fichier.

## Windows 1.8.8 · Android 1.7.14

- Commandes vidéo plus discrètes, icônes Windows redessinées et couleurs des trois thèmes harmonisées.
- Aperçus pendant la navigation dans une vidéo, préparés à la demande et mis en cache par le serveur PC ou Android 8.1 et plus. Le temps reste disponible quand l'aperçu ne peut pas être créé.
- Fiches plus compactes, synopsis abrégé avec accès au texte complet et bande-annonce à côté de Lire sur Windows.
- Épisodes TV plus compacts, métadonnées regroupées et état vu conservé sur toute la ligne.
- Proposition de l'épisode suivant avec décompte et progression discrets. Retour et passage en arrière-plan annulent le lancement automatique ; le réglage de lecture automatique reste respecté.
- Accueil Windows avec temps restant sur les reprises, aperçus des dossiers photos et pochettes des albums.

## Windows 1.8.1 · Android 1.7.6

- Windows : une application unique avec choix Lecteur ou Serveur, mémorisé et modifiable dans les options.
- Téléchargement PC : installateur graphique, raccourci et désinstallation ; le dépôt complet n'est plus nécessaire.
- Lecteur PC : films, séries par saisons, vidéos, dossiers photos, musique, thèmes et lecture native des fichiers originaux.
- Serveur PC : préparation de Python et FFmpeg sans fenêtre de commande ; démarrage automatique via l'application installée.
- Android : reprise du rendu Dolby/DTS après déplacement, cible conservée pendant le chargement et secours temporel en copie vidéo/audio sur serveur PC.
- Navigation : maintien gauche/droite progressif jusqu'à ×64, sans multiplier les ouvertures de flux pendant le maintien.
- Android : Media3 1.11.1, avec les correctifs récents de navigation Matroska et de rendu.
- Serveur PC : les flux abandonnés après un déplacement libèrent immédiatement FFmpeg et leur place réservée, pour éviter les erreurs 503 après plusieurs reprises.
- Windows : changement de mode rafraîchi même depuis le lecteur déjà ouvert ; les flèches du clavier restent utilisables depuis les commandes vidéo.

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
