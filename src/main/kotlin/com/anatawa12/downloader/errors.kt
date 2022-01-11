package com.anatawa12.downloader

open class UserError(message: String, throwable: Throwable? = null) : Exception(message, throwable)

class ParsingError(message: String, file: String, line: Int) :
    UserError("parsing error at $file line $line: $message")
