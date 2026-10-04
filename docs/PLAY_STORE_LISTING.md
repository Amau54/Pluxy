# Fiches Google Play préparées

Les textes ci-dessous décrivent Pluxy 1.5.1. Ils ne doivent être publiés qu'après
la validation de la piste interne et la mise en ligne de la politique de
confidentialité sur une URL publique stable. Les mentions DLNA/Google Cast doivent être
retirées si la matrice physique multi-marques n'est pas validée.

## Pluxy — `com.pluxy.tv`

**Nom :** Pluxy

**Description courte :**

> Films, séries, photos et musique de votre serveur local, sans compte.

**Description complète :**

> Pluxy transforme votre Android TV, téléviseur compatible ou appareil Android en
> lecteur pour vos médias personnels.
>
> L'application découvre automatiquement un serveur Pluxy sur votre réseau
> domestique. Une interface épurée donne accès aux onglets Films, Séries, Photos et
> Musique en quelques actions. Recherchez dans toute la bibliothèque, parcourez des
> albums photo, écoutez une file musicale par album, enchaînez les épisodes, reprenez
> une vidéo et utilisez les sous-titres. L'accueil met en avant Reprendre, À suivre et
> les ajouts récents. Un mode avancé rend
> disponibles les tris, informations réseau et réglages techniques.
>
> Direct Play est prioritaire. En mode Automatique, Pluxy détecte les blocages réseau,
> conserve votre position et choisit un débit adapté ; après une période stable, il peut
> remonter prudemment en qualité. Vous pouvez aussi imposer Direct Play ou le profil
> Compatibilité. Le serveur Android partage le fichier original avec un tampon renforcé ;
> le serveur PC peut assurer le transcodage.
>
> Le lecteur Android TV propose une timeline pensée pour la télécommande, des commandes
> protégées des bords de l'écran et des raccourcis Audio, Sous-titres et Options.
>
> Pluxy ne fournit aucun film, aucun abonnement et aucun accès à un catalogue tiers.
> Vous choisissez votre propre serveur et restez responsable des fichiers partagés.
> Aucun compte, publicité ou suivi analytique n'est intégré.
>
> Important : Pluxy est conçu pour un Wi-Fi/Ethernet domestique privé de confiance.
> Les échanges vidéo locaux utilisent HTTP et tous les appareils du LAN peuvent voir
> le serveur volontairement ouvert.

Catégorie proposée : **Lecteurs et éditeurs vidéo**. Publicité : **Non**. Accès à
l'application : **aucun compte requis**.

## Pluxy Serveur — `com.pluxy.server`

**Nom :** Pluxy Serveur

**Description courte :**

> Partagez films, photos et musique du téléphone sur votre réseau privé.

**Description complète :**

> Pluxy Serveur transforme votre téléphone ou tablette en bibliothèque locale pour
> l'application Pluxy installée sur la télévision.
>
> Sélectionnez explicitement vos dossiers Films, Photos et Musique avec le sélecteur
> Android, démarrez le serveur, puis éteignez l'écran : un service au premier plan
> et sa notification
> maintiennent le transfert pendant la lecture. L'index, les réglages et la progression
> restent sur vos appareils. Les miniatures photo, les pochettes audio et les images de
> remplacement des vidéos sans affiche sont générées localement et
> l'enrichissement TMDB des films et séries est facultatif.
>
> Vous pouvez aussi choisir un film, une photo ou un morceau dans trois onglets séparés,
> puis l'envoyer en Direct Play vers Google Cast ou une télévision DLNA. Lecture, pause,
> position et arrêt restent contrôlables depuis le téléphone et sa notification. La
> compatibilité est vérifiée avant l'envoi et mémorisée séparément pour chaque TV.
>
> Aucun compte, publicité, télémétrie propre à Pluxy ou cloud Pluxy n'est utilisé. Le
> SDK Google Cast facultatif traite des événements techniques anonymisés selon la
> politique Google décrite dans notre politique de confidentialité. Pluxy ne fournit
> aucun contenu ; vous restez responsable des fichiers que vous choisissez.
>
> Important : tous les appareils de votre LAN de confiance peuvent découvrir le
> serveur et lire les médias sélectionnés. N'utilisez pas l'application sur un réseau
> public et ne redirigez jamais son port vers Internet.

Catégorie proposée : **Lecteurs et éditeurs vidéo**. Publicité : **Non**. Accès à
l'application : **aucun compte requis**.

## Éléments communs à fournir

- icône Play 512 × 512 sans transparence inutile ;
- visuel de présentation 1 024 × 500 ;
- au moins deux captures réelles par type d'appareil distribué, sans film ni affiche
  dont les droits ne permettent pas la publication ;
- captures TV navigables à la télécommande montrant les quatre onglets et les modes
  simple/avancé ;
- captures téléphone montrant les trois sources, les onglets d'envoi, la sélection d'une
  TV de test, le serveur actif et la notification pour le serveur Android ;
- adresse de contact du compte développeur et URL publique de
  `docs/PRIVACY_POLICY.md` ;
- attribution TMDB visible, sans présenter TMDB comme partenaire ou certificateur.

Ne pas utiliser de captures générées ou retouchées qui représenteraient une fonction
absente. Masquer les adresses IP, noms de fichiers, clés et informations privées.

## Décisions qui restent au titulaire du compte

- choisir et ajouter la licence de distribution du projet : le dépôt n'en contient
  actuellement aucune et ce choix juridique ne doit pas être présumé ;
- confirmer le nom légal/public du développeur et une adresse de contact durable ;
- répondre au questionnaire IARC selon les captures et le contenu réellement montré ;
- choisir les pays, le prix et le public cible ;
- valider les déclarations `docs/DATA_SAFETY.md` dans la taxonomie affichée le jour de
  l'envoi.
