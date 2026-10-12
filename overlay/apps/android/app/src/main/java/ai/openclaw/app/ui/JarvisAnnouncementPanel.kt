package ai.openclaw.app.ui

import ai.openclaw.app.home.AlexaAnnouncement
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A spoken household announcement is deliberately kept separate from Jarvis's
 * conversation. It is information from another person, not an instruction for
 * the assistant, and remains readable while the device speaks it.
 */
@Composable
internal fun JarvisAnnouncementPanel(
  announcement: AlexaAnnouncement,
  onReplay: () -> Unit,
  onDismiss: () -> Unit,
  maxHeight: Dp,
  modifier: Modifier = Modifier,
) {
  val shape = remember { RoundedCornerShape(16.dp) }
  val bodyScroll = rememberScrollState()
  Column(
    modifier =
      modifier
        .fillMaxWidth()
        .heightIn(max = maxHeight)
        .clip(shape)
        .background(
          Brush.verticalGradient(
            listOf(Color(0xFF262A2D), Color(0xFF14191F)),
          ),
        )
        .border(1.dp, Color(0xAAE8B55B), shape)
        .clickable(role = Role.Button, onClickLabel = "Ansage wiederholen", onClick = onReplay)
        .semantics {
          contentDescription = "Alexa-Ankündigung von ${announcement.sourceName}. Tippen zum Wiederholen."
        }
        .padding(start = 15.dp, end = 6.dp, top = 11.dp, bottom = 10.dp)
        .testTag("jarvis-alexa-announcement"),
    verticalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = "ANKÜNDIGUNG",
          color = Color(0xFFFFC56E),
          fontSize = 10.sp,
          lineHeight = 13.sp,
          fontWeight = FontWeight.Bold,
          letterSpacing = 1.8.sp,
        )
        Text(
          text = announcement.sourceName,
          color = Color(0xFFC7D0D8),
          fontSize = 13.sp,
          lineHeight = 17.sp,
          fontWeight = FontWeight.Medium,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
      IconButton(
        onClick = onReplay,
        modifier = Modifier.size(36.dp).semantics { contentDescription = "Ansage wiederholen" },
      ) {
        Icon(
          Icons.Default.Replay,
          contentDescription = null,
          tint = Color(0xFFFFD58E),
          modifier = Modifier.size(19.dp),
        )
      }
      Spacer(Modifier.width(2.dp))
      IconButton(
        onClick = onDismiss,
        modifier = Modifier.size(36.dp).semantics { contentDescription = "Ansage schließen" },
      ) {
        Icon(
          Icons.Default.Close,
          contentDescription = null,
          tint = Color(0xFFAAB6C0),
          modifier = Modifier.size(18.dp),
        )
      }
    }
    Column(
      modifier =
        Modifier
          .fillMaxWidth()
          .heightIn(max = (maxHeight - 72.dp).coerceAtLeast(52.dp))
          .verticalScroll(bodyScroll)
          .testTag("jarvis-announcement-body"),
    ) {
      Text(
        text = announcement.text,
        color = Color(0xFFF0F6FA),
        fontSize = 18.sp,
        lineHeight = 24.sp,
      )
    }
  }
}
