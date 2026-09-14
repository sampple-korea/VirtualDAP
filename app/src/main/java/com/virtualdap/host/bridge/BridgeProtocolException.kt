package com.virtualdap.host.bridge

/** Malformed or unsupported data on the application-container PCM socket. */
class BridgeProtocolException(message: String) : IllegalStateException(message)
