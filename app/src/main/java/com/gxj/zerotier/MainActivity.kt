package com.gxj.zerotier

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.Bundle
import android.os.IBinder
import android.preference.PreferenceManager
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import net.kaaass.zerotierfix.ZerotierFixApplication
import net.kaaass.zerotierfix.events.IsServiceRunningEvent
import net.kaaass.zerotierfix.events.RequestNetworkListEvent
import net.kaaass.zerotierfix.events.RequestNodeStatusEvent
import net.kaaass.zerotierfix.events.StopEvent
import net.kaaass.zerotierfix.model.*
import net.kaaass.zerotierfix.service.ZeroTierOneService
import net.kaaass.zerotierfix.service.ZeroTierOneService.ZeroTierBinder
import net.kaaass.zerotierfix.ui.JoinNetworkFragment
import net.kaaass.zerotierfix.ui.NetworkListFragment
import net.kaaass.zerotierfix.util.Constants
import net.kaaass.zerotierfix.util.NetworkIdUtils
import org.greenrobot.eventbus.EventBus


class MainActivity : AppCompatActivity() {
    var mNetwork: Network? = null
    private var eventBus: EventBus? = null
    private var mIsBound: Boolean = false
    private var joinAfterAuth: JoinAfterAuth? = null

    private var mBoundService: ZeroTierOneService? = null

    private val mConnection: ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(componentName: ComponentName, iBinder: IBinder) {
            mBoundService = (iBinder as ZeroTierBinder).service
        }

        override fun onServiceDisconnected(componentName: ComponentName) {
            mBoundService = null
            setIsBound(false)
        }
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        this.eventBus = EventBus.getDefault()
//        eventBus?.register(this)
        eventBus?.post(RequestNetworkListEvent())
        eventBus?.post(RequestNodeStatusEvent())
        eventBus?.post(IsServiceRunningEvent.NewRequest())
        findViewById<View>(R.id.btn_connect_zerotier).setOnClickListener {
            mNetwork = joinNet("your network id")
        }

        findViewById<View>(R.id.btn_start_zerotier).setOnClickListener {
            mNetwork?.also { network ->
                startNet(network)
            }
        }


    }

    private fun joinNet(networkStr: String): Network? {
        val hexStringToLong = NetworkIdUtils.hexStringToLong("your network id")

        val daoSession = (getApplication() as ZerotierFixApplication).daoSession
        val networkDao = daoSession.networkDao
        if (!networkDao.queryBuilder().where(
                NetworkDao.Properties.NetworkId.eq(java.lang.Long.valueOf(hexStringToLong)),
                *arrayOfNulls(0)
            ).build().forCurrentThread().list().isEmpty()
        ) {
            Log.e(JoinNetworkFragment.TAG, "Network already present")
            val networkEntitys =
                daoSession.getNetworkDao().queryBuilder().orderAsc(NetworkDao.Properties.NetworkId)
                    .build().forCurrentThread().list()
            if (!networkEntitys.isNullOrEmpty()) {
                return networkEntitys[0]
            }
        }
        Log.d(
            JoinNetworkFragment.TAG,
            "Joining network $hexStringToLong"
        )
        val network = Network()
        network.networkId = java.lang.Long.valueOf(hexStringToLong)
        network.networkIdStr = networkStr
        network.useDefaultRoute = true
        network.connected = false
        val networkConfig = NetworkConfig()
        networkConfig.id = java.lang.Long.valueOf(hexStringToLong)
        networkConfig.routeViaZeroTier = true
        networkConfig.dnsMode = 0
        daoSession.networkConfigDao.insert(networkConfig)
        network.networkConfigId = hexStringToLong
        networkDao.insert(network)
        return network
    }


    fun startNet(network: Network): Boolean {
        val networkDao = (getApplication() as ZerotierFixApplication).daoSession.networkDao
        // 启动网络
        val context: Context = this
        val useCellularData = PreferenceManager
            .getDefaultSharedPreferences(context)
            .getBoolean(Constants.PREF_NETWORK_USE_CELLULAR_DATA, false)
        val activeNetworkInfo = (context
            .getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager)
            .activeNetworkInfo
        if (activeNetworkInfo == null || !activeNetworkInfo.isConnectedOrConnecting) {
            // 设备无网络
            Toast.makeText(
                this,
                net.kaaass.zerotierfix.R.string.toast_no_network,
                Toast.LENGTH_SHORT
            ).show()
            return false
        } else if (useCellularData || !(activeNetworkInfo == null || activeNetworkInfo.type == 0)) {
            // 可以连接至网络
            // 先关闭所有现有网络连接
//                    for (network in thismNetworks) {
//                        if (network.connected) {
//                            network.connected = false
//                        }
//                        network.lastActivated = false
//                        network.update()
//                    }
            stopService()
            // 连接目标网络
            if (!isBound()) {
                sendStartServiceIntent(
                    network.getNetworkId(),
                    network.getUseDefaultRoute()
                )
            } else {
                mBoundService?.joinNetwork(
                    network.getNetworkId(),
                    network.getUseDefaultRoute()
                )
            }
            Log.d(
                NetworkListFragment.TAG,
                "Joining Network: " + network.getNetworkIdStr()
            )
            network.setConnected(true)
            network.setLastActivated(true)
            networkDao.save(network)
        } else {
            // 移动数据且未确认
            Toast.makeText(
                this,
                net.kaaass.zerotierfix.R.string.toast_mobile_data,
                Toast.LENGTH_SHORT
            ).show()
            return false
        }
        return true
    }

    fun stopNet(network: Network) {
        val networkDao = (getApplication() as ZerotierFixApplication).daoSession.networkDao
        // 关闭网络
        Log.d(
            NetworkListFragment.TAG,
            "Leaving Leaving Network: " + network.getNetworkIdStr()
        )
        if (!(!isBound() || mBoundService == null || network == null)) {
            mBoundService?.leaveNetwork(network.getNetworkId())
            doUnbindService()
        }
        stopService()
        network.setConnected(false)
        networkDao.save(network)
    }


    private fun sendStartServiceIntent(networkId: Long, useDefaultRoute: Boolean) {
        val prepare = VpnService.prepare(this)
        if (prepare != null) {
            this.joinAfterAuth = JoinAfterAuth(networkId, useDefaultRoute)
            startActivityForResult(prepare, 3)
            return
        }
        Log.d(NetworkListFragment.TAG, "Intent is NULL.  Already approved.")
        startService(networkId, useDefaultRoute)
    }

    private fun startService(networkId: Long, useDefaultRoute: Boolean) {
        val intent = Intent(this, ZeroTierOneService::class.java)
        intent.putExtra(ZeroTierOneService.ZT1_NETWORK_ID, networkId)
        intent.putExtra(ZeroTierOneService.ZT1_USE_DEFAULT_ROUTE, useDefaultRoute)
        doBindService()
        startService(intent)
    }

    fun doBindService() {
        if (!isBound()) {
            if (bindService(
                    Intent(this, ZeroTierOneService::class.java),
                    this.mConnection,
                    BIND_NOT_FOREGROUND or BIND_DEBUG_UNBIND
                )
            ) {
                setIsBound(true)
            }
        }
    }

    private fun stopService() {
        mBoundService?.stopZeroTier()
        val intent = Intent(this, ZeroTierOneService::class.java)
        this.eventBus?.post(StopEvent())
        if (!stopService(intent)) {
            Log.e(NetworkListFragment.TAG, "stopService() returned false")
        }
        doUnbindService()
    }

    fun doUnbindService() {
        if (isBound()) {
            try {
                unbindService(this.mConnection)
            } catch (e: Exception) {
                Log.e(NetworkListFragment.TAG, "", e)
            } catch (th: Throwable) {
                setIsBound(false)
                throw th
            }
            setIsBound(false)
        }
    }

    @Synchronized
    fun setIsBound(z: Boolean) {
        mIsBound = z
    }

    @Synchronized
    fun isBound(): Boolean {
        return mIsBound
    }

    override fun onDestroy() {
        super.onDestroy()
        eventBus!!.unregister(this)
    }

}