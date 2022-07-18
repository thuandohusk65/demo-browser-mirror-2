package com.nts.demobrowsermirror


import android.app.*
import android.content.*
import android.media.RingtoneManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.ironz.binaryprefs.BinaryPreferencesBuilder

import androidx.core.content.ContextCompat
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.coroutineScope
import com.afollestad.materialdialogs.MaterialDialog
import com.afollestad.materialdialogs.lifecycle.lifecycleOwner
import com.elvishew.xlog.LogLevel
import com.elvishew.xlog.XLog
import com.nhnextsoft.screenmirroring.service.AppService
import com.nhnextsoft.screenmirroring.service.ServiceMessage
import com.nts.demobrowsermirror.databinding.ActivityStreamBinding
import com.nts.demobrowsermirror.service.helper.IntentAction

import info.dvkr.screenstream.data.model.AppError
import info.dvkr.screenstream.data.model.FixableError
import info.dvkr.screenstream.data.other.asString
import info.dvkr.screenstream.data.other.getLog
import info.dvkr.screenstream.data.other.setUnderlineSpan
import info.dvkr.screenstream.data.settings.Settings
import info.dvkr.screenstream.data.settings.SettingsImpl
import info.dvkr.screenstream.data.settings.SettingsReadOnly
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import timber.log.Timber


class StreamActivity : AppCompatActivity() {

    private val clipboard: ClipboardManager? by lazy {
        ContextCompat.getSystemService(this, ClipboardManager::class.java)
    }

    private lateinit var binding: ActivityStreamBinding
    private lateinit var settings: Settings
    private val serviceMessageLiveData = MutableLiveData<ServiceMessage>()
    private var serviceMessageFlowJob: Job? = null
    private var isBound: Boolean = false
    private var isCastPermissionsPending: Boolean = false
    private var permissionsErrorDialog:
            MaterialDialog? = null
    private var isStopStream: Boolean = false
    private var isCheckedPermission: Boolean = false
    private var isCastPermissionGranted: Boolean = false

    var viewModel: StreamViewModel? = null

    companion object {
        fun newIntent(context: Context): Intent {
            return Intent(context, StreamActivity::class.java)
        }

        private const val SCREEN_CAPTURE_REQUEST_CODE = 10
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = SettingsImpl(
            BinaryPreferencesBuilder(applicationContext)
                .supportInterProcess(true)
                .exceptionHandler { ex -> Timber.e(ex) }
                .build()
        )
        binding = ActivityStreamBinding.inflate(layoutInflater)
        setContentView(binding.root)
        viewModel = ViewModelProvider(this)[StreamViewModel::class.java]
        showNotification()
        binding.imageBack.setOnClickListener {
            onBackPressed()
        }

        binding.btnStopStream.setOnClickListener {
            showDialogStopService()
        }

        binding.ivItemDeviceAddressCopy.setOnClickListener {
            clipboard?.setPrimaryClip(
                ClipData.newPlainText(
                    binding.tvItemDeviceAddress.text,
                    binding.tvItemDeviceAddress.text
                )
            )
            Toast.makeText(this, R.string.stream_fragment_copied, Toast.LENGTH_LONG).show()
        }

        binding.txGuide3.setOnClickListener {
            if(!isCastPermissionGranted) {
                Timber.d("Request CastPermission ")
                val projectionManager =
                    getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                val createScreenCaptureIntent = projectionManager.createScreenCaptureIntent()
                startActivityForResult(
                    createScreenCaptureIntent, SCREEN_CAPTURE_REQUEST_CODE//,options.toBundle()
                )
            }
        }
        initLogger()
    }

    override fun onStart() {
        super.onStart()
        val projectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        IntentAction.CastIntent(projectionManager.createScreenCaptureIntent())
            .sendToAppService(this@StreamActivity)

        serviceMessageLiveData.observe(this) { serviceMessage ->
            when (serviceMessage) {
                is ServiceMessage.ServiceState -> onServiceStateMessage(serviceMessage)
                else -> {}
            }
        }
        bindService(
            AppService.getAppServiceIntent(this),
            serviceConnection,
            Context.BIND_AUTO_CREATE
        )

    }

    override fun onStop() {
        if (isBound) {
            serviceMessageFlowJob?.cancel()
            serviceMessageFlowJob = null
            unbindService(serviceConnection)
            isBound = false
        }
        super.onStop()
    }

    override fun onDestroy() {
        super.onDestroy()
        NotificationManagerCompat.from(this).cancelAll();
    }

    override fun onBackPressed() {
        super.onBackPressed()
    }

    private fun showDialogStopService() {
        AlertDialog.Builder(this)
            .setTitle("Want to disconnect?")
            .setMessage("Connection will be interrupted.")
            .setPositiveButton(android.R.string.ok) { _, _ ->
                Timber.d("onPress Stop Stream")
                stopStreamScreen()
            }
            .setNegativeButton(android.R.string.no, null)
            .setIcon(android.R.drawable.ic_dialog_alert)
            .show()
    }

    private fun setNewPortAndReStart() {
        val newPort = (1025..65535).random()
        Timber.d("setNewPortAndReStart $newPort")
        if (settings.severPort != newPort) settings.severPort = newPort
        IntentAction.StartStream.sendToAppService(this@StreamActivity)
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder) {
            serviceMessageFlowJob =
                lifecycle.coroutineScope.launch(CoroutineName("StreamActivity.ServiceMessageFlow")) {
                    (service as AppService.AppServiceBinder).getServiceMessageFlow()
                        .onEach { serviceMessage ->
                            Timber.d("onServiceMessage $serviceMessage")
                            serviceMessageLiveData.value = serviceMessage
                        }
                        .catch { cause -> Timber.d("onServiceMessage : $cause") }
                        .collect()
                }

            isBound = true
            IntentAction.GetServiceState.sendToAppService(this@StreamActivity)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            serviceMessageFlowJob?.cancel()
            serviceMessageFlowJob = null
            isBound = false
        }
    }

    private fun showErrorDialog(
        @StringRes titleRes: Int = R.string.permission_activity_error_title,
        @StringRes messageRes: Int = R.string.permission_activity_error_unknown
    ) {
        permissionsErrorDialog?.dismiss()

        permissionsErrorDialog = MaterialDialog(this).show {
            lifecycleOwner(this@StreamActivity)
            title(titleRes)
            message(messageRes)
            positiveButton(android.R.string.ok)
            cancelable(false)
            cancelOnTouchOutside(false)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == SCREEN_CAPTURE_REQUEST_CODE) {
            if (resultCode == Activity.RESULT_OK) {
                XLog.d(getLog("onActivityResult", "Cast permission granted"))
                require(data != null) { "onActivityResult: data = null" }
                IntentAction.CastIntent(data).sendToAppService(this@StreamActivity)
                isCastPermissionGranted = true
            } else {
                XLog.w(getLog("onActivityResult", "Cast permission denied"))
                IntentAction.CastPermissionsDenied.sendToAppService(this@StreamActivity)
                isCastPermissionGranted = false
                isCastPermissionsPending = false

                showErrorDialog(
                    R.string.permission_activity_cast_permission_required_title,
                    R.string.permission_activity_cast_permission_required
                )
            }
        } else {
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    private fun checkPermission(serviceMessage: ServiceMessage.ServiceState) {
        Timber.d("checkPermission $isCheckedPermission")
        if (serviceMessage.isWaitingForPermission && !isCheckedPermission) {
            if (isCastPermissionsPending) {
                XLog.i(getLog("onServiceMessage", "Ignoring: isCastPermissionsPending == true"))
            } else {
                isCastPermissionsPending = true

                val projectionManager =
                    getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                try {
                    val createScreenCaptureIntent = projectionManager.createScreenCaptureIntent()
                    startActivityForResult(
                        createScreenCaptureIntent, SCREEN_CAPTURE_REQUEST_CODE//,options.toBundle()
                    )
                    isCheckedPermission = true
                } catch (ex: ActivityNotFoundException) {
                    isCheckedPermission = true
                    showErrorDialog(
                        R.string.permission_activity_error_title_activity_not_found,
                        R.string.permission_activity_error_activity_not_found
                    )
                }
            }
        } else {
            isCastPermissionsPending = false
        }
    }

    private fun initLogger() {
        if (BuildConfig.DEBUG) {
            XLog.init(LogLevel.ALL)
        } else {
            XLog.init(LogLevel.ERROR)
        }


    }

    private fun showError(appError: AppError?) {
        when (appError) {
            is FixableError.AddressInUseException -> setNewPortAndReStart()
            else -> {}
        }
    }

    private fun startStreamScreen() {
        IntentAction.StartStream.sendToAppService(this@StreamActivity)
        Global.IS_RUNNING_STREAM_HTTP = true
    }

    private fun stopStreamScreen() {
        IntentAction.StopStream.sendToAppService(this@StreamActivity)
        isStopStream = true
        Global.IS_RUNNING_STREAM_HTTP = false
        NotificationManagerCompat.from(this).cancelAll();
        finish()
    }

    private fun onServiceStateMessage(serviceMessage: ServiceMessage.ServiceState) {
        Timber.d("onServiceStateMessage ${serviceMessage}")
        // Interfaces
//        binding.llFragmentStreamAddresses.removeAllViews()
        checkPermission(serviceMessage)
        if (!isStopStream && serviceMessage.appError == null && !serviceMessage.isStreaming && !serviceMessage.isWaitingForPermission && !serviceMessage.isBusy) {
            startStreamScreen()
        }
        if(serviceMessage.isStreaming) {
            binding.btnStopStream.visibility = View.VISIBLE
            binding.btnStopStream.isEnabled = true
            binding.txGuide3.visibility = View.GONE
        }
        if (serviceMessage.netInterfaces.isEmpty()) {
//            with(
//                ItemDeviceAddressBinding.inflate(
//                    layoutInflater,
//                    binding.llFragmentStreamAddresses,
//                    false
//                )
//            ) {
//                tvItemDeviceAddressName.text = ""
//                binding.llFragmentStreamAddresses.addView(this.root)
//            }
        } else {
            serviceMessage.netInterfaces.sortedBy { it.address.asString() }
                .forEach { netInterface ->
                    val fullAddress =
                        "http://${netInterface.address.asString()}:${settings.severPort}"
                    binding.tvItemDeviceAddress.text = fullAddress.setUnderlineSpan()

                }
        }

        showError(serviceMessage.appError)
    }

    private fun showNotification() {
        val intent = Intent(this, App::class.java)

        val pendingIntent: PendingIntent =
            PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)

        val channelId = getString(R.string.app_name)
        val defaultSoundUri =
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val notificationBuilder =
            NotificationCompat.Builder(this, channelId)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("$channelId - Phản chiếu màn hình")
                .setContentText("Quyền riêng tư của bạn đang được bảo v...")
                .setAutoCancel(true)
                .setSound(defaultSoundUri)
                .setPriority(10)
                .setContentIntent(pendingIntent)
                .setOngoing(true)

        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Channel human readable title",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            notificationManager.createNotificationChannel(channel)
        }

        notificationManager.notify(2, notificationBuilder.build())
    }
}