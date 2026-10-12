package ai.openclaw.app.ui

import ai.openclaw.app.home.timers.JarvisScheduleRepository
import ai.openclaw.app.home.timers.JarvisScheduleState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Compact foreground surface; the root Jarvis screen decides where this panel is displayed. */
@Composable
fun JarvisSchedulePanel(
  repository: JarvisScheduleRepository = JarvisScheduleRepository.get(LocalContext.current),
  modifier: Modifier = Modifier,
) {
  val schedules by repository.schedules.collectAsState()
  val active by repository.activeAlert.collectAsState()
  val actions = rememberCoroutineScope()
  val formatter = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
  var nowMs by androidx.compose.runtime.remember { androidx.compose.runtime.mutableLongStateOf(System.currentTimeMillis()) }
  androidx.compose.runtime.LaunchedEffect(Unit) {
    while (true) {
      nowMs = System.currentTimeMillis()
      delay(1_000)
    }
  }
  Column(
    modifier = modifier.fillMaxWidth().heightIn(max = 150.dp),
    verticalArrangement = Arrangement.spacedBy(5.dp),
  ) {
    active?.let { due ->
      Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
          "${due.name} · FÄLLIG",
          color = Color(0xFFFFD58E),
          fontSize = 12.sp,
          fontWeight = FontWeight.SemiBold,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
          Button(onClick = { actions.launch(Dispatchers.IO) { repository.snooze(due.id) } }, modifier = Modifier.weight(1f)) { Text("10 min") }
          Button(onClick = { actions.launch(Dispatchers.IO) { repository.stop(due.id) } }, modifier = Modifier.weight(1f)) { Text("Stopp") }
        }
      }
    }
    schedules.filter { it.state == JarvisScheduleState.Scheduled }.take(2).forEach { schedule ->
      val remaining = (schedule.dueAtEpochMs - nowMs).coerceAtLeast(0L)
      Text(
        "${formatter.format(Instant.ofEpochMilli(schedule.dueAtEpochMs))} · ${schedule.name} · ${remaining.jarvisCountdown()}",
        color = Color(0xFFB5C0C8),
        fontSize = 11.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
      )
    }
  }
}

private fun Long.jarvisCountdown(): String {
  val seconds = this / 1_000L
  val hours = seconds / 3_600L
  val minutes = (seconds % 3_600L) / 60L
  val rest = seconds % 60L
  return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, rest) else "%d:%02d".format(minutes, rest)
}
