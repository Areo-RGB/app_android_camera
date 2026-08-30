package com.example.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log

class NetworkServiceDiscovery(private val context: Context) {

    private val nsdManager: NsdManager =
        context.getSystemService(Context.NSD_SERVICE) as NsdManager

    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    private var isDiscovering = false
    private var isRegistered = false

    companion object {
        private const val TAG = "MultiCamNSD"
        const val SERVICE_TYPE = "_multicam._tcp."
    }

    data class DiscoveredDirector(
        val serviceName: String,
        val host: String,
        val port: Int
    )

    fun registerDirectorService(serviceName: String, port: Int, onRegistered: (String) -> Unit) {
        val serviceInfo = NsdServiceInfo().apply {
            this.serviceName = serviceName
            this.serviceType = SERVICE_TYPE
            this.port = port
        }

        registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(NsdServiceInfo: NsdServiceInfo) {
                isRegistered = true
                Log.d(TAG, "Director service registered: ${NsdServiceInfo.serviceName}")
                onRegistered(NsdServiceInfo.serviceName)
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "Registration failed: $errorCode")
                isRegistered = false
            }

            override fun onServiceUnregistered(arg0: NsdServiceInfo) {
                isRegistered = false
                Log.d(TAG, "Director service unregistered")
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "Unregistration failed: $errorCode")
            }
        }

        try {
            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
        } catch (e: Exception) {
            Log.e(TAG, "Error registering NSD service", e)
        }
    }

    fun startDiscovery(onDirectorFound: (DiscoveredDirector) -> Unit) {
        if (isDiscovering) return

        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                isDiscovering = true
                Log.d(TAG, "NSD Discovery started")
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                Log.d(TAG, "NSD Service found: ${service.serviceName}")
                if (service.serviceType == SERVICE_TYPE || service.serviceType.contains("multicam")) {
                    resolveService(service, onDirectorFound)
                }
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                Log.d(TAG, "NSD Service lost: ${service.serviceName}")
            }

            override fun onDiscoveryStopped(serviceType: String) {
                isDiscovering = false
                Log.d(TAG, "NSD Discovery stopped")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                isDiscovering = false
                Log.e(TAG, "Discovery start failed: $errorCode")
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Discovery stop failed: $errorCode")
            }
        }

        try {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (e: Exception) {
            Log.e(TAG, "Error starting NSD discovery", e)
        }
    }

    private fun resolveService(serviceInfo: NsdServiceInfo, onResolved: (DiscoveredDirector) -> Unit) {
        nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "Resolve failed: $errorCode")
            }

            override fun onServiceResolved(resolvedInfo: NsdServiceInfo) {
                val host = resolvedInfo.host?.hostAddress ?: return
                val port = resolvedInfo.port
                Log.d(TAG, "Resolved Director: ${resolvedInfo.serviceName} at $host:$port")
                onResolved(
                    DiscoveredDirector(
                        serviceName = resolvedInfo.serviceName,
                        host = host,
                        port = port
                    )
                )
            }
        })
    }

    fun stopDiscovery() {
        if (isDiscovering && discoveryListener != null) {
            try {
                nsdManager.stopServiceDiscovery(discoveryListener)
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping discovery", e)
            }
            isDiscovering = false
            discoveryListener = null
        }
    }

    fun unregisterService() {
        if (isRegistered && registrationListener != null) {
            try {
                nsdManager.unregisterService(registrationListener)
            } catch (e: Exception) {
                Log.e(TAG, "Error unregistering service", e)
            }
            isRegistered = false
            registrationListener = null
        }
    }

    fun cleanup() {
        stopDiscovery()
        unregisterService()
    }
}
