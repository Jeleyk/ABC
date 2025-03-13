package ru.meldren.abc.processor

import ru.meldren.abc.common.AbstractCommandData
import ru.meldren.abc.common.SubcommandData

fun interface ExecutionHandler<S : Any> {

    fun execute(sender: S, input: String, commandData: AbstractCommandData, subcommandData: SubcommandData)
}