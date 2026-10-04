# Barrières de fusion et de publication

Ce document décrit les réglages GitHub et les preuves externes qui ne peuvent pas être
activés par une modification du dépôt. Tant qu'ils ne sont pas configurés par un
administrateur et vérifiés sur des appareils réels, une CI verte n'autorise pas une
publication en production.

## Protection de `main`

Configurer une règle de protection ou un ruleset GitHub pour `main` :

- interdire les pushes directs et les force-pushes ;
- exiger au moins une revue approuvée et l'annulation des approbations après un nouveau
  commit ;
- exiger la résolution de toutes les conversations ;
- exiger une branche à jour avant fusion ;
- appliquer la règle aux administrateurs ;
- exiger les contrôles suivants :
  - `Serveur PC - tests et dependances` ;
  - `Android - tests, lint et bundles` ;
  - `Revue des dependances / Dependances vulnerables` ;
  - `CodeQL (python)` ;
  - `CodeQL (java-kotlin)`.

Activer la détection de secrets, la protection contre les pushes contenant un secret et
les mises à jour de sécurité Dependabot. La CI refuse en complément toute clé JKS/P12 et
tout mot de passe de signature littéral encore suivi par Git. Les dépendances Python
directes sont épinglées et Gradle vérifie les SHA-256 enregistrés dans
`client/gradle/verification-metadata.xml` ; toute mise à jour doit régénérer puis faire
relire explicitement ces sommes dans la pull request.

## Environnement `play-release`

- limiter l'environnement à la branche protégée `main` et aux tags protégés `v*` ;
- demander au moins une approbation humaine avant le job ;
- stocker uniquement les huit secrets listés dans `PLAY_STORE_RELEASE.md` ;
- ne jamais utiliser une clé anciennement exposée ni les clés debug ;
- conserver les journaux et la provenance du run ayant produit chaque artefact.

Le workflow refuse une révision qui n'est pas la tête actuelle de `main`, un tag ne
respectant pas `vMAJEUR.MINEUR.CORRECTIF`, un tag différent du `versionName`, un secret
absent ou une release GitHub existante. Il vérifie les signatures, génère une SBOM
CycloneDX et publie un fichier `SHA256SUMS.txt`. Les tables de désobfuscation R8 sont
conservées dans un artefact CI séparé pendant 90 jours et doivent être transférées dans
Play Console.

## Preuves de sortie

Une release candidate doit d'abord passer par une piste interne, puis fermée. Le dossier
de preuve doit contenir :

- le lien du run CI et son SHA de commit ;
- le rapport Play Pre-launch ;
- les rapports JSON `device_soak.py` ;
- la matrice remplie de `DEVICE_VALIDATION.md` ;
- les anomalies connues et leur décision explicite ;
- les empreintes des certificats Play App Signing et des clés d'envoi, conservées hors
  du dépôt.

La simulation HTTP exécutée en CI teste le runner de soak, `/health` et les réponses
Range. Elle ne prouve ni le maintien Wi-Fi écran éteint, ni le rendu vidéo/audio, ni les
commandes de télécommande sur un appareil physique.
