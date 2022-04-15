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
import com.kyle.shadowsocks.core.acl.Acl
import com.kyle.shadowsocks.core.aidl.TrafficStats
import com.kyle.shadowsocks.core.bg.BaseService
import com.kyle.shadowsocks.core.database.Profile
import com.kyle.shadowsocks.core.database.ProfileManager
import com.kyle.shadowsocks.core.preference.DataStore
import com.kyle.shadowsocks.databinding.ActivityMainBinding
import java.net.URL


class MainActivity : AppCompatActivity() {

    private var binding: ActivityMainBinding? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = DataBindingUtil.setContentView(this, R.layout.activity_main)
        var profile = Profile()
        ProfileManager.clear()

        ProfileManager.getAllProfiles()?.forEach {
            Log.e("it","it")
        }

        val acl = Acl.customRules
        acl.urls.clear()
        acl.urls.add(URL("https://pac.unblockcn.mobi/android.acl"))
        Acl.customRules = acl

        profile.name = "①(广东广州\uD83C\uDDE8\uD83C\uDDF3)*(无线)*(5000)*(ꜱꜱᴛᴀᴘ)"
        profile.route = VpnManager.Route.CUSTOM_RULES.route
        profile.proxyApps = true//是否分应用绕过vpn true则分应用，选bypass且individual字段
        //的值为所选应用的包名带换行
        // ，flase则全部走代理
        profile.bypass = false //true为选取部分应用走代理  false为选取部分应用不走代理
        profile.metered = false
        profile.remoteDns = "114.114.114.114"//远程DNS
        profile.udpdns = true//是否dns转发
        profile.individual = "\n" +
                "com.android.browser"

        //zhengKyles@gmail.com
        //Zidanshen55

        binding?.profile = profile
        VpnManager.getInstance().setOnStatusChangeListener(object : VpnManager.OnStatusChangeListener {
            override fun onStatusChanged(state: BaseService.State) {
                binding?.tvStatus?.text = state.name
                Log.e("",state.name)
            }

            override fun onTrafficUpdated(profileId: Long, stats: TrafficStats) {

                            }
        })
        DataStore.profileId = ProfileManager.createProfile(profile).id
        start()
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
