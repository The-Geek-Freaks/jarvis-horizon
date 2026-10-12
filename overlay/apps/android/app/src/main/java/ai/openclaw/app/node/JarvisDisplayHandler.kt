package ai.openclaw.app.node

import ai.openclaw.app.gateway.GatewaySession
import ai.openclaw.app.home.DoorCameraAction
import ai.openclaw.app.home.DoorCameraDisplayState
import ai.openclaw.app.home.HomeCamera
import ai.openclaw.app.home.HomeCameraSelection
import ai.openclaw.app.home.HomeDashboardRepository
import ai.openclaw.app.home.RingIntercomMicrophoneLease
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal fun isSupportedJarvisDisplayAction(action: String): Boolean =
  action in setOf("open", "fullscreen", "minimize", "close", "status", "list", "intercom_start", "intercom_stop")

/** Intercom always targets the already visible live source, never a name-selected camera. */
internal fun jarvisDisplayActionAllowsCamera(action: String): Boolean = action == "open" || action == "fullscreen"

/** Fixed local Jarvis display controls; it never accepts URLs or arbitrary destinations. */
interface JarvisDisplayHandler {
  suspend fun handleJarvisDisplay(paramsJson: String?): GatewaySession.InvokeResult
}

internal class HomeDashboardJarvisDisplayHandler(
  private val repository: HomeDashboardRepository,
  private val acquireIntercomMicrophone: suspend () -> RingIntercomMicrophoneLease?,
) : JarvisDisplayHandler {
  override suspend fun handleJarvisDisplay(paramsJson: String?): GatewaySession.InvokeResult {
    val params = parseJsonParamsObject(paramsJson)
      ?: return nodeInvokeError("INVALID_REQUEST", "expected action: open, fullscreen, minimize, close, status, list, intercom_start, or intercom_stop")
    val action = parseJsonString(params, "action")?.trim()?.lowercase()
      ?: return nodeInvokeError("INVALID_REQUEST", "expected action: open, fullscreen, minimize, close, status, list, intercom_start, or intercom_stop")
    val camera = parseJsonString(params, "camera")?.trim()
    if (camera != null && camera.isEmpty()) return nodeInvokeError("INVALID_REQUEST", "camera must not be empty")
    if (!isSupportedJarvisDisplayAction(action)) return nodeInvokeError("INVALID_REQUEST", "unsupported display action")
    if (camera != null && !jarvisDisplayActionAllowsCamera(action)) {
      return nodeInvokeError("INVALID_REQUEST", "camera is allowed only with open or fullscreen")
    }
    if (action == "intercom_start") {
      if (camera != null) return nodeInvokeError("INVALID_REQUEST", "intercom_start uses the current live camera")
      val route = repository.prepareDoorIntercom()
        ?: return nodeInvokeError("INTERCOM_UNAVAILABLE", "the configured Ring door source is unavailable")
      val lease = acquireIntercomMicrophone()
        ?: return nodeInvokeError("MIC_BUSY", "microphone is unavailable for door intercom")
      if (!repository.startDoorIntercom(route, lease)) {
        return nodeInvokeError("INTERCOM_UNAVAILABLE", "open a live door camera before starting intercom")
      }
      return GatewaySession.InvokeResult.ok(displayResult(action, repository.doorCameraDisplay.value))
    }
    if (action == "intercom_stop") {
      if (camera != null) return nodeInvokeError("INVALID_REQUEST", "intercom_stop does not accept camera")
      if (!repository.stopDoorIntercom()) {
        return nodeInvokeError("INTERCOM_NOT_ACTIVE", "door intercom is not active")
      }
      return GatewaySession.InvokeResult.ok(displayResult(action, repository.doorCameraDisplay.value))
    }
    if (action == "list") {
      return if (camera != null) {
        nodeInvokeError("INVALID_REQUEST", "list does not accept camera")
      } else {
        repository.listCameras().fold(
          onSuccess = { cameras -> GatewaySession.InvokeResult.ok(cameraCatalogResult(cameras)) },
          onFailure = { nodeInvokeError("UNAVAILABLE", "camera catalog is unavailable") },
        )
      }
    }
    if (camera != null) {
      return when (val selected = repository.openCamera(camera, fullscreen = action == "fullscreen")) {
        is HomeCameraSelection.Selected -> GatewaySession.InvokeResult.ok(displayResult(action, repository.doorCameraDisplay.value))
        is HomeCameraSelection.Ambiguous -> nodeInvokeError("AMBIGUOUS_CAMERA", "camera name matches: ${selected.cameras.joinToString(", ") { it.name.take(96) }}")
        is HomeCameraSelection.Unavailable -> nodeInvokeError("UNAVAILABLE", "camera is unavailable: ${selected.camera.name.take(96)}")
        HomeCameraSelection.Missing -> nodeInvokeError("CAMERA_NOT_FOUND", "camera is not known to Home Assistant")
      }
    }
    val state =
      when (action) {
        "open" -> repository.handleDoorCameraAction(DoorCameraAction.Open)
        "fullscreen" -> repository.handleDoorCameraAction(DoorCameraAction.Fullscreen)
        "minimize" -> repository.handleDoorCameraAction(DoorCameraAction.Minimize)
        "close" -> repository.handleDoorCameraAction(DoorCameraAction.Close)
        "status" -> repository.doorCameraDisplay.value
        else -> return nodeInvokeError("INVALID_REQUEST", "unsupported display action")
      }
    return GatewaySession.InvokeResult.ok(displayResult(action, state))
  }

  private fun displayResult(
    action: String,
    state: DoorCameraDisplayState,
  ): String =
    buildJsonObject {
      put("action", JsonPrimitive(action))
      put("presentation", JsonPrimitive(state.presentation.name.lowercase()))
      put("lifecycle", JsonPrimitive(state.lifecycle.name.lowercase()))
      put("manual", JsonPrimitive(state.manual))
      state.camera?.let { camera ->
        put("cameraEntity", JsonPrimitive(camera.entityId))
        put("cameraName", JsonPrimitive(camera.name))
        put("supportsLive", JsonPrimitive(camera.supportsLive))
      }
    }.toString()

  private fun cameraCatalogResult(cameras: List<HomeCamera>): String =
    buildJsonObject {
      put("cameras", buildJsonArray {
        cameras.forEach { camera ->
          add(
            buildJsonObject {
              put("entityId", JsonPrimitive(camera.entityId))
              put("name", JsonPrimitive(camera.name))
              put("available", JsonPrimitive(camera.available))
              put("supportsLive", JsonPrimitive(camera.supportsLive))
              put("aliases", buildJsonArray { camera.aliases.sorted().forEach { add(JsonPrimitive(it)) } })
            },
          )
        }
      })
    }.toString()
}
