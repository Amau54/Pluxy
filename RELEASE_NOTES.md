# Pluxy 1.6.2

Lecteur et serveur Android : **version 1.6.2, versionCode 53**.

- Photos en arborescence : racines distinctes, sous-dossiers en cascade et images visibles par défaut. Albums et chronologie restent disponibles.
- Films, séries et vidéos personnelles séparés. Chaque dossier du serveur PC ou Android peut être classé automatiquement ou recevoir un type choisi.
- La détection utilise les dossiers, noms de caméra, épisodes, durée et titre intégré. Sans indice fiable, le fichier reste dans Vidéos.
- Miniatures vidéo choisies parmi plusieurs captures, avec préférence pour une image visible plutôt qu’un fondu noir ou blanc. Cache réutilisé et chargements réessayés lorsque le serveur est occupé.
- Installation automatique de FFmpeg et ffprobe sur Windows, dans le compte utilisateur.
- TMDB accepte les clés API et les jetons de lecture. Les titres sont vérifiés avant d’attribuer une affiche ; le serveur PC distingue les recherches sans résultat des erreurs de connexion ou de clé.
- Bouton de diagnostic TMDB et miniatures dans les réglages du serveur PC.

Le classement ne déplace aucun fichier. Pour corriger une détection, changez la catégorie du dossier puis relancez l’analyse.

[Téléchargements](https://github.com/Amau54/Pluxy/releases/tag/dev-latest) · [Signature des APK](docs/DEBUG_SIGNING.md).
