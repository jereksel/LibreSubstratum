package com.jereksel.libresubstratum.domain

import android.graphics.drawable.Drawable
import com.jereksel.libresubstratum.data.Application
import com.jereksel.libresubstratum.data.InstalledTheme
import java.io.File

interface IPackageManager {
    fun getInstalledThemes(): List<InstalledTheme>
//    fun getApplications(): List<Application>
//    fun getHeroImage(appId: String): Drawable?
    fun getAppIcon(appId: String): Drawable?
    fun getAppName(appId: String): String
    fun isPackageInstalled(appId: String): Boolean
    fun getAppLocation(appId: String): File
    fun stringIdToString(stringId: Int): String
    fun getCacheFolder(): File
}
