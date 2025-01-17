package com.jessecorbett.diskord.testbot

import com.jessecorbett.diskord.bot.bot
import com.jessecorbett.diskord.bot.classicCommands
import com.jessecorbett.diskord.bot.events
import com.jessecorbett.diskord.util.sendMessage

// Hack to get node envvars
external val process: Process
external interface Process {
    val env: dynamic
}

suspend fun main() {
    bot(process.env.DISKORD_JS_BOT.unsafeCast<String>()) {
        events {
            var started = false
            onReady {
                if (!started) {
                    channel("547517051556855808").sendMessage("Diskord JS bot has started")
                }
                setStatus("Making sure JS runtime works")
                started = true
            }
        }

        classicCommands {
            command("js") {
                it.respondAndDelete("JS bot is working!")
            }
        }
    }
}
