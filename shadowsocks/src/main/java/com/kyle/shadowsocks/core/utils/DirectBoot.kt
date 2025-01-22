package com.kyle.shadowsocks.core.utils

import android.annotation.TargetApi
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import com.kyle.shadowsocks.core.Core
import com.kyle.shadowsocks.core.Core.app
import com.kyle.shadowsocks.core.bg.BaseService
import com.kyle.shadowsocks.core.database.Profile
import com.kyle.shadowsocks.core.database.ProfileManager
import com.kyle.shadowsocks.core.preference.DataStore
import java.io.File
import java.io.IOException
import java.io.ObjectInputStream
import java.io.ObjectOutputStream

@TargetApi(24)
object DirectBoot : BroadcastReceiver() {
    private val file = File(Core.deviceStorage.noBackupFilesDir, "directBootProfile")
    private var registered = false

    fun getDeviceProfile(): Pair<Profile, Profile?>? = try {
        ObjectInputStream(file.inputStream()).use { it.readObject() as? Pair<Profile, Profile?> }
    } catch (_: IOException) { null }

    fun clean() {
        file.delete()
        File(Core.deviceStorage.noBackupFilesDir, BaseService.CONFIG_FILE).delete()
        File(Core.deviceStorage.noBackupFilesDir, BaseService.CONFIG_FILE_UDP).delete()
    }

    /**
     * app.currentProfile will call this.
     */
    fun update(profile: Profile? = ProfileManager.getProfile(DataStore.profileId)) =
            if (profile == null) clean()
            else ObjectOutputStream(file.outputStream()).use { it.writeObject(ProfileManager.expand(profile)) }

    fun flushTrafficStats() {
        getDeviceProfile()?.also { (profile, fallback) ->
            if (profile.dirty) ProfileManager.updateProfile(profile)
            if (fallback?.dirty == true) ProfileManager.updateProfile(fallback)
        }
        update()
    }

    fun listenForUnlock() {
        if (registered) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            app.registerReceiver(this, IntentFilter(Intent.ACTION_BOOT_COMPLETED),2)
        }else{
            app.registerReceiver(this, IntentFilter(Intent.ACTION_BOOT_COMPLETED))
        }
        registered = true
    }
    override fun onReceive(context: Context, intent: Intent) {
        flushTrafficStats()
        app.unregisterReceiver(this)
        registered = false
    }
}
