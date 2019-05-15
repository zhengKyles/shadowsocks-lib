/*******************************************************************************
 *                                                                             *
 *  Copyright (C) 2019 by Max Lv <max.c.lv@gmail.com>                          *
 *  Copyright (C) 2019 by Mygod Studio <contact-shadowsocks-android@mygod.be>  *
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

package com.kyle.shadowsocks.core.bg

import android.content.Context

import com.kyle.shadowsocks.core.acl.Acl
import com.kyle.shadowsocks.core.acl.AclSyncer
import com.kyle.shadowsocks.core.database.Profile
import com.kyle.shadowsocks.core.database.ProfileManager
import com.kyle.shadowsocks.core.plugin.PluginConfiguration
import com.kyle.shadowsocks.core.plugin.PluginManager
import com.kyle.shadowsocks.core.preference.DataStore
import com.kyle.shadowsocks.core.utils.DirectBoot
import com.kyle.shadowsocks.core.utils.parseNumericAddress
import kotlinx.coroutines.*
import java.io.File
import java.io.IOException
import java.net.UnknownHostException

/**
 * This class sets up environment for ss-local.
 */
class ProxyInstance(val profile: Profile, private val route: String = profile.route) {
    private var configFile: File? = null
    var trafficMonitor: TrafficMonitor? = null
    private val plugin = PluginConfiguration(profile.plugin ?: "").selectedOptions
    val pluginPath by lazy { PluginManager.init(plugin) }

    suspend fun init(service: BaseService.Interface) {
        if (route == Acl.CUSTOM_RULES) withContext(Dispatchers.IO) {
            Acl.save(Acl.CUSTOM_RULES, Acl.customRules.flatten(10, service::openConnection))
        }

        // it's hard to resolve DNS on a specific interface so we'll do it here
        if (profile.host.parseNumericAddress() == null) {
            while (true) try {
                val io = GlobalScope.async(Dispatchers.IO) { service.resolver(profile.host) }
                profile.host = io.await().firstOrNull()?.hostAddress ?: throw UnknownHostException()
                return
            } catch (e: UnknownHostException) {
                // retries are only needed on Chrome OS where arc0 is brought up/down during VPN changes
                if (!DataStore.hasArc0) throw e
                Thread.yield()
            }
        }
    }

    /**
     * Sensitive shadowsocks configuration file requires extra protection. It may be stored in encrypted storage or
     * device storage, depending on which is currently available.
     */
    fun start(service: BaseService.Interface, stat: File, configFile: File, extraFlag: String? = null) {
        trafficMonitor = TrafficMonitor(stat)

        this.configFile = configFile
        val config = profile.toJson()
        if (pluginPath != null) config.put("plugin", pluginPath).put("plugin_opts", plugin.toString())
        configFile.writeText(config.toString())

        val cmd = service.buildAdditionalArguments(arrayListOf(
                File((service as Context).applicationInfo.nativeLibraryDir, Executable.SS_LOCAL).absolutePath,
                "-b", DataStore.listenAddress,
                "-l", DataStore.portProxy.toString(),
                "-t", "600",
                "-S", stat.absolutePath,
                "-c", configFile.absolutePath))
        if (extraFlag != null) cmd.add(extraFlag)

        if (route != Acl.ALL) {
            cmd += "--acl"
            cmd += Acl.getFile(route).absolutePath
        }

        // for UDP profile, it's only going to operate in UDP relay mode-only so this flag has no effect
        if (profile.route == Acl.ALL || profile.route == Acl.BYPASS_LAN) cmd += "-D"

        if (DataStore.tcpFastOpen) cmd += "--fast-open"

        service.data.processes!!.start(cmd)
    }

    fun scheduleUpdate() {
        if (route !in arrayOf(Acl.ALL, Acl.CUSTOM_RULES)) AclSyncer.schedule(route)
    }

    fun shutdown(scope: CoroutineScope) {
        trafficMonitor?.apply {
            thread.shutdown(scope)
            // Make sure update total traffic when stopping the runner
            try {
                // profile may have host, etc. modified and thus a re-fetch is necessary (possible race condition)
                val profile = ProfileManager.getProfile(profile.id) ?: return
                profile.tx += current.txTotal
                profile.rx += current.rxTotal
                ProfileManager.updateProfile(profile)
            } catch (e: IOException) {
                if (!DataStore.directBootAware) throw e // we should only reach here because we're in direct boot
                val profile = DirectBoot.getDeviceProfile()!!.toList().filterNotNull().single { it.id == profile.id }
                profile.tx += current.txTotal
                profile.rx += current.rxTotal
                profile.dirty = true
                DirectBoot.update(profile)
                DirectBoot.listenForUnlock()
            }
        }
        trafficMonitor = null
        configFile?.delete()    // remove old config possibly in device storage
        configFile = null
    }
}
