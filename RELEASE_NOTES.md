Buffering plus intelligent : fini les pauses à répétition sur Wi-Fi instable

Quand le réseau (Wi-Fi double saut) ne suivait pas le débit d'un film 4K HDR en
lecture directe, le tampon se vidait et le lecteur se figeait pour recharger,
parfois en boucle. La lecture ne réagissait pas (un rebuffering n'est pas une
erreur). Deux améliorations, façon Plex/Jellyfin :

- Tampon plus résilient : coussin minimal garanti et, surtout, REPRISE après un
  blocage avec une vraie avance -> plus de cycle « pause / reprise / pause ».
- Bascule adaptative automatique : si la lecture bégaie plusieurs fois, Pluxy
  descend d'un palier pour tenir le débit, SANS perdre la position :
    Lecture directe  ->  HEVC/HDR transcodé à débit plafonné (qualité quasi
    intacte, HDR conservé)  ->  H.264 1080p compatible (dernier recours).
  La qualité d'origine est retentée à la prochaine ouverture du film.

Correctif client uniquement : pas besoin de redémarrer le serveur.
Installez la v1.0.21 par-dessus l'existante (signature stable, aucune
désinstallation nécessaire).
