/*
 * Copyright 2019 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.ConnectivityManager.NetworkCallback
import android.net.Network
import android.os.Build.VERSION
import android.os.Build.VERSION_CODES
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import com.google.common.base.Preconditions
import com.google.common.flogger.FluentLogger

/**
 * Checks whether or not there is currently a connection and if that connection is Wifi. Need to
 * call [.unregisterNetworkCallback] before it is destroyed.
 */
class NetworkConnectionChecker(context: Context) {
    private val connectionManager: ConnectivityManager
    private val networkCallback: NetworkCallback
    private val state: MutableLiveData<NetworkState?>
    private val context: Context
    private val networkStateReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            synchronized(state) {
                state.postValue(this.networkState)
            }
        }
    }

    init {
        Preconditions.checkNotNull<Context?>(
            context, "You need to pass a context to the NetworkConnectionChecker"
        )
        this.context = context
        this.connectionManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        this.networkCallback =
            object : NetworkCallback() {
                override fun onAvailable(network: Network?) {
                    synchronized(state) {
                        NetworkConnectionChecker.Companion.logger.atConfig()
                            .log("Network is available.")
                        state.postValue(
                            NetworkState.newBuilder()
                                .setConnected(true)
                                .setNetworkMetered(connectionManager.isActiveNetworkMetered())
                                .build()
                        )
                    }
                }

                override fun onLost(network: Network?) {
                    synchronized(state) {
                        NetworkConnectionChecker.Companion.logger.atConfig()
                            .log("Network is unavailable.")
                        state.postValue(
                            NetworkState.newBuilder()
                                .setConnected(false)
                                .setNetworkMetered(connectionManager.isActiveNetworkMetered())
                                .build()
                        )
                    }
                }
            }
        state = MutableLiveData<NetworkState?>()
        registerNetworkCallback()
    }

    fun addNetworkStateObserver(owner: LifecycleOwner, observer: Observer<NetworkState?>) {
        synchronized(state) {
            state.observe(owner, observer)
        }
    }

    protected val networkState: NetworkState?
        get() {
            val activeNetwork = connectionManager.getActiveNetworkInfo()
            val isConnected =
                activeNetwork != null && activeNetwork.isConnectedOrConnecting()
            val state =
                NetworkState.newBuilder()
                    .setConnected(isConnected)
                    .setNetworkMetered(connectionManager.isActiveNetworkMetered())
                    .build()
            return state
        }

    val isConnected: Boolean
        get() {
            synchronized(state) {
                return state.getValue()!!.getConnected()
            }
        }

    /**
     * Applications can skip register if they don't need register/unregister many times. Callback
     * register is done in the constructor.
     */
    fun registerNetworkCallback() {
        synchronized(state) {
            state.postValue(this.networkState)
        }
        if (VERSION.SDK_INT >= VERSION_CODES.N) {
            connectionManager.registerDefaultNetworkCallback(networkCallback)
        } else {
            context.registerReceiver(
                networkStateReceiver,
                IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION)
            )
        }
    }

    /** Note this must be called if NetworkConnectionChecker is not being used anymore.  */
    fun unregisterNetworkCallback() {
        try {
            if (VERSION.SDK_INT >= VERSION_CODES.N) {
                connectionManager.unregisterNetworkCallback(networkCallback)
            } else {
                context.unregisterReceiver(networkStateReceiver)
            }
        } catch (unregisteredCallbackException: IllegalArgumentException) {
            NetworkConnectionChecker.Companion.logger.atWarning()
                .log("Tried to unregister network callback already unregistered.")
        }
    }

    companion object {
        private val logger: FluentLogger = FluentLogger.forEnclosingClass()
    }
}
