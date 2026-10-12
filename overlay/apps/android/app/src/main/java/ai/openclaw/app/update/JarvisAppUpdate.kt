package ai.openclaw.app.update

import android.os.Build
import ai.openclaw.app.BuildConfig
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal const val JARVIS_UPDATE_MANIFEST_URL = "" // Public builds ship with OTA disabled.

private const val JARVIS_UPDATE_PATH_PREFIX = "/apks/"
private const val MAX_APK_BYTES = 512L * 1024L * 1024L

internal data class JarvisAppUpdate(
  val packageName: String,
  val versionCode: Long,
  val versionName: String,
  val apkUrl: String,
  val sha256: String,
  val sizeBytes: Long,
  val abis: Set<String>,
)

internal fun parseVerifiedJarvisUpdate(
  envelope: String,
  publicKeyDer: ByteArray,
  installedPackageName: String = BuildConfig.APPLICATION_ID,
  installedVersionCode: Long = BuildConfig.VERSION_CODE.toLong(),
  supportedAbis: Array<String> = Build.SUPPORTED_ABIS,
): JarvisAppUpdate? {
  val root = runCatching { jarvisUpdateJson.parseToJsonElement(envelope).jsonObject }.getOrNull() ?: return null
  if (root.keys != setOf("payload", "signature")) return null
  val payload = root.string("payload")?.decodeBase64OrNull() ?: return null
  val signature = root.string("signature")?.decodeBase64OrNull() ?: return null
  if (!verifyJarvisUpdateSignature(payload, signature, publicKeyDer)) return null
  return parseJarvisUpdatePayload(
    payload = payload,
    installedPackageName = installedPackageName,
    installedVersionCode = installedVersionCode,
    supportedAbis = supportedAbis,
  )
}

internal fun verifyJarvisUpdateSignature(
  payload: ByteArray,
  signature: ByteArray,
  publicKeyDer: ByteArray,
): Boolean =
  runCatching {
    val key = java.security.KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(publicKeyDer))
    val verifier = Signature.getInstance("SHA256withRSA")
    verifier.apply {
      initVerify(key)
      update(payload)
    }
    verifier.verify(signature)
  }.getOrDefault(false)

internal fun parseJarvisUpdatePayload(
  payload: ByteArray,
  installedPackageName: String,
  installedVersionCode: Long,
  supportedAbis: Array<String>,
): JarvisAppUpdate? {
  val root = runCatching { jarvisUpdateJson.parseToJsonElement(payload.decodeToString()).jsonObject }.getOrNull() ?: return null
  if (root.keys != setOf("packageName", "versionCode", "versionName", "apkUrl", "sha256", "sizeBytes", "abis")) return null
  val packageName = root.string("packageName") ?: return null
  val versionCode = root.long("versionCode") ?: return null
  val versionName = root.string("versionName") ?: return null
  val apkUrl = root.string("apkUrl") ?: return null
  val sha256 = root.string("sha256")?.lowercase(Locale.ROOT) ?: return null
  val sizeBytes = root.long("sizeBytes") ?: return null
  val abis =
    (root["abis"] as? JsonArray)
      ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase(Locale.ROOT) }
      ?.toSet()
      ?: return null
  if (packageName != installedPackageName || versionCode <= installedVersionCode || versionName.isBlank()) return null
  if (!sha256.matches(Regex("[0-9a-f]{64}")) || sizeBytes !in 1..MAX_APK_BYTES || abis.isEmpty()) return null
  if (abis.any { it !in JARVIS_SUPPORTED_ABIS }) return null
  if (supportedAbis.none { it.lowercase(Locale.ROOT) in abis }) return null
  if (!isJarvisUpdateUrl(apkUrl)) return null
  return JarvisAppUpdate(packageName, versionCode, versionName, apkUrl, sha256, sizeBytes, abis)
}

internal fun isJarvisUpdateUrl(value: String): Boolean {
  val manifest = JARVIS_UPDATE_MANIFEST_URL.toHttpUrlOrNull() ?: return false
  val candidate = value.toHttpUrlOrNull() ?: return false
  return candidate.scheme == manifest.scheme &&
    candidate.host == manifest.host &&
    candidate.port == manifest.port &&
    candidate.username.isEmpty() &&
    candidate.password.isEmpty() &&
    candidate.encodedPath.startsWith(JARVIS_UPDATE_PATH_PREFIX) &&
    candidate.query == null &&
    candidate.fragment == null
}

private fun JsonObject.string(name: String): String? =
  (this[name] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty)

private fun JsonObject.long(name: String): Long? = (this[name] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()

private fun String.decodeBase64OrNull(): ByteArray? = runCatching { Base64.getDecoder().decode(this) }.getOrNull()

private val jarvisUpdateJson = Json { ignoreUnknownKeys = false; isLenient = false }

private val JARVIS_SUPPORTED_ABIS = setOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
