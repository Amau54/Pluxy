# Composants tiers et crédits

## Pictogrammes du lecteur

Lucide, sous licence ISC, fournit les pictogrammes du lecteur. Les chiffres des
commandes de saut proviennent de Material Symbols Rounded, Google, sous licence
Apache 2.0. Les SVG d’origine et leurs licences sont dans `assets/player-icons`.
Les versions Android et Windows utilisent les mêmes tracés, sans police distante.

## FFmpeg sur Windows

Le lanceur télécharge séparément FFmpeg et ffprobe 9.0.2 depuis
[Gyan.dev](https://www.gyan.dev/ffmpeg/builds/), un fournisseur de binaires référencé
par [FFmpeg](https://ffmpeg.org/download.html). Le build Essentials est sous GPLv3 ;
sa licence et sa documentation restent dans le dossier installé. Son
[code source correspondant](https://github.com/FFmpeg/FFmpeg/commit/946fcce07b)
est disponible publiquement. Les exécutables sont lancés comme processus séparés.

## AndroidX, Media3 et Google Cast

Les applications utilisent AndroidX et Media3, sous licence
[Apache 2.0](https://github.com/androidx/media/blob/release/LICENSE).
Pluxy Serveur utilise aussi le SDK Google Cast pour l'envoi vers un appareil Cast.
Google Cast et Google Play services ont leurs propres
[conditions d'utilisation](https://developers.google.com/cast/docs/terms) et leur
[déclaration de données](https://developers.google.com/cast/docs/android_sender/data_disclosure).

Google Cast est une marque de Google LLC. Pluxy est indépendant de Google.

## OkHttp

OkHttp assure une partie des échanges réseau des applications Android. Il est
distribué sous [licence Apache 2.0](https://github.com/square/okhttp/blob/master/LICENSE.txt).

## Reconnaissance de texte

Le serveur Android utilise le modèle latin intégré de Google ML Kit pour lire les
crédits des génériques. Son utilisation relève des
[conditions de ML Kit](https://developers.google.com/ml-kit/terms).
Les images et le texte sont traités sur l’appareil. Les données techniques collectées
par ce SDK sont décrites dans la [politique de confidentialité](PRIVACY_POLICY.md).

## The Movie Database

L'API et les images TMDB sont utilisées lorsque les métadonnées TMDB sont activées.

This product uses the TMDB API but is not endorsed or certified by TMDB.

Le logo des écrans de crédits reprend le logo officiel « Asset 4 » de TMDB, créé par
Travis Bell. Le tracé et les proportions sont conservés dans les fichiers Android
`tmdb_logo.xml`. La version blanche suit les
[règles de marque TMDB](https://developer.themoviedb.org/docs/faq).

Le [SVG de référence](https://commons.wikimedia.org/wiki/File:Tmdb.new.logo.svg) est
distribué sous [licence Creative Commons Attribution-ShareAlike 4.0](https://creativecommons.org/licenses/by-sa/4.0/).
L'adaptation Android reprend ce tracé en blanc. Cette licence concerne le logo,
pas les affiches ni les autres images renvoyées par l'API.

## Lecteur Windows

Le lecteur Windows utilise Microsoft WebView2 et le moteur mpv dans un processus séparé.
Les licences sont conservées dans le dossier `licenses` de l'installation.
Les versions et empreintes de compilation sont dans `desktop/dependencies.json`.
Voir [les notices Windows](../desktop/THIRD_PARTY.txt) pour les sources et distributions.
