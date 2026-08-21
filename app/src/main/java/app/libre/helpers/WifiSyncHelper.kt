package app.libre.helpers

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import app.libre.api.JsonHelper
import app.libre.db.DatabaseHolder.Database
import app.libre.extensions.TAG
import app.libre.obj.BackupFile
import app.libre.obj.PipedImportPlaylist
import app.libre.ui.dialogs.ShareDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket

object WifiSyncHelper {
    private const val SERVICE_TYPE = "_mylibretube-sync._tcp"
    private const val SERVICE_NAME = "MyLibreTubeSync"

    private var serverSocket: ServerSocket? = null
    private var isRunning = false
    private var mLocalPort = 0

    private var nsdManager: NsdManager? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var isRegistered = false

    fun start(context: Context) {
        if (isRunning) return
        isRunning = true

        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Try to bind to static port 50505 first, fallback to dynamic
                val socket = try {
                    ServerSocket(50505)
                } catch (e: Exception) {
                    ServerSocket(0)
                }
                serverSocket = socket
                mLocalPort = socket.localPort
                Log.d(TAG(), "WifiSync Server started on port $mLocalPort")

                // Start mDNS registration helper
                setupNsd(context, mLocalPort)

                while (isRunning) {
                    val clientSocket = try {
                        socket.accept()
                    } catch (e: Exception) {
                        break
                    }
                    handleClient(clientSocket)
                }
            } catch (e: Exception) {
                Log.e(TAG(), "Error running WifiSync server: $e")
            }
        }
    }

    fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            // Ignore
        }
        serverSocket = null
        unregisterNsd()
    }

    private fun handleClient(socket: Socket) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val output = PrintWriter(OutputStreamWriter(socket.getOutputStream(), "UTF-8"), true)

                val requestLine = reader.readLine() ?: return@launch
                val parts = requestLine.split(" ")
                if (parts.size < 2) return@launch
                val method = parts[0]
                val path = parts[1]

                // Consume remaining headers
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    if (line!!.isEmpty()) break
                }

                if (method == "GET" && path == "/status") {
                    sendResponse(output, 200, "text/plain", "OK")
                } else if (method == "GET" && path == "/backup") {
                    val playlistBackupJson = getPlaylistOnlyBackupJson()
                    sendResponse(output, 200, "application/json", playlistBackupJson)
                } else {
                    sendResponse(output, 404, "text/plain", "Not Found")
                }
            } catch (e: Exception) {
                Log.e(TAG(), "Error handling WifiSync client: $e")
            } finally {
                try {
                    socket.close()
                } catch (e: Exception) {
                    // Ignore
                }
            }
        }
    }

    private fun sendResponse(out: PrintWriter, statusCode: Int, contentType: String, body: String) {
        val statusMessage = when (statusCode) {
            200 -> "OK"
            404 -> "Not Found"
            else -> "Internal Server Error"
        }
        out.print("HTTP/1.1 $statusCode $statusMessage\r\n")
        out.print("Content-Type: $contentType\r\n")
        out.print("Content-Length: ${body.toByteArray(Charsets.UTF_8).size}\r\n")
        out.print("Connection: close\r\n")
        out.print("\r\n")
        out.print(body)
        out.flush()
    }

    @OptIn(ExperimentalSerializationApi::class)
    private suspend fun getPlaylistOnlyBackupJson(): String {
        val backupFile = BackupFile()
        val localPlaylists = Database.localPlaylistsDao().getAll()
        backupFile.localPlaylists = localPlaylists
        backupFile.playlists = localPlaylists.map { (playlist, playlistVideos) ->
            val videos = playlistVideos.map { item ->
                val isJioSaavn = JioSaavnHelper.isJioSaavn(item.videoId, false)
                if (isJioSaavn) {
                    val cleanId = item.videoId.removePrefix("jsa_song_")
                    val parts = cleanId.split("_")
                    val token = parts.getOrNull(1) ?: parts[0]
                    "https://www.jiosaavn.com/song/track/$token"
                } else {
                    "${ShareDialog.YOUTUBE_FRONTEND_URL}/watch?v=${item.videoId}"
                }
            }
            PipedImportPlaylist(playlist.name, "playlist", "private", videos)
        }
        return JsonHelper.json.encodeToString(backupFile)
    }

    private fun setupNsd(context: Context, port: Int) {
        nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager

        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        connectivityManager.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                registerNsd(port)
            }

            override fun onLost(network: Network) {
                unregisterNsd()
            }
        })
    }

    @Synchronized
    private fun registerNsd(port: Int) {
        if (isRegistered) return

        val serviceInfo = NsdServiceInfo().apply {
            serviceName = SERVICE_NAME
            serviceType = SERVICE_TYPE
            setPort(port)
        }

        registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(NsdServiceInfo: NsdServiceInfo) {
                isRegistered = true
                Log.d(WifiSyncHelper.TAG(), "mDNS Service registered successfully: ${NsdServiceInfo.serviceName}")
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e(WifiSyncHelper.TAG(), "mDNS Registration failed: $errorCode")
            }

            override fun onServiceUnregistered(arg0: NsdServiceInfo) {
                isRegistered = false
                Log.d(WifiSyncHelper.TAG(), "mDNS Service unregistered successfully")
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e(WifiSyncHelper.TAG(), "mDNS Unregistration failed: $errorCode")
            }
        }

        try {
            nsdManager?.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
        } catch (e: Exception) {
            Log.e(TAG(), "Error registering mDNS service: $e")
        }
    }

    @Synchronized
    private fun unregisterNsd() {
        if (!isRegistered) return
        try {
            nsdManager?.unregisterService(registrationListener)
        } catch (e: Exception) {
            Log.e(TAG(), "Error unregistering mDNS service: $e")
        }
    }

    val isRunningStatus: Boolean get() = isRunning

    fun getLocalIpAddress(): String? {
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (networkInterface.isLoopback || !networkInterface.isUp) continue
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress && address is java.net.Inet4Address) {
                        return address.hostAddress
                    }
                }
            }
        } catch (e: Exception) {
            // Ignore
        }
        return null
    }

    fun getServerAddress(): String {
        if (!isRunning) return "Stopped"
        val ip = getLocalIpAddress() ?: "Unknown IP"
        return "Running at http://$ip:$mLocalPort"
    }
}
