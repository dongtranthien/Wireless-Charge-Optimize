package com.dongtran.wirelesschargeoptimize

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import retrofit2.Call
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.POST
import java.util.*

class ForegroundService : Service() {
    private val TAG = "ForegroundService"
    private val handler = Handler(Looper.getMainLooper())
    private var isCharging = false
    private var isChargedFull = false
    private val BATTERY_LEVEL_NEED_CHARGE = 25
    private val BATTERY_LEVEL_FULL = 85
    private lateinit var mqttClient: MqttClient
    private val mqttTopic = "wirelesscharge/data"
    private var isServiceRunning = false
    private var cnt = 0
    private var waitingTemperatureDecrease = false
    private var timeStartWaitingTemperatureDecrease:Long = 0

    private var wakeLock: PowerManager.WakeLock? = null

    private var waitingCheckEsp32ChargingCnt = 0
    private var retryCheckEsp32ChargingCnt = 0
    private var isChargingControlBefore = false
    private var checkChargingSuccessful = false

    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "ForegroundServiceChannel"
        private const val NOTIFICATION_ID = 1
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service started")

        try{
            if (!isServiceRunning) {
                val notification = createNotification()

                val sharedPreferences: SharedPreferences = applicationContext.getSharedPreferences("myPrefs", Context.MODE_PRIVATE)
                val isChargingContain = sharedPreferences.contains("isCharging")
                val isChargedFullContain = sharedPreferences.contains("isChargedFull")
                val isChargingValue: Boolean = sharedPreferences.getBoolean("isCharging", false)
                val isChargedFullValue: Boolean = sharedPreferences.getBoolean("isChargedFull", false)
                if(isChargingContain && isChargedFullContain && isChargingValue != isChargedFullValue){
                    isCharging = isChargingValue
                    isChargedFull = isChargedFullValue

                    val editor: SharedPreferences.Editor = sharedPreferences.edit()
                    editor.putBoolean("isCharging", false)
                    editor.putBoolean("isChargedFull", true)
                    editor.apply()
                }
                else{
                    // Check battery
                    val batteryPercentageInit = getBatteryPercentage(applicationContext)
                    if (batteryPercentageInit < (BATTERY_LEVEL_FULL - 1)) {
                        isCharging = true
                    } else {
                        isChargedFull = true
                    }

                    // Save data to shared preferences
                    val editor: SharedPreferences.Editor = sharedPreferences.edit()
                    editor.putBoolean("isCharging", isCharging)
                    editor.putBoolean("isChargedFull", isChargedFull)
                    editor.apply()
                }

                // Start the Foreground service with a notification
                startForeground(NOTIFICATION_ID, notification)
                isServiceRunning = true

                val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MyForegroundService:WakeLock")
                wakeLock?.acquire()

                startWork()
            }
        }
        catch(e:Exception){
            e.printStackTrace()
            println("Failed: ${e.message}")
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        isServiceRunning = false
        wakeLock?.release()
        super.onDestroy()
        Log.d(TAG, "Service stopped")
    }

    private fun startWork() {
        handler.postDelayed({
            // Thực hiện công việc của dịch vụ ở đây, ví dụ:
            println("ForegroundService is running")

            val batteryPercentageNow = getBatteryPercentage(applicationContext)
            print("batteryPercentage: ")
            println(batteryPercentageNow)

            val batteryTemperature = getBatteryTemperature(applicationContext)
            println("batteryTemperature: $batteryTemperature")


            var isUpdateCharging = false
            if (isCharging) {
                if (batteryPercentageNow >= BATTERY_LEVEL_FULL) {
                    isCharging = false
                    isChargedFull = true
                    isUpdateCharging = true
                }
            } else if (isChargedFull) {
                if (batteryPercentageNow <= BATTERY_LEVEL_NEED_CHARGE) {
                    isCharging = true
                    isChargedFull = false
                    isUpdateCharging = true
                }
            }

            // Check is update charging to update to shared preferences
            if(isUpdateCharging){
                val sharedPreferences: SharedPreferences = applicationContext.getSharedPreferences("myPrefs", Context.MODE_PRIVATE)
                // Save data to shared preferences
                val editor: SharedPreferences.Editor = sharedPreferences.edit()
                editor.putBoolean("isCharging", isCharging)
                editor.putBoolean("isChargedFull", isChargedFull)
                editor.apply()
            }

            if(isCharging && batteryTemperature >= 39 && !waitingTemperatureDecrease){
                waitingTemperatureDecrease = true;
                timeStartWaitingTemperatureDecrease = System.currentTimeMillis()
            }
            if(waitingTemperatureDecrease && batteryTemperature <= 32){
                waitingTemperatureDecrease = false;
            }

            // Check if device has over temperature -> disable charge
            var isChargingControl = false
            if(isCharging && !waitingTemperatureDecrease) isChargingControl = true;
            if(isChargingControlBefore != isChargingControl){
                isChargingControlBefore = isChargingControl
                if(isChargingControl){
                    checkChargingSuccessful = true

                    waitingCheckEsp32ChargingCnt = 0
                    retryCheckEsp32ChargingCnt = 0
                }
                else{
                    checkChargingSuccessful = false
                }
            }

            if(checkChargingSuccessful){
                val status = getBatteryStatus(applicationContext)
                if (status == BatteryManager.BATTERY_STATUS_DISCHARGING) {
                    if (retryCheckEsp32ChargingCnt < 2) {
                        if (waitingCheckEsp32ChargingCnt > 2) {
                            isChargingControl = false
                            waitingCheckEsp32ChargingCnt = 0
                            retryCheckEsp32ChargingCnt++
                        } else {
                            waitingCheckEsp32ChargingCnt++
                        }
                    }
                    else{
                        checkChargingSuccessful = false
                    }
                } else {
                    checkChargingSuccessful = false
                }
            }

            Thread {
                sendTelegramMessage(cnt, isCharging, batteryPercentageNow, waitingTemperatureDecrease, isChargingControl)
                publishMqttMessage(mqttTopic, cnt, isChargingControl, batteryPercentageNow)
            }.start()
            cnt++

            startWork()
        }, 60000)
    }

    private fun createNotificationChannel() {
        Log.d(TAG, "createNotificationChannel")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationChannel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Foreground Service Channel",
                NotificationManager.IMPORTANCE_DEFAULT
            )

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(notificationChannel)
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun createNotification(): Notification {
        val notificationText = "Your Foreground Service is running."

        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Foreground Service")
            .setContentText(notificationText)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun publishMqttMessage(topic: String, cnt: Int, charging: Boolean, batteryPercentageNow: Int) {
        var isChargingControl = '0'
        if(charging){
            isChargingControl = '1'
        }
        val message = "$isChargingControl"
        try {
            // Customize the following values based on your settings
            val clientId = "phone_device_${UUID.randomUUID()}"
            val brokerUri = "ssl://o126710c.ala.us-east-1.emqxsl.com:8883"

            mqttClient = MqttClient(brokerUri, clientId, MemoryPersistence())

            val connectOptions = MqttConnectOptions()
            connectOptions.userName = "dongtran"
            connectOptions.password = "dongtran".toCharArray()

            mqttClient.connect(connectOptions)

            // Publish data
            val message = MqttMessage(message.toByteArray())
            mqttClient.publish(topic, message)

            val messageDebug = "Charge optimize - $cnt - $charging - $batteryPercentageNow"
            val messageDebugObj = MqttMessage(messageDebug.toByteArray())
            val topicDebug = "wirelesscharge/data_debug"
            mqttClient.publish(topicDebug, messageDebugObj)

            println("Message published to MQTT topic: $topic")
            mqttClient.disconnect()
        } catch (e: Exception) {
            e.printStackTrace()
            println("Failed to publish MQTT message: ${e.message}")
        }
    }

    private fun sendTelegramMessage(cnt: Int, charging: Boolean, batteryPercentageNow: Int, waitingTemperatureDecrease: Boolean, isChargingControl: Boolean) {
        try {
            val token = "5868771943:AAFy3Yzhq5sW8BpsF9WxuGPMg-hFEvQkOA8" // Replace YOUR_BOT_TOKEN with your bot's token
            val chatId = "-4051901987" // Replace YOUR_CHAT_ID with the recipient's chat ID
            val message = "Charge optimize - $cnt - $charging - $batteryPercentageNow - $waitingTemperatureDecrease -> $isChargingControl" // Your message content

            val retrofit = Retrofit.Builder()
                .baseUrl("https://api.telegram.org/bot$token/")
                .addConverterFactory(GsonConverterFactory.create())
                .build()

            val telegramAPI = retrofit.create(TelegramAPI::class.java)

            val call = telegramAPI.sendMessage(chatId, message)

            val response = call.execute()
            if (response.isSuccessful) {
                println("Message sent successfully!")
            } else {
                val errorBody = response.errorBody()?.string()
                println("Failed to send message: $errorBody")
            }
        } catch (e: Exception) {
            e.printStackTrace()
            println("Failed to send message: ${e.message}")
        }
    }

    private fun getBatteryPercentage(context: Context): Int {
        try{
            val batteryStatus: Intent? = IntentFilter(Intent.ACTION_BATTERY_CHANGED).let { ifilter ->
                context.registerReceiver(null, ifilter)
            }
            val level: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1

            return (level.toFloat() / scale.toFloat() * 100).toInt()
        }
        catch(e: Exception){
            e.printStackTrace()
            println("Failed to get battery percentage: ${e.message}")
            return 0;
        }
    }

    private fun getBatteryTemperature(context: Context): Int {
        try{
            val batteryStatus: Intent? = IntentFilter(Intent.ACTION_BATTERY_CHANGED).let { ifilter ->
                context.registerReceiver(null, ifilter)
            }
            val temperature: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1

            return (temperature/10)
        }
        catch(e: Exception){
            e.printStackTrace()
            println("Failed to get battery temperature: ${e.message}")
            return 0;
        }
    }

    private fun getBatteryStatus(context: Context): Int? {
        try{
            val batteryStatus: Intent? = IntentFilter(Intent.ACTION_BATTERY_CHANGED).let { ifilter ->
                context.registerReceiver(null, ifilter)
            }
            val status: Int? = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, 0)

            return status
        }
        catch(e: Exception){
            e.printStackTrace()
            println("Failed to get battery status: ${e.message}")
            return 0;
        }
    }
}

interface TelegramAPI {
    @FormUrlEncoded
    @POST("sendMessage")
    fun sendMessage(
        @Field("chat_id") chatId: String,
        @Field("text") text: String
    ): Call<Unit>
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        print("ksdafjad")
    }
}
