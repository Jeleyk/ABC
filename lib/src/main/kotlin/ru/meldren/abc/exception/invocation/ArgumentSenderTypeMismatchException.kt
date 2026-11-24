package ru.meldren.abc.exception.invocation

class ArgumentSenderTypeMismatchException(val expectedType: Class<*>? = null) : CommandInvocationException()