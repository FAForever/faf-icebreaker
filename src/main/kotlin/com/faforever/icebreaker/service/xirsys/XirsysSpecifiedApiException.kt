package com.faforever.icebreaker.service.xirsys

import java.io.IOException

interface XirsysApiException

class XirsysSpecifiedApiException(val errorCode: String, override val message: String) :
    IOException("Xirsys API responded with error code: $errorCode"), XirsysApiException
class XirsysUnspecifiedApiException(val errorResponse: String, cause: Exception? = null) :
    IOException("Xirsys API failed with unparseable message: $errorResponse", cause), XirsysApiException
