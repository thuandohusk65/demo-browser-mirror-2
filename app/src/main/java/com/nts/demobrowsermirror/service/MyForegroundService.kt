package com.nhnextsoft.screenmirroring.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.IBinder
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import com.nhnextsoft.screenmirroring.R
import com.nhnextsoft.screenmirroring.Constants
import android.app.PendingIntent
import android.widget.RemoteViews
import androidx.annotation.IdRes
import com.nhnextsoft.screenmirroring.view.activity.SplashActivity


class MyForegroundService : Service() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nameTV = intent?.getStringExtra(Constants.SERVICE_EXTRA_NAME_TV)
        val notificationIntent = Intent(this, SplashActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE)
        val channelId =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                createNotificationChannel("my_service", "My Background Service")
            } else {
                getString(R.string.channel_id)
            }

        val notificationLayout = RemoteViews(packageName, R.layout.layout_notification)
        notificationLayout.setTextViewText(R.id.text_name_tv,"Connected to $nameTV")
        notificationLayout.setOnClickPendingIntent(R.id.text_disconnect,
            onButtonNotificationClick(R.id.text_disconnect))

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Connected to $nameTV")
            .setSmallIcon(R.drawable.ic_connect)
            .setCustomContentView(notificationLayout)
            .setContentIntent(pendingIntent)
            .build()
        startForeground(1000, notification)

        return START_STICKY
    }

    private fun onButtonNotificationClick(@IdRes id: Int): PendingIntent? {
        val intent = Intent()
        intent.putExtra("", id)
        return PendingIntent.getBroadcast(this, id, intent, PendingIntent.FLAG_IMMUTABLE)
    }

//    private val receiver: BroadcastReceiver = object : BroadcastReceiver() {
//        override fun onReceive(context: Context, intent: Intent) {
//            val id = intent.getIntExtra(EXTRA_BUTTON_CLICKED, -1)
//            when (id) {
//                R.id.btnAccept -> Toast.makeText(context, "Accepted", Toast.LENGTH_SHORT).show()
//                R.id.btnDenied -> Toast.makeText(context, "Denied", Toast.LENGTH_SHORT).show()
//            }
//        }
//    }
    @RequiresApi(Build.VERSION_CODES.O)
    private fun createNotificationChannel(channelId: String, channelName: String): String {
        val chan = NotificationChannel(
            channelId,
            channelName, NotificationManager.IMPORTANCE_NONE
        )
        chan.lightColor = Color.BLUE
        chan.lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        val service = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        service.createNotificationChannel(chan)
        return channelId
    }

    override fun onBind(intent: Intent?): IBinder? = null
}