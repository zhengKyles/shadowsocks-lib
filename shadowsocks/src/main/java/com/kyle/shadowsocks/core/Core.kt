/*******************************************************************************
 *                                                                             *
 *  Copyright (C) 2018 by Max Lv <max.c.lv@gmail.com>                          *
 *  Copyright (C) 2018 by Mygod Studio <contact-shadowsocks-android@mygod.be>  *
 *                                                                             *
 *  This program is free software: you can redistribute it and/or modify       *
 *  it under the terms of the GNU General Public License as published by       *
 *  the Free Software Foundation, either version 3 of the License, or          *
 *  (at your option) any later version.                                        *
 *                                                                             *
 *  This program is distributed in the hope that it will be useful,            *
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of             *
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the              *
 *  GNU General Public License for more details.                               *
 *                                                                             *
 *  You should have received a copy of the GNU General Public License          *
 *  along with this program. If not, see <http://www.gnu.org/licenses/>.       *
 *                                                                             *
 *******************************************************************************/

package com.kyle.shadowsocks.core

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.PendingIntent.FLAG_IMMUTABLE
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.UserManager
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.work.Configuration
import androidx.work.WorkManager

import com.kyle.shadowsocks.core.acl.Acl
import com.kyle.shadowsocks.core.aidl.ShadowsocksConnection
import com.kyle.shadowsocks.core.database.Profile
import com.kyle.shadowsocks.core.database.ProfileManager
import com.kyle.shadowsocks.core.net.TcpFastOpen
import com.kyle.shadowsocks.core.preference.DataStore
import com.kyle.shadowsocks.core.utils.*
import kotlinx.coroutines.DEBUG_PROPERTY_NAME
import kotlinx.coroutines.DEBUG_PROPERTY_VALUE_ON
import java.io.File
import java.io.IOException
import kotlin.reflect.KClass

object Core {
    const val TAG = "Core"


    var RECEIVER_WHICH=2

    lateinit var app: Application
    lateinit var configureIntent: (Context) -> PendingIntent
    val packageInfo: PackageInfo by lazy { getPackageInfo(app.packageName) }
    val deviceStorage by lazy { if (Build.VERSION.SDK_INT < 24) app else DeviceStorageApp(app) }
    val directBootSupported by lazy {
        Build.VERSION.SDK_INT >= 24 && app.getSystemService<DevicePolicyManager>()?.storageEncryptionStatus ==
                DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE_PER_USER
    }

    val activeProfileIds
        get() = ProfileManager.getProfile(DataStore.profileId).let {
            if (it == null) emptyList() else listOfNotNull(it.id, it.udpFallback)
        }
    val currentProfile: Pair<Profile, Profile?>?
        get() {
            if (DataStore.directBootAware) DirectBoot.getDeviceProfile()?.apply { return this }
            return ProfileManager.expand(ProfileManager.getProfile(DataStore.profileId)
                    ?: return null)
        }

    fun switchProfile(id: Long): Profile {
        val result = ProfileManager.getProfile(id) ?: ProfileManager.createProfile()
        DataStore.profileId = result.id
        return result
    }

    fun init(app: Application, configureClass: KClass<out Any>) {
        Core.app = app
        configureIntent = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.getActivity(it, 0, Intent(it, configureClass.java)
                        .setFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT), FLAG_IMMUTABLE)
            } else {
                PendingIntent.getActivity(it, 0, Intent(it, configureClass.java)
                    .setFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT), 0)
            }
        }

        if (Build.VERSION.SDK_INT >= 24) {  // migrate old files
            deviceStorage.moveDatabaseFrom(app, Key.DB_PUBLIC)
            val old = Acl.getFile(Acl.CUSTOM_RULES, app)
            if (old.canRead()) {
                Acl.getFile(Acl.CUSTOM_RULES).writeText(old.readText())
                old.delete()
            }
        }

        // overhead of debug mode is minimal: https://github.com/Kotlin/kotlinx.coroutines/blob/f528898/docs/debugging.md#debug-mode
        System.setProperty(DEBUG_PROPERTY_NAME, DEBUG_PROPERTY_VALUE_ON)
//        WorkManager.initialize(deviceStorage, Configuration.Builder().build())

        // handle data restored/crash
        if (Build.VERSION.SDK_INT >= 24 && DataStore.directBootAware &&
                app.getSystemService<UserManager>()?.isUserUnlocked == true) DirectBoot.flushTrafficStats()
        if (DataStore.tcpFastOpen && !TcpFastOpen.sendEnabled) TcpFastOpen.enableTimeout()
        if (DataStore.publicStore.getLong(Key.assetUpdateTime, -1) != packageInfo.lastUpdateTime) {
            val assetManager = app.assets
            try {
                for (file in assetManager.list("acl")!!) assetManager.open("acl/$file").use { input ->
                    File(ContextCompat.getNoBackupFilesDir(deviceStorage), file).outputStream().use { output -> input.copyTo(output) }
                }
            } catch (e: IOException) {
                printLog(e)
            }
            DataStore.publicStore.putLong(Key.assetUpdateTime, packageInfo.lastUpdateTime)
        }
        updateNotificationChannels()
    }

    fun updateNotificationChannels() {
        if (Build.VERSION.SDK_INT >= 26) @RequiresApi(26) {
            val nm = app.getSystemService<NotificationManager>()!!
            nm.createNotificationChannels(listOf(
                    NotificationChannel("service-vpn", app.getText(R.string.service_vpn),
                            NotificationManager.IMPORTANCE_LOW),
                    NotificationChannel("service-proxy", app.getText(R.string.service_proxy),
                            NotificationManager.IMPORTANCE_LOW),
                    NotificationChannel("service-transproxy", app.getText(R.string.service_transproxy),
                            NotificationManager.IMPORTANCE_LOW)))
            nm.deleteNotificationChannel("service-nat") // NAT mode is gone for good
        }
    }

    fun getPackageInfo(packageName: String) = app.packageManager.getPackageInfo(packageName,
            if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES
            else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES)!!

    fun startService() = ContextCompat.startForegroundService(app, Intent(app, ShadowsocksConnection.serviceClass))
    fun reloadService() = app.sendBroadcast(Intent(Action.RELOAD))
    fun stopService() = app.sendBroadcast(Intent(Action.CLOSE))

    fun listenForPackageChanges(onetime: Boolean = true, callback: () -> Unit) = object : BroadcastReceiver() {
        init {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                app.registerReceiver(this, IntentFilter().apply {
                    addAction(Intent.ACTION_PACKAGE_ADDED)
                    addAction(Intent.ACTION_PACKAGE_REMOVED)
                    addDataScheme("package")
                },RECEIVER_WHICH)
            }else{
                app.registerReceiver(this, IntentFilter().apply {
                    addAction(Intent.ACTION_PACKAGE_ADDED)
                    addAction(Intent.ACTION_PACKAGE_REMOVED)
                    addDataScheme("package")
                })
            }
        }

        override fun onReceive(context: Context, intent: Intent) {
            if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return
            callback()
            if (onetime) app.unregisterReceiver(this)
        }
    }
}
