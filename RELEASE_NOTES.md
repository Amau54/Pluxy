Fin des pauses toutes les 5 minutes pendant la lecture

L'économiseur d'écran / la mise en veille de l'Android TV s'enclenchait après
quelques minutes sans action sur la télécommande et mettait le film en PAUSE,
puis recommençait à chaque cycle (~5 min).

Correctifs (client uniquement) :
- L'écran est désormais maintenu éveillé tant que le lecteur est ouvert
  (FLAG_KEEP_SCREEN_ON) : plus d'économiseur ni de veille pendant un film.
- Wake lock CPU + Wi-Fi pendant la lecture (setWakeMode) : empêche
  l'endormissement réseau qui pouvait couper le flux.

Correctif client uniquement : pas besoin de redémarrer le serveur.
Installez la v1.0.20 par-dessus l'existante (signature stable, aucune
désinstallation nécessaire).
