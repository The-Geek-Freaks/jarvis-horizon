package ai.openclaw.app.voice

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A deliberately small, output-free description of a live tool invocation.
 *
 * Tool arguments may contain commands, patches, credentials, or user text. Only
 * allowlisted operation words and a basename or host are projected to the HUD.
 */
internal fun jarvisToolDetail(
  name: String,
  args: JsonObject?,
): String? {
  val normalized = name.jarvisToolHudName()
  return when (normalized) {
    "apply_patch", "applypatch", "patch", "edit", "edit_file", "multiedit", "multi_edit",
    "write", "write_file", "create_file" -> args?.safePathBasename()?.let { "Datei · $it" } ?: "Dateiänderung"
    "read", "read_file", "readfile", "notebookread", "notebook_read" -> args?.safePathBasename()?.let { "Lesen · $it" } ?: "Datei lesen"
    "grep", "find", "glob", "ls", "list", "codebase_search" -> "Projekt durchsuchen"
    "ssh" -> args?.safeHost()?.let { "SSH · $it" } ?: "SSH-Verbindung"
    "bash", "exec", "shell", "run_command", "run_terminal_cmd", "terminal", "exec_command" ->
      args?.safeCommandDetail() ?: "Befehl ausführen"
    else -> null
  }
}

/** A controlled title so an untrusted tool name can never become HUD content. */
internal fun jarvisToolDisplayName(name: String): String =
  when (name.jarvisToolHudName()) {
    "apply_patch", "applypatch", "patch", "edit", "edit_file", "multiedit", "multi_edit",
    "write", "write_file", "create_file" -> "Datei"
    "read", "read_file", "readfile", "notebookread", "notebook_read" -> "Lesen"
    "grep", "find", "glob", "ls", "list", "codebase_search" -> "Suche"
    "ssh" -> "SSH"
    "memory_recall", "memory_search", "memory_get", "memory_store" -> "Gedächtnis"
    "hippo_search", "hippo_store", "hippo_recall" -> "Hippo"
    "qmd_search", "qmd_query" -> "QMD"
    "sessions_spawn" -> "Unteragent"
    "sessions_send", "sessions_yield", "subagents" -> "Delegation"
    "nodes" -> "Gerät"
    "bash", "exec", "shell", "run_command", "run_terminal_cmd", "terminal", "exec_command" -> "Shell"
    else -> "Tool"
  }

/** OpenClaw's trusted MCP namespace is a transport prefix, not a HUD label. */
private fun String.jarvisToolHudName(): String =
  trim().lowercase().removePrefix("mcp__openclaw__")

private fun JsonObject.safePathBasename(): String? =
  listOf("path", "filePath", "file_path", "file", "filename")
    .firstNotNullOfOrNull { key -> safeString(key) }
    ?.substringAfterLast('/')
    ?.substringAfterLast('\\')
    ?.takeIf(::isSafeBasename)
    ?.take(JARVIS_TOOL_DETAIL_LIMIT)

private fun JsonObject.safeHost(): String? =
  listOf("host", "hostname", "address", "target")
    .firstNotNullOfOrNull { key -> safeString(key) }
    ?.substringAfterLast('@')
    ?.takeIf(::isSafeHost)
    ?.take(JARVIS_TOOL_DETAIL_LIMIT)

private fun JsonObject.safeCommandDetail(): String? {
  val command = listOf("command", "cmd").firstNotNullOfOrNull { key -> safeString(key) } ?: return null
  val tokens = command.trim().split(Regex("\\s+")).filter(String::isNotBlank)
  val executable = tokens.firstOrNull()?.takeIf(String::isSafeExecutable)?.substringAfterLast('/')?.lowercase()
  if (executable !in SAFE_EXECUTABLES) return null
  if (executable == "ssh") return sshCommandDetail(tokens.drop(1))
  val action = tokens.drop(1).firstOrNull { it.lowercase() in SAFE_COMMAND_ACTIONS }
  return if (action == null) "Befehl · $executable" else "Befehl · $executable ${action.lowercase()}"
}

private fun sshCommandDetail(arguments: List<String>): String {
  var index = 0
  while (index < arguments.size && arguments[index].startsWith('-')) {
    val option = arguments[index]
    index += if (option in SSH_OPTIONS_WITH_SEPARATE_VALUE) 2 else 1
  }
  val host = arguments.getOrNull(index)?.substringAfterLast('@')?.takeIf(::isSafeHost)?.take(JARVIS_TOOL_DETAIL_LIMIT)
  return if (host == null) "SSH-Verbindung" else "SSH · $host"
}

private fun JsonObject.safeString(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull()?.trim()?.takeIf(String::isNotEmpty)

private fun JsonPrimitive.contentOrNull(): String? = if (isString) content else null

private fun isSafeBasename(value: String): Boolean =
  value.length in 1..JARVIS_TOOL_DETAIL_LIMIT && value.all { it.isLetterOrDigit() || it in "._-" }

private fun isSafeHost(value: String): Boolean =
  value.length in 1..JARVIS_TOOL_DETAIL_LIMIT && value.all { it.isLetterOrDigit() || it in ".-" }

private fun String.isSafeExecutable(): Boolean =
  isNotBlank() && all { it.isLetterOrDigit() || it in "._-/" }

private val SAFE_COMMAND_ACTIONS = setOf("status", "test", "build", "check", "install", "start", "stop", "restart", "deploy", "pull", "push", "diff", "log", "fetch", "run", "compile", "assemble", "lint")

private val SAFE_EXECUTABLES = setOf("adb", "ansible", "cargo", "cmake", "curl", "docker", "git", "go", "gradle", "gradlew", "java", "kubectl", "make", "node", "npm", "openclaw", "pnpm", "python", "python3", "rg", "rsync", "scp", "ssh", "systemctl", "terraform", "wget", "yarn")

private val SSH_OPTIONS_WITH_SEPARATE_VALUE = setOf("-b", "-c", "-D", "-E", "-F", "-i", "-J", "-L", "-l", "-m", "-O", "-o", "-p", "-Q", "-R", "-S", "-W", "-w")

private const val JARVIS_TOOL_DETAIL_LIMIT = 48
