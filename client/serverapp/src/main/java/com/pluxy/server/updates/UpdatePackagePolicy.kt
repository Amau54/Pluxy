package com.pluxy.server.updates

internal data class UpdatePackageIdentity(
    val packageName: String,
    val version: String?,
    val code: Long,
    val certificates: Set<String>,
)

internal enum class UpdatePackageFailure(val message: String) {
    ALREADY_CURRENT("Pluxy est déjà à jour. Vérifiez à nouveau pour rechercher une version plus récente."),
    WRONG_APPLICATION("Ce téléchargement appartient à une autre application. Relancez la vérification."),
    STALE_DOWNLOAD("Ce fichier ne correspond plus à la version proposée. Relancez le téléchargement."),
    UNREADABLE_SIGNATURE("Android n’a pas pu vérifier la signature de ce fichier. Relancez le téléchargement."),
    DIFFERENT_SIGNATURE("Cette installation utilise une autre clé de signature. Elle ne peut pas recevoir cet APK comme mise à jour."),
}

internal object UpdatePackagePolicy {
    fun failure(installed: UpdatePackageIdentity, advertised: UpdatePackageIdentity, downloaded: UpdatePackageIdentity): UpdatePackageFailure? = when {
        downloaded.packageName != installed.packageName || advertised.packageName != installed.packageName -> UpdatePackageFailure.WRONG_APPLICATION
        downloaded.code != advertised.code || downloaded.version != advertised.version -> UpdatePackageFailure.STALE_DOWNLOAD
        advertised.code <= installed.code -> UpdatePackageFailure.ALREADY_CURRENT
        installed.certificates.isEmpty() || downloaded.certificates.isEmpty() || advertised.certificates.isEmpty() -> UpdatePackageFailure.UNREADABLE_SIGNATURE
        downloaded.certificates != installed.certificates || downloaded.certificates != advertised.certificates -> UpdatePackageFailure.DIFFERENT_SIGNATURE
        else -> null
    }
}
