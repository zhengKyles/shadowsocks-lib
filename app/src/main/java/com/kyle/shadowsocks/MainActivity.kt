package com.kyle.shadowsocks

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.databinding.DataBindingUtil
import com.kyle.shadowsocks.core.VpnManager
import com.kyle.shadowsocks.core.bg.BaseService
import com.kyle.shadowsocks.core.database.Profile
import com.kyle.shadowsocks.core.database.ProfileManager
import com.kyle.shadowsocks.core.preference.DataStore
import com.kyle.shadowsocks.databinding.ActivityMainBinding


class MainActivity : AppCompatActivity() {

    private var binding: ActivityMainBinding? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = DataBindingUtil.setContentView(this, R.layout.activity_main)
        var profile = Profile()
        profile.name = "测试"
        profile.route = VpnManager.Route.ALL.route
        profile.ipv6 = false//是否转发IPV6流量到远程服务器
        profile.proxyApps = false//是否分应用绕过vpn true则分应用，选bypass且individual字段
        //的值为所选应用的包名带换行
        // ，flase则全部走代理
        profile.bypass = true //true为选取部分应用走代理  false为选取部分应用不走代理
        profile.metered = false
        profile.remoteDns = "dns.google"//远程DNS
        profile.udpdns = false//是否dns转发

        binding?.profile = profile
        VpnManager.getInstance().setOnStatusChangeListener(object : VpnManager.OnStatusChangeListener {
            override fun onStatusChanged(state: BaseService.State) {
                binding?.tvStatus?.text = state.name
            }
        })
        binding?.btnStart?.setOnClickListener {
            DataStore.profileId = ProfileManager.createProfile(profile).id
            start()
        }
        binding?.btnClose?.setOnClickListener {
            start()
        }


    }


    private fun start() {
        VpnManager.getInstance().run(this)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        VpnManager.getInstance().onActivityResult(requestCode, resultCode, data)
    }

    override fun onStart() {
        super.onStart()
        VpnManager.getInstance().onStop()
    }



}
