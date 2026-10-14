package com.lucifer.mobile.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun LuciferIcon(
    modifier: Modifier = Modifier,
    tint: Color = com.lucifer.mobile.ui.theme.LuciferColors.OnSurface
) {
    // Base icon component
    Icon(
        imageVector = androidx.compose.material.icons.Icons.Default.Android,
        contentDescription = null,
        modifier = modifier.size(24.dp),
        tint = tint
    )
}

object LuciferIcon {
    @Composable
    fun Send(modifier: Modifier = Modifier, tint: Color = com.lucifer.mobile.ui.theme.LuciferColors.Gold) {
        Icon(
            imageVector = androidx.compose.material.icons.Icons.Default.Send,
            contentDescription = "Send",
            modifier = modifier.size(24.dp),
            tint = tint
        )
    }

    @Composable
    fun Terminal(modifier: Modifier = Modifier, tint: Color = com.lucifer.mobile.ui.theme.LuciferColors.Gold) {
        Icon(
            imageVector = androidx.compose.material.icons.Icons.Default.Terminal,
            contentDescription = "Terminal",
            modifier = modifier.size(24.dp),
            tint = tint
        )
    }

    @Composable
    fun Wallet(modifier: Modifier = Modifier, tint: Color = com.lucifer.mobile.ui.theme.LuciferColors.Gold) {
        Icon(
            imageVector = androidx.compose.material.icons.Icons.Default.AccountBalanceWallet,
            contentDescription = "Wallet",
            modifier = modifier.size(24.dp),
            tint = tint
        )
    }

    @Composable
    fun Browser(modifier: Modifier = Modifier, tint: Color = com.lucifer.mobile.ui.theme.LuciferColors.Gold) {
        Icon(
            imageVector = androidx.compose.material.icons.Icons.Default.Web,
            contentDescription = "Browser",
            modifier = modifier.size(24.dp),
            tint = tint
        )
    }

    @Composable
    fun Skills(modifier: Modifier = Modifier, tint: Color = com.lucifer.mobile.ui.theme.LuciferColors.Gold) {
        Icon(
            imageVector = androidx.compose.material.icons.Icons.Default.Psychology,
            contentDescription = "Skills",
            modifier = modifier.size(24.dp),
            tint = tint
        )
    }

    @Composable
    fun Settings(modifier: Modifier = Modifier, tint: Color = com.lucifer.mobile.ui.theme.LuciferColors.Gold) {
        Icon(
            imageVector = androidx.compose.material.icons.Icons.Default.Settings,
            contentDescription = "Settings",
            modifier = modifier.size(24.dp),
            tint = tint
        )
    }

    @Composable
    fun Conquest(modifier: Modifier = Modifier, tint: Color = com.lucifer.mobile.ui.theme.LuciferColors.Red) {
        Icon(
            imageVector = androidx.compose.material.icons.Icons.Default.MilitaryTech,
            contentDescription = "Conquest",
            modifier = modifier.size(24.dp),
            tint = tint
        )
    }

    @Composable
    fun War(modifier: Modifier = Modifier, tint: Color = com.lucifer.mobile.ui.theme.LuciferColors.Gold) {
        Icon(
            imageVector = androidx.compose.material.icons.Icons.Default.Shield,
            contentDescription = "War",
            modifier = modifier.size(24.dp),
            tint = tint
        )
    }

    @Composable
    fun Famine(modifier: Modifier = Modifier, tint: Color = com.lucifer.mobile.ui.theme.LuciferColors.Gold) {
        Icon(
            imageVector = androidx.compose.material.icons.Icons.Default.Speed,
            contentDescription = "Famine",
            modifier = modifier.size(24.dp),
            tint = tint
        )
    }

    @Composable
    fun Death(modifier: Modifier = Modifier, tint: Color = com.lucifer.mobile.ui.theme.LuciferColors.Error) {
        Icon(
            imageVector = androidx.compose.material.icons.Icons.Default.DeleteForever,
            contentDescription = "Death",
            modifier = modifier.size(24.dp),
            tint = tint
        )
    }

    @Composable
    fun Lucifer(modifier: Modifier = Modifier, tint: Color = com.lucifer.mobile.ui.theme.LuciferColors.Red) {
        Icon(
            imageVector = androidx.compose.material.icons.Icons.Default.Fireplace,
            contentDescription = "Lucifer",
            modifier = modifier.size(24.dp),
            tint = tint
        )
    }

    @Composable
    fun Menu(modifier: Modifier = Modifier, tint: Color = com.lucifer.mobile.ui.theme.LuciferColors.OnSurface) {
        Icon(
            imageVector = androidx.compose.material.icons.Icons.Default.Menu,
            contentDescription = "Menu",
            modifier = modifier.size(24.dp),
            tint = tint
        )
    }

    @Composable
    fun Close(modifier: Modifier = Modifier, tint: Color = com.lucifer.mobile.ui.theme.LuciferColors.OnSurface) {
        Icon(
            imageVector = androidx.compose.material.icons.Icons.Default.Close,
            contentDescription = "Close",
            modifier = modifier.size(24.dp),
            tint = tint
        )
    }

    @Composable
    fun ChevronLeft(modifier: Modifier = Modifier, tint: Color = com.lucifer.mobile.ui.theme.LuciferColors.OnSurface) {
        Icon(
            imageVector = androidx.compose.material.icons.Icons.Default.ChevronLeft,
            contentDescription = "Back",
            modifier = modifier.size(24.dp),
            tint = tint
        )
    }

    @Composable
    fun ChevronRight(modifier: Modifier = Modifier, tint: Color = com.lucifer.mobile.ui.theme.LuciferColors.OnSurface) {
        Icon(
            imageVector = androidx.compose.material.icons.Icons.Default.ChevronRight,
            contentDescription = "Forward",
            modifier = modifier.size(24.dp),
            tint = tint
        )
    }

    @Composable
    fun Refresh(modifier: Modifier = Modifier, tint: Color = com.lucifer.mobile.ui.theme.LuciferColors.Gold) {
        Icon(
            imageVector = androidx.compose.material.icons.Icons.Default.Refresh,
            contentDescription = "Refresh",
            modifier = modifier.size(24.dp),
            tint = tint
        )
    }

    @Composable
    fun Search(modifier: Modifier = Modifier, tint: Color = com.lucifer.mobile.ui.theme.LuciferColors.OnSurface) {
        Icon(
            imageVector = androidx.compose.material.icons.Icons.Default.Search,
            contentDescription = "Search",
            modifier = modifier.size(24.dp),
            tint = tint
        )
    }

    @Composable
    fun Add(modifier: Modifier = Modifier, tint: Color = com.lucifer.mobile.ui.theme.LuciferColors.Gold) {
        Icon(
            imageVector = androidx.compose.material.icons.Icons.Default.Add,
            contentDescription = "Add",
            modifier = modifier.size(24.dp),
            tint = tint
        )
    }

    @Composable
    fun Delete(modifier: Modifier = Modifier, tint: Color = com.lucifer.mobile.ui.theme.LuciferColors.Error) {
        Icon(
            imageVector = androidx.compose.material.icons.Icons.Default.Delete,
            contentDescription = "Delete",
            modifier = modifier.size(24.dp),
            tint = tint
        )
    }

    @Composable
    fun Edit(modifier: Modifier = Modifier, tint: Color = com.lucifer.mobile.ui.theme.LuciferColors.Gold) {
        Icon(
            imageVector = androidx.compose.material.icons.Icons.Default.Edit,
            contentDescription = "Edit",
            modifier = modifier.size(24.dp),
            tint = tint
        )
    }

    @Composable
    fun Copy(modifier: Modifier = Modifier, tint: Color = com.lucifer.mobile.ui.theme.LuciferColors.Gold) {
        Icon(
            imageVector = androidx.compose.material.icons.Icons.Default.ContentCopy,
            contentDescription = "Copy",
            modifier = modifier.size(24.dp),
            tint = tint
        )
    }

    @Composable
    fun Paste(modifier: Modifier = Modifier, tint: Color = com.lucifer.mobile.ui.theme.LuciferColors.Gold) {
        Icon(
            imageVector = androidx.compose.material.icons.Icons.Default.ContentPaste,
            contentDescription = "Paste",
            modifier = modifier.size(24.dp),
            tint = tint
        )
    }

    @Composable
    fun Download(modifier: Modifier = Modifier, tint: Color = com.lucifer.mobile.ui.theme.LuciferColors.Gold) {
        Icon(
            imageVector = androidx.compose.material.icons.Icons.Default.Download,
            contentDescription = "Download",
            modifier = modifier.size(24.dp),
            tint = tint
        )
    }

    @Composable
    fun Upload(modifier: Modifier = Modifier, tint: Color = com.lucifer.mobile.ui.theme.LuciferColors.Gold) {
        Icon(
            imageVector = androidx.compose.material.icons.Icons.Default.Upload,
            contentDescription = "Upload",
            modifier = modifier.size(24.dp),
            tint = tint
        )
    }

    @Composable
    fun Lock(modifier: Modifier = Modifier, tint: Color = com.lucifer.mobile.ui.theme.LuciferColors.Red) {
        Icon(
            imageVector = androidx.compose.material.icons.Icons.Default.Lock,
            contentDescription = "Lock",
            modifier = modifier.size(24.dp),
            tint = tint
        )
    }

    @Composable
    fun Unlock(modifier: Modifier = Modifier, tint: Color = com.lucifer.mobile.ui.theme.LuciferColors.Gold) {
        Icon(
            imageVector = androidx.compose.material.icons.Icons.Default.LockOpen,
            contentDescription = "Unlock",
            modifier = modifier.size(24.dp),
            tint = tint
        )
    }

    @Composable
    fun Warning(modifier: Modifier = Modifier, tint: Color = com.lucifer.mobile.ui.theme.LuciferColors.Gold) {
        Icon(
            imageVector = androidx.compose.material.icons.Icons.Default.Warning,
            contentDescription = "Warning",
            modifier = modifier.size(24.dp),
            tint = tint
        )
    }

    @Composable
    fun Info(modifier: Modifier = Modifier, tint: Color = com.lucifer.mobile.ui.theme.LuciferColors.Gold) {
        Icon(
            imageVector = androidx.compose.material.icons.Icons.Default.Info,
            contentDescription = "Info",
            modifier = modifier.size(24.dp),
            tint = tint
        )
    }

    @Composable
    fun CheckCircle(modifier: Modifier = Modifier, tint: Color = com.lucifer.mobile.ui.theme.LuciferColors.Gold) {
        Icon(
            imageVector = androidx.compose.material.icons.Icons.Default.CheckCircle,
            contentDescription = "Success",
            modifier = modifier.size(24.dp),
            tint = tint
        )
    }

    @Composable
    fun Error(modifier: Modifier = Modifier, tint: Color = com.lucifer.mobile.ui.theme.LuciferColors.Error) {
        Icon(
            imageVector = androidx.compose.material.icons.Icons.Default.Error,
            contentDescription = "Error",
            modifier = modifier.size(24.dp),
            tint = tint
        )
    }

    @Composable
    fun Home(modifier: Modifier = Modifier, tint: Color = com.lucifer.mobile.ui.theme.LuciferColors.Gold) {
        Icon(
            imageVector = androidx.compose.material.icons.Icons.Default.Home,
            contentDescription = "Home",
            modifier = modifier.size(24.dp),
            tint = tint
        )
    }

    @Composable
    fun Person(modifier: Modifier = Modifier, tint: Color = com.lucifer.mobile.ui.theme.LuciferColors.Gold) {
        Icon(
            imageVector = androidx.compose.material.icons.Icons.Default.Person,
            contentDescription = "Person",
            modifier = modifier.size(24.dp),
            tint = tint
        )
    }

    @Composable
    fun Cloud(modifier: Modifier = Modifier, tint: Color = com.lucifer.mobile.ui.theme.LuciferColors.Gold) {
        Icon(
            imageVector = androidx.compose.material.icons.Icons.Default.Cloud,
            contentDescription = "Cloud",
            modifier = modifier.size(24.dp),
            tint = tint
        )
    }

    @Composable
    fun Wifi(modifier: Modifier = Modifier, tint: Color = com.lucifer.mobile.ui.theme.LuciferColors.Gold) {
        Icon(
            imageVector = androidx.compose.material.icons.Icons.Default.Wifi,
            contentDescription = "WiFi",
            modifier = modifier.size(24.dp),
            tint = tint
        )
    }

    @Composable
    fun Bluetooth(modifier: Modifier = Modifier, tint: Color = com.lucifer.mobile.ui.theme.LuciferColors.Gold) {
        Icon(
            imageVector = androidx.compose.material.icons.Icons.Default.Bluetooth,
            contentDescription = "Bluetooth",
            modifier = modifier.size(24.dp),
            tint = tint
        )
    }

    @Composable
    fun Usb(modifier: Modifier = Modifier, tint: Color = com.lucifer.mobile.ui.theme.LuciferColors.Gold) {
        Icon(
            imageVector = androidx.compose.material.icons.Icons.Default.Usb,
            contentDescription = "USB",
            modifier = modifier.size(24.dp),
            tint = tint
        )
    }

    @Composable
    fun SdCard(modifier: Modifier = Modifier, tint: Color = com.lucifer.mobile.ui.theme.LuciferColors.Gold) {
        Icon(
            imageVector = androidx.compose.material.icons.Icons.Default.SdCard,
            contentDescription = "SD Card",
            modifier = modifier.size(24.dp),
            tint = tint
        )
    }

    @Composable
    fun Battery(modifier: Modifier = Modifier, tint: Color = com.lucifer.mobile.ui.theme.LuciferColors.Gold) {
        Icon(
            imageVector = androidx.compose.material.icons.Icons.Default.BatteryFull,
            contentDescription = "Battery",
            modifier = modifier.size(24.dp),
            tint = tint
        )
    }

    @Composable
    fun VolumeUp(modifier: Modifier = Modifier, tint: Color = com.lucifer.mobile.ui.theme.LuciferColors.Gold) {
        Icon(
            imageVector = androidx.compose.material.icons.Icons.Default.VolumeUp,
            contentDescription = "Volume Up",
            modifier = modifier.size(24.dp),
            tint = tint
        )
    }

    @Composable
    fun VolumeDown(modifier: Modifier = Modifier, tint: Color = com.lucifer.mobile.ui.theme.LuciferColors.Gold) {
        Icon(
            imageVector = androidx.compose.material.icons.Icons.Default.VolumeDown,
            contentDescription = "Volume Down",
            modifier = modifier.size(24.dp),
            tint = tint
        )
    }

    @Composable
    fun VolumeOff(modifier: Modifier = Modifier, tint: Color = com.lucifer.mobile.ui.theme.LuciferColors.Gold) {
        Icon(
            imageVector = androidx.compose.material.icons.Icons.Default.VolumeOff,
            contentDescription = "Volume Off",
            modifier = modifier.size(24.dp),
            tint = tint
        )
    }

    @Composable
    fun Brightness(modifier: Modifier = Modifier, tint: Color = com.lucifer.mobile.ui.theme.LuciferColors.Gold) {
        Icon(
            imageVector = androidx.compose.material.icons.Icons.Default.BrightnessHigh,
            contentDescription = "Brightness",
            modifier = modifier.size(24.dp),
            tint = tint
        )
    }

    @Composable
    fun Flashlight(modifier: Modifier = Modifier, tint: Color = com.lucifer.mobile.ui.theme.LuciferColors.Gold) {
        Icon(
            imageVector = androidx.compose.material.icons.Icons.Default.FlashlightOn,
            contentDescription = "Flashlight",
            modifier = modifier.size(24.dp),
            tint = tint
        )
    }
    
    @Composable
    fun ArrowDown(modifier: Modifier = Modifier, tint: Color = com.lucifer.mobile.ui.theme.LuciferColors.Gold) {
        Icon(
            imageVector = androidx.compose.material.icons.Icons.Default.ArrowDownward,
            contentDescription = "Arrow Down",
            modifier = modifier.size(24.dp),
            tint = tint
        )
    }
    
    @Composable
    fun ArrowUp(modifier: Modifier = Modifier, tint: Color = com.lucifer.mobile.ui.theme.LuciferColors.Gold) {
        Icon(
            imageVector = androidx.compose.material.icons.Icons.Default.ArrowUpward,
            contentDescription = "Arrow Up",
            modifier = modifier.size(24.dp),
            tint = tint
        )
    }
    
    @Composable
    fun ArrowLeft(modifier: Modifier = Modifier, tint: Color = com.lucifer.mobile.ui.theme.LuciferColors.Gold) {
        Icon(
            imageVector = androidx.compose.material.icons.Icons.Default.ArrowBack,
            contentDescription = "Arrow Left",
            modifier = modifier.size(24.dp),
            tint = tint
        )
    }
    
    @Composable
    fun ArrowRight(modifier: Modifier = Modifier, tint: Color = com.lucifer.mobile.ui.theme.LuciferColors.Gold) {
        Icon(
            imageVector = androidx.compose.material.icons.Icons.Default.ArrowForward,
            contentDescription = "Arrow Right",
            modifier = modifier.size(24.dp),
            tint = tint
        )
    }
}