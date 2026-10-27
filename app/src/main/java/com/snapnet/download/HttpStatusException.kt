package com.snapnet.download

import java.io.IOException

/** A response-status failure that can be classified without retaining its request URL. */
internal class HttpStatusException(val statusCode: Int) :
    IOException("Server returned HTTP $statusCode")
