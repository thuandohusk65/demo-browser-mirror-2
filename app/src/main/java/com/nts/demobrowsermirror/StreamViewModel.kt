package com.nts.demobrowsermirror

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.nhnextsoft.screenmirroring.service.ServiceMessage

class StreamViewModel: ViewModel() {
    private val serviceMessageLiveData = MutableLiveData<ServiceMessage>()

    fun getServiceMessageLiveData(): LiveData<ServiceMessage> = serviceMessageLiveData
}