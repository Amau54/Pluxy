# APK de test — Pluxy 1.7.20 (74)

Cette mise à jour ajoute **Passer l’intro** et **Passer le générique**, sans clé API.
Les chapitres clairement nommés servent de repères. Sans chapitres, définissez
le début et la fin du passage dans **Options → Intro et générique** du lecteur.
Une intro peut être réutilisée pour une saison. Les repères restent sur l’appareil ;
le générique reste propre à chaque vidéo. Définir sa fin permet de conserver
une éventuelle scène après le générique.

Le profil Général est utilisé par défaut. Les profils supplémentaires se
réactivent dans **Réglages → Options**. Ce sous-menu regroupe aussi les réglages
secondaires. Les commandes du lecteur utilisent de nouvelles icônes, avec une
sélection plus discrète et des libellés facultatifs dans les options d’accessibilité.

Les reprises de lecture sont conservées localement pendant une coupure réseau,
les grandes bibliothèques utilisent un index persistant et les correspondances
de films et de séries peuvent être corrigées depuis leur fiche.

Le lecteur Windows 1.8.14 propose aussi les repères d’intro et de générique,
les profils facultatifs, un mini lecteur musical et la sauvegarde des réglages.
L’installateur Windows est disponible sur le canal PC ; il n’est pas signé
avec un certificat Authenticode de confiance.

Une seule APK Android TV/mobile avec les modes Lecteur et Serveur, hors Google Play.
Les images des cartes suivent leurs coins arrondis, même avec le contour de sélection.
Les captures vidéo remplissent aussi les cartes de films, sans bandes grises.
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
