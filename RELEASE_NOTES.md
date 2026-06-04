Gros tampon pour les 4K + flèches qui ne cassent plus la navigation

Deux améliorations attendues :

1) Tampon costaud (gros fichiers 4K à haut débit)
   Le vrai facteur limitant n'était pas la durée du tampon mais sa TAILLE en
   octets, plafonnée bien trop bas sur TV (quelques secondes de 4K seulement).
   - large heap activé + plafond d'octets relevé sur TV (jusqu'à la moitié du
     grand heap), avec un plancher de 600 Mo -> le tampon retient désormais des
     dizaines de secondes de 4K et absorbe les pics de débit.
   - Coussin de reprise après blocage plus généreux.
   - La bascule adaptative de qualité redevient un simple FILET DE SECOURS : elle
     ne se déclenche plus que si le réseau est réellement insuffisant (pas sur un
     pic ponctuel d'un gros fichier).

2) Flèches gauche/droite (D-pad)
   Le décalage temporel ne se fait plus QUE lorsque la barre de progression est
   sélectionnée (contrôles ouverts, focus sur la timeline). En lecture nue ou
   dans les menus, les flèches servent à la navigation normale -> elles ne
   cassent plus le déplacement du focus.

Correctif client uniquement (le serveur n'a que des défauts ajustés pour les
nouvelles installations). Installez la v1.0.22 par-dessus l'existante.
