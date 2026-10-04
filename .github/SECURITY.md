# Politique de sécurité

Le modèle de confiance et les recommandations de déploiement sont décrits dans
[`docs/SECURITY.md`](../docs/SECURITY.md).

Pour signaler une vulnérabilité, privilégiez un rapport privé dans l'onglet
**Security** du dépôt. Si cette option n'est pas disponible, ouvrez uniquement un
ticket minimal sans preuve de concept, clé, chemin privé ni nom de média afin de
convenir d'un canal de transmission.

La branche `main` est prise en charge. Les APK GitHub historiques signés avec la clé
publique compromise constituent un canal legacy et ne reçoivent plus de correctifs de
sécurité. La prise en charge d'une version binaire reprendra avec la première release
validée via Play App Signing et les barrières de `docs/RELEASE_GATES.md`.
