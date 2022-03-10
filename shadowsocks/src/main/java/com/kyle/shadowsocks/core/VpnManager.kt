package com.kyle.shadowsocks.core

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.DeadObjectException
import android.os.Handler
import android.util.Log
import android.widget.Toast
import com.kyle.shadowsocks.core.aidl.IShadowsocksService
import com.kyle.shadowsocks.core.aidl.ShadowsocksConnection
import com.kyle.shadowsocks.core.aidl.TrafficStats
import com.kyle.shadowsocks.core.bg.BaseService
import com.kyle.shadowsocks.core.preference.DataStore
import com.kyle.shadowsocks.core.utils.Key

/**
 * @author : kyle
 * e-mail : 1239878682@qq.com
 * @date : 2019/5/14 16:54
 * 看了我的代码，感动了吗?
 */
class VpnManager private constructor() {

     var state = BaseService.State.Idle
    private var context: Context? = null
    private val handler = Handler()
    private val connection = ShadowsocksConnection(handler, true)
    private var listener: OnStatusChangeListener? = null
    private val callback: ShadowsocksConnection.Callback = object : ShadowsocksConnection.Callback {
        override fun stateChanged(state: BaseService.State, profileName: String?, msg: String?) {
            changeState(state)
        }

        override fun onServiceDisconnected() = changeState(BaseService.State.Idle)

        override fun onServiceConnected(service: IShadowsocksService) {
            changeState(try {
                BaseService.State.values()[service.state]
            } catch (_: DeadObjectException) {
                BaseService.State.Idle
            })
        }

        override fun onBinderDied() {
            disconnect()
            connect()
        }
    }

    private fun connect() {
        context?.let { connection.connect(it, callback) }
    }

    private fun disconnect() {
        context?.let { connection.disconnect(it) }
    }

    companion object {
        private const val REQUEST_CONNECT = 1
        @SuppressLint("StaticFieldLeak")
        private var instance: VpnManager? = null

        fun getInstance(): VpnManager {
            if (instance == null) {
                instance = VpnManager()
            }
            return instance as VpnManager
        }
    }

    fun init(context: Context){
        this.context=context
        connect()
    }

    /***
     * 开启或者关闭 自动判断
     */
    fun run(activity:Activity) {
        when {
            state.canStop -> Core.stopService()
            DataStore.serviceMode == Key.modeVpn -> {
                val intent = VpnService.prepare(activity)
                if (intent != null) activity.startActivityForResult(intent, REQUEST_CONNECT)
                else onActivityResult(REQUEST_CONNECT, Activity.RESULT_OK, null)
            }
            else -> Core.startService()
        }
    }

    /***
     * 设置状态监听
     */
    fun setOnStatusChangeListener(listener: OnStatusChangeListener) {
        this.listener = listener
    }

    /***
     * application调用stop时调用
     */
    fun onStop() {
        connection.bandwidthTimeout = 0
    }

    /***
     * activity调用onActivityResult时调用
     */
    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        when {
            requestCode != REQUEST_CONNECT -> {
            }
            resultCode == Activity.RESULT_OK -> Core.startService()
            else -> {
                //无权限
            }
        }
    }

    /***
     * 改变当前状态
     */
    private fun changeState(state: BaseService.State) {
        this.state = state
        this.listener?.onStatusChanged(state)
    }

    /***
     * 状态改变监听器
     */
    interface OnStatusChangeListener {
        fun onStatusChanged(state: BaseService.State)
    }

    enum class Route(name: String) {
        //全部
        ALL("all")
        //绕过局域网地址
        ,
        BY_PASS_LAN("bypass-lan")
        //绕过中国大陆地址
        ,
        BY_PASS_CHINA("bypass-china")
        //绕过局域网和中国大陆地址
        ,
        BY_PASS_LAN_CHINA("bypass-lan-china")
        //GFW列表
        ,
        GFW_LIST("gfwlist")
        //仅代理中国大陆地址
        ,
        CHINA_LIST("china-list")
        //自定义规则
        ,
        CUSTOM_RULES("custom-rules");

        var route = name
    }
}
