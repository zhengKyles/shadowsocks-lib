/*******************************************************************************
 *                                                                             *
 *  Copyright (C) 2017 by Max Lv <max.c.lv@gmail.com>                          *
 *  Copyright (C) 2017 by Mygod Studio <contact-shadowsocks-android@mygod.be>  *
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

package com.kyle.shadowsocks.core.aidl

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.DeadObjectException
import android.os.Handler
import android.os.IBinder
import android.os.RemoteException
import com.kyle.shadowsocks.core.bg.BaseService
import com.kyle.shadowsocks.core.bg.ProxyService
import com.kyle.shadowsocks.core.bg.TransproxyService
import com.kyle.shadowsocks.core.bg.VpnService
import com.kyle.shadowsocks.core.preference.DataStore
import com.kyle.shadowsocks.core.utils.Action
import com.kyle.shadowsocks.core.utils.Key

/**
 * This object should be compact as it will not get GC-ed.
 */
class ShadowsocksConnection(private val handler: Handler = Handler(),
                            private var listenForDeath: Boolean = false) :
        ServiceConnection, IBinder.DeathRecipient {
    companion object {
        val serviceClass get() = when (DataStore.serviceMode) {
            Key.modeProxy -> ProxyService::class
            Key.modeVpn -> VpnService::class
            Key.modeTransproxy -> TransproxyService::class
            else -> throw UnknownError()
        }.java
    }

    interface Callback {
        fun stateChanged(state: BaseService.State, profileName: String?, msg: String?)
        fun trafficUpdated(profileId: Long, stats: TrafficStats) { }
        fun trafficPersisted(profileId: Long) { }

        fun onServiceConnected(service: IShadowsocksService)
        /**
         * Different from Android framework, this method will be called even when you call `detachService`.
         */
        fun onServiceDisconnected() { }
        fun onBinderDied() { }
    }

    private var connectionActive = false
    private var callbackRegistered = false
    private var callback: Callback? = null
    private val serviceCallback = object : IShadowsocksServiceCallback.Stub() {
        override fun stateChanged(state: Int, profileName: String?, msg: String?) {
            val callback = callback ?: return
            handler.post { callback.stateChanged(BaseService.State.values()[state], profileName, msg) }
        }
        override fun trafficUpdated(profileId: Long, stats: TrafficStats) {
            val callback = callback ?: return
            handler.post {
                callback.trafficUpdated(profileId, stats)
            }
        }
        override fun trafficPersisted(profileId: Long) {
            val callback = callback ?: return
            handler.post { callback.trafficPersisted(profileId) }
        }
    }
    private var binder: IBinder? = null

    var bandwidthTimeout = 0L
        set(value) {
            val service = service
            if (bandwidthTimeout != value && service != null)
                if (value > 0) service.startListeningForBandwidth(serviceCallback, value) else try {
                    service.stopListeningForBandwidth(serviceCallback)
                } catch (_: DeadObjectException) { }
            field = value
        }
    var service: IShadowsocksService? = null

    override fun onServiceConnected(name: ComponentName?, binder: IBinder) {
        this.binder = binder
        if (listenForDeath) binder.linkToDeath(this, 0)
        val service = IShadowsocksService.Stub.asInterface(binder)!!
        this.service = service
        if (!callbackRegistered) try {
            service.registerCallback(serviceCallback)
            callbackRegistered = true
            if (bandwidthTimeout > 0) service.startListeningForBandwidth(serviceCallback, bandwidthTimeout)
        } catch (_: RemoteException) { }
        callback!!.onServiceConnected(service)
    }

    override fun onServiceDisconnected(name: ComponentName?) {
        unregisterCallback()
        callback?.onServiceDisconnected()
        service = null
        binder = null
    }

    override fun binderDied() {
        service = null
        callback?.also { handler.post(it::onBinderDied) }
    }

    private fun unregisterCallback() {
        val service = service
        if (service != null && callbackRegistered) try {
            service.unregisterCallback(serviceCallback)
        } catch (_: RemoteException) { }
        callbackRegistered = false
    }

    fun connect(context: Context, callback: Callback) {
        if (connectionActive) return
        connectionActive = true
        check(this.callback == null)
        this.callback = callback
        val intent = Intent(context, serviceClass).setAction(Action.SERVICE)
        context.bindService(intent, this, Context.BIND_AUTO_CREATE)
    }

    fun disconnect(context: Context) {
        unregisterCallback()
        if (connectionActive) try {
            context.unbindService(this)
        } catch (_: IllegalArgumentException) { }   // ignore
        connectionActive = false
        if (listenForDeath) binder?.unlinkToDeath(this, 0)
        binder = null
        service?.stopListeningForBandwidth(serviceCallback)
        service = null
        callback = null
    }
}
