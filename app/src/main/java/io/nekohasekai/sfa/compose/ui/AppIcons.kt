package io.nekohasekai.sfa.compose.ui

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.automirrored.outlined.CallSplit
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.automirrored.outlined.Rule
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Deselect
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.Colorize
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.DashboardCustomize
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.DeveloperBoard
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.FlashlightOff
import androidx.compose.material.icons.outlined.FlashlightOn
import androidx.compose.material.icons.outlined.FontDownload
import androidx.compose.material.icons.outlined.FormatSize
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.QrCode
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material.icons.outlined.ToggleOff
import androidx.compose.material.icons.outlined.ToggleOn
import androidx.compose.material.icons.outlined.TrackChanges
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The app's icons: stock Material icons (material-icons-extended), by what they stand for. */
object AppIcons {
    val dashboard: ImageVector get() = Icons.Outlined.Dashboard
    val proxies: ImageVector get() = Icons.Outlined.Public
    val systemProxy: ImageVector get() = Icons.Outlined.Language
    val profiles: ImageVector get() = Icons.Outlined.Description
    val requests: ImageVector get() = Icons.Outlined.SwapVert
    val connections: ImageVector get() = Icons.Outlined.Hub
    val dns: ImageVector get() = Icons.Outlined.Dns
    val resources: ImageVector get() = Icons.Outlined.Inventory2
    val logs: ImageVector get() = Icons.AutoMirrored.Outlined.Article
    val tools: ImageVector get() = Icons.Outlined.Build
    val target: ImageVector get() = Icons.Outlined.TrackChanges
    val back: ImageVector get() = Icons.AutoMirrored.Filled.ArrowBack
    val chevronForward: ImageVector get() = Icons.AutoMirrored.Filled.KeyboardArrowRight
    val chevronDown: ImageVector get() = Icons.Filled.KeyboardArrowDown
    val picker: ImageVector get() = Icons.Filled.UnfoldMore
    val caretDown: ImageVector get() = Icons.Filled.ArrowDropDown
    val arrowUp: ImageVector get() = Icons.Filled.ArrowUpward
    val arrowDown: ImageVector get() = Icons.Filled.ArrowDownward
    val openExternal: ImageVector get() = Icons.AutoMirrored.Outlined.OpenInNew
    val undo: ImageVector get() = Icons.AutoMirrored.Filled.Undo
    val redo: ImageVector get() = Icons.AutoMirrored.Filled.Redo
    val sync: ImageVector get() = Icons.Filled.Sync
    val refresh: ImageVector get() = Icons.Filled.Refresh
    val reset: ImageVector get() = Icons.Filled.RestartAlt
    val export: ImageVector get() = Icons.Outlined.Share
    val upload: ImageVector get() = Icons.Outlined.FileUpload
    val add: ImageVector get() = Icons.Filled.Add
    val remove: ImageVector get() = Icons.Filled.Remove
    val addCircle: ImageVector get() = Icons.Outlined.AddCircleOutline
    val close: ImageVector get() = Icons.Filled.Close
    val clearField: ImageVector get() = Icons.Filled.Cancel
    val check: ImageVector get() = Icons.Filled.Check
    val checkCircle: ImageVector get() = Icons.Filled.CheckCircle
    val block: ImageVector get() = Icons.Outlined.Block
    val info: ImageVector get() = Icons.Outlined.Info
    val more: ImageVector get() = Icons.Filled.MoreVert
    val moreCircle: ImageVector get() = Icons.Outlined.MoreHoriz
    val dragHandle: ImageVector get() = Icons.Filled.DragHandle
    val sort: ImageVector get() = Icons.AutoMirrored.Filled.Sort
    val filter: ImageVector get() = Icons.Filled.FilterList
    val edit: ImageVector get() = Icons.Outlined.Edit
    val compose: ImageVector get() = Icons.Outlined.EditNote
    val delete: ImageVector get() = Icons.Outlined.Delete
    val clearAll: ImageVector get() = Icons.Filled.ClearAll
    val copy: ImageVector get() = Icons.Outlined.ContentCopy
    val paste: ImageVector get() = Icons.Outlined.ContentPaste
    val selectAll: ImageVector get() = Icons.Filled.SelectAll
    val deselect: ImageVector get() = Icons.Filled.Deselect
    val search: ImageVector get() = Icons.Filled.Search
    val importFile: ImageVector get() = Icons.Outlined.FileOpen
    val cloudDownload: ImageVector get() = Icons.Outlined.CloudDownload
    val cloudSync: ImageVector get() = Icons.Outlined.CloudSync
    val link: ImageVector get() = Icons.Filled.Link
    val rules: ImageVector get() = Icons.AutoMirrored.Outlined.Rule
    val code: ImageVector get() = Icons.Filled.Code
    val settings: ImageVector get() = Icons.Outlined.Settings
    val sliders: ImageVector get() = Icons.Outlined.Tune
    val toggle: ImageVector get() = Icons.Outlined.ToggleOff
    val toggleOn: ImageVector get() = Icons.Outlined.ToggleOn
    val pin: ImageVector get() = Icons.Outlined.PushPin
    val eyeOff: ImageVector get() = Icons.Outlined.VisibilityOff
    val lock: ImageVector get() = Icons.Outlined.Lock
    val lockOpen: ImageVector get() = Icons.Outlined.LockOpen
    val key: ImageVector get() = Icons.Outlined.Key
    val account: ImageVector get() = Icons.Outlined.AccountCircle
    val bolt: ImageVector get() = Icons.Outlined.Bolt
    val locate: ImageVector get() = Icons.Filled.MyLocation
    val customize: ImageVector get() = Icons.Outlined.DashboardCustomize
    val layers: ImageVector get() = Icons.Outlined.Layers
    val sun: ImageVector get() = Icons.Outlined.LightMode
    val palette: ImageVector get() = Icons.Outlined.Palette
    val paintbrush: ImageVector get() = Icons.Outlined.Brush
    val eyedropper: ImageVector get() = Icons.Outlined.Colorize
    val font: ImageVector get() = Icons.Outlined.FontDownload
    val textSize: ImageVector get() = Icons.Outlined.FormatSize
    val camera: ImageVector get() = Icons.Outlined.PhotoCamera
    val qrCode: ImageVector get() = Icons.Outlined.QrCode
    val torch: ImageVector get() = Icons.Outlined.FlashlightOn
    val torchOff: ImageVector get() = Icons.Outlined.FlashlightOff
    val vpn: ImageVector get() = Icons.Outlined.Shield
    val status: ImageVector get() = Icons.Outlined.MonitorHeart
    val verified: ImageVector get() = Icons.Outlined.VerifiedUser
    val swap: ImageVector get() = Icons.Filled.SwapHoriz
    val split: ImageVector get() = Icons.AutoMirrored.Outlined.CallSplit
    val speed: ImageVector get() = Icons.Outlined.Speed
    val memory: ImageVector get() = Icons.Outlined.Memory
    val cpu: ImageVector get() = Icons.Outlined.DeveloperBoard
    val devices: ImageVector get() = Icons.Outlined.Devices
    val broom: ImageVector get() = Icons.Outlined.CleaningServices
    val wifi: ImageVector get() = Icons.Outlined.Wifi
    val wifiOff: ImageVector get() = Icons.Outlined.WifiOff
    val appsList: ImageVector get() = Icons.AutoMirrored.Outlined.List
    val iconTile: ImageVector get() = Icons.Outlined.Image
    val start: ImageVector get() = Icons.Filled.PlayArrow
    val pause: ImageVector get() = Icons.Filled.Pause
}

/** A Material icon at [size] in [color]. */
@Composable
fun AppIcon(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    color: Color = LocalContentColor.current,
    contentDescription: String? = null,
) {
    Icon(icon, contentDescription = contentDescription, modifier = modifier.size(size), tint = color)
}

/** A Material IconButton showing [icon]; disabled when [onClick] is null. */
@Composable
fun AppIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    iconSize: Dp = 24.dp,
    color: Color = LocalContentColor.current,
) {
    IconButton(onClick = { onClick?.invoke() }, enabled = onClick != null, modifier = modifier.size(size)) {
        Icon(icon, contentDescription = contentDescription, modifier = Modifier.size(iconSize), tint = if (onClick == null) color.copy(alpha = .38f) else color)
    }
}
