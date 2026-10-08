# APK de test — Pluxy 1.7.3 (57)

Une seule APK Android TV/mobile avec les modes Lecteur et Serveur, hors Google Play.
Choisissez le mode au premier lancement ; il se change ensuite dans les options.
Cette APK conserve l’identité de l’ancien lecteur. Si vous utilisiez Pluxy Serveur,
arrêtez l’ancien serveur et sélectionnez vos dossiers dans la nouvelle application.
Android ne transfère pas les autorisations entre les deux packages.

Bibliothèques en grille, filtres, collections, albums photo et musique.
Navigation TV/mobile compacte et choix de densité dans les réglages.
Direct Play démarre avec un seuil de lecture plus court, tout en conservant le
tampon réseau. Une erreur audio ne marque plus HEVC comme incompatible ; les
anciennes limites apprises sont réinitialisées. En automatique, un échec audio
sur une vidéo originale déclenche une nouvelle négociation du son avec le serveur PC.
Sortie audio : Automatique, Passthrough forcé ou Audio compatible. Le mode automatique
privilégie une adaptation surround avant le repli stéréo. Vidéo originale (4K/HDR)
conserve la vidéo même si le son doit être adapté. Le choix reste accessible après
une erreur audio. Le passthrough réel dépend de la liaison et de l'ampli.
La recherche d'un serveur de remplacement est automatique si le serveur actuel
ne répond plus ; plusieurs serveurs disponibles donnent lieu à un choix.
`BUILD_INFO.json` indique le commit, la version et les certificats.
Les sommes de contrôle sont dans `SHA256SUMS.txt`.

Les clés du canal ont changé le 4 octobre 2026. Si une ancienne application
porte une autre signature, sauvegardez les réglages possibles puis désinstallez-la
avant d'installer cette APK. Ses données privées seront effacées ; les fichiers
médias stockés ailleurs restent sur leur support. Reconfigurez ensuite les
permissions et les dossiers du serveur.

Les prochaines mises à jour avec la même signature conservent normalement les données.
[Guide de signature](https://github.com/Amau54/Pluxy/blob/main/docs/DEBUG_SIGNING.md).
